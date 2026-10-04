package com.albertominetti.orderbook.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests of the web UI served at {@code /ui}.
 *
 * <p>The page is a self contained template served by {@link UiController}: it must answer {@code 200}
 * with an HTML body and it must carry the stable element ids that its own script binds to, otherwise
 * the page would load and stay blank.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class UiPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /ui serves the web UI with the panels and fields its script binds to")
    void uiPageServesEveryPanel() throws Exception {
        mockMvc.perform(get("/ui"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("id=\"order-form\"")))
                .andExpect(content().string(containsString("id=\"orderbook\"")))
                .andExpect(content().string(containsString("id=\"trades\"")))
                .andExpect(content().string(containsString("id=\"instruments\"")));
    }

    @Test
    @DisplayName("GET /ui exposes the order entry fields, the status area and no external asset")
    void uiPageExposesTheOrderFormAndTheStatusArea() throws Exception {
        String body = mockMvc.perform(get("/ui"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Stable ids of the order entry form.
        assertThat(body)
                .contains("id=\"side\"")
                .contains("id=\"type\"")
                .contains("id=\"price\"")
                .contains("id=\"quantity\"")
                .contains("id=\"message\"")
                .contains("id=\"my-orders\"");
        // The page is self contained: no stylesheet and no script comes from a CDN.
        assertThat(body)
                .doesNotContain("<script src=")
                .doesNotContain("<link rel=\"stylesheet\"");
    }
}
