package com.albertominetti.orderbook.engine;

import com.albertominetti.orderbook.domain.InstrumentStats;
import com.albertominetti.orderbook.domain.Order;
import com.albertominetti.orderbook.domain.OrderType;
import com.albertominetti.orderbook.domain.OrderView;
import com.albertominetti.orderbook.domain.PriceLevel;
import com.albertominetti.orderbook.domain.Side;
import com.albertominetti.orderbook.domain.SymbolRules;
import com.albertominetti.orderbook.domain.Trade;
import com.albertominetti.orderbook.exception.OrderNotFoundException;
import com.albertominetti.orderbook.exception.OrderStateException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single-instrument, in-memory order book with strict price-time priority.
 *
 * <p>One engine owns exactly one symbol: every order it accepts and every trade it produces
 * carries that symbol, and because the book state is private to the instance, orders of
 * different instruments can never be matched against each other. The
 * {@link com.albertominetti.orderbook.service.MarketRegistry} creates one engine per symbol.</p>
 *
 * <p>Structure</p>
 * <ul>
 *   <li>two {@link TreeMap}s of price level to FIFO queue of orders, both iterated
 *       best-price-first (bids descending, asks ascending), so the best price is always
 *       the {@code firstKey()} of the map;</li>
 *   <li>a {@link LinkedHashMap} of every order ever accepted, so terminal orders can still be fetched by id;</li>
 *   <li>a bounded FIFO {@link ArrayDeque} of the most recent trades.</li>
 * </ul>
 *
 * <p>Concurrency: a single {@link ReentrantLock} guards all mutable state, so matching is
 * atomic and the book is never observed half-updated. The lock is per engine, therefore orders
 * on different instruments are matched fully in parallel.</p>
 *
 * <p>Matching rules</p>
 * <ul>
 *   <li>a BUY matches the lowest ask when {@code bestAsk <= order.price} (always, for MARKET);</li>
 *   <li>a SELL matches the highest bid when {@code bestBid >= order.price} (always, for MARKET);</li>
 *   <li>inside a level the oldest order is filled first (FIFO) and fills can be partial;</li>
 *   <li>trades execute at the resting order price;</li>
 *   <li>the remainder of an aggressive LIMIT order rests on the book, while the remainder of a
 *       MARKET order is discarded and the order ends as {@code FILLED} (fully consumed) or
 *       {@code CANCELLED} (partially consumed).</li>
 * </ul>
 */
public class MatchingEngine {

    /** Maximum number of trades kept in memory for the per-instrument trade tape. */
    public static final int MAX_RECENT_TRADES = 1_000;

    /** price level -> orders resting on it, oldest first (FIFO). */
    private final TreeMap<BigDecimal, Deque<Order>> bids = new TreeMap<>(Collections.reverseOrder());
    private final TreeMap<BigDecimal, Deque<Order>> asks = new TreeMap<>();
    private final Map<UUID, Order> ordersById = new LinkedHashMap<>();
    private final Deque<Trade> recentTrades = new ArrayDeque<>();

    private final String symbol;
    private final ReentrantLock lock = new ReentrantLock();
    private final Clock clock;

    /**
     * @param symbol the instrument this book trades, normalized by {@link SymbolRules#normalize(String)}
     */
    public MatchingEngine(String symbol) {
        this(symbol, Clock.systemUTC());
    }

    /** Visible for testing: allows a deterministic clock. */
    public MatchingEngine(String symbol, Clock clock) {
        this.symbol = SymbolRules.normalize(symbol);
        this.clock = clock;
    }

    /** The instrument this book trades. */
    public String symbol() {
        return symbol;
    }

    // ------------------------------------------------------------------ commands

