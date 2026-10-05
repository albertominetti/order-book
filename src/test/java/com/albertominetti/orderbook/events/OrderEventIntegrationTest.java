package com.albertominetti.orderbook.events;

import com.albertominetti.orderbook.domain.OrderStatus;
import com.albertominetti.orderbook.domain.OrderType;
import com.albertominetti.orderbook.domain.Side;
import com.albertominetti.orderbook.dto.CreateOrderRequest;
import com.albertominetti.orderbook.engine.MatchResult;
import com.albertominetti.orderbook.exception.InvalidOrderException;
import com.albertominetti.orderbook.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests of the event layer through the real application context: submitting and
 * cancelling orders must produce the events a consumer of the stream would see.
 *
 * <p>The publisher behind the port is replaced by a {@link RecordingPublisher}, so no broker is
 * needed and the events are inspected directly. The registry is a per-context singleton, so the
 * context is refreshed before each test and every test starts from an empty market.</p>
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class OrderEventIntegrationTest {

    private static final String SYMBOL = "BTC-USD";

    @Autowired
    private OrderService orderService;

    @Autowired
    private RecordingPublisher publisher;

    @Test
    @DisplayName("A submission publishes an ORDER_ACCEPTED and the trades it executed")
    void submissionPublishesOrderAcceptedAndTrades() {
        orderService.submitOrder(buy("100.00", "5"));
        publisher.events.clear();

        MatchResult result = orderService.submitOrder(sell("100.00", "2"));

        assertThat(result.trades()).hasSize(1);
        assertThat(publisher.events).extracting(MarketEvent::type)
                .containsExactly(MarketEventType.ORDER_ACCEPTED, MarketEventType.TRADE_EXECUTED);

        MarketEvent accepted = publisher.events.get(0);
        assertThat(accepted.symbol()).isEqualTo(SYMBOL);
        assertThat(accepted.side()).isEqualTo(Side.SELL);
        assertThat(accepted.orderType()).isEqualTo(OrderType.LIMIT);
        assertThat(accepted.price()).isEqualByComparingTo("100.00");
        assertThat(accepted.quantity()).isEqualByComparingTo("2");
        assertThat(accepted.status()).isEqualTo(OrderStatus.FILLED);

        MarketEvent trade = publisher.events.get(1);
        assertThat(trade.tradeId()).isEqualTo(result.trades().get(0).id());
        assertThat(trade.symbol()).isEqualTo(SYMBOL);
        assertThat(trade.price()).isEqualByComparingTo("100.00");
        assertThat(trade.quantity()).isEqualByComparingTo("2");
        assertThat(trade.orderId()).isNull();

        // Per-symbol numbering keeps increasing across the submissions.
        assertThat(publisher.events).extracting(MarketEvent::seq).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("Cancelling a resting order publishes an ORDER_CANCELLED")
    void cancelPublishesOrderCancelled() {
        MatchResult submitted = orderService.submitOrder(buy("100.00", "5"));
        publisher.events.clear();

        orderService.cancelOrder(submitted.order().id());

        assertThat(publisher.events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(MarketEventType.ORDER_CANCELLED);
            assertThat(event.symbol()).isEqualTo(SYMBOL);
            assertThat(event.orderId()).isEqualTo(submitted.order().id());
            assertThat(event.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(event.seq()).isEqualTo(2L);
        });
    }

    @Test
    @DisplayName("A rejected submit publishes nothing")
    void rejectedSubmitPublishesNothing() {
        assertThatThrownBy(() -> orderService.submitOrder(new CreateOrderRequest(SYMBOL, Side.BUY,
                OrderType.LIMIT, null, new BigDecimal("5"))))
                .isInstanceOf(InvalidOrderException.class);
        assertThat(publisher.events).isEmpty();
    }

    private static CreateOrderRequest buy(String price, String quantity) {
        return new CreateOrderRequest(SYMBOL, Side.BUY, OrderType.LIMIT,
                new BigDecimal(price), new BigDecimal(quantity));
    }

    private static CreateOrderRequest sell(String price, String quantity) {
        return new CreateOrderRequest(SYMBOL, Side.SELL, OrderType.LIMIT,
                new BigDecimal(price), new BigDecimal(quantity));
    }

    /** Overrides the no-op publisher of the application so the events can be asserted. */
    @TestConfiguration
    static class RecordingPublisherConfiguration {

        @Bean
        @Primary
        RecordingPublisher recordingMarketEventPublisher() {
            return new RecordingPublisher();
        }
    }

    /** Captures the published events instead of sending them anywhere. */
    static class RecordingPublisher implements MarketEventPublisher {

        final List<MarketEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void publish(MarketEvent event) {
            events.add(event);
        }
    }
}