package com.carenexus.api.health.service;

import com.carenexus.api.health.config.HealthProperties;
import com.carenexus.api.health.model.HealthMetric;
import com.carenexus.api.health.model.MetricType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Classifies a reading against the adult reference ranges of {@link HealthProperties} (defaults in code,
 * {@code carenexus.health.ranges.*} overrides):
 * <ul>
 *   <li>CRITICAL when {@code value < criticalLow} or {@code value > criticalHigh};</li>
 *   <li>otherwise ABNORMAL when {@code value < low} or {@code value > high};</li>
 *   <li>otherwise NORMAL.</li>
 * </ul>
 * Unset bounds are not checked, so a type without a range (WEIGHT, HEIGHT, STEPS by default) is always NORMAL.
 * The same ranges apply to every patient regardless of age.
 *
 * BMI is derived from the latest weight and height: {@code weightKg / (heightCm / 100)^2}, rounded HALF_UP to one
 * decimal, and classified with the BMI range.
 */
@Component
@RequiredArgsConstructor
public class VitalRangeEvaluator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final HealthProperties properties;

    /** NORMAL, ABNORMAL or CRITICAL for {@code value} of {@code type}. */
    public String evaluate(MetricType type, BigDecimal value) {
        if (type == null || value == null) {
            throw new IllegalArgumentException("Type and value are required");
        }
        HealthProperties.Range range = properties.getRanges().forType(type);
        if (below(value, range.getCriticalLow()) || above(value, range.getCriticalHigh())) {
            return HealthMetric.SEVERITY_CRITICAL;
        }
        if (below(value, range.getLow()) || above(value, range.getHigh())) {
            return HealthMetric.SEVERITY_ABNORMAL;
        }
        return HealthMetric.SEVERITY_NORMAL;
    }

    /** True for ABNORMAL and CRITICAL. */
    public static boolean isAbnormal(String severity) {
        return !HealthMetric.SEVERITY_NORMAL.equals(severity);
    }

    /**
     * Body-mass index from a weight in kg and a height in cm, rounded HALF_UP to one decimal; null when either is
     * missing or the height is not positive.
     */
    public BigDecimal bmi(BigDecimal weightKg, BigDecimal heightCm) {
        if (weightKg == null || heightCm == null || heightCm.signum() <= 0 || weightKg.signum() < 0) {
            return null;
        }
        BigDecimal heightM = heightCm.divide(HUNDRED, MathContext.DECIMAL64);
        return weightKg.divide(heightM.multiply(heightM, MathContext.DECIMAL64), MathContext.DECIMAL64)
                .setScale(1, RoundingMode.HALF_UP);
    }

    private static boolean below(BigDecimal value, BigDecimal bound) {
        return bound != null && value.compareTo(bound) < 0;
    }

    private static boolean above(BigDecimal value, BigDecimal bound) {
        return bound != null && value.compareTo(bound) > 0;
    }
}
