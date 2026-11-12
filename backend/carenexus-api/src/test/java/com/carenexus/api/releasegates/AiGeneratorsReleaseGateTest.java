package com.carenexus.api.releasegates;

import com.carenexus.api.ai.generator.AiGeneratorFixtures;
import com.carenexus.api.ai.generator.service.MedicationInteractionAlertGenerator;
import com.carenexus.api.ai.interaction.InteractionHit;
import com.carenexus.api.ai.interaction.MedicationInteractionChecker;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.core.prescriptions.PrescriptionFixtures.createBody;
import static com.carenexus.api.core.results.ClinicalResultFixtures.imagingBody;
import static com.carenexus.api.core.results.ClinicalResultFixtures.labBody;
import static com.carenexus.api.medication.MedicationFixtures.insertDose;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Release gate of the alert generators (api/be-ai-generators.md), end to end over HTTP with committed transactions:
 * the originating endpoints of lab and imaging results, vitals, prescriptions and dose marks answer exactly as before,
 * and by the time they answer the expected AI alerts and doctors' AI_ALERT notifications exist, once per dedupe key.
 * A failing interaction provider never fails the prescription.
 */
class AiGeneratorsReleaseGateTest extends CommittedIntegrationTest {

    private static final String LAB = "/api/lab-results";
    private static final String IMAGING = "/api/imaging-results";
    private static final String ALERTS = "/api/ai-alerts";
    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private Clock clock;

    @Autowired
    private MedicationInteractionAlertGenerator interactionGenerator;

    private AiGeneratorFixtures fixtures;

    @BeforeEach
    void setUpFixtures() {
        fixtures = new AiGeneratorFixtures(jdbc);
    }

