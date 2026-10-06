package com.albertominetti.orderbook.events;

import com.albertominetti.orderbook.domain.OrderStatus;
import com.albertominetti.orderbook.domain.OrderType;
import com.albertominetti.orderbook.domain.OrderView;
import com.albertominetti.orderbook.domain.Side;
import com.albertominetti.orderbook.domain.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests of the event emitter: what it builds and how it numbers the events.
 *
 * <p>No Spring, no broker: a {@link RecordingPublisher} captures the events, and a fixed clock makes
 * the timestamps exact.</p>
 */
class MarketEventEmitterTest {

    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TRADE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID BUY_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID SELL_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private RecordingPublisher publisher;
    private MarketEventEmitter emitter;

    @BeforeEach
    void setUp() {
        publisher = new RecordingPublisher();
        emitter = new MarketEventEmitter(publisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ order events

    @Test
    @DisplayName("An accepted order becomes an ORDER_ACCEPTED event carrying the order fields, seq 1")
    void orderAcceptedCarriesTheOrderFields() {
        emitter.orderAccepted(order("BTC-USD", OrderStatus.NEW, "10.00", "5", "5"));

        assertThat(publisher.events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(MarketEventType.ORDER_ACCEPTED);
            assertThat(event.seq()).isEqualTo(1L);
            assertThat(event.symbol()).isEqualTo("BTC-USD");
            assertThat(event.occurredAt()).isEqualTo(NOW);
            assertThat(event.orderId()).isEqualTo(ORDER_ID);
            assertThat(event.side()).isEqualTo(Side.BUY);
            assertThat(event.orderType()).isEqualTo(OrderType.LIMIT);
            assertThat(event.price()).isEqualByComparingTo("10.00");
            assertThat(event.quantity()).isEqualByComparingTo("5");
            assertThat(event.remainingQuantity()).isEqualByComparingTo("5");
            assertThat(event.status()).isEqualTo(OrderStatus.NEW);
            // Members of the other event types stay empty.
            assertThat(event.tradeId()).isNull();
            assertThat(event.buyOrderId()).isNull();
            assertThat(event.sellOrderId()).isNull();
        });
    }

    @Test
    @DisplayName("A cancelled order becomes an ORDER_CANCELLED event with the CANCELLED status")
    void orderCancelledCarriesTheCancelledStatus() {
        emitter.orderCancelled(order("BTC-USD", OrderStatus.CANCELLED, "10.00", "5", "5"));

        assertThat(publisher.events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(MarketEventType.ORDER_CANCELLED);
            assertThat(event.seq()).isEqualTo(1L);
            assertThat(event.orderId()).isEqualTo(ORDER_ID);
            assertThat(event.status()).isEqualTo(OrderStatus.CANCELLED);
        });
    }

    // ------------------------------------------------------------------ sequence numbers

    @Test
    @DisplayName("The sequence number is monotonic per symbol and restarts on another symbol")
    void sequenceIsMonotonicPerSymbol() {
        emitter.orderAccepted(order("BTC-USD", OrderStatus.NEW, "10.00", "5", "5"));
        emitter.orderCancelled(order("BTC-USD", OrderStatus.CANCELLED, "10.00", "5", "5"));
        emitter.orderAccepted(order("ETH-USD", OrderStatus.NEW, "20.00", "1", "1"));

        assertThat(publisher.events).extracting(MarketEvent::seq).containsExactly(1L, 2L, 1L);
        assertThat(publisher.events).extracting(MarketEvent::symbol)
                .containsExactly("BTC-USD", "BTC-USD", "ETH-USD");
    }

    // ------------------------------------------------------------------ trade events

    @Test
    @DisplayName("A trade becomes a TRADE_EXECUTED event with both order ids, the price and the quantity")
    void tradeExecutedCarriesTheTradeFields() {
        emitter.tradeExecuted(new Trade(TRADE_ID, "BTC-USD", BUY_ID, SELL_ID,
                new BigDecimal("10.00"), new BigDecimal("2"), NOW));

        assertThat(publisher.events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(MarketEventType.TRADE_EXECUTED);
            assertThat(event.seq()).isEqualTo(1L);
            assertThat(event.symbol()).isEqualTo("BTC-USD");
            assertThat(event.occurredAt()).isEqualTo(NOW);
            assertThat(event.tradeId()).isEqualTo(TRADE_ID);
            assertThat(event.buyOrderId()).isEqualTo(BUY_ID);
            assertThat(event.sellOrderId()).isEqualTo(SELL_ID);
            assertThat(event.price()).isEqualByComparingTo("10.00");
            assertThat(event.quantity()).isEqualByComparingTo("2");
            // Order members stay empty on a trade event.
            assertThat(event.orderId()).isNull();
            assertThat(event.side()).isNull();
            assertThat(event.orderType()).isNull();
            assertThat(event.remainingQuantity()).isNull();
            assertThat(event.status()).isNull();
        });
    }

    private static OrderView order(String symbol, OrderStatus status, String price, String quantity,
                                   String remaining) {
        return new OrderView(ORDER_ID, symbol, Side.BUY, OrderType.LIMIT, new BigDecimal(price),
                new BigDecimal(quantity), new BigDecimal(remaining), status, NOW);
    }

    /** Captures the published events instead of sending them anywhere. */
    static final class RecordingPublisher implements MarketEventPublisher {

        final List<MarketEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void publish(MarketEvent e) {
            events.add(e);
        }
    }
}
