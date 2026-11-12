package com.carenexus.api.ai.detection.config;

import com.carenexus.api.ai.provider.RiskDetectionProvider;
import com.carenexus.api.ai.provider.RuleBasedRiskDetectionProvider;
import com.carenexus.api.common.config.LocalProviderGuard;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Startup check of the risk detection seam (CONTRACTS.v2 5.7). The rule-based provider is a local implementation:
 * accepted on developer profiles, a WARN in staging, and in production only with
 * {@code carenexus.allow-local-providers=true}. The disabled provider pretends nothing, so it is accepted
 * everywhere.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiDetectionStartupChecks {

    static final String SEAM = "risk-detection";

    private final LocalProviderGuard localProviderGuard;
    private final RiskDetectionProvider riskDetectionProvider;
    private final AiDetectionProperties properties;

    @PostConstruct
    void check() {
        boolean local = riskDetectionProvider instanceof RuleBasedRiskDetectionProvider;
        localProviderGuard.check(SEAM, properties.providerId(), local);
        log.info("Risk detection: provider {} (model {}, {}), assessment timeout {} ms", properties.providerId(),
                riskDetectionProvider.modelVersion(), riskDetectionProvider.isConfigured() ? "enabled" : "switched off",
                properties.effectiveTimeoutMs());
    }
}
