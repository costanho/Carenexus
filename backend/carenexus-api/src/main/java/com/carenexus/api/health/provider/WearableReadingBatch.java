package com.carenexus.api.health.provider;

import com.carenexus.api.health.service.MetricReading;

import java.util.List;

/**
 * Readings of one device, ready for {@code HealthMetricIngestService.ingest(patientSystemId, source, deviceId,
 * readings)}.
 *
 * @param source   WEARABLE, DEVICE or INTEGRATION
 * @param deviceId the platform's identifier of the device (max 100 characters); never a secret
 * @param readings the readings, oldest first
 */
public record WearableReadingBatch(String source, String deviceId, List<MetricReading> readings) {

    public WearableReadingBatch {
        readings = readings == null ? List.of() : List.copyOf(readings);
    }
}
