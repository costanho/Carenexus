package com.carenexus.api.health;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.event.SymptomLoggedEvent;
import com.carenexus.api.core.model.CareTeam;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * POST / GET / DELETE /api/symptom-logs (CONTRACT be-health-tracking 5.6 - 5.9): sources by author, the care-team
 * notification of SEVERE and CRITICAL logs, the access matrix, validation, paging and filters, soft delete by the
 * author within 24 hours, idempotency, events and audit.
 */
@RecordApplicationEvents
class SymptomLogApiTest extends HealthApiTestBase {

    @Autowired
    private ApplicationEvents events;

    private List<SymptomLoggedEvent> loggedEvents() {
        return events.stream(SymptomLoggedEvent.class).toList();
    }

    private int notificationsFor(User recipient) {
        return count("SELECT COUNT(*) FROM notifications WHERE system_id = ? AND type = 'SYSTEM'", recipient.getSystemId());
    }

    /** A second doctor on the patient's care team. */
    private User secondDoctor() {
        User second = createAndSaveUser(8, "DOC-00003", "second.doc@example.com", "DOCTOR");
        careTeamRepository.saveAndFlush(CareTeam.builder()
                .patientSystemId(patient.getSystemId())
                .providerSystemId(second.getSystemId())
                .role("SPECIALIST")
                .assignedAt(LocalDateTime.now())
                .isActive(true)
                .build());
        return second;
    }

    private void backdate(int symptomId, LocalDateTime loggedAt) {
        jdbc.update("UPDATE symptom_logs SET logged_at = ? WHERE symptom_id = ?", loggedAt, symptomId);
    }

    // ==================== CREATE ====================

    @Test
    void patientLogsSevereSymptoms_careTeamIsNotified() throws Exception {
        Map<String, Object> body = symptomLog(null, " severe ", "  Headache ", "Blurred vision", "Headache");
        body.put("durationDays", 2);
        body.put("notes", " Worse in the evening ");
        body.put("source", "DOCTOR_ENTERED"); // unknown to the request: ignored
        body.put("loggedAt", "2020-01-01T00:00:00"); // set by the server only

        LocalDateTime before = LocalDateTime.now().withNano(0);
        JsonNode log = logOk(patient, body);

        int id = log.path("symptomId").asInt();
        assertEquals(patient.getSystemId(), log.path("patientSystemId").asText());
        List<String> symptoms = new ArrayList<>();
        log.path("symptoms").forEach(s -> symptoms.add(s.asText()));
        assertEquals(List.of("Headache", "Blurred vision", "Headache"), symptoms, "trimmed, in order, duplicates kept");
        assertEquals("SEVERE", log.path("severity").asText());
        assertEquals(2, log.path("durationDays").asInt());
        assertEquals("Worse in the evening", log.path("notes").asText());
        assertEquals("PATIENT_REPORTED", log.path("source").asText());
        LocalDateTime loggedAt = LocalDateTime.parse(log.path("loggedAt").asText());
        assertFalse(loggedAt.isBefore(before));
        assertEquals(patient.getSystemId(), log.path("loggedBySystemId").asText());
        assertEquals(nameOf(patient), log.path("loggedByName").asText());
        assertTrue(log.path("canDelete").asBoolean());

        assertEquals(1, auditRows("CREATE", "SYMPTOM_LOG", patient.getSystemId()));
        List<SymptomLoggedEvent> published = loggedEvents();
        assertEquals(1, published.size());
        assertEquals(new SymptomLoggedEvent(id, patient.getSystemId(), patient.getSystemId(), "SEVERE"), published.get(0));

        Map<String, Object> notification = jdbc.queryForMap(
                "SELECT type, category, priority, title, message, target, action_url, resource_type, resource_id, "
                        + "dedupe_key, patient_system_id, actor_system_id FROM notifications WHERE system_id = ? AND type = 'SYSTEM'",
                doctor.getSystemId());
        assertEquals("SYSTEM", notification.get("type"));
        assertEquals("CRITICAL", notification.get("category"));
        assertEquals("HIGH", notification.get("priority"));
        assertEquals("Severe symptoms logged", notification.get("title"));
        assertEquals("Severe symptoms were logged for " + nameOf(patient) + ". Please review the symptom log.",
                notification.get("message"));
        assertEquals("HEALTH_TRACKING", notification.get("target"));
        assertEquals("/dashboard/doctor?section=patients&patient=" + patient.getSystemId() + "&id=" + id + "&tab=vitals",
                notification.get("action_url"));
        assertEquals("SYMPTOM_LOG", notification.get("resource_type"));
        assertEquals(id, ((Number) notification.get("resource_id")).intValue());
        assertEquals("SYMPTOM_LOGGED:" + id, notification.get("dedupe_key"));
        assertEquals(patient.getSystemId(), notification.get("patient_system_id"));
        assertEquals(patient.getSystemId(), notification.get("actor_system_id"));
        assertFalse(((String) notification.get("message")).contains("Headache"), "no symptom text in the notification");

        assertEquals(0, notificationsFor(patient), "the patient is never notified");
        assertEquals(0, notificationsFor(otherDoctor), "an unrelated doctor is never notified");
        assertEquals(0, notificationsFor(caregiver));
    }

