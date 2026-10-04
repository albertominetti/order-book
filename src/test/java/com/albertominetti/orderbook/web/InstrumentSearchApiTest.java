package com.albertominetti.orderbook.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of {@code GET /api/instruments/search}, the catalogue behind the instrument
 * picker of the web app: the default slice, a query by symbol, a query by name, an unknown query,
 * the {@code limit} boundaries and the fact that the new path does not disturb the existing
 * per-instrument endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class InstrumentSearchApiTest {

    @Autowired
    private MockMvc mockMvc;

    // ------------------------------------------------------------------ happy paths

    @Test
    @DisplayName("A query on the symbol returns the expected instrument as the first result")
    void symbolQueryReturnsTheExpectedFirstSymbol() throws Exception {
        mockMvc.perform(get("/api/instruments/search").param("q", "ubs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].symbol").value("UBSG"))
                .andExpect(jsonPath("$[0].name").value("UBS Group"))
                .andExpect(jsonPath("$[0].market").value("SIX"));
    }

    @Test
    @DisplayName("A query on the name matches by substring, whatever the case")
    void nameQueryMatchesBySubstring() throws Exception {
        mockMvc.perform(get("/api/instruments/search").param("q", "roche"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].symbol").value("ROG"))
                .andExpect(jsonPath("$[0].name").value("Roche Holding"));

        mockMvc.perform(get("/api/instruments/search").param("q", "nvidia"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].symbol").value("NVDA"))
                .andExpect(jsonPath("$[0].market").value("S&P 500"));
    }

    @Test
    @DisplayName("Without a query the search returns the first fifty instruments of the catalogue")
    void missingQueryReturnsTheDefaultSlice() throws Exception {
        mockMvc.perform(get("/api/instruments/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(50))
                .andExpect(jsonPath("$[0].symbol").value("UBSG"))
                .andExpect(jsonPath("$[0].name").value("UBS Group"))
                .andExpect(jsonPath("$[0].market").value("SIX"))
                .andExpect(jsonPath("$[46].symbol").value("VZN"))
                .andExpect(jsonPath("$[47].symbol").value("A"));

        mockMvc.perform(get("/api/instruments/search").param("q", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(50))
                .andExpect(jsonPath("$[0].symbol").value("UBSG"));
    }

    @Test
    @DisplayName("The limit caps the results and symbol prefix matches are ranked first")
    void limitIsHonouredAndPrefixMatchesComeFirst() throws Exception {
        // "a" matches A, AAPL, ABBN (ABB), ABBV, ABNB, ... by prefix, in that order.
        mockMvc.perform(get("/api/instruments/search").param("q", "a").param("limit", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].symbol").value("A"))
                .andExpect(jsonPath("$[1].symbol").value("AAPL"))
                .andExpect(jsonPath("$[2].symbol").value("ABBN"))
                .andExpect(jsonPath("$[3].symbol").value("ABBV"));
    }

    // ------------------------------------------------------------------ empty and errors

    @Test
    @DisplayName("An unknown query returns an empty array")
    void unknownQueryReturnsAnEmptyArray() throws Exception {
        mockMvc.perform(get("/api/instruments/search").param("q", "nosuchticker"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0))
                .andExpect(content().string("[]"));
    }

    @Test
    @DisplayName("A limit outside 1 to 200 is a 400 VALIDATION_ERROR")
    void limitOutOfRangeIsRejected() throws Exception {
        mockMvc.perform(get("/api/instruments/search").param("q", "a").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].field").value("limit"))
                .andExpect(jsonPath("$.violations[0].message").value("limit must be at least 1"))
                .andExpect(jsonPath("$.path").value("/api/instruments/search"));

        mockMvc.perform(get("/api/instruments/search").param("q", "a").param("limit", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].field").value("limit"))
                .andExpect(jsonPath("$.violations[0].message").value("limit must be at most 200"));

        mockMvc.perform(get("/api/instruments/search").param("q", "a").param("limit", "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    // ------------------------------------------------------------------ existing API intact

    @Test
    @DisplayName("The search endpoint neither creates an instrument nor shadows the per-instrument ones")
    void existingInstrumentEndpointsAreUntouched() throws Exception {
        mockMvc.perform(get("/api/instruments/search").param("q", "ubs"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"UBSG","side":"BUY","type":"LIMIT","price":"10.00","quantity":"1"}"""))
                .andExpect(status().isCreated());

        // The book of the instrument still answers on its own path.
        mockMvc.perform(get("/api/instruments/UBSG/orderbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bids[0].price").value(10.00));

        mockMvc.perform(get("/api/instruments/UBSG/trades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/instruments/NOPE/orderbook"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_INSTRUMENT"));

        // Only the instrument created by the order is active, the search suggests symbols only.
        mockMvc.perform(get("/api/instruments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].symbol").value("UBSG"));
    }

    @Test
    @DisplayName("GET /v3/api-docs documents the catalogue search endpoint")
    void openApiDescribesTheSearchEndpoint() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"/api/instruments/search\"");
        assertThat(body).contains("\"/api/instruments/{symbol}/orderbook\"");
    }
}