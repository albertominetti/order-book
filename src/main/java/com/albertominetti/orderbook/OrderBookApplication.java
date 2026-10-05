package com.albertominetti.orderbook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the in-memory order book / matching engine REST API.
 *
 * <p>Scheduling is enabled for the single keep-alive task of
 * {@link com.albertominetti.orderbook.service.MarketStreamBroadcaster}, which keeps the Server-Sent
 * Events stream channel to the UI alive through idle periods and proxies.</p>
 */
@SpringBootApplication
@EnableScheduling
@ImportRuntimeHints(OrderBookRuntimeHints.class)
public class OrderBookApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderBookApplication.class, args);
    }
}
