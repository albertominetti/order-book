package com.albertominetti.orderbook.exception;

/**
 * Thrown when a syntactically valid request breaks a business rule
 * (for example a LIMIT order without a price): mapped to HTTP 400.
 */
public class InvalidOrderException extends RuntimeException {

    public InvalidOrderException(String message) {
        super(message);
    }
}
