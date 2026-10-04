package com.albertominetti.orderbook.domain;

/**
 * Order type.
 *
 * <p>{@code LIMIT} orders carry a price and may rest on the book.
 * {@code MARKET} orders have no price: they execute immediately against the
 * available liquidity and any unfilled remainder is discarded.</p>
 */
public enum OrderType {
    LIMIT,
    MARKET
}
