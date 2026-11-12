package com.carenexus.api.health.config;

import com.carenexus.api.common.config.LocalProviderGuard;
import com.carenexus.api.health.model.MetricType;
import com.carenexus.api.health.provider.WearableConnector;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Startup checks of the health module:
 * <ul>
 *   <li>the provider-seam rule ({@link LocalProviderGuard}) for the wearable connector. The disabled connector is
 *       not a stand-in for a real platform (it synchronises nothing and pretends nothing), so it is accepted in
 *       every environment;</li>
 *   <li>the configured reference ranges are consistent: on each side the critical bound lies beyond the normal
 *       bound (criticalLow &le; low &lt; high &le; criticalHigh, where set). A wrong override fails startup instead
 *       of mislabelling readings.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HealthStartupChecks {

    private final LocalProviderGuard localProviderGuard;
    private final WearableConnector wearableConnector;
    private final HealthProperties properties;

    @PostConstruct
    void check() {
        localProviderGuard.check("wearables", wearableConnector.providerName(), false);
        List<String> problems = rangeProblems(properties.getRanges());
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid carenexus.health.ranges configuration: " + String.join("; ", problems));
        }
        log.info("Health tracking: wearable connector {} ({})", wearableConnector.providerName(),
                wearableConnector.isEnabled() ? "enabled" : "disabled");
    }

    static List<String> rangeProblems(HealthProperties.Ranges ranges) {
        List<String> problems = new ArrayList<>();
        for (MetricType type : MetricType.values()) {
            HealthProperties.Range range = ranges.forType(type);
            String name = type.name();
            if (outOfOrder(range.getCriticalLow(), range.getLow(), false)) {
                problems.add(name + ": critical-low must not be above low");
            }
            if (outOfOrder(range.getLow(), range.getHigh(), true)) {
                problems.add(name + ": low must be below high");
            }
            if (outOfOrder(range.getHigh(), range.getCriticalHigh(), false)) {
                problems.add(name + ": high must not be above critical-high");
            }
            if (outOfOrder(range.getCriticalLow(), range.getCriticalHigh(), true)) {
                problems.add(name + ": critical-low must be below critical-high");
            }
        }
        return problems;
    }

    private static boolean outOfOrder(BigDecimal lower, BigDecimal upper, boolean strict) {
        if (lower == null || upper == null) {
            return false;
        }
        int comparison = lower.compareTo(upper);
        return strict ? comparison >= 0 : comparison > 0;
    }
}
