package com.carenexus.api.ai.generator.config;

import com.carenexus.api.ai.interaction.MedicationInteractionChecker;
import com.carenexus.api.common.config.LocalProviderGuard;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Startup checks of the alert generators:
 * <ul>
 *   <li>the provider-seam rule ({@link LocalProviderGuard}) for the medication-interaction checker. Both shipped
 *       checkers (the static rule table and the disabled one) stand in for a real interaction service, so they are
 *       reported as local implementations: production refuses them unless
 *       {@code carenexus.allow-local-providers=true};</li>
 *   <li>the thresholds are usable: a wrong override fails startup instead of flooding or silencing alerts.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiGeneratorStartupChecks {

    static final String SEAM = "medication-interactions";

    private final LocalProviderGuard localProviderGuard;
    private final MedicationInteractionChecker interactionChecker;
    private final AiGeneratorProperties properties;

    @PostConstruct
    void check() {
        List<String> problems = problems(properties);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid carenexus.ai configuration: " + String.join("; ", problems));
        }
        localProviderGuard.check(SEAM, interactionChecker.providerName(), true);
        log.info("AI alert generators: interaction checker {}, missed-dose threshold {}, anomaly deviation {}%, "
                        + "follow-up lookback {} days", interactionChecker.providerName(),
                properties.getMissedDoseThreshold(), properties.getAnomalyDeviationPercent(),
                properties.getFollowUpLookbackDays());
    }

    static List<String> problems(AiGeneratorProperties properties) {
        List<String> problems = new ArrayList<>();
        if (properties.getMissedDoseThreshold() < 1) {
            problems.add("missed-dose-threshold must be at least 1");
        }
        BigDecimal deviation = properties.getAnomalyDeviationPercent();
        if (deviation == null || deviation.signum() <= 0) {
            problems.add("anomaly-deviation-percent must be greater than 0");
        }
        if (properties.getFollowUpLookbackDays() < 1) {
            problems.add("follow-up-lookback-days must be at least 1");
        }
        return problems;
    }
}
