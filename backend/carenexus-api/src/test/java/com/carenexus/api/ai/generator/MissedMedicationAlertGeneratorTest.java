package com.carenexus.api.ai.generator;

import com.carenexus.api.ai.generator.config.AiGeneratorProperties;
import com.carenexus.api.common.event.MedicationDoseMissedEvent;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Row 4 of api/be-ai-generators.md 5.1: a missed dose that ends a run of at least
 * {@code carenexus.ai.missed-dose-threshold} (3) misses raises one MISSED_MEDICATION alert per prescription and
 * day, addressed to every related doctor, with a text that names no medication.
 */
class MissedMedicationAlertGeneratorTest extends CommittedIntegrationTest {

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private AiGeneratorProperties properties;

    private AiGeneratorFixtures fixtures;
    private LocalDate today;
    private int prescriptionId;

    @BeforeEach
    void setUpFixtures() {
        fixtures = new AiGeneratorFixtures(jdbc);
        today = LocalDate.now();
        prescriptionId = fixtures.prescription(patient.getSystemId(), doctor.getSystemId(), "Metformin", "metformin",
                "ACTIVE", today.minusDays(10), null);
    }

    private void missed(LocalDate day, int consecutiveMisses) {
        events.publishEvent(new MedicationDoseMissedEvent(1000 + consecutiveMisses, prescriptionId,
                patient.getSystemId(), day, LocalTime.of(20, 0), consecutiveMisses));
    }

    private List<Map<String, Object>> alerts() {
        return fixtures.alerts(patient.getSystemId(), "MISSED_MEDICATION");
    }

    @Test
    void belowTheThresholdNothingIsRaised() {
        missed(today, 1);
        missed(today, 2);
        assertEquals(0, fixtures.alertCount());
        assertEquals(0, fixtures.aiAlertNotifications());
    }

    @Test
    void reachingTheThresholdRaisesOneAlertPerPrescriptionAndDay() {
        missed(today, 3);

        List<Map<String, Object>> alerts = alerts();
        assertEquals(1, alerts.size());
        Map<String, Object> alert = alerts.get(0);
        assertEquals("MEDIUM", alert.get("priority"));
        assertEquals(Boolean.FALSE, alert.get("action_required"));
        assertEquals("Missed medication doses", alert.get("title"));
        assertEquals("3 scheduled doses in a row were not marked as taken.", alert.get("message"));
        assertFalse(((String) alert.get("message")).contains("Metformin"), "the medication is not named");
        assertEquals("Check adherence with the patient and review the prescription.", alert.get("suggested_action"));
        assertEquals("PRESCRIPTION", alert.get("source_type"));
        assertEquals(prescriptionId, alert.get("source_id"));
        assertEquals("MISSED_MEDICATION:" + prescriptionId + ":" + today, alert.get("dedupe_key"));
        assertNull(alert.get("doctor_system_id"));
        assertEquals(1, fixtures.aiAlertNotifications(doctor.getSystemId()));
        assertEquals(0, fixtures.aiAlertNotifications(patient.getSystemId()));

        // A longer run the same day (or the same event again) adds nothing; the next day raises again.
        missed(today, 4);
        missed(today, 3);
        assertEquals(1, alerts().size());
        missed(today.plusDays(1), 5);
        assertEquals(2, alerts().size());
        assertEquals("5 scheduled doses in a row were not marked as taken.", alerts().get(1).get("message"));
        assertEquals(2, fixtures.aiAlertNotifications(doctor.getSystemId()));
    }

    @Test
    void theThresholdComesFromConfiguration() {
        int previous = properties.getMissedDoseThreshold();
        try {
            properties.setMissedDoseThreshold(2);
            missed(today, 2);
        } finally {
            properties.setMissedDoseThreshold(previous);
        }
        assertEquals(1, alerts().size());
        assertEquals("2 scheduled doses in a row were not marked as taken.", alerts().get(0).get("message"));
    }
}
