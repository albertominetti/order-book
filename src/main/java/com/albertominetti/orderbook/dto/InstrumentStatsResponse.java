package com.albertominetti.orderbook.dto;

import com.albertominetti.orderbook.domain.InstrumentStats;

import java.math.BigDecimal;

/**
 * One active instrument as returned by {@code GET /api/instruments}.
 *
 * @param symbol        instrument symbol
 * @param restingOrders number of orders currently resting on its book
 * @param bestBid       highest resting bid, omitted when there are no bids
 * @param bestAsk       lowest resting ask, omitted when there are no asks
 * @param lastPrice     price of the latest trade, omitted when nothing has traded yet
 */
public record InstrumentStatsResponse(
        String symbol,
        int restingOrders,
        BigDecimal bestBid,
        BigDecimal bestAsk,
        BigDecimal lastPrice
) {

    public static InstrumentStatsResponse from(InstrumentStats stats) {
        return new InstrumentStatsResponse(
                stats.symbol(),
                stats.restingOrders(),
                stats.bestBid(),
                stats.bestAsk(),
                stats.lastPrice());
    }
}