package com.albertominetti.orderbook.exception;

/**
 * Thrown when a request is syntactically valid but breaks a business rule
 * (for example cancelling an order that is no longer resting): mapped to HTTP 422.
 */
public class OrderStateException extends RuntimeException {

    public OrderStateException(String message) {
        super(message);
    }
}
