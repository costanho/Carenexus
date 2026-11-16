package com.carenexus.api.releasegates;

import com.carenexus.api.common.event.HealthMetricRecordedEvent;
import com.carenexus.api.common.event.SymptomLoggedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Release gate for health tracking (vitals and symptom logs), end to end over HTTP with committed transactions:
 * a patient and their caregiver record readings, the doctor sees severity computed by the server, the latest
 * values with the derived BMI and the daily trend; a severe symptom log reaches the care team; access ends the
 * moment a grant is narrowed or expires; administrators can read but never author clinical data.
 */
@RecordApplicationEvents
class HealthTrackingReleaseGateTest extends CommittedIntegrationTest {

    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private ApplicationEvents events;

    private static Map<String, Object> reading(String type, Object value, LocalDateTime at) {
        return Map.of("type", type, "value", value, "recordedAt", at.withNano(0).format(WALL_CLOCK));
    }

    @Test
    void vitalsFlowFromEntryToTheDoctorsView() throws Exception {
        String patientId = patient.getSystemId();
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1).withHour(8).withMinute(0).withSecond(0);
        LocalDateTime now = LocalDateTime.now().minusMinutes(5).withNano(0);

        // The patient records an elevated blood pressure with heart rate
        MvcResult bp = doPostWithHeaders("/api/health-metrics", patient, Map.of("readings", List.of(
                reading("BP_SYSTOLIC", 152, now), reading("BP_DIASTOLIC", 96, now), reading("HEART_RATE", 74, now))),
                Map.of("Idempotency-Key", "gate-vitals-0001"));
        assertEquals(201, bp.getResponse().getStatus(), bp.getResponse().getContentAsString());
        // A caregiver allowed to record adds weight and height for yesterday
        grantAccess(caregiver, patient, a -> {
            a.setCanViewRecords(true);
            a.setCanManageCarePlan(true);
        });
        MvcResult body = doPost("/api/health-metrics", caregiver, Map.of("patientSystemId", patientId,
                "readings", List.of(reading("WEIGHT", 88, yesterday), reading("HEIGHT", 175, yesterday))));
        assertEquals(201, body.getResponse().getStatus(), body.getResponse().getContentAsString());
        // A retried submission of the first form stores nothing new
        MvcResult retry = doPostWithHeaders("/api/health-metrics", patient, Map.of("readings", List.of(
                reading("BP_SYSTOLIC", 152, now), reading("BP_DIASTOLIC", 96, now), reading("HEART_RATE", 74, now))),
                Map.of("Idempotency-Key", "gate-vitals-0001"));
        assertEquals(200, retry.getResponse().getStatus());
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM health_metrics WHERE patient_system_id = ?",
                Integer.class, patientId));

        List<HealthMetricRecordedEvent> recorded = events.stream(HealthMetricRecordedEvent.class).toList();
        assertEquals(5, recorded.size());
        assertEquals(2, recorded.stream().filter(HealthMetricRecordedEvent::abnormal).count());

        // The doctor sees the server's severity, the derived BMI and the trend
        JsonNode latest = json(doGet("/api/health-metrics/patient/" + patientId + "/latest", doctor));
        assertEquals(5, latest.path("metrics").size());
        assertEquals("ABNORMAL", latest.path("metrics").get(0).path("severity").asText());
        assertEquals(json(bp).path("readingGroupId").asText(), latest.path("metrics").get(1).path("readingGroupId").asText());
        JsonNode bmi = latest.path("bmi");
        assertEquals(0, new BigDecimal("28.7").compareTo(bmi.path("value").decimalValue()));
        assertEquals("ABNORMAL", bmi.path("severity").asText());
        latest.path("metrics").forEach(m -> assertFalse(m.path("canDelete").asBoolean(), "the doctor recorded nothing"));

        JsonNode trend = json(doGet("/api/health-metrics/patient/" + patientId + "/trend?type=WEIGHT&days=7", doctor));
        assertEquals(1, trend.path("points").size());
        assertEquals(LocalDate.now().minusDays(1).toString(), trend.path("points").get(0).path("date").asText());

        // Narrowing the grant to read-only stops recording at once; expiry stops reading
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertEquals(403, doPost("/api/health-metrics", caregiver, Map.of("patientSystemId", patientId,
                "readings", List.of(reading("WEIGHT", 87, now)))).getResponse().getStatus());
        assertEquals(200, doGet("/api/health-metrics/patient/" + patientId, caregiver).getResponse().getStatus());
        grantAccess(caregiver, patient, a -> {
            a.setCanViewRecords(true);
            a.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        });
        assertEquals(403, doGet("/api/health-metrics/patient/" + patientId, caregiver).getResponse().getStatus());

        // Administrators read but never author or remove clinical data
        assertEquals(200, doGet("/api/health-metrics/patient/" + patientId, admin).getResponse().getStatus());
        assertEquals(403, doPost("/api/health-metrics", admin, Map.of("patientSystemId", patientId,
                "readings", List.of(reading("WEIGHT", 87, now)))).getResponse().getStatus());
        int metricId = json(bp).path("metrics").get(0).path("metricId").asInt();
        assertEquals(403, doDelete("/api/health-metrics/" + metricId, admin).getResponse().getStatus());

        // Strangers see nothing
        assertEquals(403, doGet("/api/health-metrics/patient/" + patientId + "/latest", otherPatient).getResponse().getStatus());
        assertEquals(403, doGet("/api/health-metrics/patient/" + patientId + "/latest", otherDoctor).getResponse().getStatus());
        assertEquals(403, doGet("/api/health-metrics/patient/" + patientId + "/latest", otherCaregiver).getResponse().getStatus());

        // The patient corrects a mistyped pair in one call
        assertEquals(204, doDelete("/api/health-metrics/" + metricId, patient).getResponse().getStatus());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM health_metrics WHERE patient_system_id = ?",
                Integer.class, patientId));
    }

    @Test
    void severeSymptomLogReachesTheCareTeamAndIsAudited() throws Exception {
        String patientId = patient.getSystemId();
        MvcResult created = doPost("/api/symptom-logs", patient, Map.of(
                "symptoms", List.of("Headache", "Blurred vision"), "severity", "SEVERE", "durationDays", 2));
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        int symptomId = json(created).path("symptomId").asInt();

        assertEquals(List.of(new SymptomLoggedEvent(symptomId, patientId, patientId, "SEVERE")),
                events.stream(SymptomLoggedEvent.class).toList());

        // The doctor's notification opens the patient's vitals tab on this log
        JsonNode inbox = json(doGet("/api/notifications", doctor));
        String body = inbox.toString();
        assertTrue(body.contains("Severe symptoms logged"), body);
        assertTrue(body.contains("section=patients"), body);
        assertTrue(body.contains("id=" + symptomId), body);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE system_id IN (?, ?)",
                Integer.class, patientId, otherDoctor.getSystemId()));

        JsonNode asDoctor = json(doGet("/api/symptom-logs/" + symptomId, doctor));
        assertEquals("PATIENT_REPORTED", asDoctor.path("source").asText());
        assertFalse(asDoctor.path("canDelete").asBoolean());
        assertEquals(403, doDelete("/api/symptom-logs/" + symptomId, doctor).getResponse().getStatus());
        assertEquals(403, doGet("/api/symptom-logs/" + symptomId, otherDoctor).getResponse().getStatus());

        // The author removes it; it disappears from every read, the audit trail stays
        assertEquals(204, doDelete("/api/symptom-logs/" + symptomId, patient).getResponse().getStatus());
        assertEquals(404, doGet("/api/symptom-logs/" + symptomId, doctor).getResponse().getStatus());
        assertEquals(0, json(doGet("/api/symptom-logs/patient/" + patientId, doctor)).path("totalElements").asInt());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM symptom_logs WHERE symptom_id = ? AND is_deleted",
                Integer.class, symptomId));
        for (String action : List.of("CREATE", "READ", "DELETE")) {
            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND resource_type = 'SYMPTOM_LOG' "
                    + "AND patient_system_id = ?", Integer.class, action, patientId) > 0, action);
        }
    }
}
