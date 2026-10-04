package com.albertominetti.orderbook.service;

import com.albertominetti.orderbook.domain.OrderStatus;
import com.albertominetti.orderbook.domain.OrderType;
import com.albertominetti.orderbook.domain.Side;
import com.albertominetti.orderbook.engine.MatchResult;
import com.albertominetti.orderbook.engine.MatchingEngine;
import com.albertominetti.orderbook.exception.UnknownInstrumentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the registry: one engine per symbol, created lazily, and the isolation
 * guarantee that only orders of the same instrument can ever match.
 */
class MarketRegistryTest {

    private MarketRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new MarketRegistry(Clock.systemUTC());
    }

    // ------------------------------------------------------------------ registry

    @Test
    @DisplayName("engineFor creates the book of a new instrument on first use")
    void engineForCreatesLazily() {
        MatchingEngine engine = registry.engineFor("BTC-USD");

        assertThat(engine.symbol()).isEqualTo("BTC-USD");
        assertThat(registry.symbols()).containsExactly("BTC-USD");
        assertThat(registry.instrumentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("engineFor returns the very same instance for a symbol, whatever its case")
    void engineForIsIdempotentAndNormalized() {
        MatchingEngine first = registry.engineFor("BTC-USD");
        MatchingEngine again = registry.engineFor(" btc-usd ");
        MatchingEngine different = registry.engineFor("ETH-USD");

        assertThat(again).isSameAs(first);
        assertThat(different).isNotSameAs(first);
        assertThat(registry.symbols()).containsExactly("BTC-USD", "ETH-USD");
        assertThat(registry.engines()).hasSize(2);
    }

    @Test
    @DisplayName("engineOrThrow resolves a known instrument and rejects an unknown one")
    void engineOrThrow() {
        MatchingEngine engine = registry.engineFor("BTC-USD");

        assertThat(registry.engineOrThrow("btc-usd")).isSameAs(engine);

        assertThatThrownBy(() -> registry.engineOrThrow("DOGE"))
                .isInstanceOf(UnknownInstrumentException.class)
                .hasMessage("unknown instrument 'DOGE'");
    }

    @Test
    @DisplayName("engineFor rejects a malformed symbol without creating an instrument")
    void malformedSymbolIsRejected() {
        assertThatThrownBy(() -> registry.engineFor("BT/USD"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.engineFor("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("symbol is required");
        assertThatThrownBy(() -> registry.engineFor(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(registry.instrumentCount()).isZero();
    }

    @Test
    @DisplayName("Concurrent first submissions of a new symbol publish a single engine")
    void concurrentCreationPublishesOneEngine() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        try {
            List<Callable<MatchingEngine>> tasks = IntStream.range(0, threads)
                    .<Callable<MatchingEngine>>mapToObj(i -> () -> {
                        startLine.await();
                        return registry.engineFor("BTC-USD");
                    })
                    .toList();

            List<Future<MatchingEngine>> futures = tasks.stream().map(pool::submit).toList();
            startLine.countDown();

            List<MatchingEngine> engines = new java.util.ArrayList<>();
            for (Future<MatchingEngine> future : futures) {
                engines.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(engines).allSatisfy(engine -> assertThat(engine).isSameAs(engines.get(0)));
            assertThat(registry.instrumentCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ isolation

    @Test
    @DisplayName("Orders of two symbols never match, even at the same price")
    void twoSymbolsNeverCross() {
        MatchingEngine btc = registry.engineFor("BTC-USD");
        MatchingEngine eth = registry.engineFor("ETH-USD");

        MatchResult btcSell = limit(btc, Side.SELL, "100.00", "5");
        MatchResult ethBuy = limit(eth, Side.BUY, "100.00", "5");

        // The aggressive order rests, it did not hit the ask of the other instrument.
        assertThat(btcSell.trades()).isEmpty();
        assertThat(ethBuy.trades()).isEmpty();
        assertThat(ethBuy.order().status()).isEqualTo(OrderStatus.NEW);
        assertThat(ethBuy.order().symbol()).isEqualTo("ETH-USD");

        assertThat(btc.snapshot().asks()).hasSize(1);
        assertThat(btc.snapshot().bids()).isEmpty();
        assertThat(btc.recentTrades(10)).isEmpty();
        assertThat(eth.snapshot().bids()).hasSize(1);
        assertThat(eth.snapshot().asks()).isEmpty();
        assertThat(eth.recentTrades(10)).isEmpty();

        // A market order on ETH still cannot touch the BTC book.
        MatchResult marketBuy = market(eth, Side.BUY, "5");
        assertThat(marketBuy.trades()).isEmpty();
        assertThat(marketBuy.order().status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("Orders of the same symbol match")
    void sameSymbolCrosses() {
        MatchingEngine btc = registry.engineFor("BTC-USD");
        MatchingEngine sameBtc = registry.engineFor("btc-usd");

        MatchResult sell = limit(btc, Side.SELL, "100.00", "5");
        MatchResult buy = limit(sameBtc, Side.BUY, "100.00", "5");

        assertThat(buy.trades()).hasSize(1);
        assertThat(buy.trades().get(0).symbol()).isEqualTo("BTC-USD");
        assertThat(buy.trades().get(0).sellOrderId()).isEqualTo(sell.order().id());
        assertThat(buy.order().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(sameBtc.recentTrades(10)).hasSize(1);
    }

    @Test
    @DisplayName("Orders of the same symbol match across concurrent engines of that symbol")
    void everyOrderOfASymbolReachesTheSameBook() {
        MatchingEngine engine = registry.engineFor("BTC-USD");
        limit(engine, Side.SELL, "100.00", "3");

        // Whatever engine instance the caller goes through, the book is the same one.
        assertThat(registry.engineOrThrow("BTC-USD").snapshot().asks()).hasSize(1);
        assertThat(limit(registry.engineFor("BTC-USD"), Side.BUY, "100.00", "3").trades()).hasSize(1);
        assertThat(engine.restingOrderCount()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private static MatchResult limit(MatchingEngine engine, Side side, String price, String quantity) {
        return engine.submit(side, OrderType.LIMIT, new BigDecimal(price), new BigDecimal(quantity));
    }

    private static MatchResult market(MatchingEngine engine, Side side, String quantity) {
        return engine.submit(side, OrderType.MARKET, null, new BigDecimal(quantity));
    }
}
