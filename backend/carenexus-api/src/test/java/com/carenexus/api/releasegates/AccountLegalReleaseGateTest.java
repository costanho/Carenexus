package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.support.AuthApi;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Release gate for the terms of service and privacy policy (be-account-legal), with committed transactions so the
 * registration listener, the session flag and the audit rows run exactly as in production.
 * <ul>
 *   <li>A self-registration that sends a stale version is refused and leaves nothing behind; one that sends no
 *       versions succeeds and must accept at first sign-in; one that sends the current versions is recorded with
 *       the client address and User-Agent.</li>
 *   <li>Publishing a new version makes every account accept again; only the current version can be accepted, and
 *       accepting is idempotent.</li>
 *   <li>Only administrators publish and read other accounts' acceptance records.</li>
 * </ul>
 * Publishing adds rows to the reference table legal_documents: they are removed after every test.
 */
class AccountLegalReleaseGateTest extends CommittedIntegrationTest {

    private static final String BROWSER = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/130.0 Safari/537.36";
    private static final String CLIENT_IP = "196.43.12.7";
    private static final String STALE_REGISTRATION =
            "The terms or privacy policy changed. Review the current version and accept it.";
    private static final String STALE_ACCEPT = "This version is no longer current. Review the latest version and accept it.";
    private static final String FORBIDDEN = "You don't have permission to access this resource";

    private AuthApi api;

    @BeforeEach
    void setUpApi() {
        api = new AuthApi(mockMvc, objectMapper);
    }

    @AfterEach
    void removePublishedVersions() {
        // Only the versions shipped by V29 stay in the reference table.
        jdbc.update("DELETE FROM legal_documents WHERE published_by_system_id IS NOT NULL");
    }

    // ==================== PUBLIC DOCUMENTS ====================

    @Test
    void currentVersionsAndTexts_arePublic_andCarryThePlaceholderMark() throws Exception {
        JsonNode current = json(mockMvc.perform(get("/api/legal/current")).andReturn());
        assertEquals("TERMS", current.path("terms").path("docType").asText());
        assertEquals("1", current.path("terms").path("version").asText());
        assertEquals("Terms of Service", current.path("terms").path("title").asText());
        assertEquals("2026-11-01T00:00:00Z", current.path("terms").path("effectiveAt").asText());
        assertTrue(current.path("terms").path("pendingLegalReview").asBoolean());
        assertFalse(current.path("terms").has("contentMd"), "the summary carries no text");
        assertEquals("PRIVACY", current.path("privacy").path("docType").asText());
        assertEquals("Privacy Policy", current.path("privacy").path("title").asText());
        assertTrue(current.path("privacy").path("pendingLegalReview").asBoolean());

        for (String path : List.of("terms", "TERMS", "Privacy")) {
            MvcResult result = mockMvc.perform(get("/api/legal/documents/" + path)
                    .header("Authorization", "Bearer not-a-valid-token")).andReturn();
            assertEquals(200, result.getResponse().getStatus(), path + ": a token, valid or not, is ignored");
            JsonNode doc = json(result);
            assertTrue(doc.path("contentMd").asText().startsWith("**Placeholder text - pending legal review.**"));
            assertTrue(doc.path("pendingLegalReview").asBoolean());
            assertEquals("1", doc.path("version").asText());
        }

        MvcResult unknown = mockMvc.perform(get("/api/legal/documents/cookies")).andReturn();
        assertEquals(404, unknown.getResponse().getStatus());
        assertEquals("Legal document not found", message(unknown));

        // The account endpoints need a token.
        MvcResult anonymous = mockMvc.perform(get("/api/users/me/legal")).andReturn();
        assertEquals(401, anonymous.getResponse().getStatus());
        assertEquals("Authentication required", message(anonymous));
        MvcResult anonymousAccept = mockMvc.perform(post("/api/users/me/legal/accept")
                .contentType(MediaType.APPLICATION_JSON).content("{\"docType\":\"TERMS\",\"version\":\"1\"}")).andReturn();
        assertEquals(401, anonymousAccept.getResponse().getStatus());
    }

