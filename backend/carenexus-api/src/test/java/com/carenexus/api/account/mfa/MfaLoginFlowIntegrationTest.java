package com.carenexus.api.account.mfa;

import com.carenexus.api.account.mfa.config.MfaProperties;
import com.carenexus.api.account.mfa.crypto.MfaTokens;
import com.carenexus.api.account.mfa.service.MfaErrors;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.ratelimit.RateLimitProperties;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The sign-in side of MFA against committed transactions: login challenge, verify with TOTP and recovery codes,
 * replay, expiry, the attempt cap, forced enrolment of a required role and the shared rate limit.
 */
class MfaLoginFlowIntegrationTest extends CommittedIntegrationTest {

    private static final String PASSWORD = "password123";

    @Autowired
    private MfaProperties mfaProperties;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private RateLimitGuard rateLimitGuard;

    private String savedRequiredRoles;

    @org.junit.jupiter.api.BeforeEach
    void rememberSettings() {
        savedRequiredRoles = mfaProperties.getMfaRequiredRoles();
    }

    @AfterEach
    void restoreSettings() {
        mfaProperties.setMfaRequiredRoles(savedRequiredRoles);
        rateLimitProperties.setEnabled(false);
        for (User user : List.of(patient, doctor, admin)) {
            rateLimitGuard.reset("auth-mfa", user.getSystemId());
        }
    }

    // ==================== HELPERS ====================

    private record Enrolment(String secret, List<String> recoveryCodes) {
    }

    /** Self-service enrolment with a token minted for the user; afterwards the next code may be used at once. */
    private Enrolment enrol(User user) throws Exception {
        MvcResult setup = doPost("/api/users/me/mfa/setup", user, null);
        assertEquals(200, setup.getResponse().getStatus(), setup.getResponse().getContentAsString());
        String secret = json(setup).path("secret").asText();
        MvcResult enabled = doPost("/api/users/me/mfa/enable", user, Map.of("code", MfaFixtures.currentCode(secret)));
        assertEquals(200, enabled.getResponse().getStatus(), enabled.getResponse().getContentAsString());
        List<String> codes = new ArrayList<>();
        json(enabled).path("recoveryCodes").forEach(code -> codes.add(code.asText()));
        MfaFixtures.forgetLastUsedStep(jdbc, user.getSystemId());
        return new Enrolment(secret, codes);
    }

    private MvcResult postPublic(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                        .header("User-Agent", "MfaTest/1.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode login(User user) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", user.getEmail());
        body.put("password", PASSWORD);
        body.put("deviceType", "Web");
        body.put("deviceInfo", "Chrome on macOS");
        MvcResult result = postPublic("/api/auth/login", body);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private String challengeToken(User user, String expectedType) throws Exception {
        JsonNode response = login(user);
        assertEquals(expectedType, response.path("challenge").path("type").asText(), response.toString());
        return response.path("challenge").path("token").asText();
    }

    private MvcResult verifyCode(String token, String code) throws Exception {
        return postPublic("/api/auth/mfa/verify", Map.of("challengeToken", token, "code", code));
    }

    private MvcResult verifyRecovery(String token, String recoveryCode) throws Exception {
        return postPublic("/api/auth/mfa/verify", Map.of("challengeToken", token, "recoveryCode", recoveryCode));
    }

    private int refreshTokens(User user) {
        return MfaFixtures.count(jdbc, "SELECT COUNT(*) FROM refresh_tokens WHERE system_id = ?", user.getSystemId());
    }

    private int attempts(String token) {
        return MfaFixtures.count(jdbc, "SELECT attempts FROM mfa_challenges WHERE challenge_hash = ?",
                MfaTokens.sha256Hex(token));
    }

    private void assertError(MvcResult result, int status, String code, String message) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        if (code != null) {
            assertEquals(code, body.path("code").asText(), body.toString());
        }
        assertEquals(message, body.path("message").asText());
    }

    // ==================== MFA ON ====================

