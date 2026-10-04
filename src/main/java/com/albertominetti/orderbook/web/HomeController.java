package com.albertominetti.orderbook.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Landing page of the application, served at the root path {@code /}.
 *
 * <p>The markup lives in the Thymeleaf template {@code templates/index.html}, which links to the
 * generated API documentation (Swagger UI and the raw OpenAPI JSON). The controller only selects
 * the view; no HTML is written from Java.</p>
 */
@Controller
public class HomeController {

    /**
     * Renders the landing page by returning the Thymeleaf view name.
     *
     * @return the logical view name {@code "index"}, resolved to {@code templates/index.html}
     */
    @GetMapping("/")
    public String home() {
        return "index";
    }
}
