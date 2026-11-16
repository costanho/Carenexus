package com.carenexus.api.auth.controller;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.auth.support.AuthApi;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.ratelimit.RateLimitProperties;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Sessions end to end: sign-in, refresh-token rotation and reuse detection, sign-out, revocation by session id,
 * deactivation during a session, account-state refusals and the sign-in rate limits. Committed transactions, real
 * schema.
 */
class AuthSessionIntegrationTest extends CommittedIntegrationTest {

    private static final String REUSE = "Refresh token reuse detected. Please sign in again.";
    private static final String INVALID = "Invalid refresh token";

    @Autowired private AuthService authService;
    @Autowired private RateLimitProperties rateLimitProperties;
    @Autowired private RateLimitGuard rateLimitGuard;

    private AuthApi api;

    /** What a client keeps of a session. */
    private record Session(String accessToken, String refreshToken, String sessionId) {
    }

    @BeforeEach
    void setUp() {
        api = new AuthApi(mockMvc, objectMapper);
    }

    // ==================== SIGN-IN ====================

    @Test
    void login_issuesASession_withSessionIdFlagsDeviceAndAudit() throws Exception {
        Map<String, Object> request = AuthApi.credentials("  Thomas@Example.com ", AuthApi.FIXTURE_PASSWORD);
        request.put("deviceType", "Web");
        request.put("deviceInfo", "Chrome on macOS");
        request.put("role", "patient");   // sent by the login screens, must be ignored

        MvcResult result = api.login(request);

        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        assertTrue(body.get("message").isNull());
        assertTrue(body.get("challenge").isNull());
        assertTrue(body.get("flags").isObject(), "flags is always an object on a session");
        assertTrue(body.get("flags").path("mustChangePassword").isBoolean(), "the production contributors fill the flags");

        JsonNode tokens = body.path("tokens");
        assertEquals(3, tokens.path("accessToken").asText().split("\\.").length, "a JWT");
        assertEquals(43, tokens.path("refreshToken").asText().length());
        assertEquals(900, tokens.path("expiresIn").asLong());
        String sessionId = tokens.path("sessionId").asText();
        assertEquals(36, sessionId.length());
        assertEquals(sessionId, UUID.fromString(sessionId).toString(), "a lower-case UUID");

        JsonNode user = body.path("user");
        assertEquals(patient.getUserId().intValue(), user.path("user_id").asInt());
        assertEquals("PAT-00001", user.path("system_id").asText());
        assertEquals("thomas@example.com", user.path("email").asText());
        assertEquals("patient", user.path("role").asText());
        assertFalse(user.has("flags"), "the user object never carries flags");

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM refresh_tokens WHERE system_id = 'PAT-00001'");
        assertEquals(sessionId, row.get("session_id").toString());
        assertEquals("Web", row.get("device_type"));
        assertEquals("Chrome on macOS", row.get("device_info"));
        assertEquals(Boolean.FALSE, row.get("is_revoked"));
        assertNotEquals(tokens.path("refreshToken").asText(), row.get("token_hash"), "only the hash is stored");

        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'LOGIN' AND status = 'SUCCESS' "
                + "AND system_id = 'PAT-00001'"));
    }

    @Test
    void login_validatesTheBody() throws Exception {
        MvcResult noPassword = api.login(AuthApi.credentials("thomas@example.com", ""));
        assertEquals(400, noPassword.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(noPassword).path("code").asText());
        assertEquals("password: Password is required", message(noPassword));

        MvcResult noEmail = api.login(AuthApi.credentials("   ", AuthApi.FIXTURE_PASSWORD));
        assertEquals(400, noEmail.getResponse().getStatus());
        assertEquals("email: Email is required", message(noEmail));

        MvcResult nothing = api.login(Map.of());
        assertEquals(400, nothing.getResponse().getStatus());
        assertEquals("email: Email is required", message(nothing));
        assertEquals(2, json(nothing).path("errors").size());

        Map<String, Object> longDevice = AuthApi.credentials("thomas@example.com", AuthApi.FIXTURE_PASSWORD);
        longDevice.put("deviceInfo", "x".repeat(256));
        MvcResult tooLong = api.login(longDevice);
        assertEquals(400, tooLong.getResponse().getStatus());
        assertEquals("deviceInfo: must be at most 255 characters", message(tooLong));

        assertEquals(0, count("SELECT COUNT(*) FROM refresh_tokens"));
    }

    @Test
    void wrongPasswordAndUnknownEmail_getTheSame401_andAreAudited() throws Exception {
        MvcResult wrong = api.login("thomas@example.com", "wrong-password1");
        MvcResult unknown = api.login("nobody@example.com", "wrong-password1");

        assertEquals(401, wrong.getResponse().getStatus());
        assertEquals(401, unknown.getResponse().getStatus());
        assertEquals("Invalid email or password", message(wrong));
        assertEquals(message(wrong), message(unknown));
        assertFalse(json(wrong).has("code"));

        // The reason is recorded, never the email.
        assertEquals(2, count("SELECT COUNT(*) FROM audit_log WHERE action = 'LOGIN' AND status = 'FAILURE'"));
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE error_message LIKE '%example.com%'"));
    }

    // ==================== ACCOUNT STATE ====================

    @Test
    void accountStates_areRefusedWithTheirCode_onlyAfterThePasswordWasVerified() throws Exception {
        // 1. Closed (first match, even for a clinician that is also pending).
        jdbc.update("UPDATE users SET closed_at = now(), is_active = FALSE WHERE system_id = ?", otherPatient.getSystemId());
        MvcResult closed = api.login(otherPatient.getEmail(), AuthApi.FIXTURE_PASSWORD);
        assertEquals(403, closed.getResponse().getStatus(), closed.getResponse().getContentAsString());
        assertEquals("ACCOUNT_CLOSED", json(closed).path("code").asText());
        assertEquals("This account has been closed. Please contact support.", message(closed));
        // A wrong password never reveals the state.
        MvcResult closedWrongPassword = api.login(otherPatient.getEmail(), "wrong-password1");
        assertEquals(401, closedWrongPassword.getResponse().getStatus());
        assertEquals("Invalid email or password", message(closedWrongPassword));

        // 2. Clinician pending, whatever is_active says.
        jdbc.update("UPDATE doctors SET verification_status = 'PENDING' WHERE system_id = ?", otherDoctor.getSystemId());
        MvcResult pending = api.login(otherDoctor.getEmail(), AuthApi.FIXTURE_PASSWORD);
        assertEquals(403, pending.getResponse().getStatus());
        assertEquals("ACCOUNT_PENDING_VERIFICATION", json(pending).path("code").asText());
        assertTrue(message(pending).contains("pending verification"));

        // 3. Clinician rejected.
        jdbc.update("UPDATE doctors SET verification_status = 'REJECTED', is_active = FALSE WHERE system_id = ?",
                otherDoctor.getSystemId());
        jdbc.update("UPDATE users SET is_active = FALSE WHERE system_id = ?", otherDoctor.getSystemId());
        MvcResult rejected = api.login(otherDoctor.getEmail(), AuthApi.FIXTURE_PASSWORD);
        assertEquals(403, rejected.getResponse().getStatus());
        assertEquals("ACCOUNT_REJECTED", json(rejected).path("code").asText());
        assertEquals("Your clinician registration was not approved. Please contact support.", message(rejected));

        // 4. A suspended clinician is reported as inactive: 400 without a code.
        jdbc.update("UPDATE doctors SET verification_status = 'SUSPENDED' WHERE system_id = ?", otherDoctor.getSystemId());
        MvcResult suspended = api.login(otherDoctor.getEmail(), AuthApi.FIXTURE_PASSWORD);
        assertEquals(400, suspended.getResponse().getStatus());
        assertEquals("Account is inactive", message(suspended));
        assertFalse(json(suspended).has("code"));

        // 5. An account deactivated by an administrator.
        jdbc.update("UPDATE users SET is_active = FALSE WHERE system_id = ?", otherCaregiver.getSystemId());
        MvcResult inactive = api.login(otherCaregiver.getEmail(), AuthApi.FIXTURE_PASSWORD);
        assertEquals(400, inactive.getResponse().getStatus());
        assertEquals("Account is inactive", message(inactive));

        // Closed first, even when the clinician is also pending.
        jdbc.update("UPDATE doctors SET verification_status = 'PENDING' WHERE system_id = ?", otherDoctor.getSystemId());
        jdbc.update("UPDATE users SET closed_at = now() WHERE system_id = ?", otherDoctor.getSystemId());
        assertEquals("ACCOUNT_CLOSED", json(api.login(otherDoctor.getEmail(), AuthApi.FIXTURE_PASSWORD)).path("code").asText());

        // An approved clinician signs in.
        assertEquals(200, api.login(doctor.getEmail(), AuthApi.FIXTURE_PASSWORD).getResponse().getStatus());

        assertEquals(0, count("SELECT COUNT(*) FROM refresh_tokens WHERE system_id <> 'DOC-00001'"));
        // Every refusal is a failed login in the audit log (6 state refusals + 1 wrong password).
        assertEquals(7, count("SELECT COUNT(*) FROM audit_log WHERE action = 'LOGIN' AND status = 'FAILURE'"));
    }

    // ==================== REFRESH ====================

    @Test
    void refresh_rotatesTheToken_keepsTheSessionId_andReuseOfTheOldTokenRevokesEverySession() throws Exception {
        Session first = signIn(patient, "Web", "Chrome on macOS");
        Session otherDevice = signIn(patient, "iOS", "iPhone 14");
        assertNotEquals(first.sessionId(), otherDevice.sessionId(), "every sign-in is a session of its own");

        // Rotation: a new refresh token, the same session.
        MvcResult rotatedResult = api.refresh("  " + first.refreshToken() + "  ");   // surrounding whitespace is ignored
        assertEquals(200, rotatedResult.getResponse().getStatus(), rotatedResult.getResponse().getContentAsString());
        JsonNode rotatedBody = json(rotatedResult);
        Session rotated = toSession(rotatedBody);
        assertNotEquals(first.refreshToken(), rotated.refreshToken());
        assertEquals(first.sessionId(), rotated.sessionId());
        assertEquals(900, rotatedBody.path("tokens").path("expiresIn").asLong());
        assertEquals("PAT-00001", rotatedBody.path("user").path("system_id").asText());
        assertTrue(rotatedBody.get("flags").isObject());
        assertTrue(rotatedBody.get("message").isNull());
        assertTrue(rotatedBody.get("challenge").isNull());

        // And again: the session id is stable for the life of the session.
        Session rotatedTwice = toSession(json(api.refresh(rotated.refreshToken())));
        assertEquals(first.sessionId(), rotatedTwice.sessionId());
        assertEquals(3, count("SELECT COUNT(*) FROM refresh_tokens WHERE session_id = '" + first.sessionId() + "'::uuid "
                + "AND device_type = 'Web' AND device_info = 'Chrome on macOS'"), "device fields are copied on rotation");
        assertEquals(1, count("SELECT COUNT(*) FROM refresh_tokens WHERE session_id = '" + first.sessionId() + "'::uuid "
                + "AND is_revoked = FALSE"), "a session has one active token");
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE action IN ('TOKEN_REFRESH', 'TOKEN_REUSE')"),
                "a successful refresh is not audited");
        // The access token of the rotated session works.
        assertEquals(200, api.me(rotatedTwice.accessToken()).getResponse().getStatus());

        // The old token is dead. Presenting it again is reuse: every session of the user ends.
        MvcResult reuse = api.refresh(first.refreshToken());
        assertEquals(401, reuse.getResponse().getStatus());
        assertEquals(REUSE, message(reuse));
        assertFalse(json(reuse).has("code"));
        assertEquals(0, count("SELECT COUNT(*) FROM refresh_tokens WHERE system_id = 'PAT-00001' AND is_revoked = FALSE"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'TOKEN_REUSE' AND status = 'FAILURE' "
                + "AND resource_type = 'ACCOUNT' AND target_system_id = 'PAT-00001' AND system_id IS NULL"));

        // The newest token of the family and the other device are both signed out.
        assertEquals(401, api.refresh(rotatedTwice.refreshToken()).getResponse().getStatus());
        assertEquals(401, api.refresh(otherDevice.refreshToken()).getResponse().getStatus());
    }

    @Test
    void expiredRefreshToken_is401_andTheTokenIsRevoked() throws Exception {
        Session session = signIn(patient);
        jdbc.update("UPDATE refresh_tokens SET expires_at = now() - interval '1 minute' WHERE system_id = 'PAT-00001'");

        MvcResult result = api.refresh(session.refreshToken());

        assertEquals(401, result.getResponse().getStatus());
        assertEquals("Refresh token has expired", message(result));
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT is_revoked FROM refresh_tokens WHERE system_id = 'PAT-00001'", Boolean.class));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'TOKEN_REFRESH' AND status = 'FAILURE' "
                + "AND target_system_id = 'PAT-00001'"));
    }

    @Test
    void missingBlankUnknownAndOversizedTokens_areRefused() throws Exception {
        MvcResult noBody = mockMvc.perform(post("/api/auth/refresh")).andReturn();
        assertEquals(401, noBody.getResponse().getStatus());
        assertEquals("Refresh token is required", message(noBody));
        assertEquals("Unauthorized", json(noBody).path("error").asText());
        assertEquals("/api/auth/refresh", json(noBody).path("path").asText());

        MvcResult emptyObject = mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertEquals(401, emptyObject.getResponse().getStatus());
        assertEquals("Refresh token is required", message(emptyObject));

        MvcResult blank = api.refresh("   ");
        assertEquals(401, blank.getResponse().getStatus());
        assertEquals("Refresh token is required", message(blank));

        MvcResult unknown = api.refresh("not-a-token-this-server-issued");
        assertEquals(401, unknown.getResponse().getStatus());
        assertEquals(INVALID, message(unknown));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'TOKEN_REFRESH' AND status = 'FAILURE' "
                + "AND target_system_id IS NULL"));

        MvcResult tooLong = api.refresh("x".repeat(513));
        assertEquals(400, tooLong.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(tooLong).path("code").asText());
        assertEquals("refreshToken: must be at most 512 characters", message(tooLong));
    }

    // ==================== SIGN-OUT ====================

    @Test
    void logoutWithTheRefreshToken_endsThatSessionOnly() throws Exception {
        Session laptop = signIn(patient);
        Session phone = signIn(patient);

        // Works without an access token (it may have expired).
        assertEquals(200, api.logoutWithToken(laptop.refreshToken()).getResponse().getStatus());

        MvcResult ended = api.refresh(laptop.refreshToken());
        assertEquals(401, ended.getResponse().getStatus());
        assertEquals(INVALID, message(ended), "a session that was ended is refused, it is not token theft");
        // The other session was not touched by the sign-out, nor by the ended session's late refresh.
        MvcResult stillThere = api.refresh(phone.refreshToken());
        assertEquals(200, stillThere.getResponse().getStatus(), stillThere.getResponse().getContentAsString());
        assertEquals(phone.sessionId(), toSession(json(stillThere)).sessionId());
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE action = 'TOKEN_REUSE'"));

        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'LOGOUT' AND status = 'SUCCESS' "
                + "AND system_id = 'PAT-00001'"));

        // Unknown, already used and oversized tokens: the first two are ignored, the last is a validation error.
        assertEquals(200, api.logoutWithToken("unknown-token").getResponse().getStatus());
        assertEquals(200, api.logoutWithToken(laptop.refreshToken()).getResponse().getStatus());
        assertEquals(400, api.logoutWithToken("x".repeat(513)).getResponse().getStatus());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'LOGOUT'"));
    }

    @Test
    void logoutWithoutAToken_endsEverySessionOfTheCaller() throws Exception {
        Session laptop = signIn(patient);
        Session phone = signIn(patient);
        Session someoneElse = signIn(otherPatient);

        // The web sends {} when it has no stored refresh token.
        MvcResult result = api.logoutEverywhere(laptop.accessToken());

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("", result.getResponse().getContentAsString());
        assertEquals(0, count("SELECT COUNT(*) FROM refresh_tokens WHERE system_id = 'PAT-00001' AND is_revoked = FALSE"));
        assertEquals(401, api.refresh(laptop.refreshToken()).getResponse().getStatus());
        assertEquals(401, api.refresh(phone.refreshToken()).getResponse().getStatus());
        assertEquals(200, api.refresh(someoneElse.refreshToken()).getResponse().getStatus(), "other users are not affected");
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'LOGOUT' AND system_id = 'PAT-00001'"));

        // Neither a token nor a caller: nothing happens, still 200.
        assertEquals(200, mockMvc.perform(post("/api/auth/logout")).andReturn().getResponse().getStatus());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'LOGOUT'"));
    }

    // ==================== DEACTIVATION ====================

    @Test
    void deactivationDuringASession_is401OnTheNextRequest_andTheRefreshIsRefused() throws Exception {
        Session session = signIn(patient);
        Session secondDevice = signIn(patient);
        assertEquals(200, api.me(session.accessToken()).getResponse().getStatus());

        jdbc.update("UPDATE users SET is_active = FALSE WHERE system_id = 'PAT-00001'");

        // The access token has not expired, but the account is checked on every request.
        MvcResult me = api.me(session.accessToken());
        assertEquals(401, me.getResponse().getStatus());
        assertEquals("Authentication required", message(me));
        assertEquals("/api/users/me", json(me).path("path").asText());

        MvcResult refresh = api.refresh(session.refreshToken());
        assertEquals(401, refresh.getResponse().getStatus());
        assertEquals("User account is inactive", message(refresh));
        assertEquals(0, count("SELECT COUNT(*) FROM refresh_tokens WHERE system_id = 'PAT-00001' AND is_revoked = FALSE"),
                "every session of the account ends");
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'TOKEN_REFRESH' AND status = 'FAILURE' "
                + "AND target_system_id = 'PAT-00001'"));
        assertEquals(401, api.refresh(secondDevice.refreshToken()).getResponse().getStatus());
    }

    @Test
    void closedAccount_cannotRefresh() throws Exception {
        Session session = signIn(caregiver);
        jdbc.update("UPDATE users SET closed_at = now() WHERE system_id = 'CGV-00001'");

        MvcResult refresh = api.refresh(session.refreshToken());

        assertEquals(401, refresh.getResponse().getStatus());
        assertEquals("User account is inactive", message(refresh));
        assertEquals(0, count("SELECT COUNT(*) FROM refresh_tokens WHERE system_id = 'CGV-00001' AND is_revoked = FALSE"));
    }

    // ==================== CURRENT USER ====================

    @Test
    void currentUser_returnsTheUserFieldsAtTheTopLevel_withFlags() throws Exception {
        MvcResult result = api.me(signIn(caregiver).accessToken());

        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        assertEquals(caregiver.getUserId().intValue(), body.path("user_id").asInt());
        assertEquals("CGV-00001", body.path("system_id").asText());
        assertEquals("CAREGIVER User3", body.path("name").asText());
        assertEquals("sarah@example.com", body.path("email").asText());
        assertEquals("proxy", body.path("role").asText(), "CAREGIVER is reported as proxy");
        assertTrue(body.get("flags").isObject());

        assertEquals("admin", json(api.me(signIn(admin).accessToken())).path("role").asText());

        // No token: the error envelope, not a bare string.
        MvcResult anonymous = api.me(null);
        assertEquals(401, anonymous.getResponse().getStatus());
        assertEquals("Authentication required", message(anonymous));
        assertEquals("Unauthorized", json(anonymous).path("error").asText());
    }

    // ==================== REVOCATION BY SESSION ID ====================

    @Test
    void revokeSession_endsExactlyOneSessionOfThatUser() throws Exception {
        Session one = signIn(patient);
        Session two = signIn(patient);
        Session foreign = signIn(otherPatient);

        assertTrue(authService.revokeSession("PAT-00001", two.sessionId()));

        assertEquals(INVALID, message(api.refresh(two.refreshToken())), "ended, and not treated as reuse");
        assertEquals(200, api.refresh(one.refreshToken()).getResponse().getStatus(), "the other session lives on");

        // Nothing to revoke: already ended, another user's session, unknown id, not a UUID, null.
        assertFalse(authService.revokeSession("PAT-00001", two.sessionId()));
        assertFalse(authService.revokeSession("PAT-00001", foreign.sessionId()));
        assertFalse(authService.revokeSession("PAT-00001", UUID.randomUUID().toString()));
        assertFalse(authService.revokeSession("PAT-00001", "not-a-session-id"));
        assertFalse(authService.revokeSession("PAT-00001", null));
        assertEquals(200, api.refresh(foreign.refreshToken()).getResponse().getStatus(), "a foreign session is untouched");

        // No event-driven sign-out and no audit row: the caller audits its own action.
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE action = 'LOGOUT'"));
    }

    @Test
    void revokeOtherSessions_keepsTheNamedSession_evenAfterItWasRotated() throws Exception {
        Session keep = signIn(patient);
        Session second = signIn(patient);
        Session third = signIn(patient);
        Session foreign = signIn(otherPatient);
        // The kept session has been refreshed since sign-in: its id is still the one the client holds.
        Session keepRotated = toSession(json(api.refresh(keep.refreshToken())));

        assertEquals(2, authService.revokeOtherSessions("PAT-00001", keep.sessionId()));

        // The devices that were signed out present their old tokens: plain 401, and the kept session survives it.
        assertEquals(INVALID, message(api.refresh(second.refreshToken())));
        assertEquals(INVALID, message(api.refresh(third.refreshToken())));
        MvcResult kept = api.refresh(keepRotated.refreshToken());
        assertEquals(200, kept.getResponse().getStatus(), kept.getResponse().getContentAsString());
        assertEquals(keep.sessionId(), toSession(json(kept)).sessionId());
        assertEquals(200, api.refresh(foreign.refreshToken()).getResponse().getStatus());
        assertEquals(0, authService.revokeOtherSessions("PAT-00001", keep.sessionId()), "nothing else to end");
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE action IN ('LOGOUT', 'TOKEN_REUSE')"));
    }

    @Test
    void revokeOtherSessions_rejectsAnIdThatIsNotAUuid_andEndsEverythingForAnUnknownOrNullId() throws Exception {
        Session one = signIn(patient);
        signIn(patient);

        BadRequestException invalid = assertThrows(BadRequestException.class,
                () -> authService.revokeOtherSessions("PAT-00001", "not-a-session-id"));
        assertEquals("Invalid session id", invalid.getMessage());
        assertThrows(BadRequestException.class, () -> authService.revokeOtherSessions("PAT-00001", ""));
        assertEquals(2, count("SELECT COUNT(*) FROM refresh_tokens WHERE system_id = 'PAT-00001' AND is_revoked = FALSE"),
                "nothing was revoked");

        // A UUID that matches no active session ends them all.
        assertEquals(2, authService.revokeOtherSessions("PAT-00001", UUID.randomUUID().toString()));
        assertEquals(401, api.refresh(one.refreshToken()).getResponse().getStatus());

        // A null keep id is "revoke all".
        signIn(patient);
        signIn(patient);
        signIn(patient);
        assertEquals(3, authService.revokeOtherSessions("PAT-00001", null));
        assertEquals(0, count("SELECT COUNT(*) FROM refresh_tokens WHERE system_id = 'PAT-00001' AND is_revoked = FALSE"));
    }

    @Test
    void revokeAllSessions_endsEverySession_withoutAnAuditRow() throws Exception {
        Session one = signIn(patient);
        Session two = signIn(patient);
        Session foreign = signIn(otherPatient);

        authService.revokeAllSessions("PAT-00001");

        assertEquals(401, api.refresh(one.refreshToken()).getResponse().getStatus());
        assertEquals(401, api.refresh(two.refreshToken()).getResponse().getStatus());
        assertEquals(200, api.refresh(foreign.refreshToken()).getResponse().getStatus());
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE action = 'LOGOUT'"));
    }

    // ==================== RATE LIMITS ====================

    @Test
    void sixthBadLogin_is429WithRetryAfter_andASuccessfulSignInClearsTheCount() throws Exception {
        AuthApi attacker = api.from("10.30.0.5");
        AuthApi home = api.from("10.30.0.6");
        List<String[]> used = new ArrayList<>();
        rateLimitProperties.setEnabled(true);
        try {
            used.add(new String[]{"auth-login-ip", "10.30.0.5"});
            used.add(new String[]{"auth-login-ip", "10.30.0.6"});
            used.add(new String[]{"auth-login-email-ip", "thomas@example.com|10.30.0.5"});
            used.add(new String[]{"auth-login-email-ip", "thomas@example.com|10.30.0.6"});
            used.add(new String[]{"auth-login-account", "thomas@example.com"});

            for (int i = 1; i <= 5; i++) {
                MvcResult wrong = attacker.login("thomas@example.com", "wrong-password" + i);
                assertEquals(401, wrong.getResponse().getStatus(), "attempt " + i);
            }

            // The sixth request is refused before the password is looked at, even when it is right.
            MvcResult sixth = attacker.login("Thomas@Example.com", AuthApi.FIXTURE_PASSWORD);
            assertEquals(429, sixth.getResponse().getStatus(), sixth.getResponse().getContentAsString());
            assertEquals("RATE_LIMITED", json(sixth).path("code").asText());
            assertEquals("Too many sign-in attempts. Please try again later.", message(sixth));
            long retryAfter = Long.parseLong(sixth.getResponse().getHeader("Retry-After"));
            assertTrue(retryAfter >= 1 && retryAfter <= 900, "Retry-After is the rest of the 15 minute window: " + retryAfter);
            assertEquals(0, count("SELECT COUNT(*) FROM refresh_tokens"));

            // The limit is per email and address: the owner still signs in from home, which clears nothing for
            // the attacker's address.
            assertEquals(200, home.login("thomas@example.com", AuthApi.FIXTURE_PASSWORD).getResponse().getStatus());
            assertEquals(429, attacker.login("thomas@example.com", AuthApi.FIXTURE_PASSWORD).getResponse().getStatus());

            // A successful sign-in clears the count of its own email and address.
            for (int i = 1; i <= 4; i++) {
                assertEquals(401, home.login("thomas@example.com", "wrong-password" + i).getResponse().getStatus());
            }
            assertEquals(200, home.login("thomas@example.com", AuthApi.FIXTURE_PASSWORD).getResponse().getStatus());
            for (int i = 1; i <= 5; i++) {
                assertEquals(401, home.login("thomas@example.com", "wrong-password" + i).getResponse().getStatus(),
                        "after the success, attempt " + i + " starts a new count");
            }
            assertEquals(429, home.login("thomas@example.com", AuthApi.FIXTURE_PASSWORD).getResponse().getStatus());
        } finally {
            rateLimitProperties.setEnabled(false);
            used.forEach(entry -> rateLimitGuard.reset(entry[0], entry[1]));
        }
    }

    @Test
    void accountLimit_locksAnEmailAcrossAddresses_andAuditsItOnlyForARealAccount() throws Exception {
        RateLimitProperties.Bucket small = new RateLimitProperties.Bucket();
        small.setLimit(3);
        small.setWindowSeconds(3600L);
        RateLimitProperties.Bucket previousAuthLoginAccount = rateLimitProperties.getBuckets().put("auth-login-account", small);
        rateLimitProperties.setEnabled(true);
        List<String> ips = List.of("10.31.0.1", "10.31.0.2", "10.31.0.3", "10.31.0.4");
        List<String> emails = List.of("mary@example.com", "ghost@example.com");
        try {
            for (String email : emails) {
                // Three attempts from three addresses, each well inside the per-address limits.
                for (int i = 0; i < 3; i++) {
                    assertEquals(401, api.from(ips.get(i)).login(email, "wrong-password1").getResponse().getStatus());
                }
                // The fourth, from yet another address and with whatever password, is locked out.
                MvcResult locked = api.from(ips.get(3)).login(email, AuthApi.FIXTURE_PASSWORD);
                assertEquals(429, locked.getResponse().getStatus(), email);
                assertEquals("RATE_LIMITED", json(locked).path("code").asText());
                assertEquals("Too many sign-in attempts. Please try again later.", message(locked),
                        "the same answer whether or not the account exists");
                long retryAfter = Long.parseLong(locked.getResponse().getHeader("Retry-After"));
                assertTrue(retryAfter >= 1 && retryAfter <= 3600);
            }

            assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCOUNT_LOCKED' AND status = 'FAILURE' "
                    + "AND resource_type = 'ACCOUNT' AND target_system_id = 'PAT-00002'"));
            assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCOUNT_LOCKED'"),
                    "nothing is recorded for an email without an account");
        } finally {
            rateLimitProperties.setEnabled(false);
            if (previousAuthLoginAccount != null) { rateLimitProperties.getBuckets().put("auth-login-account", previousAuthLoginAccount); } else { rateLimitProperties.getBuckets().remove("auth-login-account"); }
            for (String email : emails) {
                rateLimitGuard.reset("auth-login-account", email);
                ips.forEach(ip -> rateLimitGuard.reset("auth-login-email-ip", email + "|" + ip));
            }
            ips.forEach(ip -> rateLimitGuard.reset("auth-login-ip", ip));
        }
    }

    @Test
    void refreshRequestsFromOneAddress_areLimited() throws Exception {
        RateLimitProperties.Bucket small = new RateLimitProperties.Bucket();
        small.setLimit(2);
        small.setWindowSeconds(900L);
        RateLimitProperties.Bucket previousAuthRefreshIp = rateLimitProperties.getBuckets().put("auth-refresh-ip", small);
        rateLimitProperties.setEnabled(true);
        AuthApi client = api.from("10.32.0.1");
        try {
            Session session = signIn(patient);
            Session rotated = toSession(json(client.refresh(session.refreshToken())));
            assertEquals(401, client.refresh("unknown").getResponse().getStatus(), "counted whatever the outcome");

            MvcResult limited = client.refresh(rotated.refreshToken());
            assertEquals(429, limited.getResponse().getStatus());
            assertEquals("RATE_LIMITED", json(limited).path("code").asText());
            assertTrue(Long.parseLong(limited.getResponse().getHeader("Retry-After")) >= 1);
            // The refused request did not consume the token.
            assertEquals(200, api.from("10.32.0.2").refresh(rotated.refreshToken()).getResponse().getStatus());
        } finally {
            rateLimitProperties.setEnabled(false);
            if (previousAuthRefreshIp != null) { rateLimitProperties.getBuckets().put("auth-refresh-ip", previousAuthRefreshIp); } else { rateLimitProperties.getBuckets().remove("auth-refresh-ip"); }
            rateLimitGuard.reset("auth-refresh-ip", "10.32.0.1");
            rateLimitGuard.reset("auth-refresh-ip", "10.32.0.2");
            rateLimitGuard.reset("auth-login-ip", "127.0.0.1");
            rateLimitGuard.reset("auth-login-email-ip", "thomas@example.com|127.0.0.1");
            rateLimitGuard.reset("auth-login-account", "thomas@example.com");
        }
    }

    // ==================== HELPERS ====================

    private Session signIn(User user) throws Exception {
        return signIn(user, null, null);
    }

    private Session signIn(User user, String deviceType, String deviceInfo) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>(AuthApi.credentials(user.getEmail(), AuthApi.FIXTURE_PASSWORD));
        if (deviceType != null) {
            request.put("deviceType", deviceType);
        }
        if (deviceInfo != null) {
            request.put("deviceInfo", deviceInfo);
        }
        MvcResult result = api.login(request);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        Session session = toSession(json(result));
        assertNull(json(result).get("challenge").textValue());
        return session;
    }

    private static Session toSession(JsonNode body) {
        JsonNode tokens = body.path("tokens");
        assertTrue(tokens.isObject(), "expected a session: " + body);
        return new Session(tokens.path("accessToken").asText(), tokens.path("refreshToken").asText(),
                tokens.path("sessionId").asText());
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
