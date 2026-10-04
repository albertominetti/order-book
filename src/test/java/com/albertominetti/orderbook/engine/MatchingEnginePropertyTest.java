package com.albertominetti.orderbook.engine;

import com.albertominetti.orderbook.domain.OrderStatus;
import com.albertominetti.orderbook.domain.OrderType;
import com.albertominetti.orderbook.domain.OrderView;
import com.albertominetti.orderbook.domain.PriceLevel;
import com.albertominetti.orderbook.domain.Side;
import com.albertominetti.orderbook.domain.Trade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property based invariants of the matching engine, driven by a seeded {@link Random}.
 *
 * <p>Every run replays the very same random order stream, so a failure is always reproducible:
 * the seed is part of the assertion message. Each submission is checked immediately, which
 * pins down the first operation that breaks an invariant instead of only the final state.</p>
 *
 * <p>The generated stream mixes both sides on a small grid of limit prices (so random orders
 * cross most of the time) with quantities between 1 and 10 and one MARKET order out of ten.
 * {@value #ORDERS_PER_SEED} orders per seed keep the number of fills (and therefore the trade
 * tape) well below {@link MatchingEngine#MAX_RECENT_TRADES}, so nothing is ever evicted and the
 * whole history stays comparable.</p>
 */
class MatchingEnginePropertyTest {

    /** Frozen clock so timestamps play no role in the invariants. */
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    /** Five deterministic seeds: the same five streams are replayed on every run. */
    private static final long[] SEEDS = {42L, 1_337L, 20_240_101L, 987_654_321L, 5_555_555L};

    /** Small grid of limit prices, so random sides cross each other often. */
    private static final List<BigDecimal> LIMIT_PRICES = List.of(
            new BigDecimal("99.50"),
            new BigDecimal("100.00"),
            new BigDecimal("100.25"),
            new BigDecimal("100.50"),
            new BigDecimal("101.00"),
            new BigDecimal("101.50"));

    /** Orders generated per seed: a random stream of this size stays around {@value #MAX_GENERATED_TRADES} fills. */
    private static final int ORDERS_PER_SEED = 900;

    /** Upper bound on the fills of one run: the trade tape must never overflow. */
    private static final int MAX_GENERATED_TRADES = 900;

    /** Lower bound on the fills of one run, so the invariants are never checked on an empty tape. */
    private static final int MIN_GENERATED_TRADES = 100;

    /** What one random run produced, so every test can assert on a different aspect of it. */
    private record Scenario(
            long seed,
            MatchingEngine engine,
            int submittedOrders,
            int generatedTrades,
            List<Trade> trades,
            BigDecimal lowestLimitPrice,
            BigDecimal highestLimitPrice,
            int restingOrders,
            BigDecimal restingQuantity,
            List<UUID> orderIds,
            List<OrderType> orderTypes
    ) {
    }

    @Nested
    @DisplayName("invariants of a random order stream")
    class Invariants {

        @Test
        @DisplayName("The book is never crossed and every quantity is conserved, seed after seed")
        void randomStreamsKeepTheBookConsistent() {
            for (long seed : SEEDS) {
                Scenario scenario = runScenario(seed);

                assertThat(scenario.submittedOrders()).as("orders submitted, seed %d", seed).isEqualTo(ORDERS_PER_SEED);
                // The run really traded, otherwise the checks of runScenario would be vacuous.
                assertThat(scenario.generatedTrades()).as("trades generated, seed %d", seed)
                        .isBetween(MIN_GENERATED_TRADES, MAX_GENERATED_TRADES);
            }
        }

        @Test
        @DisplayName("Every trade happens at the price of a submitted limit order")
        void tradePricesAreAlwaysASubmittedLimitPrice() {
            Scenario scenario = runScenario(SEEDS[0]);

            assertThat(scenario.trades()).isNotEmpty();
            assertThat(scenario.trades()).extracting(Trade::price)
                    .allSatisfy(price -> assertThat(price)
                            .isIn(LIMIT_PRICES)
                            .isBetween(scenario.lowestLimitPrice(), scenario.highestLimitPrice()));
            // The whole grid gets used, so the range really is the range of the limit prices.
            assertThat(scenario.lowestLimitPrice()).isEqualByComparingTo(LIMIT_PRICES.get(0));
            assertThat(scenario.highestLimitPrice()).isEqualByComparingTo(LIMIT_PRICES.get(LIMIT_PRICES.size() - 1));
        }

        @Test
        @DisplayName("A long random run never overflows the trade tape")
        void tradeTapeNeverOverflows() {
            Scenario scenario = runScenario(SEEDS[1]);

            assertThat(scenario.generatedTrades()).isLessThanOrEqualTo(MatchingEngine.MAX_RECENT_TRADES);
            // Nothing was evicted: the tape still holds every single trade of the run.
            assertThat(scenario.engine().recentTrades(MatchingEngine.MAX_RECENT_TRADES))
                    .hasSize(scenario.generatedTrades());
        }

        @Test
        @DisplayName("restingOrderCount agrees with the aggregated snapshot at the end of a run")
        void restingOrderCountMatchesTheAggregatedSnapshot() {
            Scenario scenario = runScenario(SEEDS[2]);
            MatchingEngine.BookSnapshot snapshot = scenario.engine().snapshot();

            int levelOrderCount = levelOrderCount(snapshot.bids()) + levelOrderCount(snapshot.asks());

            assertThat(scenario.engine().restingOrderCount())
                    .isEqualTo(scenario.restingOrders())
                    .isEqualTo(levelOrderCount);
            assertThat(bookQuantity(snapshot)).isEqualByComparingTo(scenario.restingQuantity());
            assertNotCrossed(snapshot);
        }

        @Test
        @DisplayName("Every order and trade of a random run carries the instrument symbol")
        void everythingCarriesTheInstrumentSymbol() {
            Scenario scenario = runScenario(SEEDS[3]);

            assertThat(scenario.trades()).allSatisfy(trade -> {
                assertThat(trade.symbol()).isEqualTo("PROP");
                assertThat(trade.buyOrderId()).isIn(scenario.orderIds());
                assertThat(trade.sellOrderId()).isIn(scenario.orderIds());
                assertThat(trade.quantity().signum()).isPositive();
            });

            for (int i = 0; i < scenario.orderIds().size(); i++) {
                OrderView stored = scenario.engine().findOrder(scenario.orderIds().get(i));
                assertThat(stored.symbol()).isEqualTo("PROP");
                if (scenario.orderTypes().get(i) == OrderType.MARKET) {
                    // A MARKET order never rests: it always ends FILLED or CANCELLED.
                    assertThat(stored.status().isTerminal())
                            .as("market order %s is %s", stored.id(), stored.status())
                            .isTrue();
                    assertThat(stored.status()).isIn(OrderStatus.FILLED, OrderStatus.CANCELLED);
                }
            }
        }
    }

    // ------------------------------------------------------------------ the random stream

    /**
     * Replays one random order stream and checks every invariant after every single submission.
     *
     * @return what the run produced, so each test can assert on a different aspect of it
     */
    private Scenario runScenario(long seed) {
        MatchingEngine engine = new MatchingEngine("PROP", FIXED_CLOCK);
        Random random = new Random(seed);

        List<UUID> orderIds = new ArrayList<>(ORDERS_PER_SEED);
        List<OrderType> orderTypes = new ArrayList<>(ORDERS_PER_SEED);
        List<Trade> trades = new ArrayList<>();
        BigDecimal tradedQuantity = BigDecimal.ZERO;
        BigDecimal lowestLimitPrice = null;
        BigDecimal highestLimitPrice = null;
        BigDecimal restingTotal = BigDecimal.ZERO;
        int restingOrders = 0;

        for (int i = 0; i < ORDERS_PER_SEED; i++) {
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            // One order out of ten is a MARKET order, which always crosses.
            OrderType type = random.nextInt(10) == 0 ? OrderType.MARKET : OrderType.LIMIT;
            BigDecimal price = LIMIT_PRICES.get(random.nextInt(LIMIT_PRICES.size()));
            BigDecimal quantity = BigDecimal.valueOf(random.nextInt(10) + 1L);

            if (type == OrderType.LIMIT) {
                lowestLimitPrice = lowestLimitPrice == null ? price : lowestLimitPrice.min(price);
                highestLimitPrice = highestLimitPrice == null ? price : highestLimitPrice.max(price);
            }

            MatchResult result = engine.submit(side, type, type == OrderType.LIMIT ? price : null, quantity);
            orderIds.add(result.order().id());
            orderTypes.add(type);

            // (2) the submitted order is internally consistent.
            OrderView order = result.order();
            assertThat(order.symbol()).as("seed %d, order %d", seed, i).isEqualTo("PROP");
            assertThat(order.filledQuantity().add(order.remainingQuantity()))
                    .as("seed %d, order %d, filled + remaining", seed, i)
                    .isEqualByComparingTo(order.quantity());
            assertThat(order.filledQuantity().signum()).as("filled quantity is never negative").isNotNegative();
            assertThat(order.remainingQuantity().signum()).as("remaining quantity is never negative").isNotNegative();
            assertThat(order.remainingQuantity()).isLessThanOrEqualTo(order.quantity());
            assertThat(order.price()).isEqualTo(type == OrderType.LIMIT ? price : null);

            // (5) every trade of this submission stays inside the range of the limit prices seen so far.
            for (Trade trade : result.trades()) {
                trades.add(trade);
                tradedQuantity = tradedQuantity.add(trade.quantity());
                assertThat(trade.symbol()).as("seed %d, order %d", seed, i).isEqualTo("PROP");
                assertThat(trade.quantity().signum()).as("traded quantity is positive").isPositive();
                assertThat(trade.buyOrderId()).isNotEqualTo(trade.sellOrderId());
                assertThat(trade.price()).as("seed %d, order %d, trade price", seed, i)
                        .isBetween(lowestLimitPrice, highestLimitPrice)
                        .isIn(LIMIT_PRICES);
            }

            // (3) and (4) global reconciliation: every traded unit is filled on both sides, and the
            // quantity still open on the orders is exactly what the aggregated book reports.
            BigDecimal filledTotal = BigDecimal.ZERO;
            restingTotal = BigDecimal.ZERO;
            restingOrders = 0;
            for (UUID id : orderIds) {
                OrderView stored = engine.findOrder(id);
                assertThat(stored.filledQuantity().add(stored.remainingQuantity()))
                        .as("seed %d, order %d, filled + remaining", seed, i)
                        .isEqualByComparingTo(stored.quantity());
                filledTotal = filledTotal.add(stored.filledQuantity());
                if (!stored.status().isTerminal()) {
                    restingTotal = restingTotal.add(stored.remainingQuantity());
                    restingOrders++;
                }
            }
            assertThat(filledTotal).as("seed %d, order %d, filled = 2 x traded", seed, i)
                    .isEqualByComparingTo(tradedQuantity.multiply(BigDecimal.TWO));

            MatchingEngine.BookSnapshot snapshot = engine.snapshot();
            // (1) the book is never crossed.
            assertNotCrossed(snapshot);
            assertThat(bookQuantity(snapshot)).as("seed %d, order %d, resting quantity", seed, i)
                    .isEqualByComparingTo(restingTotal);
        }

        // (3) one last time, on the whole history.
        BigDecimal filledTotal = BigDecimal.ZERO;
        for (UUID id : orderIds) {
            filledTotal = filledTotal.add(engine.findOrder(id).filledQuantity());
        }
        assertThat(filledTotal).as("seed %d, final filled total", seed)
                .isEqualByComparingTo(tradedQuantity.multiply(BigDecimal.TWO));

        MatchingEngine.BookSnapshot finalSnapshot = engine.snapshot();
        assertNotCrossed(finalSnapshot);
        assertThat(finalSnapshot.lastPrice()).isNotNull();

        return new Scenario(seed, engine, ORDERS_PER_SEED, trades.size(), trades,
                lowestLimitPrice, highestLimitPrice,
                restingOrders, restingTotal, orderIds, orderTypes);
    }

    // ------------------------------------------------------------------ helpers

    /** A book is valid only when the best bid is strictly below the best ask. */
    private static void assertNotCrossed(MatchingEngine.BookSnapshot snapshot) {
        if (!snapshot.bids().isEmpty() && !snapshot.asks().isEmpty()) {
            assertThat(snapshot.bestBid().compareTo(snapshot.bestAsk()))
                    .as("book is crossed: %s bids against %s asks", snapshot.bestBid(), snapshot.bestAsk())
                    .isNegative();
        }
    }

    private static BigDecimal bookQuantity(MatchingEngine.BookSnapshot snapshot) {
        return totalQuantity(snapshot.bids()).add(totalQuantity(snapshot.asks()));
    }

    private static BigDecimal totalQuantity(List<PriceLevel> levels) {
        BigDecimal total = BigDecimal.ZERO;
        for (PriceLevel level : levels) {
            total = total.add(level.quantity());
        }
        return total;
    }

    private static int levelOrderCount(List<PriceLevel> levels) {
        int count = 0;
        for (PriceLevel level : levels) {
            count += level.orderCount();
        }
        return count;
    }
}
