package com.albertominetti.orderbook.domain;

/**
 * Lifecycle of an order.
 *
 * <ul>
 *   <li>{@code NEW} - accepted, nothing filled yet</li>
 *   <li>{@code PARTIALLY_FILLED} - at least one fill happened, still open</li>
 *   <li>{@code FILLED} - fully executed, no longer on the book</li>
 *   <li>{@code CANCELLED} - removed from the book (an unfilled MARKET remainder also ends here)</li>
 * </ul>
 */
public enum OrderStatus {
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED;

    /** Terminal statuses can never change again. */
    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED;
    }
}
