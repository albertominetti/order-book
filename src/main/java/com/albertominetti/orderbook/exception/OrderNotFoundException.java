package com.albertominetti.orderbook.exception;

/** Thrown when an order id is unknown: mapped to HTTP 404. */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String message) {
        super(message);
    }
}