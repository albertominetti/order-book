package com.albertominetti.orderbook;

import java.util.regex.Pattern;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import com.albertominetti.orderbook.events.MarketEvent;

/**
 * GraalVM native image runtime hints for resources and types that are only reached at runtime.
 *
 * <p>The native image must be instructed to include custom classpath resources that are not
 * detected by static analysis. In particular, {@code InstrumentCatalog} reads
 * {@code classpath:instruments.csv} at runtime to build the instrument catalogue. The application
 * also serves static assets from {@code static/**} and uses templates from {@code templates/**}
 * (including the SPA under {@code static/app/**}, which is covered by {@code static/**}).</p>
 *
 * <p>It must also be instructed to keep the {@link MarketEvent} record reflectively reachable:
 * the optional Kafka publisher serializes it to JSON, and Jackson reflects over the record
 * components at runtime. Without this hint GraalVM throws
 * {@code UnsupportedFeatureError: Record components not available} on the first publication.</p>
 */
public class OrderBookRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // Register the instrument catalogue loaded from the classpath.
        hints.resources().registerPattern(Pattern.quote("instruments.csv"));

        // Register static assets and server-side templates.
        hints.resources().registerPattern("static/.*");
        hints.resources().registerPattern("templates/.*");

        // Keep the market event record (and its components/accessors) available for JSON binding.
        hints.reflection().registerType(MarketEvent.class, MemberCategory.values());
    }
}
