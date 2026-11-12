package com.carenexus.api.account.email;

import com.carenexus.api.account.email.config.AccountEmailProperties;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.auth.support.AuthApi;
import com.carenexus.api.common.event.UserRegisteredEvent;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.ratelimit.RateLimitProperties;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;

import static com.carenexus.api.account.email.AccountEmailTestSupport.TEMPLATE_VERIFY;
import static com.carenexus.api.account.email.AccountEmailTestSupport.auditRows;
import static com.carenexus.api.account.email.AccountEmailTestSupport.count;
import static com.carenexus.api.account.email.AccountEmailTestSupport.countMessages;
import static com.carenexus.api.account.email.AccountEmailTestSupport.newestBody;
import static com.carenexus.api.account.email.AccountEmailTestSupport.unverify;
import static com.carenexus.api.account.email.AccountEmailTestSupport.verificationToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Email verification: the registration listener, {@code POST /api/auth/email/verify}, {@code POST
 * /api/auth/email/resend}, the login gate and the {@code emailVerified} session flag.
 */
class EmailVerificationIntegrationTest extends CommittedIntegrationTest {

    private static final String VERIFY = "/api/auth/email/verify";
    private static final String RESEND = "/api/auth/email/resend";
    private static final String TOKEN_INVALID_MESSAGE =
            "This verification link is invalid or has expired. Request a new one.";
    private static final String RESEND_MESSAGE =
            "If that address belongs to an account that still needs verification, we have sent a new link.";

    @Autowired private AccountEmailProperties properties;
    @Autowired private RateLimitProperties rateLimitProperties;
    @Autowired private RateLimitGuard rateLimitGuard;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private AuthService authService;

    private AuthApi api;
    private boolean requireVerifiedBefore;

    @BeforeEach
    void setUp() {
        api = new AuthApi(mockMvc, objectMapper);
        requireVerifiedBefore = properties.isRequireVerifiedEmail();
    }

    @AfterEach
    void restore() {
        properties.setRequireVerifiedEmail(requireVerifiedBefore);
        rateLimitProperties.setEnabled(false);
    }

    // ==================== REGISTRATION ====================

