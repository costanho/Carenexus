package com.carenexus.api.ai.provider;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a {@link RiskDetectionProvider} found.
 *
 * @param riskLevel       overall level: the highest level among {@code conditions}, LOW when there is none
 * @param confidenceScore confidence of the primary (first) condition, 0 to 1; null when there is none
 * @param conditions      findings, highest level first, then highest confidence, then code ascending; the first is
 *                        the primary condition
 * @param recommendations clinician-directed suggestions, in the order of {@code conditions}
 */
public record DetectionResult(RiskLevel riskLevel, BigDecimal confidenceScore, List<DetectedCondition> conditions,
                              List<String> recommendations) {

    public DetectionResult {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
    }

    /** A result that matched nothing. */
    public static DetectionResult noFinding() {
        return new DetectionResult(RiskLevel.LOW, null, List.of(), List.of());
    }

    /** The primary condition, or null when nothing was found. */
    public DetectedCondition primary() {
        return conditions.isEmpty() ? null : conditions.get(0);
    }

    /**
     * One finding.
     *
     * @param code       stable identifier, for example {@code HYPERTENSIVE_BP}
     * @param name       display text worded as a finding, not a diagnosis (at most 120 characters)
     * @param riskLevel  level of this finding
     * @param confidence 0 to 1, at most 3 decimals
     * @param evidence   1 to 5 short sentences naming the readings or symptoms that matched, with their clinic date
     */
    public record DetectedCondition(String code, String name, RiskLevel riskLevel, BigDecimal confidence,
                                    List<String> evidence) {

        public DetectedCondition {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }
}
