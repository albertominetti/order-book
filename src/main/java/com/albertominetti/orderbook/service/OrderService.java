package com.albertominetti.orderbook.service;

import com.albertominetti.orderbook.domain.InstrumentStats;
import com.albertominetti.orderbook.domain.OrderType;
import com.albertominetti.orderbook.domain.OrderView;
import com.albertominetti.orderbook.domain.SymbolRules;
import com.albertominetti.orderbook.domain.Trade;
import com.albertominetti.orderbook.dto.CreateOrderRequest;
import com.albertominetti.orderbook.engine.MatchResult;
import com.albertominetti.orderbook.engine.MatchingEngine;
import com.albertominetti.orderbook.exception.InvalidOrderException;
import com.albertominetti.orderbook.exception.OrderNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application service around the {@link MarketRegistry}.
 *
 * <p>It routes every request to the engine of its symbol, adds the request-level validation rules
 * that Bean Validation cannot express, and adapts the domain model to the REST layer.</p>
 *
 * <p>Because an order id is unique across the whole market but lives in a single engine, the service
 * keeps a global index from order id to symbol, so {@code GET} and {@code DELETE /api/orders/{id}}
 * can find the right engine without asking the caller for a symbol.</p>
 */
@Service
public class OrderService {

    private final MarketRegistry registry;

    /** Global index: every accepted order id mapped to the instrument that owns it. */
    private final ConcurrentHashMap<UUID, String> symbolByOrderId = new ConcurrentHashMap<>();

    public OrderService(MarketRegistry registry) {
        this.registry = registry;
    }

    /**
     * Validates and submits an order to the book of its instrument, creating that instrument
     * on first use.
     *
     * @throws InvalidOrderException if the payload breaks a business rule
     */
    public MatchResult submitOrder(CreateOrderRequest request) {
        validate(request);
        String symbol = SymbolRules.normalize(request.symbol());
        MatchingEngine engine = registry.engineFor(symbol);

        MatchResult result = engine.submit(request.side(), request.type(), request.price(), request.quantity());
        symbolByOrderId.put(result.order().id(), symbol);
        return result;
    }

    /** Looks up an order by id on any instrument; terminal orders are still available. */
    public OrderView getOrder(UUID orderId) {
        return engineOf(orderId).findOrder(orderId);
    }

    /** Cancels an order that is still resting on the book of its own instrument. */
    public OrderView cancelOrder(UUID orderId) {
        return engineOf(orderId).cancel(orderId);
    }

    /** Aggregated snapshot of one instrument. */
    public MatchingEngine.BookSnapshot getBookSnapshot(String rawSymbol) {
        return registry.engineOrThrow(rawSymbol).snapshot();
    }

    /** Recent trades of one instrument, newest first. */
    public List<Trade> getRecentTrades(String rawSymbol, int limit) {
        return registry.engineOrThrow(rawSymbol).recentTrades(limit);
    }

    /** Every active instrument with its stats, sorted by symbol. */
    public List<InstrumentStats> listInstruments() {
        return registry.engines().stream()
                .map(MatchingEngine::stats)
                .sorted(Comparator.comparing(InstrumentStats::symbol))
                .toList();
    }

    /** Instrument owning the given order, or a 404 when the id is unknown. */
    private MatchingEngine engineOf(UUID orderId) {
        if (orderId == null) {
            throw new InvalidOrderException("order id must not be null");
        }
        String symbol = symbolByOrderId.get(orderId);
        if (symbol == null) {
            throw new OrderNotFoundException("order " + orderId + " not found");
        }
        return registry.engineOrThrow(symbol);
    }

    /** Read-only view of the global order index, for diagnostics and tests. */
    Map<UUID, String> orderIndex() {
        return Map.copyOf(symbolByOrderId);
    }

    /**
     * Cross-field rules: a symbol is mandatory, a LIMIT order needs a price and
     * a MARKET order must not carry one.
     */
    private void validate(CreateOrderRequest request) {
        if (request == null || request.side() == null || request.type() == null) {
            throw new InvalidOrderException("side and type are required");
        }
        if (request.symbol() == null || request.symbol().isBlank()) {
            throw new InvalidOrderException("symbol is required");
        }
        if (request.quantity() == null || request.quantity().signum() <= 0) {
            throw new InvalidOrderException("quantity must be greater than 0");
        }
        if (request.type() == OrderType.LIMIT) {
            if (request.price() == null || request.price().signum() <= 0) {
                throw new InvalidOrderException("price must be greater than 0 for LIMIT orders");
            }
        } else if (request.price() != null) {
            throw new InvalidOrderException("price must not be provided for MARKET orders");
        }
    }
}
