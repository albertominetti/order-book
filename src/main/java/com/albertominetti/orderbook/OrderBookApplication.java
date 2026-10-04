package com.albertominetti.orderbook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Entry point of the in-memory order book / matching engine REST API.
 */
@SpringBootApplication
@ImportRuntimeHints(OrderBookRuntimeHints.class)
public class OrderBookApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderBookApplication.class, args);
    }
}
