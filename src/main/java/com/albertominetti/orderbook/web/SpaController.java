package com.albertominetti.orderbook.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Entry point of the Vue 3 single page application, served under {@code /app}.
 *
 * <p>The bundle built by Maven from {@code frontend/} lands in {@code static/app} and is served
 * as a static resource, so the assets and the routes below the base path work out of the box.
 * Only the two paths without an asset are forwarded to {@code /app/index.html}, so that
 * {@code /app} and {@code /app/} open the application.</p>
 */
@Controller
public class SpaController {

    /**
     * Forwards the base path of the single page application to its entry document.
     *
     * @return the forward to {@code /app/index.html}, which the static resource resolver serves
     */
    @GetMapping({"/app", "/app/"})
    public String app() {
        return "forward:/app/index.html";
    }
}