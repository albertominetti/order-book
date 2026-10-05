package com.albertominetti.orderbook.service;

import com.albertominetti.orderbook.domain.SymbolRules;
import com.albertominetti.orderbook.dto.InstrumentStatsResponse;
import com.albertominetti.orderbook.dto.OrderBookResponse;
import com.albertominetti.orderbook.dto.OrderResponse;
import com.albertominetti.orderbook.dto.TradeResponse;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Fan-out of market updates over the Server-Sent Events **stream** channel, the one the browser UI
 * subscribes to, and the push counterpart of the polling REST endpoints.
 *
 * <p>Everything pushed here is a full-state snapshot: the current book, the current order list, the
 * current trade tape and the current instrument list. Nothing is a delta and nothing is a fact that
 * has just happened, because a snapshot is all a UI needs to render itself and a subscriber that
 * (re)connects gets the whole picture again.</p>
 *
 * <p>There is one set of subscribers per instrument symbol plus a single set for the "market" stream
 * that carries the instrument list. Subscribers are {@link SseEmitter} instances registered on
 * subscription and removed again as soon as the connection ends, for whatever reason: the client
 * closed it, the container timed it out, the connection broke or a write failed.</p>
 *
 * <p>Every payload is the very same JSON the REST endpoints return, because the payloads are built
 * from the same response records: {@link OrderBookResponse} for the book, {@link TradeResponse} for
 * the trade tape and {@link InstrumentStatsResponse} for the instrument list. A client therefore
 * reuses one parser for both channels.</p>
 *
 * <p>The push channel and the query endpoints agree on what an instrument nobody has traded on is:
 * an empty state, not a missing resource. A stream can be opened for any well formed symbol, even one
 * that has no book yet, and starts with an empty book, an empty order list and an empty tape, while
 * {@code GET /api/instruments/{symbol}/orderbook} and {@code .../trades} answer {@code 200} with
 * that very same empty book and tape. Only a malformed symbol is an error, {@code 400}, on either
 * channel.</p>
 *
 * <p>This component is purely additive: it only writes to whoever subscribed, so a broken subscriber
 * can never affect a matching engine or a REST response. Publishing never throws, it only drops the
 * subscribers that cannot be written to.</p>
 *
 * <p>This is the stream channel: ephemeral full-state snapshots for browsers, kept open per
 * subscriber and never replayed. It is not the event stream: the append-only event stream for
 * reporting consumers and backend systems lives in its own package,
 * {@link com.albertominetti.orderbook.events}, and carries what happened instead of the current
 * state. The two channels are independent.</p>
 */
@Component
public class MarketStreamBroadcaster {

    /** How long a stream stays open before the container times it out: 30 minutes. */
    public static final long STREAM_TIMEOUT_MS = 30L * 60L * 1000L;

    /** Number of trades carried by a {@code trades} update, the size of the streamed trade tape. */
    public static final int STREAM_TRADE_LIMIT = 20;

    /** Interval between two keep-alive comments. */
    static final long HEARTBEAT_MS = 20_000L;

    /** Update carrying the book snapshot. */
    public static final String BOOK_UPDATE = "book";

    /** Update carrying the recent trade tape. */
    public static final String TRADES_UPDATE = "trades";

    /** Update carrying the instrument list. */
    public static final String INSTRUMENTS_UPDATE = "instruments";

    /** Update carrying all orders of the instrument. */
    public static final String ORDERS_UPDATE = "orders";

    private final OrderService orderService;

    /** Subscribers of the per-instrument streams, keyed by normalized symbol. */
    private final Map<String, Set<SseEmitter>> bookSubscribers = new ConcurrentHashMap<>();

    /** Subscribers of the market stream, the instrument list. */
    private final Set<SseEmitter> instrumentSubscribers = ConcurrentHashMap.newKeySet();

