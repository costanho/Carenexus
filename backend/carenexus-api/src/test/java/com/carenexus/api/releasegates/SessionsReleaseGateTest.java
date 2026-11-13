package com.carenexus.api.releasegates;

import com.carenexus.api.account.sessions.SessionFixtures;
import com.carenexus.api.account.sessions.SessionFixtures.Session;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.support.AuthApi;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Release gate of the session endpoints (be-sessions-retention):
 * GET /api/users/me/sessions, DELETE /api/users/me/sessions/{sessionId}, POST /api/users/me/sessions/revoke-others.
 *
 * Sessions are created through the real login endpoint; requests commit, so audit rows and the refresh behaviour
 * after a revocation are observed as in production.
 */
class SessionsReleaseGateTest extends CommittedIntegrationTest {

    private static final String SESSIONS = "/api/users/me/sessions";
    private static final String REVOKE_OTHERS = SESSIONS + "/revoke-others";

    private SessionFixtures sessions;

    @BeforeEach
    void setUpSessions() {
        sessions = new SessionFixtures(new AuthApi(mockMvc, objectMapper));
    }

    // ==================== GET /api/users/me/sessions ====================

    @Test
    void list_returnsOnlyOwnActiveSessions_currentFirst_thenMostRecentlyUsed() throws Exception {
        Session chrome = sessions.signIn(patient, "Web", "Chrome on macOS");
        Session firefox = sessions.signIn(patient, "Web", "Firefox on Windows");
        Session noDevice = sessions.signIn(patient, null, null);
        Session foreign = sessions.signIn(otherPatient, "Web", "Safari on iOS");

        // The current id is compared case-insensitively and trimmed
        MvcResult result = doGet(SESSIONS + "?current= " + chrome.sessionId().toUpperCase(Locale.ROOT) + " ", patient);

        assertEquals(200, result.getResponse().getStatus());
        JsonNode list = json(result);
        assertTrue(list.isArray());
        assertEquals(3, list.size());
        assertEquals(List.of(chrome.sessionId(), noDevice.sessionId(), firefox.sessionId()), ids(list));
        assertFalse(ids(list).contains(foreign.sessionId()));

        JsonNode current = list.get(0);
        assertTrue(current.path("current").asBoolean());
        assertEquals("Web", current.path("deviceType").asText());
        assertEquals("Chrome on macOS", current.path("deviceInfo").asText());
        assertEquals(chrome.sessionId().toLowerCase(Locale.ROOT), current.path("sessionId").asText());
        assertTrue(current.path("createdAt").asText().endsWith("Z"));
        assertTrue(current.path("lastUsedAt").asText().endsWith("Z"));

        JsonNode unknownDevice = list.get(1);
        assertFalse(unknownDevice.path("current").asBoolean());
        // Nullable members are present as JSON null, never omitted
        assertTrue(unknownDevice.has("deviceType") && unknownDevice.get("deviceType").isNull());
        assertTrue(unknownDevice.has("deviceInfo") && unknownDevice.get("deviceInfo").isNull());
        assertFalse(list.get(2).path("current").asBoolean());
    }

    @Test
    void list_withoutOrWithUnknownCurrent_marksNothing() throws Exception {
        Session mine = sessions.signIn(patient, "Web", "Chrome on macOS");
        Session foreign = sessions.signIn(otherPatient, "Web", "Chrome on macOS");

        for (String query : List.of("", "?current=", "?current=not-a-uuid", "?current=" + UUID.randomUUID(),
                "?current=" + foreign.sessionId())) {
            MvcResult result = doGet(SESSIONS + query, patient);
            assertEquals(200, result.getResponse().getStatus(), query);
            JsonNode list = json(result);
            assertEquals(1, list.size(), query);
            assertEquals(mine.sessionId(), list.get(0).path("sessionId").asText());
            assertFalse(list.get(0).path("current").asBoolean(), query);
        }
    }

    @Test
    void list_leavesOutEndedAndExpiredSessions() throws Exception {
        Session active = sessions.signIn(patient, "Web", "Chrome on macOS");
        Session loggedOut = sessions.signIn(patient, "Web", "Firefox on Windows");
        Session expired = sessions.signIn(patient, "iOS", "iPhone");
        new AuthApi(mockMvc, objectMapper).logoutWithToken(loggedOut.refreshToken());
        jdbc.update("UPDATE refresh_tokens SET expires_at = now() - interval '1 minute' WHERE session_id = ?::uuid",
                expired.sessionId());

        JsonNode list = json(doGet(SESSIONS, patient));

        assertEquals(List.of(active.sessionId()), ids(list));
    }

