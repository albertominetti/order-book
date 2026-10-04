package com.albertominetti.orderbook.service;

import com.albertominetti.orderbook.domain.OrderType;
import com.albertominetti.orderbook.domain.OrderView;
import com.albertominetti.orderbook.domain.Trade;
import com.albertominetti.orderbook.dto.CreateOrderRequest;
import com.albertominetti.orderbook.engine.MatchResult;
import com.albertominetti.orderbook.engine.MatchingEngine;
import com.albertominetti.orderbook.exception.InvalidOrderException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Application service around the {@link MatchingEngine}.
 *
 * <p>It adds the request-level validation rules that Bean Validation cannot express
 * and adapts the domain model to the REST layer.</p>
 */
@Service
public class OrderService {

    private final MatchingEngine engine;

    public OrderService(MatchingEngine engine) {
        this.engine = engine;
    }

    /**
     * Validates and submits an order.
     *
     * @throws InvalidOrderException if the payload breaks a business rule
     */
    public MatchResult submitOrder(CreateOrderRequest request) {
        validate(request);
        return engine.submit(request.side(), request.type(), request.price(), request.quantity());
    }

    /** Looks up an order by id; terminal orders are still available. */
    public OrderView getOrder(UUID orderId) {
        if (orderId == null) {
            throw new InvalidOrderException("order id must not be null");
        }
        return engine.findOrder(orderId);
    }

    /** Cancels an order that is still resting on the book. */
    public OrderView cancelOrder(UUID orderId) {
        if (orderId == null) {
            throw new InvalidOrderException("order id must not be null");
        }
        return engine.cancel(orderId);
    }

    public MatchingEngine.BookSnapshot getBookSnapshot() {
        return engine.snapshot();
    }

    /** Recent trades, newest first. */
    public List<Trade> getRecentTrades(int limit) {
        return engine.recentTrades(limit);
    }

    /**
     * Cross-field rules: a LIMIT order needs a price, a MARKET order must not carry one.
     */
    private void validate(CreateOrderRequest request) {
        if (request == null || request.side() == null || request.type() == null) {
            throw new InvalidOrderException("side and type are required");
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