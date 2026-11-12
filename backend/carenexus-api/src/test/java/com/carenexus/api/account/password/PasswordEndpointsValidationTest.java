package com.carenexus.api.account.password;

import com.carenexus.api.account.password.PasswordTestSupport.SwitchableHumanVerification;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.support.AuthApi;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.ratelimit.RateLimitProperties;
import com.carenexus.api.delivery.TemplateRenderer;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static com.carenexus.api.account.password.PasswordTestSupport.body;
import static com.carenexus.api.account.password.PasswordTestSupport.count;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Request validation of the three password endpoints (messages and order binding for the web), the
 * human-verification seam of forgot-password, the {@code mustChangePassword} flag and the shipped templates.
 */
@Import(PasswordTestSupport.class)
class PasswordEndpointsValidationTest extends CommittedIntegrationTest {

    private static final String FORGOT = "/api/auth/password/forgot";
    private static final String RESET = "/api/auth/password/reset";
    private static final String CHANGE = "/api/users/me/password";
    private static final String WEAK = "Password must be at least 8 characters and contain at least one letter and one number";

    @Autowired
    private SwitchableHumanVerification humanVerification;
    @Autowired
    private RateLimitProperties rateLimitProperties;
    @Autowired
    private RateLimitGuard rateLimitGuard;
    @Autowired
    private TemplateRenderer templateRenderer;

    private AuthApi api;

    @BeforeEach
    void setUpApi() {
        api = new AuthApi(mockMvc, objectMapper);
        humanVerification.reset();
    }

    // ==================== FORGOT ====================

