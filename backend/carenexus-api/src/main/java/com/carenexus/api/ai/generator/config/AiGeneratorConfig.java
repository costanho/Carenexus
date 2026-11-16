package com.carenexus.api.ai.generator.config;

import com.carenexus.api.ai.interaction.DisabledInteractionChecker;
import com.carenexus.api.ai.interaction.MedicationInteractionChecker;
import com.carenexus.api.ai.interaction.StaticRuleInteractionChecker;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Alert-generator wiring: the medication-interaction checker chosen by {@code carenexus.ai.interaction-provider}.
 *
 * A real interaction service is added here: write an adapter implementing {@link MedicationInteractionChecker} and
 * return it for its provider id. An id nobody implements fails startup, so a typo never silently turns screening
 * off.
 */
@Configuration
@EnableConfigurationProperties(AiGeneratorProperties.class)
public class AiGeneratorConfig {

    @Bean
    public MedicationInteractionChecker medicationInteractionChecker(AiGeneratorProperties properties) {
        return checkerFor(properties.interactionProviderId());
    }

    static MedicationInteractionChecker checkerFor(String providerId) {
        return switch (providerId) {
            case AiGeneratorProperties.PROVIDER_RULES -> new StaticRuleInteractionChecker();
            case AiGeneratorProperties.PROVIDER_DISABLED -> new DisabledInteractionChecker();
            default -> throw new IllegalStateException("carenexus.ai.interaction-provider is '" + providerId
                    + "', which this build does not support. Use 'rules' or 'disabled', or add the checker adapter.");
        };
    }
}
