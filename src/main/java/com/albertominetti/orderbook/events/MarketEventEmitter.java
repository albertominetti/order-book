package com.albertominetti.orderbook.events;

import com.albertominetti.orderbook.domain.OrderView;
import com.albertominetti.orderbook.domain.Trade;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds the typed market events and hands them to the configured {@link MarketEventPublisher}.
 *
 * <p>Every event is stamped with the application {@link Clock} and with a per-symbol sequence
 * number, so the events of one instrument form an ordered stream that a consumer can check for
 * gaps. Which publisher is behind the port is a configuration decision, so this component is the
 * single place that turns a domain outcome into an event.</p>
 */
@Component
public class MarketEventEmitter {

    private final MarketEventPublisher publisher;
    private final Clock clock;
    private final ConcurrentHashMap<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    public MarketEventEmitter(MarketEventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    public void orderAccepted(OrderView order) {
        publisher.publish(new MarketEvent(nextSeq(order.symbol()), Instant.now(clock), order.symbol(),
                MarketEventType.ORDER_ACCEPTED, order.id(), order.side(), order.type(), order.price(),
                order.quantity(), order.remainingQuantity(), order.status(), null, null, null));
    }

    public void orderCancelled(OrderView order) {
        publisher.publish(new MarketEvent(nextSeq(order.symbol()), Instant.now(clock), order.symbol(),
                MarketEventType.ORDER_CANCELLED, order.id(), order.side(), order.type(), order.price(),
                order.quantity(), order.remainingQuantity(), order.status(), null, null, null));
    }

    public void tradeExecuted(Trade trade) {
        publisher.publish(new MarketEvent(nextSeq(trade.symbol()), Instant.now(clock), trade.symbol(),
                MarketEventType.TRADE_EXECUTED, null, null, null, trade.price(), trade.quantity(),
                null, null, trade.id(), trade.buyOrderId(), trade.sellOrderId()));
    }

    private long nextSeq(String symbol) {
        return sequences.computeIfAbsent(symbol, s -> new AtomicLong()).incrementAndGet();
    }
}