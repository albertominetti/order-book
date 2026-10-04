package com.albertominetti.orderbook.service;

import com.albertominetti.orderbook.domain.OrderType;
import com.albertominetti.orderbook.domain.Side;
import com.albertominetti.orderbook.dto.CreateOrderRequest;
import com.albertominetti.orderbook.dto.OrderBookResponse;
import com.albertominetti.orderbook.exception.UnknownInstrumentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests of the SSE fan-out: what a subscriber receives when it subscribes, what it receives on
 * every publish, and that a subscriber is unregistered as soon as its connection ends.
 *
 * <p>Subscribers are {@link RecordingEmitter}s handed out by a {@link CapturingBroadcaster}, so the
 * events are inspected directly instead of going through a servlet container.</p>
 */
class MarketBroadcasterTest {

    private static final String SYMBOL = "BTC-USD";

    private OrderService orderService;
    private CapturingBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(new MarketRegistry(Clock.systemUTC()));
        broadcaster = new CapturingBroadcaster(orderService);
        submit(SYMBOL, "BUY", "100.00", "2");
    }

    // ------------------------------------------------------------------ subscribe

    @Test
    @DisplayName("Subscribing to a book immediately pushes the current book and trade tape")
    void subscribeBookPushesCurrentState() {
        RecordingEmitter emitter = subscribe(broadcaster.subscribeBook(SYMBOL));

        assertThat(emitter.eventNames()).containsExactly("book", "trades");
        assertThat(emitter.payload("book")).isInstanceOfSatisfying(OrderBookResponse.class,
                book -> {
                    assertThat(book.bids()).hasSize(1);
                    assertThat(book.bestBid()).isEqualByComparingTo("100.00");
                });
        assertThat(emitter.payload("trades")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("Subscribing to the market stream immediately pushes the instrument list")
    void subscribeInstrumentsPushesTheList() {
        RecordingEmitter emitter = subscribe(broadcaster.subscribeInstruments());

        assertThat(emitter.eventNames()).containsExactly("instruments");
        assertThat((List<?>) emitter.payload("instruments")).hasSize(1);
    }

    @Test
    @DisplayName("A subscriber is registered under its normalized symbol")
    void subscribeNormalizesTheSymbol() {
        broadcaster.subscribeBook(" btc-usd ");
        broadcaster.subscribeBook(SYMBOL);

        assertThat(broadcaster.bookSubscriberCount(SYMBOL)).isEqualTo(2);
        assertThat(broadcaster.instrumentSubscriberCount()).isZero();
        assertThat(broadcaster.subscriberCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("An unknown instrument is rejected with 404 UNKNOWN_INSTRUMENT")
    void subscribeRejectsAnUnknownInstrument() {
        assertThatThrownBy(() -> broadcaster.subscribeBook("NOPE"))
                .isInstanceOf(UnknownInstrumentException.class)
                .hasMessage("unknown instrument 'NOPE'");
        assertThat(broadcaster.subscriberCount()).isZero();
    }

    @Test
    @DisplayName("A malformed symbol is rejected before any stream is opened")
    void subscribeRejectsAMalformedSymbol() {
        assertThatThrownBy(() -> broadcaster.subscribeBook("BT/USD"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(broadcaster.subscriberCount()).isZero();
    }

    // ------------------------------------------------------------------ publish

    @Test
    @DisplayName("A publish reaches every subscriber of the symbol and nobody else")
    void publishReachesTheSubscribersOfTheSymbolOnly() {
        submit("ETH-USD", "SELL", "200.00", "1");
        RecordingEmitter first = subscribe(broadcaster.subscribeBook(SYMBOL));
        RecordingEmitter second = subscribe(broadcaster.subscribeBook(SYMBOL));
        RecordingEmitter other = subscribe(broadcaster.subscribeBook("ETH-USD"));
        RecordingEmitter market = subscribe(broadcaster.subscribeInstruments());

        broadcaster.publishBook(SYMBOL, OrderBookResponse.from(orderService.getBookSnapshot(SYMBOL)));
        broadcaster.publishTrades(SYMBOL, List.of("a trade"));
        broadcaster.publishInstruments(broadcaster.instrumentStats());

        assertThat(first.eventNames()).containsExactly("book", "trades", "book", "trades");
        assertThat(second.eventNames()).containsExactly("book", "trades", "book", "trades");
        assertThat(first.payloads("trades")).containsExactly(List.of(), List.of("a trade"));
        assertThat(first.payloads("book")).hasSize(2).allMatch(OrderBookResponse.class::isInstance);
        assertThat(other.eventNames()).containsExactly("book", "trades");
        assertThat(market.eventNames()).containsExactly("instruments", "instruments");
        assertThat(market.payload("instruments")).isNotNull();
    }

    @Test
    @DisplayName("Publishing with no subscriber at all is a no-op, never a failure")
    void publishWithoutSubscribersIsSilent() {
        assertThatCode(() -> {
            broadcaster.publishBook(SYMBOL, "payload");
            broadcaster.publishTrades(SYMBOL, "payload");
            broadcaster.publishInstruments("payload");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The heartbeat sends a keep-alive comment on every open stream")
    void heartbeatCommentsEveryStream() {
        RecordingEmitter book = subscribe(broadcaster.subscribeBook(SYMBOL));
        RecordingEmitter market = subscribe(broadcaster.subscribeInstruments());

        broadcaster.heartbeat();

        assertThat(book.comments()).containsExactly("keep-alive");
        assertThat(market.comments()).containsExactly("keep-alive");
    }

    // ------------------------------------------------------------------ removal

    @Test
    @DisplayName("A subscriber whose write fails is completed and removed, the healthy one stays")
    void brokenSubscriberIsRemovedOnTheFailedSend() {
        RecordingEmitter broken = subscribe(broadcaster.subscribeBook(SYMBOL));
        RecordingEmitter healthy = subscribe(broadcaster.subscribeBook(SYMBOL));
        broken.failOnSend();

        assertThatCode(() -> broadcaster.publishBook(SYMBOL, "payload")).doesNotThrowAnyException();

        assertThat(broadcaster.bookSubscriberCount(SYMBOL)).isEqualTo(1);
        assertThat(broken.isCompleted()).isTrue();
        assertThat(healthy.isCompleted()).isFalse();
        assertThat(healthy.eventNames()).containsExactly("book", "trades", "book");
    }

    @Test
    @DisplayName("A heartbeat that cannot write drops the dead stream instead of failing")
    void brokenSubscriberIsRemovedByTheHeartbeat() {
        RecordingEmitter broken = subscribe(broadcaster.subscribeInstruments());
        RecordingEmitter healthy = subscribe(broadcaster.subscribeInstruments());
        broken.failOnSend();

        assertThatCode(broadcaster::heartbeat).doesNotThrowAnyException();

        assertThat(broadcaster.instrumentSubscriberCount()).isEqualTo(1);
        assertThat(healthy.comments()).containsExactly("keep-alive");
    }

    @Test
    @DisplayName("Completion, timeout and error each unregister the subscriber")
    void terminalCallbacksUnregister() {
        RecordingEmitter completed = subscribe(broadcaster.subscribeBook(SYMBOL));
        RecordingEmitter timedOut = subscribe(broadcaster.subscribeBook(SYMBOL));
        RecordingEmitter failed = subscribe(broadcaster.subscribeBook(SYMBOL));
        RecordingEmitter marketFailed = subscribe(broadcaster.subscribeInstruments());
        assertThat(broadcaster.subscriberCount()).isEqualTo(4);

        completed.fireCompletion();
        assertThat(broadcaster.bookSubscriberCount(SYMBOL)).isEqualTo(2);

        timedOut.fireTimeout();
        assertThat(broadcaster.bookSubscriberCount(SYMBOL)).isEqualTo(1);

        failed.fireError(new IllegalStateException("client is gone"));
        assertThat(broadcaster.bookSubscriberCount(SYMBOL)).isZero();

        marketFailed.fireError(new IllegalStateException("client is gone"));
        assertThat(broadcaster.instrumentSubscriberCount()).isZero();
        assertThat(broadcaster.subscriberCount()).isZero();
    }

    @Test
    @DisplayName("A subscriber that cannot even read its initial state is dropped right away")
    void brokenSubscriberOfTheInitialStateIsRemoved() {
        CapturingBroadcaster failing = new CapturingBroadcaster(orderService);
        failing.alwaysFail();

        RecordingEmitter emitter = subscribe(failing.subscribeBook(SYMBOL));

        assertThat(emitter.eventNames()).isEmpty();
        assertThat(failing.bookSubscriberCount(SYMBOL)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private void submit(String symbol, String side, String price, String quantity) {
        orderService.submitOrder(new CreateOrderRequest(symbol, Side.valueOf(side), OrderType.LIMIT,
                new BigDecimal(price), new BigDecimal(quantity)));
    }

    /** The emitter the broadcaster just created for a subscription, as a recording emitter. */
    private RecordingEmitter subscribe(SseEmitter emitter) {
        return (RecordingEmitter) emitter;
    }

    /** A broadcaster whose emitters record what is written to them instead of writing it out. */
    private static final class CapturingBroadcaster extends MarketBroadcaster {

        private final List<RecordingEmitter> created = new CopyOnWriteArrayList<>();
        private boolean alwaysFail;

        CapturingBroadcaster(OrderService orderService) {
            super(orderService);
        }

        void alwaysFail() {
            this.alwaysFail = true;
        }

        @Override
        protected SseEmitter newEmitter() {
            RecordingEmitter emitter = new RecordingEmitter(alwaysFail);
            created.add(emitter);
            return emitter;
        }
    }

    /**
     * An {@link SseEmitter} that records what is sent to it, captures its terminal callbacks so a
     * test can fire them, and can be made to fail on demand.
     */
    private static final class RecordingEmitter extends SseEmitter {

        private final List<Sent> sent = new CopyOnWriteArrayList<>();
        private final List<String> comments = new CopyOnWriteArrayList<>();
        private Runnable onCompletionCallback;
        private Runnable onTimeoutCallback;
        private Consumer<Throwable> onErrorCallback;
        private volatile boolean failing;
        private boolean completed;

        private RecordingEmitter(boolean failing) {
            this.failing = failing;
        }

        // --- what the broadcaster calls, intercepted instead of hitting a servlet response

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failing) {
                throw new IOException("broken pipe");
            }
            for (ResponseBodyEmitter.DataWithMediaType item : builder.build()) {
                if (isText(item.getMediaType())) {
                    // A builder writes the event name, then the data, then the closing newline, and
                    // only the name line matters here.
                    String text = String.valueOf(item.getData());
                    if (text.startsWith(":")) {
                        comments.add(text.substring(1).trim());
                    } else if (!eventName(text).isEmpty()) {
                        sent.add(new Sent(eventName(text), null));
                    }
                } else {
                    attach(item.getData());
                }
            }
        }

        /** Fills the payload of the event that was just opened, or records a nameless one. */
        private void attach(Object data) {
            int last = sent.size() - 1;
            if (last >= 0 && sent.get(last).data() == null) {
                sent.set(last, new Sent(sent.get(last).name(), data));
            } else {
                sent.add(new Sent("", data));
            }
        }

        @Override
        public void onCompletion(Runnable callback) {
            this.onCompletionCallback = callback;
        }

        @Override
        public void onTimeout(Runnable callback) {
            this.onTimeoutCallback = callback;
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            this.onErrorCallback = callback;
        }

        @Override
        public void complete() {
            this.completed = true;
        }

        // --- test controls and assertions

        void failOnSend() {
            this.failing = true;
        }

        boolean isCompleted() {
            return completed;
        }

        void fireCompletion() {
            onCompletionCallback.run();
        }

        void fireTimeout() {
            onTimeoutCallback.run();
        }

        void fireError(Throwable failure) {
            onErrorCallback.accept(failure);
        }

        /** Names of the events received, in order. */
        List<String> eventNames() {
            return sent.stream().map(Sent::name).toList();
        }

        /** Keep-alive comments received, in order. */
        List<String> comments() {
            return comments;
        }

        /** Data of the first event with that name. */
        Object payload(String name) {
            return sent.stream()
                    .filter(event -> event.name().equals(name) && event.data() != null)
                    .map(Sent::data)
                    .findFirst()
                    .orElse(null);
        }

        /** Data of every event with that name, in order. */
        List<Object> payloads(String name) {
            return sent.stream()
                    .filter(event -> event.name().equals(name) && event.data() != null)
                    .map(Sent::data)
                    .toList();
        }

        private static boolean isText(MediaType mediaType) {
            return mediaType != null && mediaType.isCompatibleWith(MediaType.TEXT_PLAIN);
        }

        private static String eventName(String text) {
            for (String line : text.split("\n")) {
                if (line.startsWith("event:")) {
                    return line.substring("event:".length()).trim();
                }
            }
            return "";
        }
    }

    /** One event written to a subscriber: its name and, for a data event, its payload. */
    private record Sent(String name, Object data) {
    }
}