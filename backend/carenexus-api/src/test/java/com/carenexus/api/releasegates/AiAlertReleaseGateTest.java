package com.carenexus.api.releasegates;

import com.carenexus.api.ai.alert.AiAlertCommand;
import com.carenexus.api.ai.alert.AiAlertService;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.event.AiAlertRaisedEvent;
import com.carenexus.api.core.model.CareTeam;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Release gates for the AI alert inbox (api/be-ai-core.md) and the {@link AiAlertService#raise} facade: who sees
 * which alerts (related and addressed doctors, administrators, never patients or caregivers in the inbox), the
 * patient-facing filter of the per-patient list, acknowledgement (role, relationship, idempotency, audit), the badge
 * count, filters and their 400s, the page envelope, the dedupe of raise inside a committing transaction, and one
 * AI_ALERT notification per addressed doctor.
 */
@RecordApplicationEvents
class AiAlertReleaseGateTest extends CommittedIntegrationTest {

    private static final String ALERTS = "/api/ai-alerts";
    private static final String COUNT = "/api/ai-alerts/unacknowledged-count";
    private static final String DISCLAIMER = "This alert comes from automated rule-based screening. It is not a diagnosis.";

    @Autowired
    private AiAlertService aiAlertService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationEvents applicationEvents;

    // ==================== helpers ====================

    private int raise(User aboutPatient, User toDoctor, String type, String priority, String dedupeKey) {
        return raise(aboutPatient, toDoctor, type, priority, null, dedupeKey);
    }

    private int raise(User aboutPatient, User toDoctor, String type, String priority, Integer detectionId,
                      String dedupeKey) {
        return aiAlertService.raise(new AiAlertCommand(aboutPatient.getSystemId(),
                toDoctor != null ? toDoctor.getSystemId() : null, type, priority, null,
                "Automated screening flagged this record for review.", true, "Review the record.",
                "LAB_RESULT", 87, detectionId, dedupeKey)).orElseThrow();
    }

    private int detection(User forPatient, String reviewStatus) {
        return jdbc.queryForObject("INSERT INTO disease_detection (patient_system_id, model_version, detected_conditions,"
                        + " risk_level, review_status) VALUES (?, 'rules-v1', '[]'::jsonb, 'HIGH', ?) RETURNING detection_id",
                Integer.class, forPatient.getSystemId(), reviewStatus);
    }

    private List<Integer> alertIds(JsonNode page) {
        List<Integer> ids = new ArrayList<>();
        page.path("content").forEach(row -> ids.add(row.path("alertId").asInt()));
        return ids;
    }

    private int auditCount(String action, String systemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type = 'AI_ALERT' AND action = ?"
                + " AND system_id = ?", Integer.class, action, systemId);
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

    // ==================== inbox visibility ====================

    @Test
    void inboxShowsAddressedAndRelatedAlertsAndAdminSeesAll() throws Exception {
        int aboutPatient = raise(patient, null, "CRITICAL_RESULT", "CRITICAL", "t:1");
        int otherForOtherDoctor = raise(otherPatient, otherDoctor, "CRITICAL_VITALS", "HIGH", "t:2");
        int otherAddressedToDoctor = raise(otherPatient, doctor, "ANOMALY_DETECTED", "MEDIUM", "t:3");

        MvcResult mine = doGet(ALERTS, doctor);
        assertEquals(200, mine.getResponse().getStatus());
        assertEquals(List.of(otherAddressedToDoctor, aboutPatient), alertIds(json(mine)));

        JsonNode row = json(mine).path("content").get(1);
        assertEquals("PAT-00001", row.path("patientSystemId").asText());
        assertEquals("PATIENT User1", row.path("patientName").asText());
        assertTrue(row.path("doctorSystemId").isNull());
        assertEquals("CRITICAL_RESULT", row.path("alertType").asText());
        assertEquals("CRITICAL", row.path("priority").asText());
        assertEquals("Critical result", row.path("title").asText());
        assertEquals("Review the record.", row.path("suggestedAction").asText());
        assertEquals("LAB_RESULT", row.path("sourceType").asText());
        assertEquals(87, row.path("sourceId").asInt());
        assertTrue(row.path("detectionId").isNull());
        assertTrue(row.path("detectionReviewStatus").isNull());
        assertFalse(row.path("acknowledged").asBoolean());
        assertTrue(row.path("canAcknowledge").asBoolean());
        assertTrue(row.path("createdAt").asText().endsWith("Z"));
        assertEquals(DISCLAIMER, row.path("disclaimer").asText());

        // otherDoctor is related to otherPatient: sees both alerts about them, even the one addressed to DOC-00001
        assertEquals(List.of(otherAddressedToDoctor, otherForOtherDoctor), alertIds(json(doGet(ALERTS, otherDoctor))));

        JsonNode all = json(doGet(ALERTS, admin));
        assertEquals(List.of(otherAddressedToDoctor, otherForOtherDoctor, aboutPatient), alertIds(all));
        all.path("content").forEach(r -> assertFalse(r.path("canAcknowledge").asBoolean()));

        MvcResult asPatient = doGet(ALERTS, patient);
        assertEquals(403, asPatient.getResponse().getStatus());
        assertEquals("Your role is not authorized to perform this action", message(asPatient));
        assertEquals(403, doGet(ALERTS, caregiver).getResponse().getStatus());
        assertEquals(403, doGet(COUNT, patient).getResponse().getStatus());
        assertEquals(403, doGet(COUNT, caregiver).getResponse().getStatus());

        // the list read is audited once per patient on the page; the count poll is not
        assertEquals(2, auditCount("READ", doctor.getSystemId()));
        doGet(COUNT, doctor);
        assertEquals(2, auditCount("READ", doctor.getSystemId()));
    }

    @Test
    void doctorRelatedThroughAnAppointmentOrConsultationSeesThePatientsAlerts() throws Exception {
        int alert = raise(patient, null, "FOLLOW_UP_DUE", "LOW", "t:rel");
        assertEquals(List.of(), alertIds(json(doGet(ALERTS, otherDoctor))));

        jdbc.update("INSERT INTO appointments (patient_system_id, doctor_system_id, scheduled_at, duration_minutes,"
                        + " type, status) VALUES (?, ?, ?, 30, 'VIDEO', 'COMPLETED')",
                patient.getSystemId(), otherDoctor.getSystemId(),
                LocalDateTime.now().minusDays(1).truncatedTo(ChronoUnit.HOURS));
        assertEquals(List.of(alert), alertIds(json(doGet(ALERTS, otherDoctor))));
    }

    // ==================== filters, paging and 400s ====================

    @Test
    void filtersCombineAndInvalidValuesAre400() throws Exception {
        int critical = raise(patient, null, "CRITICAL_RESULT", "CRITICAL", "f:1");
        int high = raise(patient, null, "CRITICAL_VITALS", "HIGH", "f:2");
        int otherPatientAlert = raise(otherPatient, doctor, "CRITICAL_VITALS", "HIGH", "f:3");
        assertEquals(204, doPost(ALERTS + "/" + critical + "/acknowledge", doctor, null).getResponse().getStatus());

        assertEquals(List.of(otherPatientAlert, high), alertIds(json(doGet(ALERTS + "?acknowledged=false", doctor))));
        assertEquals(List.of(critical), alertIds(json(doGet(ALERTS + "?acknowledged=true", doctor))));
        assertEquals(List.of(otherPatientAlert, high), alertIds(json(doGet(ALERTS + "?priority=high", doctor))));
        assertEquals(List.of(otherPatientAlert, high), alertIds(json(doGet(ALERTS + "?type=critical_vitals", doctor))));
        assertEquals(List.of(high), alertIds(json(doGet(ALERTS + "?type=CRITICAL_VITALS&patientSystemId=PAT-00001", doctor))));
        assertEquals(List.of(otherPatientAlert, high, critical), alertIds(json(doGet(ALERTS + "?priority=&type=", doctor))));

        // patientSystemId narrows, never widens: otherDoctor is not related to PAT-00001 and gets an empty page
        MvcResult narrowed = doGet(ALERTS + "?patientSystemId=PAT-00001", otherDoctor);
        assertEquals(200, narrowed.getResponse().getStatus());
        assertEquals(0, json(narrowed).path("totalElements").asInt());

        MvcResult badPriority = doGet(ALERTS + "?priority=URGENT", doctor);
        assertEquals(400, badPriority.getResponse().getStatus());
        assertEquals("priority: must be one of LOW, MEDIUM, HIGH, CRITICAL", message(badPriority));
        assertEquals("VALIDATION_FAILED", json(badPriority).path("code").asText());

        MvcResult badType = doGet(ALERTS + "?type=FEVER", doctor);
        assertEquals(400, badType.getResponse().getStatus());
        assertEquals("type: must be a known alert type", message(badType));
        assertEquals("VALIDATION_FAILED", json(badType).path("code").asText());

        MvcResult badPatient = doGet(ALERTS + "?patientSystemId=DOC-00001", doctor);
        assertEquals(400, badPatient.getResponse().getStatus());
        assertEquals("patientSystemId: must be a patient system id like PAT-00001", message(badPatient));
        assertEquals("VALIDATION_FAILED", json(badPatient).path("code").asText());

        MvcResult badAcknowledged = doGet(ALERTS + "?acknowledged=yes", doctor);
        assertEquals(400, badAcknowledged.getResponse().getStatus());
        assertEquals("Invalid value for parameter 'acknowledged'", message(badAcknowledged));

        MvcResult badPage = doGet(ALERTS + "?page=first", doctor);
        assertEquals(400, badPage.getResponse().getStatus());
        assertEquals("Invalid value for parameter 'page'", message(badPage));
    }

    @Test
    void listsUseThePageEnvelopeNewestFirstWithAClampedSize() throws Exception {
        List<Integer> raised = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            raised.add(raise(patient, null, "MISSED_MEDICATION", "MEDIUM", "p:" + i));
        }

        JsonNode first = json(doGet(ALERTS + "?size=2&page=0", doctor));
        assertEquals(List.of(raised.get(2), raised.get(1)), alertIds(first));
        assertEquals(0, first.path("page").asInt());
        assertEquals(2, first.path("size").asInt());
        assertEquals(3, first.path("totalElements").asInt());
        assertEquals(2, first.path("totalPages").asInt());

        assertEquals(List.of(raised.get(0)), alertIds(json(doGet(ALERTS + "?size=2&page=1", doctor))));

        JsonNode pastTheEnd = json(doGet(ALERTS + "?size=2&page=7", doctor));
        assertEquals(0, pastTheEnd.path("content").size());
        assertEquals(3, pastTheEnd.path("totalElements").asInt());

        JsonNode clamped = json(doGet(ALERTS + "?size=500&page=-3", doctor));
        assertEquals(100, clamped.path("size").asInt());
        assertEquals(0, clamped.path("page").asInt());
        assertEquals(1, json(doGet(ALERTS + "?size=0", doctor)).path("size").asInt());

        JsonNode patientPage = json(doGet(ALERTS + "/patient/PAT-00001?size=2&page=1", patient));
        assertEquals(List.of(raised.get(0)), alertIds(patientPage));
        assertEquals(3, patientPage.path("totalElements").asInt());
        assertEquals(2, patientPage.path("totalPages").asInt());
    }

    // ==================== acknowledge ====================

    @Test
    void acknowledgeIsForRelatedOrAddressedDoctorsAndIsIdempotent() throws Exception {
        int alert = raise(patient, null, "CRITICAL_RESULT", "CRITICAL", "a:1");
        String url = ALERTS + "/" + alert + "/acknowledge";

        MvcResult asPatient = doPost(url, patient, null);
        assertEquals(403, asPatient.getResponse().getStatus());
        assertEquals("Your role is not authorized to perform this action", message(asPatient));
        assertEquals(403, doPost(url, caregiver, null).getResponse().getStatus());
        assertEquals(403, doPost(url, admin, null).getResponse().getStatus());

        MvcResult unrelated = doPost(url, otherDoctor, null);
        assertEquals(403, unrelated.getResponse().getStatus());
        assertEquals("You are not part of this patient's care team", message(unrelated));
        assertEquals(1, auditCount("ACCESS_DENIED", otherDoctor.getSystemId()));

        MvcResult missing = doPost(ALERTS + "/999999/acknowledge", doctor, null);
        assertEquals(404, missing.getResponse().getStatus());
        assertEquals("Alert not found", message(missing));
        assertEquals("Invalid value for parameter 'alertId'", message(doPost(ALERTS + "/abc/acknowledge", doctor, null)));

        assertEquals(1, json(doGet(COUNT, doctor)).path("unacknowledgedCount").asInt());
        assertEquals(DISCLAIMER, json(doGet(COUNT, doctor)).path("disclaimer").asText());

        MvcResult first = doPost(url, doctor, null);
        assertEquals(204, first.getResponse().getStatus());
        assertEquals("", first.getResponse().getContentAsString());
        Map<String, Object> stored = jdbc.queryForMap("SELECT is_acknowledged, acknowledged_by_system_id, acknowledged_at"
                + " FROM ai_alerts WHERE alert_id = ?", alert);
        assertEquals(Boolean.TRUE, stored.get("is_acknowledged"));
        assertEquals("DOC-00001", stored.get("acknowledged_by_system_id"));
        assertNotNull(stored.get("acknowledged_at"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type = 'AI_ALERT'"
                + " AND action = 'UPDATE' AND resource_id = ? AND patient_system_id = 'PAT-00001'", Integer.class, alert));

        // a repeat (same doctor, then a second related doctor) is a no-op that keeps the first acknowledger
        relate(otherDoctor, patient);
        assertEquals(204, doPost(url, doctor, null).getResponse().getStatus());
        assertEquals(204, doPost(url, otherDoctor, null).getResponse().getStatus());
        Map<String, Object> after = jdbc.queryForMap("SELECT acknowledged_by_system_id, acknowledged_at FROM ai_alerts"
                + " WHERE alert_id = ?", alert);
        assertEquals("DOC-00001", after.get("acknowledged_by_system_id"));
        assertEquals(stored.get("acknowledged_at"), after.get("acknowledged_at"));
        assertEquals(1, auditCount("UPDATE", doctor.getSystemId()));
        assertEquals(0, auditCount("UPDATE", otherDoctor.getSystemId()));

        // acknowledged for everybody: the count drops and the row can no longer be acknowledged
        assertEquals(0, json(doGet(COUNT, doctor)).path("unacknowledgedCount").asInt());
        JsonNode row = json(doGet(ALERTS, otherDoctor)).path("content").get(0);
        assertTrue(row.path("acknowledged").asBoolean());
        assertFalse(row.path("canAcknowledge").asBoolean());
        assertEquals("DOC-00001", row.path("acknowledgedBySystemId").asText());
        assertTrue(row.path("acknowledgedAt").asText().endsWith("Z"));
    }

    @Test
    void aDoctorTheAlertIsAddressedToMayAcknowledgeItWithoutARelationship() throws Exception {
        int alert = raise(patient, otherDoctor, "CRITICAL_VITALS", "HIGH", "a:2");

        assertEquals(1, json(doGet(COUNT, otherDoctor)).path("unacknowledgedCount").asInt());
        assertEquals(204, doPost(ALERTS + "/" + alert + "/acknowledge", otherDoctor, null).getResponse().getStatus());
        assertEquals("DOC-00002", jdbc.queryForObject(
                "SELECT acknowledged_by_system_id FROM ai_alerts WHERE alert_id = ?", String.class, alert));
        assertEquals(0, json(doGet(COUNT, otherDoctor)).path("unacknowledgedCount").asInt());
        assertEquals(0, json(doGet(COUNT, admin)).path("unacknowledgedCount").asInt());
    }

    // ==================== one patient's alerts ====================

    @Test
    void patientAndCaregiversSeeOnlyNonDiagnosticAndConfirmedAlerts() throws Exception {
        int missed = raise(patient, null, "MISSED_MEDICATION", "MEDIUM", "pf:1");
        int followUp = raise(patient, null, "FOLLOW_UP_DUE", "LOW", "pf:2");
        int critical = raise(patient, null, "CRITICAL_RESULT", "CRITICAL", "pf:3");
        int pending = raise(patient, null, "DISEASE_RISK", "HIGH", detection(patient, "PENDING_REVIEW"), "pf:4");
        int confirmed = raise(patient, null, "DISEASE_RISK", "HIGH", detection(patient, "CONFIRMED"), "pf:5");
        int rejected = raise(patient, null, "DISEASE_RISK", "HIGH", detection(patient, "REJECTED"), "pf:6");
        String url = ALERTS + "/patient/PAT-00001";

        JsonNode own = json(doGet(url, patient));
        assertEquals(List.of(confirmed, followUp, missed), alertIds(own));
        assertEquals(3, own.path("totalElements").asInt());
        own.path("content").forEach(row -> {
            assertTrue(row.path("suggestedAction").isNull());
            assertFalse(row.path("canAcknowledge").asBoolean());
            assertEquals(DISCLAIMER, row.path("disclaimer").asText());
        });
        assertEquals("CONFIRMED", own.path("content").get(0).path("detectionReviewStatus").asText());
        assertEquals(1, auditCount("READ", patient.getSystemId()));

        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertEquals(List.of(confirmed, followUp, missed), alertIds(json(doGet(url, caregiver))));

        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertEquals(List.of(confirmed, followUp, missed), alertIds(json(doGet(url, caregiver))));

        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        MvcResult withoutFlag = doGet(url, caregiver);
        assertEquals(403, withoutFlag.getResponse().getStatus());
        assertTrue(message(withoutFlag).startsWith("Your proxy access for this patient does not allow"));

        grantAccess(caregiver, patient, a -> {
            a.setCanViewRecords(true);
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
        });
        assertEquals(403, doGet(url, caregiver).getResponse().getStatus());

        MvcResult noGrant = doGet(url, otherCaregiver);
        assertEquals(403, noGrant.getResponse().getStatus());
        assertEquals("You don't have active proxy access to this patient", message(noGrant));

        MvcResult anotherPatient = doGet(url, otherPatient);
        assertEquals(403, anotherPatient.getResponse().getStatus());
        assertEquals("Patients can only access their own data", message(anotherPatient));

        MvcResult unrelatedDoctor = doGet(url, otherDoctor);
        assertEquals(403, unrelatedDoctor.getResponse().getStatus());
        assertEquals("You are not part of this patient's care team", message(unrelatedDoctor));

        // the related doctor and the administrator see everything, with the clinician fields
        JsonNode asDoctor = json(doGet(url, doctor));
        assertEquals(List.of(rejected, confirmed, pending, critical, followUp, missed), alertIds(asDoctor));
        assertEquals("REJECTED", asDoctor.path("content").get(0).path("detectionReviewStatus").asText());
        assertEquals("PENDING_REVIEW", asDoctor.path("content").get(2).path("detectionReviewStatus").asText());
        assertEquals("Review the record.", asDoctor.path("content").get(3).path("suggestedAction").asText());
        asDoctor.path("content").forEach(row -> assertTrue(row.path("canAcknowledge").asBoolean()));

        JsonNode asAdmin = json(doGet(url, admin));
        assertEquals(6, asAdmin.path("totalElements").asInt());
        asAdmin.path("content").forEach(row -> assertFalse(row.path("canAcknowledge").asBoolean()));

        MvcResult unknown = doGet(ALERTS + "/patient/PAT-09999", admin);
        assertEquals(404, unknown.getResponse().getStatus());
        assertEquals("Patient not found", message(unknown));
        assertEquals(403, doGet(ALERTS + "/patient/PAT-09999", doctor).getResponse().getStatus());

        MvcResult malformed = doGet(ALERTS + "/patient/CGV-00001", admin);
        assertEquals(400, malformed.getResponse().getStatus());
        assertEquals("patientSystemId: must be a patient system id like PAT-00001", message(malformed));
    }

    @Test
    void confirmingTheDetectionLaterRevealsItsAlertToThePatient() throws Exception {
        int detectionId = detection(patient, "PENDING_REVIEW");
        int alert = raise(patient, null, "DISEASE_RISK", "HIGH", detectionId, "pf:later");
        String url = ALERTS + "/patient/PAT-00001";

        assertEquals(0, json(doGet(url, patient)).path("totalElements").asInt());
        jdbc.update("UPDATE disease_detection SET review_status = 'CONFIRMED' WHERE detection_id = ?", detectionId);
        assertEquals(List.of(alert), alertIds(json(doGet(url, patient))));
    }

    // ==================== raise ====================

    @Test
    void aRepeatedDedupeKeyInsertsNothingAndTheSurroundingTransactionStillCommits() {
        applicationEvents.clear();
        List<Optional<Integer>> results = new TransactionTemplate(transactionManager).execute(status -> {
            List<Optional<Integer>> out = new ArrayList<>();
            out.add(aiAlertService.raise(command(null, "CRITICAL", "DUP:1")));
            out.add(aiAlertService.raise(command(null, "CRITICAL", "DUP:1")));
            out.add(aiAlertService.raise(command(null, "HIGH", "DUP:2")));
            return out;
        });

        assertNotNull(results);
        assertTrue(results.get(0).isPresent());
        assertTrue(results.get(1).isEmpty());
        assertTrue(results.get(2).isPresent());
        // both inserted rows committed
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_alerts", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_alerts WHERE dedupe_key = 'DUP:1'", Integer.class));
        // one event and one notification per inserted alert, nothing for the repeat
        List<AiAlertRaisedEvent> events = applicationEvents.stream(AiAlertRaisedEvent.class).toList();
        assertEquals(2, events.size());
        assertEquals(results.get(0).get(), events.get(0).alertId());
        assertEquals("PAT-00001", events.get(0).patientSystemId());
        assertEquals("CRITICAL_RESULT", events.get(0).alertType());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'AI_ALERT'", Integer.class));

        // a later repeat outside the transaction is also a no-op
        assertTrue(aiAlertService.raise(command(null, "CRITICAL", "DUP:1")).isEmpty());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_alerts", Integer.class));
    }

    @Test
    void oneAiAlertNotificationPerAddressedDoctorAndNoneForThePatientCircle() throws Exception {
        relate(otherDoctor, patient);
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));

        int toCareTeam = aiAlertService.raise(command(null, "CRITICAL", "N:1")).orElseThrow();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT system_id, category, priority, target, title, message,"
                + " resource_type, resource_id, dedupe_key, action_url, patient_system_id FROM notifications"
                + " WHERE type = 'AI_ALERT' ORDER BY system_id");
        assertEquals(2, rows.size());
        Set<String> recipients = new HashSet<>();
        for (Map<String, Object> row : rows) {
            recipients.add((String) row.get("system_id"));
            assertEquals("CRITICAL", row.get("category"));
            assertEquals("CRITICAL", row.get("priority"));
            assertEquals("AI_ALERTS", row.get("target"));
            assertEquals("Critical clinical alert", row.get("title"));
            assertEquals("An automated screening alert was raised for one of your patients. Open Clinical Alerts to"
                    + " review it.", row.get("message"));
            assertEquals("AI_ALERT", row.get("resource_type"));
            assertEquals(toCareTeam, row.get("resource_id"));
            assertEquals("AI_ALERT:" + toCareTeam, row.get("dedupe_key"));
            assertEquals("PAT-00001", row.get("patient_system_id"));
            String actionUrl = (String) row.get("action_url");
            assertTrue(actionUrl.startsWith("/dashboard/doctor?section=ai-alerts"), actionUrl);
            assertTrue(actionUrl.contains("id=" + toCareTeam), actionUrl);
        }
        assertEquals(Set.of("DOC-00001", "DOC-00002"), recipients);

        // addressed to one doctor: only that doctor, with the mapped priority and the non-critical title
        int addressed = aiAlertService.raise(command(otherDoctor, "MEDIUM", "N:2")).orElseThrow();
        List<Map<String, Object>> second = jdbc.queryForList("SELECT system_id, priority, title FROM notifications"
                + " WHERE type = 'AI_ALERT' AND resource_id = ?", addressed);
        assertEquals(1, second.size());
        assertEquals("DOC-00002", second.get(0).get("system_id"));
        assertEquals("NORMAL", second.get(0).get("priority"));
        assertEquals("New clinical alert", second.get(0).get("title"));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'AI_ALERT'"
                + " AND system_id IN ('PAT-00001', 'CGV-00001', 'ADM-00001')", Integer.class));

        // the stored alert keeps the command's addressee: null for the care-team alert
        assertTrue(json(doGet(ALERTS, doctor)).path("content").get(1).path("doctorSystemId").isNull());
    }

    private AiAlertCommand command(User toDoctor, String priority, String dedupeKey) {
        return new AiAlertCommand(patient.getSystemId(), toDoctor != null ? toDoctor.getSystemId() : null,
                "CRITICAL_RESULT", priority, "Critical lab result",
                "A lab result for this patient was flagged as critical and needs review.", true,
                "Review the result and contact the patient.", "LAB_RESULT", 87, null, dedupeKey);
    }
}
