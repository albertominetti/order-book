package com.albertominetti.orderbook.domain;

import java.math.BigDecimal;

/**
 * Aggregated view of a single price level of the book.
 *
 * @param price      the price of the level
 * @param quantity   sum of the remaining quantity of every order resting on the level
 * @param orderCount number of orders resting on the level
 */
public record PriceLevel(BigDecimal price, BigDecimal quantity, int orderCount) {
}
