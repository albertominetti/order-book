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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of the REST API.
 *
 * <p>The registry is a per-context singleton, so the context is refreshed before each test:
 * every test starts with no instrument at all and can assert exact values.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class OrderApiIntegrationTest {

    private static final String NO_SUCH_ORDER_ID = "3f1b7c58-0000-4000-8000-00000000dead";

    /** No Jackson in the tests: the order id is pulled out of the raw response. */
    private static final Pattern ORDER_ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]{8}-[0-9a-fA-F-]{27})\"");

    @Autowired
    private MockMvc mockMvc;

    // ------------------------------------------------------------------ POST /api/orders

    @Test
    @DisplayName("POST /api/orders returns 201 with the resting order and no trades")
    void submitOrderThatRests() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"BTC-USD","side":"BUY","type":"LIMIT","price":"100.00","quantity":"5"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.order.symbol").value("BTC-USD"))
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
                .andExpect(jsonPath("$.symbol").value("BTC-USD"))
                .andExpect(jsonPath("$.status").value("NEW"));
    }

    @Test
    @DisplayName("POST /api/orders normalizes the symbol (trim + uppercase)")
    void submitOrderWithUnnormalizedSymbol() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":" btc-usd ","side":"BUY","type":"LIMIT","price":"100.00","quantity":"1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.symbol").value("BTC-USD"));

        // The normalized symbol is the one that owns the book.
        mockMvc.perform(get("/api/instruments/BTC-USD/orderbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestBid").value(100.00));

        mockMvc.perform(get("/api/instruments/btc-usd/orderbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestBid").value(100.00));
    }

    @Test
    @DisplayName("POST /api/orders returns the generated trades when the order matches")
    void submitOrderThatTrades() throws Exception {
        submit("BTC-USD", "SELL", 110.00, "4");

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"BTC-USD","side":"BUY","type":"LIMIT","price":"110.00","quantity":"4"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("FILLED"))
                .andExpect(jsonPath("$.order.remainingQuantity").value(0))
                .andExpect(jsonPath("$.trades.length()").value(1))
                .andExpect(jsonPath("$.trades[0].symbol").value("BTC-USD"))
                .andExpect(jsonPath("$.trades[0].price").value(110.00))
                .andExpect(jsonPath("$.trades[0].quantity").value(4))
                .andExpect(jsonPath("$.trades[0].buyOrderId").exists())
                .andExpect(jsonPath("$.trades[0].sellOrderId").exists())
                .andExpect(jsonPath("$.trades[0].timestamp").exists());
    }

    @Test
    @DisplayName("POST /api/orders supports market orders without a price")
    void submitMarketOrder() throws Exception {
        submit("BTC-USD", "SELL", 120.00, "2");

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"BTC-USD","side":"BUY","type":"MARKET","quantity":"5"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.symbol").value("BTC-USD"))
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
                                {"symbol":"BTC-USD","side":"BUY","type":"LIMIT","quantity":"1"}"""))
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
                                {"symbol":"BTC-USD","side":"BUY","type":"MARKET","price":"100.00","quantity":"1"}"""))
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
                                {"symbol":"BTC-USD","side":"BUY","type":"LIMIT","price":"100.00","quantity":"0"}"""))
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
                                {"symbol":"BTC-USD","type":"LIMIT","price":"100.00","quantity":"1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].field").value("side"));
    }

    @Test
    @DisplayName("POST /api/orders rejects a missing symbol with 400")
    void rejectMissingSymbol() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"BUY","type":"LIMIT","price":"100.00","quantity":"1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations.length()").value(1))
                .andExpect(jsonPath("$.violations[0].field").value("symbol"))
                .andExpect(jsonPath("$.violations[0].message").value("symbol is required"));

        // And no instrument was created for a rejected order.
        mockMvc.perform(get("/api/instruments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("POST /api/orders rejects a malformed symbol with 400")
    void rejectMalformedSymbol() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"BTC~USD","side":"BUY","type":"LIMIT","price":"100.00","quantity":"1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].field").value("symbol"));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"_TOOLONGINVALIDSYMBOL","side":"BUY","type":"LIMIT","price":"100.00","quantity":"1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/instruments"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("POST /api/orders rejects an unknown side value with 400")
    void rejectUnknownEnumValue() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"BTC-USD","side":"LONG","type":"LIMIT","price":"100.00","quantity":"1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("POST /api/orders rejects malformed JSON with 400")
    void rejectMalformedJson() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"BTC-USD\",\"side\":\"BUY\","))
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

    @Test
    @DisplayName("GET /api/orders/{id} finds an order on any instrument without asking for the symbol")
    void getOrderOfAnyInstrument() throws Exception {
        String btcId = submit("BTC-USD", "BUY", 300.00, "2");
        String ethId = submit("ETH-USD", "SELL", 40.00, "7");

        mockMvc.perform(get("/api/orders/{id}", btcId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(btcId))
                .andExpect(jsonPath("$.symbol").value("BTC-USD"))
                .andExpect(jsonPath("$.quantity").value(2));

        mockMvc.perform(get("/api/orders/{id}", ethId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ethId))
                .andExpect(jsonPath("$.symbol").value("ETH-USD"))
                .andExpect(jsonPath("$.side").value("SELL"))
                .andExpect(jsonPath("$.quantity").value(7));
    }

    // ------------------------------------------------------------------ DELETE /api/orders/{id}

    @Test
    @DisplayName("DELETE /api/orders/{id} cancels a resting order")
    void cancelRestingOrder() throws Exception {
        String id = submit("BTC-USD", "BUY", 130.00, "3");

        mockMvc.perform(delete("/api/orders/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.symbol").value("BTC-USD"))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/orders/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("DELETE /api/orders/{id} routes the cancel to the instrument that owns the order")
    void cancelRoutesToTheRightInstrument() throws Exception {
        String btcId = submit("BTC-USD", "BUY", 150.00, "1");
        String ethId = submit("ETH-USD", "BUY", 150.00, "4");

        // Cancelling an ETH-USD order must not touch the BTC-USD book.
        mockMvc.perform(delete("/api/orders/{id}", ethId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("ETH-USD"))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/instruments/BTC-USD/orderbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bids.length()").value(1))
                .andExpect(jsonPath("$.bids[0].quantity").value(1));

        mockMvc.perform(get("/api/instruments/ETH-USD/orderbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bids").isEmpty())
                .andExpect(jsonPath("$.bestBid").doesNotExist());

        mockMvc.perform(get("/api/orders/{id}", btcId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"));
    }

    @Test
    @DisplayName("DELETE /api/orders/{id} returns 422 when the order is already filled")
    void cancelFilledOrder() throws Exception {
        String sellId = submit("BTC-USD", "SELL", 140.00, "2");
        submit("BTC-USD", "BUY", 140.00, "2");

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
        String id = submit("BTC-USD", "BUY", 150.00, "1");

        mockMvc.perform(delete("/api/orders/{id}", id)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/orders/{id}", id))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATE"))
                .andExpect(jsonPath("$.message").value("order " + id + " cannot be cancelled because it is CANCELLED"));
    }

    // ------------------------------------------------------------------ GET /api/instruments

    @Test
    @DisplayName("GET /api/instruments is empty until the first order")
    void instrumentsAreEmptyByDefault() throws Exception {
        mockMvc.perform(get("/api/instruments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/instruments lists every active instrument with its stats")
    void listInstrumentsWithStats() throws Exception {
        submit("ETH-USD", "SELL", 210.00, "1");
        submit("BTC-USD", "BUY", 199.90, "2");
        submit("BTC-USD", "BUY", 200.00, "3");
        submit("BTC-USD", "SELL", 200.50, "1");
        submit("ETH-USD", "BUY", 205.00, "2");

        mockMvc.perform(get("/api/instruments"))
                .andExpect(status().isOk())
                // Sorted by symbol.
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].symbol").value("BTC-USD"))
                .andExpect(jsonPath("$[0].restingOrders").value(3))
                .andExpect(jsonPath("$[0].bestBid").value(200.00))
                .andExpect(jsonPath("$[0].bestAsk").value(200.50))
                .andExpect(jsonPath("$[0].lastPrice").doesNotExist())
                .andExpect(jsonPath("$[1].symbol").value("ETH-USD"))
                .andExpect(jsonPath("$[1].restingOrders").value(2))
                .andExpect(jsonPath("$[1].bestBid").value(205.00))
                .andExpect(jsonPath("$[1].bestAsk").value(210.00));

        // Once something trades, the last price shows up on that instrument only.
        submit("BTC-USD", "BUY", 200.50, "1");
        mockMvc.perform(get("/api/instruments"))
                .andExpect(jsonPath("$[0].symbol").value("BTC-USD"))
                .andExpect(jsonPath("$[0].restingOrders").value(2))
                .andExpect(jsonPath("$[0].lastPrice").value(200.50))
                .andExpect(jsonPath("$[1].symbol").value("ETH-USD"))
                .andExpect(jsonPath("$[1].lastPrice").doesNotExist());
    }

    // ------------------------------------------------------------------ GET /api/instruments/{symbol}/orderbook

    @Test
    @DisplayName("GET /api/instruments/{symbol}/orderbook aggregates levels and reports best bid, best ask and spread")
    void orderBookSnapshot() throws Exception {
        submit("BTC-USD", "BUY", 199.90, "2");
        submit("BTC-USD", "BUY", 200.00, "3");
        submit("BTC-USD", "BUY", 200.00, "4");
        submit("BTC-USD", "SELL", 200.50, "1");
        submit("BTC-USD", "SELL", 201.00, "5");

        mockMvc.perform(get("/api/instruments/BTC-USD/orderbook"))
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
    @DisplayName("GET /api/instruments/{symbol}/orderbook omits best prices when a side is empty")
    void orderBookWithOneSideOnly() throws Exception {
        submit("BTC-USD", "BUY", 210.00, "5");

        mockMvc.perform(get("/api/instruments/BTC-USD/orderbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bids.length()").value(1))
                .andExpect(jsonPath("$.asks.length()").value(0))
                .andExpect(jsonPath("$.bestBid").value(210.00))
                .andExpect(jsonPath("$.bestAsk").doesNotExist())
                .andExpect(jsonPath("$.spread").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/instruments/{symbol}/orderbook returns 404 UNKNOWN_INSTRUMENT for an untouched symbol")
    void unknownInstrumentOnOrderBook() throws Exception {
        mockMvc.perform(get("/api/instruments/NOPE/orderbook"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("UNKNOWN_INSTRUMENT"))
                .andExpect(jsonPath("$.message").value("unknown instrument 'NOPE'"))
                .andExpect(jsonPath("$.path").value("/api/instruments/NOPE/orderbook"));
    }

    @Test
    @DisplayName("GET /api/instruments/{symbol}/orderbook returns 400 for a malformed symbol")
    void malformedSymbolOnOrderBook() throws Exception {
        mockMvc.perform(get("/api/instruments/BTC~USD/orderbook"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].field").value("symbol"));

        mockMvc.perform(get("/api/instruments/BTC~USD/trades"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].field").value("symbol"));
    }

    // ------------------------------------------------------------------ GET /api/instruments/{symbol}/trades

    @Test
    @DisplayName("GET /api/instruments/{symbol}/trades returns the trades of that instrument, newest first")
    void recentTrades() throws Exception {
        submit("BTC-USD", "SELL", 220.00, "1");
        submit("BTC-USD", "BUY", 220.00, "1");
        submit("BTC-USD", "SELL", 221.00, "2");
        submit("BTC-USD", "BUY", 221.00, "2");

        mockMvc.perform(get("/api/instruments/BTC-USD/trades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].symbol").value("BTC-USD"))
                .andExpect(jsonPath("$[0].price").value(221.00))
                .andExpect(jsonPath("$[0].sellOrderId").exists())
                .andExpect(jsonPath("$[1].price").value(220.00));

        mockMvc.perform(get("/api/instruments/BTC-USD/trades").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].price").value(221.00));

        // The trade tape records the resting order price on every fill.
        mockMvc.perform(get("/api/instruments/BTC-USD/trades"))
                .andExpect(jsonPath("$[0].quantity").value(2))
                .andExpect(jsonPath("$[1].quantity").value(1));
    }

    @Test
    @DisplayName("GET /api/instruments/{symbol}/trades returns 400 for an out of range limit")
    void rejectInvalidTradeLimit() throws Exception {
        mockMvc.perform(get("/api/instruments/BTC-USD/trades").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/instruments/BTC-USD/trades").param("limit", "100000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/instruments/BTC-USD/trades").param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("GET /api/instruments/{symbol}/trades returns 404 UNKNOWN_INSTRUMENT for an untouched symbol")
    void unknownInstrumentOnTrades() throws Exception {
        mockMvc.perform(get("/api/instruments/NOPE/trades"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("UNKNOWN_INSTRUMENT"));
    }

    // ------------------------------------------------------------------ instrument isolation

    @Test
    @DisplayName("Crossing orders on different symbols never match")
    void differentSymbolsNeverCross() throws Exception {
        submit("BTC-USD", "SELL", 100.00, "5");
        submit("ETH-USD", "BUY", 100.00, "5");

        // Both are still resting: nothing traded across instruments.
        mockMvc.perform(get("/api/instruments/BTC-USD/orderbook"))
                .andExpect(jsonPath("$.asks[0].quantity").value(5))
                .andExpect(jsonPath("$.bids").isEmpty())
                .andExpect(jsonPath("$.lastPrice").doesNotExist());

        mockMvc.perform(get("/api/instruments/ETH-USD/orderbook"))
                .andExpect(jsonPath("$.bids[0].quantity").value(5))
                .andExpect(jsonPath("$.asks").isEmpty());

        mockMvc.perform(get("/api/instruments/BTC-USD/trades"))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/instruments/ETH-USD/trades"))
                .andExpect(jsonPath("$.length()").value(0));

        // The opposite side of the same instrument still crosses.
        submit("BTC-USD", "BUY", 100.00, "5");
        mockMvc.perform(get("/api/instruments/BTC-USD/trades"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].symbol").value("BTC-USD"));
    }

    @Test
    @DisplayName("Orders on the same symbol match, whichever instrument they belong to")
    void sameSymbolMatches() throws Exception {
        String sellId = submit("ETH-USD", "SELL", 50.00, "3");

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"ETH-USD","side":"BUY","type":"LIMIT","price":"50.00","quantity":"3"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("FILLED"))
                .andExpect(jsonPath("$.trades.length()").value(1))
                .andExpect(jsonPath("$.trades[0].symbol").value("ETH-USD"))
                .andExpect(jsonPath("$.trades[0].price").value(50.00));

        mockMvc.perform(get("/api/orders/{id}", sellId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("ETH-USD"))
                .andExpect(jsonPath("$.status").value("FILLED"));
    }

    @Test
    @DisplayName("The trade tape of an instrument only shows its own trades")
    void tradeTapeIsPerInstrument() throws Exception {
        submit("BTC-USD", "SELL", 100.00, "1");
        submit("BTC-USD", "BUY", 100.00, "1");
        submit("ETH-USD", "SELL", 200.00, "1");
        submit("ETH-USD", "BUY", 200.00, "1");

        mockMvc.perform(get("/api/instruments/BTC-USD/trades"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].price").value(100.00));
        mockMvc.perform(get("/api/instruments/ETH-USD/trades"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].price").value(200.00));
    }

    // ------------------------------------------------------------------ removed endpoints

    @Test
    @DisplayName("The old single book endpoints are gone")
    void oldEndpointsAreRemoved() throws Exception {
        mockMvc.perform(get("/api/orderbook"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/trades"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------------ helpers

    /** Submits a LIMIT order and returns its id. */
    private String submit(String symbol, String side, double price, String quantity) throws Exception {
        String body = """
                {"symbol":"%s","side":"%s","type":"LIMIT","price":"%s","quantity":"%s"}"""
                .formatted(symbol, side, price, quantity);
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractOrderId(result);
    }

    /** Pulls the order id out of the response without depending on any JSON library. */
    private String extractOrderId(MvcResult result) {
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        Matcher matcher = ORDER_ID.matcher(body);
        assertThat(matcher.find()).as("no order id in %s", body).isTrue();
        return matcher.group(1);
    }
}