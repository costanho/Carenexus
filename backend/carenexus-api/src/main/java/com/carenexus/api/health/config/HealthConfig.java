package com.carenexus.api.health.config;

import com.carenexus.api.health.provider.DisabledWearableConnector;
import com.carenexus.api.health.provider.WearableConnector;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Health-tracking wiring: the wearable connector chosen by {@code carenexus.health.wearables.provider}.
 *
 * A device platform is added here: write an adapter implementing {@link WearableConnector} and return it for its
 * provider id. An id nobody implements fails startup, so a typo never silently disables synchronisation.
 */
@Configuration
@EnableConfigurationProperties(HealthProperties.class)
public class HealthConfig {

    @Bean
    public WearableConnector wearableConnector(HealthProperties properties) {
        String provider = properties.getWearables().providerId();
        if (HealthProperties.Wearables.DISABLED.equals(provider)) {
            return new DisabledWearableConnector();
        }
        throw new IllegalStateException("carenexus.health.wearables.provider is '" + provider
                + "', which this build does not support. Use 'disabled', or add the connector adapter.");
    }
}
