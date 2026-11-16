package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Release gates for facilities: who may call the seven routes (any signed-in user for /api/facilities, ADMIN for
 * /api/admin/facilities, nobody without a token, caregiver grants irrelevant), create / replace / deactivate /
 * activate with their audit rows, the 400 envelope, the public list hiding inactive facilities while the detail
 * still returns them, ordering, search, the paging clamp and idempotent create. Runs with committed transactions
 * (CommittedIntegrationTest); the facilities table starts empty in every test (no seed).
 */
class FacilityReleaseGateTest extends CommittedIntegrationTest {

    private static final String PUBLIC = "/api/facilities";
    private static final String ADMIN = "/api/admin/facilities";
    private static final String FORBIDDEN = "You don't have permission to access this resource";
    private static final String UNAUTHENTICATED = "Authentication required";
    private static final String NOT_FOUND = "Facility not found";
    private static final String KEY_FORMAT = "Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'";

    private static final List<String> FACILITY_FIELDS = List.of("facilityId", "name", "type", "address", "phone",
            "email", "isActive", "createdAt", "updatedAt");
    private static final List<String> PAGE_FIELDS = List.of("content", "page", "size", "totalElements", "totalPages");

    // ==================== HELPERS ====================

    private static Map<String, Object> body(String name, String type, String address, String phone, String email) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("type", type);
        body.put("address", address);
        body.put("phone", phone);
        body.put("email", email);
        return body;
    }

    private static Map<String, Object> named(String name) {
        return body(name, "Clinic", "1 Main Road, Harare", "+263 24 270 0000", "desk@clinic.example");
    }

    private JsonNode create(Map<String, Object> body) throws Exception {
        MvcResult result = doPost(ADMIN, admin, body);
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private int create(String name) throws Exception {
        return create(named(name)).path("facilityId").asInt();
    }

    private MvcResult createWithKey(User as, Map<String, Object> body, String key) throws Exception {
        return doPostWithHeaders(ADMIN, as, body, Map.of("Idempotency-Key", key));
    }

    private JsonNode ok(MvcResult result) throws Exception {
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private MvcResult raw(MockHttpServletRequestBuilder builder, User as, String rawBody) throws Exception {
        builder.header("Authorization", tokenFor(as));
        if (rawBody != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(rawBody);
        }
        return mockMvc.perform(builder).andReturn();
    }

    private void assertError(MvcResult result, int status, String message, String code) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        assertEquals(message, body.path("message").asText());
        if (code == null) {
            assertTrue(body.path("code").isMissingNode() || body.path("code").isNull(), "no code expected: " + body);
        } else {
            assertEquals(code, body.path("code").asText());
        }
    }

    /** The errors[] of a validation failure as "field: message" strings, in the order the server sent them. */
    private List<String> fieldErrors(MvcResult result) throws Exception {
        List<String> errors = new ArrayList<>();
        for (JsonNode error : json(result).path("errors")) {
            errors.add(error.path("field").asText() + ": " + error.path("message").asText());
        }
        return errors;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(names::add);
        return names.stream().sorted().toList();
    }

    private static List<String> sorted(List<String> names) {
        return names.stream().sorted().toList();
    }

    private static List<String> names(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.path("content").forEach(item -> names.add(item.path("name").asText()));
        return names;
    }

    /** GET with the query parameter set through the builder, so the value reaches the server exactly as given. */
    private MvcResult search(String url, User as, String query) throws Exception {
        return call(get(url).param("query", query), as, null);
    }

    private int facilityRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM facilities", Integer.class);
    }

    private List<Map<String, Object>> facilityAudits() {
        return jdbc.queryForList("SELECT * FROM audit_log WHERE resource_type = 'FACILITY' ORDER BY audit_id");
    }

    private void assertAudit(Map<String, Object> row, String action, int facilityId, String method, String path) {
        assertEquals(action, row.get("action"));
        assertEquals("FACILITY", row.get("resource_type"));
        assertEquals(facilityId, ((Number) row.get("resource_id")).intValue());
        assertEquals(admin.getSystemId(), row.get("system_id"));
        assertEquals("ADMIN", row.get("actor_role"));
        assertEquals("SUCCESS", row.get("status"));
        assertNull(row.get("patient_system_id"));
        assertEquals(method, row.get("http_method"));
        assertEquals(path, row.get("request_path"));
    }

    private List<User> nonAdmins() {
        return List.of(patient, doctor, caregiver, otherPatient, otherDoctor, otherCaregiver);
    }

    // ==================== AUTHENTICATION AND ROLES ====================

    @Test
    void everyRoute_answers401WithoutAToken_andWithAnInvalidOne() throws Exception {
        int id = create("Avondale Family Clinic");
        String valid = "{\"name\":\"Anonymous\"}";
        List<MockHttpServletRequestBuilder> anonymous = List.of(
                get(PUBLIC),
                get(PUBLIC + "/" + id),
                get(ADMIN),
                post(ADMIN).contentType(MediaType.APPLICATION_JSON).content(valid),
                put(ADMIN + "/" + id).contentType(MediaType.APPLICATION_JSON).content(valid),
                patch(ADMIN + "/" + id + "/deactivate"),
                patch(ADMIN + "/" + id + "/activate"));
        for (MockHttpServletRequestBuilder request : anonymous) {
            assertError(mockMvc.perform(request).andReturn(), 401, UNAUTHENTICATED, null);
        }
        List<MockHttpServletRequestBuilder> invalidToken = List.of(
                get(PUBLIC).header("Authorization", "Bearer not-a-token"),
                get(PUBLIC + "/" + id).header("Authorization", "Bearer not-a-token"),
                get(ADMIN).header("Authorization", "Bearer not-a-token"),
                post(ADMIN).header("Authorization", "Bearer not-a-token")
                        .contentType(MediaType.APPLICATION_JSON).content(valid),
                patch(ADMIN + "/" + id + "/deactivate").header("Authorization", "Bearer not-a-token"));
        for (MockHttpServletRequestBuilder request : invalidToken) {
            assertError(mockMvc.perform(request).andReturn(), 401, UNAUTHENTICATED, null);
        }
        assertEquals(1, facilityRows());
        assertEquals("Avondale Family Clinic", ok(doGet(PUBLIC + "/" + id, admin)).path("name").asText());
    }

    @Test
    void adminRoutes_are403ForEveryOtherRole_beforeBodyOrIdAreLookedAt() throws Exception {
        int id = create("Avondale Family Clinic");
        int auditsBefore = facilityAudits().size();
        for (User caller : nonAdmins()) {
            assertError(doGet(ADMIN, caller), 403, FORBIDDEN, null);
            assertError(doPost(ADMIN, caller, named("Intruder Clinic")), 403, FORBIDDEN, null);
            assertError(doPostWithHeaders(ADMIN, caller, named("Intruder Clinic"), Map.of("Idempotency-Key", clientId())),
                    403, FORBIDDEN, null);
            assertError(doPut(ADMIN + "/" + id, caller, named("Renamed")), 403, FORBIDDEN, null);
            assertError(doPatch(ADMIN + "/" + id + "/deactivate", caller, null), 403, FORBIDDEN, null);
            assertError(doPatch(ADMIN + "/" + id + "/activate", caller, null), 403, FORBIDDEN, null);
            // The role is decided before the body, the id or its existence are looked at.
            assertError(raw(post(ADMIN), caller, "{not json"), 403, FORBIDDEN, null);
            assertError(doPost(ADMIN, caller, body(" ", null, null, null, null)), 403, FORBIDDEN, null);
            assertError(doPut(ADMIN + "/999999", caller, named("Renamed")), 403, FORBIDDEN, null);
            assertError(doPatch(ADMIN + "/abc/deactivate", caller, null), 403, FORBIDDEN, null);
        }
        assertEquals(1, facilityRows());
        JsonNode stored = ok(doGet(PUBLIC + "/" + id, admin));
        assertEquals("Avondale Family Clinic", stored.path("name").asText());
        assertTrue(stored.path("isActive").asBoolean());
        assertEquals(auditsBefore, facilityAudits().size());
    }

    @Test
    void publicRoutes_giveEveryRoleTheSameAnswer_whateverTheCaregiversGrants() throws Exception {
        int id = create("Avondale Family Clinic");
        JsonNode expectedList = ok(doGet(PUBLIC, admin));
        JsonNode expectedOne = ok(doGet(PUBLIC + "/" + id, admin));
        List<User> everyone = new ArrayList<>(nonAdmins());
        everyone.add(admin);
        for (User caller : everyone) {
            assertEquals(expectedList, ok(doGet(PUBLIC, caller)), caller.getSystemId());
            assertEquals(expectedOne, ok(doGet(PUBLIC + "/" + id, caller)), caller.getSystemId());
        }

        // A caregiver with an active grant, then with an expired one, then with a revoked one: no difference.
        var access = grantApprovedAccess(caregiver, patient);
        assertEquals(expectedList, ok(doGet(PUBLIC, caregiver)));
        access.setExpiresAt(LocalDateTime.now().minusMinutes(5));
        dependentAccessRepository.saveAndFlush(access);
        assertEquals(expectedList, ok(doGet(PUBLIC, caregiver)));
        assertEquals(expectedOne, ok(doGet(PUBLIC + "/" + id, caregiver)));
        access.setRevokedAt(LocalDateTime.now());
        access.setIsActive(false);
        dependentAccessRepository.saveAndFlush(access);
        assertEquals(expectedOne, ok(doGet(PUBLIC + "/" + id, caregiver)));

        // Reads are not audited.
        assertEquals(1, facilityAudits().size());
    }

    // ==================== CREATE ====================

    @Test
    void create_returns201WithTheFullObject_normalisesInput_andAudits() throws Exception {
        Map<String, Object> request = body("  Avondale Family Clinic  ", "  Clinic ", "   ", "", "  FrontDesk@Avondale.example ");
        request.put("isActive", false);
        request.put("facilityId", 999);
        request.put("createdAt", "2001-01-01T00:00:00");
        request.put("unknownField", "ignored");

        JsonNode created = create(request);
        assertEquals(sorted(FACILITY_FIELDS), fieldNames(created));
        int id = created.path("facilityId").asInt();
        assertTrue(id > 0 && id != 999);
        assertEquals("Avondale Family Clinic", created.path("name").asText());
        assertEquals("Clinic", created.path("type").asText());
        assertTrue(created.path("address").isNull(), "blank optional field is stored as null and sent as JSON null");
        assertTrue(created.path("phone").isNull());
        assertEquals("FrontDesk@Avondale.example", created.path("email").asText(), "case is kept");
        assertTrue(created.path("isActive").asBoolean(), "a new facility is always active");
        assertFalse(created.path("createdAt").asText().startsWith("2001"));
        assertEquals(created.path("createdAt").asText(), created.path("updatedAt").asText());
        assertFalse(created.path("createdAt").asText().endsWith("Z"), "clinic wall-clock, no offset");

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM facilities WHERE facility_id = ?", id);
        assertEquals("Avondale Family Clinic", row.get("name"));
        assertNull(row.get("address"));
        assertNull(row.get("phone"));
        assertEquals(Boolean.TRUE, row.get("is_active"));
        assertNotNull(row.get("updated_at"));

        List<Map<String, Object>> audits = facilityAudits();
        assertEquals(1, audits.size());
        assertAudit(audits.get(0), "CREATE", id, "POST", ADMIN);

        // Missing optional fields are fine too, and names are not unique.
        JsonNode minimal = create(Map.of("name", "Avondale Family Clinic"));
        assertNotEquals(id, minimal.path("facilityId").asInt());
        for (String field : List.of("type", "address", "phone", "email")) {
            assertTrue(minimal.has(field) && minimal.path(field).isNull(), field + " is present and null");
        }
        assertEquals(2, facilityRows());
    }

    @Test
    void create_validationFailures_are400WithEveryInvalidFieldSorted_andCreateNothing() throws Exception {
        MvcResult result = doPost(ADMIN, admin, body(" ", null, null, null, "front desk"));
        assertError(result, 400, "email: must be a valid email address", "VALIDATION_FAILED");
        assertEquals(List.of("email: must be a valid email address", "name: must not be blank"), fieldErrors(result));
        assertEquals(ADMIN, json(result).path("path").asText());

        result = doPost(ADMIN, admin, body("x".repeat(201), "t".repeat(81), "a".repeat(301), "12345", "e@x"));
        assertError(result, 400, "address: must be at most 300 characters", "VALIDATION_FAILED");
        assertEquals(List.of(
                "address: must be at most 300 characters",
                "email: must be a valid email address",
                "name: must be at most 200 characters",
                "phone: must be a valid phone number",
                "type: must be at most 80 characters"), fieldErrors(result));

        result = doPost(ADMIN, admin, body("Clinic", null, null, "+263 24 270 0000 000 000", "a".repeat(62) + "@" + "b".repeat(40) + "." + "c".repeat(40) + ".example"));
        assertEquals(List.of(
                "email: must be at most 150 characters",
                "phone: must be a valid phone number",
                "phone: must be at most 20 characters"), fieldErrors(result));

        result = doPost(ADMIN, admin, new HashMap<>());
        assertError(result, 400, "name: must not be blank", "VALIDATION_FAILED");
        assertEquals(List.of("name: must not be blank"), fieldErrors(result));

        assertError(raw(post(ADMIN), admin, "{not json"), 400, "Malformed request body", null);
        assertError(raw(post(ADMIN), admin, ""), 400, "Malformed request body", null);

        // Boundary values are accepted (lengths counted after trimming).
        JsonNode edge = create(body("  " + "n".repeat(200) + "  ", "t".repeat(80), "a".repeat(300),
                "(024) 270-0000", "a@b.co"));
        assertEquals(200, edge.path("name").asText().length());
        assertEquals("(024) 270-0000", edge.path("phone").asText());

        assertEquals(1, facilityRows());
        assertEquals(1, facilityAudits().size());
    }

    @Test
    void create_withIdempotencyKey_replays200WithTheStoredBody_andCreatesOnce() throws Exception {
        String key = clientId();
        MvcResult first = createWithKey(admin, named("Avondale Family Clinic"), key);
        assertEquals(201, first.getResponse().getStatus(), first.getResponse().getContentAsString());
        JsonNode created = json(first);
        int id = created.path("facilityId").asInt();

        // The repeat answers 200 with the stored body, even with another body and after the facility changed.
        ok(doPut(ADMIN + "/" + id, admin, named("Renamed Clinic")));
        MvcResult replay = createWithKey(admin, named("Something Else"), key);
        assertEquals(200, replay.getResponse().getStatus(), replay.getResponse().getContentAsString());
        assertEquals(created, json(replay));
        assertEquals(1, facilityRows());
        assertEquals(1, facilityAudits().stream().filter(r -> "CREATE".equals(r.get("action"))).count());

        // The same key used by another administrator is independent.
        User secondAdmin = createAndSaveUser(8, "ADM-00002", "second.admin@example.com", "ADMIN");
        MvcResult other = createWithKey(secondAdmin, named("Second Admin Clinic"), key);
        assertEquals(201, other.getResponse().getStatus(), other.getResponse().getContentAsString());
        assertNotEquals(id, json(other).path("facilityId").asInt());
        assertEquals(2, facilityRows());

        // A failed first call does not use the key up.
        String retried = clientId();
        assertError(createWithKey(admin, body(" ", null, null, null, null), retried), 400,
                "name: must not be blank", "VALIDATION_FAILED");
        MvcResult afterFailure = createWithKey(admin, named("Retried Clinic"), retried);
        assertEquals(201, afterFailure.getResponse().getStatus(), afterFailure.getResponse().getContentAsString());
        assertEquals(3, facilityRows());

        // A malformed key is refused; no key (or a blank one) simply creates.
        assertError(createWithKey(admin, named("Bad Key Clinic"), "short"), 400, KEY_FORMAT, null);
        assertError(createWithKey(admin, named("Bad Key Clinic"), "has spaces in it"), 400, KEY_FORMAT, null);
        assertEquals(3, facilityRows());
        assertEquals(201, doPost(ADMIN, admin, named("Twice")).getResponse().getStatus());
        assertEquals(201, doPost(ADMIN, admin, named("Twice")).getResponse().getStatus());
        assertEquals(201, createWithKey(admin, named("Blank Key"), "   ").getResponse().getStatus());
        assertEquals(6, facilityRows());
    }

    // ==================== UPDATE ====================

    @Test
    void update_replacesAllDetails_clearsOmittedFields_keepsState_andAudits() throws Exception {
        JsonNode created = create(named("Avondale Family Clinic"));
        int id = created.path("facilityId").asInt();
        Thread.sleep(5);

        Map<String, Object> replacement = new HashMap<>();
        replacement.put("name", " Avondale Clinic ");
        replacement.put("address", "14 King George Road, Avondale, Harare");
        replacement.put("phone", null);
        replacement.put("email", "  ");
        replacement.put("isActive", false);
        JsonNode updated = ok(doPut(ADMIN + "/" + id, admin, replacement));
        assertEquals(sorted(FACILITY_FIELDS), fieldNames(updated));
        assertEquals(id, updated.path("facilityId").asInt());
        assertEquals("Avondale Clinic", updated.path("name").asText());
        assertTrue(updated.path("type").isNull(), "omitted optional field is cleared");
        assertEquals("14 King George Road, Avondale, Harare", updated.path("address").asText());
        assertTrue(updated.path("phone").isNull());
        assertTrue(updated.path("email").isNull());
        assertTrue(updated.path("isActive").asBoolean(), "PUT never changes the active flag");
        assertEquals(created.path("createdAt").asText(), updated.path("createdAt").asText());
        assertTrue(LocalDateTime.parse(updated.path("updatedAt").asText())
                .isAfter(LocalDateTime.parse(created.path("updatedAt").asText())));
        assertEquals(updated, ok(doGet(PUBLIC + "/" + id, patient)));

        // Unchanged values still write, stamp and audit.
        Thread.sleep(5);
        JsonNode again = ok(doPut(ADMIN + "/" + id, admin, replacement));
        assertTrue(LocalDateTime.parse(again.path("updatedAt").asText())
                .isAfter(LocalDateTime.parse(updated.path("updatedAt").asText())));

        // An inactive facility can be edited and stays inactive.
        ok(doPatch(ADMIN + "/" + id + "/deactivate", admin, null));
        JsonNode inactiveEdit = ok(doPut(ADMIN + "/" + id, admin, named("Closed Clinic")));
        assertFalse(inactiveEdit.path("isActive").asBoolean());
        assertEquals("Closed Clinic", inactiveEdit.path("name").asText());

        List<Map<String, Object>> audits = facilityAudits();
        assertEquals(5, audits.size());
        assertAudit(audits.get(0), "CREATE", id, "POST", ADMIN);
        assertAudit(audits.get(1), "UPDATE", id, "PUT", ADMIN + "/" + id);
        assertAudit(audits.get(2), "UPDATE", id, "PUT", ADMIN + "/" + id);
        assertAudit(audits.get(3), "UPDATE", id, "PATCH", ADMIN + "/" + id + "/deactivate");
        assertAudit(audits.get(4), "UPDATE", id, "PUT", ADMIN + "/" + id);
    }

    @Test
    void update_errors_followTheContractOrder() throws Exception {
        int id = create("Avondale Family Clinic");
        assertError(doPut(ADMIN + "/abc", admin, named("Renamed")), 400, "Invalid value for parameter 'id'", null);
        assertError(raw(put(ADMIN + "/" + id), admin, "{not json"), 400, "Malformed request body", null);
        // Validation is checked before existence.
        MvcResult invalid = doPut(ADMIN + "/999999", admin, body("Clinic", null, null, "+263-4-7XXXXX", null));
        assertError(invalid, 400, "phone: must be a valid phone number", "VALIDATION_FAILED");
        assertEquals(List.of("phone: must be a valid phone number"), fieldErrors(invalid));
        assertError(doPut(ADMIN + "/999999", admin, named("Renamed")), 404, NOT_FOUND, null);
        assertError(doPut(ADMIN + "/0", admin, named("Renamed")), 404, NOT_FOUND, null);

        assertEquals("Avondale Family Clinic", ok(doGet(PUBLIC + "/" + id, admin)).path("name").asText());
        assertEquals(1, facilityAudits().size());
    }

    // ==================== DEACTIVATE / ACTIVATE ====================

    @Test
    void deactivation_hidesTheFacilityFromThePublicList_butNotFromTheDetailOrTheAdminList() throws Exception {
        int avondale = create("Avondale Family Clinic");
        int central = create("Central Medical Hospital");
        assertEquals(List.of("Avondale Family Clinic", "Central Medical Hospital"), names(ok(doGet(PUBLIC, patient))));
        JsonNode before = ok(doGet(PUBLIC + "/" + avondale, admin));
        Thread.sleep(5);

        JsonNode deactivated = ok(doPatch(ADMIN + "/" + avondale + "/deactivate", admin, null));
        assertEquals(sorted(FACILITY_FIELDS), fieldNames(deactivated));
        assertFalse(deactivated.path("isActive").asBoolean());
        assertTrue(LocalDateTime.parse(deactivated.path("updatedAt").asText())
                .isAfter(LocalDateTime.parse(before.path("updatedAt").asText())));
        assertEquals(Boolean.FALSE,
                jdbc.queryForObject("SELECT is_active FROM facilities WHERE facility_id = ?", Boolean.class, avondale));

        JsonNode publicList = ok(doGet(PUBLIC, patient));
        assertEquals(List.of("Central Medical Hospital"), names(publicList));
        assertEquals(1, publicList.path("totalElements").asLong());
        assertEquals(0, ok(doGet(PUBLIC + "?query=avondale", doctor)).path("totalElements").asLong());
        JsonNode detail = ok(doGet(PUBLIC + "/" + avondale, caregiver));
        assertFalse(detail.path("isActive").asBoolean());
        assertEquals(deactivated, detail);

        assertEquals(List.of("Avondale Family Clinic", "Central Medical Hospital"), names(ok(doGet(ADMIN, admin))));
        assertEquals(List.of("Avondale Family Clinic"), names(ok(doGet(ADMIN + "?active=false", admin))));
        assertEquals(List.of("Central Medical Hospital"), names(ok(doGet(ADMIN + "?active=true", admin))));
        assertEquals(2, ok(doGet(ADMIN + "?active=", admin)).path("totalElements").asLong());

        // Repeating is a silent no-op: same state, same updatedAt, no audit row.
        int audits = facilityAudits().size();
        Thread.sleep(5);
        assertEquals(deactivated, ok(doPatch(ADMIN + "/" + avondale + "/deactivate", admin, null)));
        assertEquals(deactivated, ok(doPatch(ADMIN + "/" + avondale + "/deactivate", admin, "{\"ignored\":true}")));
        assertEquals(audits, facilityAudits().size());

        // Activation brings it back; activating an active facility is a no-op too.
        JsonNode activated = ok(doPatch(ADMIN + "/" + avondale + "/activate", admin, null));
        assertTrue(activated.path("isActive").asBoolean());
        assertTrue(LocalDateTime.parse(activated.path("updatedAt").asText())
                .isAfter(LocalDateTime.parse(deactivated.path("updatedAt").asText())));
        assertEquals(List.of("Avondale Family Clinic", "Central Medical Hospital"), names(ok(doGet(PUBLIC, patient))));
        assertEquals(activated, ok(doPatch(ADMIN + "/" + avondale + "/activate", admin, null)));

        List<Map<String, Object>> rows = facilityAudits();
        assertEquals(audits + 1, rows.size());
        assertAudit(rows.get(rows.size() - 2), "UPDATE", avondale, "PATCH", ADMIN + "/" + avondale + "/deactivate");
        assertAudit(rows.get(rows.size() - 1), "UPDATE", avondale, "PATCH", ADMIN + "/" + avondale + "/activate");

        // Deactivating the last active facility is allowed: the public list is then empty.
        ok(doPatch(ADMIN + "/" + avondale + "/deactivate", admin, null));
        ok(doPatch(ADMIN + "/" + central + "/deactivate", admin, null));
        JsonNode empty = ok(doGet(PUBLIC, patient));
        assertEquals(0, empty.path("content").size());
        assertEquals(0, empty.path("totalElements").asLong());
    }

    @Test
    void activationRoutes_answer404ForUnknownIds_and400ForNonIntegerIds() throws Exception {
        create("Avondale Family Clinic");
        for (String action : List.of("deactivate", "activate")) {
            assertError(doPatch(ADMIN + "/999999/" + action, admin, null), 404, NOT_FOUND, null);
            assertError(doPatch(ADMIN + "/-1/" + action, admin, null), 404, NOT_FOUND, null);
            assertError(doPatch(ADMIN + "/abc/" + action, admin, null), 400, "Invalid value for parameter 'id'", null);
        }
        assertEquals(1, facilityAudits().size());
    }

    // ==================== READS ====================

    @Test
    void detail_answers404ForUnknownIds_and400ForNonIntegerIds() throws Exception {
        assertError(doGet(PUBLIC + "/999999", patient), 404, NOT_FOUND, null);
        assertError(doGet(PUBLIC + "/0", doctor), 404, NOT_FOUND, null);
        assertError(doGet(PUBLIC + "/-3", caregiver), 404, NOT_FOUND, null);
        assertError(doGet(PUBLIC + "/abc", admin), 400, "Invalid value for parameter 'id'", null);
    }

    @Test
    void lists_areEmptyPagesWithoutFacilities_sortedByNameIgnoringCase_andSearchNameOrAddress() throws Exception {
        JsonNode empty = ok(doGet(PUBLIC, patient));
        assertEquals(sorted(PAGE_FIELDS), fieldNames(empty));
        assertEquals(0, empty.path("content").size());
        assertEquals(0, empty.path("page").asInt());
        assertEquals(20, empty.path("size").asInt());
        assertEquals(0, empty.path("totalElements").asLong());
        assertEquals(0, empty.path("totalPages").asInt());
        assertEquals(0, ok(doGet(ADMIN, admin)).path("totalElements").asLong());

        int zeta = create("zeta Clinic");
        create(body("Alpha Hospital", "Hospital", "9 Samora Machel Avenue, Harare", null, null));
        create(body("beta Diagnostic Centre", "Diagnostic Centre", "Bulawayo", null, null));
        int zeta2 = create("Zeta Clinic");
        create(body("100% Care_Point", null, "Mutare", null, null));

        JsonNode all = ok(doGet(PUBLIC, patient));
        assertEquals(List.of("100% Care_Point", "Alpha Hospital", "beta Diagnostic Centre", "zeta Clinic", "Zeta Clinic"),
                names(all));
        assertEquals(zeta, all.path("content").get(3).path("facilityId").asInt(), "equal names: by facilityId");
        assertEquals(zeta2, all.path("content").get(4).path("facilityId").asInt());
        assertEquals(sorted(FACILITY_FIELDS), fieldNames(all.path("content").get(0)));

        // Name or address, case-insensitive, trimmed.
        assertEquals(List.of("Alpha Hospital", "zeta Clinic", "Zeta Clinic"),
                names(ok(search(PUBLIC, patient, " HARARE "))));
        assertEquals(List.of("beta Diagnostic Centre"), names(ok(doGet(PUBLIC + "?query=DiAgNoStIc", patient))));
        assertEquals(List.of("beta Diagnostic Centre"), names(ok(doGet(ADMIN + "?query=bulawayo", admin))));
        assertEquals(5, ok(search(PUBLIC, patient, "  ")).path("totalElements").asLong());
        // % and _ are literal.
        assertEquals(List.of("100% Care_Point"), names(ok(search(PUBLIC, patient, "%"))));
        assertEquals(List.of("100% Care_Point"), names(ok(doGet(PUBLIC + "?query=e_p", patient))));
        assertEquals(0, ok(doGet(PUBLIC + "?query=a_p", patient)).path("totalElements").asLong());
        assertEquals(0, ok(search(PUBLIC, patient, "\\")).path("totalElements").asLong());

        // query longer than 100 characters is a 400 on both lists; 100 is fine.
        String tooLong = "q".repeat(101);
        for (String url : List.of(PUBLIC + "?query=" + tooLong, ADMIN + "?query=" + tooLong)) {
            MvcResult result = doGet(url, admin);
            assertError(result, 400, "query: must be at most 100 characters", "VALIDATION_FAILED");
            assertEquals(List.of("query: must be at most 100 characters"), fieldErrors(result));
        }
        assertEquals(0, ok(doGet(PUBLIC + "?query=" + "q".repeat(100), patient)).path("totalElements").asLong());

        assertError(doGet(PUBLIC + "?page=x", patient), 400, "Invalid value for parameter 'page'", null);
        assertError(doGet(PUBLIC + "?size=x", patient), 400, "Invalid value for parameter 'size'", null);
        assertError(doGet(ADMIN + "?active=maybe", admin), 400, "Invalid value for parameter 'active'", null);
    }

    @Test
    void lists_clampPageAndSize_andReportTrueTotalsPastTheEnd() throws Exception {
        for (int i = 0; i < 3; i++) {
            create("Clinic " + i);
        }
        JsonNode first = ok(doGet(PUBLIC + "?page=0&size=2", patient));
        assertEquals(List.of("Clinic 0", "Clinic 1"), names(first));
        assertEquals(0, first.path("page").asInt());
        assertEquals(2, first.path("size").asInt());
        assertEquals(3, first.path("totalElements").asLong());
        assertEquals(2, first.path("totalPages").asInt());
        assertEquals(List.of("Clinic 2"), names(ok(doGet(PUBLIC + "?page=1&size=2", patient))));

        JsonNode pastEnd = ok(doGet(PUBLIC + "?page=7&size=2", patient));
        assertEquals(0, pastEnd.path("content").size());
        assertEquals(7, pastEnd.path("page").asInt());
        assertEquals(3, pastEnd.path("totalElements").asLong());
        assertEquals(2, pastEnd.path("totalPages").asInt());

        JsonNode negative = ok(doGet(PUBLIC + "?page=-4&size=0", patient));
        assertEquals(0, negative.path("page").asInt());
        assertEquals(1, negative.path("size").asInt());
        assertEquals(1, negative.path("content").size());
        assertEquals(3, negative.path("totalPages").asInt());

        assertEquals(100, ok(doGet(PUBLIC + "?size=500", patient)).path("size").asInt());
        assertEquals(1, ok(doGet(ADMIN + "?size=-5", admin)).path("size").asInt());
        assertEquals(100, ok(doGet(ADMIN + "?size=101", admin)).path("size").asInt());
        assertEquals(20, ok(doGet(ADMIN, admin)).path("size").asInt());
    }
}
