package com.albertominetti.orderbook.events;

/**
 * The default adapter: publication is disabled, so events are built and then dropped.
 *
 * <p>This is the bean that exists when no broker is configured, which is why the application never
 * opens a connection and never needs one to run.</p>
 */
public class NoOpMarketEventPublisher implements MarketEventPublisher {

    @Override
    public void publish(MarketEvent event) {
        // Publication disabled: no broker configured, deliberately do nothing.
    }
}