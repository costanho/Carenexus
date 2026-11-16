package com.carenexus.api.common.event;

/**
 * Published by the health module after a reading was stored. {@code severity} is NORMAL, ABNORMAL or CRITICAL;
 * {@code source} says how it arrived (for example MANUAL or a device connector). Carries no reading values.
 */
public record HealthMetricRecordedEvent(
        Integer metricId, String patientSystemId, String recordedBySystemId, String type, boolean abnormal,
        String severity, String source) {

    public static final String NORMAL = "NORMAL";
    public static final String ABNORMAL = "ABNORMAL";
    public static final String CRITICAL = "CRITICAL";
}
