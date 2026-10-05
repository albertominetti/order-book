package com.albertominetti.orderbook.events;

/**
 * The kind of market event carried by a {@link MarketEvent}.
 */
public enum MarketEventType {
    ORDER_ACCEPTED,
    ORDER_CANCELLED,
    TRADE_EXECUTED
}