    @Test
    void login_withMfaOn_returnsAChallengeAndNoTokens_thenVerifyIssuesTheSession() throws Exception {
        Enrolment enrolment = enrol(doctor);

        JsonNode response = login(doctor);
        JsonNode challenge = response.path("challenge");
        assertEquals("MFA", challenge.path("type").asText());
        assertEquals(300, challenge.path("expiresInSeconds").asLong());
        String token = challenge.path("token").asText();
        assertTrue(token.matches("^[A-Za-z0-9_-]{43}$"), token);
        assertTrue(response.path("tokens").isNull());
        assertTrue(response.path("user").isNull());
        assertTrue(response.path("flags").isNull());
        assertEquals(0, refreshTokens(doctor), "no session before the second step");

        // Only the hash is stored, with the device of the login request.
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM mfa_challenges WHERE system_id = ?",
                doctor.getSystemId());
        assertEquals(MfaTokens.sha256Hex(token), row.get("challenge_hash"));
        assertEquals("LOGIN", row.get("purpose"));
        assertEquals("Web", row.get("device_type"));
        assertEquals("Chrome on macOS", row.get("device_info"));

        MvcResult verified = verifyCode(token, MfaFixtures.currentCode(enrolment.secret()));
        assertEquals(200, verified.getResponse().getStatus(), verified.getResponse().getContentAsString());
        JsonNode session = json(verified);
        assertTrue(session.path("challenge").isNull());
        assertFalse(session.path("tokens").path("accessToken").asText().isEmpty());
        assertFalse(session.path("tokens").path("sessionId").asText().isEmpty());
        assertEquals(doctor.getSystemId(), session.path("user").path("system_id").asText());
        assertTrue(session.path("flags").path("mfaEnabled").asBoolean());

        assertEquals(1, refreshTokens(doctor));
        assertEquals("Web", jdbc.queryForObject("SELECT device_type FROM refresh_tokens WHERE system_id = ?",
                String.class, doctor.getSystemId()), "device copied from the login request");
        assertEquals(0, MfaFixtures.count(jdbc, "SELECT COUNT(*) FROM mfa_challenges WHERE system_id = ?",
                doctor.getSystemId()), "a used challenge is deleted");
        assertEquals(1, MfaFixtures.auditRows(jdbc, "MFA_SUCCESS", doctor.getSystemId(), "SUCCESS"));

        // The session works and carries the flag.
        MvcResult me = mockMvc.perform(get("/api/users/me").header("Authorization",
                "Bearer " + session.path("tokens").path("accessToken").asText())).andReturn();
        assertEquals(200, me.getResponse().getStatus());
        assertTrue(json(me).path("flags").path("mfaEnabled").asBoolean());

