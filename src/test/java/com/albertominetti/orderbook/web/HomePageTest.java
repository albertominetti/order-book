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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests of the landing page served at the root path.
 *
 * <p>It must answer {@code 200} with an HTML body that links to the single page application, to the
 * interactive documentation and to the raw OpenAPI spec, so a browser opening {@code /} finds the
 * web app and the documentation right away.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class HomePageTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET / returns the landing page linking to the web app and to Swagger UI")
    void landingPageLinksToTheDocumentation() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<title>Order Book API</title>")))
                .andExpect(content().string(containsString("<a href=\"/app/\">"
                        + "Open the web app (Vue 3)</a>")))
                .andExpect(content().string(containsString("<a href=\"/swagger-ui.html\">"
                        + "API documentation (Swagger UI)</a>")))
                .andExpect(content().string(containsString("<a href=\"/v3/api-docs\">OpenAPI JSON</a>")));
    }
}
