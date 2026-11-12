package com.carenexus.api.ai.alert;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules of {@link AiAlertService#raise}: what is stored (normalised type and priority, default and cut titles,
 * the source pair, a null dedupe key always inserting) and which commands are programming errors.
 */
class AiAlertServiceTest extends CommittedIntegrationTest {

    @Autowired
    private AiAlertService aiAlertService;

    private AiAlertCommand command(String type, String priority, String title, String message, String sourceType,
                                   Integer sourceId, Integer detectionId, String dedupeKey) {
        return new AiAlertCommand(patient.getSystemId(), null, type, priority, title, message, false, null,
                sourceType, sourceId, detectionId, dedupeKey);
    }

    private Map<String, Object> stored(int alertId) {
        return jdbc.queryForMap("SELECT * FROM ai_alerts WHERE alert_id = ?", alertId);
    }

    @Test
    void storesNormalisedValuesAndTheDefaultTitleOfTheType() {
        int alertId = aiAlertService.raise(command(" missed_medication ", "medium", "  ",
                "  Three scheduled doses in a row were not marked as taken.  ", " prescription ", 58, null, "  k:1  "))
                .orElseThrow();

        Map<String, Object> row = stored(alertId);
        assertEquals("MISSED_MEDICATION", row.get("alert_type"));
        assertEquals("MEDIUM", row.get("priority"));
        assertEquals("Missed medication doses", row.get("title"));
        assertEquals("Three scheduled doses in a row were not marked as taken.", row.get("message"));
        assertEquals("PRESCRIPTION", row.get("source_type"));
        assertEquals(58, row.get("source_id"));
        assertEquals("k:1", row.get("dedupe_key"));
        assertEquals(Boolean.FALSE, row.get("is_acknowledged"));
        assertNull(row.get("doctor_system_id"));
        assertNull(row.get("suggested_action"));
        assertNull(row.get("resolved_at"));
    }

    @Test
    void longTitlesAreCutTo160Characters() {
        String title = "x".repeat(200);
        int alertId = aiAlertService.raise(command("FOLLOW_UP_DUE", "LOW", title, "Follow-up visit is due.",
                null, null, null, null)).orElseThrow();
        assertEquals(160, ((String) stored(alertId).get("title")).length());
    }

    @Test
    void aNullDedupeKeyAlwaysInserts() {
        int first = aiAlertService.raise(command("FOLLOW_UP_DUE", "LOW", null, "Follow-up visit is due.", null, null,
                null, null)).orElseThrow();
        int second = aiAlertService.raise(command("FOLLOW_UP_DUE", "LOW", null, "Follow-up visit is due.", null, null,
                null, null)).orElseThrow();
        assertNotEquals(first, second);
    }

    @Test
    void anAlertForAPatientWithoutDoctorsIsStoredWithoutNotifications() {
        User lonely = createAndSaveUser(20, "PAT-00020", "lonely@example.com", "PATIENT");
        int alertId = aiAlertService.raise(new AiAlertCommand(lonely.getSystemId(), null, "CRITICAL_VITALS", "CRITICAL",
                null, "A reading was flagged as critical.", true, null, "HEALTH_METRIC", 4, null, "lonely:1"))
                .orElseThrow();
        assertEquals("PAT-00020", stored(alertId).get("patient_system_id"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class));
    }

    @Test
    void aDiseaseRiskAlertKeepsItsDetection() {
        int detectionId = jdbc.queryForObject("INSERT INTO disease_detection (patient_system_id, model_version,"
                + " detected_conditions) VALUES (?, 'rules-v1', '[]'::jsonb) RETURNING detection_id", Integer.class,
                patient.getSystemId());
        assertEquals("PENDING_REVIEW", jdbc.queryForObject(
                "SELECT review_status FROM disease_detection WHERE detection_id = ?", String.class, detectionId));

        int alertId = aiAlertService.raise(command("DISEASE_RISK", "HIGH", null, "Screening flagged a possible risk.",
                "SYMPTOM_LOG", 205, detectionId, null)).orElseThrow();
        assertEquals(detectionId, stored(alertId).get("detection_id"));
        assertEquals("Possible health risk", stored(alertId).get("title"));
    }

    @Test
    void brokenCommandsAreProgrammingErrors() {
        String ok = "Text.";
        assertThrows(IllegalArgumentException.class, () -> aiAlertService.raise(null));
        assertThrows(IllegalArgumentException.class, () -> aiAlertService.raise(
                command("FEVER", "LOW", null, ok, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> aiAlertService.raise(
                command("FOLLOW_UP_DUE", "URGENT", null, ok, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> aiAlertService.raise(
                command("FOLLOW_UP_DUE", "LOW", null, "  ", null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> aiAlertService.raise(
                command("FOLLOW_UP_DUE", "LOW", null, ok, "CONSULTATION", null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> aiAlertService.raise(
                command("FOLLOW_UP_DUE", "LOW", null, ok, null, 12, null, null)));
        assertThrows(IllegalArgumentException.class, () -> aiAlertService.raise(
                command("FOLLOW_UP_DUE", "LOW", null, ok, "APPOINTMENT", 12, null, null)));
        assertThrows(IllegalArgumentException.class, () -> aiAlertService.raise(
                command("DISEASE_RISK", "HIGH", null, ok, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> aiAlertService.raise(
                command("DISEASE_RISK", "HIGH", null, ok, null, null, 999999, null)));
        assertThrows(IllegalArgumentException.class, () -> aiAlertService.raise(
                command("FOLLOW_UP_DUE", "LOW", null, ok, null, null, null, "k".repeat(161))));
        assertThrows(IllegalArgumentException.class, () -> aiAlertService.raise(new AiAlertCommand(
                "PAT-09999", null, "FOLLOW_UP_DUE", "LOW", null, ok, false, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> aiAlertService.raise(new AiAlertCommand(
                patient.getSystemId(), "DOC-09999", "FOLLOW_UP_DUE", "LOW", null, ok, false, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> aiAlertService.raise(new AiAlertCommand(
                " ", null, "FOLLOW_UP_DUE", "LOW", null, ok, false, null, null, null, null, null)));

        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM ai_alerts", Integer.class) == 0);
    }
}
