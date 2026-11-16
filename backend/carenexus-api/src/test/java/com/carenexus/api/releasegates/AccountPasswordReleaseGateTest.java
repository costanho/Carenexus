package com.carenexus.api.releasegates;

import com.carenexus.api.account.password.PasswordTestSupport;
import com.carenexus.api.account.password.PasswordTestSupport.PasswordEventRecorder;
import com.carenexus.api.account.password.PasswordTestSupport.StoredEmail;
import com.carenexus.api.account.password.PasswordTestSupport.SwitchableHumanVerification;
import com.carenexus.api.account.password.config.PasswordResetProperties;
import com.carenexus.api.account.password.service.PasswordResetTokenService;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.support.AuthApi;
import com.carenexus.api.common.event.AdminPasswordResetRequestedEvent;
import com.carenexus.api.common.event.PasswordChangedEvent;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.ratelimit.MutableClock;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.ratelimit.RateLimitProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.account.password.PasswordTestSupport.auditRows;
import static com.carenexus.api.account.password.PasswordTestSupport.body;
import static com.carenexus.api.account.password.PasswordTestSupport.count;
import static com.carenexus.api.account.password.PasswordTestSupport.emails;
import static com.carenexus.api.account.password.PasswordTestSupport.latestEmail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Release gate for account recovery and password change (be-account-password):
 * <ul>
 *   <li>forgot-password never reveals whether an account exists and sends nothing to unknown, inactive, closed or
 *       managed accounts;</li>
 *   <li>a link works once, only the newest link works, and it expires (fixed clock);</li>
 *   <li>a reset ends every session, clears the forced-change flag and is audited;</li>
 *   <li>a weak password is refused before the token is consumed;</li>
 *   <li>changing the password needs the current one (400, then 429), keeps only the named session and clears the
 *       flag;</li>
 *   <li>an administrator's reset and a caregiver's invitation only send a link; the invitation sets the first
 *       password of an account that has none.</li>
 * </ul>
 * The publishers of {@link AdminPasswordResetRequestedEvent} are built in parallel, so this gate publishes the event
 * itself, inside a transaction like the publishers do.
 */
@Import(PasswordTestSupport.class)
class AccountPasswordReleaseGateTest extends CommittedIntegrationTest {

    private static final String FORGOT = "/api/auth/password/forgot";
    private static final String RESET = "/api/auth/password/reset";
    private static final String CHANGE = "/api/users/me/password";
    private static final String FORGOT_TEXT = "If an account exists for that email, a password reset link has been sent.";
    private static final String NEW_PASSWORD = "Harvest2026moon";
    private static final String OLD_PASSWORD = AuthApi.FIXTURE_PASSWORD;

    @Autowired
    private PasswordResetTokenService tokenService;
    @Autowired
    private PasswordResetProperties properties;
    @Autowired
    private PasswordEventRecorder events;
    @Autowired
    private SwitchableHumanVerification humanVerification;
    @Autowired
    private RateLimitProperties rateLimitProperties;
    @Autowired
    private RateLimitGuard rateLimitGuard;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private AuthApi api;

    @BeforeEach
    void setUpApi() {
        api = new AuthApi(mockMvc, objectMapper);
        events.changed.clear();
        humanVerification.reset();
    }

    // ==================== FORGOT PASSWORD ====================

    @Test
    void forgot_unknownInactiveClosedAndManagedAccounts_get200AndNothingIsStored() throws Exception {
        User inactive = createAndSaveUser(20, "PAT-00020", "inactive@example.com", "PATIENT");
        inactive.setIsActive(false);
        userRepository.saveAndFlush(inactive);
        User closed = createAndSaveUser(21, "PAT-00021", "closed@example.com", "PATIENT");
        closed.setClosedAt(LocalDateTime.now());
        userRepository.saveAndFlush(closed);
        User managed = createAndSaveUser(22, "PAT-00022", "managed@example.com", "PATIENT");
        managed.setPasswordHash(null);
        userRepository.saveAndFlush(managed);

        String known = forgotRaw(patient.getEmail()).getResponse().getContentAsString();
        for (String email : List.of("nobody@example.com", "inactive@example.com", "closed@example.com",
                "managed@example.com", "  Nobody@Example.com  ")) {
            MvcResult result = forgotRaw(email);
            assertEquals(200, result.getResponse().getStatus(), email);
            assertEquals(FORGOT_TEXT, json(result).path("message").asText(), email);
            // Byte-for-byte the same answer as for an account that exists.
            assertEquals(known, result.getResponse().getContentAsString(), email);
        }

        // Only the real account got a token and an email; nothing was audited for the request.
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM password_reset_tokens"));
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM password_reset_tokens WHERE system_id = ?", patient.getSystemId()));
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM outbound_messages"));
        assertEquals(List.of(patient.getEmail()),
                emails(jdbc, PasswordResetTokenService.TEMPLATE_RESET).stream().map(StoredEmail::to).toList());
        assertTrue(auditRows(jdbc).isEmpty());
    }

