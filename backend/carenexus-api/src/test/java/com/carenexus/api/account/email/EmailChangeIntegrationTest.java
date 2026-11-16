package com.carenexus.api.account.email;

import com.carenexus.api.account.password.PasswordTestSupport;
import com.carenexus.api.account.password.service.PasswordResetTokenService;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.support.AuthApi;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.ratelimit.RateLimitProperties;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import static com.carenexus.api.account.email.AccountEmailTestSupport.TEMPLATE_CHANGED;
import static com.carenexus.api.account.email.AccountEmailTestSupport.TEMPLATE_CHANGE_CONFIRM;
import static com.carenexus.api.account.email.AccountEmailTestSupport.TEMPLATE_CHANGE_NOTICE;
import static com.carenexus.api.account.email.AccountEmailTestSupport.auditRows;
import static com.carenexus.api.account.email.AccountEmailTestSupport.confirmationToken;
import static com.carenexus.api.account.email.AccountEmailTestSupport.count;
import static com.carenexus.api.account.email.AccountEmailTestSupport.countMessages;
import static com.carenexus.api.account.email.AccountEmailTestSupport.newestBody;
import static com.carenexus.api.account.email.AccountEmailTestSupport.unverify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Email change: {@code POST/GET/DELETE /api/users/me/email-change} and {@code POST /api/auth/email-change/confirm}.
 */
class EmailChangeIntegrationTest extends CommittedIntegrationTest {

    private static final String ME = "/api/users/me/email-change";
    private static final String CONFIRM = "/api/auth/email-change/confirm";
    private static final String TOKEN_INVALID_MESSAGE =
            "This confirmation link is invalid or has expired. Request the change again from your account settings.";
    private static final String INSTANT_SECONDS = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$";

    @Autowired private RateLimitProperties rateLimitProperties;
    @Autowired private RateLimitGuard rateLimitGuard;

    private AuthApi api;

    @BeforeEach
    void setUp() {
        api = new AuthApi(mockMvc, objectMapper);
    }

    @AfterEach
    void restore() {
        rateLimitProperties.setEnabled(false);
    }

    // ==================== REQUEST ====================

