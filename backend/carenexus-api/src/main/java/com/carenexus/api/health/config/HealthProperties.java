package com.carenexus.api.health.config;

import com.carenexus.api.health.model.MetricType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Health-tracking settings ({@code carenexus.health.*}). Defaults live here; application.yml only overrides.
 *
 * <pre>
 * carenexus.health.wearables.provider = disabled          WearableConnector implementation
 * carenexus.health.ranges.&lt;type-kebab&gt;.critical-low    adult reference ranges used by VitalRangeEvaluator,
 * carenexus.health.ranges.&lt;type-kebab&gt;.low             for example carenexus.health.ranges.heart-rate.high=100
 * carenexus.health.ranges.&lt;type-kebab&gt;.high            A bound left empty means "no bound on that side".
 * carenexus.health.ranges.&lt;type-kebab&gt;.critical-high
 * </pre>
 *
 * The ranges are applied to every patient regardless of age. A change affects readings stored afterwards only:
 * the severity of a stored reading is fixed when it is recorded.
 */
@Getter
@Setter
@ConfigurationProperties("carenexus.health")
public class HealthProperties {

    private Wearables wearables = new Wearables();

    private Ranges ranges = new Ranges();

    @Getter
    @Setter
    public static class Wearables {

        public static final String DISABLED = "disabled";

        /** Wearable connector id; only {@code disabled} ships with this build. */
        private String provider = DISABLED;

        public String providerId() {
            String id = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
            return id.isEmpty() ? DISABLED : id;
        }
    }

    /**
     * One reference range per evaluated type. WEIGHT, HEIGHT and STEPS have no clinical range by default (every
     * value is NORMAL); they can be given one.
     */
    @Getter
    @Setter
    public static class Ranges {

        private Range bpSystolic = Range.of("70", "90", "139", "179");
        private Range bpDiastolic = Range.of("40", "60", "89", "119");
        private Range heartRate = Range.of("40", "60", "100", "130");
        private Range glucose = Range.of("3.0", "3.9", "7.8", "13.9");
        private Range spo2 = Range.of("90", "95", null, null);
        private Range temperature = Range.of("35.0", "36.0", "37.5", "39.4");
        private Range respiratoryRate = Range.of("9", "12", "20", "29");
        private Range bmi = Range.of(null, "18.5", "24.9", null);
        private Range weight = new Range();
        private Range height = new Range();
        private Range steps = new Range();

        /** The range configured for a type (never null; an empty range means always NORMAL). */
        public Range forType(MetricType type) {
            Range range = switch (type) {
                case BP_SYSTOLIC -> bpSystolic;
                case BP_DIASTOLIC -> bpDiastolic;
                case HEART_RATE -> heartRate;
                case GLUCOSE -> glucose;
                case SPO2 -> spo2;
                case TEMPERATURE -> temperature;
                case RESPIRATORY_RATE -> respiratoryRate;
                case BMI -> bmi;
                case WEIGHT -> weight;
                case HEIGHT -> height;
                case STEPS -> steps;
            };
            return range == null ? new Range() : range;
        }
    }

    /**
     * Bounds of one reference range. CRITICAL when value &lt; criticalLow or value &gt; criticalHigh; otherwise
     * ABNORMAL when value &lt; low or value &gt; high; otherwise NORMAL. A null bound is not checked.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Range {

        private BigDecimal criticalLow;
        private BigDecimal low;
        private BigDecimal high;
        private BigDecimal criticalHigh;

        static Range of(String criticalLow, String low, String high, String criticalHigh) {
            return new Range(decimal(criticalLow), decimal(low), decimal(high), decimal(criticalHigh));
        }

        private static BigDecimal decimal(String value) {
            return value == null ? null : new BigDecimal(value);
        }
    }
}
