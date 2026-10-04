package com.albertominetti.orderbook;

import java.util.regex.Pattern;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * GraalVM native image runtime hints for resources loaded by the application at startup.
 *
 * <p>The native image must be instructed to include custom classpath resources that are not
 * detected by static analysis. In particular, {@code InstrumentCatalog} reads
 * {@code classpath:instruments.tsv} at runtime to build the instrument catalogue. The
 * application also serves static assets from {@code static/**} and uses templates from
 * {@code templates/**} (including the SPA under {@code static/app/**}, which is covered by
 * {@code static/**}).</p>
 */
public class OrderBookRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // Register the instrument catalogue loaded from the classpath.
        hints.resources().registerPattern(Pattern.quote("instruments.tsv"));

        // Register static assets and server-side templates.
        hints.resources().registerPattern("static/.*");
        hints.resources().registerPattern("templates/.*");
    }
}