    @Test
    void doctorLogsCriticalSymptoms_otherRelatedDoctorsAreNotifiedButNotTheAuthor() throws Exception {
        User second = secondDoctor();
        JsonNode log = logOk(doctor, symptomLog(patient.getSystemId(), "CRITICAL", "Chest pain"));

        assertEquals("DOCTOR_ENTERED", log.path("source").asText());
        assertEquals(doctor.getSystemId(), log.path("loggedBySystemId").asText());
        assertEquals(nameOf(doctor), log.path("loggedByName").asText());
        assertTrue(log.path("durationDays").isNull());
        assertTrue(log.path("notes").isNull());

        assertEquals(0, notificationsFor(doctor), "the author is not notified");
        Map<String, Object> notification = jdbc.queryForMap(
                "SELECT priority, title, message FROM notifications WHERE system_id = ? AND type = 'SYSTEM'",
                second.getSystemId());
        assertEquals("CRITICAL", notification.get("priority"));
        assertEquals("Critical symptoms logged", notification.get("title"));
        assertEquals("Critical symptoms were logged for " + nameOf(patient) + ". Please review the symptom log.",
                notification.get("message"));
        assertEquals(1, loggedEvents().size());
        assertEquals("CRITICAL", loggedEvents().get(0).severity());
    }

    @Test
    void mildAndModerateLogsNotifyNobody() throws Exception {
        logOk(patient, symptomLog(null, "MILD", "Runny nose"));
        logOk(patient, symptomLog(null, "moderate", "Cough"));
        assertEquals(0, count("SELECT COUNT(*) FROM notifications"));
        assertEquals(List.of("MILD", "MODERATE"), loggedEvents().stream().map(SymptomLoggedEvent::severity).toList());
    }

    @Test
    void writeAccessMatrix() throws Exception {
        Map<String, Object> body = symptomLog(patient.getSystemId(), "MILD", "Headache");

        assertEquals(201, doPost(SYMPTOMS, patient, body).getResponse().getStatus());
        assertError(doPost(SYMPTOMS, otherPatient, body), 403, "Patients can only access their own data");
        assertError(doPost(SYMPTOMS, otherDoctor, body), 403, "You are not part of this patient's care team");
        assertError(doPost(SYMPTOMS, caregiver, body), 403, "You don't have active proxy access to this patient");
        assertError(doPost(SYMPTOMS, admin, body), 403, "Your role is not authorized to perform this action");
        assertRuleError(doPost(SYMPTOMS, doctor, symptomLog(null, "MILD", "Headache")), "patientSystemId is required");

        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        MvcResult readOnly = doPost(SYMPTOMS, caregiver, body);
        assertError(readOnly, 403, "Your proxy access for this patient does not allow recording health readings");
        assertEquals("HEALTH_METRIC", json(readOnly).path("resourceType").asText());
        grantAccess(caregiver, patient, a -> {
            a.setCanManageCarePlan(true);
            a.setAccessLevel("VIEW_ONLY");
        });
        assertError(doPost(SYMPTOMS, caregiver, body), 403,
                "Your proxy access for this patient does not allow recording health readings");
        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        JsonNode byCaregiver = logOk(caregiver, body);
        assertEquals("PATIENT_REPORTED", byCaregiver.path("source").asText());
        assertEquals(caregiver.getSystemId(), byCaregiver.path("loggedBySystemId").asText());

        assertEquals(2, count("SELECT COUNT(*) FROM symptom_logs WHERE patient_system_id = ?", patient.getSystemId()));
        assertEquals(2, loggedEvents().size());
    }