    /**
     * Accepts a new order, matches it against the opposite side of the book and, if quantity
     * remains, rests it (LIMIT) or discards the remainder (MARKET).
     *
     * @param side     BUY or SELL
     * @param type     LIMIT or MARKET
     * @param price    required (and positive) for LIMIT, {@code null} for MARKET
     * @param quantity strictly positive quantity
     * @return the order in its final state plus the trades it generated, in execution order
     */
    public MatchResult submit(Side side, OrderType type, BigDecimal price, BigDecimal quantity) {
        if (side == null || type == null) {
            throw new IllegalArgumentException("side and type must not be null");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }
        if (type == OrderType.LIMIT) {
            if (price == null || price.signum() <= 0) {
                throw new IllegalArgumentException("price must be greater than zero for LIMIT orders");
            }
        } else if (price != null) {
            throw new IllegalArgumentException("price must not be provided for MARKET orders");
        }

        lock.lock();
        try {
            Order order = new Order(symbol, side, type, price, quantity, Instant.now(clock));
            ordersById.put(order.getId(), order);

            List<Trade> trades = new ArrayList<>();
            match(order, trades);

            if (order.isResting()) {
                if (type == OrderType.LIMIT) {
                    rest(order);
                } else {
                    // Unfilled MARKET remainder: discard it.
                    order.cancel();
                }
            }

            return new MatchResult(order.toView(), trades);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Cancels an order still resting on the book.
     *
     * @throws OrderNotFoundException if the id is unknown
     * @throws OrderStateException    if the order is not resting (already FILLED/CANCELLED, or a MARKET order)
     */
    public OrderView cancel(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("order id must not be null");
        }
        lock.lock();
        try {
            Order order = requireOrder(orderId);
            // MARKET orders always end FILLED or CANCELLED, so this also covers them:
            // they are never cancellable because they never rest.
            if (order.getStatus().isTerminal()) {
                throw new OrderStateException("order " + orderId + " cannot be cancelled because it is " + order.getStatus());
            }
            removeFromBook(order);
            order.cancel();
            return order.toView();
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ queries

    /** Looks up any order ever accepted, including terminal ones. */
    public OrderView findOrder(UUID orderId) {
        lock.lock();
        try {
            return requireOrder(orderId).toView();
        } finally {
            lock.unlock();
        }
    }

    /** Aggregated book snapshot, best price first on both sides. */
    public BookSnapshot snapshot() {
        lock.lock();
        try {
            List<PriceLevel> bidLevels = aggregate(bids);
            List<PriceLevel> askLevels = aggregate(asks);
            BigDecimal bestBid = bestBid();
            BigDecimal bestAsk = bestAsk();
            BigDecimal spread = (bestBid == null || bestAsk == null) ? null : bestAsk.subtract(bestBid);
            BigDecimal lastPrice = recentTrades.isEmpty() ? null : recentTrades.peekLast().price();
            return new BookSnapshot(bidLevels, askLevels, bestBid, bestAsk, spread, lastPrice);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Recent trades, newest first.
     *
     * @param limit maximum number of trades to return, within [1, MAX_RECENT_TRADES]
     */
    public List<Trade> recentTrades(int limit) {
        if (limit < 1 || limit > MAX_RECENT_TRADES) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_RECENT_TRADES);
        }
        lock.lock();
        try {
            List<Trade> result = new ArrayList<>(Math.min(limit, recentTrades.size()));
            Iterator<Trade> iterator = recentTrades.descendingIterator();
            while (iterator.hasNext() && result.size() < limit) {
                result.add(iterator.next());
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /** Number of orders currently resting on the book. */
    public int restingOrderCount() {
        lock.lock();
        try {
            return countOrders(bids) + countOrders(asks);
        } finally {
            lock.unlock();
        }
    }

    /** Summary of this instrument, computed under a single lock acquisition. */
    public InstrumentStats stats() {
        lock.lock();
        try {
            return new InstrumentStats(
                    symbol,
                    countOrders(bids) + countOrders(asks),
                    bestBid(),
                    bestAsk(),
                    recentTrades.isEmpty() ? null : recentTrades.peekLast().price());
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ internals

    /** Price-time priority matching loop. Assumes the lock is held. */
    private void match(Order aggressor, List<Trade> trades) {
        TreeMap<BigDecimal, Deque<Order>> opposite = aggressor.getSide() == Side.BUY ? asks : bids;

        while (aggressor.getRemainingQuantity().signum() > 0) {
            BigDecimal bestOppositePrice = aggressor.getSide() == Side.BUY ? bestAsk() : bestBid();
            if (bestOppositePrice == null || !isMatchable(aggressor, bestOppositePrice)) {
                return;
            }

            Deque<Order> level = opposite.get(bestOppositePrice);
            Order resting = level.peekFirst();
            BigDecimal tradedQuantity = aggressor.getRemainingQuantity().min(resting.getRemainingQuantity());

            aggressor.fill(tradedQuantity);
            resting.fill(tradedQuantity);
            trades.add(recordTrade(aggressor, resting, tradedQuantity));

            if (!resting.isResting()) {
                level.pollFirst();
            }
            if (level.isEmpty()) {
                opposite.remove(bestOppositePrice);
            }
        }
    }

    /** Lowest resting ask, or {@code null} when the ask side is empty. */
    private BigDecimal bestAsk() {
        return asks.isEmpty() ? null : asks.firstKey();
    }

    /** Highest resting bid, or {@code null} when the bid side is empty. */
    private BigDecimal bestBid() {
        return bids.isEmpty() ? null : bids.firstKey();
    }

    /**
     * Does an aggressive order cross the given opposite price?
     * MARKET orders always cross; LIMIT orders only when aggressive enough.
     */
    private boolean isMatchable(Order aggressor, BigDecimal oppositePrice) {
        if (aggressor.getType() == OrderType.MARKET) {
            return true;
        }
        int comparison = aggressor.getPrice().compareTo(oppositePrice);
        return aggressor.getSide() == Side.BUY ? comparison >= 0 : comparison <= 0;
    }

    /** Creates a trade at the resting order price and stores it. Assumes the lock is held. */
    private Trade recordTrade(Order aggressor, Order resting, BigDecimal quantity) {
        UUID buyOrderId = aggressor.getSide() == Side.BUY ? aggressor.getId() : resting.getId();
        UUID sellOrderId = aggressor.getSide() == Side.SELL ? aggressor.getId() : resting.getId();
        Trade trade = new Trade(UUID.randomUUID(), symbol, buyOrderId, sellOrderId, resting.getPrice(), quantity,
                Instant.now(clock));
        recentTrades.addLast(trade);
        while (recentTrades.size() > MAX_RECENT_TRADES) {
            recentTrades.pollFirst();
        }
        return trade;
    }

    /** Puts an order on its own side of the book. Assumes the lock is held. */
    private void rest(Order order) {
        TreeMap<BigDecimal, Deque<Order>> side = order.getSide() == Side.BUY ? bids : asks;
        side.computeIfAbsent(order.getPrice(), price -> new ArrayDeque<>()).addLast(order);
    }

    /** Removes an order from its price level and prunes the level when it becomes empty. */
    private void removeFromBook(Order order) {
        TreeMap<BigDecimal, Deque<Order>> side = order.getSide() == Side.BUY ? bids : asks;
        Deque<Order> level = side.get(order.getPrice());
        if (level == null) {
            return;
        }
        level.remove(order);
        if (level.isEmpty()) {
            side.remove(order.getPrice());
        }
    }

    /** Aggregates the queues into one entry per price level, preserving the map order. */
    private List<PriceLevel> aggregate(TreeMap<BigDecimal, Deque<Order>> side) {
        List<PriceLevel> levels = new ArrayList<>(side.size());
        side.forEach((price, level) -> {
            BigDecimal total = BigDecimal.ZERO;
            for (Order order : level) {
                total = total.add(order.getRemainingQuantity());
            }
            levels.add(new PriceLevel(price, total, level.size()));
        });
        return levels;
    }

    private int countOrders(TreeMap<BigDecimal, Deque<Order>> side) {
        int count = 0;
        for (Deque<Order> level : side.values()) {
            count += level.size();
        }
        return count;
    }

    /** Assumes the lock is held. */
    private Order requireOrder(UUID orderId) {
        Order order = orderId == null ? null : ordersById.get(orderId);
        if (order == null) {
            throw new OrderNotFoundException("order " + orderId + " not found");
        }
        return order;
    }

    /**
     * Read-only book snapshot.
     *
     * @param bids      bid levels, best (highest) price first
     * @param asks      ask levels, best (lowest) price first
     * @param bestBid   highest resting bid, {@code null} when there are no bids
     * @param bestAsk   lowest resting ask, {@code null} when there are no asks
     * @param spread    bestAsk - bestBid, {@code null} when one of the two sides is empty
     * @param lastPrice price of the most recent trade, {@code null} when nothing has traded yet
     */
    public record BookSnapshot(
            List<PriceLevel> bids,
            List<PriceLevel> asks,
            BigDecimal bestBid,
            BigDecimal bestAsk,
            BigDecimal spread,
            BigDecimal lastPrice
    ) {
        public BookSnapshot {
            bids = List.copyOf(bids);
            asks = List.copyOf(asks);
        }
    }
}
