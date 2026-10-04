package com.albertominetti.orderbook.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A match between two orders.
 *
 * <p>The trade price is always the price of the <em>resting</em> order, which is
 * the standard price-time priority convention: a new aggressive order never
 * improves the price of the level it consumes.</p>
 *
 * @param id           unique trade id
 * @param buyOrderId   id of the buy order
 * @param sellOrderId  id of the sell order
 * @param price        execution price (resting order price)
 * @param quantity     executed quantity
 * @param timestamp    execution instant
 */
public record Trade(
        UUID id,
        UUID buyOrderId,
        UUID sellOrderId,
        BigDecimal price,
        BigDecimal quantity,
        Instant timestamp
) {
}