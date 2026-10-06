package com.albertominetti.orderbook.web;

import com.albertominetti.orderbook.service.MarketStreamBroadcaster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of the Server-Sent Events endpoints.
 *
 * <p>The contract under test is that a stream answers {@code 200 text/event-stream} and starts an
 * async request that stays open, that any valid symbol can be streamed even when no order has created
 * its book yet (an empty book is pushed, never a 404), that only a malformed symbol is rejected, and
 * that the REST endpoints keep answering unchanged while a stream is open and receiving every
 * publish.</p>
 *
 * <p>Like the other integration tests, the context is refreshed before each test, so every test
 * starts with an empty market.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class MarketStreamApiTest {

    /** One character too long, so the shape check rejects it with 400. */
    private static final String TWENTY_ONE_CHAR_SYMBOL = "ABCDEFGHIJ01234567890";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MarketStreamBroadcaster broadcaster;

    // ------------------------------------------------------------------ opening a stream

    @Test
    @DisplayName("GET /api/instruments/{symbol}/stream answers 200 text/event-stream and stays open")
    void instrumentStreamIsAnOpenEventStream() throws Exception {
        submit("BTC-USD", "BUY", "100.00", "2").andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/api/instruments/BTC-USD/stream"))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        // The response is deliberately never dispatched: completing it would mean waiting for the
        // stream to end, and it only ends when the client leaves or the container times it out.
        assertThat(result.getRequest().isAsyncStarted()).isTrue();
        assertThat(broadcaster.bookSubscriberCount("BTC-USD")).isEqualTo(1);
        assertThat(broadcaster.instrumentSubscriberCount()).isZero();

        // A fresh subscriber is never blank: the current state is already on the wire.
        assertThat(wire(result)).contains("event:book").contains("event:trades");
    }

    @Test
    @DisplayName("GET /api/instruments/stream answers 200 text/event-stream for the whole market")
    void marketStreamIsAnOpenEventStream() throws Exception {
        submit("BTC-USD", "BUY", "100.00", "2").andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/api/instruments/stream"))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        assertThat(broadcaster.instrumentSubscriberCount()).isEqualTo(1);
        assertThat(broadcaster.bookSubscriberCount("BTC-USD")).isZero();
        assertThat(wire(result)).contains("event:instruments").contains("\"symbol\":\"BTC-USD\"");
    }

    @Test
    @DisplayName("Every subscription is registered, a differently spelled symbol being the same one")
    void everySubscriptionIsRegistered() throws Exception {
        submit("BTC-USD", "BUY", "100.00", "2").andExpect(status().isCreated());

        mockMvc.perform(get("/api/instruments/BTC-USD/stream")).andExpect(status().isOk());
        mockMvc.perform(get("/api/instruments/btc-usd/stream")).andExpect(status().isOk());
        mockMvc.perform(get("/api/instruments/stream")).andExpect(status().isOk());

        assertThat(broadcaster.bookSubscriberCount("BTC-USD")).isEqualTo(2);
        assertThat(broadcaster.instrumentSubscriberCount()).isEqualTo(1);
        assertThat(broadcaster.subscriberCount()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ symbols without a book

    @Test
    @DisplayName("Streaming any valid symbol opens the stream and pushes an empty book first")
    void anyValidSymbolStreamsAnEmptyBook() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/instruments/UBSG/stream"))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        assertThat(result.getRequest().isAsyncStarted()).isTrue();
        assertThat(broadcaster.bookSubscriberCount("UBSG")).isEqualTo(1);

        // No bids, no asks and no price at all: an empty book the client can render right away,
        // instead of an error that would push it back to polling.
        assertThat(wire(result))
                .contains("event:book")
                .contains("event:trades")
                .contains("\"bids\":[]")
                .contains("\"asks\":[]")
                .doesNotContain("bestBid")
                .doesNotContain("bestAsk")
                .doesNotContain("spread")
                .doesNotContain("lastPrice");
    }

    @Test
    @DisplayName("A stream on a symbol without a book creates no instrument and its REST queries are an empty 200")
    void streamingDoesNotCreateTheInstrumentNorChangeItsRestQueries() throws Exception {
        submit("BTC-USD", "BUY", "100.00", "2").andExpect(status().isCreated());

        mockMvc.perform(get("/api/instruments/NOPE/stream")).andExpect(status().isOk());

        // The push channel and the query endpoints agree: an instrument nobody traded on is empty,
        // not missing, so both REST queries answer 200 with nothing in it.
        mockMvc.perform(get("/api/instruments/NOPE/orderbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bids").isEmpty())
                .andExpect(jsonPath("$.asks").isEmpty())
                .andExpect(jsonPath("$.bestBid").doesNotExist())
                .andExpect(jsonPath("$.bestAsk").doesNotExist());
        mockMvc.perform(get("/api/instruments/NOPE/trades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        assertThat(broadcaster.subscriberCount()).isEqualTo(1);
        mockMvc.perform(get("/api/instruments"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].symbol").value("BTC-USD"));
    }

    @Test
    @DisplayName("The first order on a streamed symbol pushes the book that replaces the empty one")
    void theFirstOrderReachesASubscriberOfAnEmptySymbol() throws Exception {
        MvcResult stream = mockMvc.perform(get("/api/instruments/UBSG/stream"))
                .andExpect(status().isOk())
                .andReturn();
        String afterSubscribe = wire(stream);

        submit("UBSG", "BUY", "100.00", "2").andExpect(status().isCreated());

        // The empty book opened the stream, the first order filled it for the very same subscriber.
        assertThat(wire(stream))
                .startsWith(afterSubscribe)
                .contains("\"bestBid\":100.00");
        assertThat(countOccurrences(wire(stream), "event:book")).isEqualTo(2);
        assertThat(broadcaster.bookSubscriberCount("UBSG")).isEqualTo(1);
        mockMvc.perform(get("/api/instruments/UBSG/orderbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestBid").value(100.00));
    }

    @Test
    @DisplayName("Streaming a malformed symbol is 400 VALIDATION_ERROR, like every other endpoint")
    void malformedSymbolIsRejected() throws Exception {
        mockMvc.perform(get("/api/instruments/" + TWENTY_ONE_CHAR_SYMBOL + "/stream"))
                .andExpect(status().isBadRequest())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].field").value("symbol"));

        assertThat(broadcaster.subscriberCount()).isZero();
    }

    // ------------------------------------------------------------------ coexistence with REST

    @Test
    @DisplayName("The REST endpoints keep answering unchanged while a stream is open")
    void restKeepsWorkingWhileAStreamIsOpen() throws Exception {
        MvcResult created = submit("BTC-USD", "BUY", "100.00", "3")
                .andExpect(status().isCreated())
                .andReturn();

        mockMvc.perform(get("/api/instruments/BTC-USD/stream"))
                .andExpect(status().isOk());

        // Every submission and cancellation publishes to the attached subscriber and still answers
        // the same 201, 200 and 200 as before the streams existed.
        submit("BTC-USD", "SELL", "120.00", "4")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("NEW"));

        mockMvc.perform(get("/api/instruments/BTC-USD/orderbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bids[0].price").value(100.00))
                .andExpect(jsonPath("$.asks[0].price").value(120.00));

        String location = created.getResponse().getHeader("Location");
        mockMvc.perform(delete(location))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/instruments/BTC-USD/orderbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bids").isEmpty())
                .andExpect(jsonPath("$.asks[0].price").value(120.00));

        // The subscriber of the now thinner book is still registered: a stream is long lived.
        assertThat(broadcaster.bookSubscriberCount("BTC-USD")).isEqualTo(1);
    }

    @Test
    @DisplayName("A trade generated by a crossing order reaches the subscriber of the symbol")
    void aCrossingOrderIsPushedWhileAStreamIsOpen() throws Exception {
        submit("BTC-USD", "SELL", "100.00", "2").andExpect(status().isCreated());

        MvcResult stream = mockMvc.perform(get("/api/instruments/BTC-USD/stream"))
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"BTC-USD","side":"BUY","type":"LIMIT","price":"100.00","quantity":"2"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("FILLED"))
                .andExpect(jsonPath("$.trades.length()").value(1))
                .andExpect(jsonPath("$.trades[0].symbol").value("BTC-USD"));

        // The submission pushed the new book and the new tape on the already open stream.
        assertThat(wire(stream))
                .contains("event:book")
                .contains("event:trades")
                .contains("\"lastPrice\":100.00");
        assertThat(broadcaster.bookSubscriberCount("BTC-USD")).isEqualTo(1);
    }

    @Test
    @DisplayName("A cancellation is pushed on the stream as a book event and nothing else")
    void aCancellationIsPushedWhileAStreamIsOpen() throws Exception {
        MvcResult created = submit("BTC-USD", "BUY", "100.00", "2")
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult stream = mockMvc.perform(get("/api/instruments/BTC-USD/stream"))
                .andExpect(status().isOk())
                .andReturn();
        String afterSubscribe = wire(stream);

        mockMvc.perform(delete(created.getResponse().getHeader("Location")))
                .andExpect(status().isNoContent());

        // One more book event, and no trade event: a cancellation generates no trade.
        assertThat(wire(stream)).startsWith(afterSubscribe).contains("event:book");
        assertThat(countOccurrences(wire(stream), "event:book")).isEqualTo(2);
        assertThat(countOccurrences(wire(stream), "event:trades")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    /** What an open stream has written so far, decoded as text. */
    private String wire(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private int countOccurrences(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    /** Submits a LIMIT order. */
    private ResultActions submit(String symbol, String side, String price, String quantity)
            throws Exception {
        String body = """
                {"symbol":"%s","side":"%s","type":"LIMIT","price":"%s","quantity":"%s"}"""
                .formatted(symbol, side, price, quantity);
        return mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
