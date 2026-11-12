package com.carenexus.api.releasegates;

import com.carenexus.api.ai.detection.DetectionTestSupport;
import com.carenexus.api.ai.detection.DetectionTestSupport.SwitchableRiskDetectionProvider;
import com.carenexus.api.ai.detection.DetectionTestSupport.SwitchableRiskDetectionProvider.Mode;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.core.model.CareTeam;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Release gates for disease detection (api/be-ai-detection.md): who may see, review and run screenings (owner,
 * unrelated user of each role, caregiver with and without records access and with an expired grant, administrator),
 * the review state machine (terminal states, 409, validation 400s), unconfirmed detections hidden from the patient
 * and caregivers and visible after confirmation, exactly one DISEASE_RISK alert per detection, the run outcomes and
 * the 503 of a switched-off or failing provider, paging and filters, and the audit rows.
 */
@Import(DetectionTestSupport.class)
class DiseaseDetectionReleaseGateTest extends CommittedIntegrationTest {

    private static final String BASE = "/api/disease-detections";
    private static final String DISCLAIMER = "This result comes from automated rule-based screening. It is not a diagnosis.";
    private static final String NOT_RELATED = "You are not part of this patient's care team";
    private static final String WRONG_ROLE = "Your role is not authorized to perform this action";

    @Autowired
    private SwitchableRiskDetectionProvider provider;

    @Autowired
    private Clock clock;

    @BeforeEach
    void resetProvider() {
        provider.reset();
    }

    @AfterEach
    void restoreProvider() {
        provider.reset();
    }

    // ==================== helpers ====================

    private String list(User forPatient) {
        return BASE + "/patient/" + forPatient.getSystemId();
    }

    private String run(User forPatient) {
        return BASE + "/patient/" + forPatient.getSystemId() + "/run";
    }

    private static String one(int detectionId) {
        return BASE + "/" + detectionId;
    }

    private static String review(int detectionId) {
        return BASE + "/" + detectionId + "/review";
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int audit(String action, String actor) {
        return count("SELECT COUNT(*) FROM audit_log WHERE resource_type = 'DISEASE_DETECTION' AND action = ? "
                + "AND system_id = ?", action, actor);
    }

    private int auditFor(String action, String actor, int detectionId) {
        return count("SELECT COUNT(*) FROM audit_log WHERE resource_type = 'DISEASE_DETECTION' AND action = ? "
                + "AND system_id = ? AND resource_id = ?", action, actor, detectionId);
    }

    /** A hypertensive reading written without an event, then a manual screening by the patient's doctor. */
    private int detectionByRun() throws Exception {
        DetectionTestSupport.insertReading(jdbc, patient.getSystemId(), "BP_SYSTOLIC", "148", "mmHg", now().minusMinutes(30));
        DetectionTestSupport.insertReading(jdbc, patient.getSystemId(), "BP_DIASTOLIC", "92", "mmHg", now().minusMinutes(30));
        MvcResult created = doPost(run(patient), doctor, null);
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        return json(created).path("detection").path("detectionId").asInt();
    }

    private static Map<String, Object> decision(String decision, String notes) {
        Map<String, Object> body = new HashMap<>();
        body.put("decision", decision);
        if (notes != null) {
            body.put("notes", notes);
        }
        return body;
    }

    private void relate(User aDoctor, User aPatient) {
        careTeamRepository.saveAndFlush(CareTeam.builder()
                .patientSystemId(aPatient.getSystemId())
                .providerSystemId(aDoctor.getSystemId())
                .role("SPECIALIST")
                .assignedAt(LocalDateTime.now())
                .isActive(true)
                .build());
    }

    private static List<Integer> ids(JsonNode page) {
        List<Integer> ids = new ArrayList<>();
        page.path("content").forEach(row -> ids.add(row.path("detectionId").asInt()));
        return ids;
    }

    // ==================== 4.4 run ====================

    @Test
    void doctorRunsScreeningsWithEveryOutcome() throws Exception {
        MvcResult noData = doPost(run(patient), doctor, null);
        assertEquals(200, noData.getResponse().getStatus(), noData.getResponse().getContentAsString());
        JsonNode body = json(noData);
        assertEquals("NO_DATA", body.path("outcome").asText());
        assertEquals("There are no recent symptoms or readings to screen.", body.path("message").asText());
        assertTrue(body.path("detection").isNull());
        assertEquals("rules-v1", body.path("modelVersion").asText());
        assertTrue(body.path("assessedAt").asText().endsWith("Z"));
        assertEquals(DISCLAIMER, body.path("disclaimer").asText());
        assertEquals(0, provider.calls(), "NO_DATA does not call the provider");
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE resource_type = 'DISEASE_DETECTION' AND action = 'READ' "
                + "AND system_id = ? AND resource_id IS NULL AND patient_system_id = ?", doctor.getSystemId(), patient.getSystemId()));

