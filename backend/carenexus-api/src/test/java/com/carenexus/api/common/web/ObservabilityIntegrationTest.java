package com.carenexus.api.common.web;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.job.JobSupport;
import com.carenexus.api.delivery.OutboundMessageService;
import com.carenexus.api.delivery.OutboundRequest;
import com.carenexus.api.delivery.job.OutboundMessageWorker;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The deployed observability surface, with the real filter chain, the production endpoint exposure and the
 * Prometheus registry (Spring Boot tests switch metrics export off unless {@link AutoConfigureMetrics} is
 * present):
 * <ul>
 *   <li>/actuator/prometheus answers an administrator or the static scrape token (carenexus.metrics.scrape-token):
 *       401 without a token or with a wrong one, 403 for patient, doctor and caregiver, 200 for either; the scrape
 *       token opens no other path;</li>
 *   <li>the scrape contains the job counters (carenexus.jobs.runs) and the outbox outcome counters
 *       (carenexus.outbound.messages);</li>
 *   <li>every response carries X-Request-Id, and the envelopes written by the security chain and by
 *       GlobalExceptionHandler carry the same value as requestId.</li>
 * </ul>
 */
@AutoConfigureMetrics
@TestPropertySource(properties = {
        "management.endpoints.web.exposure.include=health, info, prometheus",
        "carenexus.metrics.scrape-token=" + ObservabilityIntegrationTest.SCRAPE_TOKEN
})
class ObservabilityIntegrationTest extends CommittedIntegrationTest {

    private static final String PROMETHEUS = "/actuator/prometheus";
    static final String SCRAPE_TOKEN = "test-scrape-token-0123456789abcdef0123456789";

    @Autowired
    private OutboundMessageService outbound;

    @Autowired
    private OutboundMessageWorker outboundWorker;

    @Autowired
    private JobSupport jobs;

    private static String requestIdHeader(MvcResult result) {
        return result.getResponse().getHeader(RequestCorrelation.HEADER);
    }

    // ==================== METRICS ====================

    @Test
    void prometheus_isRefusedWithoutAToken() throws Exception {
        MvcResult result = mockMvc.perform(get(PROMETHEUS)).andReturn();
        assertEquals(401, result.getResponse().getStatus());
        assertEquals("Authentication required", message(result));
    }

    @Test
    void prometheus_isForbiddenForEveryNonAdminRole() throws Exception {
        for (User user : new User[]{patient, doctor, caregiver, otherPatient}) {
            MvcResult result = doGet(PROMETHEUS, user);
            assertEquals(403, result.getResponse().getStatus(), user.getRole() + " must not scrape metrics");
            assertEquals("You don't have permission to access this resource", message(result));
            assertFalse(result.getResponse().getContentAsString().contains("carenexus_jobs_runs"),
                    "no metric leaks into a refusal");
        }
    }

    @Test
    void prometheus_exposesTheJobAndOutboxCounters_toAnAdministrator() throws Exception {
        // One outbox message through the local email sender: counts a run of the outbound job and one "sent".
        outbound.queue(new OutboundRequest("EMAIL", "metrics.check@example.com", null, null, null,
                "Metrics check", "Counted by the outbox worker.", null, "observability-test:" + UUID.randomUUID(),
                null, null));
        assertTrue(outboundWorker.sweepOnce() >= 1, "the worker sent the queued message");
        // A failing job run is counted with outcome=failure.
        assertThrows(IllegalStateException.class, () -> jobs.runExclusive("observability-check", () -> {
            throw new IllegalStateException("job failed on purpose");
        }));

        MvcResult result = doGet(PROMETHEUS, admin);
        assertEquals(200, result.getResponse().getStatus());
        assertTrue(String.valueOf(result.getResponse().getContentType()).startsWith("text/plain")
                || String.valueOf(result.getResponse().getContentType()).contains("openmetrics"),
                result.getResponse().getContentType());
        String scrape = result.getResponse().getContentAsString();

        assertScrapeHas(scrape, "carenexus_jobs_runs_total", "job=\"outbound\"", "outcome=\"success\"");
        assertScrapeHas(scrape, "carenexus_jobs_runs_total", "job=\"observability-check\"", "outcome=\"failure\"");
        assertScrapeHas(scrape, "carenexus_jobs_duration_seconds", "job=\"outbound\"");
        assertScrapeHas(scrape, "carenexus_outbound_messages_total", "channel=\"EMAIL\"", "outcome=\"sent\"");
        // Standard meters are there too (database pool, JVM).
        assertTrue(scrape.contains("hikaricp_connections"), "Hikari pool metrics are exposed");
        assertTrue(scrape.contains("jvm_memory_used_bytes"), "JVM metrics are exposed");
    }

