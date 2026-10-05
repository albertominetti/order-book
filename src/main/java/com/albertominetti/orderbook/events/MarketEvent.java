package com.albertominetti.orderbook.events;

import com.albertominetti.orderbook.domain.OrderStatus;
import com.albertominetti.orderbook.domain.OrderType;
import com.albertominetti.orderbook.domain.Side;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A single market event, flat and immutable so it serializes to one flat JSON object.
 *
 * <p>Not every member applies to every {@link MarketEventType}; the members that do not apply are
 * {@code null} and are omitted from the JSON (the application sets
 * {@code default-property-inclusion: non_null}):</p>
 *
 * <ul>
 *   <li>{@code ORDER_ACCEPTED} and {@code ORDER_CANCELLED} carry {@code orderId}, {@code side},
 *       {@code orderType}, {@code price} (the limit price, {@code null} for a MARKET order),
 *       {@code quantity} (the original one), {@code remainingQuantity} and {@code status}</li>
 *   <li>{@code TRADE_EXECUTED} carries {@code tradeId}, {@code buyOrderId}, {@code sellOrderId},
 *       {@code price} (the execution price) and {@code quantity} (the executed one)</li>
 * </ul>
 *
 * <p>{@code seq} is a per-symbol monotonic counter, so a consumer of one instrument can detect a gap
 * or a reordering on its own stream.</p>
 *
 * @param seq               per-symbol sequence number, starting at 1
 * @param occurredAt        instant the event was stamped, from the application clock
 * @param symbol            instrument the event belongs to, also the Kafka message key
 * @param type              kind of event
 * @param orderId           id of the order, for order events
 * @param side              BUY or SELL, for order events
 * @param orderType         LIMIT or MARKET, for order events
 * @param price             limit price for an order event, execution price for a trade
 * @param quantity          original quantity for an order event, executed quantity for a trade
 * @param remainingQuantity quantity still open, for order events
 * @param status            lifecycle status, for order events
 * @param tradeId           id of the trade, for a trade event
 * @param buyOrderId        id of the buy order, for a trade event
 * @param sellOrderId       id of the sell order, for a trade event
 */
public record MarketEvent(
        long seq,
        Instant occurredAt,
        String symbol,
        MarketEventType type,
        UUID orderId,
        Side side,
        OrderType orderType,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal remainingQuantity,
        OrderStatus status,
        UUID tradeId,
        UUID buyOrderId,
        UUID sellOrderId
) {
}