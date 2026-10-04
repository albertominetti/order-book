package com.albertominetti.orderbook.dto;

import com.albertominetti.orderbook.domain.Trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public representation of a trade.
 *
 * @param id          unique trade id
 * @param buyOrderId  id of the buy order
 * @param sellOrderId id of the sell order
 * @param price       execution price (resting order price)
 * @param quantity    executed quantity
 * @param timestamp   execution instant
 */
public record TradeResponse(
        UUID id,
        UUID buyOrderId,
        UUID sellOrderId,
        BigDecimal price,
        BigDecimal quantity,
        Instant timestamp
) {

    public static TradeResponse from(Trade trade) {
        return new TradeResponse(
                trade.id(),
                trade.buyOrderId(),
                trade.sellOrderId(),
                trade.price(),
                trade.quantity(),
                trade.timestamp());
    }
}