        // The challenge cannot be used twice.
        MfaFixtures.forgetLastUsedStep(jdbc, doctor.getSystemId());
        assertError(verifyCode(token, MfaFixtures.currentCode(enrolment.secret())), 401,
                MfaErrors.CHALLENGE_INVALID, MfaErrors.M3_CHALLENGE_EXPIRED);
    }

    @Test
    void login_withoutMfa_isNotChallenged_andTheFlagIsFalse() throws Exception {
        JsonNode response = login(patient);
        assertTrue(response.path("challenge").isNull());
        assertFalse(response.path("tokens").path("accessToken").asText().isEmpty());
        assertTrue(response.path("flags").has("mfaEnabled"));
        assertFalse(response.path("flags").path("mfaEnabled").asBoolean());
        assertEquals(0, MfaFixtures.count(jdbc, "SELECT COUNT(*) FROM mfa_challenges"));
    }

    @Test
    void verify_rejectsAReplayedCode() throws Exception {
        Enrolment enrolment = enrol(patient);
        String code = MfaFixtures.currentCode(enrolment.secret());

        assertEquals(200, verifyCode(challengeToken(patient, "MFA"), code).getResponse().getStatus());

        String second = challengeToken(patient, "MFA");
        assertError(verifyCode(second, code), 400, MfaErrors.CODE_INVALID, MfaErrors.M1_TOTP_INVALID);
        assertEquals(1, attempts(second), "the failed attempt is committed");
        assertEquals(1, refreshTokens(patient));
    }

    @Test
    void verify_afterTheChallengeExpired_is401() throws Exception {
        Enrolment enrolment = enrol(patient);
        String token = challengeToken(patient, "MFA");
        MfaFixtures.expireChallenges(jdbc, patient.getSystemId());

        assertError(verifyCode(token, MfaFixtures.currentCode(enrolment.secret())), 401,
                MfaErrors.CHALLENGE_INVALID, MfaErrors.M3_CHALLENGE_EXPIRED);
        assertEquals(0, refreshTokens(patient));
    }

    @Test
    void verify_fiveWrongCodesKillTheChallenge() throws Exception {
        Enrolment enrolment = enrol(patient);
        String token = challengeToken(patient, "MFA");
        String wrong = MfaFixtures.wrongCode(enrolment.secret());

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertError(verifyCode(token, wrong), 400, MfaErrors.CODE_INVALID, MfaErrors.M1_TOTP_INVALID);
            assertEquals(attempt, attempts(token));
        }
        assertError(verifyCode(token, wrong), 401, MfaErrors.CHALLENGE_INVALID, MfaErrors.M4_CHALLENGE_LOCKED);
        assertEquals(5, attempts(token));

        // Even the right code is refused now.
        assertError(verifyCode(token, MfaFixtures.currentCode(enrolment.secret())), 401,
                MfaErrors.CHALLENGE_INVALID, MfaErrors.M3_CHALLENGE_EXPIRED);
        assertEquals(5, attempts(token));
        assertEquals(0, refreshTokens(patient));
        assertEquals(5, MfaFixtures.auditRows(jdbc, "MFA_FAILURE", patient.getSystemId(), "FAILURE"));

        // A new sign-in gets a new challenge.
        String next = challengeToken(patient, "MFA");
        assertEquals(200, verifyCode(next, MfaFixtures.currentCode(enrolment.secret())).getResponse().getStatus());
    }

    @Test
    void verify_withARecoveryCode_worksOnce() throws Exception {
        Enrolment enrolment = enrol(caregiver);
        String recovery = enrolment.recoveryCodes().get(0);
        // The server ignores case and the hyphen.
        String typed = recovery.replace("-", "").toLowerCase();

        MvcResult first = verifyRecovery(challengeToken(caregiver, "MFA"), typed);
        assertEquals(200, first.getResponse().getStatus(), first.getResponse().getContentAsString());
        assertNotNull(json(first).path("tokens").path("accessToken").asText());

        JsonNode status = json(doGet("/api/users/me/mfa", caregiver));
        assertEquals(9, status.path("recoveryCodesRemaining").asInt());

        assertError(verifyRecovery(challengeToken(caregiver, "MFA"), recovery), 400, MfaErrors.CODE_INVALID,
                MfaErrors.M2_RECOVERY_INVALID);
        assertEquals(1, refreshTokens(caregiver));
    }

    @Test
    void verify_validatesTheBody() throws Exception {
        Enrolment enrolment = enrol(patient);
        String token = challengeToken(patient, "MFA");
        String code = MfaFixtures.currentCode(enrolment.secret());

        Map<String, Object> both = new LinkedHashMap<>();
        both.put("challengeToken", token);
        both.put("code", code);
        both.put("recoveryCode", enrolment.recoveryCodes().get(0));
        assertError(postPublic("/api/auth/mfa/verify", both), 400, "VALIDATION_FAILED", MfaErrors.M10_CODE_OR_RECOVERY);
        assertError(postPublic("/api/auth/mfa/verify", Map.of("challengeToken", token)), 400, "VALIDATION_FAILED",
                MfaErrors.M10_CODE_OR_RECOVERY);
        assertError(verifyCode(token, "12345"), 400, "VALIDATION_FAILED", "code: must be 6 digits");
        assertError(verifyRecovery(token, "ABC"), 400, "VALIDATION_FAILED",
                "recoveryCode: must be a recovery code such as ABCDE-FGHJK");
        assertError(postPublic("/api/auth/mfa/verify", Map.of("challengeToken", " ", "code", code)), 400,
                "VALIDATION_FAILED", "challengeToken: is required");
        assertEquals(0, attempts(token), "malformed requests are not attempts");

        assertError(verifyCode(MfaTokens.newChallengeToken(), code), 401, MfaErrors.CHALLENGE_INVALID,
                MfaErrors.M3_CHALLENGE_EXPIRED);
    }

    @Test
    void verify_forAnAccountDeactivatedMeanwhile_is401() throws Exception {
        Enrolment enrolment = enrol(patient);
        String token = challengeToken(patient, "MFA");
        jdbc.update("UPDATE users SET is_active = false WHERE system_id = ?", patient.getSystemId());

        assertError(verifyCode(token, MfaFixtures.currentCode(enrolment.secret())), 401,
                MfaErrors.CHALLENGE_INVALID, MfaErrors.M3_CHALLENGE_EXPIRED);
        assertEquals(0, refreshTokens(patient));
    }

    @Test
    void disable_deletesLiveChallenges() throws Exception {
        Enrolment enrolment = enrol(patient);
        String token = challengeToken(patient, "MFA");
        Map<String, Object> body = Map.of("password", PASSWORD, "recoveryCode", enrolment.recoveryCodes().get(1));
        assertEquals(200, doPost("/api/users/me/mfa/disable", patient, body).getResponse().getStatus());

        assertError(verifyCode(token, MfaFixtures.currentCode(enrolment.secret())), 401,
                MfaErrors.CHALLENGE_INVALID, MfaErrors.M3_CHALLENGE_EXPIRED);
        assertTrue(login(patient).path("challenge").isNull(), "password only again");
    }

    // ==================== FORCED ENROLMENT ====================

    @Test
    void requiredRole_withoutMfa_getsMfaSetup_andNoTokens_thenEnrolsAndSignsIn() throws Exception {
        mfaProperties.setMfaRequiredRoles("ADMIN");

        JsonNode response = login(admin);
        assertEquals("MFA_SETUP", response.path("challenge").path("type").asText());
        assertTrue(response.path("tokens").isNull());
        assertEquals(0, refreshTokens(admin));
        String token = response.path("challenge").path("token").asText();

        // A role outside the list is not affected.
        assertTrue(login(patient).path("challenge").isNull());

        // The MFA_SETUP token is not a verify token.
        assertError(verifyCode(token, "123456"), 401, MfaErrors.CHALLENGE_INVALID, MfaErrors.M3_CHALLENGE_EXPIRED);
        // confirm before start
        assertError(postPublic("/api/auth/mfa/setup/confirm", Map.of("challengeToken", token, "code", "123456")),
                409, null, MfaErrors.M7_SETUP_NOT_STARTED);

        MvcResult start = postPublic("/api/auth/mfa/setup/start", Map.of("challengeToken", token));
        assertEquals(200, start.getResponse().getStatus(), start.getResponse().getContentAsString());
        String secret = json(start).path("secret").asText();
        assertTrue(secret.matches("^[A-Z2-7]{32}$"));
        assertEquals("otpauth://totp/CareNexus:admin%40example.com?secret=" + secret
                + "&issuer=CareNexus&algorithm=SHA1&digits=6&period=30", json(start).path("otpauthUri").asText());
        assertEquals(secret, json(postPublic("/api/auth/mfa/setup/start", Map.of("challengeToken", token)))
                .path("secret").asText(), "the same secret on a repeated call");
        assertFalse(jdbc.queryForObject("SELECT pending_secret_encrypted FROM mfa_challenges WHERE system_id = ?",
                String.class, admin.getSystemId()).contains(secret), "stored encrypted");

        assertError(postPublic("/api/auth/mfa/setup/confirm",
                        Map.of("challengeToken", token, "code", MfaFixtures.wrongCode(secret))),
                400, MfaErrors.CODE_INVALID, MfaErrors.M1_TOTP_INVALID);
        assertEquals(1, attempts(token));

        MvcResult confirmed = postPublic("/api/auth/mfa/setup/confirm",
                Map.of("challengeToken", token, "code", MfaFixtures.currentCode(secret)));
        assertEquals(200, confirmed.getResponse().getStatus(), confirmed.getResponse().getContentAsString());
        JsonNode body = json(confirmed);
        assertEquals(10, body.path("recoveryCodes").size());
        body.path("recoveryCodes").forEach(c -> assertTrue(c.asText().matches(MfaFixtures.RECOVERY_CODE_REGEX)));
        JsonNode session = body.path("session");
        assertFalse(session.path("tokens").path("accessToken").asText().isEmpty());
        assertEquals("admin", session.path("user").path("role").asText());
        assertTrue(session.path("flags").path("mfaEnabled").asBoolean());
        assertTrue(session.path("challenge").isNull());
        assertEquals(1, refreshTokens(admin));
        assertEquals(1, MfaFixtures.auditRows(jdbc, "MFA_ENABLED", admin.getSystemId(), "SUCCESS"));
        assertEquals(1, MfaFixtures.auditRows(jdbc, "MFA_FAILURE", admin.getSystemId(), "FAILURE"));

        MvcResult status = mockMvc.perform(get("/api/users/me/mfa").header("Authorization",
                "Bearer " + session.path("tokens").path("accessToken").asText())).andReturn();
        JsonNode statusBody = json(status);
        assertTrue(statusBody.path("enabled").asBoolean());
        assertTrue(statusBody.path("required").asBoolean());
        assertEquals(10, statusBody.path("recoveryCodesRemaining").asInt());
        assertTrue(statusBody.path("enabledAt").asText().endsWith("Z"), statusBody.toString());

        // The setup challenge is used up, and the next sign-in asks for a code.
        assertError(postPublic("/api/auth/mfa/setup/start", Map.of("challengeToken", token)), 401,
                MfaErrors.CHALLENGE_INVALID, MfaErrors.M3_CHALLENGE_EXPIRED);
        assertEquals("MFA", login(admin).path("challenge").path("type").asText());
    }

    @Test
    void setupChallenge_isVoidOnceTheAccountEnrolledAnotherWay() throws Exception {
        mfaProperties.setMfaRequiredRoles("ADMIN");
        String token = challengeToken(admin, "MFA_SETUP");
        assertEquals(200, postPublic("/api/auth/mfa/setup/start", Map.of("challengeToken", token))
                .getResponse().getStatus());

        enrol(admin);

        assertError(postPublic("/api/auth/mfa/setup/start", Map.of("challengeToken", token)), 401,
                MfaErrors.CHALLENGE_INVALID, MfaErrors.M3_CHALLENGE_EXPIRED);
        assertError(postPublic("/api/auth/mfa/setup/confirm", Map.of("challengeToken", token, "code", "123456")),
                401, MfaErrors.CHALLENGE_INVALID, MfaErrors.M3_CHALLENGE_EXPIRED);
    }

    @Test
    void setupChallenge_expires() throws Exception {
        mfaProperties.setMfaRequiredRoles("ADMIN");
        String token = challengeToken(admin, "MFA_SETUP");
        String secret = json(postPublic("/api/auth/mfa/setup/start", Map.of("challengeToken", token)))
                .path("secret").asText();
        MfaFixtures.expireChallenges(jdbc, admin.getSystemId());

        assertError(postPublic("/api/auth/mfa/setup/confirm",
                        Map.of("challengeToken", token, "code", MfaFixtures.currentCode(secret))),
                401, MfaErrors.CHALLENGE_INVALID, MfaErrors.M3_CHALLENGE_EXPIRED);
        assertFalse(json(doGet("/api/users/me/mfa", admin)).path("enabled").asBoolean());

        // A new sign-in produces a new secret.
        String next = challengeToken(admin, "MFA_SETUP");
        String nextSecret = json(postPublic("/api/auth/mfa/setup/start", Map.of("challengeToken", next)))
                .path("secret").asText();
        assertNotEquals(secret, nextSecret);
    }

    // ==================== RATE LIMIT ====================

    @Test
    void theAuthMfaBucket_isSharedByAllChallengesOfTheAccount() throws Exception {
        Enrolment enrolment = enrol(patient);
        String first = challengeToken(patient, "MFA");
        String second = challengeToken(patient, "MFA");
        String wrong = MfaFixtures.wrongCode(enrolment.secret());

        rateLimitProperties.setEnabled(true);
        rateLimitGuard.reset("auth-mfa", patient.getSystemId());
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertEquals(400, verifyCode(first, wrong).getResponse().getStatus());
        }
        assertEquals(401, verifyCode(first, wrong).getResponse().getStatus(), "fifth wrong code locks the challenge");

        MvcResult limited = verifyCode(second, MfaFixtures.currentCode(enrolment.secret()));
        assertEquals(429, limited.getResponse().getStatus(), limited.getResponse().getContentAsString());
        assertEquals("RATE_LIMITED", json(limited).path("code").asText());
        assertNotNull(limited.getResponse().getHeader("Retry-After"));
        assertEquals(0, attempts(second), "a refused call is not an attempt of the challenge");
        assertEquals(0, refreshTokens(patient));
    }
}
