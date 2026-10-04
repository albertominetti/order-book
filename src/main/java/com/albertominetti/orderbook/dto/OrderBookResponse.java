package com.albertominetti.orderbook.dto;

import com.albertominetti.orderbook.engine.MatchingEngine;

import java.math.BigDecimal;
import java.util.List;

/**
 * Snapshot of the book of one instrument, returned by {@code GET /api/instruments/{symbol}/orderbook}.
 *
 * @param bids      bid levels, best (highest) price first
 * @param asks      ask levels, best (lowest) price first
 * @param bestBid   highest resting bid, {@code null} when there are no bids
 * @param bestAsk   lowest resting ask, {@code null} when there are no asks
 * @param spread    bestAsk - bestBid, {@code null} when one side is empty
 * @param lastPrice price of the latest trade, {@code null} when nothing has traded yet
 */
public record OrderBookResponse(
        List<PriceLevelResponse> bids,
        List<PriceLevelResponse> asks,
        BigDecimal bestBid,
        BigDecimal bestAsk,
        BigDecimal spread,
        BigDecimal lastPrice
) {

    public static OrderBookResponse from(MatchingEngine.BookSnapshot snapshot) {
        return new OrderBookResponse(
                snapshot.bids().stream().map(PriceLevelResponse::from).toList(),
                snapshot.asks().stream().map(PriceLevelResponse::from).toList(),
                snapshot.bestBid(),
                snapshot.bestAsk(),
                snapshot.spread(),
                snapshot.lastPrice());
    }

    /**
     * The book of an instrument that has no resting order yet: no level on either side, and no best
     * price, spread or last price either.
     *
     * <p>The push channel streams this to a subscriber of a symbol whose book does not exist yet, so
     * opening a stream is never an error and the client has something to render right away.</p>
     */
    public static OrderBookResponse empty() {
        return new OrderBookResponse(List.of(), List.of(), null, null, null, null);
    }
}
