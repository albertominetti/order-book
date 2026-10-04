package com.albertominetti.orderbook.domain;

import java.math.BigDecimal;

/**
 * Aggregated activity of a single instrument, as returned by {@code GET /api/instruments}.
 *
 * @param symbol        instrument symbol
 * @param restingOrders number of orders currently resting on its book
 * @param bestBid       highest resting bid, {@code null} when there are no bids
 * @param bestAsk       lowest resting ask, {@code null} when there are no asks
 * @param lastPrice     price of the latest trade, {@code null} when nothing has traded yet
 */
public record InstrumentStats(
        String symbol,
        int restingOrders,
        BigDecimal bestBid,
        BigDecimal bestAsk,
        BigDecimal lastPrice
) {
}
