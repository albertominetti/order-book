package com.albertominetti.orderbook.web;

import com.albertominetti.orderbook.domain.InstrumentRef;
import com.albertominetti.orderbook.domain.SymbolRules;
import com.albertominetti.orderbook.dto.CreateOrderRequest;
import com.albertominetti.orderbook.dto.InstrumentStatsResponse;
import com.albertominetti.orderbook.dto.MatchResponse;
import com.albertominetti.orderbook.dto.OrderBookResponse;
import com.albertominetti.orderbook.dto.OrderResponse;
import com.albertominetti.orderbook.dto.TradeResponse;
import com.albertominetti.orderbook.engine.MatchingEngine;
import com.albertominetti.orderbook.exception.OrderNotFoundException;
import com.albertominetti.orderbook.exception.OrderStateException;
import com.albertominetti.orderbook.exception.UnknownInstrumentException;
import com.albertominetti.orderbook.service.InstrumentCatalog;
import com.albertominetti.orderbook.service.MarketBroadcaster;
import com.albertominetti.orderbook.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
 *
 * <p>Orders are routed by symbol, while order lookups are global: an order id is unique across the
 * whole market, so {@code /api/orders/{id}} does not need the symbol.</p>
 */
@RestController
@RequestMapping("/api")
@Validated
public class OrderController {

    /** Default number of trades returned by the per-instrument trade tape. */
    static final int DEFAULT_TRADE_LIMIT = 50;

    /** Default number of instruments returned by the catalogue search. */
    static final int DEFAULT_INSTRUMENT_SEARCH_LIMIT = 50;

    /** Largest number of instruments a single catalogue search can return. */
    static final int MAX_INSTRUMENT_SEARCH_LIMIT = 200;

    private final OrderService orderService;
    private final InstrumentCatalog instrumentCatalog;
    private final MarketBroadcaster broadcaster;

    public OrderController(OrderService orderService, InstrumentCatalog instrumentCatalog,
                           MarketBroadcaster broadcaster) {
        this.orderService = orderService;
        this.instrumentCatalog = instrumentCatalog;
        this.broadcaster = broadcaster;
    }