    public MarketStreamBroadcaster(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Subscribes to the book and the trades of one instrument and immediately pushes its current
     * state, so a fresh subscriber is never blank.
     *
     * <p>Any valid symbol can be streamed, even one whose book does not exist yet: such a subscriber
     * is registered like any other and receives an empty book plus an empty trade tape, then the
     * real state as soon as the first order creates the book. Nothing is created here, so opening a
     * stream never brings an instrument into existence. Only a malformed symbol is rejected.</p>
     *
     * @param rawSymbol symbol to normalize (trim + uppercase)
     * @return the emitter to hand back to the client
     * @throws IllegalArgumentException when the symbol is missing or malformed (HTTP 400)
     */
    public SseEmitter subscribeBook(String rawSymbol) {
        String symbol = SymbolRules.normalize(rawSymbol);

        // The initial state is resolved before anything is registered, so an emitter that cannot be
        // written to never leaves a subscriber behind. A symbol without an engine is not a failure:
        // it is the empty state of an instrument no order has reached yet.
        boolean known = orderService.hasInstrument(symbol);
        OrderBookResponse book = known
                ? OrderBookResponse.from(orderService.getBookSnapshot(symbol))
                : OrderBookResponse.empty();
        List<TradeResponse> tape = known ? tradeTape(symbol) : List.of();
        List<OrderResponse> orders = known ? ordersList(symbol, 50) : List.of();

        SseEmitter emitter = newEmitter();
        Set<SseEmitter> subscribers = bookSubscribers.computeIfAbsent(symbol, key -> subscribersOf());

        register(subscribers, emitter);

        if (!push(emitter, BOOK_UPDATE, book)) {
            remove(subscribers, emitter);
            return emitter;
        }
        if (!push(emitter, ORDERS_UPDATE, orders)) {
            remove(subscribers, emitter);
            return emitter;
        }
        if (!push(emitter, TRADES_UPDATE, tape)) {
            remove(subscribers, emitter);
        }
        return emitter;
    }

    /**
     * Subscribes to the market stream and immediately pushes the current instrument list.
     *
     * @return the emitter to hand back to the client
     */
    public SseEmitter subscribeInstruments() {
        SseEmitter emitter = newEmitter();

        register(instrumentSubscribers, emitter);

        if (!push(emitter, INSTRUMENTS_UPDATE, instrumentStats())) {
            remove(instrumentSubscribers, emitter);
        }
        return emitter;
    }

    /**
     * Creates the emitter of a new subscription, with a long timeout so the stream stays open.
     *
     * <p>Overridable so tests can capture what is written to a subscriber without a servlet
     * container behind it.</p>
     */
    protected SseEmitter newEmitter() {
        return new SseEmitter(STREAM_TIMEOUT_MS);
    }

    /** Pushes a book snapshot to the subscribers of one instrument. */
    public void publishBook(String symbol, Object payload) {
        broadcast(bookSubscribersOf(symbol), () -> event(BOOK_UPDATE, payload));
    }

    /** Pushes a trade tape to the subscribers of one instrument. */
    public void publishTrades(String symbol, Object payload) {
        broadcast(bookSubscribersOf(symbol), () -> event(TRADES_UPDATE, payload));
    }

    /** Pushes all orders to the subscribers of one instrument. */
    public void publishOrders(String symbol, Object payload) {
        broadcast(bookSubscribersOf(symbol), () -> event(ORDERS_UPDATE, payload));
    }

    /** Pushes the instrument list to the subscribers of the market stream. */
    public void publishInstruments(Object payload) {
        broadcast(instrumentSubscribers, () -> event(INSTRUMENTS_UPDATE, payload));
    }

    /**
     * Sends a keep-alive comment to every open stream, so idle connections survive the proxies and
     * load balancers that would otherwise close them without any notice.
     */
    @Scheduled(fixedRateString = "20000")
    public void heartbeat() {
        Supplier<SseEmitter.SseEventBuilder> comments = () -> SseEmitter.event().comment("keep-alive");
        bookSubscribers.values().forEach(subscribers -> broadcast(subscribers, comments));
        broadcast(instrumentSubscribers, comments);
    }

    /** Current instrument stats, exactly the payload of {@code GET /api/instruments}. */
    public List<InstrumentStatsResponse> instrumentStats() {
        return orderService.listInstruments().stream()
                .map(InstrumentStatsResponse::from)
                .toList();
    }

    // ------------------------------------------------------------------ diagnostics and tests

    /** Number of open subscribers of the stream of one instrument. */
    public int bookSubscriberCount(String rawSymbol) {
        return bookSubscribersOf(rawSymbol).size();
    }

    /** Number of open subscribers of the market stream. */
    public int instrumentSubscriberCount() {
        return instrumentSubscribers.size();
    }

    /** Total number of open subscribers, on every stream. */
    public int subscriberCount() {
        return bookSubscribers.values().stream().mapToInt(Set::size).sum() + instrumentSubscribers.size();
    }

    // ------------------------------------------------------------------ internals

    /**
     * Adds an emitter to a subscriber set and wires its three terminal callbacks, so completion,
     * timeout and error all unregister it.
     */
    private void register(Set<SseEmitter> subscribers, SseEmitter emitter) {
        Runnable unregister = () -> remove(subscribers, emitter);
        emitter.onCompletion(unregister);
        emitter.onTimeout(unregister);
        emitter.onError(failure -> unregister.run());
        subscribers.add(emitter);
    }

    /** Unregisters an emitter, and forgets an instrument nobody listens to any more. */
    private void remove(Set<SseEmitter> subscribers, SseEmitter emitter) {
        subscribers.remove(emitter);
        // Only a per-instrument set is ever stored as a value, so this drops the symbol as soon as
        // its last subscriber is gone and is a no-op for the market stream.
        if (subscribers.isEmpty()) {
            bookSubscribers.values().remove(subscribers);
        }
    }

    /**
     * Sends one event to one emitter.
     *
     * @return {@code false} when the connection is already broken
     */
    private boolean push(SseEmitter emitter, String name, Object payload) {
        try {
            emitter.send(event(name, payload));
            return true;
        } catch (Exception failure) {
            // A dead connection is an expected outcome, never a failure of the caller.
            return false;
        }
    }

    /**
     * Sends one event to every subscriber of a set, dropping the ones that cannot be written to.
     *
     * <p>A fresh builder per emitter, because a builder holds the state of a single event.</p>
     */
    private void broadcast(Set<SseEmitter> subscribers, Supplier<SseEmitter.SseEventBuilder> events) {
        for (SseEmitter emitter : List.copyOf(subscribers)) {
            try {
                emitter.send(events.get());
            } catch (Exception failure) {
                remove(subscribers, emitter);
                close(emitter);
            }
        }
    }

    /** Completes an emitter whose connection is gone, ignoring any further failure. */
    private void close(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // Nothing left to clean up on a connection that is already gone.
        }
    }

    private SseEmitter.SseEventBuilder event(String name, Object payload) {
        return SseEmitter.event().name(name).data(payload, MediaType.APPLICATION_JSON);
    }

    /** The recent trade tape of one instrument, the payload of the {@code trades} update. */
    private List<TradeResponse> tradeTape(String symbol) {
        return orderService.getRecentTrades(symbol, STREAM_TRADE_LIMIT).stream()
                .map(TradeResponse::from)
                .toList();
    }

    /** All orders of one instrument, newest first. */
    private List<OrderResponse> ordersList(String symbol, int limit) {
        return orderService.getOrders(symbol, limit).stream()
                .map(OrderResponse::from)
                .toList();
    }

    /** Subscribers of one instrument, an empty set for a symbol nobody listens to. */
    private Set<SseEmitter> bookSubscribersOf(String rawSymbol) {
        return bookSubscribers.getOrDefault(SymbolRules.normalize(rawSymbol), Set.of());
    }

    private static Set<SseEmitter> subscribersOf() {
        return ConcurrentHashMap.newKeySet();
    }
}
