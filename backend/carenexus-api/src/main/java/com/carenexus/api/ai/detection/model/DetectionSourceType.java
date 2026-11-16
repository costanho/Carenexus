package com.carenexus.api.ai.detection.model;

/**
 * What triggered an automatic screening (CHECK {@code ck_disease_detection_source_type}, V66). The names are also
 * valid {@code ai_alerts.source_type} values, so the DISEASE_RISK alert carries the same source. A screening a
 * doctor runs by hand has no source.
 */
public enum DetectionSourceType {

    /** A symptom log was saved; the source id is {@code symptom_logs.symptom_id}. */
    SYMPTOM_LOG,
    /** An abnormal reading was saved; the source id is {@code health_metrics.metric_id}. */
    HEALTH_METRIC
}