    // ==================== VALIDATION ====================

    @Test
    void fieldValidation() throws Exception {
        Map<String, Object> noSymptoms = new LinkedHashMap<>();
        noSymptoms.put("severity", "MILD");
        assertFieldError(doPost(SYMPTOMS, patient, noSymptoms), "symptoms", "must contain 1 to 20 symptoms");
        assertFieldError(doPost(SYMPTOMS, patient, symptomLog(null, "MILD")), "symptoms", "must contain 1 to 20 symptoms");
        String[] many = Collections.nCopies(21, "Ache").toArray(String[]::new);
        assertFieldError(doPost(SYMPTOMS, patient, symptomLog(null, "MILD", many)), "symptoms",
                "must contain 1 to 20 symptoms");
        assertFieldError(doPost(SYMPTOMS, patient, symptomLog(null, "MILD", "Cough", "  ")), "symptoms[1]",
                "must not be blank");
        assertFieldError(doPost(SYMPTOMS, patient, symptomLog(null, "MILD", "x".repeat(101))), "symptoms[0]",
                "size must be between 0 and 100");
        assertFieldError(doPost(SYMPTOMS, patient, symptomLog(null, null, "Cough")), "severity", "must not be blank");
        assertFieldError(doPost(SYMPTOMS, patient, symptomLog(null, " ", "Cough")), "severity", "must not be blank");
        assertFieldError(doPost(SYMPTOMS, patient, symptomLog(null, "URGENT", "Cough")), "severity",
                "must be one of MILD, MODERATE, SEVERE, CRITICAL");

        Map<String, Object> negative = symptomLog(null, "MILD", "Cough");
        negative.put("durationDays", -1);
        assertFieldError(doPost(SYMPTOMS, patient, negative), "durationDays", "must be greater than or equal to 0");
        Map<String, Object> tooLong = symptomLog(null, "MILD", "Cough");
        tooLong.put("durationDays", 3651);
        assertFieldError(doPost(SYMPTOMS, patient, tooLong), "durationDays", "must be less than or equal to 3650");
        Map<String, Object> notes = symptomLog(null, "MILD", "Cough");
        notes.put("notes", "n".repeat(2001));
        assertFieldError(doPost(SYMPTOMS, patient, notes), "notes", "size must be between 0 and 2000");
        assertFieldError(doPost(SYMPTOMS, patient, symptomLog("PAT-1", "MILD", "Cough")), "patientSystemId",
                "must be a valid system id");

        assertError(raw("{\"symptoms\": \"Cough\", \"severity\": \"MILD\"}"), 400, "Malformed request body");
        assertError(raw("{\"symptoms\": [{\"name\": \"Cough\"}], \"severity\": \"MILD\"}"), 400, "Malformed request body");
        assertError(raw("{\"symptoms\": [\"Cough\"], \"severity\": \"MILD\", \"durationDays\": \"two\"}"), 400,
                "Malformed request body");

        // Boundaries are accepted
        Map<String, Object> edges = symptomLog(null, "MILD", Collections.nCopies(20, "y".repeat(100)).toArray(String[]::new));
        edges.put("durationDays", 3650);
        edges.put("notes", "n".repeat(2000));
        logOk(patient, edges);
        Map<String, Object> zero = symptomLog(null, "MILD", "Cough");
        zero.put("durationDays", 0);
        assertEquals(0, logOk(patient, zero).path("durationDays").asInt());
        assertEquals(2, count("SELECT COUNT(*) FROM symptom_logs"));
    }

