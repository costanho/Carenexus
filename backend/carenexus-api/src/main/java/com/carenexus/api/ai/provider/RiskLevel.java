package com.carenexus.api.ai.provider;

import java.util.Locale;
import java.util.Optional;

/**
 * Risk level of a screening finding (database CHECK on {@code disease_detection.risk_level}), lowest first. A
 * screening whose overall level is below {@link #MEDIUM} stores nothing.
 */
public enum RiskLevel {

    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    /** True when this level is {@code other} or higher. */
    public boolean isAtLeast(RiskLevel other) {
        return compareTo(other) >= 0;
    }

    /** The higher of the two levels; a null argument counts as absent. */
    public static RiskLevel max(RiskLevel a, RiskLevel b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareTo(b) >= 0 ? a : b;
    }

    /** The level named by {@code value} (trimmed, any case), or empty for null, blank or unknown values. */
    public static Optional<RiskLevel> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (RiskLevel level : values()) {
            if (level.name().equals(normalized)) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }
}