    // ==================== REGISTRATION ====================

    @Test
    void registrationWithAStaleVersion_is400_andLeavesNothingBehind() throws Exception {
        Map<String, Object> body = AuthApi.registration("patient", "stale.terms@example.com");
        body.put("acceptedTermsVersion", "1");
        body.put("acceptedPrivacyVersion", "0.9");

        MvcResult result = register(body);

        assertEquals(400, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("LEGAL_ACCEPTANCE_REQUIRED", json(result).path("code").asText());
        assertEquals(STALE_REGISTRATION, message(result));
        assertEquals(0, count("SELECT COUNT(*) FROM users WHERE email = 'stale.terms@example.com'"));
        assertEquals(0, count("SELECT COUNT(*) FROM legal_acceptances"));
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE action IN ('LEGAL_ACCEPT', 'REGISTER')"));

        // Versions are opaque labels compared exactly: anything but "1" is refused, even when only one field is sent.
        Map<String, Object> other = AuthApi.registration("patient", "stale.case@example.com");
        other.put("acceptedTermsVersion", "1.0");
        MvcResult caseResult = register(other);
        assertEquals(400, caseResult.getResponse().getStatus());
        assertEquals("LEGAL_ACCEPTANCE_REQUIRED", json(caseResult).path("code").asText());
        assertEquals(0, count("SELECT COUNT(*) FROM users WHERE email = 'stale.case@example.com'"));
    }

    @Test
    void registrationWithoutVersions_succeeds_writesNoRows_andTheFirstSessionMustAccept() throws Exception {
        MvcResult result = register(AuthApi.registration("patient", "native.screen@example.com"));

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        String systemId = json(result).path("user").path("system_id").asText();
        assertEquals("PAT-00003", systemId);
        assertEquals(0, count("SELECT COUNT(*) FROM legal_acceptances"));

        JsonNode login = json(api.login("native.screen@example.com", "Str0ng-Passw0rd"));
        assertTrue(login.path("flags").path("legalActionRequired").asBoolean(), login.toString());

        String accessToken = login.path("tokens").path("accessToken").asText();
        JsonNode me = json(api.me(accessToken));
        assertTrue(me.path("flags").path("legalActionRequired").asBoolean());

        JsonNode status = json(api.send(get("/api/users/me/legal"), null, accessToken));
        assertTrue(status.path("actionRequired").asBoolean());
        assertEquals(0, status.path("accepted").size());
        assertEquals(2, status.path("pending").size());
        assertEquals("TERMS", status.path("pending").get(0).path("docType").asText());
        assertEquals("PRIVACY", status.path("pending").get(1).path("docType").asText());
    }

    @Test
    void registrationWithTheCurrentVersions_recordsBothWithAddressAndUserAgent_andAudits() throws Exception {
        Map<String, Object> body = AuthApi.registration("patient", "web.form@example.com");
        body.put("acceptedTermsVersion", "1");
        body.put("acceptedPrivacyVersion", " 1 ");

        MvcResult result = register(body);

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        String systemId = json(result).path("user").path("system_id").asText();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT doc_type, version, ip_address, user_agent, accepted_at FROM legal_acceptances "
                        + "WHERE system_id = ? ORDER BY doc_type", systemId);
        assertEquals(2, rows.size());
        assertEquals("PRIVACY", rows.get(0).get("doc_type"));
        assertEquals("TERMS", rows.get(1).get("doc_type"));
        for (Map<String, Object> row : rows) {
            assertEquals("1", row.get("version"));
            assertEquals(CLIENT_IP, row.get("ip_address"));
            assertEquals(BROWSER, row.get("user_agent"));
        }
        assertEquals(2, count("SELECT COUNT(*) FROM audit_log WHERE action = 'LEGAL_ACCEPT' AND resource_type = 'ACCOUNT' "
                + "AND system_id = '" + systemId + "' AND target_system_id = '" + systemId + "' AND status = 'SUCCESS'"));

        JsonNode login = json(api.login("web.form@example.com", "Str0ng-Passw0rd"));
        assertFalse(login.path("flags").path("legalActionRequired").asBoolean(true), login.toString());
        assertTrue(login.path("flags").has("legalActionRequired"));
    }

