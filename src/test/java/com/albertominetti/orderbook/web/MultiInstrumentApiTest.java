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
 * End-to-end tests of the REST API with several instruments in play at the same time, plus
 * symbol normalization, unknown instrument handling, the OpenAPI description and Bean Validation.
 *
 * <p>Like the other integration test, the context is refreshed before each test, so every test
 * starts with an empty market and can assert exact values.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class MultiInstrumentApiTest {

    @Autowired
    private MockMvc mockMvc;

    // ------------------------------------------------------------------ several instruments

    @Test
    @DisplayName("Two instruments interleaved end to end keep their own book, trades and stats")
    void twoInstrumentsStayIsolated() throws Exception {
        // BTC-USD: two bids and one ask, then the ask is taken out by a crossing buy.
        submit("BTC-USD", "BUY", "199.50", "2");
        submit("BTC-USD", "SELL", "201.00", "3");
        submit("BTC-USD", "BUY", "200.00", "1");
        submit("BTC-USD", "BUY", "201.00", "3");

        // ETH-USD: an untouched book that never trades.
        submit("ETH-USD", "SELL", "49.00", "4");
        submit("ETH-USD", "BUY", "48.00", "5");

        mockMvc.perform(get("/api/instruments/BTC-USD/orderbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bids.length()").value(2))
                .andExpect(jsonPath("$.bids[0].price").value(200.00))
                .andExpect(jsonPath("$.bids[0].quantity").value(1))
                .andExpect(jsonPath("$.bids[1].price").value(199.50))
                .andExpect(jsonPath("$.bids[1].quantity").value(2))
                .andExpect(jsonPath("$.asks").isEmpty())
                .andExpect(jsonPath("$.bestBid").value(200.00))
                .andExpect(jsonPath("$.bestAsk").doesNotExist())
                .andExpect(jsonPath("$.spread").doesNotExist())
                .andExpect(jsonPath("$.lastPrice").value(201.00));

        mockMvc.perform(get("/api/instruments/ETH-USD/orderbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bids.length()").value(1))
                .andExpect(jsonPath("$.bids[0].price").value(48.00))
                .andExpect(jsonPath("$.bids[0].quantity").value(5))
                .andExpect(jsonPath("$.asks.length()").value(1))
                .andExpect(jsonPath("$.asks[0].price").value(49.00))
                .andExpect(jsonPath("$.asks[0].quantity").value(4))
                .andExpect(jsonPath("$.bestBid").value(48.00))
                .andExpect(jsonPath("$.bestAsk").value(49.00))
                .andExpect(jsonPath("$.spread").value(1.00))
                .andExpect(jsonPath("$.lastPrice").doesNotExist());

        mockMvc.perform(get("/api/instruments/BTC-USD/trades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].symbol").value("BTC-USD"))
                .andExpect(jsonPath("$[0].price").value(201.00))
                .andExpect(jsonPath("$[0].quantity").value(3));

        // The other instrument never traded: the trades never leak across instruments.
        mockMvc.perform(get("/api/instruments/ETH-USD/trades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/instruments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].symbol").value("BTC-USD"))
                .andExpect(jsonPath("$[0].restingOrders").value(2))
                .andExpect(jsonPath("$[0].bestBid").value(200.00))
                .andExpect(jsonPath("$[0].bestAsk").doesNotExist())
                .andExpect(jsonPath("$[0].lastPrice").value(201.00))
                .andExpect(jsonPath("$[1].symbol").value("ETH-USD"))
                .andExpect(jsonPath("$[1].restingOrders").value(2))
                .andExpect(jsonPath("$[1].bestBid").value(48.00))
                .andExpect(jsonPath("$[1].bestAsk").value(49.00))
                .andExpect(jsonPath("$[1].lastPrice").doesNotExist());
    }

    @Test
    @DisplayName("A padded lower case symbol creates a single normalized instrument")
    void symbolIsNormalizedEndToEnd() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":" btc-usd ","side":"BUY","type":"LIMIT","price":"100.00","quantity":"1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.symbol").value("BTC-USD"));

        // A different spelling of the same symbol feeds the same book.
        submit("Btc-Usd", "BUY", "100.00", "2");

        mockMvc.perform(get("/api/instruments/BTC-USD/orderbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bids.length()").value(1))
                .andExpect(jsonPath("$.bids[0].price").value(100.00))
                .andExpect(jsonPath("$.bids[0].quantity").value(3))
                .andExpect(jsonPath("$.bids[0].orderCount").value(2))
                .andExpect(jsonPath("$.bestBid").value(100.00));

        mockMvc.perform(get("/api/instruments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].symbol").value("BTC-USD"))
                .andExpect(jsonPath("$[0].restingOrders").value(2));
    }

    // ------------------------------------------------------------------ unknown instrument

    @Test
    @DisplayName("An unknown instrument is a 404 UNKNOWN_INSTRUMENT on both read endpoints")
    void unknownInstrumentOnBothEndpoints() throws Exception {
        submit("BTC-USD", "BUY", "100.00", "1");

        mockMvc.perform(get("/api/instruments/NOPE/orderbook"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("UNKNOWN_INSTRUMENT"))
                .andExpect(jsonPath("$.message").value("unknown instrument 'NOPE'"))
                .andExpect(jsonPath("$.path").value("/api/instruments/NOPE/orderbook"));

        mockMvc.perform(get("/api/instruments/NOPE/trades"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("UNKNOWN_INSTRUMENT"))
                .andExpect(jsonPath("$.message").value("unknown instrument 'NOPE'"))
                .andExpect(jsonPath("$.path").value("/api/instruments/NOPE/trades"));

        // Asking for an unknown instrument does not create it.
        mockMvc.perform(get("/api/instruments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].symbol").value("BTC-USD"));

        mockMvc.perform(get("/api/instruments/BTC-USD/orderbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestBid").value(100.00));
    }

    // ------------------------------------------------------------------ openapi

    @Test
    @DisplayName("GET /v3/api-docs serves the OpenAPI description of the API")
    void openApiIsServed() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"openapi\"");
        assertThat(body).contains("\"/api/orders\"");
        assertThat(body).contains("\"/api/instruments\"");
        assertThat(body).contains("\"/api/instruments/{symbol}/orderbook\"");
        assertThat(body).contains("\"/api/instruments/{symbol}/trades\"");
    }

    // ------------------------------------------------------------------ bean validation

    @Test
    @DisplayName("Bean Validation rejects a price with 9 decimal places and a zero quantity")
    void beanValidationRejectsPrecisionAndQuantity() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"BTC-USD","side":"BUY","type":"LIMIT","price":"100.123456789","quantity":"1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations.length()").value(1))
                .andExpect(jsonPath("$.violations[0].field").value("price"))
                .andExpect(jsonPath("$.violations[0].message").value("price must have at most 8 decimal places"));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"BTC-USD","side":"BUY","type":"LIMIT","price":"100.00","quantity":"0"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations.length()").value(1))
                .andExpect(jsonPath("$.violations[0].field").value("quantity"))
                .andExpect(jsonPath("$.violations[0].message").value("quantity must be greater than 0"));

        // A rejected payload never creates an instrument.
        mockMvc.perform(get("/api/instruments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ------------------------------------------------------------------ helpers

    /** Submits a LIMIT order and expects it to be accepted. */
    private void submit(String symbol, String side, String price, String quantity) throws Exception {
        String body = """
                {"symbol":"%s","side":"%s","type":"LIMIT","price":"%s","quantity":"%s"}"""
                .formatted(symbol, side, price, quantity);
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }
}