    private MvcResult raw(String json) throws Exception {
        return mockMvc.perform(post(SYMPTOMS).header("Authorization", tokenFor(patient))
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    // ==================== READ ====================

    @Test
    void readAccessMatrix() throws Exception {
        int id = logOk(patient, symptomLog(null, "MILD", "Cough")).path("symptomId").asInt();
        List<String> reads = List.of(SYMPTOMS + "/patient/" + patient.getSystemId(), SYMPTOMS + "/" + id);

        for (String url : reads) {
            assertEquals(200, doGet(url, patient).getResponse().getStatus(), url);
            assertEquals(200, doGet(url, doctor).getResponse().getStatus(), url);
            assertEquals(200, doGet(url, admin).getResponse().getStatus(), url);
            assertError(doGet(url, otherPatient), 403, "Patients can only access their own data");
            assertError(doGet(url, otherDoctor), 403, "You are not part of this patient's care team");
            assertError(doGet(url, caregiver), 403, "You don't have active proxy access to this patient");
        }
        MvcResult denied = doGet(SYMPTOMS + "/" + id, otherDoctor);
        assertEquals("HEALTH_METRIC", json(denied).path("resourceType").asText());
        assertEquals(id, json(denied).path("resourceId").asInt());

        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        for (String url : reads) {
            assertError(doGet(url, caregiver), 403,
                    "Your proxy access for this patient does not allow viewing health readings");
        }
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        for (String url : reads) {
            assertEquals(200, doGet(url, caregiver).getResponse().getStatus(), url);
        }
        JsonNode one = json(doGet(SYMPTOMS + "/" + id, caregiver));
        assertFalse(one.path("canDelete").asBoolean(), "only the author may delete");

        assertError(doGet(SYMPTOMS + "/987654", patient), 404, "Symptom log not found");
        assertError(doGet(SYMPTOMS + "/987654", otherPatient), 404, "Symptom log not found");
        assertError(doGet(SYMPTOMS + "/first", patient), 400, "Invalid value for parameter 'symptomId'");
        assertTrue(count("SELECT COUNT(*) FROM audit_log WHERE action = 'READ' AND resource_type = 'SYMPTOM_LOG' "
                + "AND resource_id = ?", id) >= 3);
    }

    @Test
    void historyIsPagedNewestFirstWithFiltersAndHidesDeletedLogs() throws Exception {
        int old = logOk(patient, symptomLog(null, "MILD", "Sneezing")).path("symptomId").asInt();
        backdate(old, LocalDate.now().minusDays(10).atTime(9, 0));
        int severe = logOk(patient, symptomLog(null, "SEVERE", "Headache")).path("symptomId").asInt();
        backdate(severe, LocalDate.now().minusDays(2).atTime(9, 0));
        int byDoctor = logOk(doctor, symptomLog(patient.getSystemId(), "MODERATE", "Dry cough")).path("symptomId").asInt();
        int deleted = logOk(patient, symptomLog(null, "MILD", "Typo")).path("symptomId").asInt();
        logOk(otherPatient, symptomLog(null, "SEVERE", "Fever"));
        assertEquals(204, doDelete(SYMPTOMS + "/" + deleted, patient).getResponse().getStatus());

        String base = SYMPTOMS + "/patient/" + patient.getSystemId();
        JsonNode all = json(doGet(base, patient));
        assertEquals(3, all.path("totalElements").asInt());
        List<Integer> ids = new ArrayList<>();
        all.path("content").forEach(l -> ids.add(l.path("symptomId").asInt()));
        assertEquals(List.of(byDoctor, severe, old), ids);
        assertFalse(all.path("content").get(0).path("canDelete").asBoolean(), "the patient did not write it");
        assertEquals(nameOf(doctor), all.path("content").get(0).path("loggedByName").asText());
        assertFalse(all.path("content").get(1).path("canDelete").asBoolean(), "written two days ago: past 24 hours");
        assertEquals(0, all.path("page").asInt());
        assertEquals(20, all.path("size").asInt());
        assertEquals(1, all.path("totalPages").asInt());

        JsonNode severeOnly = json(doGet(base + "?severity=severe", patient));
        assertEquals(1, severeOnly.path("totalElements").asInt());
        assertEquals(severe, severeOnly.path("content").get(0).path("symptomId").asInt());
        JsonNode window = json(doGet(base + "?from=" + LocalDate.now().minusDays(10) + "&to="
                + LocalDate.now().minusDays(2), patient));
        assertEquals(2, window.path("totalElements").asInt(), "from and to are inclusive clinic days");
        JsonNode today = json(doGet(base + "?from=" + LocalDate.now(), patient));
        assertEquals(1, today.path("totalElements").asInt());

        JsonNode second = json(doGet(base + "?page=1&size=2", patient));
        assertEquals(2, second.path("totalPages").asInt());
        assertEquals(1, second.path("content").size());
        assertEquals(old, second.path("content").get(0).path("symptomId").asInt());
        JsonNode clamped = json(doGet(base + "?page=-1&size=1000", patient));
        assertEquals(0, clamped.path("page").asInt());
        assertEquals(100, clamped.path("size").asInt());

        assertRuleError(doGet(base + "?severity=URGENT", patient), "Unknown symptom severity");
        assertRuleError(doGet(base + "?from=" + LocalDate.now() + "&to=" + LocalDate.now().minusDays(1), patient),
                "from must not be after to");
        assertError(doGet(base + "?to=yesterday", patient), 400, "Invalid value for parameter 'to'");
        assertError(doGet(base + "?size=big", patient), 400, "Invalid value for parameter 'size'");

        // Unknown patient: 403 for a related role, empty for an admin
        assertError(doGet(SYMPTOMS + "/patient/PAT-99999", patient), 403, "Patients can only access their own data");
        assertEquals(0, json(doGet(SYMPTOMS + "/patient/PAT-99999", admin)).path("totalElements").asInt());
        assertTrue(auditRows("READ", "SYMPTOM_LOG", patient.getSystemId()) > 0);
    }

    // ==================== DELETE ====================

    @Test
    void deleteRules() throws Exception {
        String notAuthor = "Only the person who logged these symptoms can delete them";
        int byPatient = logOk(patient, symptomLog(null, "MILD", "Cough")).path("symptomId").asInt();
        int byDoctor = logOk(doctor, symptomLog(patient.getSystemId(), "MILD", "Rash")).path("symptomId").asInt();
        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        int byCaregiver = logOk(caregiver, symptomLog(patient.getSystemId(), "MILD", "Tired")).path("symptomId").asInt();
        int stale = logOk(patient, symptomLog(null, "MILD", "Old")).path("symptomId").asInt();
        backdate(stale, LocalDateTime.now().minusHours(24).minusMinutes(1));

        assertError(doDelete(SYMPTOMS + "/" + byDoctor, patient), 403, notAuthor);
        assertError(doDelete(SYMPTOMS + "/" + byPatient, doctor), 403, notAuthor);
        assertError(doDelete(SYMPTOMS + "/" + byPatient, admin), 403, notAuthor);
        assertError(doDelete(SYMPTOMS + "/" + byPatient, caregiver), 403, notAuthor);
        assertError(doDelete(SYMPTOMS + "/" + stale, patient), 409, "Symptom logs can only be deleted within 24 hours");
        assertError(doDelete(SYMPTOMS + "/" + stale, doctor), 403, notAuthor);
        assertError(doDelete(SYMPTOMS + "/987654", patient), 404, "Symptom log not found");
        assertError(doDelete(SYMPTOMS + "/x", patient), 400, "Invalid value for parameter 'symptomId'");

        // An author who lost write access cannot delete
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertError(doDelete(SYMPTOMS + "/" + byCaregiver, caregiver), 403,
                "Your proxy access for this patient does not allow recording health readings");
        jdbc.update("UPDATE care_team SET is_active = false WHERE provider_system_id = ?", doctor.getSystemId());
        assertError(doDelete(SYMPTOMS + "/" + byDoctor, doctor), 403, "You are not part of this patient's care team");
        jdbc.update("UPDATE care_team SET is_active = true WHERE provider_system_id = ?", doctor.getSystemId());

        // Soft delete by the author
        events.clear();
        assertEquals(204, doDelete(SYMPTOMS + "/" + byDoctor, doctor).getResponse().getStatus());
        assertEquals(204, doDelete(SYMPTOMS + "/" + byPatient, patient).getResponse().getStatus());
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT is_deleted FROM symptom_logs WHERE symptom_id = ?",
                Boolean.class, byPatient));
        assertNotNull(jdbc.queryForObject("SELECT deleted_at FROM symptom_logs WHERE symptom_id = ?",
                LocalDateTime.class, byPatient));
        assertEquals(0, loggedEvents().size(), "no event on delete");
        assertEquals(2, auditRows("DELETE", "SYMPTOM_LOG", patient.getSystemId()));

        assertError(doDelete(SYMPTOMS + "/" + byPatient, patient), 404, "Symptom log not found");
        assertError(doGet(SYMPTOMS + "/" + byPatient, patient), 404, "Symptom log not found");
        JsonNode remaining = json(doGet(SYMPTOMS + "/patient/" + patient.getSystemId(), patient));
        assertEquals(2, remaining.path("totalElements").asInt());
        remaining.path("content").forEach(l -> assertFalse(l.path("canDelete").asBoolean(),
                "the caregiver's log and the stale log cannot be deleted by the patient"));
    }

