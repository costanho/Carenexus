package com.carenexus.api.ai.generator;

import com.carenexus.api.common.event.HealthMetricRecordedEvent;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Assertions count only CRITICAL_VITALS alerts and the notifications about them: be-ai-detection screens the same
 * abnormal readings and may add DISEASE_RISK alerts of its own (API-NOTES).
 *
 * Row 2 of api/be-ai-generators.md 5.1: an abnormal reading raises one CRITICAL_VITALS alert per reading for every
 * related doctor, with the priority, action flag and text taken from the severity. The event is published from the
 * test, as the listener receives it after the health module's commit.
 */
class VitalSignAlertGeneratorTest extends CommittedIntegrationTest {

    @Autowired
    private ApplicationEventPublisher events;

    private AiGeneratorFixtures fixtures;

    @BeforeEach
    void setUpFixtures() {
        fixtures = new AiGeneratorFixtures(jdbc);
    }

    private HealthMetricRecordedEvent recorded(int metricId, String type, String severity) {
        return new HealthMetricRecordedEvent(metricId, patient.getSystemId(), patient.getSystemId(), type,
                !HealthMetricRecordedEvent.NORMAL.equals(severity), severity, "MANUAL");
    }

    @Test
    void anAbnormalReadingGivesAHighPriorityAlertWithoutRequiredAction() {
        LocalDateTime at = LocalDateTime.now().minusMinutes(5).withNano(0);
        int metricId = fixtures.metric(patient.getSystemId(), "BP_SYSTOLIC", "152", "mmHg", "ABNORMAL", at);

        events.publishEvent(recorded(metricId, "BP_SYSTOLIC", "ABNORMAL"));
        awaitBackgroundWork();

        List<Map<String, Object>> alerts = fixtures.alerts(patient.getSystemId(), "CRITICAL_VITALS");
        assertEquals(1, alerts.size(), alerts.toString());
        Map<String, Object> alert = alerts.get(0);
        assertEquals("CRITICAL_VITALS", alert.get("alert_type"));
        assertEquals("HIGH", alert.get("priority"));
        assertEquals(Boolean.FALSE, alert.get("action_required"));
        assertEquals("Abnormal vital sign reading", alert.get("title"));
        assertEquals("A systolic pressure reading for this patient is outside the reference range.", alert.get("message"));
        assertEquals("Review the patient's recent readings.", alert.get("suggested_action"));
        assertEquals("HEALTH_METRIC", alert.get("source_type"));
        assertEquals(metricId, alert.get("source_id"));
        assertEquals("CRITICAL_VITALS:" + metricId, alert.get("dedupe_key"));
        assertNull(alert.get("doctor_system_id"), "addressed to every related doctor");
        assertNull(alert.get("detection_id"));

        List<Map<String, Object>> notifications = fixtures.notificationsAbout((Integer) alert.get("alert_id"));
        assertEquals(1, notifications.size());
        assertEquals(doctor.getSystemId(), notifications.get(0).get("system_id"));
        assertEquals("HIGH", notifications.get(0).get("priority"));
        assertEquals("New clinical alert", notifications.get(0).get("title"));
        assertEquals(0, fixtures.aiAlertNotifications(otherDoctor.getSystemId()));
        assertEquals(0, fixtures.aiAlertNotifications(patient.getSystemId()));
    }

    @Test
    void aCriticalReadingGivesACriticalAlertThatNeedsAction() {
        LocalDateTime at = LocalDateTime.now().minusMinutes(5).withNano(0);
        int metricId = fixtures.metric(patient.getSystemId(), "SPO2", "85", "%", "CRITICAL", at);

        events.publishEvent(recorded(metricId, "SPO2", "CRITICAL"));
        awaitBackgroundWork();

        List<Map<String, Object>> alerts = fixtures.alerts(patient.getSystemId(), "CRITICAL_VITALS");
        assertEquals(1, alerts.size(), alerts.toString());
        Map<String, Object> alert = alerts.get(0);
        assertEquals("CRITICAL", alert.get("priority"));
        assertEquals(Boolean.TRUE, alert.get("action_required"));
        assertEquals("Critical vital sign reading", alert.get("title"));
        assertEquals("A oxygen saturation reading for this patient is in the critical range.", alert.get("message"));
        assertEquals("Review the reading and contact the patient.", alert.get("suggested_action"));
        List<Map<String, Object>> notifications = fixtures.notificationsAbout((Integer) alert.get("alert_id"));
        assertEquals("CRITICAL", notifications.get(0).get("priority"));
        assertEquals("Critical clinical alert", notifications.get(0).get("title"));
    }

    @Test
    void eachReadingOfABloodPressurePairGetsItsOwnAlertAndARepeatAddsNone() {
        LocalDateTime at = LocalDateTime.now().minusMinutes(5).withNano(0);
        int systolic = fixtures.metric(patient.getSystemId(), "BP_SYSTOLIC", "185", "mmHg", "CRITICAL", at);
        int diastolic = fixtures.metric(patient.getSystemId(), "BP_DIASTOLIC", "96", "mmHg", "ABNORMAL", at);

        events.publishEvent(recorded(systolic, "BP_SYSTOLIC", "CRITICAL"));
        awaitBackgroundWork();
        events.publishEvent(recorded(diastolic, "BP_DIASTOLIC", "ABNORMAL"));
        awaitBackgroundWork();
        events.publishEvent(recorded(systolic, "BP_SYSTOLIC", "CRITICAL"));
        awaitBackgroundWork();

        List<Map<String, Object>> alerts = fixtures.alerts(patient.getSystemId(), "CRITICAL_VITALS");
        assertEquals(2, alerts.size());
        assertEquals("CRITICAL", alerts.get(0).get("priority"));
        assertEquals("HIGH", alerts.get(1).get("priority"));
        for (Map<String, Object> alert : alerts) {
            List<Map<String, Object>> notifications = fixtures.notificationsAbout((Integer) alert.get("alert_id"));
            assertEquals(1, notifications.size(), notifications.toString());
            assertEquals(doctor.getSystemId(), notifications.get(0).get("system_id"));
        }
    }

    @Test
    void aNormalReadingRaisesNoVitalsAlert() {
        LocalDateTime at = LocalDateTime.now().minusMinutes(5).withNano(0);
        int metricId = fixtures.heartRate(patient.getSystemId(), "72", at);

        events.publishEvent(recorded(metricId, "HEART_RATE", "NORMAL"));
        awaitBackgroundWork();

        assertEquals(0, fixtures.alertCount());
        assertEquals(0, fixtures.aiAlertNotifications());
    }
}