    @Test
    void selfRegistration_queuesOneVerificationLink_andLeavesTheEmailUnverified() throws Exception {
        MvcResult result = api.register(AuthApi.registration("PATIENT", "  New.Patient@Example.com "));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        String systemId = json(result).path("user").path("system_id").asText();

        User created = userRepository.findBySystemId(systemId).orElseThrow();
        assertNull(created.getEmailVerifiedAt());
        assertEquals(1, countMessages(jdbc, TEMPLATE_VERIFY, "new.patient@example.com"));
        String body = newestBody(jdbc, TEMPLATE_VERIFY, "new.patient@example.com");
        assertTrue(body.contains("Hello Tendai,"));
        assertTrue(body.contains("\nhttp://localhost:8081/auth/verify-email?token="), "the link stands on its own line");
        assertTrue(body.contains("24 hours"));
        String token = verificationToken(jdbc, "new.patient@example.com");
        assertEquals(43, token.length());

        // Only the hash is stored, with a 24 hour lifetime.
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM email_verification_tokens WHERE system_id = ? "
                + "AND used_at IS NULL AND token_hash <> ? AND length(token_hash) = 64 "
                + "AND expires_at BETWEEN now() + interval '23 hours 59 minutes' AND now() + interval '24 hours 1 minute'",
                systemId, token));
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM outbound_messages WHERE dedupe_key LIKE 'EMAIL_VERIFY:%' "
                + "AND recipient_system_id = ?", systemId));
    }

    @Test
    void selfRegisteredDoctorAndCaregiver_getALinkAsWell() throws Exception {
        assertEquals(201, api.register(AuthApi.registration("DOCTOR", "new.doctor@example.com")).getResponse().getStatus());
        assertEquals(201, api.register(AuthApi.registration("PROXY", "new.proxy@example.com")).getResponse().getStatus());
        assertEquals(1, countMessages(jdbc, TEMPLATE_VERIFY, "new.doctor@example.com"));
        assertEquals(1, countMessages(jdbc, TEMPLATE_VERIFY, "new.proxy@example.com"));
    }

    @Test
    void listener_issuesALinkForAPublishedSelfRegistration_only() {
        User unverified = createAndSaveUser(20, "PAT-00020", "listener@example.com", "PATIENT");
        unverify(userRepository, unverified);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // Created by somebody else: nothing.
        tx.executeWithoutResult(status -> eventPublisher.publishEvent(new UserRegisteredEvent("PAT-00020", "PATIENT",
                "listener@example.com", null, null, null, null, "SYSTEM")));
        assertEquals(0, countMessages(jdbc, TEMPLATE_VERIFY, "listener@example.com"));

        // No email: nothing.
        tx.executeWithoutResult(status -> eventPublisher.publishEvent(new UserRegisteredEvent("PAT-00020", "PATIENT",
                " ", null, null, null, null, null)));
        assertEquals(0, countMessages(jdbc, TEMPLATE_VERIFY, "listener@example.com"));

        // Self-registration: one link.
        tx.executeWithoutResult(status -> eventPublisher.publishEvent(new UserRegisteredEvent("PAT-00020", "PATIENT",
                "listener@example.com", null, null, "127.0.0.1", "JUnit", null)));
        assertEquals(1, countMessages(jdbc, TEMPLATE_VERIFY, "listener@example.com"));
    }

    @Test
    void listener_runsInThePublishersTransaction_soARollbackSendsNothing() {
        User unverified = createAndSaveUser(21, "PAT-00021", "rollback@example.com", "PATIENT");
        unverify(userRepository, unverified);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new UserRegisteredEvent("PAT-00021", "PATIENT", "rollback@example.com",
                    null, null, null, null, null));
            status.setRollbackOnly();
        });
        assertEquals(0, countMessages(jdbc, TEMPLATE_VERIFY, "rollback@example.com"));
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM email_verification_tokens"));
    }

    @Test
    void accountsCreatedForSomebodyElse_areVerifiedAndGetNoMail() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        User created = tx.execute(status -> authService.createActiveUserAccount("Ruth", "Dube",
                "ruth.dependent@example.com", "Str0ng-Passw0rd", null, "PATIENT"));
        assertNotNull(created);
        assertNotNull(userRepository.findBySystemId(created.getSystemId()).orElseThrow().getEmailVerifiedAt());
        assertEquals(0, countMessages(jdbc, TEMPLATE_VERIFY, "ruth.dependent@example.com"));
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM email_verification_tokens"));
    }

    // ==================== VERIFY ====================

    @Test
    void verify_stampsTheAccount_auditsOnce_andTheTokenWorksOnlyOnce() throws Exception {
        String systemId = registerPatient("verify.me@example.com");
        String token = verificationToken(jdbc, "verify.me@example.com");

        MvcResult first = postPublic(VERIFY, Map.of("token", "  " + token + " "));
        assertEquals(200, first.getResponse().getStatus(), first.getResponse().getContentAsString());
        assertEquals("{\"message\":\"Your email address has been verified.\"}", first.getResponse().getContentAsString());
        assertNotNull(userRepository.findBySystemId(systemId).orElseThrow().getEmailVerifiedAt());
        assertEquals(1, auditRows(jdbc, "EMAIL_VERIFIED", systemId));
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM audit_log WHERE action = 'EMAIL_VERIFIED' "
                + "AND system_id = ? AND resource_type = 'ACCOUNT' AND status = 'SUCCESS'", systemId));
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM email_verification_tokens WHERE used_at IS NULL"));

        MvcResult second = postPublic(VERIFY, Map.of("token", token));
        assertTokenInvalid(second);
        assertEquals(1, auditRows(jdbc, "EMAIL_VERIFIED", systemId));
    }

    @Test
    void verify_refusesExpiredUnknownAndSupersededTokens() throws Exception {
        registerPatient("expiry@example.com");
        String expired = verificationToken(jdbc, "expiry@example.com");
        jdbc.update("UPDATE email_verification_tokens SET expires_at = now() - interval '1 minute'");
        assertTokenInvalid(postPublic(VERIFY, Map.of("token", expired)));

        assertTokenInvalid(postPublic(VERIFY, Map.of("token", "pV3s0c8mJm3bXq1yT9d2kQ7uE5wZr4nA6hLgC0fYxSI")));

        // A resend supersedes the earlier link; only the newest works.
        MvcResult resend = postPublic(RESEND, Map.of("email", "expiry@example.com"));
        assertEquals(200, resend.getResponse().getStatus());
        String older = verificationToken(jdbc, "expiry@example.com");
        assertEquals(200, postPublic(RESEND, Map.of("email", "EXPIRY@example.com")).getResponse().getStatus());
        String newest = verificationToken(jdbc, "expiry@example.com");
        assertNotEquals(older, newest);
        assertTokenInvalid(postPublic(VERIFY, Map.of("token", older)));
        assertEquals(200, postPublic(VERIFY, Map.of("token", newest)).getResponse().getStatus());
    }

    @Test
    void verify_refusesALinkForAPreviousAddress_andAClosedAccount() throws Exception {
        String systemId = registerPatient("old.address@example.com");
        String token = verificationToken(jdbc, "old.address@example.com");
        User user = userRepository.findBySystemId(systemId).orElseThrow();
        user.setEmail("other.address@example.com");
        userRepository.saveAndFlush(user);
        assertTokenInvalid(postPublic(VERIFY, Map.of("token", token)));

        String closedId = registerPatient("closed@example.com");
        String closedToken = verificationToken(jdbc, "closed@example.com");
        User closed = userRepository.findBySystemId(closedId).orElseThrow();
        closed.setClosedAt(LocalDateTime.now());
        closed.setIsActive(false);
        userRepository.saveAndFlush(closed);
        assertTokenInvalid(postPublic(VERIFY, Map.of("token", closedToken)));
        assertNull(userRepository.findBySystemId(closedId).orElseThrow().getEmailVerifiedAt());
    }

    @Test
    void verify_worksForAClinicianAwaitingApproval_whoStillCannotSignIn() throws Exception {
        MvcResult registered = api.register(AuthApi.registration("DOCTOR", "pending.doc@example.com"));
        String systemId = json(registered).path("user").path("system_id").asText();
        assertEquals(200, postPublic(VERIFY, Map.of("token", verificationToken(jdbc, "pending.doc@example.com")))
                .getResponse().getStatus());
        assertNotNull(userRepository.findBySystemId(systemId).orElseThrow().getEmailVerifiedAt());

        MvcResult login = api.login("pending.doc@example.com", "Str0ng-Passw0rd");
        assertEquals(403, login.getResponse().getStatus());
        assertEquals("ACCOUNT_PENDING_VERIFICATION", json(login).path("code").asText());
        assertEquals(Doctor.VERIFICATION_PENDING,
                doctorRepository.findBySystemId(systemId).orElseThrow().getVerificationStatus());
    }

    @Test
    void verify_validatesTheToken() throws Exception {
        MvcResult blank = postPublic(VERIFY, Map.of("token", "   "));
        assertEquals(400, blank.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(blank).path("code").asText());
        assertEquals("token: must not be blank", message(blank));
        assertEquals("token", json(blank).path("errors").get(0).path("field").asText());

        MvcResult tooLong = postPublic(VERIFY, Map.of("token", "a".repeat(201)));
        assertEquals(400, tooLong.getResponse().getStatus());
        assertEquals("token: size must be between 0 and 200", message(tooLong));

        MvcResult missing = postPublic(VERIFY, Map.of());
        assertEquals("token: must not be blank", message(missing));

        MvcResult malformed = mockMvc.perform(post(VERIFY).contentType("application/json").content("{not json"))
                .andReturn();
        assertEquals(400, malformed.getResponse().getStatus());
        assertEquals("Malformed request body", message(malformed));
    }

    @Test
    void verify_ignoresAGarbageBearerToken() throws Exception {
        registerPatient("bearer@example.com");
        String token = verificationToken(jdbc, "bearer@example.com");
        MvcResult result = api.send(post(VERIFY), Map.of("token", token), "not-a-jwt");
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    // ==================== RESEND ====================

    @Test
    void resend_answersTheSameForEveryAddress_andSendsOnlyToUnverifiedOpenAccounts() throws Exception {
        String unverifiedId = registerPatient("needs.link@example.com");
        String closedId = registerPatient("closed.link@example.com");
        User closed = userRepository.findBySystemId(closedId).orElseThrow();
        closed.setClosedAt(LocalDateTime.now());
        closed.setIsActive(false);
        userRepository.saveAndFlush(closed);
        int queuedBefore = count(jdbc, "SELECT COUNT(*) FROM outbound_messages");
        int tokensBefore = count(jdbc, "SELECT COUNT(*) FROM email_verification_tokens");

        for (String address : new String[]{"nobody@example.com", "thomas@example.com", "closed.link@example.com"}) {
            MvcResult result = postPublic(RESEND, Map.of("email", address));
            assertEquals(200, result.getResponse().getStatus());
            assertEquals("{\"message\":\"" + RESEND_MESSAGE + "\"}", result.getResponse().getContentAsString());
        }
        assertEquals(queuedBefore, count(jdbc, "SELECT COUNT(*) FROM outbound_messages"), "nothing queued");
        assertEquals(tokensBefore, count(jdbc, "SELECT COUNT(*) FROM email_verification_tokens"), "nothing written");

        MvcResult result = postPublic(RESEND, Map.of("email", " Needs.Link@Example.com "));
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(RESEND_MESSAGE, message(result));
        assertEquals(2, countMessages(jdbc, TEMPLATE_VERIFY, "needs.link@example.com"));
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM email_verification_tokens WHERE system_id = ? "
                + "AND used_at IS NULL", unverifiedId), "only the newest link is outstanding");
    }

    @Test
    void resend_validatesTheAddress() throws Exception {
        MvcResult blank = postPublic(RESEND, Map.of("email", "  "));
        assertEquals(400, blank.getResponse().getStatus());
        assertEquals("email: must not be blank", message(blank));
        assertEquals(1, json(blank).path("errors").size(), "a blank value reports only must not be blank");

        MvcResult malformed = postPublic(RESEND, Map.of("email", "not-an-email"));
        assertEquals("email: must be a valid email address", message(malformed));
        assertEquals("VALIDATION_FAILED", json(malformed).path("code").asText());

        MvcResult tooLong = postPublic(RESEND, Map.of("email", "a".repeat(140) + "@example.com"));
        assertEquals("email: size must be between 0 and 150", message(tooLong));
    }

    @Test
    void resend_isLimitedPerClientAddress_andInvalidRequestsDoNotCount() throws Exception {
        String ip = "10.77.0." + (int) (Math.random() * 200 + 1);
        rateLimitGuard.reset("auth-verify-ip", ip);
        rateLimitProperties.setEnabled(true);
        try {
            AuthApi fromIp = api.from(ip);
            for (int i = 0; i < 5; i++) {
                assertEquals(400, fromIp.send(post(RESEND), Map.of("email", "bad"), null).getResponse().getStatus());
            }
            for (int i = 0; i < 20; i++) {
                assertEquals(200, fromIp.send(post(RESEND), Map.of("email", "nobody" + i + "@example.com"), null)
                        .getResponse().getStatus());
            }
            MvcResult limited = fromIp.send(post(RESEND), Map.of("email", "nobody@example.com"), null);
            assertEquals(429, limited.getResponse().getStatus());
            assertEquals("RATE_LIMITED", json(limited).path("code").asText());
            assertNotNull(limited.getResponse().getHeader("Retry-After"));

            // Another address is not affected.
            assertEquals(200, api.from(ip + "1").send(post(RESEND), Map.of("email", "nobody@example.com"), null)
                    .getResponse().getStatus());
        } finally {
            rateLimitProperties.setEnabled(false);
            rateLimitGuard.reset("auth-verify-ip", ip);
            rateLimitGuard.reset("auth-verify-ip", ip + "1");
        }
    }

    // ==================== LOGIN GATE AND FLAG ====================

    @Test
    void loginGate_refusesUnverifiedAccountsOfEveryRole_whenRequired() throws Exception {
        properties.setRequireVerifiedEmail(true);
        for (User user : new User[]{patient, doctor, caregiver, admin}) {
            unverify(userRepository, user);
            MvcResult refused = api.login(user.getEmail(), AuthApi.FIXTURE_PASSWORD);
            assertEquals(403, refused.getResponse().getStatus(), user.getRole());
            assertEquals("EMAIL_NOT_VERIFIED", json(refused).path("code").asText());
            assertEquals("Please verify your email address before signing in.", message(refused));
            assertTrue(json(refused).path("tokens").isMissingNode());
        }
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM refresh_tokens"));
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM outbound_messages"), "the refusal sends no mail");

        // A wrong password is still the generic 401, never a hint that the account exists.
        assertEquals(401, api.login(patient.getEmail(), "wrong-password-1").getResponse().getStatus());

        // A verified account signs in.
        MvcResult verified = api.login(otherPatient.getEmail(), AuthApi.FIXTURE_PASSWORD);
        assertEquals(200, verified.getResponse().getStatus());
        assertTrue(json(verified).path("flags").path("emailVerified").asBoolean());
    }

    @Test
    void loginGate_letsEveryoneThroughWhenNotRequired_andTheFlagTellsTheTruth() throws Exception {
        properties.setRequireVerifiedEmail(false);
        String systemId = registerPatient("flag.user@example.com");

        MvcResult login = api.login("flag.user@example.com", "Str0ng-Passw0rd");
        assertEquals(200, login.getResponse().getStatus(), login.getResponse().getContentAsString());
        JsonNode flags = json(login).path("flags");
        assertTrue(flags.has("emailVerified"));
        assertFalse(flags.path("emailVerified").asBoolean());

        assertEquals(200, postPublic(VERIFY, Map.of("token", verificationToken(jdbc, "flag.user@example.com")))
                .getResponse().getStatus());

        JsonNode refreshed = json(api.refresh(json(login).path("tokens").path("refreshToken").asText()));
        assertTrue(refreshed.path("flags").path("emailVerified").asBoolean(), "recomputed on refresh");
        JsonNode me = json(api.me(refreshed.path("tokens").path("accessToken").asText()));
        assertTrue(me.path("flags").path("emailVerified").asBoolean());
        assertEquals(systemId, me.path("system_id").asText());
    }

    @Test
    void loginGate_letsAVerifiedAccountInOnceTheLinkWasUsed() throws Exception {
        properties.setRequireVerifiedEmail(true);
        registerPatient("gate.user@example.com");
        assertEquals(403, api.login("gate.user@example.com", "Str0ng-Passw0rd").getResponse().getStatus());

        assertEquals(200, postPublic(VERIFY, Map.of("token", verificationToken(jdbc, "gate.user@example.com")))
                .getResponse().getStatus());

        MvcResult login = api.login("gate.user@example.com", "Str0ng-Passw0rd");
        assertEquals(200, login.getResponse().getStatus(), login.getResponse().getContentAsString());
        assertTrue(json(login).path("flags").path("emailVerified").asBoolean());
    }

    // ==================== HELPERS ====================

    private String registerPatient(String email) throws Exception {
        MvcResult result = api.register(AuthApi.registration("PATIENT", email));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result).path("user").path("system_id").asText();
    }

    private MvcResult postPublic(String url, Object body) throws Exception {
        return api.send(post(url), body, null);
    }

    private void assertTokenInvalid(MvcResult result) throws Exception {
        assertEquals(400, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("TOKEN_INVALID", json(result).path("code").asText());
        assertEquals(TOKEN_INVALID_MESSAGE, message(result));
    }
}
