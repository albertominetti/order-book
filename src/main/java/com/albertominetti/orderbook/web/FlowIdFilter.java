package com.albertominetti.orderbook.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Propagates the {@code X-Flow-ID} correlation id across a request, following the Zalando
 * guideline "Must Support X-Flow-ID".
 *
 * <p>An incoming {@code X-Flow-ID} header is reused; when the client does not send one a fresh id
 * is generated. The value is always written back on the response as the {@code X-Flow-ID} header
 * and stored as a request attribute, so the {@code GlobalExceptionHandler} can repeat it inside the
 * RFC 7807 problem JSON as the {@code flowId} member.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FlowIdFilter implements Filter {

    /** Request/response header that carries the correlation id. */
    public static final String HEADER = "X-Flow-ID";

    /** Request attribute the id is stored under. */
    public static final String ATTRIBUTE = FlowIdFilter.class.getName() + ".flowId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest
                && response instanceof HttpServletResponse httpResponse) {
            String flowId = httpRequest.getHeader(HEADER);
            if (flowId == null || flowId.isBlank()) {
                flowId = UUID.randomUUID().toString();
            }
            httpRequest.setAttribute(ATTRIBUTE, flowId);
            httpResponse.setHeader(HEADER, flowId);
        }
        chain.doFilter(request, response);
    }

    /**
     * Reads the flow id of a request, as set by this filter.
     *
     * @param request the current request, may be {@code null}
     * @return the flow id, or {@code null} when the filter did not run for this request
     */
    public static String flowIdOf(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(ATTRIBUTE);
        return value == null ? null : value.toString();
    }
}
