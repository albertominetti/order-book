package com.albertominetti.orderbook.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of an {@link Order}.
 *
 * <p>The engine mutates {@link Order} instances in place while holding its lock; every value
 * that leaves the engine is exposed as an {@code OrderView} created inside that critical
 * section, so callers can safely read it without any locking.</p>
 *
 * @param id                 unique order id
 * @param symbol             instrument the order belongs to
 * @param side               BUY or SELL
 * @param type               LIMIT or MARKET
 * @param price              limit price, {@code null} for MARKET orders
 * @param quantity           original quantity
 * @param remainingQuantity  quantity still open (0 when fully filled)
 * @param status             current lifecycle status
 * @param timestamp          submission instant
 */
public record OrderView(
        UUID id,
        String symbol,
        Side side,
        OrderType type,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal remainingQuantity,
        OrderStatus status,
        Instant timestamp
) {

    /** Quantity already executed against this order. */
    public BigDecimal filledQuantity() {
        return quantity.subtract(remainingQuantity);
    }
}