    @Test
    void request_storesAPendingChange_mailsBothAddresses_andLeavesTheAccountAlone() throws Exception {
        MvcResult result = doPost(ME, patient, change(AuthApi.FIXTURE_PASSWORD, "  Thomas.New@Example.com "));

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        assertEquals("thomas.new@example.com", body.path("newEmail").asText());
        assertTrue(body.path("requestedAt").asText().matches(INSTANT_SECONDS), body.toString());
        assertTrue(body.path("expiresAt").asText().matches(INSTANT_SECONDS), body.toString());
        assertEquals(Duration.ofHours(24), Duration.between(Instant.parse(body.path("requestedAt").asText()),
                Instant.parse(body.path("expiresAt").asText())));
        assertEquals(3, body.size());

        // The account is unchanged until confirmation.
        User unchanged = userRepository.findBySystemId(patient.getSystemId()).orElseThrow();
        assertEquals("thomas@example.com", unchanged.getEmail());

        String confirmBody = newestBody(jdbc, TEMPLATE_CHANGE_CONFIRM, "thomas.new@example.com");
        assertNotNull(confirmBody);
        assertTrue(confirmBody.contains("\nhttp://localhost:8081/auth/confirm-email-change?token="));
        assertEquals(43, confirmationToken(jdbc, "thomas.new@example.com").length());
        String notice = newestBody(jdbc, TEMPLATE_CHANGE_NOTICE, "thomas@example.com");
        assertNotNull(notice);
        assertTrue(notice.contains("t***@example.com"), "the new address is masked in the notice");
        assertFalse(notice.contains("thomas.new@example.com"));
        assertFalse(notice.contains("token="), "the notice carries no token");

        assertEquals(1, auditRows(jdbc, "EMAIL_CHANGE_REQUESTED", patient.getSystemId()));
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM audit_log WHERE action = 'EMAIL_CHANGE_REQUESTED' "
                + "AND system_id = ? AND resource_type = 'ACCOUNT' AND status = 'SUCCESS'", patient.getSystemId()));

        // Still signed in: the request revokes nothing.
        assertEquals(200, doGet("/api/users/me", patient).getResponse().getStatus());
    }

    @Test
    void request_refusesAWrongPassword_theCurrentAddress_andATakenAddress() throws Exception {
        MvcResult wrong = doPost(ME, patient, change("wrong-password-1", "thomas.new@example.com"));
        assertEquals(400, wrong.getResponse().getStatus());
        assertEquals("INVALID_CURRENT_PASSWORD", json(wrong).path("code").asText());
        assertEquals("Current password is incorrect", message(wrong));

        MvcResult same = doPost(ME, patient, change(AuthApi.FIXTURE_PASSWORD, "THOMAS@example.com"));
        assertEquals(400, same.getResponse().getStatus());
        assertEquals("EMAIL_UNCHANGED", json(same).path("code").asText());
        assertEquals("New email must be different from your current email", message(same));

        MvcResult taken = doPost(ME, patient, change(AuthApi.FIXTURE_PASSWORD, "Mary@Example.com"));
        assertEquals(409, taken.getResponse().getStatus());
        assertEquals("Email already registered", message(taken));

        // The password is checked before the address.
        MvcResult wrongAndTaken = doPost(ME, patient, change("wrong-password-1", "mary@example.com"));
        assertEquals("INVALID_CURRENT_PASSWORD", json(wrongAndTaken).path("code").asText());

        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM email_change_requests"));
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM outbound_messages"));
        assertEquals(0, auditRows(jdbc, "EMAIL_CHANGE_REQUESTED", patient.getSystemId()));
    }

    @Test
    void request_refusesAnAccountWithoutAPassword() throws Exception {
        User noPassword = userRepository.findBySystemId(caregiver.getSystemId()).orElseThrow();
        noPassword.setPasswordHash(null);
        userRepository.saveAndFlush(noPassword);
        MvcResult result = doPost(ME, caregiver, change(AuthApi.FIXTURE_PASSWORD, "sarah.new@example.com"));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("INVALID_CURRENT_PASSWORD", json(result).path("code").asText());
    }

    @Test
    void request_validatesTheBody_andNeedsASignedInCaller() throws Exception {
        MvcResult empty = doPost(ME, patient, Map.of());
        assertEquals(400, empty.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(empty).path("code").asText());
        assertEquals("currentPassword: must not be blank", message(empty));
        assertEquals(2, json(empty).path("errors").size());

        MvcResult malformed = doPost(ME, patient, change(AuthApi.FIXTURE_PASSWORD, "not-an-email"));
        assertEquals("newEmail: must be a valid email address", message(malformed));

        MvcResult longPassword = doPost(ME, patient, change("p".repeat(129), "thomas.new@example.com"));
        assertEquals("currentPassword: size must be between 0 and 128", message(longPassword));

        MvcResult longEmail = doPost(ME, patient, change(AuthApi.FIXTURE_PASSWORD, "a".repeat(140) + "@example.com"));
        assertEquals("newEmail: size must be between 0 and 150", message(longEmail));

        MvcResult anonymous = mockMvc.perform(post(ME).contentType("application/json")
                .content(objectMapper.writeValueAsString(change(AuthApi.FIXTURE_PASSWORD, "x.y@example.com"))))
                .andReturn();
        assertEquals(401, anonymous.getResponse().getStatus());
        assertEquals(401, mockMvc.perform(get(ME)).andReturn().getResponse().getStatus());
        assertEquals(401, mockMvc.perform(delete(ME)).andReturn().getResponse().getStatus());
    }

    @Test
    void request_isLimitedPerAccount_withThePasswordChangeBucket() throws Exception {
        rateLimitGuard.reset("password-change", patient.getSystemId());
        rateLimitProperties.setEnabled(true);
        try {
            // Invalid bodies do not count.
            for (int i = 0; i < 3; i++) {
                assertEquals(400, doPost(ME, patient, Map.of()).getResponse().getStatus());
            }
            for (int i = 0; i < 5; i++) {
                assertEquals(400, doPost(ME, patient, change("wrong-password-" + i, "thomas.new@example.com"))
                        .getResponse().getStatus());
            }
            MvcResult limited = doPost(ME, patient, change(AuthApi.FIXTURE_PASSWORD, "thomas.new@example.com"));
            assertEquals(429, limited.getResponse().getStatus());
            assertEquals("RATE_LIMITED", json(limited).path("code").asText());
            assertNotNull(limited.getResponse().getHeader("Retry-After"));

            // Another account has its own allowance.
            rateLimitGuard.reset("password-change", otherPatient.getSystemId());
            assertEquals(201, doPost(ME, otherPatient, change(AuthApi.FIXTURE_PASSWORD, "mary.new@example.com"))
                    .getResponse().getStatus());
        } finally {
            rateLimitProperties.setEnabled(false);
            rateLimitGuard.reset("password-change", patient.getSystemId());
            rateLimitGuard.reset("password-change", otherPatient.getSystemId());
        }
    }

    @Test
    void aNewRequest_replacesTheEarlierOne_andOnlyTheNewestLinkWorks() throws Exception {
        assertEquals(201, doPost(ME, patient, change(AuthApi.FIXTURE_PASSWORD, "first.choice@example.com"))
                .getResponse().getStatus());
        String firstToken = confirmationToken(jdbc, "first.choice@example.com");
        assertEquals(201, doPost(ME, patient, change(AuthApi.FIXTURE_PASSWORD, "second.choice@example.com"))
                .getResponse().getStatus());
        String secondToken = confirmationToken(jdbc, "second.choice@example.com");

        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM email_change_requests WHERE system_id = ? "
                + "AND used_at IS NULL AND cancelled_at IS NULL", patient.getSystemId()));
        assertEquals("second.choice@example.com", json(doGet(ME, patient)).path("newEmail").asText());

        assertTokenInvalid(confirm(firstToken));
        MvcResult confirmed = confirm(secondToken);
        assertEquals(200, confirmed.getResponse().getStatus(), confirmed.getResponse().getContentAsString());
        assertEquals("second.choice@example.com", json(confirmed).path("email").asText());
    }

    // ==================== GET AND DELETE ====================

    @Test
    void pending_is204WhenNothingIsPending_200WithTheRequest_andIsPerAccount() throws Exception {
        MvcResult none = doGet(ME, patient);
        assertEquals(204, none.getResponse().getStatus());
        assertEquals("", none.getResponse().getContentAsString());

        MvcResult created = doPost(ME, patient, change(AuthApi.FIXTURE_PASSWORD, "thomas.new@example.com"));
        MvcResult pending = doGet(ME, patient);
        assertEquals(200, pending.getResponse().getStatus());
        assertEquals(json(created), json(pending));

        // Nobody else sees it, an administrator included.
        for (User other : new User[]{otherPatient, doctor, caregiver, admin}) {
            assertEquals(204, doGet(ME, other).getResponse().getStatus(), other.getRole());
        }

        // An expired request is not pending.
        jdbc.update("UPDATE email_change_requests SET expires_at = now() - interval '1 minute'");
        assertEquals(204, doGet(ME, patient).getResponse().getStatus());
    }

    @Test
    void cancel_stopsTheLink_isIdempotent_andAuditsOnlyARealCancellation() throws Exception {
        assertEquals(204, doDelete(ME, patient).getResponse().getStatus());
        assertEquals(0, auditRows(jdbc, "EMAIL_CHANGE_CANCELLED", patient.getSystemId()));

        doPost(ME, patient, change(AuthApi.FIXTURE_PASSWORD, "thomas.new@example.com"));
        String token = confirmationToken(jdbc, "thomas.new@example.com");

        // Another user's DELETE cancels only their own (nothing).
        assertEquals(204, doDelete(ME, otherPatient).getResponse().getStatus());
        assertEquals(200, doGet(ME, patient).getResponse().getStatus());

        int queued = count(jdbc, "SELECT COUNT(*) FROM outbound_messages");
        MvcResult cancelled = doDelete(ME, patient);
        assertEquals(204, cancelled.getResponse().getStatus());
        assertEquals("", cancelled.getResponse().getContentAsString());
        assertEquals(204, doGet(ME, patient).getResponse().getStatus());
        assertEquals(1, auditRows(jdbc, "EMAIL_CHANGE_CANCELLED", patient.getSystemId()));
        assertEquals(queued, count(jdbc, "SELECT COUNT(*) FROM outbound_messages"), "no mail on cancel");

        assertEquals(204, doDelete(ME, patient).getResponse().getStatus());
        assertEquals(1, auditRows(jdbc, "EMAIL_CHANGE_CANCELLED", patient.getSystemId()));

        assertTokenInvalid(confirm(token));
        assertEquals("thomas@example.com", userRepository.findBySystemId(patient.getSystemId()).orElseThrow().getEmail());
    }

    // ==================== CONFIRM ====================

    @Test
    void confirm_changesTheEmail_verifiesIt_endsEverySession_auditsAndNotifiesTheOldAddress() throws Exception {
        unverify(userRepository, patient);
        JsonNode session = json(api.login("thomas@example.com", AuthApi.FIXTURE_PASSWORD));
        String refreshToken = session.path("tokens").path("refreshToken").asText();
        JsonNode otherDevice = json(api.login("thomas@example.com", AuthApi.FIXTURE_PASSWORD));
        // An outstanding verification link for the old address stops working.
        assertEquals(200, api.send(post("/api/auth/email/resend"), Map.of("email", "thomas@example.com"), null)
                .getResponse().getStatus());
        String verifyToken = AccountEmailTestSupport.verificationToken(jdbc, "thomas@example.com");

        doPost(ME, patient, change(AuthApi.FIXTURE_PASSWORD, "thomas.new@example.com"));
        String token = confirmationToken(jdbc, "thomas.new@example.com");

        // A public endpoint: whatever bearer token the browser sends is ignored; the token alone decides.
        MvcResult result = api.send(post(CONFIRM), Map.of("token", token), "garbage-token");
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("{\"message\":\"Your email address has been changed. Sign in with your new email address.\","
                + "\"email\":\"thomas.new@example.com\"}", result.getResponse().getContentAsString());

        User changed = userRepository.findBySystemId(patient.getSystemId()).orElseThrow();
        assertEquals("thomas.new@example.com", changed.getEmail());
        assertNotNull(changed.getEmailVerifiedAt());
        assertTrue(changed.getEmailVerifiedAt().isAfter(LocalDateTime.now().minusMinutes(1)));

        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM refresh_tokens WHERE system_id = ? AND is_revoked = false",
                patient.getSystemId()), "every session ended");
        assertEquals(401, api.refresh(refreshToken).getResponse().getStatus());
        assertEquals(401, api.refresh(otherDevice.path("tokens").path("refreshToken").asText()).getResponse().getStatus());

        assertEquals(1, auditRows(jdbc, "EMAIL_CHANGE", patient.getSystemId()));
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM audit_log WHERE action = 'EMAIL_CHANGE' AND system_id = ? "
                + "AND resource_type = 'ACCOUNT' AND status = 'SUCCESS'", patient.getSystemId()));
        String notice = newestBody(jdbc, TEMPLATE_CHANGED, "thomas@example.com");
        assertNotNull(notice);
        assertFalse(notice.contains("http"), "the notice carries no link");
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM email_verification_tokens WHERE used_at IS NULL"));

        assertEquals(400, api.send(post("/api/auth/email/verify"), Map.of("token", verifyToken), null)
                .getResponse().getStatus());

        // Sign-in works with the new address only.
        assertEquals(401, api.login("thomas@example.com", AuthApi.FIXTURE_PASSWORD).getResponse().getStatus());
        MvcResult login = api.login("thomas.new@example.com", AuthApi.FIXTURE_PASSWORD);
        assertEquals(200, login.getResponse().getStatus());
        assertTrue(json(login).path("flags").path("emailVerified").asBoolean());
        assertEquals("thomas.new@example.com", json(login).path("user").path("email").asText());

        // The token works once.
        assertTokenInvalid(confirm(token));
        assertEquals(1, auditRows(jdbc, "EMAIL_CHANGE", patient.getSystemId()));
        assertEquals(1, countMessages(jdbc, TEMPLATE_CHANGED, "thomas@example.com"));
    }

    @Test
    void confirm_invalidatesPasswordResetLinksMailedToTheOldAddress() throws Exception {
        // A reset link reaches the old mailbox before the account moves to the new address.
        assertEquals(200, api.send(post("/api/auth/password/forgot"), Map.of("email", "thomas@example.com"), null)
                .getResponse().getStatus());
        String resetToken = PasswordTestSupport.latestEmail(jdbc, PasswordResetTokenService.TEMPLATE_RESET,
                "thomas@example.com").orElseThrow().token();
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM password_reset_tokens WHERE system_id = ? "
                + "AND used_at IS NULL", patient.getSystemId()));

        doPost(ME, patient, change(AuthApi.FIXTURE_PASSWORD, "thomas.new@example.com"));
        assertEquals(200, confirm(confirmationToken(jdbc, "thomas.new@example.com")).getResponse().getStatus());

        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM password_reset_tokens WHERE system_id = ? "
                + "AND used_at IS NULL", patient.getSystemId()));
        MvcResult reset = api.send(post("/api/auth/password/reset"),
                Map.of("token", resetToken, "newPassword", "Harvest2026moon"), null);
        assertEquals(400, reset.getResponse().getStatus(), reset.getResponse().getContentAsString());
        assertEquals("PASSWORD_RESET_TOKEN_INVALID", json(reset).path("code").asText());
        // The password is unchanged: the owner still signs in with it at the new address.
        assertEquals(200, api.login("thomas.new@example.com", AuthApi.FIXTURE_PASSWORD).getResponse().getStatus());
    }

    @Test
    void confirm_refusesExpiredUnknownAndBlankTokens() throws Exception {
        doPost(ME, patient, change(AuthApi.FIXTURE_PASSWORD, "thomas.new@example.com"));
        String token = confirmationToken(jdbc, "thomas.new@example.com");
        jdbc.update("UPDATE email_change_requests SET expires_at = now() - interval '1 second'");
        assertTokenInvalid(confirm(token));
        assertTokenInvalid(confirm("Zk1nq0u2mXh8sT4vB7cR5yW9aD3fG6jL0pEoIiUuYtQ"));

        MvcResult blank = confirm(" ");
        assertEquals(400, blank.getResponse().getStatus());
        assertEquals("token: must not be blank", message(blank));
        assertEquals("VALIDATION_FAILED", json(blank).path("code").asText());
        assertEquals("token: size must be between 0 and 200", message(confirm("x".repeat(201))));
        assertEquals("thomas@example.com", userRepository.findBySystemId(patient.getSystemId()).orElseThrow().getEmail());
    }

    @Test
    void confirm_refusesAnInactiveOrClosedAccount() throws Exception {
        doPost(ME, patient, change(AuthApi.FIXTURE_PASSWORD, "thomas.new@example.com"));
        String token = confirmationToken(jdbc, "thomas.new@example.com");
        User inactive = userRepository.findBySystemId(patient.getSystemId()).orElseThrow();
        inactive.setIsActive(false);
        userRepository.saveAndFlush(inactive);
        assertTokenInvalid(confirm(token));

        doPost(ME, otherPatient, change(AuthApi.FIXTURE_PASSWORD, "mary.new@example.com"));
        String otherToken = confirmationToken(jdbc, "mary.new@example.com");
        User closed = userRepository.findBySystemId(otherPatient.getSystemId()).orElseThrow();
        closed.setClosedAt(LocalDateTime.now());
        userRepository.saveAndFlush(closed);
        assertTokenInvalid(confirm(otherToken));
        assertEquals("mary@example.com", userRepository.findBySystemId(otherPatient.getSystemId()).orElseThrow().getEmail());
    }

    @Test
    void confirm_answers409WhenTheAddressWasTakenMeanwhile_andChangesNothing() throws Exception {
        doPost(ME, patient, change(AuthApi.FIXTURE_PASSWORD, "contested@example.com"));
        String token = confirmationToken(jdbc, "contested@example.com");
        JsonNode session = json(api.login("thomas@example.com", AuthApi.FIXTURE_PASSWORD));

        createAndSaveUser(30, "PAT-00030", "Contested@Example.com", "PATIENT");

        MvcResult taken = confirm(token);
        assertEquals(409, taken.getResponse().getStatus());
        assertEquals("Email already registered", message(taken));

        assertEquals("thomas@example.com", userRepository.findBySystemId(patient.getSystemId()).orElseThrow().getEmail());
        assertEquals(200, doGet(ME, patient).getResponse().getStatus(), "the request stays pending");
        assertEquals(200, api.refresh(session.path("tokens").path("refreshToken").asText()).getResponse().getStatus(),
                "no session was revoked");
        assertEquals(0, auditRows(jdbc, "EMAIL_CHANGE", patient.getSystemId()));
        assertEquals(0, countMessages(jdbc, TEMPLATE_CHANGED, "thomas@example.com"));
        assertEquals(409, confirm(token).getResponse().getStatus(), "confirming again answers 409 again");
    }

    @Test
    void everyRole_canChangeTheirOwnEmail() throws Exception {
        for (User user : new User[]{doctor, caregiver, admin}) {
            String newEmail = user.getRole().toLowerCase() + ".changed@example.com";
            assertEquals(201, doPost(ME, user, change(AuthApi.FIXTURE_PASSWORD, newEmail)).getResponse().getStatus());
            MvcResult confirmed = confirm(confirmationToken(jdbc, newEmail));
            assertEquals(200, confirmed.getResponse().getStatus(), user.getRole());
            assertEquals(newEmail, userRepository.findBySystemId(user.getSystemId()).orElseThrow().getEmail());
            assertNotEquals(user.getEmail(), newEmail);
        }
    }

    // ==================== HELPERS ====================

    private static Map<String, Object> change(String currentPassword, String newEmail) {
        return Map.of("currentPassword", currentPassword, "newEmail", newEmail);
    }

    private MvcResult confirm(String token) throws Exception {
        return api.send(post(CONFIRM), Map.of("token", token), null);
    }

    private void assertTokenInvalid(MvcResult result) throws Exception {
        assertEquals(400, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("TOKEN_INVALID", json(result).path("code").asText());
        assertEquals(TOKEN_INVALID_MESSAGE, message(result));
    }
}
