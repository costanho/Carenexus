package com.carenexus.api.health.model;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

/**
 * The health metric types of {@code health_metrics.type} (the V1 CHECK list), in the canonical order used by the
 * "latest" endpoint. Each type has exactly one ASCII unit and a plausible physical range: a value outside it is
 * rejected as a typing or device error, whatever the clinical reference ranges say.
 */
public enum MetricType {

    BP_SYSTOLIC("Systolic pressure", "mmHg", "50", "300", true),
    BP_DIASTOLIC("Diastolic pressure", "mmHg", "20", "200", true),
    HEART_RATE("Heart rate", "bpm", "20", "300", true),
    GLUCOSE("Blood glucose", "mmol/L", "1", "50", true),
    WEIGHT("Weight", "kg", "1", "500", true),
    HEIGHT("Height", "cm", "30", "275", true),
    /** Derived from height and weight at read time; never entered manually. */
    BMI("BMI", "kg/m2", "5", "150", false),
    SPO2("Oxygen saturation", "%", "50", "100", true),
    TEMPERATURE("Temperature", "C", "25", "45", true),
    RESPIRATORY_RATE("Respiratory rate", "breaths/min", "4", "80", true),
    STEPS("Steps", "steps", "0", "200000", true);

    private final String label;
    private final String unit;
    private final BigDecimal plausibleMin;
    private final BigDecimal plausibleMax;
    private final boolean manualEntry;

    MetricType(String label, String unit, String plausibleMin, String plausibleMax, boolean manualEntry) {
        this.label = label;
        this.unit = unit;
        this.plausibleMin = new BigDecimal(plausibleMin);
        this.plausibleMax = new BigDecimal(plausibleMax);
        this.manualEntry = manualEntry;
    }

    /** Name used in validation messages, for example "Heart rate". */
    public String label() {
        return label;
    }

    /** The one unit stored and accepted for this type. */
    public String unit() {
        return unit;
    }

    public BigDecimal plausibleMin() {
        return plausibleMin;
    }

    public BigDecimal plausibleMax() {
        return plausibleMax;
    }

    /** False for BMI, which is derived from the latest height and weight. */
    public boolean manualEntry() {
        return manualEntry;
    }

    /** True for the two blood-pressure types, which are entered and removed as a pair. */
    public boolean isBloodPressure() {
        return this == BP_SYSTOLIC || this == BP_DIASTOLIC;
    }

    /** The other blood-pressure type, or empty for any other type. */
    public Optional<MetricType> bloodPressurePartner() {
        return switch (this) {
            case BP_SYSTOLIC -> Optional.of(BP_DIASTOLIC);
            case BP_DIASTOLIC -> Optional.of(BP_SYSTOLIC);
            default -> Optional.empty();
        };
    }

    /** Lenient lookup: trimmed and upper-cased; empty for null, blank or unknown values. */
    public static Optional<MetricType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (MetricType type : values()) {
            if (type.name().equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