    @Test
    void list_isEmptyWhenTheCallersOwnSessionsEnded() throws Exception {
        Session only = sessions.signIn(doctor, "Web", "Chrome on macOS");
        new AuthApi(mockMvc, objectMapper).logoutWithToken(only.refreshToken());

        MvcResult result = doGet(SESSIONS + "?current=" + only.sessionId(), doctor);

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(0, json(result).size());
    }

    @Test
    void list_isCappedAtFifty_andKeepsTheCurrentSessionEvenWhenItIsTheOldest() throws Exception {
        Session oldest = sessions.signIn(patient, "Web", "Chrome on macOS");
        // 55 more sessions, inserted directly (signing in 55 times through BCrypt would be slow), all newer
        for (int i = 0; i < 55; i++) {
            jdbc.update("INSERT INTO refresh_tokens (system_id, token_hash, session_id, expires_at, is_revoked, "
                            + "device_type, device_info, created_at) VALUES (?, ?, ?::uuid, now() + interval '7 days', "
                            + "FALSE, 'Web', ?, now() + (? * interval '1 second'))",
                    patient.getSystemId(), "hash-" + UUID.randomUUID(), UUID.randomUUID().toString(), "Device " + i, i + 1);
        }

        JsonNode list = json(doGet(SESSIONS + "?current=" + oldest.sessionId(), patient));

        assertEquals(50, list.size());
        assertEquals(oldest.sessionId(), list.get(0).path("sessionId").asText());
        assertTrue(list.get(0).path("current").asBoolean());
        assertEquals("Device 54", list.get(1).path("deviceInfo").asText());
        Instant previous = Instant.parse(list.get(1).path("lastUsedAt").asText());
        for (int i = 2; i < list.size(); i++) {
            Instant next = Instant.parse(list.get(i).path("lastUsedAt").asText());
            assertFalse(next.isAfter(previous), "ordered by lastUsedAt descending");
            previous = next;
        }
    }

    @Test
    void sessionId_survivesRefresh_andTheListShowsOneSessionWithSignInAndLastUse() throws Exception {
        Session signedIn = sessions.signIn(caregiver, "Android", "Pixel 8");
        Session refreshed = sessions.refresh(signedIn);
        Session refreshedAgain = sessions.refresh(refreshed);

        assertEquals(signedIn.sessionId(), refreshed.sessionId());
        assertEquals(signedIn.sessionId(), refreshedAgain.sessionId());

        JsonNode list = json(doGet(SESSIONS + "?current=" + signedIn.sessionId(), caregiver));
        assertEquals(1, list.size());
        JsonNode entry = list.get(0);
        assertEquals(signedIn.sessionId(), entry.path("sessionId").asText());
        assertTrue(entry.path("current").asBoolean());
        assertEquals("Android", entry.path("deviceType").asText());
        Instant createdAt = Instant.parse(entry.path("createdAt").asText());
        Instant lastUsedAt = Instant.parse(entry.path("lastUsedAt").asText());
        assertTrue(lastUsedAt.isAfter(createdAt), "lastUsedAt is the newest token, createdAt the sign-in");
    }

    @Test
    void list_requiresAuthentication() throws Exception {
        MvcResult result = mockMvc.perform(get(SESSIONS)).andReturn();

        assertEquals(401, result.getResponse().getStatus());
        assertEquals("Authentication required", message(result));
    }

    // ==================== DELETE /api/users/me/sessions/{sessionId} ====================

    @Test
    void delete_ownSession_endsIt_auditsOnce_andDoesNotTouchTheOtherSessions() throws Exception {
        Session here = sessions.signIn(patient, "Web", "Chrome on macOS");
        Session elsewhere = sessions.signIn(patient, "Web", "Firefox on Windows");

        MvcResult result = doDelete(SESSIONS + "/" + elsewhere.sessionId(), patient);

        assertEquals(204, result.getResponse().getStatus());
        assertEquals("", result.getResponse().getContentAsString());
        assertEquals(List.of(here.sessionId()), ids(json(doGet(SESSIONS, patient))));
        assertEquals(1, sessionRevokeAudits(patient));
        // The ended device is refused at its next refresh; that is not reuse, so this session keeps working
        assertEquals(401, sessions.refreshStatus(elsewhere));
        Session stillHere = sessions.refresh(here);
        assertEquals(here.sessionId(), stillHere.sessionId());

        // Repeat: already ended
        MvcResult repeat = doDelete(SESSIONS + "/" + elsewhere.sessionId(), patient);
        assertEquals(404, repeat.getResponse().getStatus());
        assertEquals("Session not found", message(repeat));
        assertEquals(1, sessionRevokeAudits(patient));
    }

