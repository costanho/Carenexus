package com.carenexus.api.ai.detection;

import com.carenexus.api.ai.detection.DetectionTestSupport.SwitchableRiskDetectionProvider;
import com.carenexus.api.ai.detection.DetectionTestSupport.SwitchableRiskDetectionProvider.Mode;
import com.carenexus.api.ai.detection.config.AiDetectionProperties;
import com.carenexus.api.ai.provider.DetectionInput;
import com.carenexus.api.common.event.HealthMetricRecordedEvent;
import com.carenexus.api.common.event.SymptomLoggedEvent;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The automatic screening pipeline end to end: a committed symptom log or abnormal reading starts a screening after
 * commit, a MEDIUM or higher result stores one PENDING_REVIEW detection with its de-identified input and raises
 * exactly one DISEASE_RISK alert; a normal reading is not screened; nothing below MEDIUM is stored; the uniqueness
 * rule per patient, primary condition and day; and a switched-off, failing or hanging provider never fails or
 * blocks the symptom or reading write.
 */
@Import(DetectionTestSupport.class)
class DiseaseDetectionPipelineTest extends CommittedIntegrationTest {

    private static final String SYMPTOMS = "/api/symptom-logs";
    private static final String METRICS = "/api/health-metrics";

    @Autowired
    private SwitchableRiskDetectionProvider provider;

    @Autowired
    private AiDetectionProperties properties;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private Clock clock;

    private long originalTimeout;

    @BeforeEach
    void resetProvider() {
        provider.reset();
        originalTimeout = properties.getAssessmentTimeoutMs();
    }

    @AfterEach
    void restoreProvider() {
        provider.reset();
        properties.setAssessmentTimeoutMs(originalTimeout);
    }

    // ==================== helpers ====================

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private int logSymptoms(String severity, String... symptoms) throws Exception {
        MvcResult result = doPost(SYMPTOMS, patient, Map.of("symptoms", List.of(symptoms), "severity", severity));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result).path("symptomId").asInt();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int detections() {
        return count("SELECT COUNT(*) FROM disease_detection");
    }

    private int diseaseRiskAlerts() {
        return count("SELECT COUNT(*) FROM ai_alerts WHERE alert_type = 'DISEASE_RISK'");
    }

    private int aiAlertNotifications(String systemId) {
        return count("SELECT COUNT(*) FROM notifications WHERE type = 'AI_ALERT' AND system_id = ?", systemId);
    }

    private static Map<String, Object> reading(String type, Object value) {
        return Map.of("type", type, "value", value);
    }

    // ==================== automatic screening ====================

