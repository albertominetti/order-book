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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of the Zalando guidelines adopted by the API: RFC 7807 problem details served as
 * {@code application/problem+json}, the {@code X-Flow-ID} correlation header and the create/delete
 * status codes ({@code 201} with a {@code Location} header, {@code 204} on delete).
 *
 * <p>The context is refreshed before each test because the market registry is a per-context
 * singleton.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ZalandoGuidelinesApiTest {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String NO_SUCH_ORDER_ID = "3f1b7c58-0000-4000-8000-00000000dead";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("A 404 is an application/problem+json RFC 7807 document")
    void notFoundIsProblemJson() throws Exception {
        mockMvc.perform(get("/api/orders/{id}", NO_SUCH_ORDER_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.instance").value("/api/orders/" + NO_SUCH_ORDER_ID))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("A 400 is an application/problem+json RFC 7807 document with the violations")
    void badRequestIsProblemJson() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"BTC-USD","side":"BUY","type":"LIMIT","price":"100.00","quantity":"0"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/api/orders"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].field").value("quantity"));
    }

    @Test
    @DisplayName("POST /api/orders answers 201 with a Location header pointing to the order")
    void createReturns201AndLocation() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"BTC-USD","side":"BUY","type":"LIMIT","price":"100.00","quantity":"1"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertThat(location).startsWith("/api/orders/");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("BTC-USD"));
    }

    @Test
    @DisplayName("DELETE /api/orders/{id} answers 204 No Content with an empty body")
    void deleteReturns204() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"BTC-USD","side":"BUY","type":"LIMIT","price":"100.00","quantity":"1"}"""))
                .andExpect(status().isCreated())
                .andReturn();

        String location = created.getResponse().getHeader("Location");

        mockMvc.perform(delete(location))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("A sent X-Flow-ID is echoed verbatim on the response")
    void flowIdIsEchoed() throws Exception {
        mockMvc.perform(get("/api/instruments").header("X-Flow-ID", "test-flow-42"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Flow-ID", "test-flow-42"));
    }

    @Test
    @DisplayName("A missing X-Flow-ID is generated and returned on the response")
    void flowIdIsGenerated() throws Exception {
        mockMvc.perform(get("/api/instruments"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Flow-ID"));
    }

    @Test
    @DisplayName("The X-Flow-ID is repeated in the problem JSON as flowId")
    void flowIdInProblemJson() throws Exception {
        mockMvc.perform(get("/api/orders/{id}", NO_SUCH_ORDER_ID).header("X-Flow-ID", "trace-in-body"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Flow-ID", "trace-in-body"))
                .andExpect(jsonPath("$.flowId").value("trace-in-body"));
    }
}
