package com.albertominetti.orderbook.service;

import com.albertominetti.orderbook.engine.MatchingEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the single matching engine instance, shared by every request
 * for the lifetime of the application.
 */
@Configuration
public class EngineConfiguration {

    @Bean
    public MatchingEngine matchingEngine() {
        return new MatchingEngine();
    }
}
