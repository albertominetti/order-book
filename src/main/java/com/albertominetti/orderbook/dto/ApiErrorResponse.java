package com.albertominetti.orderbook.dto;

import java.time.Instant;
import java.util.List;

/**
 * Single error shape returned by every failing endpoint.
 *
 * <p>{@code null} fields (for example an empty {@code violations} list) are omitted from the
 * JSON payload: the mapper is configured with {@code spring.jackson.default-property-inclusion=non_null}.</p>
 *
 * @param timestamp  when the error was produced
 * @param status     HTTP status code
 * @param error      HTTP reason phrase
 * @param code       stable machine-readable error code
 * @param message    human-readable explanation
 * @param path       request path that failed
 * @param violations field-level validation details, omitted when empty
 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        List<Violation> violations
) {

    /**
     * Field-level validation problem.
     *
     * @param field   rejected field
     * @param message why it was rejected
     */
    public record Violation(String field, String message) {
    }
}