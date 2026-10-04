package com.albertominetti.orderbook.web;

import com.albertominetti.orderbook.domain.SymbolRules;
import com.albertominetti.orderbook.service.MarketBroadcaster;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events streams, the push channel of the market data.
 *
 * <p>They are an addition to the REST endpoints, never a replacement: the same payloads are served
 * by both channels, so a client may use the streams while it can, and fall back to
 * {@code GET /api/instruments/{symbol}/orderbook},
 * {@code GET /api/instruments/{symbol}/trades} and {@code GET /api/instruments} otherwise.</p>
 *
 * <p>Two streams are exposed:</p>
 * <ul>
 *   <li>{@code GET /api/instruments/{symbol}/stream} pushes {@code book} and {@code trades} events of
 *       one instrument, validated exactly like its order book endpoint;</li>
 *   <li>{@code GET /api/instruments/stream} pushes {@code instruments} events with the instrument
 *       list shared by every client.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/instruments")
@Validated
public class MarketStreamController {

    private final MarketBroadcaster broadcaster;

    public MarketStreamController(MarketBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /**
     * Opens the live stream of one instrument: the book snapshot and the recent trade tape, both
     * pushed again after every submission and every cancellation on that symbol.
     *
     * <p>Returns {@code 200} with content type {@code text/event-stream} and keeps the response open.
     * A comment is sent every 20 seconds so idle connections are not closed by a proxy.</p>
     *
     * @throws IllegalArgumentException                                            when the symbol is missing or malformed (400)
     * @throws com.albertominetti.orderbook.exception.UnknownInstrumentException when nothing was ever traded on the symbol (404)
     */
    @GetMapping(path = "/{symbol}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamInstrument(@PathVariable
                                       @Pattern(regexp = SymbolRules.RAW_PATTERN_SOURCE,
                                               message = "symbol must match " + SymbolRules.PATTERN_SOURCE)
                                       @NotBlank(message = "symbol is required")
                                       String symbol) {
        return broadcaster.subscribeBook(symbol);
    }

    /**
     * Opens the market stream: the list of active instruments with their stats, pushed again after
     * every submission and every cancellation.
     *
     * @return an open {@code text/event-stream} response
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamInstruments() {
        return broadcaster.subscribeInstruments();
    }
}