        DetectionTestSupport.insertSymptomLog(jdbc, patient.getSystemId(), "MILD", now().minusDays(1), "Runny nose");
        JsonNode noFinding = json(doPost(run(patient), doctor, null));
        assertEquals("NO_FINDING", noFinding.path("outcome").asText());
        assertEquals("Screening found nothing that needs review.", noFinding.path("message").asText());
        assertTrue(noFinding.path("detection").isNull());

        // A hypertensive reading: 201 with a new detection and one alert, the calling doctor notified too
        DetectionTestSupport.insertReading(jdbc, patient.getSystemId(), "BP_SYSTOLIC", "148", "mmHg", now().minusMinutes(5));
        DetectionTestSupport.insertReading(jdbc, patient.getSystemId(), "BP_DIASTOLIC", "92", "mmHg", now().minusMinutes(5));
        MvcResult created = doPost(run(patient), doctor, null);
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        body = json(created);
        assertEquals("DETECTION_CREATED", body.path("outcome").asText());
        assertEquals("Screening flagged a possible risk. Review the result below.", body.path("message").asText());
        JsonNode detection = body.path("detection");
        int detectionId = detection.path("detectionId").asInt();
        assertEquals(patient.getSystemId(), detection.path("patientSystemId").asText());
        assertEquals("PATIENT User1", detection.path("patientName").asText());
        assertTrue(detection.path("sourceType").isNull());
        assertTrue(detection.path("sourceId").isNull());
        assertEquals(doctor.getSystemId(), detection.path("requestedBySystemId").asText());
        assertEquals("rules-v1", detection.path("modelVersion").asText());
        assertEquals("MEDIUM", detection.path("riskLevel").asText());
        assertEquals(0.7, detection.path("confidenceScore").asDouble(), 1e-9);
        JsonNode condition = detection.path("detectedConditions").get(0);
        assertEquals(1, detection.path("detectedConditions").size());
        assertEquals("HYPERTENSIVE_BP", condition.path("code").asText());
        assertEquals("Blood pressure in the hypertensive range", condition.path("name").asText());
        assertEquals("MEDIUM", condition.path("riskLevel").asText());
        assertEquals(0.7, condition.path("confidence").asDouble(), 1e-9);
        assertEquals("Blood pressure 148/92 mmHg recorded on " + today(), condition.path("evidence").get(0).asText());
        assertEquals("Repeat the blood pressure measurement and review blood pressure management.",
                detection.path("recommendations").get(0).asText());
        assertEquals("PENDING_REVIEW", detection.path("reviewStatus").asText());
        assertTrue(detection.path("reviewNotes").isNull());
        assertTrue(detection.path("reviewedBySystemId").isNull());
        assertTrue(detection.path("reviewedAt").isNull());
        assertTrue(detection.path("createdAt").asText().endsWith("Z"));
        assertTrue(detection.path("canReview").asBoolean());
        assertEquals(DISCLAIMER, detection.path("disclaimer").asText());
        assertFalse(detection.has("inputData"));
        assertFalse(detection.has("dedupeKey"));

