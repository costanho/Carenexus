package com.carenexus.api.ai.generator;

import com.carenexus.api.common.event.ClinicalResultRecordedEvent;
import com.carenexus.api.common.event.HealthMetricRecordedEvent;
import com.carenexus.api.common.event.MedicationDoseMissedEvent;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Timing and failure rules of the event-driven generators (api/be-ai-generators.md 5.7): alerts are written only
 * after the originating transaction commits, in their own transaction; a rolled-back origin raises nothing; a failing
 * generator never reaches the publisher; a critical result raises one CRITICAL_RESULT alert for ever.
 */
class AiAlertGeneratorListenerTest extends CommittedIntegrationTest {

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private AiGeneratorFixtures fixtures;

    @BeforeEach
    void setUpFixtures() {
        fixtures = new AiGeneratorFixtures(jdbc);
    }

    private ClinicalResultRecordedEvent lab(int resultId, String patientId, boolean critical, boolean newlyCritical) {
        return new ClinicalResultRecordedEvent(ClinicalResultRecordedEvent.LAB, resultId, patientId,
                doctor.getSystemId(), doctor.getSystemId(), critical ? "CRITICAL" : "COMPLETED", critical,
                newlyCritical, true);
    }

    @Test
    void theAlertIsWrittenOnlyAfterTheOriginCommits() {
        TransactionTemplate origin = new TransactionTemplate(transactionManager);
        Integer visibleInsideOrigin = origin.execute(status -> {
            events.publishEvent(lab(87, patient.getSystemId(), true, true));
            return fixtures.alertCount();
        });

        assertEquals(0, visibleInsideOrigin, "nothing is written before the commit");
        List<Map<String, Object>> alerts = fixtures.alerts(patient.getSystemId());
        assertEquals(1, alerts.size());
        Map<String, Object> alert = alerts.get(0);
        assertEquals("CRITICAL_RESULT", alert.get("alert_type"));
        assertEquals("CRITICAL", alert.get("priority"));
        assertEquals(Boolean.TRUE, alert.get("action_required"));
        assertEquals("Critical lab result", alert.get("title"));
        assertEquals("A lab result for this patient was flagged as critical and needs review.", alert.get("message"));
        assertEquals("Review the result and contact the patient.", alert.get("suggested_action"));
        assertEquals("LAB_RESULT", alert.get("source_type"));
        assertEquals(87, alert.get("source_id"));
        assertEquals("CRITICAL_RESULT:LAB:87", alert.get("dedupe_key"));
        assertNull(alert.get("doctor_system_id"));
        assertEquals(1, fixtures.aiAlertNotifications(doctor.getSystemId()));
    }

    @Test
    void aRolledBackOriginRaisesNothing() {
        TransactionTemplate origin = new TransactionTemplate(transactionManager);
        origin.executeWithoutResult(status -> {
            events.publishEvent(lab(87, patient.getSystemId(), true, true));
            events.publishEvent(new MedicationDoseMissedEvent(1, 2, patient.getSystemId(), LocalDate.now(),
                    LocalTime.NOON, 5));
            status.setRollbackOnly();
        });

        assertEquals(0, fixtures.alertCount());
        assertEquals(0, fixtures.aiAlertNotifications());
    }

    @Test
    void aCriticalImagingResultNamesItsKind() {
        events.publishEvent(new ClinicalResultRecordedEvent(ClinicalResultRecordedEvent.IMAGING, 5,
                patient.getSystemId(), doctor.getSystemId(), doctor.getSystemId(), "CRITICAL", true, true, false));

        Map<String, Object> alert = fixtures.alerts(patient.getSystemId()).get(0);
        assertEquals("Critical imaging result", alert.get("title"));
        assertEquals("An imaging result for this patient was flagged as critical and needs review.",
                alert.get("message"));
        assertEquals("IMAGING_RESULT", alert.get("source_type"));
        assertEquals("CRITICAL_RESULT:IMAGING:5", alert.get("dedupe_key"));
    }

    @Test
    void onlyTheTransitionToCriticalRaisesAndOnlyOncePerResult() {
        events.publishEvent(lab(87, patient.getSystemId(), true, false));      // already critical: an edit
        events.publishEvent(lab(87, patient.getSystemId(), false, false));     // not critical
        assertEquals(0, fixtures.alertCount());

        events.publishEvent(lab(87, patient.getSystemId(), true, true));       // becomes critical
        events.publishEvent(lab(87, patient.getSystemId(), false, false));     // stops being critical
        events.publishEvent(lab(87, patient.getSystemId(), true, true));       // critical again
        events.publishEvent(lab(88, patient.getSystemId(), true, true));       // another result

        List<Map<String, Object>> alerts = fixtures.alerts(patient.getSystemId());
        assertEquals(List.of("CRITICAL_RESULT:LAB:87", "CRITICAL_RESULT:LAB:88"),
                alerts.stream().map(a -> a.get("dedupe_key")).toList());
        assertEquals(2, fixtures.aiAlertNotifications(doctor.getSystemId()));
    }

    @Test
    void aFailingGeneratorIsSwallowedAndLeavesNothingBehind() {
        // An unknown patient is refused by AiAlertService.raise; the publisher never sees the failure.
        assertDoesNotThrow(() -> events.publishEvent(lab(87, "PAT-09999", true, true)));
        assertDoesNotThrow(() -> events.publishEvent(new HealthMetricRecordedEvent(1, "PAT-09999", "PAT-09999",
                "SPO2", true, "CRITICAL", "MANUAL")));
        assertDoesNotThrow(() -> events.publishEvent(new MedicationDoseMissedEvent(1, 2, "PAT-09999",
                LocalDate.now(), LocalTime.NOON, 4)));

        assertEquals(0, fixtures.alertCount());

        // The same thread keeps working for the next event.
        events.publishEvent(lab(87, patient.getSystemId(), true, true));
        assertEquals(1, fixtures.alertCount());
    }

    @Test
    void aFailureInsideAnOriginTransactionDoesNotRollItBack() {
        TransactionTemplate origin = new TransactionTemplate(transactionManager);
        assertDoesNotThrow(() -> origin.executeWithoutResult(status -> {
            jdbc.update("UPDATE users SET first_name = 'Thomas' WHERE system_id = ?", patient.getSystemId());
            events.publishEvent(lab(87, "PAT-09999", true, true));
        }));

        assertEquals("Thomas", jdbc.queryForObject("SELECT first_name FROM users WHERE system_id = ?", String.class,
                patient.getSystemId()));
        assertEquals(0, fixtures.alertCount());
    }
}
