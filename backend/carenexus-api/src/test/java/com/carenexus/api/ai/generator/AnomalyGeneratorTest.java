package com.carenexus.api.ai.generator;

import com.carenexus.api.ai.generator.config.AiGeneratorProperties;
import com.carenexus.api.common.event.HealthMetricRecordedEvent;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Row 3 of api/be-ai-generators.md 5.1, rule 5.3: a normal reading that deviates from the median of the patient's
 * own readings of the last 30 days (at least 5) by more than the configured percentage raises ANOMALY_DETECTED once
 * per patient, type and day; too little history, a small deviation, an excluded type or an abnormal reading raise
 * nothing here.
 */
class AnomalyGeneratorTest extends CommittedIntegrationTest {

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private AiGeneratorProperties properties;

    private AiGeneratorFixtures fixtures;
    private LocalDateTime now;

    @BeforeEach
    void setUpFixtures() {
        fixtures = new AiGeneratorFixtures(jdbc);
        // Mid-day, so readings "an hour ago" and "now" fall on the same clinic day.
        now = LocalDateTime.now().withHour(12).withMinute(0).withSecond(0).withNano(0);
    }

    /** Five earlier heart-rate readings with median 64. */
    private void usualHeartRates() {
        String[] values = {"60", "62", "64", "66", "68"};
        for (int i = 0; i < values.length; i++) {
            fixtures.heartRate(patient.getSystemId(), values[i], now.minusDays(i + 1));
        }
    }

    private void record(int metricId, String type) {
        events.publishEvent(new HealthMetricRecordedEvent(metricId, patient.getSystemId(), patient.getSystemId(), type,
                false, HealthMetricRecordedEvent.NORMAL, "MANUAL"));
        awaitBackgroundWork();
    }

    private List<Map<String, Object>> anomalies() {
        return fixtures.alerts(patient.getSystemId(), "ANOMALY_DETECTED");
    }

    @Test
    void aMarkedDeviationRaisesOneMediumAlertPerDay() {
        usualHeartRates();
        int reading = fixtures.heartRate(patient.getSystemId(), "85", now);

        record(reading, "HEART_RATE");

        List<Map<String, Object>> alerts = anomalies();
        assertEquals(1, alerts.size());
        Map<String, Object> alert = alerts.get(0);
        assertEquals("MEDIUM", alert.get("priority"));
        assertEquals(Boolean.FALSE, alert.get("action_required"));
        assertEquals("Unusual reading", alert.get("title"));
        assertEquals("A heart rate reading differs markedly from this patient's usual readings over the last 30 days.",
                alert.get("message"));
        assertEquals("Review the patient's recent readings.", alert.get("suggested_action"));
        assertEquals("HEALTH_METRIC", alert.get("source_type"));
        assertEquals(reading, alert.get("source_id"));
        assertEquals("ANOMALY_DETECTED:" + patient.getSystemId() + ":HEART_RATE:" + now.toLocalDate(),
                alert.get("dedupe_key"));
        assertNull(alert.get("doctor_system_id"));
        assertEquals(1, fixtures.aiAlertNotifications(doctor.getSystemId()));

        // A second unusual reading the same day adds nothing; the redelivered event neither.
        int later = fixtures.heartRate(patient.getSystemId(), "40", now.plusMinutes(30));
        record(later, "HEART_RATE");
        record(reading, "HEART_RATE");
        assertEquals(1, anomalies().size());
        assertEquals(1, fixtures.aiAlertNotifications(doctor.getSystemId()));

        // The next day raises again.
        int tomorrow = fixtures.heartRate(patient.getSystemId(), "90", now.plusDays(1));
        record(tomorrow, "HEART_RATE");
        assertEquals(2, anomalies().size());
    }

    @Test
    void aDeviationOfExactlyTheThresholdRaisesNothing() {
        usualHeartRates();
        int reading = fixtures.heartRate(patient.getSystemId(), "80", now);

        record(reading, "HEART_RATE");

        assertEquals(0, fixtures.alertCount());
    }

