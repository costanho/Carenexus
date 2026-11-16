package com.carenexus.api.health.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One reading handed to {@link HealthMetricIngestService#ingest}, from manual entry or from a device connector.
 *
 * @param type       a type of {@link com.carenexus.api.health.model.MetricType} (trimmed and upper-cased)
 * @param value      the measurement, at most 7 integer digits and 3 decimals
 * @param unit       optional; when present it must equal the unit of the type
 * @param recordedAt clinic wall-clock time of the measurement; null = now (one value for the whole batch)
 * @param notes      optional free text (max 500); blank is stored as null
 */
public record MetricReading(String type, BigDecimal value, String unit, LocalDateTime recordedAt, String notes) {
}