    private JsonNode created(MvcResult result) throws Exception {
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private void assertOk(MvcResult result) throws Exception {
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private static Map<String, Object> reading(String type, Object value, LocalDateTime at) {
        return Map.of("type", type, "value", value, "recordedAt", at.withNano(0).format(WALL_CLOCK));
    }

    // ==================== critical results ====================

    @Test
    void aCriticalLabGivesExactlyOneAlertAndOneNotificationAndRepeatedUpdatesAddNone() throws Exception {
        Map<String, Object> body = labBody(patient.getSystemId());
        body.put("status", "COMPLETED");
        body.put("isCritical", true);
        JsonNode lab = created(doPost(LAB, doctor, body));
        int resultId = lab.path("resultId").asInt();
        assertEquals("CRITICAL", lab.path("status").asText());
        assertFalse(lab.has("alertId"), "no originating response carries an alert id");

        List<Map<String, Object>> alerts = fixtures.alerts(patient.getSystemId());
        assertEquals(1, alerts.size());
        Map<String, Object> alert = alerts.get(0);
        assertEquals("CRITICAL_RESULT", alert.get("alert_type"));
        assertEquals("CRITICAL", alert.get("priority"));
        assertEquals("LAB_RESULT", alert.get("source_type"));
        assertEquals(resultId, alert.get("source_id"));
        assertEquals("CRITICAL_RESULT:LAB:" + resultId, alert.get("dedupe_key"));
        int alertId = (Integer) alert.get("alert_id");

        List<Map<String, Object>> notifications = fixtures.notificationsAbout(alertId);
        assertEquals(1, notifications.size());
        assertEquals(doctor.getSystemId(), notifications.get(0).get("system_id"),
                "the doctor who recorded the result is notified too");
        assertEquals("CRITICAL", notifications.get(0).get("priority"));
        assertEquals("AI_ALERT:" + alertId, notifications.get(0).get("dedupe_key"));
        assertEquals(1, fixtures.aiAlertNotifications());

        // Repeated updates, including the result becoming critical a second time, add nothing.
        assertOk(doPut(LAB + "/" + resultId, doctor, Map.of("unit", "g/dL")));
        assertOk(doPut(LAB + "/" + resultId, doctor, Map.of("isCritical", false)));
        assertOk(doPut(LAB + "/" + resultId, doctor, Map.of("isCritical", true)));
        assertEquals(1, fixtures.alertCount());
        assertEquals(1, fixtures.aiAlertNotifications());

        // The doctor reads it in Clinical Alerts; the patient never sees a critical-result alert.
        JsonNode inbox = json(doGet(ALERTS, doctor));
        assertEquals(1, inbox.path("content").size(), inbox.toString());
        assertEquals("Review the result and contact the patient.",
                inbox.path("content").get(0).path("suggestedAction").asText());
        JsonNode own = json(doGet(ALERTS + "/patient/" + patient.getSystemId(), patient));
        assertEquals(0, own.path("content").size(), own.toString());
        assertEquals(0, json(doGet(ALERTS, otherDoctor)).path("content").size());
    }

    @Test
    void aCriticalImagingResultRaisesItsOwnAlert() throws Exception {
        Map<String, Object> body = imagingBody(patient.getSystemId());
        body.put("status", "CRITICAL");
        int imagingId = created(doPost(IMAGING, doctor, body)).path("imagingId").asInt();

        List<Map<String, Object>> alerts = fixtures.alerts(patient.getSystemId());
        assertEquals(1, alerts.size());
        assertEquals("CRITICAL_RESULT:IMAGING:" + imagingId, alerts.get(0).get("dedupe_key"));
        assertEquals("IMAGING_RESULT", alerts.get(0).get("source_type"));
        assertEquals(1, fixtures.aiAlertNotifications(doctor.getSystemId()));
    }

    @Test
    void aResultThatIsNotCriticalRaisesNothing() throws Exception {
        Map<String, Object> body = labBody(patient.getSystemId());
        body.put("status", "COMPLETED");
        created(doPost(LAB, doctor, body));
        assertEquals(0, fixtures.alertCount());
        assertEquals(0, fixtures.aiAlertNotifications());
    }

    // ==================== vitals ====================

    @Test
    void abnormalVitalsRaiseOneAlertPerReadingWithThePriorityOfTheirSeverity() throws Exception {
        LocalDateTime now = LocalDateTime.now().minusMinutes(5).withNano(0);
        MvcResult bp = doPost("/api/health-metrics", patient, Map.of("readings", List.of(
                reading("BP_SYSTOLIC", 152, now), reading("BP_DIASTOLIC", 96, now), reading("HEART_RATE", 74, now))));
        created(bp);
        MvcResult spo2 = doPost("/api/health-metrics", patient, Map.of("readings", List.of(reading("SPO2", 85, now))));
        created(spo2);

        List<Map<String, Object>> alerts = fixtures.alerts(patient.getSystemId(), "CRITICAL_VITALS");
        assertEquals(3, alerts.size(), alerts.toString());
        assertEquals(List.of("HIGH", "HIGH", "CRITICAL"), alerts.stream().map(a -> a.get("priority")).toList());
        assertEquals(List.of(false, false, true), alerts.stream().map(a -> a.get("action_required")).toList());
        assertEquals("A oxygen saturation reading for this patient is in the critical range.",
                alerts.get(2).get("message"));
        // Count only this slice's output: be-ai-detection may screen the same readings and raise DISEASE_RISK alerts
        // with their own AI_ALERT notifications (API-NOTES), which this gate does not own.
        for (Map<String, Object> alert : alerts) {
            List<Map<String, Object>> notifications = fixtures.notificationsAbout((Integer) alert.get("alert_id"));
            assertEquals(1, notifications.size(), notifications.toString());
            assertEquals(doctor.getSystemId(), notifications.get(0).get("system_id"));
        }
        int heartRate = fixtures.metricId(patient.getSystemId(), "HEART_RATE");
        assertTrue(fixtures.alertsFromSource("HEALTH_METRIC", heartRate).stream()
                        .noneMatch(a -> List.of("CRITICAL_VITALS", "ANOMALY_DETECTED").contains(a.get("alert_type"))),
                "the normal heart rate raises nothing");
        assertEquals(0, fixtures.aiAlertNotifications(patient.getSystemId()));
        assertEquals(0, fixtures.aiAlertNotifications(otherDoctor.getSystemId()));
    }

    // ==================== medication ====================

    @Test
    void aPrescriptionThatInteractsWithAnActiveOneAlertsThePrescriber() throws Exception {
        LocalDate today = LocalDate.now(clock);
        fixtures.prescription(patient.getSystemId(), otherDoctor.getSystemId(), "Warfarin", null, "ACTIVE",
                today.minusDays(5), null);
        Map<String, Object> body = createBody(patient);
        body.put("medicationName", "Aspirin 75 mg");
        body.put("genericName", "aspirin");
        int prescriptionId = created(doPost("/api/prescriptions", doctor, body)).path("prescriptionId").asInt();

        List<Map<String, Object>> alerts = fixtures.alerts(patient.getSystemId(), "MEDICATION_INTERACTION");
        assertEquals(1, alerts.size());
        Map<String, Object> alert = alerts.get(0);
        assertEquals("HIGH", alert.get("priority"));
        assertEquals(doctor.getSystemId(), alert.get("doctor_system_id"));
        assertEquals(prescriptionId, alert.get("source_id"));
        assertEquals("Aspirin 75 mg may interact with Warfarin, which this patient is currently prescribed.",
                alert.get("message"));
        assertEquals("MEDICATION_INTERACTION:" + prescriptionId + ":warfarin", alert.get("dedupe_key"));
        assertEquals(1, fixtures.aiAlertNotifications(doctor.getSystemId()));
        assertEquals(0, fixtures.aiAlertNotifications(otherDoctor.getSystemId()));
    }

    @Test
    void aFailingInteractionProviderNeverFailsThePrescription() throws Exception {
        LocalDate today = LocalDate.now(clock);
        fixtures.prescription(patient.getSystemId(), doctor.getSystemId(), "Warfarin", null, "ACTIVE",
                today.minusDays(5), null);
        MedicationInteractionChecker original = (MedicationInteractionChecker)
                ReflectionTestUtils.getField(interactionGenerator, "checker");
        MedicationInteractionChecker failing = new MedicationInteractionChecker() {
            @Override
            public String providerName() {
                return "failing";
            }

            @Override
            public List<InteractionHit> check(String newMedicationName, List<String> activeMedicationNames) {
                throw new IllegalStateException("interaction service unavailable");
            }
        };
        ReflectionTestUtils.setField(interactionGenerator, "checker", failing);
        try {
            Map<String, Object> body = createBody(patient);
            body.put("medicationName", "Aspirin");
            body.put("genericName", "aspirin");
            JsonNode issued = created(doPost("/api/prescriptions", doctor, body));
            assertEquals("ACTIVE", issued.path("status").asText());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM prescriptions WHERE prescription_id = ?",
                    Integer.class, issued.path("prescriptionId").asInt()));
        } finally {
            ReflectionTestUtils.setField(interactionGenerator, "checker", original);
        }
        assertEquals(0, fixtures.alertCount());
        assertEquals(0, fixtures.aiAlertNotifications());
    }

