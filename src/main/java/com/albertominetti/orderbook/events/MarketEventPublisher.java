package com.albertominetti.orderbook.events;

/**
 * The port the domain publishes market events through.
 *
 * <p>Deliberately free of any broker technology: the application depends on this interface only,
 * and the adapter in use is decided at configuration time (see
 * {@link MarketEventConfiguration}).</p>
 *
 * <p>This is the reporting event stream: append-only facts for backend consumers, independent of
 * the SSE stream channel to the UI served by
 * {@link com.albertominetti.orderbook.service.MarketStreamBroadcaster}, which pushes full-state
 * snapshots to browsers instead.</p>
 */
public interface MarketEventPublisher {

    void publish(MarketEvent event);
}
