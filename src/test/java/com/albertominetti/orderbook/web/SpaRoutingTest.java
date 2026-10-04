package com.albertominetti.orderbook.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests of the single page application served under {@code /app}.
 *
 * <p>{@code SpaController} forwards the two paths without an asset to the entry document, and the
 * bundle built by Maven from {@code frontend/} is served as a static resource. Both must hold for
 * the SPA to open in a browser and to load its own assets below the {@code /app} base path.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class SpaRoutingTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /app and GET /app/ forward to the index of the single page application")
    void basePathsForwardToTheEntryDocument() throws Exception {
        mockMvc.perform(get("/app"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/app/index.html"));

        mockMvc.perform(get("/app/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/app/index.html"));
    }

    @Test
    @DisplayName("GET /app/index.html serves the built entry document of the single page application")
    void entryDocumentIsBuiltByMaven() throws Exception {
        mockMvc.perform(get("/app/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<div id=\"app\"></div>")))
                .andExpect(content().string(containsString("/app/assets/")));
    }
}