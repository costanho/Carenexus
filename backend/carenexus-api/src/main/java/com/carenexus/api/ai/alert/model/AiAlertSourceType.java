package com.carenexus.api.ai.alert.model;

import java.util.Locale;
import java.util.Optional;

/**
 * The record that triggered an alert (database CHECK {@code ck_ai_alerts_source_type}, V48) and what
 * {@code ai_alerts.source_id} then holds:
 * <ul>
 *   <li>LAB_RESULT: {@code lab_results.result_id}</li>
 *   <li>IMAGING_RESULT: {@code imaging_results.imaging_id}</li>
 *   <li>HEALTH_METRIC: {@code health_metrics.metric_id}</li>
 *   <li>SYMPTOM_LOG: {@code symptom_logs.symptom_id}</li>
 *   <li>PRESCRIPTION: {@code prescriptions.prescription_id}</li>
 *   <li>MEDICATION_SCHEDULE: {@code medication_schedules.schedule_id}</li>
 *   <li>CONSULTATION: {@code consultations.consultation_id}</li>
 * </ul>
 */
public enum AiAlertSourceType {

    LAB_RESULT,
    IMAGING_RESULT,
    HEALTH_METRIC,
    SYMPTOM_LOG,
    PRESCRIPTION,
    MEDICATION_SCHEDULE,
    CONSULTATION;

    /** The source type named by {@code value} (trimmed, any case), or empty for null, blank or an unknown name. */
    public static Optional<AiAlertSourceType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (AiAlertSourceType type : values()) {
            if (type.name().equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