    // ==================== IDEMPOTENCY, LEGACY ROWS ====================

    @Test
    void idempotentReplayDoesNotNotifyTwice() throws Exception {
        Map<String, String> key = Map.of("Idempotency-Key", "symptom_form_42");
        MvcResult first = doPostWithHeaders(SYMPTOMS, patient, symptomLog(null, "SEVERE", "Headache"), key);
        assertEquals(201, first.getResponse().getStatus(), first.getResponse().getContentAsString());
        MvcResult replay = doPostWithHeaders(SYMPTOMS, patient, symptomLog(null, "MILD", "Other"), key);
        assertEquals(200, replay.getResponse().getStatus(), replay.getResponse().getContentAsString());
        assertEquals(json(first).path("symptomId").asInt(), json(replay).path("symptomId").asInt());
        assertEquals("SEVERE", json(replay).path("severity").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM symptom_logs"));
        assertEquals(1, notificationsFor(doctor));
        assertEquals(1, loggedEvents().size());
    }

    @Test
    void legacyRowsWithoutAStringListOrAuthorAreReadable() throws Exception {
        Integer id = jdbc.queryForObject("INSERT INTO symptom_logs (patient_system_id, symptoms, severity, source, logged_at) "
                + "VALUES (?, '{\"head\": \"ache\"}'::jsonb, 'MILD', 'INTEGRATION', ?) RETURNING symptom_id",
                Integer.class, patient.getSystemId(), LocalDateTime.now().minusDays(1));
        JsonNode log = json(doGet(SYMPTOMS + "/" + id, patient));
        assertEquals(0, log.path("symptoms").size());
        assertTrue(log.path("symptoms").isArray());
        assertTrue(log.path("loggedBySystemId").isNull());
        assertTrue(log.path("loggedByName").isNull());
        assertFalse(log.path("canDelete").asBoolean());
        assertEquals("INTEGRATION", log.path("source").asText());
    }
}
