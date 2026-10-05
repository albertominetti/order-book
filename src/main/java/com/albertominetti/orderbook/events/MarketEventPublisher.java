package com.albertominetti.orderbook.events;

/**
 * The port the domain publishes market events through.
 *
 * <p>Deliberately free of any broker technology: the application depends on this interface only,
 * and the adapter in use is decided at configuration time (see
 * {@link MarketEventConfiguration}).</p>
 */
public interface MarketEventPublisher {

    void publish(MarketEvent event);
}