    @Test
    void severeSymptomLogWithFeverStoresOnePendingDetectionAndRaisesOneAlert() throws Exception {
        DetectionTestSupport.insertReading(jdbc, patient.getSystemId(), "TEMPERATURE", "39.6", "C", now().minusHours(2));
        jdbc.update("UPDATE patients SET date_of_birth = ?, gender = 'FEMALE', chronic_conditions = '[\"Asthma\"]'::jsonb "
                + "WHERE system_id = ?", today().minusYears(44).minusDays(3), patient.getSystemId());

        int symptomId = logSymptoms("SEVERE", "Dry cough", "Shortness of breath");

        assertEquals(1, detections());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM disease_detection");
        int detectionId = ((Number) row.get("detection_id")).intValue();
        String key = "DISEASE_RISK:" + patient.getSystemId() + ":FEVER_RESPIRATORY:" + today();
        assertEquals(patient.getSystemId(), row.get("patient_system_id"));
        assertEquals("PENDING_REVIEW", row.get("review_status"));
        assertEquals("SYMPTOM_LOG", row.get("source_type"));
        assertEquals(symptomId, ((Number) row.get("source_id")).intValue());
        assertEquals(symptomId, ((Number) row.get("symptom_id")).intValue());
        assertNull(row.get("doctor_system_id"));
        assertEquals("rules-v1", row.get("model_version"));
        assertEquals("HIGH", row.get("risk_level"));
        assertEquals(0, new BigDecimal("0.75").compareTo((BigDecimal) row.get("confidence_score")));
        assertEquals(key, row.get("dedupe_key"));
        assertEquals(Boolean.FALSE, row.get("is_confirmed"));

        JsonNode conditions = objectMapper.readTree(row.get("detected_conditions").toString());
        assertEquals("FEVER_RESPIRATORY", conditions.get(0).path("code").asText());
        assertEquals("SEVERE_SYMPTOMS", conditions.get(1).path("code").asText());
        assertEquals(2, objectMapper.readTree(row.get("recommendations").toString()).size());

        // The stored input is de-identified: an age band, no name, system id, date of birth or contact detail
        String input = row.get("input_data").toString();
        JsonNode inputJson = objectMapper.readTree(input);
        assertEquals("40-49", inputJson.path("ageBand").asText());
        assertEquals("FEMALE", inputJson.path("sex").asText());
        assertEquals("Asthma", inputJson.path("chronicConditions").get(0).asText());
        assertEquals("TEMPERATURE", inputJson.path("latestReadings").get(0).path("type").asText());
        assertEquals("SEVERE", inputJson.path("symptomLogs").get(0).path("severity").asText());
        for (String identifying : List.of(patient.getSystemId(), patient.getFirstName(), patient.getLastName(),
                patient.getEmail(), patient.getPhone(), today().minusYears(44).minusDays(3).toString())) {
            assertFalse(input.contains(identifying), "input_data must not contain " + identifying);
        }

        // Exactly one DISEASE_RISK alert, linked to the detection, addressed to every related doctor
        assertEquals(1, diseaseRiskAlerts());
        Map<String, Object> alert = jdbc.queryForMap("SELECT * FROM ai_alerts WHERE alert_type = 'DISEASE_RISK'");
        assertEquals(detectionId, ((Number) alert.get("detection_id")).intValue());
        assertEquals(key, alert.get("dedupe_key"));
        assertEquals("HIGH", alert.get("priority"));
        assertNull(alert.get("doctor_system_id"));
        assertEquals("Possible health risk", alert.get("title"));
        assertEquals("Rule-based screening of recent symptoms and readings flagged: Fever with respiratory symptoms, "
                + "Severe symptoms reported.", alert.get("message"));
        assertEquals("Review the detection and confirm or reject it.", alert.get("suggested_action"));
        assertEquals(Boolean.TRUE, alert.get("action_required"));
        assertEquals("SYMPTOM_LOG", alert.get("source_type"));
        assertEquals(symptomId, ((Number) alert.get("source_id")).intValue());
        assertEquals(1, aiAlertNotifications(doctor.getSystemId()));
        assertEquals(0, aiAlertNotifications(otherDoctor.getSystemId()));
        assertEquals(0, aiAlertNotifications(patient.getSystemId()));

        // A second screening the same day with the same primary condition changes nothing
        logSymptoms("CRITICAL", "Dry cough");
        assertEquals(1, detections());
        assertEquals(1, diseaseRiskAlerts());
        assertEquals("HIGH", jdbc.queryForObject("SELECT risk_level FROM disease_detection", String.class));
    }

    @Test
    void abnormalBloodPressurePairIsScreenedOnce() throws Exception {
        MvcResult recorded = doPost(METRICS, patient, Map.of("readings",
                List.of(reading("BP_SYSTOLIC", 168), reading("BP_DIASTOLIC", 104))));
        assertEquals(201, recorded.getResponse().getStatus(), recorded.getResponse().getContentAsString());
        List<Integer> metricIds = List.of(json(recorded).path("metrics").get(0).path("metricId").asInt(),
                json(recorded).path("metrics").get(1).path("metricId").asInt());

        assertEquals(1, detections());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM disease_detection");
        assertEquals("HEALTH_METRIC", row.get("source_type"));
        assertTrue(metricIds.contains(((Number) row.get("source_id")).intValue()));
        assertNull(row.get("symptom_id"));
        assertEquals("HIGH", row.get("risk_level"));
        assertEquals("DISEASE_RISK:" + patient.getSystemId() + ":HYPERTENSIVE_BP:" + today(), row.get("dedupe_key"));
        assertTrue(row.get("detected_conditions").toString().contains("Blood pressure 168/104 mmHg recorded on " + today()));

        assertEquals(1, diseaseRiskAlerts());
        assertEquals("HEALTH_METRIC", jdbc.queryForObject("SELECT source_type FROM ai_alerts WHERE alert_type = 'DISEASE_RISK'",
                String.class));
        assertEquals(2, provider.calls(), "each abnormal reading starts a screening; the second finds the stored row");
    }

