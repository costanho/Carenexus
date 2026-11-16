package com.carenexus.api.ai.detection.service;

import com.carenexus.api.ai.detection.model.DetectionSourceType;

/**
 * Why a screening runs: an automatic trigger (a symptom log or an abnormal reading was saved) or a doctor's manual
 * run. Exactly one of the two shapes is used: {@code sourceType} and {@code sourceId} together, or
 * {@code requestedBySystemId} alone.
 *
 * @param sourceType          what triggered an automatic screening; null for a manual run
 * @param sourceId            {@code symptom_logs.symptom_id} or {@code health_metrics.metric_id}; null for a manual run
 * @param requestedBySystemId the doctor who ran the screening by hand; null for an automatic one
 */
public record ScreeningTrigger(DetectionSourceType sourceType, Integer sourceId, String requestedBySystemId) {

    public ScreeningTrigger {
        if ((sourceType == null) != (sourceId == null)) {
            throw new IllegalArgumentException("sourceType and sourceId must be set together");
        }
        if (sourceType != null && requestedBySystemId != null) {
            throw new IllegalArgumentException("An automatic screening has no requesting doctor");
        }
        if (sourceType == null && requestedBySystemId == null) {
            throw new IllegalArgumentException("A screening needs a source or a requesting doctor");
        }
    }

    public static ScreeningTrigger symptomLog(Integer symptomId) {
        return new ScreeningTrigger(DetectionSourceType.SYMPTOM_LOG, symptomId, null);
    }

    public static ScreeningTrigger healthMetric(Integer metricId) {
        return new ScreeningTrigger(DetectionSourceType.HEALTH_METRIC, metricId, null);
    }

    public static ScreeningTrigger manual(String doctorSystemId) {
        return new ScreeningTrigger(null, null, doctorSystemId);
    }

    public boolean isManual() {
        return sourceType == null;
    }
}
