package com.carenexus.api.auth.service;

import com.carenexus.api.auth.dto.response.AuthResponse;
import com.carenexus.api.auth.support.AuthApi;
import com.carenexus.api.auth.support.AuthTestHooks;
import com.carenexus.api.common.event.UserLoggedInEvent;
import com.carenexus.api.common.event.UserLoggedOutEvent;
import com.carenexus.api.common.exception.ApiException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.spi.LoginContext;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The extension points of the sign-in, exercised with fake implementations ({@link AuthTestHooks}) the way the
 * account modules will use them: {@code LoginGate} (refuse, challenge, order), {@code completeLogin} after a
 * challenge, {@code SessionFlagContributor} and the session events.
 */
@Import(AuthTestHooks.class)
class AuthLoginGateIntegrationTest extends CommittedIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private AuthTestHooks.FirstGate firstGate;
    @Autowired private AuthTestHooks.SecondGate secondGate;
    @Autowired private AuthTestHooks.TestFlags testFlags;
    @Autowired private AuthTestHooks.FailingFlags failingFlags;
    @Autowired private AuthTestHooks.EventRecorder recorder;

    private AuthApi api;

    @BeforeEach
    void setUp() {
        AuthTestHooks.reset(firstGate, secondGate, testFlags, failingFlags, recorder);
        api = new AuthApi(mockMvc, objectMapper);
    }

    @Test
    void gatesRunInOrder_onlyAfterThePasswordAndTheAccountStateWereChecked() throws Exception {
        Map<String, Object> request = AuthApi.credentials("thomas@example.com", AuthApi.FIXTURE_PASSWORD);
        request.put("deviceType", "Web");
        request.put("deviceInfo", "Firefox on Linux");
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("User-Agent", "gate-test-agent")
                .content(objectMapper.writeValueAsString(request))).andReturn();

        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertTrue(json(result).path("tokens").isObject());
        assertEquals(List.of("first:PAT-00001"), firstGate.calls);
        assertEquals(List.of("second:PAT-00001"), secondGate.calls);
        LoginContext seen = firstGate.contexts.get(0);
        assertEquals("127.0.0.1", seen.ip());
        assertEquals("gate-test-agent", seen.userAgent());
        assertEquals("Web", seen.deviceType());
        assertEquals("Firefox on Linux", seen.deviceInfo());

        // The session event carries the same device description.
        assertEquals(1, recorder.loggedIn.size());
        assertEquals(new UserLoggedInEvent("PAT-00001", "PATIENT", "127.0.0.1", "Web", "Firefox on Linux"),
                recorder.loggedIn.get(0));

        // A wrong password, an unknown email and a deactivated account never reach a gate.
        api.login("thomas@example.com", "wrong-password1");
        api.login("nobody@example.com", "wrong-password1");
        jdbc.update("UPDATE users SET is_active = FALSE WHERE system_id = ?", otherPatient.getSystemId());
        assertEquals(400, api.login(otherPatient.getEmail(), AuthApi.FIXTURE_PASSWORD).getResponse().getStatus());
        assertEquals(1, firstGate.calls.size());
        assertEquals(1, secondGate.calls.size());
        assertEquals(1, recorder.loggedIn.size());
    }

    @Test
    void challenge_answersWithoutTokens_andCompleteLoginIssuesTheSession() throws Exception {
        firstGate.challengeEmail = "thomas@example.com";

        MvcResult result = api.login("thomas@example.com", AuthApi.FIXTURE_PASSWORD);

        // HTTP 200 with the challenge and nothing else: no session exists yet.
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        assertEquals("MFA", body.path("challenge").path("type").asText());
        assertEquals(AuthTestHooks.CHALLENGE_TOKEN, body.path("challenge").path("token").asText());
        assertEquals(300, body.path("challenge").path("expiresInSeconds").asLong());
        for (String member : List.of("message", "tokens", "user", "flags")) {
            assertTrue(body.has(member) && body.get(member).isNull(), member + " must be null on a challenge");
        }
        assertEquals(0, count("SELECT COUNT(*) FROM refresh_tokens"));
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE action = 'LOGIN' AND status = 'SUCCESS'"));
        assertTrue(recorder.loggedIn.isEmpty());
        assertEquals(List.of("first:PAT-00001"), firstGate.calls);
        assertTrue(secondGate.calls.isEmpty(), "the first challenge ends the login");

        // The endpoint that verifies the challenge finishes the login. No gate runs again.
        AuthResponse session = authService.completeLogin("PAT-00001",
                new LoginContext("10.40.0.1", "challenge-agent", "Web", "Firefox on Linux"));

        assertNull(session.getChallenge());
        assertNull(session.getMessage());
        assertNotNull(session.getTokens().getAccessToken());
        assertEquals(43, session.getTokens().getRefreshToken().length());
        assertEquals(36, session.getTokens().getSessionId().length());
        assertEquals(Long.valueOf(900), session.getTokens().getExpiresIn());
        assertEquals("PAT-00001", session.getUser().getSystem_id());
        assertEquals("patient", session.getUser().getRole());
        assertNotNull(session.getFlags());
        assertEquals(1, firstGate.calls.size());
        assertTrue(secondGate.calls.isEmpty());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM refresh_tokens WHERE system_id = 'PAT-00001'");
        assertEquals(session.getTokens().getSessionId(), row.get("session_id").toString());
        assertEquals("Web", row.get("device_type"));
        assertEquals("Firefox on Linux", row.get("device_info"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'LOGIN' AND status = 'SUCCESS' "
                + "AND system_id = 'PAT-00001' AND ip_address = '10.40.0.1'"));
        assertEquals(List.of(new UserLoggedInEvent("PAT-00001", "PATIENT", "10.40.0.1", "Web", "Firefox on Linux")),
                recorder.loggedIn);

        // The tokens are a normal session.
        assertEquals(200, api.me(session.getTokens().getAccessToken()).getResponse().getStatus());
        MvcResult refreshed = api.refresh(session.getTokens().getRefreshToken());
        assertEquals(200, refreshed.getResponse().getStatus());
        assertEquals(session.getTokens().getSessionId(), json(refreshed).path("tokens").path("sessionId").asText());
    }

    @Test
    void completeLogin_appliesTheAccountStateRefusalsAgain() {
        // Between the challenge and its verification the account may have changed.
        jdbc.update("UPDATE users SET is_active = FALSE WHERE system_id = 'PAT-00001'");
        assertEquals("Account is inactive", assertThrows(BadRequestException.class,
                () -> authService.completeLogin("PAT-00001", null)).getMessage());

        jdbc.update("UPDATE users SET closed_at = now() WHERE system_id = 'PAT-00001'");
        ApiException closed = assertThrows(ApiException.class, () -> authService.completeLogin("PAT-00001", null));
        assertEquals(HttpStatus.FORBIDDEN, closed.getStatus());
        assertEquals("ACCOUNT_CLOSED", closed.getCode());

        jdbc.update("UPDATE doctors SET verification_status = 'PENDING' WHERE system_id = 'DOC-00001'");
        ApiException pending = assertThrows(ApiException.class, () -> authService.completeLogin("DOC-00001", null));
        assertEquals("ACCOUNT_PENDING_VERIFICATION", pending.getCode());

        jdbc.update("UPDATE doctors SET verification_status = 'REJECTED' WHERE system_id = 'DOC-00001'");
        ApiException rejected = assertThrows(ApiException.class, () -> authService.completeLogin("DOC-00001", null));
        assertEquals("ACCOUNT_REJECTED", rejected.getCode());

        assertThrows(InvalidCredentialsException.class, () -> authService.completeLogin("PAT-99999", null));
        assertThrows(InvalidCredentialsException.class, () -> authService.completeLogin(null, null));

        assertEquals(0, count("SELECT COUNT(*) FROM refresh_tokens"));
        assertTrue(recorder.loggedIn.isEmpty());

        // Without request context a session is still issued for a usable account.
        AuthResponse session = authService.completeLogin("CGV-00001", null);
        assertEquals("proxy", session.getUser().getRole());
        assertEquals(1, count("SELECT COUNT(*) FROM refresh_tokens WHERE system_id = 'CGV-00001' "
                + "AND device_type IS NULL AND device_info IS NULL"));
    }

    @Test
    void gateRefusal_isAnsweredWithItsStatusAndCode_andNoSessionIsIssued() throws Exception {
        firstGate.refuseEmail = "thomas@example.com";

        MvcResult result = api.login("thomas@example.com", AuthApi.FIXTURE_PASSWORD);

        assertEquals(403, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("EMAIL_NOT_VERIFIED", json(result).path("code").asText());
        assertEquals("Verify your email address to continue", message(result));
        assertEquals(0, count("SELECT COUNT(*) FROM refresh_tokens"));
        assertTrue(secondGate.calls.isEmpty());
        assertTrue(recorder.loggedIn.isEmpty());

        // Other accounts are not affected by that refusal.
        assertEquals(200, api.login("mary@example.com", AuthApi.FIXTURE_PASSWORD).getResponse().getStatus());
    }

    @Test
    void flags_areFilledByTheContributors_onLoginRefreshAndCurrentUser_andAFailingContributorIsSkipped() throws Exception {
        // The test contributors are off: their keys are absent (the production contributors' keys may be present).
        JsonNode plain = json(api.login("thomas@example.com", AuthApi.FIXTURE_PASSWORD));
        assertTrue(plain.get("flags").isObject());
        assertFalse(plain.get("flags").has("testFlag"));

        testFlags.enabled = true;
        failingFlags.enabled = true;

        JsonNode login = json(api.login("thomas@example.com", AuthApi.FIXTURE_PASSWORD));
        assertTrue(login.get("flags").path("testFlag").asBoolean());
        assertFalse(login.get("flags").has("brokenFlag"), "the failing contributor's key is left out");
        assertFalse(login.path("user").has("flags"));

        JsonNode refreshed = json(api.refresh(login.path("tokens").path("refreshToken").asText()));
        assertTrue(refreshed.get("flags").path("testFlag").asBoolean(), "flags are recomputed on refresh");
        assertFalse(refreshed.get("flags").has("brokenFlag"));

        JsonNode me = json(api.me(refreshed.path("tokens").path("accessToken").asText()));
        assertTrue(me.get("flags").path("testFlag").asBoolean());
        assertFalse(me.get("flags").has("brokenFlag"));
        assertEquals("PAT-00001", me.path("system_id").asText());

        // Recomputed, not stored with the session.
        testFlags.enabled = false;
        assertFalse(json(api.me(refreshed.path("tokens").path("accessToken").asText())).get("flags").has("testFlag"));
    }

    @Test
    void signOutEvents_arePublishedWhenTheUserLeaves_notWhenOneOfSeveralSessionsEnds() throws Exception {
        JsonNode first = json(api.login("thomas@example.com", AuthApi.FIXTURE_PASSWORD));
        JsonNode second = json(api.login("thomas@example.com", AuthApi.FIXTURE_PASSWORD));
        JsonNode third = json(api.login("thomas@example.com", AuthApi.FIXTURE_PASSWORD));

        // Ending one session, or all but one: the user is still signed in elsewhere, so nobody is told to leave.
        assertTrue(authService.revokeSession("PAT-00001", third.path("tokens").path("sessionId").asText()));
        assertEquals(1, authService.revokeOtherSessions("PAT-00001", first.path("tokens").path("sessionId").asText()));
        assertTrue(recorder.loggedOut.isEmpty());
        assertEquals(INVALID_TOKEN, message(api.refresh(second.path("tokens").path("refreshToken").asText())));

        // Sign-out with the refresh token, revoke-all and "revoke others" without a session to keep do publish it.
        api.logoutWithToken(first.path("tokens").path("refreshToken").asText());
        assertEquals(List.of(new UserLoggedOutEvent("PAT-00001")), recorder.loggedOut);

        authService.revokeAllSessions("PAT-00001");
        assertEquals(2, recorder.loggedOut.size());

        assertEquals(0, authService.revokeOtherSessions("PAT-00001", null));
        assertEquals(3, recorder.loggedOut.size());
    }

    private static final String INVALID_TOKEN = "Invalid refresh token";

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