    // Regression: a Prometheus server cannot hold an ADMIN access token (15 minutes, MFA to renew), so the
    // scrape must work with the static scrape token.
    @Test
    void prometheus_acceptsTheStaticScrapeToken() throws Exception {
        MvcResult result = mockMvc.perform(get(PROMETHEUS).header("Authorization", "Bearer " + SCRAPE_TOKEN)).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("jvm_memory_used_bytes"), "the scrape has metrics");
    }

    @Test
    void prometheus_refusesAWrongScrapeToken() throws Exception {
        for (String header : new String[]{"Bearer " + SCRAPE_TOKEN + "x", "Bearer " + SCRAPE_TOKEN.substring(1),
                "Bearer ", SCRAPE_TOKEN, "Basic " + SCRAPE_TOKEN}) {
            MvcResult result = mockMvc.perform(get(PROMETHEUS).header("Authorization", header)).andReturn();
            assertEquals(401, result.getResponse().getStatus(), "a wrong scrape credential is refused");
            assertFalse(result.getResponse().getContentAsString().contains("jvm_memory_used_bytes"));
        }
    }

    @Test
    void theScrapeToken_opensNoOtherPath() throws Exception {
        for (String path : new String[]{"/api/users/me", "/api/admin/users", "/api/profile", "/actuator/prometheus/x"}) {
            MvcResult result = mockMvc.perform(get(path).header("Authorization", "Bearer " + SCRAPE_TOKEN)).andReturn();
            assertEquals(401, result.getResponse().getStatus(), "the scrape token must not authenticate " + path);
        }
    }

    private static void assertScrapeHas(String scrape, String metric, String... labels) {
        boolean found = scrape.lines()
                .filter(line -> line.startsWith(metric))
                .anyMatch(line -> {
                    for (String label : labels) {
                        if (!line.contains(label)) {
                            return false;
                        }
                    }
                    return true;
                });
        assertTrue(found, metric + " with " + String.join(", ", labels) + " is missing from the scrape");
    }

    @Test
    void healthAndInfo_stayPublic() throws Exception {
        assertEquals(200, mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus());
        assertEquals(200, mockMvc.perform(get("/actuator/info")).andReturn().getResponse().getStatus());
    }

    // ==================== REQUEST CORRELATION ====================

    @Test
    void everyResponse_carriesARequestId_andAWellFormedOneIsEchoed() throws Exception {
        MvcResult generated = mockMvc.perform(get("/actuator/health")).andReturn();
        assertDoesNotThrow(() -> UUID.fromString(requestIdHeader(generated)));

        MvcResult echoed = doGet("/api/users/me", patient);
        assertEquals(200, echoed.getResponse().getStatus());
        assertNotNull(requestIdHeader(echoed));

        MvcResult supplied = mockMvc.perform(get("/actuator/health").header(RequestCorrelation.HEADER, "probe-0000-0001"))
                .andReturn();
        assertEquals("probe-0000-0001", requestIdHeader(supplied));

        MvcResult malformed = mockMvc.perform(get("/actuator/health").header(RequestCorrelation.HEADER, "bad id"))
                .andReturn();
        assertNotEquals("bad id", requestIdHeader(malformed));
    }

    @Test
    void theSecurityChainsEnvelopes_carryTheRequestId() throws Exception {
        MvcResult unauthorized = mockMvc.perform(get("/api/users/me").header(RequestCorrelation.HEADER, "web-401-check"))
                .andReturn();
        assertEquals(401, unauthorized.getResponse().getStatus());
        assertEquals("web-401-check", json(unauthorized).path("requestId").asText());

        MvcResult forbidden = doGet("/api/admin/users", patient);
        assertEquals(403, forbidden.getResponse().getStatus());
        assertEquals(requestIdHeader(forbidden), json(forbidden).path("requestId").asText());
        assertFalse(json(forbidden).path("requestId").asText().isBlank());
    }

    @Test
    void aValidationFailure_carriesTheRequestId() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").header(RequestCorrelation.HEADER, "login-400-check")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("login-400-check", requestIdHeader(result));
        assertEquals("login-400-check", json(result).path("requestId").asText());
    }
}
