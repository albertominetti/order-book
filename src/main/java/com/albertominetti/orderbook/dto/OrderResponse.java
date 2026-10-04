package com.albertominetti.orderbook.dto;

import com.albertominetti.orderbook.domain.OrderStatus;
import com.albertominetti.orderbook.domain.OrderType;
import com.albertominetti.orderbook.domain.OrderView;
import com.albertominetti.orderbook.domain.Side;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public representation of an order.
 *
 * @param id                unique order id
 * @param side              BUY or SELL
 * @param type              LIMIT or MARKET
 * @param price             limit price, {@code null} for MARKET orders
 * @param quantity          original quantity
 * @param remainingQuantity quantity still open
 * @param filledQuantity    quantity executed so far
 * @param status            lifecycle status
 * @param timestamp         submission instant
 */
public record OrderResponse(
        UUID id,
        Side side,
        OrderType type,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal remainingQuantity,
        BigDecimal filledQuantity,
        OrderStatus status,
        Instant timestamp
) {

    public static OrderResponse from(OrderView order) {
        return new OrderResponse(
                order.id(),
                order.side(),
                order.type(),
                order.price(),
                order.quantity(),
                order.remainingQuantity(),
                order.filledQuantity(),
                order.status(),
                order.timestamp());
    }
}
