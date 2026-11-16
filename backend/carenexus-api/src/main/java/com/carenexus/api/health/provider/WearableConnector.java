package com.carenexus.api.health.provider;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Seam for wearable and home-device platforms (fitness trackers, connected blood-pressure cuffs, glucose meters).
 *
 * A connector only fetches and translates readings; it never stores them. Whatever it returns is stored through
 * {@link com.carenexus.api.health.service.HealthMetricIngestService#ingest}, which applies the same type, unit,
 * plausible-range and severity rules as manual entry and publishes the same events.
 *
 * Selected by {@code carenexus.health.wearables.provider}. This build ships {@link DisabledWearableConnector}
 * ({@code disabled}): no device data is synchronised and every reading is entered manually. A real adapter
 * implements this interface and is returned for its provider id by {@code HealthConfig}.
 */
public interface WearableConnector {

    /** Provider id, for example {@code disabled}. */
    String providerName();

    /** False when no device platform is connected; callers then skip synchronisation entirely. */
    boolean isEnabled();

    /**
     * Readings the patient's linked devices recorded after {@code since} (clinic wall-clock), oldest first.
     *
     * @throws WearableConnectorException when the platform cannot be reached or refuses the request
     */
    List<WearableReadingBatch> fetchReadings(String patientSystemId, LocalDateTime since);
}
