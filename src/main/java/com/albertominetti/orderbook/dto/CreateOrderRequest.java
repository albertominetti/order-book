package com.albertominetti.orderbook.dto;

import com.albertominetti.orderbook.domain.OrderType;
import com.albertominetti.orderbook.domain.Side;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Payload of {@code POST /api/orders}.
 *
 * <p>{@code price} is mandatory for LIMIT orders and must be absent for MARKET orders;
 * that cross-field rule is enforced in the service layer and answered with HTTP 400.</p>
 *
 * @param side     mandatory BUY or SELL
 * @param type     mandatory LIMIT or MARKET
 * @param price    positive price for LIMIT, {@code null} for MARKET
 * @param quantity mandatory, strictly positive
 */
public record CreateOrderRequest(
        @NotNull(message = "side is required (BUY or SELL)")
        Side side,

        @NotNull(message = "type is required (LIMIT or MARKET)")
        OrderType type,

        @DecimalMin(value = "0", inclusive = false, message = "price must be greater than 0")
        @Digits(integer = 12, fraction = 8, message = "price must have at most 8 decimal places")
        BigDecimal price,

        @NotNull(message = "quantity is required")
        @Positive(message = "quantity must be greater than 0")
        @Digits(integer = 12, fraction = 8, message = "quantity must have at most 8 decimal places")
        BigDecimal quantity
) {
}