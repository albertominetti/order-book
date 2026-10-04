package com.albertominetti.orderbook.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides the single {@link Clock} used to stamp orders and trades.
 *
 * <p>The matching engines are no longer declared here: they are created per symbol, on demand,
 * by the {@link MarketRegistry}.</p>
 */
@Configuration
public class EngineConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
