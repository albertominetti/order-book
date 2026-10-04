package com.albertominetti.orderbook.dto;

import com.albertominetti.orderbook.domain.PriceLevel;

import java.math.BigDecimal;

/**
 * One aggregated price level of the book.
 *
 * @param price      price of the level
 * @param quantity   total remaining quantity resting on the level
 * @param orderCount number of orders on the level
 */
public record PriceLevelResponse(BigDecimal price, BigDecimal quantity, int orderCount) {

    public static PriceLevelResponse from(PriceLevel level) {
        return new PriceLevelResponse(level.price(), level.quantity(), level.orderCount());
    }
}