    @Test
    void normalReadingIsNotScreened() throws Exception {
        MvcResult recorded = doPost(METRICS, patient, Map.of("readings", List.of(reading("HEART_RATE", 74))));
        assertEquals(201, recorded.getResponse().getStatus(), recorded.getResponse().getContentAsString());
        assertEquals(0, provider.calls());
        assertEquals(0, detections());
    }

    @Test
    void resultBelowMediumStoresNothing() throws Exception {
        logSymptoms("MILD", "Runny nose");
        assertEquals(1, provider.calls());
        DetectionInput input = provider.lastInput();
        assertNotNull(input);
        assertEquals(1, input.symptomLogs().size());
        assertEquals(List.of("Runny nose"), input.symptomLogs().get(0).symptoms());
        assertEquals(0, detections());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_alerts"));
    }

    @Test
    void screeningInputHonoursTheWindowsAndSkipsDeletedLogs() throws Exception {
        String id = patient.getSystemId();
        DetectionTestSupport.insertReading(jdbc, id, "SPO2", "85", "%", now().minusDays(7));        // outside 7 days
        DetectionTestSupport.insertReading(jdbc, id, "GLUCOSE", "5.2", "mmol/L", now().minusDays(6).withHour(0).withMinute(5));
        DetectionTestSupport.insertReading(jdbc, id, "GLUCOSE", "4.8", "mmol/L", now().minusHours(1)); // latest glucose
        DetectionTestSupport.insertSymptomLog(jdbc, id, "CRITICAL", now().minusDays(14), "Chest pain");  // outside 14 days
        int deleted = DetectionTestSupport.insertSymptomLog(jdbc, id, "CRITICAL", now().minusDays(2), "Chest pain");
        jdbc.update("UPDATE symptom_logs SET is_deleted = TRUE, deleted_at = ? WHERE symptom_id = ?", now(), deleted);
        jdbc.update("INSERT INTO prescriptions (patient_system_id, doctor_system_id, medication_name, dosage, frequency, "
                + "status, prescribed_date, expiry_date) VALUES (?, ?, 'Metformin', '500mg', 'Twice daily', 'ACTIVE', ?, ?)",
                id, doctor.getSystemId(), today().minusDays(10), today().plusDays(20));

        logSymptoms("MODERATE", "Headache");

        DetectionInput input = provider.lastInput();
        assertNotNull(input);
        assertEquals(today(), input.assessedOn());
        assertEquals(1, input.latestReadings().size());
        assertEquals("GLUCOSE", input.latestReadings().get(0).type());
        assertEquals(0, new BigDecimal("4.8").compareTo(input.latestReadings().get(0).value()));
        assertEquals(1, input.symptomLogs().size());
        assertEquals("MODERATE", input.symptomLogs().get(0).severity());
        assertEquals(List.of("Metformin"), input.activeMedications());
        assertNull(input.ageBand());
        assertEquals(0, detections());
    }

    // ==================== failures never fail the origin ====================

    @Test
    void providerSwitchedOffScreensNothingAndTheWriteSucceeds() throws Exception {
        provider.set(Mode.DISABLED);
        DetectionTestSupport.insertReading(jdbc, patient.getSystemId(), "SPO2", "85", "%", now());

        logSymptoms("CRITICAL", "Chest pain");
        assertEquals(1, count("SELECT COUNT(*) FROM symptom_logs"));
        assertEquals(0, provider.calls());
        assertEquals(0, detections());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_alerts"));
    }

    @Test
    void providerFailureDoesNotFailTheWrite() throws Exception {
        provider.set(Mode.FAIL);
        DetectionTestSupport.insertReading(jdbc, patient.getSystemId(), "SPO2", "85", "%", now());

        logSymptoms("CRITICAL", "Chest pain");
        MvcResult recorded = doPost(METRICS, patient, Map.of("readings", List.of(reading("SPO2", 86))));
        assertEquals(201, recorded.getResponse().getStatus(), recorded.getResponse().getContentAsString());

        assertEquals(2, provider.calls());
        assertEquals(1, count("SELECT COUNT(*) FROM symptom_logs"));
        assertEquals(2, count("SELECT COUNT(*) FROM health_metrics"));
        assertEquals(0, detections());
        assertEquals(0, diseaseRiskAlerts());
        // the health-tracking care-team notification of the critical log still went out
        assertTrue(count("SELECT COUNT(*) FROM notifications WHERE type = 'SYSTEM' AND system_id = ?",
                doctor.getSystemId()) >= 1);
    }

