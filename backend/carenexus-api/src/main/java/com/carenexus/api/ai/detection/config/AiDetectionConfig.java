package com.carenexus.api.ai.detection.config;

import com.carenexus.api.ai.provider.DisabledRiskDetectionProvider;
import com.carenexus.api.ai.provider.RiskDetectionProvider;
import com.carenexus.api.ai.provider.RuleBasedRiskDetectionProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Disease-risk screening wiring: the {@link RiskDetectionProvider} chosen by {@code carenexus.ai.provider}.
 *
 * A hosted model is added here: write an adapter implementing {@link RiskDetectionProvider} and return it for its
 * provider id. An id nobody implements fails startup, so a typo never silently switches screening off.
 */
@Configuration
@EnableConfigurationProperties(AiDetectionProperties.class)
public class AiDetectionConfig {

    @Bean
    public RiskDetectionProvider riskDetectionProvider(AiDetectionProperties properties) {
        String provider = properties.providerId();
        return switch (provider) {
            case AiDetectionProperties.RULES -> new RuleBasedRiskDetectionProvider();
            case AiDetectionProperties.DISABLED -> new DisabledRiskDetectionProvider();
            default -> throw new IllegalStateException("carenexus.ai.provider is '" + provider
                    + "', which this build does not support. Use 'rules' or 'disabled', or add the provider adapter.");
        };
    }
}