    @Test
    void forgot_validationMessages() throws Exception {
        String longEmail = "a".repeat(139) + "@example.com"; // 151 characters, otherwise well-formed
        assertValidation(publicPost(FORGOT, body()), "email: Email is required", 1);
        assertValidation(publicPost(FORGOT, body("email", "   ")), "email: Email is required", 1);
        assertValidation(publicPost(FORGOT, body("email", "not-an-email")), "email: Please enter a valid email address", 1);
        assertValidation(publicPost(FORGOT, body("email", longEmail)), "email: Email must be at most 150 characters", 1);
        assertValidation(publicPost(FORGOT, body("email", patient.getEmail(), "humanVerificationToken", "t".repeat(4097))),
                "humanVerificationToken: Verification token is too long", 1);

        MvcResult malformed = mockMvc.perform(post(FORGOT).contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andReturn();
        assertEquals(400, malformed.getResponse().getStatus());
        assertEquals("Malformed request body", message(malformed));

        // An invalid request never reaches the verification seam and stores nothing.
        assertTrue(humanVerification.calls.isEmpty());
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM password_reset_tokens"));
    }

    @Test
    void forgot_humanVerificationRunsFirst_andARefusalIsNotCountedOrStored() throws Exception {
        String email = patient.getEmail();
        String ip = "192.0.2.41";
        rateLimitProperties.setEnabled(true);
        rateLimitGuard.reset("auth-forgot-email", email);
        rateLimitGuard.reset("auth-forgot-ip", ip);
        try {
            humanVerification.refuse = true;
            for (int i = 0; i < 4; i++) {
                MvcResult refused = api.from(ip).send(post(FORGOT),
                        body("email", email, "humanVerificationToken", "widget-token"), null);
                assertEquals(400, refused.getResponse().getStatus());
                assertEquals("HUMAN_VERIFICATION_FAILED", json(refused).path("code").asText());
            }
            assertEquals("PASSWORD_FORGOT|widget-token|" + ip, humanVerification.calls.get(0));
            assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM password_reset_tokens"));
            assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM outbound_messages"));

            // The refused requests did not use up the 3-per-hour allowance of the email.
            humanVerification.refuse = false;
            for (int i = 0; i < 3; i++) {
                assertEquals(200, api.from(ip).send(post(FORGOT), body("email", email), null).getResponse().getStatus());
            }
            assertEquals(429, api.from(ip).send(post(FORGOT), body("email", email), null).getResponse().getStatus());
        } finally {
            rateLimitProperties.setEnabled(false);
            rateLimitGuard.reset("auth-forgot-email", email);
            rateLimitGuard.reset("auth-forgot-ip", ip);
        }
    }

    // ==================== RESET ====================

    @Test
    void reset_validationMessages() throws Exception {
        assertValidation(publicPost(RESET, body("newPassword", "Harvest2026moon")), "token: Reset token is required", 1);
        assertValidation(publicPost(RESET, body("token", "  ", "newPassword", "Harvest2026moon")),
                "token: Reset token is required", 1);
        assertValidation(publicPost(RESET, body("token", "x".repeat(129), "newPassword", "Harvest2026moon")),
                "token: Reset token is invalid", 1);
        assertValidation(publicPost(RESET, body("token", "abc", "newPassword", "short")), "newPassword: " + WEAK, 1);
        // Both fields missing: one entry each, the message names the first field in alphabetical order.
        MvcResult both = publicPost(RESET, body());
        assertValidation(both, "newPassword: Password is required", 2);
        assertEquals("token", json(both).path("errors").path(1).path("field").asText());
    }

    // ==================== CHANGE ====================

    @Test
    void change_validationMessages() throws Exception {
        assertValidation(doPost(CHANGE, patient, body("newPassword", "Harvest2026moon")),
                "currentPassword: Current password is required", 1);
        assertValidation(doPost(CHANGE, patient, body("currentPassword", "p".repeat(129), "newPassword", "Harvest2026moon")),
                "currentPassword: Current password must be at most 128 characters", 1);
        assertValidation(doPost(CHANGE, patient, body("currentPassword", "password123", "newPassword", "nodigits")),
                "newPassword: " + WEAK, 1);
        for (String bad : List.of("", "abc", "6f1c2c1e-8a54-4f0b-9d0e-2b7a4c9e5d3", "6f1c2c1e8a544f0b9d0e2b7a4c9e5d31")) {
            assertValidation(doPost(CHANGE, patient, body("currentPassword", "password123",
                    "newPassword", "Harvest2026moon", "keepSessionId", bad)), "keepSessionId: Session id is invalid", 1);
        }
        // Nothing changed and nothing was audited by refused requests.
        User stored = userRepository.findBySystemId(patient.getSystemId()).orElseThrow();
        assertTrue(passwordEncoder.matches(AuthApi.FIXTURE_PASSWORD, stored.getPasswordHash()));
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM audit_log WHERE action = 'PASSWORD_CHANGE'"));
    }

    // ==================== FLAG AND TEMPLATES ====================

    @Test
    void mustChangePasswordFlag_isAlwaysABoolean_onSignInAndOnMe() throws Exception {
        JsonNode me = json(doGet("/api/users/me", patient));
        assertTrue(me.path("flags").path("mustChangePassword").isBoolean());
        assertFalse(me.path("flags").path("mustChangePassword").asBoolean());

        patient.setMustChangePassword(true);
        userRepository.saveAndFlush(patient);
        assertTrue(json(doGet("/api/users/me", patient)).path("flags").path("mustChangePassword").asBoolean());
        JsonNode login = api.json(api.login(patient.getEmail(), AuthApi.FIXTURE_PASSWORD));
        assertTrue(login.path("flags").path("mustChangePassword").asBoolean());
    }

    @Test
    void templates_renderTheLinkOnItsOwnLine_andNeverAPassword() {
        String link = "http://localhost:8081/auth/reset-password?token=abc_DEF-123";
        for (String template : List.of("account-password-reset", "account-password-invite")) {
            var rendered = templateRenderer.render(template, Map.of("name", "Amara", "link", link, "expiresMinutes", "45"));
            assertEquals(1, rendered.body().lines().filter(link::equals).count(), template);
            assertEquals(1, rendered.body().split(java.util.regex.Pattern.quote(link), -1).length - 1, template);
            assertTrue(rendered.body().contains("Hello Amara,"), template);
            assertTrue(rendered.body().contains("45 minutes"), template);
            assertFalse(rendered.body().contains("{{"), template);
        }
        var changed = templateRenderer.render("account-password-changed", Map.of("name", "Amara"));
        assertEquals("Your CareNexus password was changed", changed.subject());
        assertFalse(changed.body().contains("{{"));
        assertFalse(changed.body().contains("http"));
    }

    // ==================== HELPERS ====================

    private MvcResult publicPost(String url, Object payload) throws Exception {
        return api.send(post(url), payload, null);
    }

    private void assertValidation(MvcResult result, String expectedMessage, int errorCount) throws Exception {
        assertEquals(400, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode error = json(result);
        assertEquals("VALIDATION_FAILED", error.path("code").asText());
        assertEquals(expectedMessage, error.path("message").asText());
        assertEquals(errorCount, error.path("errors").size(), result.getResponse().getContentAsString());
    }
}
