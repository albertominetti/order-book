package com.albertominetti.orderbook.web;

import com.albertominetti.orderbook.dto.CreateOrderRequest;
import com.albertominetti.orderbook.dto.MatchResponse;
import com.albertominetti.orderbook.dto.OrderBookResponse;
import com.albertominetti.orderbook.dto.OrderResponse;
import com.albertominetti.orderbook.dto.TradeResponse;
import com.albertominetti.orderbook.engine.MatchingEngine;
import com.albertominetti.orderbook.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * REST API of the order book. Every endpoint lives under {@code /api}.
 */
@RestController
@RequestMapping("/api")
@Validated
public class OrderController {

    /** Default number of trades returned by {@code GET /api/trades}. */
    static final int DEFAULT_TRADE_LIMIT = 50;

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Submits an order and returns it in its final state plus the trades it generated.
     *
     * @return 201 with a {@code Location} header pointing to the new order
     */
    @PostMapping("/orders")
    public ResponseEntity<MatchResponse> submitOrder(@Valid @RequestBody CreateOrderRequest request) {
        MatchResponse response = MatchResponse.from(orderService.submitOrder(request));
        URI location = UriComponentsBuilder.fromPath("/api/orders/{id}")
                .buildAndExpand(response.order().id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    /**
     * Cancels an order that is still resting on the book.
     *
     * @return 200 with the cancelled order
     */
    @DeleteMapping("/orders/{id}")
    public OrderResponse cancelOrder(@PathVariable UUID id) {
        return OrderResponse.from(orderService.cancelOrder(id));
    }

    /** Returns a single order, including terminal (filled or cancelled) ones. */
    @GetMapping("/orders/{id}")
    public OrderResponse getOrder(@PathVariable UUID id) {
        return OrderResponse.from(orderService.getOrder(id));
    }

    /** Aggregated book snapshot with best bid, best ask and spread. */
    @GetMapping("/orderbook")
    public OrderBookResponse getOrderBook() {
        return OrderBookResponse.from(orderService.getBookSnapshot());
    }

    /**
     * Most recent trades, newest first.
     *
     * @param limit how many trades to return (1..{@value MatchingEngine#MAX_RECENT_TRADES})
     */
    @GetMapping("/trades")
    public List<TradeResponse> getTrades(
            @RequestParam(defaultValue = "" + DEFAULT_TRADE_LIMIT)
            @Min(value = 1, message = "limit must be at least 1")
            @Max(value = MatchingEngine.MAX_RECENT_TRADES, message = "limit must be at most 1000")
            int limit) {
        return orderService.getRecentTrades(limit).stream()
                .map(TradeResponse::from)
                .toList();
    }
}