package com.carenexus.api.health.service;

import com.carenexus.api.health.config.HealthProperties;
import com.carenexus.api.health.model.MetricType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The adult reference ranges of CONTRACT be-health-tracking 2.2 (defaults of {@link HealthProperties}): CRITICAL
 * beyond the critical bounds, ABNORMAL beyond the normal bounds, NORMAL inside, with every bound itself inclusive
 * on the "better" side.
 */
class VitalRangeEvaluatorTest {

    private final VitalRangeEvaluator evaluator = new VitalRangeEvaluator(new HealthProperties());

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            // Systolic: critical < 70, abnormal < 90, normal 90..139, abnormal > 139, critical > 179
            "BP_SYSTOLIC, 69, CRITICAL",
            "BP_SYSTOLIC, 70, ABNORMAL",
            "BP_SYSTOLIC, 89.9, ABNORMAL",
            "BP_SYSTOLIC, 90, NORMAL",
            "BP_SYSTOLIC, 120, NORMAL",
            "BP_SYSTOLIC, 139, NORMAL",
            "BP_SYSTOLIC, 140, ABNORMAL",
            "BP_SYSTOLIC, 179, ABNORMAL",
            "BP_SYSTOLIC, 180, CRITICAL",
            // Diastolic: 40 / 60 / 89 / 119
            "BP_DIASTOLIC, 39, CRITICAL",
            "BP_DIASTOLIC, 59, ABNORMAL",
            "BP_DIASTOLIC, 60, NORMAL",
            "BP_DIASTOLIC, 89, NORMAL",
            "BP_DIASTOLIC, 90, ABNORMAL",
            "BP_DIASTOLIC, 120, CRITICAL",
            // Heart rate: 40 / 60 / 100 / 130
            "HEART_RATE, 35, CRITICAL",
            "HEART_RATE, 55, ABNORMAL",
            "HEART_RATE, 76, NORMAL",
            "HEART_RATE, 100, NORMAL",
            "HEART_RATE, 101, ABNORMAL",
            "HEART_RATE, 131, CRITICAL",
            // Glucose (mmol/L): 3.0 / 3.9 / 7.8 / 13.9
            "GLUCOSE, 2.9, CRITICAL",
            "GLUCOSE, 3.0, ABNORMAL",
            "GLUCOSE, 3.9, NORMAL",
            "GLUCOSE, 7.8, NORMAL",
            "GLUCOSE, 7.81, ABNORMAL",
            "GLUCOSE, 14, CRITICAL",
            // SpO2: 90 / 95 / no upper bounds
            "SPO2, 89, CRITICAL",
            "SPO2, 94, ABNORMAL",
            "SPO2, 95, NORMAL",
            "SPO2, 100, NORMAL",
            // Temperature (C): 35.0 / 36.0 / 37.5 / 39.4
            "TEMPERATURE, 34.9, CRITICAL",
            "TEMPERATURE, 35.5, ABNORMAL",
            "TEMPERATURE, 36.8, NORMAL",
            "TEMPERATURE, 37.6, ABNORMAL",
            "TEMPERATURE, 39.5, CRITICAL",
            // Respiratory rate: 9 / 12 / 20 / 29
            "RESPIRATORY_RATE, 8, CRITICAL",
            "RESPIRATORY_RATE, 11, ABNORMAL",
            "RESPIRATORY_RATE, 16, NORMAL",
            "RESPIRATORY_RATE, 21, ABNORMAL",
            "RESPIRATORY_RATE, 30, CRITICAL",
            // BMI: no critical bounds, 18.5 / 24.9
            "BMI, 12, ABNORMAL",
            "BMI, 18.4, ABNORMAL",
            "BMI, 18.5, NORMAL",
            "BMI, 24.9, NORMAL",
            "BMI, 25, ABNORMAL",
            "BMI, 60, ABNORMAL",
            // No clinical range: always NORMAL
            "WEIGHT, 1, NORMAL",
            "WEIGHT, 500, NORMAL",
            "HEIGHT, 30, NORMAL",
            "STEPS, 0, NORMAL",
            "STEPS, 200000, NORMAL"
    })
    void defaultAdultRanges(MetricType type, String value, String expected) {
        assertEquals(expected, evaluator.evaluate(type, new BigDecimal(value)));
    }

    @Test
    void isAbnormal_isTrueForEverySeverityButNormal() {
        assertFalse(VitalRangeEvaluator.isAbnormal("NORMAL"));
        assertTrue(VitalRangeEvaluator.isAbnormal("ABNORMAL"));
        assertTrue(VitalRangeEvaluator.isAbnormal("CRITICAL"));
    }

    @Test
    void configuredRangesOverrideTheDefaults() {
        HealthProperties properties = new HealthProperties();
        properties.getRanges().setHeartRate(new HealthProperties.Range(
                new BigDecimal("35"), new BigDecimal("50"), new BigDecimal("90"), new BigDecimal("120")));
        properties.getRanges().setWeight(new HealthProperties.Range(null, new BigDecimal("40"), null, null));
        VitalRangeEvaluator custom = new VitalRangeEvaluator(properties);

        assertEquals("NORMAL", custom.evaluate(MetricType.HEART_RATE, new BigDecimal("55")));
        assertEquals("ABNORMAL", custom.evaluate(MetricType.HEART_RATE, new BigDecimal("95")));
        assertEquals("CRITICAL", custom.evaluate(MetricType.HEART_RATE, new BigDecimal("125")));
        assertEquals("ABNORMAL", custom.evaluate(MetricType.WEIGHT, new BigDecimal("39")));
        assertEquals("NORMAL", custom.evaluate(MetricType.WEIGHT, new BigDecimal("400")));
    }

    @Test
    void aRangeLeftUnsetMeansNormal() {
        HealthProperties properties = new HealthProperties();
        properties.getRanges().setGlucose(null);
        assertEquals("NORMAL", new VitalRangeEvaluator(properties).evaluate(MetricType.GLUCOSE, new BigDecimal("30")));
    }

    @Test
    void evaluateRejectsMissingInput() {
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(null, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(MetricType.HEART_RATE, null));
    }

    @ParameterizedTest(name = "{0} kg, {1} cm -> {2}")
    @CsvSource({
            "71.5, 172, 24.2",
            "70, 175, 22.9",
            "100, 180, 30.9",
            "50, 160, 19.5",
            // HALF_UP at the first decimal: 64.8 / 1.8^2 = 20.0
            "64.8, 180, 20.0",
            // 22.45 exactly rounds up
            "57.4720, 160, 22.5"
    })
    void bmiIsWeightOverHeightSquared_roundedHalfUpToOneDecimal(String weight, String height, String expected) {
        BigDecimal bmi = evaluator.bmi(new BigDecimal(weight), new BigDecimal(height));
        assertEquals(0, new BigDecimal(expected).compareTo(bmi), "BMI " + bmi);
        assertEquals(1, bmi.scale());
    }

    @Test
    void bmiIsNullWithoutUsableInput() {
        assertNull(evaluator.bmi(null, new BigDecimal("170")));
        assertNull(evaluator.bmi(new BigDecimal("70"), null));
        assertNull(evaluator.bmi(new BigDecimal("70"), BigDecimal.ZERO));
    }
}
