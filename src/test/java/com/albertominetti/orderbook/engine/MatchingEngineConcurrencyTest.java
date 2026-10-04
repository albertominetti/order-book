package com.albertominetti.orderbook.engine;

import com.albertominetti.orderbook.domain.OrderType;
import com.albertominetti.orderbook.domain.PriceLevel;
import com.albertominetti.orderbook.domain.Side;
import com.albertominetti.orderbook.domain.Trade;
import com.albertominetti.orderbook.service.MarketRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency behaviour of the matching engine: instruments stay isolated while many threads
 * trade at the same time, and a reader always observes a consistent book while matching runs.
 *
 * <p>Assertions made inside a worker thread cannot fail the test, so the readers collect what
 * they see and the test asserts on the collected evidence. Every run is bounded: threads are
 * stopped by a flag and by a latch, never by a sleep alone.</p>
 */
class MatchingEngineConcurrencyTest {

    /** Instruments traded in parallel. */
    private static final List<String> SYMBOLS = List.of("BTC-USD", "ETH-USD", "SOL-USD", "XRP-USD");

    /** Small grid of limit prices, so the random streams of the threads cross each other. */
    private static final List<BigDecimal> PRICES = List.of(
            new BigDecimal("99.00"),
            new BigDecimal("100.00"),
            new BigDecimal("100.50"),
            new BigDecimal("101.00"),
            new BigDecimal("102.00"));

    // ------------------------------------------------------------------ isolation

    @Nested
    @DisplayName("cross instrument isolation under load")
    class CrossInstrumentIsolation {

        @Test
        @DisplayName("Concurrent orders on four symbols never mix instruments")
        void concurrentOrdersNeverMixInstruments() throws Exception {
            MarketRegistry registry = new MarketRegistry(Clock.systemUTC());
            int threads = 6;
            int ordersPerThread = 120;

            // symbol -> id of every order submitted on it, with its original quantity.
            Map<String, Map<UUID, BigDecimal>> submitted = new ConcurrentHashMap<>();
            SYMBOLS.forEach(symbol -> submitted.put(symbol, new ConcurrentHashMap<>()));

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch startLine = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(threads);
            try {
                for (int t = 0; t < threads; t++) {
                    long threadSeed = 7_000L + t;
                    pool.submit(() -> {
                        Random random = new Random(threadSeed);
                        await(startLine);
                        for (int i = 0; i < ordersPerThread; i++) {
                            String symbol = SYMBOLS.get(random.nextInt(SYMBOLS.size()));
                            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
                            BigDecimal price = PRICES.get(random.nextInt(PRICES.size()));
                            BigDecimal quantity = BigDecimal.valueOf(random.nextInt(5) + 1L);

                            // Every thread goes through the registry, never through a cached engine.
                            MatchResult result = registry.engineFor(symbol)
                                    .submit(side, OrderType.LIMIT, price, quantity);
                            submitted.get(symbol).put(result.order().id(), quantity);
                        }
                        finished.countDown();
                    });
                }

                startLine.countDown();
                assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            assertThat(registry.instrumentCount()).isEqualTo(SYMBOLS.size());
            assertThat(registry.symbols()).containsExactlyElementsOf(SYMBOLS);
            assertThat(registry.engines()).hasSize(SYMBOLS.size());

            for (String symbol : SYMBOLS) {
                MatchingEngine engine = registry.engineOrThrow(symbol);
                assertThat(engine.symbol()).isEqualTo(symbol);
                Set<UUID> ownOrders = submitted.get(symbol).keySet();
                assertThat(ownOrders).as("orders submitted on %s", symbol).isNotEmpty();

                List<Trade> trades = engine.recentTrades(MatchingEngine.MAX_RECENT_TRADES);
                assertThat(trades).as("trades of %s", symbol).isNotEmpty();
                // The tape of an instrument holds at most MAX_RECENT_TRADES trades: nothing was lost,
                // so the quantities below really are the whole history of the instrument.
                assertThat(trades.size()).isLessThan(MatchingEngine.MAX_RECENT_TRADES);

                // No trade of this instrument may reference an order of another instrument.
                assertThat(trades).allSatisfy(trade -> {
                    assertThat(trade.symbol()).isEqualTo(symbol);
                    assertThat(trade.buyOrderId()).isIn(ownOrders);
                    assertThat(trade.sellOrderId()).isIn(ownOrders);
                });

                // Quantity conservation per instrument: nothing was traded twice and nothing lost.
                Map<UUID, BigDecimal> tradedByOrder = new HashMap<>();
                for (Trade trade : trades) {
                    tradedByOrder.merge(trade.buyOrderId(), trade.quantity(), BigDecimal::add);
                    tradedByOrder.merge(trade.sellOrderId(), trade.quantity(), BigDecimal::add);
                }
                assertThat(tradedByOrder).isNotEmpty();

                BigDecimal restingQuantity = BigDecimal.ZERO;
                int restingOrders = 0;
                for (Map.Entry<UUID, BigDecimal> entry : submitted.get(symbol).entrySet()) {
                    BigDecimal ordered = entry.getValue();
                    BigDecimal traded = tradedByOrder.getOrDefault(entry.getKey(), BigDecimal.ZERO);
                    assertThat(traded).as("traded quantity of order %s on %s", entry.getKey(), symbol)
                            .isNotNegative()
                            .isLessThanOrEqualTo(ordered);
                    BigDecimal remaining = ordered.subtract(traded);
                    assertThat(remaining).as("remaining quantity of order %s", entry.getKey()).isNotNegative();
                    if (remaining.signum() > 0) {
                        restingQuantity = restingQuantity.add(remaining);
                        restingOrders++;
                    }
                }

                // Only LIMIT orders and no cancel in this test: an order with quantity left is resting.
                MatchingEngine.BookSnapshot snapshot = engine.snapshot();
                assertThat(engine.restingOrderCount()).as("resting orders of %s", symbol).isEqualTo(restingOrders);
                assertThat(bookQuantity(snapshot)).as("resting quantity of %s", symbol)
                        .isEqualByComparingTo(restingQuantity);
                assertNotCrossed(snapshot);
            }
        }
    }

