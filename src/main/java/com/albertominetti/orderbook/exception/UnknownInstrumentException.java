package com.albertominetti.orderbook.exception;

/**
 * Thrown when a query targets an instrument that has no book yet, that is a
 * well formed symbol nobody ever sent an order for: mapped to HTTP 404
 * with the {@code UNKNOWN_INSTRUMENT} code.
 */
public class UnknownInstrumentException extends RuntimeException {

    public UnknownInstrumentException(String message) {
        super(message);
    }
}