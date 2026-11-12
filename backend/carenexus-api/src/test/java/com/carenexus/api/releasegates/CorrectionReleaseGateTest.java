package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.ratelimit.RateLimitProperties;
import com.carenexus.api.corrections.CorrectionFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.corrections.CorrectionFixtures.body;
import static com.carenexus.api.corrections.CorrectionFixtures.decision;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Release gates for correction requests (be-corrections): who may file, read, resolve and assign; the record must
 * belong to the patient (404 otherwise); one OPEN request per record (409); the authoring doctor is the assignee,
 * or every active administrator is told when that doctor is inactive; notifications are sent once to the right
 * person; the 400 envelope; paging; idempotent replay; and the audit rows. Runs with committed transactions.
 */
class CorrectionReleaseGateTest extends CommittedIntegrationTest {

    private static final String BASE = "/api/correction-requests";
    private static final String ADMIN_BASE = "/api/admin/correction-requests";
    private static final String TEXT = "The test date is wrong: the sample was taken on 3 November.";
    private static final String ROLE_FORBIDDEN = "Your role is not authorized to perform this action";
    private static final String ROUTE_FORBIDDEN = "You don't have permission to access this resource";
    private static final String NO_PROXY_ACCESS = "You don't have active proxy access to this patient";
    private static final String NO_CARE_PLAN = "Your proxy access for this patient does not allow managing the care plan";
    private static final String NO_RECORDS = "Your proxy access for this patient does not allow viewing medical records";
    private static final String RECORD_NOT_FOUND = "The record to correct was not found";
    private static final String DUPLICATE = "An open correction request already exists for this record";
    private static final String ALREADY_RESOLVED = "This correction request has already been resolved";

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private RateLimitGuard rateLimitGuard;

    private CorrectionFixtures fixtures;

    @BeforeEach
    void setUpFixtures() {
        fixtures = new CorrectionFixtures(jdbc);
    }

    // ==================== HELPERS ====================

    private MvcResult file(User as, String patientSystemId, String type, Integer id) throws Exception {
        return doPost(BASE, as, body(patientSystemId, type, id, TEXT));
    }