    @Test
    void delete_acceptsUpperCaseIds_andTheCallersOwnCurrentSession() throws Exception {
        Session only = sessions.signIn(otherDoctor, "Web", "Edge on Windows");

        MvcResult result = doDelete(SESSIONS + "/" + only.sessionId().toUpperCase(Locale.ROOT), otherDoctor);

        assertEquals(204, result.getResponse().getStatus());
        assertEquals(0, json(doGet(SESSIONS, otherDoctor)).size());
        assertEquals(401, sessions.refreshStatus(only));
    }

    @Test
    void delete_anotherUsersSession_is404ForEveryRole_andTheSessionStaysActive() throws Exception {
        Session patientSession = sessions.signIn(patient, "Web", "Chrome on macOS");
        // A caregiver with full access to the patient has no reach into the patient's sessions either
        grantAccess(caregiver, patient, access -> access.setAccessLevel("FULL_ACCESS"));

        for (User intruder : List.of(doctor, caregiver, otherPatient, otherDoctor, otherCaregiver, admin)) {
            MvcResult result = doDelete(SESSIONS + "/" + patientSession.sessionId(), intruder);
            assertEquals(404, result.getResponse().getStatus(), intruder.getSystemId());
            assertEquals("Session not found", message(result));
            assertEquals(0, sessionRevokeAudits(intruder), intruder.getSystemId());
        }

        assertEquals(List.of(patientSession.sessionId()), ids(json(doGet(SESSIONS, patient))));
        assertEquals(patientSession.sessionId(), sessions.refresh(patientSession).sessionId());
    }

    @Test
    void delete_malformedOrUnknownId_is404() throws Exception {
        sessions.signIn(patient, "Web", "Chrome on macOS");

        for (String id : List.of("not-a-uuid", "12345", UUID.randomUUID().toString())) {
            MvcResult result = doDelete(SESSIONS + "/" + id, patient);
            assertEquals(404, result.getResponse().getStatus(), id);
            assertEquals("Session not found", message(result), id);
        }
        assertEquals(0, sessionRevokeAudits(patient));
        assertEquals(1, json(doGet(SESSIONS, patient)).size());
    }

    @Test
    void delete_expiredSession_is404() throws Exception {
        Session expired = sessions.signIn(patient, "Web", "Chrome on macOS");
        jdbc.update("UPDATE refresh_tokens SET expires_at = now() - interval '1 minute' WHERE session_id = ?::uuid",
                expired.sessionId());

        MvcResult result = doDelete(SESSIONS + "/" + expired.sessionId(), patient);

        assertEquals(404, result.getResponse().getStatus());
        assertEquals(0, sessionRevokeAudits(patient));
    }

    @Test
    void delete_requiresAuthentication() throws Exception {
        MvcResult result = mockMvc.perform(delete(SESSIONS + "/" + UUID.randomUUID())).andReturn();

        assertEquals(401, result.getResponse().getStatus());
    }

    // ==================== POST /api/users/me/sessions/revoke-others ====================

    @Test
    void revokeOthers_endsEveryOtherSession_keepsTheNamedOne_andIsRepeatable() throws Exception {
        Session keep = sessions.signIn(patient, "Web", "Chrome on macOS");
        Session second = sessions.signIn(patient, "Web", "Firefox on Windows");
        Session third = sessions.signIn(patient, null, null);
        Session foreign = sessions.signIn(otherPatient, "Web", "Chrome on macOS");

        // Surrounding white space and upper case are accepted
        MvcResult result = doPost(REVOKE_OTHERS, patient,
                Map.of("keepSessionId", "  " + keep.sessionId().toUpperCase(Locale.ROOT) + " "));

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(2, json(result).path("revokedCount").asInt());
        assertEquals(List.of(keep.sessionId()), ids(json(doGet(SESSIONS, patient))));
        assertEquals(1, sessionRevokeAudits(patient));
        assertEquals(401, sessions.refreshStatus(second));
        assertEquals(401, sessions.refreshStatus(third));
        // The kept session survives the refused refreshes of the ended ones
        assertEquals(keep.sessionId(), sessions.refresh(keep).sessionId());
        // Another user's session is untouched
        assertEquals(List.of(foreign.sessionId()), ids(json(doGet(SESSIONS, otherPatient))));

        MvcResult repeat = doPost(REVOKE_OTHERS, patient, Map.of("keepSessionId", keep.sessionId()));
        assertEquals(200, repeat.getResponse().getStatus());
        assertEquals(0, json(repeat).path("revokedCount").asInt());
        assertEquals(1, sessionRevokeAudits(patient), "no audit row when nothing ended");
    }