    @Test
    void theThirdMissedDoseInARowRaisesOneAlertThatThePatientCanRead() throws Exception {
        LocalDate today = LocalDate.now(clock);
        int rx = fixtures.prescription(patient.getSystemId(), doctor.getSystemId(), "Metformin", null, "ACTIVE",
                today.minusDays(10), null);
        insertDose(jdbc, rx, patient.getSystemId(), today.minusDays(1), LocalTime.of(20, 0), "MISSED", null);
        int morning = insertDose(jdbc, rx, patient.getSystemId(), today, LocalTime.of(8, 0), "PENDING", null);
        int evening = insertDose(jdbc, rx, patient.getSystemId(), today, LocalTime.of(20, 0), "PENDING", null);

        assertOk(doPatch("/api/medication-schedules/" + morning + "/missed", patient, null));
        assertEquals(0, fixtures.alertCount(), "two in a row is below the threshold");

        assertOk(doPatch("/api/medication-schedules/" + evening + "/missed", patient, null));
        List<Map<String, Object>> alerts = fixtures.alerts(patient.getSystemId(), "MISSED_MEDICATION");
        assertEquals(1, alerts.size());
        assertEquals("MISSED_MEDICATION:" + rx + ":" + today, alerts.get(0).get("dedupe_key"));
        assertEquals("PRESCRIPTION", alerts.get(0).get("source_type"));
        assertEquals(1, fixtures.aiAlertNotifications(doctor.getSystemId()));

        // Undo and mark again: the same day's alert is not repeated.
        assertOk(doPatch("/api/medication-schedules/" + evening + "/undo", patient, null));
        assertOk(doPatch("/api/medication-schedules/" + evening + "/missed", patient, null));
        assertEquals(1, fixtures.alertCount());
        assertEquals(1, fixtures.aiAlertNotifications(doctor.getSystemId()));

        // The patient reads the alert verbatim, without the clinician's suggested action.
        JsonNode own = json(doGet(ALERTS + "/patient/" + patient.getSystemId(), patient));
        assertEquals(1, own.path("content").size(), own.toString());
        JsonNode row = own.path("content").get(0);
        assertEquals("MISSED_MEDICATION", row.path("alertType").asText());
        assertEquals("Missed medication doses", row.path("title").asText());
        assertEquals("3 scheduled doses in a row were not marked as taken.", row.path("message").asText());
        assertTrue(row.path("suggestedAction").isNull(), row.toString());
    }

    @Test
    void anUnrelatedPatientsEventsNeverReachThisPatientsDoctor() throws Exception {
        Map<String, Object> body = new HashMap<>(labBody(otherPatient.getSystemId()));
        body.put("status", "CRITICAL");
        body.put("isCritical", true);
        created(doPost(LAB, otherDoctor, body));

        assertEquals(1, fixtures.aiAlertNotifications(otherDoctor.getSystemId()));
        assertEquals(0, fixtures.aiAlertNotifications(doctor.getSystemId()));
        assertEquals(0, json(doGet(ALERTS, doctor)).path("content").size());
    }
}