    @Test
    void forgot_storesOnlyTheHash_mailsAStandaloneLink_andANewRequestSupersedesTheOldLink() throws Exception {
        forgot("  " + patient.getEmail().toUpperCase() + " ");
        StoredEmail first = latestEmail(jdbc, PasswordResetTokenService.TEMPLATE_RESET, patient.getEmail()).orElseThrow();
        String firstToken = first.token();

        assertEquals(43, firstToken.length());
        assertEquals("Reset your CareNexus password", first.subject());
        assertEquals(patient.getSystemId(), first.recipientSystemId());
        String link = "http://localhost:8081/auth/reset-password?token=" + firstToken;
        assertTrue(first.body().lines().anyMatch(link::equals), "the link stands alone on its own line");
        assertTrue(first.body().contains(properties.getTtlMinutes() + " minutes"));
        assertTrue(first.dedupeKey().startsWith("PASSWORD_RESET:"));
        // The raw token is never stored.
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM password_reset_tokens WHERE token_hash = ?", firstToken));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT token_hash, expires_at, created_at, used_at, requested_ip, requested_by_system_id "
                        + "FROM password_reset_tokens WHERE system_id = ?", patient.getSystemId());
        assertTrue(((String) row.get("token_hash")).matches("^[0-9a-f]{64}$"));
        assertEquals(Duration.ofMinutes(properties.getTtlMinutes()), Duration.between(
                ((java.sql.Timestamp) row.get("created_at")).toLocalDateTime(),
                ((java.sql.Timestamp) row.get("expires_at")).toLocalDateTime()));
        assertNull(row.get("used_at"));
        assertEquals("127.0.0.1", row.get("requested_ip"));
        assertNull(row.get("requested_by_system_id"));