    @Test
    void providerTimeoutDoesNotFailOrBlockTheWrite() throws Exception {
        provider.set(Mode.HANG);
        properties.setAssessmentTimeoutMs(300);
        DetectionTestSupport.insertReading(jdbc, patient.getSystemId(), "SPO2", "85", "%", now());

        long start = System.nanoTime();
        logSymptoms("CRITICAL", "Chest pain");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 5_000, "the write waited " + elapsedMs + " ms");
        assertEquals(1, provider.calls());
        assertEquals(1, count("SELECT COUNT(*) FROM symptom_logs"));
        assertEquals(0, detections());

        // the next screening works again once the provider answers
        provider.set(Mode.RULES);
        logSymptoms("CRITICAL", "Chest pain");
        assertEquals(1, detections());
    }

    @Test
    void whileTheProviderWaits_theScreeningHoldsNoOpenDatabaseTransaction() throws Exception {
        provider.set(Mode.HANG);
        properties.setAssessmentTimeoutMs(3_000);
        DetectionTestSupport.insertReading(jdbc, patient.getSystemId(), "SPO2", "85", "%", now());

        // Published outside a transaction (fallbackExecution): the screening starts on the dispatcher's worker
        eventPublisher.publishEvent(new HealthMetricRecordedEvent(515_151, patient.getSystemId(),
                patient.getSystemId(), "SPO2", true, HealthMetricRecordedEvent.CRITICAL, "MANUAL"));
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (provider.calls() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, provider.calls(), "the provider was called");
        Thread.sleep(200);

        // The input was read in a transaction that has already ended: no session of this database sits idle in a
        // transaction (the pooled connection is back in the pool) while the provider is waited for
        assertEquals(0, count("SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() "
                + "AND state LIKE 'idle in transaction%'"));

        awaitBackgroundWork();
        assertEquals(0, detections(), "the timed-out screening wrote nothing");
    }

    @Test
    void eventsForUnknownPatientsOrRowsAreHandledQuietly() {
        // An event outside a transaction (fallbackExecution) about a patient that does not exist: nothing happens
        eventPublisher.publishEvent(new SymptomLoggedEvent(999_999, "PAT-99999", "PAT-99999", "SEVERE"));
        awaitBackgroundWork();
        assertEquals(0, provider.calls());
        assertEquals(0, detections());

        // A reading id that no longer exists still screens the patient's data; the log link stays empty
        DetectionTestSupport.insertReading(jdbc, patient.getSystemId(), "SPO2", "85", "%", now());
        eventPublisher.publishEvent(new HealthMetricRecordedEvent(424_242, patient.getSystemId(),
                patient.getSystemId(), "SPO2", true, HealthMetricRecordedEvent.CRITICAL, "MANUAL"));
        awaitBackgroundWork();
        assertEquals(1, detections());
        Map<String, Object> row = jdbc.queryForMap("SELECT source_type, source_id, symptom_id, risk_level FROM disease_detection");
        assertEquals("HEALTH_METRIC", row.get("source_type"));
        assertEquals(424_242, ((Number) row.get("source_id")).intValue());
        assertNull(row.get("symptom_id"));
        assertEquals("CRITICAL", row.get("risk_level"));
        assertEquals(1, diseaseRiskAlerts());

        // A symptom event whose log does not exist keeps symptom_id empty (no dangling reference)
        eventPublisher.publishEvent(new SymptomLoggedEvent(777_777, patient.getSystemId(), patient.getSystemId(), "SEVERE"));
        awaitBackgroundWork();
        assertEquals(1, detections(), "same primary condition and day: nothing new");

        // A normal reading event is ignored
        eventPublisher.publishEvent(new HealthMetricRecordedEvent(424_243, patient.getSystemId(),
                patient.getSystemId(), "SPO2", false, HealthMetricRecordedEvent.NORMAL, "MANUAL"));
        awaitBackgroundWork();
        assertEquals(2, provider.calls());
    }
}
