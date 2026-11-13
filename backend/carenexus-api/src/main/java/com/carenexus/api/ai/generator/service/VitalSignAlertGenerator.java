package com.carenexus.api.ai.generator.service;

import com.carenexus.api.ai.alert.AiAlertCommand;
import com.carenexus.api.ai.alert.AiAlertService;
import com.carenexus.api.ai.alert.model.AiAlertPriority;
import com.carenexus.api.ai.alert.model.AiAlertSourceType;
import com.carenexus.api.ai.alert.model.AiAlertType;
import com.carenexus.api.common.event.HealthMetricRecordedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Row 2 of api/be-ai-generators.md 5.1: a reading outside the reference range raises one CRITICAL_VITALS alert for
 * every doctor related to the patient. Severity ABNORMAL gives priority HIGH, CRITICAL gives priority CRITICAL with
 * action required.
 * <p>
 * Dedupe key {@code CRITICAL_VITALS:<metricId>}: one alert per reading. A blood-pressure measurement is stored as
 * two readings (systolic and diastolic), so an abnormal pair gives two alerts, as the contract pins.
 */
@Component
@RequiredArgsConstructor
public class VitalSignAlertGenerator {

    private final AiAlertService aiAlertService;

    /** @return the new alert id; empty for a normal reading or when the alert exists */
    public Optional<Integer> generate(HealthMetricRecordedEvent event) {
        if (event == null || !event.abnormal() || event.metricId() == null || event.patientSystemId() == null) {
            return Optional.empty();
        }
        boolean critical = HealthMetricRecordedEvent.CRITICAL.equals(event.severity());
        return aiAlertService.raise(new AiAlertCommand(
                event.patientSystemId(),
                null,
                AiAlertType.CRITICAL_VITALS.name(),
                priorityFor(event.severity()).name(),
                critical ? AlertTexts.CRITICAL_VITALS_TITLE : AlertTexts.ABNORMAL_VITALS_TITLE,
                critical ? AlertTexts.criticalVitalsMessage(event.type()) : AlertTexts.abnormalVitalsMessage(event.type()),
                critical,
                critical ? AlertTexts.CRITICAL_VITALS_ACTION : AlertTexts.REVIEW_READINGS_ACTION,
                AiAlertSourceType.HEALTH_METRIC.name(),
                event.metricId(),
                null,
                dedupeKey(event.metricId())));
    }

    /** CRITICAL severity gives CRITICAL; any other abnormal severity (ABNORMAL) gives HIGH. */
    public static AiAlertPriority priorityFor(String severity) {
        return HealthMetricRecordedEvent.CRITICAL.equals(severity) ? AiAlertPriority.CRITICAL : AiAlertPriority.HIGH;
    }

    public static String dedupeKey(Integer metricId) {
        return AiAlertType.CRITICAL_VITALS.name() + ":" + metricId;
    }
}