    @Test
    void revokeOthers_keepIdThatIsNotAnActiveOwnSession_is404_andNothingIsRevoked() throws Exception {
        Session mine = sessions.signIn(patient, "Web", "Chrome on macOS");
        Session mineToo = sessions.signIn(patient, "Web", "Firefox on Windows");
        Session foreign = sessions.signIn(otherPatient, "Web", "Chrome on macOS");
        Session ended = sessions.signIn(patient, "iOS", "iPhone");
        new AuthApi(mockMvc, objectMapper).logoutWithToken(ended.refreshToken());

        for (String keep : List.of(foreign.sessionId(), UUID.randomUUID().toString(), ended.sessionId())) {
            MvcResult result = doPost(REVOKE_OTHERS, patient, Map.of("keepSessionId", keep));
            assertEquals(404, result.getResponse().getStatus(), keep);
            assertEquals("Session not found", message(result), keep);
        }

        assertEquals(2, json(doGet(SESSIONS, patient)).size());
        assertTrue(ids(json(doGet(SESSIONS, patient))).containsAll(List.of(mine.sessionId(), mineToo.sessionId())));
        assertEquals(1, json(doGet(SESSIONS, otherPatient)).size());
        assertEquals(0, sessionRevokeAudits(patient));
    }

    @Test
    void revokeOthers_worksForEveryRole_onTheirOwnSessionsOnly() throws Exception {
        Session patientSession = sessions.signIn(patient, "Web", "Chrome on macOS");
        for (User user : List.of(doctor, caregiver, admin)) {
            Session keep = sessions.signIn(user, "Web", "Chrome on macOS");
            sessions.signIn(user, "Web", "Firefox on Windows");

            MvcResult result = doPost(REVOKE_OTHERS, user, Map.of("keepSessionId", keep.sessionId()));

            assertEquals(200, result.getResponse().getStatus(), user.getSystemId());
            assertEquals(1, json(result).path("revokedCount").asInt(), user.getSystemId());
            assertEquals(List.of(keep.sessionId()), ids(json(doGet(SESSIONS, user))));
            assertEquals(1, sessionRevokeAudits(user), user.getSystemId());
        }
        assertEquals(List.of(patientSession.sessionId()), ids(json(doGet(SESSIONS, patient))));
    }

    @Test
    void revokeOthers_validation() throws Exception {
        sessions.signIn(patient, "Web", "Chrome on macOS");

        for (Map<String, Object> body : List.<Map<String, Object>>of(Map.of(), Map.of("keepSessionId", ""),
                Map.of("keepSessionId", "   "))) {
            MvcResult result = doPost(REVOKE_OTHERS, patient, body);
            assertEquals(400, result.getResponse().getStatus(), body.toString());
            JsonNode error = json(result);
            assertEquals("VALIDATION_FAILED", error.path("code").asText());
            assertEquals("keepSessionId: Session id is required", error.path("message").asText());
            assertEquals(1, error.path("errors").size(), "a blank value gives only the required error");
            assertEquals("keepSessionId", error.path("errors").get(0).path("field").asText());
        }

        MvcResult invalid = doPost(REVOKE_OTHERS, patient, Map.of("keepSessionId", "not-a-session"));
        assertEquals(400, invalid.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(invalid).path("code").asText());
        assertEquals("keepSessionId: Session id is invalid", message(invalid));

        MvcResult malformed = mockMvc.perform(post(REVOKE_OTHERS)
                        .header("Authorization", tokenFor(patient))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andReturn();
        assertEquals(400, malformed.getResponse().getStatus());
        assertEquals("Malformed request body", message(malformed));

        MvcResult missingBody = mockMvc.perform(post(REVOKE_OTHERS)
                        .header("Authorization", tokenFor(patient))
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        assertEquals(400, missingBody.getResponse().getStatus());
        assertEquals("Malformed request body", message(missingBody));

        assertEquals(1, json(doGet(SESSIONS, patient)).size());
        assertEquals(0, sessionRevokeAudits(patient));
    }

    @Test
    void revokeOthers_requiresAuthentication() throws Exception {
        MvcResult result = mockMvc.perform(post(REVOKE_OTHERS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keepSessionId\":\"" + UUID.randomUUID() + "\"}"))
                .andReturn();

        assertEquals(401, result.getResponse().getStatus());
    }

    // ==================== HELPERS ====================

    private static List<String> ids(JsonNode list) {
        List<String> ids = new ArrayList<>();
        list.forEach(entry -> ids.add(entry.path("sessionId").asText()));
        return ids;
    }

    /** SESSION_REVOKE rows written by {@code user} about their own account. */
    private int sessionRevokeAudits(User user) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'SESSION_REVOKE' "
                        + "AND resource_type = 'ACCOUNT' AND system_id = ? AND target_system_id = ? AND status = 'SUCCESS'",
                Integer.class, user.getSystemId(), user.getSystemId());
        return count == null ? 0 : count;
    }
}
