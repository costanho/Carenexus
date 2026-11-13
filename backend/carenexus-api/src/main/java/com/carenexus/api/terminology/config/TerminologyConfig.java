package com.carenexus.api.terminology.config;

import com.carenexus.api.terminology.provider.BundledIcd10Provider;
import com.carenexus.api.terminology.provider.TerminologyProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Terminology wiring: the provider chosen by {@code carenexus.terminology.provider}.
 *
 * A licensed ICD service or a full code set is added here: write an adapter implementing
 * {@link TerminologyProvider} and return it for its provider id. An id nobody implements fails startup, so a typo
 * never silently falls back to the bundled catalogue.
 */
@Configuration
@EnableConfigurationProperties(TerminologyProperties.class)
public class TerminologyConfig {

    @Bean
    public TerminologyProvider terminologyProvider(TerminologyProperties properties) {
        String provider = properties.providerId();
        if (TerminologyProperties.BUNDLED.equals(provider)) {
            return BundledIcd10Provider.fromClasspath(properties.bundledResourceOrDefault());
        }
        throw new IllegalStateException("carenexus.terminology.provider is '" + provider
                + "', which this build does not support. Use 'bundled', or add the provider adapter.");
    }
}