    // ------------------------------------------------------------------ snapshots

    @Test
    @DisplayName("Snapshots taken while orders are matching are always consistent")
    void snapshotsAreConsistentWhileMatching() throws Exception {
        MatchingEngine engine = new MatchingEngine("SNAP", Clock.systemUTC());
        int writers = 4;
        int maxOrdersPerWriter = 100_000;
        long runMillis = 1_000L;
        // Short pause between two snapshots, so the reader stays cheap and keeps overlapping the writers.
        long readerPauseNanos = 500_000L;

        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch writersDone = new CountDownLatch(writers);
        AtomicBoolean running = new AtomicBoolean(true);
        Queue<String> violations = new ConcurrentLinkedQueue<>();
        AtomicInteger submittedOrders = new AtomicInteger();
        AtomicInteger takenSnapshots = new AtomicInteger();

        try {
            for (int w = 0; w < writers; w++) {
                long threadSeed = 31_000L + w;
                pool.submit(() -> {
                    Random random = new Random(threadSeed);
                    await(startLine);
                    for (int i = 0; i < maxOrdersPerWriter && running.get(); i++) {
                        BigDecimal price = PRICES.get(random.nextInt(PRICES.size()));
                        BigDecimal quantity = BigDecimal.valueOf(random.nextInt(5) + 1L);
                        engine.submit(random.nextBoolean() ? Side.BUY : Side.SELL,
                                OrderType.LIMIT, price, quantity);
                        submittedOrders.incrementAndGet();
                    }
                    writersDone.countDown();
                });
            }

            // The reader hammers the snapshot while the writers match against each other.
            pool.submit(() -> {
                await(startLine);
                while (running.get()) {
                    MatchingEngine.BookSnapshot snapshot = engine.snapshot();
                    String violation = describeViolation(snapshot);
                    if (violation != null) {
                        violations.add(violation);
                    }
                    takenSnapshots.incrementAndGet();
                    LockSupport.parkNanos(readerPauseNanos);
                }
            });

            startLine.countDown();
            Thread.sleep(runMillis);
            running.set(false);
            assertThat(writersDone.await(30, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            running.set(false);
            pool.shutdownNow();
        }

        // Both threads really did work, otherwise the assertions below would be vacuous.
        assertThat(submittedOrders.get()).as("orders submitted while matching").isGreaterThan(100);
        assertThat(takenSnapshots.get()).as("snapshots taken while matching").isGreaterThan(100);
        assertThat(engine.recentTrades(MatchingEngine.MAX_RECENT_TRADES))
                .as("the matching really happened").isNotEmpty();

        assertThat(violations).as("snapshots observed while matching").isEmpty();

        MatchingEngine.BookSnapshot finalSnapshot = engine.snapshot();
        assertNotCrossed(finalSnapshot);
        assertThat(engine.restingOrderCount())
                .isEqualTo(levelOrderCount(finalSnapshot.bids()) + levelOrderCount(finalSnapshot.asks()));
        assertThat(engine.restingOrderCount()).isGreaterThan(0);
    }

    // ------------------------------------------------------------------ helpers

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Checks one snapshot and returns a description of the first problem found,
     * or {@code null} when the snapshot is consistent.
     */
    private static String describeViolation(MatchingEngine.BookSnapshot snapshot) {
        List<PriceLevel> violation = new ArrayList<>();
        for (PriceLevel level : snapshot.bids()) {
            if (level.quantity().signum() < 0 || level.orderCount() < 1) {
                violation.add(level);
            }
        }
        for (PriceLevel level : snapshot.asks()) {
            if (level.quantity().signum() < 0 || level.orderCount() < 1) {
                violation.add(level);
            }
        }
        if (!violation.isEmpty()) {
            return "negative or empty price level " + violation;
        }
        for (int i = 1; i < snapshot.bids().size(); i++) {
            if (snapshot.bids().get(i).price().compareTo(snapshot.bids().get(i - 1).price()) >= 0) {
                return "bid levels are not sorted best first: " + snapshot.bids();
            }
        }
        for (int i = 1; i < snapshot.asks().size(); i++) {
            if (snapshot.asks().get(i).price().compareTo(snapshot.asks().get(i - 1).price()) <= 0) {
                return "ask levels are not sorted best first: " + snapshot.asks();
            }
        }
        if (!snapshot.bids().isEmpty() && !snapshot.asks().isEmpty()
                && snapshot.bestBid().compareTo(snapshot.bestAsk()) >= 0) {
            return "crossed book: bestBid=" + snapshot.bestBid() + " bestAsk=" + snapshot.bestAsk();
        }
        if (snapshot.bestBid() != null
                && (snapshot.bids().isEmpty()
                || snapshot.bids().get(0).price().compareTo(snapshot.bestBid()) != 0)) {
            return "bestBid does not match the first bid level: " + snapshot.bids();
        }
        if (snapshot.bestAsk() != null
                && (snapshot.asks().isEmpty()
                || snapshot.asks().get(0).price().compareTo(snapshot.bestAsk()) != 0)) {
            return "bestAsk does not match the first ask level: " + snapshot.asks();
        }
        return null;
    }

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
