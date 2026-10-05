package com.albertominetti.orderbook.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description of the API (served by springdoc at {@code /v3/api-docs}).
 *
 * <p>The operation-level responses and the RFC 7807 error schema are declared on the controllers;
 * this class only carries the document-level information.</p>
 */
@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Order Book API",
        version = "1.0.0",
        description = """
                In-memory order book with a price-time matching engine.

                The API follows the Zalando RESTful API guidelines, with two deliberate deviations:
                JSON property names and query parameters stay in lowerCamelCase (not snake_case), and
                every endpoint is served under the /api base path.

                Errors are RFC 7807 problem details served as application/problem+json, and every
                response carries an X-Flow-ID header that is repeated in the problem body."""))
public class OpenApiConfig {
}
