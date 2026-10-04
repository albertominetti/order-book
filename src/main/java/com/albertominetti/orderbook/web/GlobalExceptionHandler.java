package com.albertominetti.orderbook.web;

import com.albertominetti.orderbook.dto.ProblemDetailResponse;
import com.albertominetti.orderbook.exception.InvalidOrderException;
import com.albertominetti.orderbook.exception.OrderNotFoundException;
import com.albertominetti.orderbook.exception.OrderStateException;
import com.albertominetti.orderbook.exception.UnknownInstrumentException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.Comparator;
import java.util.List;

/**
 * Translates exceptions into the single RFC 7807 {@link ProblemDetailResponse} shape, served as
 * {@code application/problem+json}.
 *
 * <p>Status mapping:</p>
 * <ul>
 *   <li>400 - malformed JSON, unknown enum value, invalid field (including a malformed symbol)
 *       or failed business rule</li>
 *   <li>404 - unknown order id, unknown instrument or unknown path</li>
 *   <li>405 - unsupported HTTP method on a known path</li>
 *   <li>422 - well-formed request that breaks an order lifecycle rule</li>
 * </ul>
 *
 * <p>Stable codes: {@code VALIDATION_ERROR}, {@code MALFORMED_JSON}, {@code INVALID_PARAMETER},
 * {@code INVALID_ORDER} (400), {@code NOT_FOUND}, {@code UNKNOWN_INSTRUMENT} (404),
 * {@code METHOD_NOT_ALLOWED} (405), {@code INVALID_ORDER_STATE} (422) and {@code INTERNAL_ERROR}
 * (500).</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Value of the RFC 7807 {@code type} member when no more specific type is defined. */
    private static final URI GENERIC_TYPE = URI.create("about:blank");

    /** Bean Validation failed on the request body. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetailResponse> handleBodyValidation(MethodArgumentNotValidException ex,
                                                                     HttpServletRequest request) {
        List<ProblemDetailResponse.Violation> violations = ex.getBindingResult().getAllErrors().stream()
                .map(error -> new ProblemDetailResponse.Violation(
                        error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName(),
                        error.getDefaultMessage()))
                .sorted(Comparator.comparing(ProblemDetailResponse.Violation::field))
                .toList();
        String message = violations.isEmpty() ? "invalid request" : "invalid request: "
                + violations.get(0).field() + " " + violations.get(0).message();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request, violations);
    }

    /** Method-level validation failed (for example {@code limit=0}). */
    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    public ResponseEntity<ProblemDetailResponse> handleParameterValidation(Exception ex,
                                                                           HttpServletRequest request) {
        List<ProblemDetailResponse.Violation> violations = ex instanceof ConstraintViolationException cve
                ? cve.getConstraintViolations().stream()
                        .map(violation -> new ProblemDetailResponse.Violation(
                                fieldName(String.valueOf(violation.getPropertyPath())), violation.getMessage()))
                        .toList()
                : violationsOf((HandlerMethodValidationException) ex);
        String message = violations.isEmpty() ? "invalid request parameter"
                : "invalid request parameter: " + violations.get(0).field() + " " + violations.get(0).message();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request, violations);
    }

    /** Body is not readable JSON, or an enum value is unknown. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetailResponse> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                                      HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", rootMessage(ex), request, List.of());
    }

    /** {@code {id}} is not a UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetailResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                                     HttpServletRequest request) {
        String message = "parameter '" + ex.getName() + "' has an invalid value";
        return build(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", message, request, List.of());
    }

    /** A business rule was violated, for example a LIMIT order without a price. */
    @ExceptionHandler({InvalidOrderException.class, IllegalArgumentException.class})
    public ResponseEntity<ProblemDetailResponse> handleInvalidOrder(RuntimeException ex,
                                                                    HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_ORDER", ex.getMessage(), request, List.of());
    }

    /** Unknown order id, or unknown path. */
    @ExceptionHandler({OrderNotFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ProblemDetailResponse> handleNotFound(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request, List.of());
    }

    /** Unmapped path handled by the static resource resolver. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetailResponse> handleNoResource(NoResourceFoundException ex,
                                                                  HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request, List.of());
    }

    /** A well formed symbol that has no book yet: the instrument does not exist. */
    @ExceptionHandler(UnknownInstrumentException.class)
    public ResponseEntity<ProblemDetailResponse> handleUnknownInstrument(UnknownInstrumentException ex,
                                                                         HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "UNKNOWN_INSTRUMENT", ex.getMessage(), request, List.of());
    }

    /** Valid request that breaks the order lifecycle, for example cancelling a filled order. */
    @ExceptionHandler(OrderStateException.class)
    public ResponseEntity<ProblemDetailResponse> handleOrderState(OrderStateException ex,
                                                                  HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ORDER_STATE", ex.getMessage(), request, List.of());
    }

    /** Unsupported HTTP method on an existing path. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetailResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                           HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", ex.getMessage(), request, List.of());
    }

    /** Last-resort handler so internal failures still return the standard shape. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetailResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", ex.getMessage(), request, List.of());
    }

    private List<ProblemDetailResponse.Violation> violationsOf(HandlerMethodValidationException ex) {
        return ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ProblemDetailResponse.Violation(
                                fieldName(result.getMethodParameter().getParameterName()),
                                error.getDefaultMessage())))
                .toList();
    }

    /** Keeps only the last segment of a dotted name, so {@code getOrderBook.symbol} reads {@code symbol}. */
    private static String fieldName(String name) {
        int lastDot = name.lastIndexOf('.');
        return lastDot >= 0 ? name.substring(lastDot + 1) : name;
    }

    private ResponseEntity<ProblemDetailResponse> build(HttpStatus status, String code, String message,
                                                        HttpServletRequest request,
                                                        List<ProblemDetailResponse.Violation> violations) {
        ProblemDetailResponse body = new ProblemDetailResponse(
                GENERIC_TYPE,
                status.getReasonPhrase(),
                status.value(),
                message == null ? status.getReasonPhrase() : message,
                request == null ? null : URI.create(request.getRequestURI()),
                code,
                FlowIdFilter.flowIdOf(request),
                violations);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    /** Digs the most useful message out of a Jackson/Jakarta parsing failure. */
    private String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? "malformed request body" : message.split("\\n")[0];
    }
}
