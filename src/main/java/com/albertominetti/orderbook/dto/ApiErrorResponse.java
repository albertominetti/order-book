package com.albertominetti.orderbook.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Single error shape returned by every failing endpoint.
 *
 * @param timestamp  when the error was produced
 * @param status     HTTP status code
 * @param error      HTTP reason phrase
 * @param code       stable machine-readable error code
 * @param message    human-readable explanation
 * @param path       request path that failed
 * @param violations field-level validation details, omitted when empty
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
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