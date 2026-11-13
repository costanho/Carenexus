package com.carenexus.api.common.config;

import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Route security (SecurityConfig): which paths are public, which roles pass the role gates, the error envelope
 * written by the filter chain, CORS headers and security response headers.
 *
 * Several of these paths have no controller yet (they are added by later work), so "the security filter let the
 * request through" is asserted on the response itself: a request stopped by the filter chain carries the filter's
 * own 401 / 403 message, anything else (404 from the dispatcher, a controller's own answer) came from behind it.
 */
public class SecurityRoutesTest extends CommittedIntegrationTest {

    private static final String FILTER_401 = "Authentication required";
    private static final String FILTER_403 = "You don't have permission to access this resource";

    private int anonymous(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private boolean stoppedByFilter(MvcResult result) throws Exception {
        int status = result.getResponse().getStatus();
        String message = message(result);
        return (status == 401 && FILTER_401.equals(message)) || (status == 403 && FILTER_403.equals(message));
    }

    private void assertPassedSecurity(MvcResult result, String what) throws Exception {
        assertFalse(stoppedByFilter(result), what + " was stopped by the security filter ("
                + result.getResponse().getStatus() + ")");
    }

    private void assertStoppedByFilter(int expectedStatus, MvcResult result, String what) throws Exception {
        assertEquals(expectedStatus, result.getResponse().getStatus(), what);
        assertTrue(stoppedByFilter(result), what + " should be refused by the security filter");
    }

    // ==================== PUBLIC PATHS ====================

    @Test
    void accountRecoveryVerificationAndMfaSteps_arePublicForPost() throws Exception {
        for (String path : new String[]{"/api/auth/password/forgot", "/api/auth/password/reset", "/api/auth/email/verify",
                "/api/auth/email/resend", "/api/auth/email-change/confirm", "/api/auth/mfa/verify", "/api/auth/mfa/setup/confirm"}) {
            assertPassedSecurity(mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn(),
                    "POST " + path);
        }
    }

    @Test
    void thosePathsArePublicForPostOnly_andOtherAuthPathsNeedAToken() throws Exception {
        assertEquals(401, anonymous(get("/api/auth/password/forgot")));
        assertEquals(401, anonymous(get("/api/auth/mfa/status")));
        assertEquals(401, anonymous(post("/api/auth/password/change").contentType(MediaType.APPLICATION_JSON).content("{}")));
        assertEquals(401, anonymous(post("/api/auth/email-change/request").contentType(MediaType.APPLICATION_JSON).content("{}")));
        assertEquals(401, anonymous(get("/api/users/me")));
    }

    @Test
    void legalTextsArePublicForGetOnly() throws Exception {
        assertPassedSecurity(mockMvc.perform(get("/api/legal/documents/terms")).andReturn(), "GET /api/legal/**");
        assertEquals(401, anonymous(post("/api/legal/accept").contentType(MediaType.APPLICATION_JSON).content("{}")));
    }

    @Test
    void loginRefreshRegisterLogoutAndHealth_arePublic() throws Exception {
        assertEquals(401, anonymous(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"not-a-real-token\"}")), "an invalid refresh token is answered by the controller");
        assertEquals(200, anonymous(post("/api/auth/logout")));
        assertEquals(200, anonymous(get("/actuator/health")));
    }

    // ==================== INTEGRATIONS / WEBHOOKS ====================

    @Test
    void providerWebhooksArePublicForPost_everyOtherIntegrationPathIsClosed() throws Exception {
        assertPassedSecurity(mockMvc.perform(post("/api/integrations/payments/local/webhook")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn(), "POST payments webhook");
        assertPassedSecurity(mockMvc.perform(post("/api/integrations/payments/mpesa/webhook")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn(), "POST payments webhook (another provider)");

        // closed: other methods, deeper paths, unknown integrations, even for an administrator
        assertEquals(403, doGet("/api/integrations/payments/local/webhook", admin).getResponse().getStatus());
        assertEquals(403, doPost("/api/integrations/payments/local/webhook/extra", admin, Map.of()).getResponse().getStatus());
        assertEquals(403, doPost("/api/integrations/payments/local", admin, Map.of()).getResponse().getStatus());
        assertEquals(403, doPost("/api/integrations/unknown/webhook", admin, Map.of()).getResponse().getStatus());
        assertEquals(401, anonymous(post("/api/integrations/unknown/webhook")));
    }

    // ==================== ROLE GATES ====================

    @Test
    void prometheusScrape_isAdminOnly() throws Exception {
        assertEquals(401, anonymous(get("/actuator/prometheus")));
        assertEquals(403, doGet("/actuator/prometheus", patient).getResponse().getStatus());
        assertEquals(403, doGet("/actuator/prometheus", doctor).getResponse().getStatus());
        assertPassedSecurity(doGet("/actuator/prometheus", admin), "admin GET /actuator/prometheus");
    }

    @Test
    void doctorDirectory_isOpenToEverySignedInUser_theRestOfDoctorsIsNot() throws Exception {
        // the legacy alias the booking hooks call
        assertEquals(200, doGet("/api/doctors/profile/list/active", patient).getResponse().getStatus());
        assertEquals(200, doGet("/api/doctors/profile/list/active", caregiver).getResponse().getStatus());
        assertEquals(401, anonymous(get("/api/doctors/profile/list/active")));
        // the paged directory and public profiles
        assertPassedSecurity(doGet("/api/doctors/profile/directory?page=0&size=5", patient), "patient GET directory");
        assertPassedSecurity(doGet("/api/doctors/profile/directory", caregiver), "caregiver GET directory");
        assertPassedSecurity(doGet("/api/doctors/profile/public/" + doctor.getSystemId(), patient), "patient GET public profile");

        // everything else under /api/doctors is for doctors and administrators
        assertStoppedByFilter(403, doGet("/api/doctors/patients", patient), "patient GET /api/doctors/patients");
        assertStoppedByFilter(403, doGet("/api/doctors/profile/search/specialization?specialization=x", patient),
                "patient GET the old search path");
        assertStoppedByFilter(403, doGet("/api/doctors/profile/available", caregiver), "caregiver GET the old available path");
        assertStoppedByFilter(403, doPost("/api/doctors/profile/directory", patient, Map.of()), "patient POST directory");
        assertPassedSecurity(doGet("/api/doctors/patients", doctor), "doctor GET /api/doctors/patients");
    }

    @Test
    void adminAndCaregiverAreas_areRoleGated() throws Exception {
        assertEquals(403, doGet("/api/admin/users", doctor).getResponse().getStatus());
        assertEquals(403, doGet("/api/admin/users", patient).getResponse().getStatus());
        assertEquals(200, doGet("/api/admin/users", admin).getResponse().getStatus());
        assertEquals(403, doGet("/api/caregiver/dependents", patient).getResponse().getStatus());
        assertEquals(200, doGet("/api/caregiver/dependents", caregiver).getResponse().getStatus());
    }

    // ==================== ERROR ENVELOPE FROM THE FILTER CHAIN ====================

    @Test
    void unauthenticated_is401_inTheSharedEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users/me")).andReturn();
        assertEquals(401, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentType().startsWith(MediaType.APPLICATION_JSON_VALUE));
        JsonNode body = json(result);
        assertEquals(401, body.path("status").asInt());
        assertEquals("Unauthorized", body.path("error").asText());
        assertEquals("Authentication required", body.path("message").asText());
        assertEquals("/api/users/me", body.path("path").asText());
        assertTrue(body.path("timestamp").asText().endsWith("Z"));
        assertDoesNotThrow(() -> Instant.parse(body.path("timestamp").asText()));
        assertTrue(body.path("code").isMissingNode());
        assertTrue(body.path("errors").isMissingNode());
    }

    @Test
    void forbidden_is403_inTheSharedEnvelope() throws Exception {
        MvcResult result = doGet("/api/admin/users", patient);
        assertEquals(403, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertEquals(403, body.path("status").asInt());
        assertEquals("Forbidden", body.path("error").asText());
        assertEquals("You don't have permission to access this resource", body.path("message").asText());
        assertEquals("/api/admin/users", body.path("path").asText());
        assertDoesNotThrow(() -> Instant.parse(body.path("timestamp").asText()));
    }

    @Test
    void authControllerErrors_useTheSharedEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"not-a-real-token\"}")).andReturn();
        assertEquals(401, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertEquals("Unauthorized", body.path("error").asText());
        assertEquals("/api/auth/refresh", body.path("path").asText());
        assertFalse(body.path("message").asText().isBlank());
        assertDoesNotThrow(() -> Instant.parse(body.path("timestamp").asText()));
    }

    @Test
    void adminUserList_usesTheSharedPageEnvelope_andClampsTheSize() throws Exception {
        JsonNode page = json(doGet("/api/admin/users?page=-4&size=5000", admin));
        assertEquals(0, page.path("page").asInt());
        assertEquals(100, page.path("size").asInt());
        assertEquals(7, page.path("totalElements").asInt());
        assertEquals(1, page.path("totalPages").asInt());
        assertEquals(7, page.path("content").size());
        assertTrue(page.path("content").get(0).path("passwordHash").isMissingNode());

        JsonNode doctors = json(doGet("/api/admin/users?role=doctor&active=true&size=1", admin));
        assertEquals(2, doctors.path("totalElements").asInt());
        assertEquals(1, doctors.path("content").size());

        // a forced password change must not hide a user from the administrator's list
        jdbc.update("UPDATE users SET must_change_password = TRUE WHERE system_id = ?", patient.getSystemId());
        assertEquals(2, json(doGet("/api/admin/users?role=patient", admin)).path("totalElements").asInt());

        JsonNode audit = json(doGet("/api/admin/audit-logs?size=1000", admin));
        assertEquals(100, audit.path("size").asInt());
        assertTrue(audit.path("content").isArray());
        assertEquals(400, doGet("/api/admin/users?role=nurse", admin).getResponse().getStatus());
    }

    // ==================== CORS AND RESPONSE HEADERS ====================

    @Test
    void corsPreflight_allowsIdempotencyKeyAndRequestId_fromAnAllowedOrigin() throws Exception {
        MvcResult preflight = mockMvc.perform(options("/api/appointments")
                .header("Origin", "http://localhost:8081")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization, content-type, idempotency-key, x-request-id")).andReturn();
        assertEquals(200, preflight.getResponse().getStatus());
        assertEquals("http://localhost:8081", preflight.getResponse().getHeader("Access-Control-Allow-Origin"));
        String allowed = String.valueOf(preflight.getResponse().getHeader("Access-Control-Allow-Headers")).toLowerCase();
        assertTrue(allowed.contains("idempotency-key"), allowed);
        assertTrue(allowed.contains("x-request-id"), allowed);

        MvcResult foreign = mockMvc.perform(options("/api/appointments")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")).andReturn();
        assertEquals(403, foreign.getResponse().getStatus());
        assertNull(foreign.getResponse().getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void corsResponses_exposeContentDispositionRetryAfterAndRequestId() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health").header("Origin", "http://localhost:8081")).andReturn();
        String exposed = String.valueOf(result.getResponse().getHeader("Access-Control-Expose-Headers")).toLowerCase();
        assertTrue(exposed.contains("content-disposition"), exposed);
        assertTrue(exposed.contains("retry-after"), exposed);
        assertTrue(exposed.contains("x-request-id"), exposed);
    }

    @Test
    void securityHeaders_areSetOnEveryResponse_andHstsOnHttps() throws Exception {
        MvcResult plain = mockMvc.perform(get("/api/users/me")).andReturn();
        assertEquals("nosniff", plain.getResponse().getHeader("X-Content-Type-Options"));
        assertEquals("DENY", plain.getResponse().getHeader("X-Frame-Options"));
        assertEquals("no-referrer", plain.getResponse().getHeader("Referrer-Policy"));
        assertNull(plain.getResponse().getHeader("Strict-Transport-Security"), "HSTS is only sent over HTTPS");

        MvcResult secure = mockMvc.perform(get("/actuator/health").secure(true)).andReturn();
        String hsts = secure.getResponse().getHeader("Strict-Transport-Security");
        assertNotNull(hsts);
        assertTrue(hsts.contains("max-age=31536000"), hsts);
        assertTrue(hsts.contains("includeSubDomains"), hsts);
        assertEquals("nosniff", secure.getResponse().getHeader("X-Content-Type-Options"));
    }
}