        assertEquals(1, count("SELECT COUNT(*) FROM disease_detection"));
        assertEquals(doctor.getSystemId(), jdbc.queryForObject("SELECT doctor_system_id FROM disease_detection", String.class));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_alerts WHERE alert_type = 'DISEASE_RISK' AND detection_id = ?"
                + " AND source_type IS NULL AND priority = 'MEDIUM'", detectionId));
        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE type = 'AI_ALERT' AND system_id = ?",
                doctor.getSystemId()));
        assertEquals(1, auditFor("CREATE", doctor.getSystemId(), detectionId));

        // Again the same day: the stored detection, nothing written
        MvcResult exists = doPost(run(patient), doctor, null);
        assertEquals(200, exists.getResponse().getStatus());
        assertEquals("DETECTION_EXISTS", json(exists).path("outcome").asText());
        assertEquals("Screening flagged a risk that was already recorded today.", json(exists).path("message").asText());
        assertEquals(detectionId, json(exists).path("detection").path("detectionId").asInt());
        assertEquals(1, count("SELECT COUNT(*) FROM disease_detection"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_alerts WHERE detection_id = ?", detectionId));
        assertEquals(1, auditFor("READ", doctor.getSystemId(), detectionId));
    }

    @Test
    void onlyARelatedDoctorMayRunAScreening() throws Exception {
        MvcResult unrelated = doPost(run(patient), otherDoctor, null);
        assertEquals(403, unrelated.getResponse().getStatus());
        assertEquals(NOT_RELATED, message(unrelated));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCESS_DENIED' AND system_id = ?",
                otherDoctor.getSystemId()));
        for (User refused : List.of(admin, patient, caregiver)) {
            MvcResult result = doPost(run(patient), refused, null);
            assertEquals(403, result.getResponse().getStatus(), refused.getRole());
            assertEquals(WRONG_ROLE, message(result));
        }
        assertEquals(403, doPost(BASE + "/patient/PAT-99999/run", doctor, null).getResponse().getStatus());

        MvcResult badId = doPost(BASE + "/patient/PAT-1/run", doctor, null);
        assertEquals(400, badId.getResponse().getStatus());
        assertEquals("patientSystemId: must be a patient system id like PAT-00001", message(badId));
        assertEquals("VALIDATION_FAILED", json(badId).path("code").asText());
        assertEquals(0, provider.calls());
    }

    @Test
    void switchedOffOrFailingProviderAnswers503AndWritesNothing() throws Exception {
        DetectionTestSupport.insertReading(jdbc, patient.getSystemId(), "SPO2", "86", "%", now());

        provider.set(Mode.DISABLED);
        MvcResult off = doPost(run(patient), doctor, null);
        assertEquals(503, off.getResponse().getStatus());
        assertEquals("Automated screening is switched off", message(off));
        assertEquals("PROVIDER_UNAVAILABLE", json(off).path("code").asText());

        provider.set(Mode.FAIL);
        MvcResult failed = doPost(run(patient), doctor, null);
        assertEquals(503, failed.getResponse().getStatus());
        assertEquals("Automated screening could not be completed. Try again later.", message(failed));
        assertEquals("PROVIDER_UNAVAILABLE", json(failed).path("code").asText());

        assertEquals(0, count("SELECT COUNT(*) FROM disease_detection"));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_alerts"));
        assertEquals(0, audit("CREATE", doctor.getSystemId()));

        provider.set(Mode.RULES);
        MvcResult recovered = doPost(run(patient), doctor, null);
        assertEquals(201, recovered.getResponse().getStatus());
        assertEquals("CRITICAL", json(recovered).path("detection").path("riskLevel").asText());
    }

    // ==================== access ====================

    @Test
    void unrelatedUsersOfEveryRoleAreRefused() throws Exception {
        int detectionId = detectionByRun();

        MvcResult doctorList = doGet(list(patient), otherDoctor);
        assertEquals(403, doctorList.getResponse().getStatus());
        assertEquals(NOT_RELATED, message(doctorList));
        assertEquals(403, doGet(one(detectionId), otherDoctor).getResponse().getStatus());
        MvcResult doctorReview = doPost(review(detectionId), otherDoctor, decision("CONFIRM", null));
        assertEquals(403, doctorReview.getResponse().getStatus());
        assertEquals(NOT_RELATED, message(doctorReview));
        assertTrue(count("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCESS_DENIED' AND system_id = ?",
                otherDoctor.getSystemId()) >= 3);

        MvcResult otherPatientList = doGet(list(patient), otherPatient);
        assertEquals(403, otherPatientList.getResponse().getStatus());
        assertEquals("Patients can only access their own data", message(otherPatientList));
        assertEquals(403, doGet(one(detectionId), otherPatient).getResponse().getStatus());

        MvcResult noGrant = doGet(list(patient), otherCaregiver);
        assertEquals(403, noGrant.getResponse().getStatus());
        assertEquals("You don't have active proxy access to this patient", message(noGrant));
        assertEquals(403, doGet(one(detectionId), otherCaregiver).getResponse().getStatus());

        // A grant without records access, and an expired records grant
        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        MvcResult withoutRecords = doGet(list(patient), caregiver);
        assertEquals(403, withoutRecords.getResponse().getStatus());
        assertTrue(message(withoutRecords).startsWith("Your proxy access for this patient does not allow"),
                message(withoutRecords));
        grantAccess(caregiver, patient, a -> {
            a.setCanViewRecords(true);
            a.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        });
        assertEquals(403, doGet(list(patient), caregiver).getResponse().getStatus());
        assertEquals(403, doGet(one(detectionId), caregiver).getResponse().getStatus());

        // Administrators read everything but cannot review; an unknown patient is 404 for them only
        JsonNode adminList = json(doGet(list(patient), admin));
        assertEquals(List.of(detectionId), ids(adminList));
        assertFalse(adminList.path("content").get(0).path("canReview").asBoolean());
        assertEquals(1, adminList.path("content").get(0).path("recommendations").size());
        assertEquals(200, doGet(one(detectionId), admin).getResponse().getStatus());
        MvcResult adminReview = doPost(review(detectionId), admin, decision("CONFIRM", null));
        assertEquals(403, adminReview.getResponse().getStatus());
        assertEquals(WRONG_ROLE, message(adminReview));
        MvcResult unknownPatient = doGet(BASE + "/patient/PAT-99999", admin);
        assertEquals(404, unknownPatient.getResponse().getStatus());
        assertEquals("Patient not found", message(unknownPatient));
        assertEquals(403, doGet(BASE + "/patient/PAT-99999", doctor).getResponse().getStatus());

        MvcResult missing = doGet(one(999_999), doctor);
        assertEquals(404, missing.getResponse().getStatus());
        assertEquals("Detection not found", message(missing));
        MvcResult notANumber = doGet(BASE + "/abc", doctor);
        assertEquals(400, notANumber.getResponse().getStatus());
        assertEquals("Invalid value for parameter 'detectionId'", message(notANumber));

        assertEquals("PENDING_REVIEW", jdbc.queryForObject("SELECT review_status FROM disease_detection", String.class));
    }

    // ==================== review ====================

    @Test
    void reviewIsValidatedAndFollowsTheStateMachine() throws Exception {
        int detectionId = detectionByRun();

        MvcResult blank = doPost(review(detectionId), doctor, decision(" ", null));
        assertEquals(400, blank.getResponse().getStatus());
        assertEquals("decision: must not be blank", message(blank));
        assertEquals("VALIDATION_FAILED", json(blank).path("code").asText());
        assertEquals(1, json(blank).path("errors").size());
        MvcResult missingDecision = doPost(review(detectionId), doctor, Map.of("notes", "n"));
        assertEquals("decision: must not be blank", message(missingDecision));
        MvcResult unknown = doPost(review(detectionId), doctor, decision("APPROVE", null));
        assertEquals(400, unknown.getResponse().getStatus());
        assertEquals("decision: must be CONFIRM or REJECT", message(unknown));
        assertEquals(1, json(unknown).path("errors").size());
        MvcResult longNotes = doPost(review(detectionId), doctor, decision("CONFIRM", "n".repeat(2001)));
        assertEquals(400, longNotes.getResponse().getStatus());
        assertEquals("notes: size must be between 0 and 2000", message(longNotes));
        MvcResult malformed = mockMvc.perform(post(review(detectionId)).header("Authorization", tokenFor(doctor))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":")).andReturn();
        assertEquals(400, malformed.getResponse().getStatus());
        assertEquals("Malformed request body", message(malformed));

        for (User refused : List.of(patient, caregiver)) {
            MvcResult result = doPost(review(detectionId), refused, decision("CONFIRM", null));
            assertEquals(403, result.getResponse().getStatus());
            assertEquals(WRONG_ROLE, message(result));
        }
        MvcResult missing = doPost(review(999_999), doctor, decision("CONFIRM", null));
        assertEquals(404, missing.getResponse().getStatus());
        assertEquals("Detection not found", message(missing));

        int notificationsBefore = count("SELECT COUNT(*) FROM notifications");
        MvcResult confirmed = doPost(review(detectionId), doctor,
                decision(" confirm ", "  Seen in clinic today, examination consistent.  "));
        assertEquals(200, confirmed.getResponse().getStatus(), confirmed.getResponse().getContentAsString());
        JsonNode body = json(confirmed);
        assertEquals("CONFIRMED", body.path("reviewStatus").asText());
        assertEquals("Seen in clinic today, examination consistent.", body.path("reviewNotes").asText());
        assertEquals(doctor.getSystemId(), body.path("reviewedBySystemId").asText());
        assertTrue(body.path("reviewedAt").asText().endsWith("Z"));
        assertFalse(body.path("canReview").asBoolean());
        assertEquals(1, body.path("recommendations").size());
        assertEquals(DISCLAIMER, body.path("disclaimer").asText());
        Map<String, Object> row = jdbc.queryForMap("SELECT is_confirmed, confirmed_at, review_status FROM disease_detection "
                + "WHERE detection_id = ?", detectionId);
        assertEquals(Boolean.TRUE, row.get("is_confirmed"));
        assertNotNull(row.get("confirmed_at"));
        assertEquals(1, auditFor("UPDATE", doctor.getSystemId(), detectionId));
        assertEquals(notificationsBefore, count("SELECT COUNT(*) FROM notifications"), "a review notifies nobody");

        // Terminal: any second review is 409, also the same decision, also another related doctor
        for (String again : List.of("REJECT", "CONFIRM")) {
            MvcResult conflict = doPost(review(detectionId), doctor, decision(again, null));
            assertEquals(409, conflict.getResponse().getStatus());
            assertEquals("This detection has already been reviewed", message(conflict));
        }
        relate(otherDoctor, patient);
        assertEquals(409, doPost(review(detectionId), otherDoctor, decision("REJECT", null)).getResponse().getStatus());
        assertEquals(1, auditFor("UPDATE", doctor.getSystemId(), detectionId), "a 409 writes no audit row");
        assertEquals("CONFIRMED", jdbc.queryForObject("SELECT review_status FROM disease_detection WHERE detection_id = ?",
                String.class, detectionId));

        // A rejection needs no reason; the legacy flag stays false
        int rejectedId = DetectionTestSupport.insertDetection(jdbc, patient.getSystemId(), "PENDING_REVIEW",
                now().minusHours(1), "manual:reject");
        MvcResult rejected = doPost(review(rejectedId), otherDoctor, decision("reject", "   "));
        assertEquals(200, rejected.getResponse().getStatus(), rejected.getResponse().getContentAsString());
        assertEquals("REJECTED", json(rejected).path("reviewStatus").asText());
        assertTrue(json(rejected).path("reviewNotes").isNull());
        assertEquals(otherDoctor.getSystemId(), json(rejected).path("reviewedBySystemId").asText());
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT is_confirmed FROM disease_detection WHERE detection_id = ?",
                Boolean.class, rejectedId));
        assertEquals(409, doPost(review(rejectedId), doctor, decision("CONFIRM", null)).getResponse().getStatus());
    }

    // ==================== patient-facing visibility ====================

    @Test
    void unconfirmedDetectionsAreHiddenFromPatientAndCaregiverUntilConfirmed() throws Exception {
        int detectionId = detectionByRun();
        int rejectedId = DetectionTestSupport.insertDetection(jdbc, patient.getSystemId(), "REJECTED",
                now().minusDays(1), "manual:rejected");
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));

        for (User viewer : List.of(patient, caregiver)) {
            JsonNode page = json(doGet(list(patient), viewer));
            assertEquals(0, page.path("totalElements").asInt(), viewer.getRole());
            assertEquals(0, page.path("content").size());
            JsonNode filtered = json(doGet(list(patient) + "?reviewStatus=PENDING_REVIEW", viewer));
            assertEquals(0, filtered.path("totalElements").asInt(), "the filter never widens the patient view");
            for (int hidden : List.of(detectionId, rejectedId)) {
                MvcResult byId = doGet(one(hidden), viewer);
                assertEquals(404, byId.getResponse().getStatus());
                assertEquals("Detection not found", message(byId));
            }
            JsonNode alerts = json(doGet("/api/ai-alerts/patient/" + patient.getSystemId(), viewer));
            assertEquals(0, alerts.path("totalElements").asInt(), "the pending detection's alert is hidden too");
        }
        assertEquals(0, auditFor("READ", patient.getSystemId(), detectionId), "a hidden row writes no READ audit");
        assertEquals(0, audit("READ", caregiver.getSystemId()), "an empty page writes no audit row");

        JsonNode doctorView = json(doGet(list(patient), doctor));
        assertEquals(List.of(detectionId, rejectedId), ids(doctorView));
        assertTrue(doctorView.path("content").get(0).path("canReview").asBoolean());
        assertFalse(doctorView.path("content").get(1).path("canReview").asBoolean());

        assertEquals(200, doPost(review(detectionId), doctor, decision("CONFIRM", "Discussed with the patient."))
                .getResponse().getStatus());

        for (User viewer : List.of(patient, caregiver)) {
            JsonNode page = json(doGet(list(patient), viewer));
            assertEquals(1, page.path("totalElements").asInt(), viewer.getRole());
            assertEquals(List.of(detectionId), ids(page));
            JsonNode row = page.path("content").get(0);
            assertEquals("CONFIRMED", row.path("reviewStatus").asText());
            assertEquals(0, row.path("recommendations").size());
            assertTrue(row.path("recommendations").isArray());
            assertTrue(row.path("reviewNotes").isNull());
            assertFalse(row.path("canReview").asBoolean());
            assertEquals(doctor.getSystemId(), row.path("reviewedBySystemId").asText());
            assertEquals(1, row.path("detectedConditions").get(0).path("evidence").size());
            assertEquals(DISCLAIMER, row.path("disclaimer").asText());

            MvcResult byId = doGet(one(detectionId), viewer);
            assertEquals(200, byId.getResponse().getStatus());
            assertTrue(json(byId).path("reviewNotes").isNull());
            assertEquals(404, doGet(one(rejectedId), viewer).getResponse().getStatus());

            JsonNode alerts = json(doGet("/api/ai-alerts/patient/" + patient.getSystemId(), viewer));
            assertEquals(1, alerts.path("totalElements").asInt());
            assertEquals(detectionId, alerts.path("content").get(0).path("detectionId").asInt());
            assertEquals("CONFIRMED", alerts.path("content").get(0).path("detectionReviewStatus").asText());
        }
        assertEquals(1, auditFor("READ", patient.getSystemId(), detectionId));
        assertTrue(audit("READ", caregiver.getSystemId()) >= 2);

        // Exactly one DISEASE_RISK alert per detection; the review did not add one
        assertEquals(1, count("SELECT COUNT(*) FROM ai_alerts WHERE alert_type = 'DISEASE_RISK'"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_alerts WHERE detection_id = ?", detectionId));

        // Revoking the records flag closes the view at once
        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        assertEquals(403, doGet(one(detectionId), caregiver).getResponse().getStatus());
    }

    // ==================== paging and filters ====================

    @Test
    void listIsPagedNewestFirstWithFilters() throws Exception {
        LocalDateTime base = now().minusDays(2);
        List<Integer> created = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            String status = i % 5 == 0 ? "CONFIRMED" : i % 5 == 1 ? "REJECTED" : "PENDING_REVIEW";
            created.add(DetectionTestSupport.insertDetection(jdbc, patient.getSystemId(), status,
                    base.plusMinutes(i), "page:" + i));
        }
        // same creation time as the newest: the higher id comes first
        int tie = DetectionTestSupport.insertDetection(jdbc, patient.getSystemId(), "PENDING_REVIEW",
                base.plusMinutes(24), "page:tie");
        DetectionTestSupport.insertDetection(jdbc, otherPatient.getSystemId(), "PENDING_REVIEW", base, "page:other");

        JsonNode first = json(doGet(list(patient), doctor));
        assertEquals(0, first.path("page").asInt());
        assertEquals(20, first.path("size").asInt());
        assertEquals(26, first.path("totalElements").asInt());
        assertEquals(2, first.path("totalPages").asInt());
        assertEquals(20, first.path("content").size());
        List<Integer> expectedFirst = new ArrayList<>();
        expectedFirst.add(tie);
        for (int i = 24; i >= 6; i--) {
            expectedFirst.add(created.get(i));
        }
        assertEquals(expectedFirst, ids(first));

        JsonNode second = json(doGet(list(patient) + "?page=1&size=20", doctor));
        assertEquals(6, second.path("content").size());
        assertEquals(created.get(0), ids(second).get(5));

        assertEquals(1, json(doGet(list(patient) + "?size=0", doctor)).path("size").asInt());
        assertEquals(100, json(doGet(list(patient) + "?size=500", doctor)).path("size").asInt());
        assertEquals(0, json(doGet(list(patient) + "?page=-3", doctor)).path("page").asInt());
        JsonNode beyond = json(doGet(list(patient) + "?page=9", doctor));
        assertEquals(0, beyond.path("content").size());
        assertEquals(26, beyond.path("totalElements").asInt());

        JsonNode pending = json(doGet(list(patient) + "?reviewStatus= pending_review ", doctor));
        assertEquals(16, pending.path("totalElements").asInt());
        pending.path("content").forEach(row -> assertEquals("PENDING_REVIEW", row.path("reviewStatus").asText()));
        assertEquals(5, json(doGet(list(patient) + "?reviewStatus=CONFIRMED", admin)).path("totalElements").asInt());
        assertEquals(26, json(doGet(list(patient) + "?reviewStatus=", doctor)).path("totalElements").asInt());
        assertEquals(5, json(doGet(list(patient), patient)).path("totalElements").asInt());

        MvcResult badStatus = doGet(list(patient) + "?reviewStatus=DONE", patient);
        assertEquals(400, badStatus.getResponse().getStatus());
        assertEquals("reviewStatus: must be one of PENDING_REVIEW, CONFIRMED, REJECTED", message(badStatus));
        assertEquals("VALIDATION_FAILED", json(badStatus).path("code").asText());
        MvcResult badPage = doGet(list(patient) + "?page=abc", doctor);
        assertEquals(400, badPage.getResponse().getStatus());
        assertEquals("Invalid value for parameter 'page'", message(badPage));
        MvcResult badSize = doGet(list(patient) + "?size=x", doctor);
        assertEquals("Invalid value for parameter 'size'", message(badSize));
        MvcResult badPatient = doGet(BASE + "/patient/DOC-00001", doctor);
        assertEquals(400, badPatient.getResponse().getStatus());
        assertEquals("patientSystemId: must be a patient system id like PAT-00001", message(badPatient));

        // One READ audit row per non-empty page read
        int before = audit("READ", doctor.getSystemId());
        doGet(list(patient), doctor);
        assertEquals(before + 1, audit("READ", doctor.getSystemId()));
    }
}
