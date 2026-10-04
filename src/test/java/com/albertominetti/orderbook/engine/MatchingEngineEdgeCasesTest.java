package com.albertominetti.orderbook.engine;

import com.albertominetti.orderbook.domain.OrderStatus;
import com.albertominetti.orderbook.domain.OrderType;
import com.albertominetti.orderbook.domain.PriceLevel;
import com.albertominetti.orderbook.domain.Side;
import com.albertominetti.orderbook.domain.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edge cases of the matching engine: trade tape eviction, loss of time priority after a cancel,
 * multi level sweeps and deterministic timestamps.
 */
class MatchingEngineEdgeCasesTest {

    /** Frozen clock so timestamps are deterministic. */
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    /** The instant the frozen clock always returns. */
    private static final Instant FROZEN_INSTANT = Instant.parse("2024-01-01T00:00:00Z");

    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine("EDGE", FIXED_CLOCK);
    }

    // ------------------------------------------------------------------ helpers

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private MatchResult buy(String price, String quantity) {
        return engine.submit(Side.BUY, OrderType.LIMIT, bd(price), bd(quantity));
    }

    private MatchResult sell(String price, String quantity) {
        return engine.submit(Side.SELL, OrderType.LIMIT, bd(price), bd(quantity));
    }

    private MatchResult marketBuy(String quantity) {
        return engine.submit(Side.BUY, OrderType.MARKET, null, bd(quantity));
    }

    // ------------------------------------------------------------------ trade tape

    @Nested
    @DisplayName("the trade tape is a bounded window over the most recent trades")
    class TradeTapeEviction {

        @Test
        @DisplayName("Only the newest MAX_RECENT_TRADES trades are kept, the oldest ones are dropped")
        void oldestTradesAreEvicted() {
            int totalTrades = MatchingEngine.MAX_RECENT_TRADES + 250;
            List<UUID> generated = new ArrayList<>(totalTrades);

            // One sell and one buy of a single unit each: exactly one trade per pair.
            for (int i = 0; i < totalTrades; i++) {
                sell("100.00", "1");
                generated.add(buy("100.00", "1").trades().get(0).id());
            }

            List<Trade> kept = engine.recentTrades(MatchingEngine.MAX_RECENT_TRADES);

            assertThat(kept).hasSize(MatchingEngine.MAX_RECENT_TRADES);

            // Newest first, and exactly the newest trades.
            List<UUID> newestFirst = new ArrayList<>(generated);
            Collections.reverse(newestFirst);
            assertThat(kept).extracting(Trade::id)
                    .containsExactlyElementsOf(newestFirst.subList(0, MatchingEngine.MAX_RECENT_TRADES));

            // The trades that fell out of the window are gone.
            List<UUID> evicted = new ArrayList<>(generated.subList(0, totalTrades - MatchingEngine.MAX_RECENT_TRADES));
            assertThat(evicted).isNotEmpty();
            assertThat(kept).extracting(Trade::id).doesNotContainAnyElementsOf(evicted);

            // The window never grows back: a smaller limit still works and is a prefix of the window.
            assertThat(engine.recentTrades(5)).extracting(Trade::id)
                    .containsExactlyElementsOf(newestFirst.subList(0, 5));
            assertThat(engine.recentTrades(1)).extracting(Trade::id)
                    .containsExactly(newestFirst.get(0));
        }

        @Test
        @DisplayName("A trade tape that does not overflow keeps every trade of the run")
        void aShortRunKeepsEveryTrade() {
            List<UUID> generated = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                sell("100.00", "2");
                generated.add(buy("100.00", "2").trades().get(0).id());
            }

            List<UUID> oldestFirst = new ArrayList<>(generated);
            Collections.reverse(oldestFirst);

            assertThat(engine.recentTrades(MatchingEngine.MAX_RECENT_TRADES))
                    .extracting(Trade::id)
                    .containsExactlyElementsOf(oldestFirst);
            assertThat(engine.snapshot().lastPrice()).isEqualByComparingTo("100.00");
        }
    }

    // ------------------------------------------------------------------ time priority

    @Nested
    @DisplayName("time priority is rebuilt from the moment an order rests")
    class ReAddedOrderLosesPriority {

        @Test
        @DisplayName("A cancelled sell re-added at the same price is filled after the older one")
        void reAddedSellLosesTimePriority() {
            MatchResult first = sell("100.00", "3");
            MatchResult second = sell("100.00", "3");

            engine.cancel(first.order().id());
            MatchResult readded = sell("100.00", "3");

            MatchResult aggressor = buy("100.00", "6");

            // B is older than A2, so it is consumed first even though A was cancelled.
            assertThat(aggressor.trades()).hasSize(2);
            assertThat(aggressor.trades()).extracting(Trade::sellOrderId)
                    .containsExactly(second.order().id(), readded.order().id());
            assertThat(aggressor.trades()).extracting(Trade::quantity)
                    .containsExactly(bd("3"), bd("3"));
            assertThat(aggressor.order().status()).isEqualTo(OrderStatus.FILLED);

            assertThat(engine.findOrder(first.order().id()).status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(engine.findOrder(second.order().id()).status()).isEqualTo(OrderStatus.FILLED);
            assertThat(engine.findOrder(readded.order().id()).status()).isEqualTo(OrderStatus.FILLED);
            assertThat(engine.snapshot().asks()).isEmpty();
            assertThat(engine.restingOrderCount()).isZero();
        }
    }

    // ------------------------------------------------------------------ multi level sweep

    @Nested
    @DisplayName("a multi level sweep ends with a partial fill on the last level")
    class MultiLevelSweep {

        @Test
        @DisplayName("A market buy consumes three ask levels and leaves 2 left at 102")
        void marketBuyPartiallyFillsTheLastLevel() {
            sell("100.00", "2");
            sell("101.00", "3");
            sell("102.00", "4");

            MatchResult aggressor = marketBuy("7");

            assertThat(aggressor.trades()).hasSize(3);
            assertThat(aggressor.trades()).extracting(Trade::price, Trade::quantity)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(bd("100.00"), bd("2")),
                            org.assertj.core.groups.Tuple.tuple(bd("101.00"), bd("3")),
                            org.assertj.core.groups.Tuple.tuple(bd("102.00"), bd("2")));
            assertThat(aggressor.trades()).extracting(Trade::symbol).containsOnly("EDGE");
            assertThat(aggressor.order().status()).isEqualTo(OrderStatus.FILLED);
            assertThat(aggressor.order().filledQuantity()).isEqualByComparingTo("7");

            // Only the tail of the 102 level is still resting.
            assertThat(engine.snapshot().asks()).containsExactly(new PriceLevel(bd("102.00"), bd("2"), 1));
            assertThat(engine.snapshot().bids()).isEmpty();
            assertThat(engine.snapshot().bestAsk()).isEqualByComparingTo("102.00");
            assertThat(engine.snapshot().lastPrice()).isEqualByComparingTo("102.00");
            assertThat(engine.restingOrderCount()).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ timestamps

    @Nested
    @DisplayName("a fixed clock makes every timestamp deterministic")
    class FixedClock {

        @Test
        @DisplayName("An order and the trade it generates carry exactly the instant of the clock")
        void orderAndTradeCarryTheSameInstant() {
            MatchResult resting = sell("100.00", "2");
            MatchResult aggressor = buy("100.00", "2");

            assertThat(resting.order().timestamp()).isEqualTo(FROZEN_INSTANT);
            assertThat(aggressor.order().timestamp()).isEqualTo(FROZEN_INSTANT);
            assertThat(engine.findOrder(resting.order().id()).timestamp()).isEqualTo(FROZEN_INSTANT);

            Trade trade = aggressor.trades().get(0);
            assertThat(trade.timestamp()).isEqualTo(FROZEN_INSTANT);
            assertThat(trade.timestamp()).isEqualTo(aggressor.order().timestamp());
        }

        @Test
        @DisplayName("A frozen clock never advances, even for orders submitted much later")
        void clockDoesNotAdvanceBetweenOrders() {
            sell("100.00", "1");
            sell("101.00", "1");
            MatchResult aggressor = marketBuy("1");

            assertThat(engine.findOrder(aggressor.order().id()).timestamp()).isEqualTo(FROZEN_INSTANT);
            assertThat(aggressor.trades()).hasSize(1);
            assertThat(aggressor.trades().get(0).timestamp()).isEqualTo(FROZEN_INSTANT);
        }
    }
}