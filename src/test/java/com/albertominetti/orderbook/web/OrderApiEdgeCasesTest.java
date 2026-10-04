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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Edge cases of the REST API: the exact boundaries of the symbol length, of the numeric
 * precision, of the trade tape limit, and the contract of the endpoints on a book that is empty
 * or on an order that never rests.
 *
 * <p>Like the other integration tests, the context is refreshed before each test, so every test
 * starts with an empty market and can assert exact values.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class OrderApiEdgeCasesTest {

    /** A symbol of exactly 20 characters, the maximum accepted length. */
    private static final String TWENTY_CHAR_SYMBOL = "ABCDEFGHIJ0123456789";

    /** One character more than the maximum accepted length. */
    private static final String TWENTY_ONE_CHAR_SYMBOL = "ABCDEFGHIJ01234567890";

    /** An integer part of 13 digits, one more than the accepted maximum of 12. */
    private static final String THIRTEEN_DIGITS = "9999999999999";

    /** No Jackson in the tests: the order id is pulled out of the raw response. */
    private static final Pattern ORDER_ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]{8}-[0-9a-fA-F-]{27})\"");

    @Autowired
    private MockMvc mockMvc;

    // ------------------------------------------------------------------ symbol length

    @Test
    @DisplayName("POST /api/orders accepts a symbol of one character and of twenty characters")
    void symbolLengthBoundariesAreAccepted() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"A","side":"BUY","type":"LIMIT","price":"100.00","quantity":"1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.symbol").value("A"));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"%s","side":"SELL","type":"LIMIT","price":"100.00","quantity":"2"}"""
                                .formatted(TWENTY_CHAR_SYMBOL)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.symbol").value(TWENTY_CHAR_SYMBOL));

        // Both symbols created their own instrument.
        mockMvc.perform(get("/api/instruments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].symbol").value("A"))
                .andExpect(jsonPath("$[1].symbol").value(TWENTY_CHAR_SYMBOL));
    }

    @Test
    @DisplayName("POST /api/orders rejects a symbol of twenty-one characters with 400")
    void symbolOfTwentyOneCharactersIsRejected() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"%s","side":"BUY","type":"LIMIT","price":"100.00","quantity":"1"}"""
                                .formatted(TWENTY_ONE_CHAR_SYMBOL)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations.length()").value(1))
                .andExpect(jsonPath("$.violations[0].field").value("symbol"))
                .andExpect(jsonPath("$.violations[0].message")
                        .value("symbol must be 1 to 20 characters of letters, digits, '.', '_' or '-'"));

        // The rejected symbol never created an instrument.
        mockMvc.perform(get("/api/instruments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("POST /api/orders rejects a whitespace-only symbol with 400")
    void whitespaceOnlySymbolIsRejected() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"   ","side":"BUY","type":"LIMIT","price":"100.00","quantity":"1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                // A blank symbol breaks both the blank check and the shape check.
                .andExpect(jsonPath("$.violations.length()").value(2))
                .andExpect(jsonPath("$.violations[0].field").value("symbol"))
                .andExpect(jsonPath("$.violations[1].field").value("symbol"))
                .andExpect(content().string(containsString("symbol is required")))
                .andExpect(content().string(containsString("symbol must be 1 to 20 characters")));

        mockMvc.perform(get("/api/instruments"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("POST /api/orders rejects a symbol with a disallowed leading character with 400")
    void symbolWithDisallowedLeadingCharacterIsRejected() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"-ABC","side":"BUY","type":"LIMIT","price":"100.00","quantity":"1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations.length()").value(1))
                .andExpect(jsonPath("$.violations[0].field").value("symbol"))
                .andExpect(jsonPath("$.violations[0].message")
                        .value("symbol must be 1 to 20 characters of letters, digits, '.', '_' or '-'"));

        mockMvc.perform(get("/api/instruments"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ------------------------------------------------------------------ numeric boundaries

    @Test
    @DisplayName("POST /api/orders accepts a price with exactly eight decimal places")
    void priceWithEightDecimalPlacesIsAccepted() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"BTC-USD","side":"BUY","type":"LIMIT","price":"100.12345678","quantity":"1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.price").value(100.12345678))
                .andExpect(jsonPath("$.order.status").value("NEW"));

        mockMvc.perform(get("/api/instruments/BTC-USD/orderbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestBid").value(100.12345678));
    }

    @Test
    @DisplayName("POST /api/orders rejects a price whose integer part has thirteen digits")
    void priceWithThirteenIntegerDigitsIsRejected() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"BTC-USD","side":"BUY","type":"LIMIT","price":"%s","quantity":"1"}"""
                                .formatted(THIRTEEN_DIGITS)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations.length()").value(1))
                .andExpect(jsonPath("$.violations[0].field").value("price"));

        mockMvc.perform(get("/api/instruments"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("POST /api/orders rejects a quantity whose integer part has thirteen digits")
    void quantityWithThirteenIntegerDigitsIsRejected() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"BTC-USD","side":"BUY","type":"LIMIT","price":"100.00","quantity":"%s"}"""
                                .formatted(THIRTEEN_DIGITS)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations.length()").value(1))
                .andExpect(jsonPath("$.violations[0].field").value("quantity"));

        mockMvc.perform(get("/api/instruments"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ------------------------------------------------------------------ trade tape limit

    @Test
    @DisplayName("GET /api/instruments/{symbol}/trades accepts limit=1 and limit=1000 and rejects 1001")
    void tradeTapeLimitBoundaries() throws Exception {
        submit("BOUND-USD", "SELL", "100.00", "1");
        submit("BOUND-USD", "BUY", "100.00", "1");

        mockMvc.perform(get("/api/instruments/BOUND-USD/trades").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].symbol").value("BOUND-USD"));

        mockMvc.perform(get("/api/instruments/BOUND-USD/trades").param("limit", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/instruments/BOUND-USD/trades").param("limit", "1001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations.length()").value(1))
                .andExpect(jsonPath("$.violations[0].field").value("limit"))
                .andExpect(jsonPath("$.violations[0].message").value("limit must be at most 1000"));
    }

    // ------------------------------------------------------------------ empty market

    @Test
    @DisplayName("GET /api/instruments answers an empty JSON array on an empty market")
    void instrumentsOnAnEmptyMarketAreAnEmptyArray() throws Exception {
        mockMvc.perform(get("/api/instruments"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("[]"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("A MARKET order submitted on an empty book is accepted and cancelled with the whole quantity left")
    void marketOrderOnAnEmptyBookIsCancelled() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"BTC-USD","side":"BUY","type":"MARKET","quantity":"5"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.symbol").value("BTC-USD"))
                .andExpect(jsonPath("$.order.side").value("BUY"))
                .andExpect(jsonPath("$.order.type").value("MARKET"))
                .andExpect(jsonPath("$.order.price").doesNotExist())
                .andExpect(jsonPath("$.order.quantity").value(5))
                // Nothing traded, so nothing was filled and the remainder was discarded.
                .andExpect(jsonPath("$.order.filledQuantity").value(0))
                .andExpect(jsonPath("$.order.remainingQuantity").value(5))
                .andExpect(jsonPath("$.order.status").value("CANCELLED"))
                .andExpect(jsonPath("$.trades").isEmpty());

        // The instrument exists but its book stayed empty.
        mockMvc.perform(get("/api/instruments/BTC-USD/orderbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bids").isEmpty())
                .andExpect(jsonPath("$.asks").isEmpty())
                .andExpect(jsonPath("$.lastPrice").doesNotExist());
    }

    // ------------------------------------------------------------------ DELETE on a MARKET order

    @Test
    @DisplayName("DELETE /api/orders/{id} returns 422 when cancelling a filled MARKET order")
    void cancellingAFilledMarketOrderIsRejected() throws Exception {
        submit("BTC-USD", "SELL", "100.00", "2");

        MvcResult market = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"BTC-USD","side":"BUY","type":"MARKET","quantity":"2"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("FILLED"))
                .andReturn();
        String marketId = extractOrderId(market);

        mockMvc.perform(delete("/api/orders/{id}", marketId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATE"))
                .andExpect(jsonPath("$.message")
                        .value("order " + marketId + " cannot be cancelled because it is FILLED"));
    }

    @Test
    @DisplayName("DELETE /api/orders/{id} returns 422 when cancelling a MARKET order that never rested")
    void cancellingAMarketOrderThatNeverRestedIsRejected() throws Exception {
        MvcResult market = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"BTC-USD","side":"SELL","type":"MARKET","quantity":"3"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("CANCELLED"))
                .andReturn();
        String marketId = extractOrderId(market);

        mockMvc.perform(delete("/api/orders/{id}", marketId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATE"))
                .andExpect(jsonPath("$.message")
                        .value("order " + marketId + " cannot be cancelled because it is CANCELLED"));
    }

    // ------------------------------------------------------------------ Location header

    @Test
    @DisplayName("POST /api/orders returns a Location header of exactly /api/orders/{id} that serves the order")
    void locationHeaderIsTheOrderUrl() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"BTC-USD","side":"BUY","type":"LIMIT","price":"100.00","quantity":"5"}"""))
                .andExpect(status().isCreated())
                .andReturn();

        String id = extractOrderId(result);
        String location = result.getResponse().getHeader("Location");
        assertThat(location).isEqualTo("/api/orders/" + id);

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.symbol").value("BTC-USD"))
                .andExpect(jsonPath("$.status").value("NEW"));
    }

    // ------------------------------------------------------------------ helpers

    /** Submits a LIMIT order and returns its id. */
    private String submit(String symbol, String side, String price, String quantity) throws Exception {
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