    private int fileOk(User as, String patientSystemId, String type, Integer id) throws Exception {
        MvcResult result = file(as, patientSystemId, type, id);
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result).path("requestId").asInt();
    }

    private MvcResult resolve(int requestId, User as, String decisionValue) throws Exception {
        return doPost(BASE + "/" + requestId + "/resolve", as, decision(decisionValue, "Checked against the lab report."));
    }

    private MvcResult assign(int requestId, User as, User doctorToAssign) throws Exception {
        return doPatch(ADMIN_BASE + "/" + requestId + "/assign", as, Map.of("doctorSystemId", doctorToAssign.getSystemId()));
    }

    private List<Map<String, Object>> notifications(int requestId) {
        return jdbc.queryForList("SELECT system_id, type, category, target, title, message, action_url, dedupe_key, "
                + "patient_system_id FROM notifications WHERE resource_type = 'CORRECTION_REQUEST' AND resource_id = ? "
                + "ORDER BY notification_id", requestId);
    }

    private List<String> recipients(int requestId) {
        List<String> ids = new ArrayList<>();
        notifications(requestId).forEach(row -> ids.add((String) row.get("system_id")));
        return ids;
    }

    private int auditRows(String action, Integer resourceId) {
        Integer count = resourceId == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type = 'CORRECTION_REQUEST' "
                + "AND action = ?", Integer.class, action)
                : jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type = 'CORRECTION_REQUEST' "
                + "AND action = ? AND resource_id = ?", Integer.class, action, resourceId);
        return count == null ? 0 : count;
    }

    private int requestRows() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM record_correction_requests", Integer.class);
        return count == null ? 0 : count;
    }

    private User secondRelatedDoctor() {
        User third = createAndSaveUser(8, "DOC-00003", "third.doc@example.com", "DOCTOR");
        fixtures.addToCareTeam(patient, third);
        return third;
    }

    // ==================== FILING ====================

    @Test
    void patientFilesForOwnRecordOfEachTypeAndTheAuthorIsAssignedAndNotifiedOnce() throws Exception {
        Map<String, Integer> records = new HashMap<>();
        records.put("MEDICAL_RECORD", fixtures.medicalRecord(patient, doctor));
        records.put("LAB_RESULT", fixtures.labResult(patient, doctor));
        records.put("IMAGING_RESULT", fixtures.imagingResult(patient, doctor));
        records.put("PRESCRIPTION", fixtures.prescription(patient, doctor));
        records.put("CONSULTATION", fixtures.consultation(patient, doctor));

        for (Map.Entry<String, Integer> record : records.entrySet()) {
            // the patient may omit patientSystemId (defaults to the caller)
            MvcResult result = doPost(BASE, patient, body(null, record.getKey(), record.getValue(), "  " + TEXT + "  "));
            assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
            JsonNode created = json(result);
            int requestId = created.path("requestId").asInt();
            assertEquals(patient.getSystemId(), created.path("patientSystemId").asText());
            assertEquals("PATIENT User1", created.path("patientName").asText());
            assertEquals(patient.getSystemId(), created.path("requestedBySystemId").asText());
            assertEquals("PATIENT", created.path("requestedByRole").asText());
            assertEquals(record.getKey(), created.path("resourceType").asText());
            assertEquals(record.getValue().intValue(), created.path("resourceId").asInt());
            assertEquals(TEXT, created.path("requestedText").asText(), "text is trimmed");
            assertEquals("OPEN", created.path("status").asText());
            assertEquals(doctor.getSystemId(), created.path("assignedDoctorSystemId").asText());
            assertEquals("DOCTOR User2", created.path("assignedDoctorName").asText());
            assertTrue(created.has("resolverSystemId") && created.path("resolverSystemId").isNull(), "nulls are serialised");
            assertTrue(created.path("resolutionNote").isNull());
            assertTrue(created.path("resolvedAt").isNull());
            assertTrue(created.path("createdAt").asText().endsWith("Z"));

            List<Map<String, Object>> sent = notifications(requestId);
            assertEquals(1, sent.size(), "the assignee is notified once");
            Map<String, Object> notification = sent.get(0);
            assertEquals(doctor.getSystemId(), notification.get("system_id"));
            assertEquals("SYSTEM", notification.get("type"));
            assertEquals("SYSTEM", notification.get("category"));
            assertEquals("CORRECTIONS", notification.get("target"));
            assertEquals("New correction request", notification.get("title"));
            assertEquals("CORRECTION_CREATED:" + requestId, notification.get("dedupe_key"));
            assertEquals(patient.getSystemId(), notification.get("patient_system_id"));
            String url = (String) notification.get("action_url");
            assertTrue(url.startsWith("/dashboard/doctor?") && url.contains("section=medical-records")
                    && url.contains("tab=corrections") && url.contains("id=" + requestId), url);
            assertFalse(((String) notification.get("message")).contains("sample"), "no requested text in the message");
            assertEquals(1, auditRows("CREATE", requestId));
        }
        assertEquals(5, requestRows());
    }

    @Test
    void recordOfAnotherPatientMissingOrDeletedIs404() throws Exception {
        Integer othersLab = fixtures.labResult(otherPatient, otherDoctor);
        MvcResult result = file(patient, patient.getSystemId(), "LAB_RESULT", othersLab);
        assertEquals(404, result.getResponse().getStatus());
        assertEquals(RECORD_NOT_FOUND, message(result));

        result = file(patient, patient.getSystemId(), "PRESCRIPTION", 999_999);
        assertEquals(404, result.getResponse().getStatus());
        assertEquals(RECORD_NOT_FOUND, message(result));

        Integer record = fixtures.medicalRecord(patient, doctor);
        fixtures.softDeleteMedicalRecord(record);
        result = file(patient, patient.getSystemId(), "MEDICAL_RECORD", record);
        assertEquals(404, result.getResponse().getStatus());
        assertEquals(RECORD_NOT_FOUND, message(result));

        // a lab result id used with another type does not match either
        Integer lab = fixtures.labResult(patient, doctor);
        result = file(patient, patient.getSystemId(), "IMAGING_RESULT", lab + 1000);
        assertEquals(404, result.getResponse().getStatus());
        assertEquals(0, requestRows());
    }

    @Test
    void onlyPatientsAndPermittedCaregiversMayFile() throws Exception {
        Integer lab = fixtures.labResult(patient, doctor);

        MvcResult result = file(doctor, patient.getSystemId(), "LAB_RESULT", lab);
        assertEquals(403, result.getResponse().getStatus());
        assertEquals(ROLE_FORBIDDEN, message(result));
        result = file(admin, patient.getSystemId(), "LAB_RESULT", lab);
        assertEquals(403, result.getResponse().getStatus());
        assertEquals(ROLE_FORBIDDEN, message(result));

        result = file(otherPatient, patient.getSystemId(), "LAB_RESULT", lab);
        assertEquals(403, result.getResponse().getStatus());
        assertEquals("Patients can only access their own data", message(result));

        // caregiver: no grant, flag off, VIEW_ONLY, expired, then allowed
        result = file(caregiver, patient.getSystemId(), "LAB_RESULT", lab);
        assertEquals(403, result.getResponse().getStatus());
        assertEquals(NO_PROXY_ACCESS, message(result));
        assertEquals("CARE_PLAN", json(result).path("resourceType").asText());

        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        result = file(caregiver, patient.getSystemId(), "LAB_RESULT", lab);
        assertEquals(403, result.getResponse().getStatus());
        assertEquals(NO_CARE_PLAN, message(result));

        grantAccess(caregiver, patient, a -> {
            a.setCanManageCarePlan(true);
            a.setAccessLevel("VIEW_ONLY");
        });
        result = file(caregiver, patient.getSystemId(), "LAB_RESULT", lab);
        assertEquals(403, result.getResponse().getStatus());
        assertEquals(NO_CARE_PLAN, message(result));

        grantAccess(caregiver, patient, a -> {
            a.setCanManageCarePlan(true);
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
        });
        result = file(caregiver, patient.getSystemId(), "LAB_RESULT", lab);
        assertEquals(403, result.getResponse().getStatus());
        assertEquals(NO_PROXY_ACCESS, message(result));

        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        result = doPost(BASE, caregiver, body(null, "LAB_RESULT", lab, TEXT));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("patientSystemId: is required when acting for a dependent", message(result));
        assertEquals("VALIDATION_FAILED", json(result).path("code").asText());

        result = file(caregiver, patient.getSystemId(), "LAB_RESULT", lab);
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode created = json(result);
        assertEquals(caregiver.getSystemId(), created.path("requestedBySystemId").asText());
        assertEquals("CAREGIVER", created.path("requestedByRole").asText());
        assertEquals("CAREGIVER User3", created.path("requestedByName").asText());
        int requestId = created.path("requestId").asInt();
        assertEquals(List.of(doctor.getSystemId()), recipients(requestId), "the patient is not notified");

        // the caregiver may file (MANAGE_CARE_PLAN) but not list or read without RECORDS
        result = doGet(BASE + "/patient/" + patient.getSystemId(), caregiver);
        assertEquals(403, result.getResponse().getStatus());
        assertEquals(NO_RECORDS, message(result));
        result = doGet(BASE + "/" + requestId, caregiver);
        assertEquals(403, result.getResponse().getStatus());
        assertEquals(NO_RECORDS, message(result));
        assertEquals(1, requestRows());
    }

    @Test
    void oneOpenRequestPerRecordAndANewOneAfterResolution() throws Exception {
        Integer prescription = fixtures.prescription(patient, doctor);
        int first = fileOk(patient, patient.getSystemId(), "PRESCRIPTION", prescription);

        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        MvcResult duplicate = file(caregiver, patient.getSystemId(), "PRESCRIPTION", prescription);
        assertEquals(409, duplicate.getResponse().getStatus());
        assertEquals(DUPLICATE, message(duplicate));

        assertEquals(200, resolve(first, doctor, "ACCEPTED").getResponse().getStatus());
        int second = fileOk(patient, patient.getSystemId(), "PRESCRIPTION", prescription);
        assertTrue(second > first);
        assertEquals(2, requestRows());
    }

    @Test
    void invalidBodiesGiveTheValidationEnvelope() throws Exception {
        Integer lab = fixtures.labResult(patient, doctor);

        MvcResult result = doPost(BASE, patient, body(patient.getSystemId(), null, lab, TEXT));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("resourceType: is required", message(result));
        assertEquals("VALIDATION_FAILED", json(result).path("code").asText());
        assertEquals("resourceType", json(result).path("errors").get(0).path("field").asText());

        result = doPost(BASE, patient, body(patient.getSystemId(), "PROFILE", lab, TEXT));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("resourceType: must be one of MEDICAL_RECORD, LAB_RESULT, IMAGING_RESULT, PRESCRIPTION, CONSULTATION",
                message(result));

        result = doPost(BASE, patient, body(patient.getSystemId(), "LAB_RESULT", 0, TEXT));
        assertEquals("resourceId: must be a positive number", message(result));

        result = doPost(BASE, patient, body(patient.getSystemId(), "LAB_RESULT", null, TEXT));
        assertEquals("resourceId: is required", message(result));

        result = doPost(BASE, patient, body(patient.getSystemId(), "LAB_RESULT", lab, "   "));
        assertEquals("requestedText: is required", message(result));

        result = doPost(BASE, patient, body(patient.getSystemId(), "LAB_RESULT", lab, "x".repeat(2001)));
        assertEquals("requestedText: must be at most 2000 characters", message(result));

        result = doPost(BASE, patient, body("patient-1", "LAB_RESULT", lab, TEXT));
        assertEquals("patientSystemId: must be a valid system id", message(result));

        result = doPostWithHeaders(BASE, patient, body(patient.getSystemId(), "LAB_RESULT", lab, TEXT),
                Map.of("Idempotency-Key", "bad key!"));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'", message(result));

        int requestId = fileOk(patient, patient.getSystemId(), "LAB_RESULT", lab);
        result = doPost(BASE + "/" + requestId + "/resolve", doctor, decision("MAYBE", "x"));
        assertEquals("decision: must be ACCEPTED or REJECTED", message(result));
        result = doPost(BASE + "/" + requestId + "/resolve", doctor, decision("ACCEPTED", " "));
        assertEquals("note: is required", message(result));
        result = doPost(BASE + "/" + requestId + "/resolve", doctor, decision("REJECTED", "n".repeat(1001)));
        assertEquals("note: must be at most 1000 characters", message(result));
        result = doPost(BASE + "/abc/resolve", doctor, decision("REJECTED", "ok"));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("Invalid value for parameter 'id'", message(result));

        result = doGet(BASE + "/patient/not-an-id", patient);
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("patientSystemId: must be a valid system id", message(result));
        result = doGet(BASE + "/patient/" + patient.getSystemId() + "?status=closed", patient);
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("status: must be one of OPEN, ACCEPTED, REJECTED", message(result));
        assertEquals(1, requestRows());
    }

    // ==================== RESOLVING ====================

    @Test
    void onlyTheAssigneeOrAnAdminResolvesAndTheRequesterIsNotifiedOnce() throws Exception {
        Integer lab = fixtures.labResult(patient, doctor);
        int requestId = fileOk(patient, patient.getSystemId(), "LAB_RESULT", lab);
        User related = secondRelatedDoctor();

        MvcResult result = resolve(requestId, related, "ACCEPTED");
        assertEquals(403, result.getResponse().getStatus());
        assertEquals("Only the assigned doctor or an administrator can resolve this request", message(result));
        result = resolve(requestId, otherDoctor, "ACCEPTED");
        assertEquals(403, result.getResponse().getStatus());
        result = resolve(requestId, patient, "ACCEPTED");
        assertEquals(403, result.getResponse().getStatus());
        assertEquals(ROLE_FORBIDDEN, message(result));
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        result = resolve(requestId, caregiver, "ACCEPTED");
        assertEquals(403, result.getResponse().getStatus());
        assertEquals(ROLE_FORBIDDEN, message(result));
        result = resolve(999_999, doctor, "ACCEPTED");
        assertEquals(404, result.getResponse().getStatus());
        assertEquals("Correction request not found", message(result));
        Integer denials = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCESS_DENIED' "
                + "AND resource_type = 'CORRECTION_REQUEST' AND resource_id = ?", Integer.class, requestId);
        assertEquals(2, denials, "both non-assignee doctors are audited");

        result = resolve(requestId, doctor, "REJECTED");
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode resolved = json(result);
        assertEquals("REJECTED", resolved.path("status").asText());
        assertEquals(doctor.getSystemId(), resolved.path("resolverSystemId").asText());
        assertEquals("DOCTOR User2", resolved.path("resolverName").asText());
        assertEquals("Checked against the lab report.", resolved.path("resolutionNote").asText());
        assertFalse(resolved.path("resolvedAt").isNull());
        assertEquals(doctor.getSystemId(), resolved.path("assignedDoctorSystemId").asText(), "assignee kept");

        List<Map<String, Object>> sent = notifications(requestId);
        assertEquals(List.of(doctor.getSystemId(), patient.getSystemId()), recipients(requestId));
        Map<String, Object> toPatient = sent.get(1);
        assertEquals("Correction request rejected", toPatient.get("title"));
        assertEquals("CORRECTION_RESOLVED:" + requestId, toPatient.get("dedupe_key"));
        String url = (String) toPatient.get("action_url");
        assertTrue(url.startsWith("/dashboard/patient?") && url.contains("section=medical-records")
                && url.contains("tab=corrections") && url.contains("id=" + requestId), url);
        assertFalse(((String) toPatient.get("message")).contains("lab report"), "no note in the message");
        assertEquals(1, auditRows("UPDATE", requestId));

        // terminal: the same or another decision is refused, and nothing more is sent
        result = resolve(requestId, doctor, "REJECTED");
        assertEquals(409, result.getResponse().getStatus());
        assertEquals(ALREADY_RESOLVED, message(result));
        result = resolve(requestId, admin, "ACCEPTED");
        assertEquals(409, result.getResponse().getStatus());
        assertEquals(2, notifications(requestId).size());
    }

    @Test
    void caregiverRequesterIsNotifiedOnResolutionOnlyWhileHoldingRecordsAccess() throws Exception {
        grantAccess(caregiver, patient, a -> {
            a.setCanManageCarePlan(true);
            a.setCanViewRecords(true);
        });
        int withRecords = fileOk(caregiver, patient.getSystemId(), "IMAGING_RESULT", fixtures.imagingResult(patient, doctor));
        assertEquals(200, resolve(withRecords, doctor, "ACCEPTED").getResponse().getStatus());
        List<Map<String, Object>> sent = notifications(withRecords);
        assertEquals(List.of(doctor.getSystemId(), caregiver.getSystemId()), recipients(withRecords));
        String url = (String) sent.get(1).get("action_url");
        assertTrue(url.startsWith("/dashboard/proxy?") && url.contains("section=documents-results")
                && url.contains("tab=corrections") && url.contains("patient=" + patient.getSystemId())
                && url.contains("id=" + withRecords), url);
        assertEquals("Correction request accepted", sent.get(1).get("title"));

        // the caregiver loses RECORDS: the patient is told instead
        int withoutRecords = fileOk(caregiver, patient.getSystemId(), "CONSULTATION", fixtures.consultation(patient, doctor));
        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        assertEquals(200, resolve(withoutRecords, doctor, "REJECTED").getResponse().getStatus());
        assertEquals(List.of(doctor.getSystemId(), patient.getSystemId()), recipients(withoutRecords));
    }

    // ==================== UNASSIGNED AND ADMIN ====================

    @Test
    void unassignedRequestReachesAdministratorsWhoAssignAndResolve() throws Exception {
        User secondAdmin = createAndSaveUser(9, "ADM-00002", "admin2@example.com", "ADMIN");
        User inactiveAdmin = createAndSaveUser(10, "ADM-00003", "admin3@example.com", "ADMIN");
        fixtures.deactivate(inactiveAdmin);
        Integer record = fixtures.medicalRecord(patient, doctor);
        fixtures.deactivate(doctor);
        User related = secondRelatedDoctor();

        int requestId = fileOk(patient, patient.getSystemId(), "MEDICAL_RECORD", record);
        JsonNode created = json(doGet(BASE + "/" + requestId, patient));
        assertTrue(created.path("assignedDoctorSystemId").isNull());
        assertTrue(created.path("assignedDoctorName").isNull());

        List<Map<String, Object>> sent = notifications(requestId);
        assertEquals(List.of(admin.getSystemId(), secondAdmin.getSystemId()), recipients(requestId),
                "every active administrator, once");
        for (Map<String, Object> notification : sent) {
            assertEquals("Correction request needs a doctor", notification.get("title"));
            assertEquals("CORRECTION_UNASSIGNED:" + requestId, notification.get("dedupe_key"));
            String url = (String) notification.get("action_url");
            assertTrue(url.startsWith("/dashboard/admin?") && url.contains("section=corrections")
                    && url.contains("id=" + requestId), url);
        }

        // the unassigned queue
        JsonNode queue = json(doGet(ADMIN_BASE + "?status=open&unassigned=true&size=1", admin));
        assertEquals(1, queue.path("totalElements").asInt());
        assertEquals(requestId, queue.path("content").get(0).path("requestId").asInt());

        // assignable doctors: the inactive author is not offered
        MvcResult result = doGet(ADMIN_BASE + "/" + requestId + "/assignable-doctors", admin);
        assertEquals(200, result.getResponse().getStatus());
        JsonNode doctors = json(result);
        assertEquals(1, doctors.size());
        assertEquals(related.getSystemId(), doctors.get(0).path("doctorSystemId").asText());
        assertEquals("DOCTOR User8", doctors.get(0).path("doctorName").asText());
        assertEquals("General Practice", doctors.get(0).path("specialization").asText());
        assertFalse(doctors.get(0).path("currentAssignee").asBoolean());

        // assigning an unrelated or inactive doctor is refused
        result = assign(requestId, admin, otherDoctor);
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("The doctor must be an active doctor with a care relationship with this patient", message(result));
        result = assign(requestId, admin, doctor);
        assertEquals(400, result.getResponse().getStatus());
        result = doPatch(ADMIN_BASE + "/" + requestId + "/assign", admin, Map.of("doctorSystemId", ""));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("doctorSystemId: is required", message(result));

        result = assign(requestId, admin, related);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(related.getSystemId(), json(result).path("assignedDoctorSystemId").asText());
        assertEquals("OPEN", json(result).path("status").asText());
        assertEquals(1, auditRows("UPDATE", requestId));

        // a no-op reassignment: same state, no second notification, no audit row
        result = assign(requestId, admin, related);
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(1, auditRows("UPDATE", requestId));
        List<String> afterAssign = recipients(requestId);
        assertEquals(List.of(admin.getSystemId(), secondAdmin.getSystemId(), related.getSystemId()), afterAssign);
        Map<String, Object> assigned = notifications(requestId).get(2);
        assertEquals("Correction request assigned to you", assigned.get("title"));
        assertEquals("CORRECTION_ASSIGNED:" + requestId + ":" + related.getSystemId(), assigned.get("dedupe_key"));

        assertTrue(json(doGet(ADMIN_BASE + "/" + requestId + "/assignable-doctors", admin))
                .get(0).path("currentAssignee").asBoolean());
        assertEquals(0, json(doGet(ADMIN_BASE + "?status=OPEN&unassigned=true", admin)).path("totalElements").asInt());
        assertEquals(1, json(doGet(BASE + "/assigned?status=OPEN", related)).path("totalElements").asInt());

        // the administrator resolves; the requester (the patient) is told
        result = resolve(requestId, admin, "ACCEPTED");
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(admin.getSystemId(), json(result).path("resolverSystemId").asText());
        assertEquals(patient.getSystemId(), recipients(requestId).get(3));
        assertEquals(4, notifications(requestId).size());

        result = assign(requestId, admin, related);
        assertEquals(409, result.getResponse().getStatus());
        assertEquals(ALREADY_RESOLVED, message(result));
    }

    @Test
    void adminRoutesAreForAdministratorsOnly() throws Exception {
        int requestId = fileOk(patient, patient.getSystemId(), "LAB_RESULT", fixtures.labResult(patient, doctor));
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        for (User user : List.of(patient, doctor, caregiver)) {
            MvcResult result = doGet(ADMIN_BASE, user);
            assertEquals(403, result.getResponse().getStatus());
            assertEquals(ROUTE_FORBIDDEN, message(result));
            result = assign(requestId, user, doctor);
            assertEquals(403, result.getResponse().getStatus());
            result = doGet(ADMIN_BASE + "/" + requestId + "/assignable-doctors", user);
            assertEquals(403, result.getResponse().getStatus());
        }
        assertEquals(401, mockMvc.perform(get(ADMIN_BASE)).andReturn().getResponse().getStatus());
        assertEquals(401, mockMvc.perform(get(BASE + "/" + requestId)).andReturn().getResponse().getStatus());

        assertEquals(404, doGet(ADMIN_BASE + "/999999/assignable-doctors", admin).getResponse().getStatus());
        assertEquals(404, assign(999_999, admin, doctor).getResponse().getStatus());
        assertEquals(400, doGet(ADMIN_BASE + "?unassigned=perhaps", admin).getResponse().getStatus());
        assertEquals("Invalid value for parameter 'unassigned'", message(doGet(ADMIN_BASE + "?unassigned=perhaps", admin)));
        assertEquals("status: must be one of OPEN, ACCEPTED, REJECTED", message(doGet(ADMIN_BASE + "?status=x", admin)));

        // the doctor queue is for doctors only
        MvcResult result = doGet(BASE + "/assigned", admin);
        assertEquals(403, result.getResponse().getStatus());
        assertEquals(ROLE_FORBIDDEN, message(result));
        assertEquals(403, doGet(BASE + "/assigned", patient).getResponse().getStatus());
    }

    // ==================== READING ====================

    @Test
    void readingFollowsRecordsAccessAndTheAssignee() throws Exception {
        int requestId = fileOk(patient, patient.getSystemId(), "LAB_RESULT", fixtures.labResult(patient, doctor));
        String one = BASE + "/" + requestId;
        String list = BASE + "/patient/" + patient.getSystemId();

        assertEquals(200, doGet(one, patient).getResponse().getStatus());
        assertEquals(200, doGet(one, doctor).getResponse().getStatus());
        assertEquals(200, doGet(one, admin).getResponse().getStatus());
        assertEquals(200, doGet(list, doctor).getResponse().getStatus());
        assertEquals(200, doGet(list, admin).getResponse().getStatus());

        MvcResult result = doGet(one, otherPatient);
        assertEquals(403, result.getResponse().getStatus());
        assertEquals("Patients can only access their own data", message(result));
        result = doGet(one, otherDoctor);
        assertEquals(403, result.getResponse().getStatus());
        assertEquals("You are not part of this patient's care team", message(result));
        assertEquals(403, doGet(list, otherDoctor).getResponse().getStatus());
        assertEquals(403, doGet(list, otherPatient).getResponse().getStatus());
        result = doGet(one, otherCaregiver);
        assertEquals(403, result.getResponse().getStatus());
        assertEquals(NO_PROXY_ACCESS, message(result));

        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertEquals(200, doGet(one, caregiver).getResponse().getStatus());
        assertEquals(200, doGet(list, caregiver).getResponse().getStatus());
        grantAccess(caregiver, patient, a -> {
            a.setCanViewRecords(true);
            a.setExpiresAt(LocalDateTime.now().minusHours(1));
        });
        result = doGet(list, caregiver);
        assertEquals(403, result.getResponse().getStatus());
        assertEquals(NO_PROXY_ACCESS, message(result));

        assertEquals(404, doGet(BASE + "/999999", patient).getResponse().getStatus());
        assertEquals("Correction request not found", message(doGet(BASE + "/999999", patient)));
        assertEquals(0, json(doGet(BASE + "/patient/PAT-99999", admin)).path("totalElements").asInt());
        assertTrue(auditRows("READ", requestId) >= 3, "single reads are audited");
    }

    @Test
    void listsArePagedNewestFirstAndClamped() throws Exception {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(fileOk(patient, patient.getSystemId(), "LAB_RESULT", fixtures.labResult(patient, doctor)));
        }
        assertEquals(200, resolve(ids.get(0), doctor, "ACCEPTED").getResponse().getStatus());

        JsonNode page = json(doGet(BASE + "/patient/" + patient.getSystemId() + "?size=2", patient));
        assertEquals(3, page.path("totalElements").asInt());
        assertEquals(2, page.path("totalPages").asInt());
        assertEquals(0, page.path("page").asInt());
        assertEquals(2, page.path("size").asInt());
        assertEquals(ids.get(2).intValue(), page.path("content").get(0).path("requestId").asInt(), "newest first");
        assertEquals(ids.get(1).intValue(), page.path("content").get(1).path("requestId").asInt());

        JsonNode second = json(doGet(BASE + "/patient/" + patient.getSystemId() + "?size=2&page=1", patient));
        assertEquals(1, second.path("content").size());
        assertEquals(ids.get(0).intValue(), second.path("content").get(0).path("requestId").asInt());

        JsonNode clamped = json(doGet(BASE + "/patient/" + patient.getSystemId() + "?size=500&page=-3", patient));
        assertEquals(100, clamped.path("size").asInt());
        assertEquals(0, clamped.path("page").asInt());
        assertEquals(1, json(doGet(BASE + "/patient/" + patient.getSystemId() + "?size=0", patient)).path("size").asInt());

        JsonNode open = json(doGet(BASE + "/patient/" + patient.getSystemId() + "?status=open", patient));
        assertEquals(2, open.path("totalElements").asInt());
        JsonNode accepted = json(doGet(BASE + "/patient/" + patient.getSystemId() + "?status=ACCEPTED", patient));
        assertEquals(1, accepted.path("totalElements").asInt());

        JsonNode queue = json(doGet(BASE + "/assigned?status=OPEN", doctor));
        assertEquals(2, queue.path("totalElements").asInt());
        assertEquals(3, json(doGet(BASE + "/assigned", doctor)).path("totalElements").asInt());
        assertEquals(0, json(doGet(BASE + "/assigned", otherDoctor)).path("totalElements").asInt());
        assertEquals(3, json(doGet(ADMIN_BASE, admin)).path("totalElements").asInt());

        Integer listReads = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type = 'CORRECTION_REQUEST' "
                + "AND action = 'READ' AND resource_id IS NULL AND patient_system_id = ?", Integer.class, patient.getSystemId());
        assertTrue(listReads != null && listReads >= 5, "list pages are audited");
    }

    // ==================== IDEMPOTENCY ====================

    @Test
    void idempotentReplayReturnsTheFirstBodyAndCreatesNothing() throws Exception {
        Integer lab = fixtures.labResult(patient, doctor);
        Map<String, String> headers = Map.of("Idempotency-Key", "corr-" + clientId().substring(0, 20));
        Map<String, Object> body = body(patient.getSystemId(), "LAB_RESULT", lab, TEXT);

        MvcResult first = doPostWithHeaders(BASE, patient, body, headers);
        assertEquals(201, first.getResponse().getStatus(), first.getResponse().getContentAsString());
        MvcResult replay = doPostWithHeaders(BASE, patient, body, headers);
        assertEquals(200, replay.getResponse().getStatus(), replay.getResponse().getContentAsString());
        int requestId = json(first).path("requestId").asInt();
        assertEquals(requestId, json(replay).path("requestId").asInt());
        assertEquals(json(first).path("createdAt").asText(), json(replay).path("createdAt").asText());

        assertEquals(1, requestRows());
        assertEquals(1, notifications(requestId).size(), "the replay sends nothing");
        assertEquals(1, auditRows("CREATE", requestId));

        // a different key is a new attempt: the record already has an OPEN request
        MvcResult other = doPostWithHeaders(BASE, patient, body, Map.of("Idempotency-Key", "corr-" + clientId().substring(0, 20)));
        assertEquals(409, other.getResponse().getStatus());
        assertEquals(DUPLICATE, message(other));
    }

    // ==================== RATE LIMIT ====================

    @Test
    void filingIsRateLimitedPerCallerAndReplaysDoNotCount() throws Exception {
        RateLimitProperties.Bucket small = new RateLimitProperties.Bucket();
        small.setLimit(2);
        small.setWindowSeconds(86400L);
        RateLimitProperties.Bucket previous = rateLimitProperties.getBuckets().put("correction-request", small);
        rateLimitProperties.setEnabled(true);
        try {
            Map<String, String> headers = Map.of("Idempotency-Key", "corr-rate-" + clientId().substring(0, 12));
            Map<String, Object> first = body(patient.getSystemId(), "LAB_RESULT", fixtures.labResult(patient, doctor), TEXT);

            // a 404 counts, a 201 counts
            assertEquals(404, file(patient, patient.getSystemId(), "LAB_RESULT", 999_999).getResponse().getStatus());
            assertEquals(201, doPostWithHeaders(BASE, patient, first, headers).getResponse().getStatus());

            MvcResult limited = file(patient, patient.getSystemId(), "PRESCRIPTION", fixtures.prescription(patient, doctor));
            assertEquals(429, limited.getResponse().getStatus(), limited.getResponse().getContentAsString());
            assertEquals("RATE_LIMITED", json(limited).path("code").asText());
            assertTrue(Long.parseLong(limited.getResponse().getHeader("Retry-After")) >= 1);

            // a replay of the completed call is still answered and counts nothing
            assertEquals(200, doPostWithHeaders(BASE, patient, first, headers).getResponse().getStatus());
            // another caller has their own allowance
            grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
            assertEquals(201, file(caregiver, patient.getSystemId(), "IMAGING_RESULT",
                    fixtures.imagingResult(patient, doctor)).getResponse().getStatus());
            assertEquals(2, requestRows());
        } finally {
            rateLimitProperties.setEnabled(false);
            if (previous != null) {
                rateLimitProperties.getBuckets().put("correction-request", previous);
            } else {
                rateLimitProperties.getBuckets().remove("correction-request");
            }
            rateLimitGuard.reset("correction-request", patient.getSystemId());
            rateLimitGuard.reset("correction-request", caregiver.getSystemId());
        }
    }
}