        // A second request supersedes the first link: only the newest works.
        forgot(patient.getEmail());
        String secondToken = latestEmail(jdbc, PasswordResetTokenService.TEMPLATE_RESET, patient.getEmail())
                .orElseThrow().token();
        assertNotEquals(firstToken, secondToken);
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM password_reset_tokens WHERE used_at IS NULL"));
        assertTokenInvalid(reset(firstToken, NEW_PASSWORD));
        assertEquals(200, reset(secondToken, NEW_PASSWORD).getResponse().getStatus());
    }

    @Test
    void forgot_isLimitedPerEmailAndPerAddress_beforeTheAccountIsLookedUp() throws Exception {
        String email = "limit-" + System.nanoTime() + "@example.com";
        List<String> ips = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            ips.add("203.0.113." + i);
        }
        String floodIp = "198.51.100.77";
        rateLimitProperties.setEnabled(true);
        try {
            resetBuckets(email, ips, floodIp);
            // auth-forgot-email: 3 per hour for one address, whatever the client address.
            for (int i = 0; i < 3; i++) {
                assertEquals(200, api.from(ips.get(i)).send(post(FORGOT), body("email", email), null)
                        .getResponse().getStatus());
            }
            MvcResult limited = api.from(ips.get(3)).send(post(FORGOT), body("email", email), null);
            assertEquals(429, limited.getResponse().getStatus());
            assertEquals("RATE_LIMITED", json(limited).path("code").asText());
            assertNotNull(limited.getResponse().getHeader("Retry-After"));

            // auth-forgot-ip: 10 per hour from one address, for unknown and known emails alike.
            for (int i = 0; i < 10; i++) {
                assertEquals(200, api.from(floodIp).send(post(FORGOT),
                        body("email", "flood" + i + "-" + System.nanoTime() + "@example.com"), null)
                        .getResponse().getStatus());
            }
            assertEquals(429, api.from(floodIp).send(post(FORGOT), body("email", patient.getEmail()), null)
                    .getResponse().getStatus());
            // The refused request for a real account issued nothing.
            assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM password_reset_tokens"));
        } finally {
            rateLimitProperties.setEnabled(false);
            resetBuckets(email, ips, floodIp);
        }
    }

    // ==================== RESET ====================

    @Test
    void reset_isSingleUse_setsThePassword_endsEverySession_clearsTheFlag_andIsAudited() throws Exception {
        patient.setMustChangePassword(true);
        userRepository.saveAndFlush(patient);
        JsonNode phone = login(patient, OLD_PASSWORD);
        JsonNode laptop = login(patient, OLD_PASSWORD);
        JsonNode doctorSession = login(doctor, OLD_PASSWORD);
        assertTrue(phone.path("flags").path("mustChangePassword").asBoolean());

        String token = requestLink(patient);
        MvcResult ok = reset(token, NEW_PASSWORD);
        assertEquals(200, ok.getResponse().getStatus());
        assertEquals("Your password has been reset. You can now sign in.", json(ok).path("message").asText());
        assertTrue(json(ok).path("tokens").isMissingNode(), "no tokens are returned");

        // Single use: the same link (or a replay of the request) is refused with the generic answer.
        assertTokenInvalid(reset(token, "Another2026pass"));

        // Every session of the account ended; another account's session did not.
        assertEquals(401, api.refresh(phone.path("tokens").path("refreshToken").asText()).getResponse().getStatus());
        assertEquals(401, api.refresh(laptop.path("tokens").path("refreshToken").asText()).getResponse().getStatus());
        assertEquals(200, api.refresh(doctorSession.path("tokens").path("refreshToken").asText()).getResponse().getStatus());

        // Old password refused, new one accepted, flag cleared.
        assertEquals(401, api.login(patient.getEmail(), OLD_PASSWORD).getResponse().getStatus());
        JsonNode signedIn = login(patient, NEW_PASSWORD);
        assertFalse(signedIn.path("flags").path("mustChangePassword").asBoolean());
        assertTrue(signedIn.path("flags").path("mustChangePassword").isBoolean());
        User stored = userRepository.findBySystemId(patient.getSystemId()).orElseThrow();
        assertFalse(stored.getMustChangePassword());
        assertNotNull(stored.getPasswordChangedAt());
        assertNotNull(stored.getEmailVerifiedAt(), "email verification is not touched");

        assertEquals(List.of("PASSWORD_RESET PAT-00001->PAT-00001 SUCCESS ACCOUNT"), auditRows(jdbc));
        assertEquals(List.of(new PasswordChangedEvent(patient.getSystemId(), PasswordChangedEvent.RESET)), events.changed);
        StoredEmail notice = latestEmail(jdbc, PasswordResetTokenService.TEMPLATE_CHANGED, patient.getEmail()).orElseThrow();
        assertEquals("Your CareNexus password was changed", notice.subject());
        assertFalse(notice.body().contains("http"), "the notice carries no link");
        assertFalse(notice.body().contains(NEW_PASSWORD));
    }

    @Test
    void reset_afterTheLifetime_isRefused_withAFixedClock() throws Exception {
        MutableClock clock = new MutableClock(Instant.now());
        PasswordTestSupport.withClock(tokenService, clock, () -> {
            String expired = requestLink(patient);
            clock.advance(Duration.ofMinutes(properties.getTtlMinutes()).plusSeconds(1));
            assertTokenInvalid(reset(expired, NEW_PASSWORD));

            // Exactly at the expiry instant the link no longer works.
            String atBoundary = requestLink(patient);
            clock.advance(Duration.ofMinutes(properties.getTtlMinutes()));
            assertTokenInvalid(reset(atBoundary, NEW_PASSWORD));

            String fresh = requestLink(patient);
            clock.advance(Duration.ofMinutes(properties.getTtlMinutes()).minusSeconds(1));
            assertEquals(200, reset(fresh, NEW_PASSWORD).getResponse().getStatus());
            return null;
        });
        assertEquals(200, api.login(patient.getEmail(), NEW_PASSWORD).getResponse().getStatus());
    }

    @Test
    void reset_withAWeakPassword_is400_andTheLinkStaysUsable() throws Exception {
        String token = requestLink(patient);
        Map<String, String> weak = Map.of(
                "", "newPassword: Password is required",
                "short1", "newPassword: Password must be at least 8 characters and contain at least one letter and one number",
                "lettersonly", "newPassword: Password must be at least 8 characters and contain at least one letter and one number",
                "12345678", "newPassword: Password must be at least 8 characters and contain at least one letter and one number",
                "a1" + "x".repeat(71), "newPassword: Password must be at most 72 characters");
        for (Map.Entry<String, String> entry : weak.entrySet()) {
            MvcResult result = reset(token, entry.getKey());
            assertEquals(400, result.getResponse().getStatus(), entry.getKey());
            assertEquals("VALIDATION_FAILED", json(result).path("code").asText());
            assertEquals(entry.getValue(), json(result).path("message").asText());
            assertEquals("newPassword", json(result).path("errors").path(0).path("field").asText());
        }
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM password_reset_tokens WHERE used_at IS NOT NULL"));
        assertEquals(200, reset(token, NEW_PASSWORD).getResponse().getStatus());
    }

    @Test
    void reset_ofAClosedAccount_orAnUnknownToken_getsTheGenericAnswer() throws Exception {
        String token = requestLink(patient);
        patient.setClosedAt(LocalDateTime.now());
        userRepository.saveAndFlush(patient);
        assertTokenInvalid(reset(token, NEW_PASSWORD));
        assertTokenInvalid(reset("pX3vYt0Jr8mQe1uB7n-Zk4sLw2Hc9aDf6GiRoT5yUxE", NEW_PASSWORD));
        // The password did not change.
        User stored = userRepository.findBySystemId(patient.getSystemId()).orElseThrow();
        assertTrue(passwordEncoder.matches(OLD_PASSWORD, stored.getPasswordHash()));
        assertTrue(auditRows(jdbc).isEmpty());
    }

    // ==================== CHANGE ====================

    @Test
    void change_wrongCurrentPassword_is400_thenTheSixthAttemptIs429() throws Exception {
        rateLimitProperties.setEnabled(true);
        rateLimitGuard.reset("password-change", patient.getSystemId());
        try {
            for (int i = 0; i < 5; i++) {
                MvcResult wrong = doPost(CHANGE, patient,
                        body("currentPassword", "not-my-password1", "newPassword", NEW_PASSWORD));
                assertEquals(400, wrong.getResponse().getStatus());
                assertEquals("CURRENT_PASSWORD_INCORRECT", json(wrong).path("code").asText());
                assertEquals("Current password is incorrect", message(wrong));
            }
            // The sixth attempt inside the window is refused even with the right password.
            MvcResult limited = doPost(CHANGE, patient,
                    body("currentPassword", OLD_PASSWORD, "newPassword", NEW_PASSWORD));
            assertEquals(429, limited.getResponse().getStatus());
            assertEquals("RATE_LIMITED", json(limited).path("code").asText());
            assertNotNull(limited.getResponse().getHeader("Retry-After"));
            // The bucket is per account: another user is not affected.
            assertEquals(200, doPost(CHANGE, otherPatient,
                    body("currentPassword", OLD_PASSWORD, "newPassword", NEW_PASSWORD)).getResponse().getStatus());
        } finally {
            rateLimitProperties.setEnabled(false);
            rateLimitGuard.reset("password-change", patient.getSystemId());
            rateLimitGuard.reset("password-change", otherPatient.getSystemId());
        }
        User stored = userRepository.findBySystemId(patient.getSystemId()).orElseThrow();
        assertTrue(passwordEncoder.matches(OLD_PASSWORD, stored.getPasswordHash()));
        List<String> rows = auditRows(jdbc);
        assertEquals(5, rows.stream().filter("PASSWORD_CHANGE PAT-00001->PAT-00001 FAILURE ACCOUNT"::equals).count());
        assertTrue(rows.contains("PASSWORD_CHANGE PAT-00002->PAT-00002 SUCCESS ACCOUNT"));
    }

    @Test
    void change_keepsOnlyTheNamedSession_clearsTheFlag_andSupersedesResetLinks() throws Exception {
        patient.setMustChangePassword(true);
        userRepository.saveAndFlush(patient);
        JsonNode kept = login(patient, OLD_PASSWORD);
        JsonNode second = login(patient, OLD_PASSWORD);
        JsonNode third = login(patient, OLD_PASSWORD);
        String pendingLink = requestLink(patient);
        String keepSessionId = kept.path("tokens").path("sessionId").asText();
        String accessToken = kept.path("tokens").path("accessToken").asText();

        MvcResult result = api.send(post(CHANGE), body("currentPassword", OLD_PASSWORD, "newPassword", NEW_PASSWORD,
                "keepSessionId", keepSessionId), accessToken);
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("Your password has been changed.", json(result).path("message").asText());

        // The named session survives (its refresh works, the access token still works); the others ended.
        MvcResult refreshed = api.refresh(kept.path("tokens").path("refreshToken").asText());
        assertEquals(200, refreshed.getResponse().getStatus());
        assertEquals(keepSessionId, api.json(refreshed).path("tokens").path("sessionId").asText());
        assertFalse(api.json(refreshed).path("flags").path("mustChangePassword").asBoolean());
        assertEquals(401, api.refresh(second.path("tokens").path("refreshToken").asText()).getResponse().getStatus());
        assertEquals(401, api.refresh(third.path("tokens").path("refreshToken").asText()).getResponse().getStatus());
        MvcResult me = api.me(accessToken);
        assertEquals(200, me.getResponse().getStatus());
        assertFalse(api.json(me).path("flags").path("mustChangePassword").asBoolean());

        // The reset link that was pending stopped working.
        assertTokenInvalid(reset(pendingLink, "Another2026pass"));
        assertEquals(200, api.login(patient.getEmail(), NEW_PASSWORD).getResponse().getStatus());
        assertEquals(List.of("PASSWORD_CHANGE PAT-00001->PAT-00001 SUCCESS ACCOUNT"), auditRows(jdbc));
        assertEquals(List.of(new PasswordChangedEvent(patient.getSystemId(), PasswordChangedEvent.CHANGE)), events.changed);
        assertTrue(latestEmail(jdbc, PasswordResetTokenService.TEMPLATE_CHANGED, patient.getEmail()).isPresent());

        // A repeat of the request that succeeded now has the wrong current password.
        MvcResult repeat = api.send(post(CHANGE), body("currentPassword", OLD_PASSWORD, "newPassword", NEW_PASSWORD,
                "keepSessionId", keepSessionId), accessToken);
        assertEquals("CURRENT_PASSWORD_INCORRECT", api.json(repeat).path("code").asText());
    }

    @Test
    void change_withoutKeepSessionId_orWithAnUnknownOne_endsEverySession() throws Exception {
        JsonNode first = login(patient, OLD_PASSWORD);
        assertEquals(200, doPost(CHANGE, patient, body("currentPassword", OLD_PASSWORD, "newPassword", NEW_PASSWORD))
                .getResponse().getStatus());
        assertEquals(401, api.refresh(first.path("tokens").path("refreshToken").asText()).getResponse().getStatus());

        JsonNode second = login(patient, NEW_PASSWORD);
        assertEquals(200, doPost(CHANGE, patient, body("currentPassword", NEW_PASSWORD, "newPassword", "Third2026pass",
                "keepSessionId", "6f1c2c1e-8a54-4f0b-9d0e-2b7a4c9e5d31")).getResponse().getStatus());
        assertEquals(401, api.refresh(second.path("tokens").path("refreshToken").asText()).getResponse().getStatus());
    }

    @Test
    void change_samePassword_isRefused_andEveryRoleChangesOnlyItsOwnPassword() throws Exception {
        MvcResult same = doPost(CHANGE, patient, body("currentPassword", OLD_PASSWORD, "newPassword", OLD_PASSWORD));
        assertEquals(400, same.getResponse().getStatus());
        assertEquals("PASSWORD_UNCHANGED", json(same).path("code").asText());
        assertEquals("New password must be different from your current password", message(same));

        // No identity in the body: each caller changes exactly their own password.
        for (User user : List.of(doctor, caregiver, admin)) {
            assertEquals(200, doPost(CHANGE, user, body("currentPassword", OLD_PASSWORD, "newPassword", NEW_PASSWORD,
                    "systemId", patient.getSystemId())).getResponse().getStatus(), user.getRole());
            assertTrue(passwordEncoder.matches(NEW_PASSWORD,
                    userRepository.findBySystemId(user.getSystemId()).orElseThrow().getPasswordHash()));
        }
        assertTrue(passwordEncoder.matches(OLD_PASSWORD,
                userRepository.findBySystemId(patient.getSystemId()).orElseThrow().getPasswordHash()));
        assertTrue(passwordEncoder.matches(OLD_PASSWORD,
                userRepository.findBySystemId(otherDoctor.getSystemId()).orElseThrow().getPasswordHash()));

        // Without an access token: 401 in the envelope.
        MvcResult anonymous = api.send(post(CHANGE), body("currentPassword", OLD_PASSWORD, "newPassword", NEW_PASSWORD), null);
        assertEquals(401, anonymous.getResponse().getStatus());
        assertEquals("Authentication required", api.json(anonymous).path("message").asText());
    }

    // ==================== ADMINISTRATOR RESET AND INVITATION ====================

    @Test
    void adminReset_onlySendsALink_andRedeemingItIsAnAdminReset() throws Exception {
        JsonNode session = login(patient, OLD_PASSWORD);
        publishInTransaction(new AdminPasswordResetRequestedEvent(patient.getSystemId(), admin.getSystemId()));

        StoredEmail mail = latestEmail(jdbc, PasswordResetTokenService.TEMPLATE_RESET, patient.getEmail()).orElseThrow();
        assertEquals(admin.getSystemId(), jdbc.queryForObject(
                "SELECT requested_by_system_id FROM password_reset_tokens WHERE system_id = ?", String.class,
                patient.getSystemId()));
        assertEquals(List.of("ADMIN_PASSWORD_RESET ADM-00001->PAT-00001 SUCCESS ACCOUNT"), auditRows(jdbc));
        // Nothing else changed: same password, same sessions, no forced change.
        User stored = userRepository.findBySystemId(patient.getSystemId()).orElseThrow();
        assertTrue(passwordEncoder.matches(OLD_PASSWORD, stored.getPasswordHash()));
        assertFalse(stored.getMustChangePassword());
        assertEquals(200, api.refresh(session.path("tokens").path("refreshToken").asText()).getResponse().getStatus());
        assertTrue(events.changed.isEmpty());

        assertEquals(200, reset(mail.token(), NEW_PASSWORD).getResponse().getStatus());
        assertEquals(List.of(new PasswordChangedEvent(patient.getSystemId(), PasswordChangedEvent.ADMIN_RESET)),
                events.changed);
        assertEquals(200, api.login(patient.getEmail(), NEW_PASSWORD).getResponse().getStatus());
    }

    @Test
    void invitation_ofAnAccountWithoutAPassword_setsTheFirstPassword() throws Exception {
        User dependent = createAndSaveUser(30, "PAT-00030", "dependent@example.com", "PATIENT");
        dependent.setPasswordHash(null);
        userRepository.saveAndFlush(dependent);

        // Forgot-password sends nothing to an account without a password ...
        forgot(dependent.getEmail());
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM outbound_messages"));

        // ... the caregiver's invitation does, with the invitation template and the same link format.
        publishInTransaction(new AdminPasswordResetRequestedEvent(dependent.getSystemId(), caregiver.getSystemId()));
        StoredEmail invite = latestEmail(jdbc, PasswordResetTokenService.TEMPLATE_INVITE, dependent.getEmail()).orElseThrow();
        assertEquals("Set up your CareNexus sign-in", invite.subject());
        assertTrue(invite.dedupeKey().startsWith("PASSWORD_INVITE:"));
        assertTrue(invite.body().lines()
                .anyMatch(("http://localhost:8081/auth/reset-password?token=" + invite.token())::equals));
        assertTrue(emails(jdbc, PasswordResetTokenService.TEMPLATE_RESET).isEmpty());
        assertEquals(List.of("ADMIN_PASSWORD_RESET CGV-00001->PAT-00030 SUCCESS ACCOUNT"), auditRows(jdbc));
        assertNull(userRepository.findBySystemId(dependent.getSystemId()).orElseThrow().getPasswordHash());

        assertEquals(200, reset(invite.token(), NEW_PASSWORD).getResponse().getStatus());
        assertEquals(200, api.login(dependent.getEmail(), NEW_PASSWORD).getResponse().getStatus());
        assertEquals(List.of(new PasswordChangedEvent(dependent.getSystemId(), PasswordChangedEvent.ADMIN_RESET)),
                events.changed);
    }

    @Test
    void adminReset_refusesUnknownClosedAndEmailLessTargets_andRollsThePublisherBack() {
        User closed = createAndSaveUser(31, "PAT-00031", "closed31@example.com", "PATIENT");
        closed.setClosedAt(LocalDateTime.now());
        userRepository.saveAndFlush(closed);
        User noEmail = createAndSaveUser(32, "PAT-00032", "noemail32@example.com", "PATIENT");
        noEmail.setEmail(null);
        userRepository.saveAndFlush(noEmail);

        RuntimeException unknown = assertThrows(RuntimeException.class, () ->
                publishInTransaction(new AdminPasswordResetRequestedEvent("PAT-09999", admin.getSystemId())));
        assertInstanceOf(ResourceNotFoundException.class, unknown);
        assertEquals("User not found", unknown.getMessage());

        RuntimeException closedError = assertThrows(RuntimeException.class, () ->
                publishInTransaction(new AdminPasswordResetRequestedEvent(closed.getSystemId(), admin.getSystemId())));
        assertInstanceOf(ConflictException.class, closedError);
        assertEquals("This account is closed", closedError.getMessage());

        // The publisher's own change in the same transaction is rolled back with the refusal.
        RuntimeException noEmailError = assertThrows(RuntimeException.class, () ->
                new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    jdbc.update("UPDATE users SET first_name = 'Changed' WHERE system_id = ?", noEmail.getSystemId());
                    eventPublisher.publishEvent(
                            new AdminPasswordResetRequestedEvent(noEmail.getSystemId(), admin.getSystemId()));
                }));
        assertInstanceOf(ConflictException.class, noEmailError);
        assertEquals("This account has no email address", noEmailError.getMessage());
        assertEquals("PATIENT", jdbc.queryForObject("SELECT first_name FROM users WHERE system_id = ?", String.class,
                noEmail.getSystemId()));

        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM password_reset_tokens"));
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM outbound_messages"));
        assertTrue(auditRows(jdbc).isEmpty());
    }

    // ==================== HELPERS ====================

    private MvcResult forgotRaw(String email) throws Exception {
        return api.send(post(FORGOT), body("email", email), null);
    }

    private void forgot(String email) throws Exception {
        MvcResult result = forgotRaw(email);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    /** Asks for a link for {@code user} and returns the raw token from the stored email. */
    private String requestLink(User user) throws Exception {
        forgot(user.getEmail());
        return latestEmail(jdbc, PasswordResetTokenService.TEMPLATE_RESET, user.getEmail()).orElseThrow().token();
    }

    private MvcResult reset(String token, String newPassword) throws Exception {
        return api.send(post(RESET), body("token", token, "newPassword", newPassword), null);
    }

    private void assertTokenInvalid(MvcResult result) throws Exception {
        assertEquals(400, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("PASSWORD_RESET_TOKEN_INVALID", json(result).path("code").asText());
        assertEquals("This link is invalid or has expired. Please request a new one.", message(result));
    }

    private JsonNode login(User user, String password) throws Exception {
        MvcResult result = api.login(user.getEmail(), password);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return api.json(result);
    }

    private void publishInTransaction(Object event) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> eventPublisher.publishEvent(event));
    }

    private void resetBuckets(String email, List<String> ips, String floodIp) {
        rateLimitGuard.reset("auth-forgot-email", email);
        rateLimitGuard.reset("auth-forgot-ip", floodIp);
        for (String ip : ips) {
            rateLimitGuard.reset("auth-forgot-ip", ip);
        }
    }
}
