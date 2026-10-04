package com.albertominetti.orderbook.service;

import com.albertominetti.orderbook.domain.SymbolRules;
import com.albertominetti.orderbook.engine.MatchingEngine;
import com.albertominetti.orderbook.exception.UnknownInstrumentException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of the active instruments, one {@link MatchingEngine} per symbol.
 *
 * <p>Engines are created lazily, on the first order submitted for a symbol, and then live for the
 * whole application lifetime. This is what keeps the instruments isolated: an order only ever
 * reaches the engine of its own symbol, and every engine has its own lock, so two instruments are
 * matched completely independently (and in parallel).</p>
 *
 * <p>The registry is safe for concurrent use: {@link ConcurrentHashMap#computeIfAbsent} guarantees
 * that exactly one engine is ever published per symbol, even when several threads submit the very
 * first order of a new instrument at the same time.</p>
 */
@Component
public class MarketRegistry {

    private final ConcurrentHashMap<String, MatchingEngine> engines = new ConcurrentHashMap<>();
    private final Clock clock;

    public MarketRegistry(Clock clock) {
        this.clock = clock;
    }

    /**
     * Returns the engine of the given symbol, creating it on first use.
     *
     * @param rawSymbol symbol to normalize (trim + uppercase)
     * @return the engine, never {@code null}
     * @throws IllegalArgumentException when the symbol is missing or malformed (HTTP 400)
     */
    public MatchingEngine engineFor(String rawSymbol) {
        String symbol = SymbolRules.normalize(rawSymbol);
        return engines.computeIfAbsent(symbol, this::newEngine);
    }

    /**
     * Returns the engine of an already known instrument.
     *
     * @throws IllegalArgumentException    when the symbol is missing or malformed (HTTP 400)
     * @throws UnknownInstrumentException when the symbol is well formed but nothing was ever traded on it (HTTP 404)
     */
    public MatchingEngine engineOrThrow(String rawSymbol) {
        String symbol = SymbolRules.normalize(rawSymbol);
        MatchingEngine engine = engines.get(symbol);
        if (engine == null) {
            throw new UnknownInstrumentException("unknown instrument '" + symbol + "'");
        }
        return engine;
    }

    /** Symbols of the active instruments, sorted alphabetically. */
    public List<String> symbols() {
        return engines.keySet().stream().sorted().toList();
    }

    /** The engines of the active instruments, one per {@link #symbols()}. */
    public Collection<MatchingEngine> engines() {
        return engines.values();
    }

    /** Number of active instruments. */
    public int instrumentCount() {
        return engines.size();
    }

    /** Exposes the raw map for tests and diagnostics. */
    Map<String, MatchingEngine> asMap() {
        return Map.copyOf(engines);
    }

    private MatchingEngine newEngine(String symbol) {
        return new MatchingEngine(symbol, clock);
    }
}