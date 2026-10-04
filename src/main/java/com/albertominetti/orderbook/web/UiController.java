package com.albertominetti.orderbook.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Single page web UI of the application, served at {@code /ui}.
 *
 * <p>The controller only selects the Thymeleaf view {@code ui} (template
 * {@code templates/ui.html}); no HTML is written from Java. The page itself is self contained: it
 * polls the REST API under {@code /api} from the browser with relative URLs, so it works unchanged
 * on localhost and on the deployed service, where it shares the origin of the API.</p>
 */
@Controller
public class UiController {

    /**
     * Renders the interactive web UI.
     *
     * @return the logical view name {@code "ui"}, resolved to {@code templates/ui.html}
     */
    @GetMapping("/ui")
    public String ui() {
        return "ui";
    }
}
