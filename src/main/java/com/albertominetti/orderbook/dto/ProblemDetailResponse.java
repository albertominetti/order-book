package com.albertominetti.orderbook.dto;

import java.net.URI;
import java.util.List;

/**
 * Single error shape returned by every failing endpoint, following RFC 7807
 * ({@code application/problem+json}).
 *
 * <p>Besides the five standard members ({@code type}, {@code title}, {@code status}, {@code detail},
 * {@code instance}) it carries two extension members: {@code code}, a stable machine-readable error
 * code, and {@code violations}, the field-level validation details. The {@code flowId} extension
 * repeats the value of the {@code X-Flow-ID} response header, so a single request can be traced on
 * both the wire and in the body.</p>
 *
 * <p>Property names are kept in lowerCamelCase, a deliberate deviation from the Zalando snake_case
 * convention, to stay consistent with the rest of this API. {@code null} members (for example an
 * empty {@code violations} list or a missing request) are omitted from the JSON payload: the mapper
 * is configured with {@code spring.jackson.default-property-inclusion=non_null}.</p>
 *
 * @param type       a URI identifying the problem type, {@code about:blank} when there is none
 * @param title      short human-readable summary, the HTTP reason phrase
 * @param status     HTTP status code
 * @param detail     human-readable explanation of this specific occurrence
 * @param instance   the request URI that produced the problem
 * @param code       stable machine-readable error code
 * @param flowId     the trace id shared with the {@code X-Flow-ID} header
 * @param violations field-level validation details, omitted when empty
 */
public record ProblemDetailResponse(
        URI type,
        String title,
        int status,
        String detail,
        URI instance,
        String code,
        String flowId,
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
