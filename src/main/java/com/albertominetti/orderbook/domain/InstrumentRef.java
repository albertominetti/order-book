package com.albertominetti.orderbook.domain;

/**
 * A reference to a tradable instrument, as published by the catalogue behind
 * {@code GET /api/instruments/search}.
 *
 * <p>It carries no state: it is a read-only pointer to a symbol, the name a human recognizes and
 * the market the instrument is quoted in. The catalogue itself is static reference data, so an
 * instrument listed here exists as a <em>tradable symbol</em> whether or not an order has already
 * created its book in the {@code MarketRegistry}.</p>
 *
 * @param symbol instrument symbol, the value accepted by every other endpoint
 * @param name   human readable instrument name
 * @param market market the instrument is quoted in, for example {@code SIX} or {@code S&P 500}
 */
public record InstrumentRef(
        String symbol,
        String name,
        String market
) {
}