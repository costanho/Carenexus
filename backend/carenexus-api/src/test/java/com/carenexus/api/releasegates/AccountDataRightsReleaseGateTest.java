package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.support.AuthApi;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Release gates for the data-rights routes: authentication on all eleven, the ADMIN gate on the four admin routes,
 * own-account scope for every role (a caregiver's grant changes nothing), the 400 envelopes, the admin list's
 * pagination envelope, filters and size clamp, the field set of each response, and the audit rows.
 */
class AccountDataRightsReleaseGateTest extends CommittedIntegrationTest {

    private static final String EXPORTS = "/api/users/me/data-export";
    private static final String CLOSE = "/api/users/me/close";
    private static final String DELETION = "/api/users/me/deletion-request";
    private static final String ADMIN_REQUESTS = "/api/admin/account-requests";
    private static final String FORBIDDEN = "You don't have permission to access this resource";
    private static final String UNAUTHENTICATED = "Authentication required";
    private static final String PASSWORD = AuthApi.FIXTURE_PASSWORD;

    private static final List<String> REQUEST_FIELDS = List.of("requestId", "type", "status", "reason",
            "decisionReason", "requestedAt", "processedAt", "expiresAt", "downloadable", "fileName");
    private static final List<String> ADMIN_EXTRA_FIELDS = List.of("systemId", "firstName", "lastName", "email",
            "role", "isActive", "processedBySystemId");

    private static Map<String, Object> password(String value) {
        Map<String, Object> body = new HashMap<>();
        body.put("password", value);
        return body;
    }

    private MvcResult raw(String url, User as, String content) throws Exception {
        var builder = post(url).header("Authorization", tokenFor(as));
        if (content != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(content);
        }
        return mockMvc.perform(builder).andReturn();
    }

    private int deletionRequestOf(User user) throws Exception {
        MvcResult result = doPost(DELETION, user, password(PASSWORD));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result).path("requestId").asInt();
    }

    @Test
    void everyRoute_requiresAnAccessToken() throws Exception {
        List<String> posts = List.of(EXPORTS, CLOSE, DELETION, DELETION + "/1/cancel", ADMIN_REQUESTS + "/1/approve",
                ADMIN_REQUESTS + "/1/reject");
        for (String url : posts) {
            MvcResult result = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
            assertEquals(401, result.getResponse().getStatus(), url);
            assertEquals(UNAUTHENTICATED, message(result), url);
        }
        for (String url : List.of(EXPORTS, EXPORTS + "/1/download", DELETION, ADMIN_REQUESTS, ADMIN_REQUESTS + "/1")) {
            MvcResult result = mockMvc.perform(get(url)).andReturn();
            assertEquals(401, result.getResponse().getStatus(), url);
            assertEquals(UNAUTHENTICATED, message(result), url);
        }
    }

    @Test
    void adminRoutes_areForbiddenToEveryOtherRole() throws Exception {
        int id = deletionRequestOf(otherPatient);
        for (User user : List.of(patient, doctor, caregiver)) {
            for (MvcResult result : List.of(
                    doGet(ADMIN_REQUESTS, user),
                    doGet(ADMIN_REQUESTS + "/" + id, user),
                    doPost(ADMIN_REQUESTS + "/" + id + "/approve", user, null),
                    doPost(ADMIN_REQUESTS + "/" + id + "/reject", user, Map.of("reason", "No")))) {
                assertEquals(403, result.getResponse().getStatus(), user.getSystemId());
                assertEquals(FORBIDDEN, message(result));
            }
        }
        assertEquals("REQUESTED", jdbc.queryForObject("SELECT status FROM account_requests WHERE request_id = ?",
                String.class, id));
    }

    @Test
    void ownAccountRoutes_workForEveryRole_andACaregiverGrantChangesNothing() throws Exception {
        grantApprovedAccess(caregiver, patient);
        for (User user : List.of(patient, doctor, caregiver, admin)) {
            MvcResult export = doPost(EXPORTS, user, password(PASSWORD));
            assertEquals(201, export.getResponse().getStatus(), user.getSystemId());
            JsonNode body = json(export);
            for (String field : REQUEST_FIELDS) {
                assertTrue(body.has(field), field + " is always present");
            }
            assertEquals(1, json(doGet(EXPORTS, user)).size());
            assertEquals(201, doPost(DELETION, user, password(PASSWORD)).getResponse().getStatus(), user.getSystemId());
            assertEquals(1, json(doGet(DELETION, user)).size());
        }

        // The caregiver's deletion request is about the caregiver's own account only
        int caregiverRequest = json(doGet(DELETION, caregiver)).get(0).path("requestId").asInt();
        assertEquals("CGV-00001", jdbc.queryForObject("SELECT system_id FROM account_requests WHERE request_id = ?",
                String.class, caregiverRequest));
        // Nobody may withdraw another user's request, whatever the grant
        int patientRequest = json(doGet(DELETION, patient)).get(0).path("requestId").asInt();
        assertEquals(404, doPost(DELETION + "/" + patientRequest + "/cancel", caregiver, null).getResponse().getStatus());
        assertEquals(404, doPost(DELETION + "/" + patientRequest + "/cancel", admin, null).getResponse().getStatus());
        // A body naming another user is ignored: the request is the caller's
        Map<String, Object> withSystemId = password(PASSWORD);
        withSystemId.put("systemId", patient.getSystemId());
        int ownExport = json(doPost(EXPORTS, otherCaregiver, withSystemId)).path("requestId").asInt();
        assertEquals("CGV-00002", jdbc.queryForObject("SELECT system_id FROM account_requests WHERE request_id = ?",
                String.class, ownExport));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCOUNT_DELETION_REQUEST' "
                + "AND system_id = 'CGV-00001' AND target_system_id = 'CGV-00001'", Integer.class));
    }

    @Test
    void invalidBodies_answerTheValidationEnvelope() throws Exception {
        for (String url : List.of(EXPORTS, CLOSE, DELETION)) {
            MvcResult blank = doPost(url, patient, password("   "));
            assertEquals(400, blank.getResponse().getStatus(), url);
            assertEquals("password: must not be blank", message(blank));
            assertEquals("VALIDATION_FAILED", json(blank).path("code").asText());
            assertEquals("password", json(blank).path("errors").get(0).path("field").asText());

            MvcResult tooLong = doPost(url, patient, password("x".repeat(201)));
            assertEquals(400, tooLong.getResponse().getStatus(), url);
            assertEquals("password: must be at most 200 characters", message(tooLong));

            MvcResult malformed = raw(url, patient, "{not json");
            assertEquals(400, malformed.getResponse().getStatus(), url);
            assertEquals("Malformed request body", message(malformed));
            assertEquals("Malformed request body", message(raw(url, patient, null)));
        }
        for (String url : List.of(CLOSE, DELETION)) {
            Map<String, Object> body = password(PASSWORD);
            body.put("reason", "r".repeat(501));
            MvcResult reason = doPost(url, patient, body);
            assertEquals(400, reason.getResponse().getStatus(), url);
            assertEquals("reason: must be at most 500 characters", message(reason));
            assertEquals("reason", json(reason).path("errors").get(0).path("field").asText());
        }

        int id = deletionRequestOf(patient);
        MvcResult blankReason = doPost(ADMIN_REQUESTS + "/" + id + "/reject", admin, Map.of("reason", "  "));
        assertEquals(400, blankReason.getResponse().getStatus());
        assertEquals("reason: must not be blank", message(blankReason));
        MvcResult longReason = doPost(ADMIN_REQUESTS + "/" + id + "/reject", admin, Map.of("reason", "r".repeat(501)));
        assertEquals("reason: must be at most 500 characters", message(longReason));
        assertEquals("Malformed request body", message(raw(ADMIN_REQUESTS + "/" + id + "/reject", admin, null)));
        assertEquals("Invalid value for parameter 'id'", message(doPost(ADMIN_REQUESTS + "/x/approve", admin, null)));
        assertEquals("Invalid value for parameter 'id'", message(doPost(DELETION + "/x/cancel", patient, null)));

        // Nothing was written by the rejected bodies
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM account_requests", Integer.class));
        assertEquals("REQUESTED", jdbc.queryForObject("SELECT status FROM account_requests WHERE request_id = ?",
                String.class, id));
    }

    @Test
    void adminList_isPaged_filtered_newestFirst_andClamped() throws Exception {
        int first = deletionRequestOf(patient);
        int second = deletionRequestOf(otherPatient);
        assertEquals(201, doPost(EXPORTS, doctor, password(PASSWORD)).getResponse().getStatus());
        assertEquals(200, doPost(CLOSE, otherCaregiver, password(PASSWORD)).getResponse().getStatus());

        JsonNode all = json(doGet(ADMIN_REQUESTS, admin));
        assertEquals(4, all.path("totalElements").asInt());
        assertEquals(0, all.path("page").asInt());
        assertEquals(20, all.path("size").asInt());
        assertEquals(1, all.path("totalPages").asInt());
        assertEquals("CLOSURE", all.path("content").get(0).path("type").asText(), "newest first");
        JsonNode row = all.path("content").get(0);
        for (String field : REQUEST_FIELDS) {
            assertTrue(row.has(field), field);
        }
        for (String field : ADMIN_EXTRA_FIELDS) {
            assertTrue(row.has(field), field);
        }
        assertEquals("CGV-00002", row.path("systemId").asText());
        assertEquals("CAREGIVER", row.path("role").asText());
        assertFalse(row.path("isActive").asBoolean());
        assertEquals("CGV-00002", row.path("processedBySystemId").asText());
        assertFalse(row.has("resultRef"));

        JsonNode queue = json(call(get(ADMIN_REQUESTS).param("type", "deletion").param("status", " Requested "),
                admin, null));
        assertEquals(2, queue.path("totalElements").asInt());
        assertEquals(second, queue.path("content").get(0).path("requestId").asInt());
        assertEquals(first, queue.path("content").get(1).path("requestId").asInt());
        assertEquals("mary@example.com", queue.path("content").get(0).path("email").asText());
        assertEquals("PATIENT", queue.path("content").get(0).path("role").asText());
        assertTrue(queue.path("content").get(0).path("isActive").asBoolean());

        JsonNode exports = json(doGet(ADMIN_REQUESTS + "?type=EXPORT&status=", admin));
        assertEquals(1, exports.path("totalElements").asInt());
        assertTrue(exports.path("content").get(0).path("downloadable").asBoolean());

        JsonNode paged = json(doGet(ADMIN_REQUESTS + "?page=1&size=1", admin));
        assertEquals(1, paged.path("content").size());
        assertEquals(4, paged.path("totalPages").asInt());
        assertEquals(100, json(doGet(ADMIN_REQUESTS + "?size=1000", admin)).path("size").asInt());
        assertEquals(1, json(doGet(ADMIN_REQUESTS + "?size=0", admin)).path("size").asInt());
        assertEquals(0, json(doGet(ADMIN_REQUESTS + "?page=-3", admin)).path("page").asInt());

        assertEquals("Invalid type. Must be EXPORT, CLOSURE or DELETION",
                message(doGet(ADMIN_REQUESTS + "?type=OTHER", admin)));
        assertEquals("Invalid status. Must be REQUESTED, PROCESSING, READY, COMPLETED, REJECTED or CANCELLED",
                message(doGet(ADMIN_REQUESTS + "?status=DONE", admin)));
        assertEquals("Invalid value for parameter 'page'", message(doGet(ADMIN_REQUESTS + "?page=x", admin)));
        assertEquals("Invalid value for parameter 'size'", message(doGet(ADMIN_REQUESTS + "?size=x", admin)));

        JsonNode one = json(doGet(ADMIN_REQUESTS + "/" + first, admin));
        assertEquals("PAT-00001", one.path("systemId").asText());
        MvcResult missing = doGet(ADMIN_REQUESTS + "/999999", admin);
        assertEquals(404, missing.getResponse().getStatus());
        assertEquals("Account request not found", message(missing));
        assertEquals("Invalid value for parameter 'id'", message(doGet(ADMIN_REQUESTS + "/abc", admin)));
    }

    @Test
    void secondExportWhileOneIsPrepared_isAConflict() throws Exception {
        jdbc.update("INSERT INTO account_requests (system_id, type, status, requested_at, updated_at) "
                + "VALUES ('PAT-00001', 'EXPORT', 'PROCESSING', now(), now())");
        MvcResult result = doPost(EXPORTS, patient, password(PASSWORD));
        assertEquals(409, result.getResponse().getStatus());
        assertEquals("A data export is already being prepared", message(result));

        JsonNode preparing = json(doGet(EXPORTS, patient)).get(0);
        assertEquals("PROCESSING", preparing.path("status").asText());
        assertFalse(preparing.path("downloadable").asBoolean());
        MvcResult notReady = doGet(EXPORTS + "/" + preparing.path("requestId").asInt() + "/download", patient);
        assertEquals(409, notReady.getResponse().getStatus());
        assertEquals("This export is not ready yet", message(notReady));
    }
}