    /**
     * Submits an order on the instrument it names and returns it in its final state plus the
     * trades it generated. The instrument is created on first use.
     *
     * @return 201 with a {@code Location} header pointing to the new order
     */
    @PostMapping("/orders")
    public ResponseEntity<MatchResponse> submitOrder(@Valid @RequestBody CreateOrderRequest request) {
        MatchResponse response = MatchResponse.from(orderService.submitOrder(request));
        publish(response.order().symbol(), response.trades());
        URI location = UriComponentsBuilder.fromPath("/api/orders/{id}")
                .buildAndExpand(response.order().id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    /**
     * Returns a single order of any instrument, including terminal (filled or cancelled) ones.
     *
     * @throws OrderNotFoundException when the id is unknown (404)
     */
    @GetMapping("/orders/{id}")
    public OrderResponse getOrder(@PathVariable UUID id) {
        return OrderResponse.from(orderService.getOrder(id));
    }

    /**
     * Cancels an order still resting on the book of its own instrument.
     *
     * @throws OrderNotFoundException when the id is unknown (404)
     * @throws OrderStateException    when the order is not resting any more (422)
     */
    @DeleteMapping("/orders/{id}")
    public OrderResponse cancelOrder(@PathVariable UUID id) {
        OrderResponse response = OrderResponse.from(orderService.cancelOrder(id));
        publishBookOnly(response.symbol());
        return response;
    }

    /** Every active instrument with its resting orders, best prices and last price. */
    @GetMapping("/instruments")
    public List<InstrumentStatsResponse> getInstruments() {
        return orderService.listInstruments().stream()
                .map(InstrumentStatsResponse::from)
                .toList();
    }

    /**
     * Searches the instrument catalogue of tradable symbols, the same source the UI dropdown uses.
     *
     * <p>Instruments whose symbol starts with {@code q} come first, then the instruments whose name
     * contains it, each group sorted by symbol. A blank query returns the head of the catalogue, so
     * the picker can open on something useful. This endpoint never creates a book: it only suggests
     * symbols, and {@code POST /api/orders} is still the only way to bring an instrument to life.</p>
     *
     * @param query free text to match, defaults to blank
     * @param limit how many instruments to return (1..{@value #MAX_INSTRUMENT_SEARCH_LIMIT})
     */
    @GetMapping("/instruments/search")
    public List<InstrumentRef> searchInstruments(
            @RequestParam(name = "q", defaultValue = "") String query,
            @RequestParam(defaultValue = "" + DEFAULT_INSTRUMENT_SEARCH_LIMIT)
            @Min(value = 1, message = "limit must be at least 1")
            @Max(value = MAX_INSTRUMENT_SEARCH_LIMIT,
                    message = "limit must be at most " + MAX_INSTRUMENT_SEARCH_LIMIT)
            int limit) {
        return instrumentCatalog.search(query, limit);
    }

    /**
     * Aggregated book snapshot of one instrument, with best bid, best ask and spread.
     *
     * @throws UnknownInstrumentException when nothing was ever traded on the symbol (404)
     */
    @GetMapping("/instruments/{symbol}/orderbook")
    public OrderBookResponse getOrderBook(@PathVariable
                                          @Pattern(regexp = SymbolRules.RAW_PATTERN_SOURCE,
                                                  message = "symbol must match " + SymbolRules.PATTERN_SOURCE)
                                          @NotBlank(message = "symbol is required")
                                          String symbol) {
        return OrderBookResponse.from(orderService.getBookSnapshot(symbol));
    }

    /**
     * Recent trades of one instrument, newest first.
     *
     * @param limit how many trades to return (1..{@value MatchingEngine#MAX_RECENT_TRADES})
     * @throws UnknownInstrumentException when nothing was ever traded on the symbol (404)
     */
    @GetMapping("/instruments/{symbol}/trades")
    public List<TradeResponse> getTrades(
            @PathVariable
            @Pattern(regexp = SymbolRules.RAW_PATTERN_SOURCE,
                    message = "symbol must match " + SymbolRules.PATTERN_SOURCE)
            @NotBlank(message = "symbol is required")
            String symbol,
            @RequestParam(defaultValue = "" + DEFAULT_TRADE_LIMIT)
            @Min(value = 1, message = "limit must be at least 1")
            @Max(value = MatchingEngine.MAX_RECENT_TRADES, message = "limit must be at most 1000")
            int limit) {
        return orderService.getRecentTrades(symbol, limit).stream()
                .map(TradeResponse::from)
                .toList();
    }

    /**
     * Pushes the new state of one instrument to whoever subscribed to its stream. It runs after the
     * engine released its lock, so a slow subscriber can never slow the matching down; the payloads
     * are the same response records the REST endpoints return, so both channels carry the same JSON.
     *
     * @param rawSymbol the instrument whose state changed
     * @param trades    the trades the mutation generated, or an empty list to stream the current tape
     */
    private void publish(String rawSymbol, List<TradeResponse> trades) {
        String symbol = SymbolRules.normalize(rawSymbol);
        publishBookOnly(symbol);
        broadcaster.publishTrades(symbol, trades);
    }

    /**
     * Pushes only the book (and the instrument list) of one instrument: a cancellation changes the
     * book but generates no trade, so no {@code trades} event is emitted.
     *
     * @param rawSymbol the instrument whose book changed
     */
    private void publishBookOnly(String rawSymbol) {
        String symbol = SymbolRules.normalize(rawSymbol);
        broadcaster.publishBook(symbol, OrderBookResponse.from(orderService.getBookSnapshot(symbol)));
        broadcaster.publishInstruments(broadcaster.instrumentStats());
    }
}

