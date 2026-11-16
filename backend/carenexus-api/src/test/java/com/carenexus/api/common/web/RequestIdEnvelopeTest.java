package com.carenexus.api.common.web;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.exception.GlobalExceptionHandler;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The correlation id reaches the error envelope: GlobalExceptionHandler's 400, 404 and 500 responses carry
 * {@code requestId}, equal to the {@code X-Request-Id} response header (standalone MockMvc with RequestIdFilter in
 * front, no database).
 */
class RequestIdEnvelopeTest {

    private MockMvc mockMvc;

    record Body(@NotBlank String note) {
    }

    @RestController
    @RequestMapping("/t")
    static class FailingController {
        @GetMapping("/boom")
        public void boom() {
            throw new NullPointerException("internal detail");
        }

        @GetMapping("/missing")
        public void missing() {
            throw new ResourceNotFoundException("Consultation not found");
        }

        @PostMapping("/valid")
        public void valid(@Valid @RequestBody Body body) {
        }

        @GetMapping("/ok")
        public String ok() {
            return "fine";
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    private static String header(MvcResult result) {
        return result.getResponse().getHeader(RequestCorrelation.HEADER);
    }

    @Test
    void a500Envelope_carriesTheGeneratedRequestId() throws Exception {
        MvcResult result = mockMvc.perform(get("/t/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andReturn();
        String requestId = header(result);
        assertNotNull(requestId);
        assertDoesNotThrow(() -> UUID.fromString(requestId));
        assertEquals(requestId, com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.requestId"));
    }

    @Test
    void a500Envelope_carriesTheClientsRequestId() throws Exception {
        mockMvc.perform(get("/t/boom").header(RequestCorrelation.HEADER, "client-report-7781"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.requestId").value("client-report-7781"));
    }

    @Test
    void a400ValidationEnvelope_carriesTheRequestId() throws Exception {
        MvcResult result = mockMvc.perform(post("/t/valid").header(RequestCorrelation.HEADER, "form-submit-0042")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorResponse.VALIDATION_FAILED))
                .andExpect(jsonPath("$.requestId").value("form-submit-0042"))
                .andReturn();
        assertEquals("form-submit-0042", header(result));
    }

    @Test
    void a400ForAnUnreadableBody_andA404_carryTheRequestIdToo() throws Exception {
        MvcResult unreadable = mockMvc.perform(post("/t/valid").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertEquals(header(unreadable),
                com.jayway.jsonpath.JsonPath.read(unreadable.getResponse().getContentAsString(), "$.requestId"));

        mockMvc.perform(get("/t/missing").header(RequestCorrelation.HEADER, "lookup-000404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.requestId").value("lookup-000404"));
    }

    @Test
    void successfulResponses_carryTheHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/t/ok")).andExpect(status().isOk()).andReturn();
        assertNotNull(header(result));
        assertNull(MDC.get(RequestCorrelation.MDC_REQUEST_ID), "the MDC is cleared after the request");
    }

    @Test
    void outsideARequest_theEnvelopeHasNoRequestId() {
        MDC.remove(RequestCorrelation.MDC_REQUEST_ID);
        assertNull(ErrorResponse.of(HttpStatus.BAD_REQUEST, "x", "/p").requestId());

        MDC.put(RequestCorrelation.MDC_REQUEST_ID, "on-this-thread-01");
        try {
            assertEquals("on-this-thread-01", ErrorResponse.of(HttpStatus.CONFLICT, "x", "/p", "CODE").requestId());
        } finally {
            MDC.remove(RequestCorrelation.MDC_REQUEST_ID);
        }
    }
}