    @Test
    void registrationWithOnlyOneVersion_recordsThatOne_andStillRequiresTheOther() throws Exception {
        Map<String, Object> body = AuthApi.registration("proxy", "terms.only@example.com");
        body.put("acceptedTermsVersion", "1");
        body.put("acceptedPrivacyVersion", "");

        MvcResult result = register(body);

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(1, count("SELECT COUNT(*) FROM legal_acceptances WHERE doc_type = 'TERMS'"));
        assertEquals(0, count("SELECT COUNT(*) FROM legal_acceptances WHERE doc_type = 'PRIVACY'"));

        String accessToken = json(api.login("terms.only@example.com", "Str0ng-Passw0rd"))
                .path("tokens").path("accessToken").asText();
        JsonNode status = json(api.send(get("/api/users/me/legal"), null, accessToken));
        assertTrue(status.path("actionRequired").asBoolean());
        assertEquals(1, status.path("pending").size());
        assertEquals("PRIVACY", status.path("pending").get(0).path("docType").asText());
        assertTrue(status.path("accepted").get(0).path("current").asBoolean());
    }

    // ==================== ACCEPTANCE AND RE-ACCEPTANCE ====================

    @Test
    void everyRole_acceptsForItself_andANewVersionRequiresAcceptingAgain() throws Exception {
        // Fixture accounts predate the documents: every role starts with both pending.
        for (User user : List.of(patient, doctor, caregiver, admin)) {
            assertTrue(json(doGet("/api/users/me", user)).path("flags").path("legalActionRequired").asBoolean(),
                    user.getSystemId());
            acceptBoth(user);
            assertFalse(json(doGet("/api/users/me", user)).path("flags").path("legalActionRequired").asBoolean(true),
                    user.getSystemId());
        }
        assertEquals(8, count("SELECT COUNT(*) FROM legal_acceptances"));
        assertEquals(8, count("SELECT COUNT(*) FROM audit_log WHERE action = 'LEGAL_ACCEPT'"));

        // Repeating an acceptance writes nothing.
        MvcResult repeat = doPost("/api/users/me/legal/accept", patient, Map.of("docType", "terms", "version", "1"));
        assertEquals(200, repeat.getResponse().getStatus());
        assertFalse(json(repeat).path("actionRequired").asBoolean(true));
        assertEquals(8, count("SELECT COUNT(*) FROM legal_acceptances"));
        assertEquals(8, count("SELECT COUNT(*) FROM audit_log WHERE action = 'LEGAL_ACCEPT'"));

        // The administrator publishes privacy version 2.
        MvcResult published = doPost("/api/admin/legal/documents", admin, publishBody("PRIVACY", "2"));
        assertEquals(201, published.getResponse().getStatus(), published.getResponse().getContentAsString());
        JsonNode document = json(published);
        assertEquals("2", document.path("version").asText());
        assertFalse(document.path("pendingLegalReview").asBoolean(true));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'LEGAL_PUBLISH' "
                + "AND resource_type = 'LEGAL_DOCUMENT' AND system_id = 'ADM-00001' AND target_system_id IS NULL"));
        assertEquals("2", json(mockMvc.perform(get("/api/legal/current")).andReturn())
                .path("privacy").path("version").asText());

        // Everybody must accept again, the next time the flag is computed (sign-in, refresh, /me).
        JsonNode login = json(api.login(patient.getEmail(), AuthApi.FIXTURE_PASSWORD));
        assertTrue(login.path("flags").path("legalActionRequired").asBoolean(), login.toString());
        JsonNode refreshed = json(api.refresh(login.path("tokens").path("refreshToken").asText()));
        assertTrue(refreshed.path("flags").path("legalActionRequired").asBoolean());

        JsonNode status = json(doGet("/api/users/me/legal", patient));
        assertTrue(status.path("actionRequired").asBoolean());
        assertEquals(1, status.path("pending").size());
        assertEquals("PRIVACY", status.path("pending").get(0).path("docType").asText());
        assertEquals("2", status.path("pending").get(0).path("version").asText());
        for (JsonNode row : status.path("accepted")) {
            boolean privacy = "PRIVACY".equals(row.path("docType").asText());
            assertEquals(!privacy, row.path("current").asBoolean(), row.toString());
        }

        // Only the current version can be accepted.
        MvcResult stale = doPost("/api/users/me/legal/accept", patient, Map.of("docType", "PRIVACY", "version", "1"));
        assertEquals(400, stale.getResponse().getStatus());
        assertEquals("LEGAL_ACCEPTANCE_REQUIRED", json(stale).path("code").asText());
        assertEquals(STALE_ACCEPT, message(stale));

        MvcResult accepted = doPost("/api/users/me/legal/accept", patient, Map.of("docType", "PRIVACY", "version", "2"));
        assertEquals(201, accepted.getResponse().getStatus());
        JsonNode after = json(accepted);
        assertFalse(after.path("actionRequired").asBoolean(true));
        assertEquals(0, after.path("pending").size());
        assertEquals("2", after.path("accepted").get(0).path("version").asText(), "newest first");
        assertTrue(after.path("accepted").get(0).path("current").asBoolean());
        assertFalse(json(doGet("/api/users/me", patient)).path("flags").path("legalActionRequired").asBoolean(true));

        // The others still have to accept version 2.
        assertTrue(json(doGet("/api/users/me", doctor)).path("flags").path("legalActionRequired").asBoolean());
    }

    @Test
    void acceptValidation_usesTheErrorEnvelope() throws Exception {
        assertValidation(Map.of("version", "1"), "docType: must not be blank");
        assertValidation(Map.of("docType", "COOKIES", "version", "1"), "docType: must be TERMS or PRIVACY");
        assertValidation(Map.of("docType", "TERMS", "version", "  "), "version: must not be blank");
        assertValidation(Map.of("docType", "TERMS", "version", "x".repeat(21)), "version: size must be between 0 and 20");
        assertValidation(Map.of(), "docType: must not be blank");

        MvcResult malformed = mockMvc.perform(post("/api/users/me/legal/accept").header("Authorization", tokenFor(patient))
                .contentType(MediaType.APPLICATION_JSON).content("{not json")).andReturn();
        assertEquals(400, malformed.getResponse().getStatus());
        assertEquals("Malformed request body", message(malformed));
        assertEquals(0, count("SELECT COUNT(*) FROM legal_acceptances"));
    }

    // ==================== ADMINISTRATION ====================

    @Test
    void acceptanceRecords_areReadableByAdministratorsOnly_withTheEvidence_paged() throws Exception {
        MvcResult accepted = acceptAs(patient, "TERMS", "1", "10.1.2.3", BROWSER);
        assertEquals(201, accepted.getResponse().getStatus());
        assertEquals(201, acceptAs(patient, "PRIVACY", "1", "10.1.2.3", "x".repeat(600)).getResponse().getStatus());

        String url = "/api/admin/users/" + patient.getSystemId() + "/legal-acceptances";
        for (User other : List.of(patient, doctor, caregiver, otherPatient, otherDoctor, otherCaregiver)) {
            MvcResult denied = doGet(url, other);
            assertEquals(403, denied.getResponse().getStatus(), other.getSystemId());
            assertEquals(FORBIDDEN, message(denied));
        }

        JsonNode page = json(doGet(url, admin));
        assertEquals(2, page.path("totalElements").asInt());
        assertEquals(0, page.path("page").asInt());
        assertEquals(20, page.path("size").asInt());
        assertEquals(1, page.path("totalPages").asInt());
        JsonNode newest = page.path("content").get(0);
        assertEquals("PRIVACY", newest.path("docType").asText(), "newest first");
        assertEquals(patient.getSystemId(), newest.path("systemId").asText());
        assertEquals("10.1.2.3", newest.path("ipAddress").asText());
        assertEquals(500, newest.path("userAgent").asText().length(), "the User-Agent is cut to 500 characters");
        assertTrue(newest.path("current").asBoolean());
        assertEquals(BROWSER, page.path("content").get(1).path("userAgent").asText());

        JsonNode clamped = json(doGet(url + "?page=-3&size=500", admin));
        assertEquals(0, clamped.path("page").asInt());
        assertEquals(100, clamped.path("size").asInt());
        JsonNode single = json(doGet(url + "?page=1&size=1", admin));
        assertEquals(1, single.path("content").size());
        assertEquals("TERMS", single.path("content").get(0).path("docType").asText());
        assertEquals(2, single.path("totalPages").asInt());

        JsonNode empty = json(doGet("/api/admin/users/" + doctor.getSystemId() + "/legal-acceptances", admin));
        assertEquals(0, empty.path("content").size());
        assertEquals(0, empty.path("totalElements").asInt());

        MvcResult unknown = doGet("/api/admin/users/PAT-99999/legal-acceptances", admin);
        assertEquals(404, unknown.getResponse().getStatus());
        assertEquals("User not found", message(unknown));
        MvcResult badSize = doGet(url + "?size=many", admin);
        assertEquals(400, badSize.getResponse().getStatus());
        assertEquals("Invalid value for parameter 'size'", message(badSize));
    }

    @Test
    void publishing_isForAdministratorsOnly_validated_andIdempotent() throws Exception {
        for (User other : List.of(patient, doctor, caregiver)) {
            MvcResult denied = doPost("/api/admin/legal/documents", other, publishBody("TERMS", "2"));
            assertEquals(403, denied.getResponse().getStatus(), other.getSystemId());
            assertEquals(FORBIDDEN, message(denied));
        }
        assertEquals(2, count("SELECT COUNT(*) FROM legal_documents"));

        // Validation envelope: the first field alphabetically is named in the message.
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("docType", "cookies");
        invalid.put("version", "2 beta");
        invalid.put("title", " ");
        MvcResult validation = doPost("/api/admin/legal/documents", admin, invalid);
        assertEquals(400, validation.getResponse().getStatus());
        JsonNode error = json(validation);
        assertEquals("VALIDATION_FAILED", error.path("code").asText());
        assertEquals("contentMd: must not be blank", error.path("message").asText());
        assertEquals(4, error.path("errors").size(), error.toString());

        Map<String, Object> future = publishBody("TERMS", "2");
        future.put("effectiveAt", "2999-01-01T00:00:00Z");
        MvcResult futureResult = doPost("/api/admin/legal/documents", admin, future);
        assertEquals(400, futureResult.getResponse().getStatus());
        assertEquals("effectiveAt: must be a date in the past or in the present", message(futureResult));

        Map<String, Object> earlier = publishBody("TERMS", "2");
        earlier.put("effectiveAt", "2026-10-01T00:00:00Z");
        MvcResult earlierResult = doPost("/api/admin/legal/documents", admin, earlier);
        assertEquals(409, earlierResult.getResponse().getStatus());
        assertEquals("effectiveAt must not be earlier than the current version's effective date", message(earlierResult));

        MvcResult duplicate = doPost("/api/admin/legal/documents", admin, publishBody("terms", "1"));
        assertEquals(409, duplicate.getResponse().getStatus());
        assertEquals("This version of the document already exists", message(duplicate));

        Map<String, Object> notAnInstant = publishBody("TERMS", "2");
        notAnInstant.put("effectiveAt", "next tuesday");
        MvcResult malformed = doPost("/api/admin/legal/documents", admin, notAnInstant);
        assertEquals(400, malformed.getResponse().getStatus());
        assertEquals("Malformed request body", message(malformed));
        assertEquals(2, count("SELECT COUNT(*) FROM legal_documents"));

        // An explicit past effective date with an offset is stored in UTC, cut to whole seconds.
        Map<String, Object> dated = publishBody(" privacy ", "2026.11-b_1");
        dated.put("effectiveAt", "2026-11-02T10:15:30.789+02:00");
        String key = "publish-" + clientId();
        MvcResult first = doPostWithHeaders("/api/admin/legal/documents", admin, dated, Map.of("Idempotency-Key", key));
        assertEquals(201, first.getResponse().getStatus(), first.getResponse().getContentAsString());
        JsonNode body = json(first);
        assertEquals("PRIVACY", body.path("docType").asText());
        assertEquals("2026.11-b_1", body.path("version").asText());
        assertEquals("2026-11-02T08:15:30Z", body.path("effectiveAt").asText());

        MvcResult replay = doPostWithHeaders("/api/admin/legal/documents", admin, dated, Map.of("Idempotency-Key", key));
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(body, json(replay));
        assertEquals(3, count("SELECT COUNT(*) FROM legal_documents"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'LEGAL_PUBLISH'"));

        MvcResult badKey = doPostWithHeaders("/api/admin/legal/documents", admin, publishBody("TERMS", "3"),
                Map.of("Idempotency-Key", "short"));
        assertEquals(400, badKey.getResponse().getStatus());
        assertEquals("Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'", message(badKey));

        JsonNode text = json(mockMvc.perform(get("/api/legal/documents/privacy")).andReturn());
        assertEquals("2026.11-b_1", text.path("version").asText());
        assertEquals(publishBody("PRIVACY", "x").get("contentMd"), text.path("contentMd").asText());
    }

    // ==================== HELPERS ====================

    private MvcResult register(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .header("User-Agent", BROWSER)
                        .with(request -> {
                            request.setRemoteAddr(CLIENT_IP);
                            return request;
                        }))
                .andReturn();
    }

    private void acceptBoth(User user) throws Exception {
        assertEquals(201, doPost("/api/users/me/legal/accept", user, Map.of("docType", "TERMS", "version", "1"))
                .getResponse().getStatus());
        MvcResult last = doPost("/api/users/me/legal/accept", user, Map.of("docType", "privacy", "version", "1"));
        assertEquals(201, last.getResponse().getStatus());
        assertFalse(json(last).path("actionRequired").asBoolean(true));
    }

    private MvcResult acceptAs(User user, String docType, String version, String ip, String userAgent) throws Exception {
        return mockMvc.perform(post("/api/users/me/legal/accept")
                        .header("Authorization", tokenFor(user))
                        .header("User-Agent", userAgent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("docType", docType, "version", version)))
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        }))
                .andReturn();
    }

    private void assertValidation(Map<String, Object> body, String expectedMessage) throws Exception {
        MvcResult result = doPost("/api/users/me/legal/accept", patient, body);
        assertEquals(400, result.getResponse().getStatus(), body.toString());
        JsonNode error = json(result);
        assertEquals("VALIDATION_FAILED", error.path("code").asText());
        assertEquals(expectedMessage, error.path("message").asText());
        assertTrue(error.path("errors").isArray());
    }

    private static Map<String, Object> publishBody(String docType, String version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docType", docType);
        body.put("version", version);
        body.put("title", "Privacy Policy");
        body.put("contentMd", "# Privacy Policy\n\nThis notice explains what personal data CareNexus keeps and why.");
        return body;
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
