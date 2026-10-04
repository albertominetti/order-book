package com.albertominetti.orderbook.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of the REST API.
 *
 * <p>The book is a per-context singleton, so the context is refreshed before each test:
 * every test starts from an empty book and can assert exact values.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class OrderApiIntegrationTest {

    private static final String NO_SUCH_ORDER_ID = "3f1b7c58-0000-4000-8000-00000000dead";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ------------------------------------------------------------------ POST /api/orders

    @Test
    @DisplayName("POST /api/orders returns 201 with the resting order and no trades")
    void submitOrderThatRests() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"BUY","type":"LIMIT","price":"100.00","quantity":"5"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.order.side").value("BUY"))
                .andExpect(jsonPath("$.order.type").value("LIMIT"))
                .andExpect(jsonPath("$.order.price").value(100.00))
                .andExpect(jsonPath("$.order.quantity").value(5))
                .andExpect(jsonPath("$.order.remainingQuantity").value(5))
                .andExpect(jsonPath("$.order.filledQuantity").value(0))
                .andExpect(jsonPath("$.order.status").value("NEW"))
                .andExpect(jsonPath("$.order.timestamp").exists())
                .andExpect(jsonPath("$.trades").isEmpty())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertThat(location).startsWith("/api/orders/");

        // The Location header points to a retrievable order.
        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"));
    }

    @Test
    @DisplayName("POST /api/orders returns the generated trades when the order matches")
    void submitOrderThatTrades() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"SELL","type":"LIMIT","price":"110.00","quantity":"4"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trades").isEmpty());

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"BUY","type":"LIMIT","price":"110.00","quantity":"4"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("FILLED"))
                .andExpect(jsonPath("$.order.remainingQuantity").value(0))
                .andExpect(jsonPath("$.trades.length()").value(1))
                .andExpect(jsonPath("$.trades[0].price").value(110.00))
                .andExpect(jsonPath("$.trades[0].quantity").value(4))
                .andExpect(jsonPath("$.trades[0].buyOrderId").exists())
                .andExpect(jsonPath("$.trades[0].sellOrderId").exists())
                .andExpect(jsonPath("$.trades[0].timestamp").exists());
    }

    @Test
    @DisplayName("POST /api/orders supports market orders without a price")
    void submitMarketOrder() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"SELL","type":"LIMIT","price":"120.00","quantity":"2"}"""))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"BUY","type":"MARKET","quantity":"5"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.type").value("MARKET"))
                .andExpect(jsonPath("$.order.price").doesNotExist())
                // Not enough liquidity: the remainder is discarded.
                .andExpect(jsonPath("$.order.status").value("CANCELLED"))
                .andExpect(jsonPath("$.order.remainingQuantity").value(3))
                .andExpect(jsonPath("$.trades.length()").value(1))
                .andExpect(jsonPath("$.trades[0].quantity").value(2));
    }

    @Test
    @DisplayName("POST /api/orders rejects a LIMIT order without a price with 400")
    void rejectLimitOrderWithoutPrice() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"BUY","type":"LIMIT","quantity":"1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.code").value("INVALID_ORDER"))
                .andExpect(jsonPath("$.message").value("price must be greater than 0 for LIMIT orders"))
                .andExpect(jsonPath("$.path").value("/api/orders"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("POST /api/orders rejects a MARKET order carrying a price with 400")
    void rejectMarketOrderWithPrice() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"BUY","type":"MARKET","price":"100.00","quantity":"1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER"))
                .andExpect(jsonPath("$.message").value("price must not be provided for MARKET orders"));
    }

    @Test
    @DisplayName("POST /api/orders reports field level violations with 400")
    void rejectNonPositiveQuantity() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"BUY","type":"LIMIT","price":"100.00","quantity":"0"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations.length()").value(1))
                .andExpect(jsonPath("$.violations[0].field").value("quantity"))
                .andExpect(jsonPath("$.violations[0].message").value("quantity must be greater than 0"));
    }

    @Test
    @DisplayName("POST /api/orders rejects a missing side with 400")
    void rejectMissingSide() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"LIMIT","price":"100.00","quantity":"1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].field").value("side"));
    }

    @Test
    @DisplayName("POST /api/orders rejects an unknown side value with 400")
    void rejectUnknownEnumValue() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"LONG","type":"LIMIT","price":"100.00","quantity":"1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("POST /api/orders rejects malformed JSON with 400")
    void rejectMalformedJson() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"side\":\"BUY\","))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    // ------------------------------------------------------------------ GET /api/orders/{id}

    @Test
    @DisplayName("GET /api/orders/{id} returns 404 for an unknown id")
    void getUnknownOrder() throws Exception {
        mockMvc.perform(get("/api/orders/{id}", NO_SUCH_ORDER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("order " + NO_SUCH_ORDER_ID + " not found"))
                .andExpect(jsonPath("$.path").value("/api/orders/" + NO_SUCH_ORDER_ID));
    }

    @Test
    @DisplayName("GET /api/orders/{id} returns 400 when the id is not a UUID")
    void getOrderWithInvalidId() throws Exception {
        mockMvc.perform(get("/api/orders/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    // ------------------------------------------------------------------ DELETE /api/orders/{id}

    @Test
    @DisplayName("DELETE /api/orders/{id} cancels a resting order")
    void cancelRestingOrder() throws Exception {
        String id = submitBuy(130.00, "3");

        mockMvc.perform(delete("/api/orders/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/orders/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("DELETE /api/orders/{id} returns 422 when the order is already filled")
    void cancelFilledOrder() throws Exception {
        String sellId = submit("SELL", 140.00, "2");
        submit("BUY", 140.00, "2");

        mockMvc.perform(delete("/api/orders/{id}", sellId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATE"))
                .andExpect(jsonPath("$.message").value("order " + sellId + " cannot be cancelled because it is FILLED"));
    }

    @Test
    @DisplayName("DELETE /api/orders/{id} returns 404 for an unknown id")
    void cancelUnknownOrder() throws Exception {
        mockMvc.perform(delete("/api/orders/{id}", NO_SUCH_ORDER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("DELETE /api/orders/{id} returns 422 when the order is cancelled twice")
    void cancelTwice() throws Exception {
        String id = submitBuy(150.00, "1");

        mockMvc.perform(delete("/api/orders/{id}", id)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/orders/{id}", id))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATE"))
                .andExpect(jsonPath("$.message").value("order " + id + " cannot be cancelled because it is CANCELLED"));
    }

    // ------------------------------------------------------------------ GET /api/orderbook

    @Test
    @DisplayName("GET /api/orderbook aggregates levels and reports best bid, best ask and spread")
    void orderBookSnapshot() throws Exception {
        submit("BUY", 199.90, "2");
        submit("BUY", 200.00, "3");
        submit("BUY", 200.00, "4");
        submit("SELL", 200.50, "1");
        submit("SELL", 201.00, "5");

        mockMvc.perform(get("/api/orderbook"))
                .andExpect(status().isOk())
                // Bids sorted from the best price down, aggregated per level.
                .andExpect(jsonPath("$.bids.length()").value(2))
                .andExpect(jsonPath("$.bids[0].price").value(200.00))
                .andExpect(jsonPath("$.bids[0].quantity").value(7))
                .andExpect(jsonPath("$.bids[0].orderCount").value(2))
                .andExpect(jsonPath("$.bids[1].price").value(199.90))
                .andExpect(jsonPath("$.bids[1].quantity").value(2))
                // Asks sorted from the best price up, aggregated per level.
                .andExpect(jsonPath("$.asks.length()").value(2))
                .andExpect(jsonPath("$.asks[0].price").value(200.50))
                .andExpect(jsonPath("$.asks[0].quantity").value(1))
                .andExpect(jsonPath("$.asks[1].price").value(201.00))
                .andExpect(jsonPath("$.bestBid").value(200.00))
                .andExpect(jsonPath("$.bestAsk").value(200.50))
                .andExpect(jsonPath("$.spread").value(0.50));
    }

    @Test
    @DisplayName("GET /api/orderbook omits best prices when a side is empty")
    void orderBookWithOneSideOnly() throws Exception {
        submitBuy(210.00, "5");

        mockMvc.perform(get("/api/orderbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bids.length()").value(1))
                .andExpect(jsonPath("$.asks.length()").value(0))
                .andExpect(jsonPath("$.bestBid").value(210.00))
                .andExpect(jsonPath("$.bestAsk").doesNotExist())
                .andExpect(jsonPath("$.spread").doesNotExist());
    }

    // ------------------------------------------------------------------ GET /api/trades

    @Test
    @DisplayName("GET /api/trades returns the trades of this test, newest first")
    void recentTrades() throws Exception {
        submit("SELL", 220.00, "1");
        submit("BUY", 220.00, "1");
        submit("SELL", 221.00, "2");
        submit("BUY", 221.00, "2");

        mockMvc.perform(get("/api/trades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].price").value(221.00))
                .andExpect(jsonPath("$[0].sellOrderId").exists())
                .andExpect(jsonPath("$[1].price").value(220.00));

        mockMvc.perform(get("/api/trades").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].price").value(221.00));

        // The trade tape records the resting order price on every fill.
        mockMvc.perform(get("/api/trades"))
                .andExpect(jsonPath("$[0].quantity").value(2))
                .andExpect(jsonPath("$[1].quantity").value(1));
    }

    @Test
    @DisplayName("GET /api/trades returns 400 for an out of range limit")
    void rejectInvalidTradeLimit() throws Exception {
        mockMvc.perform(get("/api/trades").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/trades").param("limit", "100000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/trades").param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    // ------------------------------------------------------------------ helpers

    /** Submits a LIMIT order and returns its id. */
    private String submit(String side, double price, String quantity) throws Exception {
        String body = """
                {"side":"%s","type":"LIMIT","price":"%s","quantity":"%s"}"""
                .formatted(side, price, quantity);
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("order").get("id").asText();
    }

    private String submitBuy(double price, String quantity) throws Exception {
        return submit("BUY", price, quantity);
    }
}