    @Test
    void theThresholdComesFromConfiguration() {
        usualHeartRates();
        int reading = fixtures.heartRate(patient.getSystemId(), "72", now);
        BigDecimal previous = properties.getAnomalyDeviationPercent();
        try {
            properties.setAnomalyDeviationPercent(new BigDecimal("10"));
            record(reading, "HEART_RATE");
        } finally {
            properties.setAnomalyDeviationPercent(previous);
        }
        assertEquals(1, anomalies().size(), "12.5 % is above a 10 % threshold");
    }

    @Test
    void tooLittleRecentHistoryRaisesNothing() {
        // Four readings inside the window and three older than 30 days.
        for (int i = 1; i <= 4; i++) {
            fixtures.heartRate(patient.getSystemId(), "64", now.minusDays(i));
        }
        for (int i = 31; i <= 33; i++) {
            fixtures.heartRate(patient.getSystemId(), "64", now.minusDays(i));
        }
        // Another patient's readings never count.
        for (int i = 1; i <= 5; i++) {
            fixtures.heartRate(otherPatient.getSystemId(), "64", now.minusDays(i));
        }
        int reading = fixtures.heartRate(patient.getSystemId(), "120", now);

        record(reading, "HEART_RATE");

        assertEquals(0, fixtures.alertCount());
    }

    @Test
    void laterReadingsAndOtherTypesAreNotPartOfTheBaseline() {
        // Five readings of another type and five readings after this one: neither is history for it.
        for (int i = 1; i <= 5; i++) {
            fixtures.metric(patient.getSystemId(), "RESPIRATORY_RATE", "16", "breaths/min", "NORMAL", now.minusDays(i));
            fixtures.heartRate(patient.getSystemId(), "64", now.plusHours(i));
        }
        int reading = fixtures.heartRate(patient.getSystemId(), "95", now);

        record(reading, "HEART_RATE");

        assertEquals(0, fixtures.alertCount());
    }

    @Test
    void excludedTypesNeverRaise() {
        for (int i = 1; i <= 5; i++) {
            fixtures.metric(patient.getSystemId(), "STEPS", "4000", "steps", "NORMAL", now.minusDays(i));
            fixtures.metric(patient.getSystemId(), "HEIGHT", "150", "cm", "NORMAL", now.minusDays(i));
        }
        int steps = fixtures.metric(patient.getSystemId(), "STEPS", "15000", "steps", "NORMAL", now);
        int height = fixtures.metric(patient.getSystemId(), "HEIGHT", "190", "cm", "NORMAL", now);

        record(steps, "STEPS");
        record(height, "HEIGHT");

        assertEquals(0, fixtures.alertCount());
    }

    @Test
    void anAbnormalReadingGoesToTheVitalsAlertOnly() {
        usualHeartRates();
        int reading = fixtures.metric(patient.getSystemId(), "HEART_RATE", "120", "bpm", "ABNORMAL", now);

        events.publishEvent(new HealthMetricRecordedEvent(reading, patient.getSystemId(), patient.getSystemId(),
                "HEART_RATE", true, HealthMetricRecordedEvent.ABNORMAL, "MANUAL"));
        awaitBackgroundWork();

        List<Map<String, Object>> alerts = fixtures.alerts(patient.getSystemId());
        assertEquals(1, alerts.size());
        assertEquals("CRITICAL_VITALS", alerts.get(0).get("alert_type"));
    }

    @Test
    void aReadingThatNoLongerExistsRaisesNothing() {
        usualHeartRates();
        int reading = fixtures.heartRate(patient.getSystemId(), "95", now);
        jdbc.update("DELETE FROM health_metrics WHERE metric_id = ?", reading);

        record(reading, "HEART_RATE");

        assertEquals(0, fixtures.alertCount());
    }
}
