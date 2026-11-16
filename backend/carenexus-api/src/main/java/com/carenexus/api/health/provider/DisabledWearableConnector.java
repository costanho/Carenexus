package com.carenexus.api.health.provider;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The connector used when no wearable platform is configured ({@code carenexus.health.wearables.provider=disabled},
 * the default). It reports itself as disabled and never returns readings, so no device data enters the system;
 * readings are entered manually through {@code POST /api/health-metrics}.
 */
public class DisabledWearableConnector implements WearableConnector {

    public static final String PROVIDER = "disabled";

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public List<WearableReadingBatch> fetchReadings(String patientSystemId, LocalDateTime since) {
        return List.of();
    }
}
