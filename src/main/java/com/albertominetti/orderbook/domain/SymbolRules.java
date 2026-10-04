package com.albertominetti.orderbook.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalization and validation rules shared by every layer that touches an instrument symbol.
 *
 * <p>A symbol is the identity of a tradable instrument. It is always stored normalized
 * (trimmed and upper-cased) so {@code " btc-usd "}, {@code "Btc-Usd"} and {@code "BTC-USD"}
 * all address the very same book.</p>
 *
 * <p>Valid shape: {@code [A-Z0-9][A-Z0-9._-]{0,19}} after normalization, that is one to
 * twenty characters, starting with a letter or a digit and then accepting letters, digits,
 * dots, underscores and dashes.</p>
 */
public final class SymbolRules {

    /** Regular expression source for the accepted symbols, reusable in Bean Validation. */
    public static final String PATTERN_SOURCE = "[A-Z0-9][A-Z0-9._-]{0,19}";

    /** Same rule, tolerant on the case, for validating raw (non normalized) input. */
    public static final String RAW_PATTERN_SOURCE = "[A-Za-z0-9][A-Za-z0-9._-]{0,19}";

    /** Maximum accepted symbol length. */
    public static final int MAX_LENGTH = 20;

    private static final Pattern NORMALIZED = Pattern.compile(PATTERN_SOURCE);

    private SymbolRules() {
    }

    /**
     * Trims and upper-cases the given symbol.
     *
     * @return the normalized symbol, or {@code null} when the input is {@code null} or blank
     */
    public static String trimAndUpper(String rawSymbol) {
        if (rawSymbol == null) {
            return null;
        }
        String trimmed = rawSymbol.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    /** Tells whether an already normalized symbol is valid. */
    public static boolean isValid(String normalizedSymbol) {
        return normalizedSymbol != null && NORMALIZED.matcher(normalizedSymbol).matches();
    }

    /**
     * Validates a raw symbol and returns its normalized form.
     *
     * @throws IllegalArgumentException when the symbol is missing or does not match the shape,
     *                                  which the web layer maps to HTTP 400
     */
    public static String normalize(String rawSymbol) {
        String normalized = trimAndUpper(rawSymbol);
        if (normalized == null) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (!isValid(normalized)) {
            throw new IllegalArgumentException("symbol '" + rawSymbol
                    + "' is invalid: it must match " + PATTERN_SOURCE);
        }
        return normalized;
    }
}
