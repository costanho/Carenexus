package com.carenexus.api.ai.provider;

import com.carenexus.api.ai.provider.DetectionInput.Reading;
import com.carenexus.api.ai.provider.DetectionInput.SymptomEntry;
import com.carenexus.api.ai.provider.DetectionResult.DetectedCondition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule table of {@code rules-v1} (api/be-ai-detection.md 2.3): every threshold on both sides, the symptom
 * clusters, ordering of conditions and recommendations, and evidence wording.
 */
class RuleBasedRiskDetectionProviderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    private final RuleBasedRiskDetectionProvider provider = new RuleBasedRiskDetectionProvider();

    // ==================== helpers ====================

    private static Reading reading(String type, String value, String unit) {
        return new Reading(type, new BigDecimal(value), unit, TODAY);
    }

    private static SymptomEntry log(String severity, String... symptoms) {
        return new SymptomEntry(TODAY, severity, List.of(symptoms));
    }

    private static DetectionInput input(List<Reading> readings, List<SymptomEntry> logs) {
        return new DetectionInput(TODAY, "40-49", "FEMALE", List.of("Hypertension"), logs, readings, List.of("Amlodipine"));
    }

    private static DetectionInput readings(Reading... readings) {
        return input(List.of(readings), List.of());
    }

    private DetectedCondition only(DetectionResult result) {
        assertEquals(1, result.conditions().size(), () -> "conditions: " + result.conditions());
        return result.conditions().get(0);
    }

    private static void assertLevel(String expectedLevel, String expectedConfidence, DetectedCondition condition) {
        assertEquals(RiskLevel.valueOf(expectedLevel), condition.riskLevel());
        assertEquals(0, new BigDecimal(expectedConfidence).compareTo(condition.confidence()),
                () -> "confidence " + condition.confidence());
    }

    // ==================== provider contract ====================

    @Test
    void identifiesItselfAndIsAlwaysConfigured() {
        assertEquals("rules-v1", provider.modelVersion());
        assertTrue(provider.isConfigured());
        assertThrows(IllegalArgumentException.class, () -> provider.assess(null));
    }

    @Test
    void emptyInputFindsNothing() {
        DetectionResult result = provider.assess(input(List.of(), List.of()));
        assertEquals(RiskLevel.LOW, result.riskLevel());
        assertNull(result.confidenceScore());
        assertTrue(result.conditions().isEmpty());
        assertTrue(result.recommendations().isEmpty());
        assertNull(result.primary());
    }

    @Test
    void normalReadingsFindNothing() {
        DetectionResult result = provider.assess(readings(reading("BP_SYSTOLIC", "120", "mmHg"),
                reading("BP_DIASTOLIC", "80", "mmHg"), reading("SPO2", "97", "%"), reading("GLUCOSE", "5.5", "mmol/L"),
                reading("TEMPERATURE", "36.8", "C"), reading("HEART_RATE", "150", "bpm")));
        assertTrue(result.conditions().isEmpty());
        assertEquals(RiskLevel.LOW, result.riskLevel());
    }

    // ==================== blood pressure ====================

    @ParameterizedTest(name = "{0}/{1} mmHg -> {2}")
    @CsvSource({
            "139, 89, , ",
            "140, 80, MEDIUM, 0.7",
            "120, 90, MEDIUM, 0.7",
            "159, 99, MEDIUM, 0.7",
            "160, 80, HIGH, 0.8",
            "130, 100, HIGH, 0.8",
            "179, 119, HIGH, 0.8",
            "180, 90, CRITICAL, 0.9",
            "150, 120, CRITICAL, 0.9",
    })
    void hypertensiveBloodPressure(String systolic, String diastolic, String level, String confidence) {
        DetectionResult result = provider.assess(readings(reading("BP_SYSTOLIC", systolic, "mmHg"),
                reading("BP_DIASTOLIC", diastolic, "mmHg")));
        if (level == null) {
            assertTrue(result.conditions().isEmpty());
            return;
        }
        DetectedCondition condition = only(result);
        assertEquals("HYPERTENSIVE_BP", condition.code());
        assertEquals("Blood pressure in the hypertensive range", condition.name());
        assertLevel(level, confidence, condition);
        assertEquals(List.of("Blood pressure " + systolic + "/" + diastolic + " mmHg recorded on 2026-11-10"),
                condition.evidence());
        assertEquals(List.of("Repeat the blood pressure measurement and review blood pressure management."),
                result.recommendations());
        assertEquals(condition.riskLevel(), result.riskLevel());
        assertEquals(condition.confidence(), result.confidenceScore());
    }

    @Test
    void bloodPressureFromDifferentDaysIsDescribedPerReading() {
        DetectionResult result = provider.assess(readings(reading("BP_SYSTOLIC", "165.000", "mmHg"),
                new Reading("BP_DIASTOLIC", new BigDecimal("85"), "mmHg", YESTERDAY)));
        DetectedCondition condition = only(result);
        assertLevel("HIGH", "0.8", condition);
        assertEquals(List.of("Systolic pressure 165 mmHg recorded on 2026-11-10",
                "Diastolic pressure 85 mmHg recorded on 2026-11-09"), condition.evidence());
    }

    @Test
    void oneSideOfTheBloodPressureIsEnough() {
        DetectedCondition condition = only(provider.assess(readings(reading("BP_DIASTOLIC", "101", "mmHg"))));
        assertLevel("HIGH", "0.8", condition);
        assertEquals(List.of("Diastolic pressure 101 mmHg recorded on 2026-11-10"), condition.evidence());
    }

    // ==================== oxygen saturation ====================

    @ParameterizedTest(name = "SpO2 {0} -> {1}")
    @CsvSource({
            "92, , ",
            "91.9, HIGH, 0.85",
            "88, HIGH, 0.85",
            "87.9, CRITICAL, 0.9",
            "80, CRITICAL, 0.9",
    })
    void lowOxygenSaturation(String spo2, String level, String confidence) {
        DetectionResult result = provider.assess(readings(reading("SPO2", spo2, "%")));
        if (level == null) {
            assertTrue(result.conditions().isEmpty());
            return;
        }
        DetectedCondition condition = only(result);
        assertEquals("LOW_OXYGEN_SATURATION", condition.code());
        assertEquals("Low oxygen saturation", condition.name());
        assertLevel(level, confidence, condition);
        assertEquals(List.of("Oxygen saturation " + spo2 + "% recorded on 2026-11-10"), condition.evidence());
        assertEquals(List.of("Check oxygen saturation again and assess the patient's breathing."),
                result.recommendations());
    }

    // ==================== glucose ====================

    @ParameterizedTest(name = "glucose {0} -> {1} {2}")
    @CsvSource({
            "11.0, , , ",
            "11.1, HYPERGLYCAEMIA, MEDIUM, 0.65",
            "16.6, HYPERGLYCAEMIA, MEDIUM, 0.65",
            "16.7, HYPERGLYCAEMIA, HIGH, 0.75",
            "22.1, HYPERGLYCAEMIA, HIGH, 0.75",
            "22.2, HYPERGLYCAEMIA, CRITICAL, 0.85",
            "3.9, , , ",
            "3.8, HYPOGLYCAEMIA, MEDIUM, 0.7",
            "3.0, HYPOGLYCAEMIA, MEDIUM, 0.7",
            "2.9, HYPOGLYCAEMIA, HIGH, 0.8",
            "2.2, HYPOGLYCAEMIA, HIGH, 0.8",
            "2.1, HYPOGLYCAEMIA, CRITICAL, 0.9",
    })
    void bloodGlucose(String glucose, String code, String level, String confidence) {
        DetectionResult result = provider.assess(readings(reading("GLUCOSE", glucose, "mmol/L")));
        if (code == null) {
            assertTrue(result.conditions().isEmpty());
            return;
        }
        DetectedCondition condition = only(result);
        assertEquals(code, condition.code());
        assertEquals("HYPERGLYCAEMIA".equals(code) ? "High blood glucose" : "Low blood glucose", condition.name());
        assertLevel(level, confidence, condition);
        assertEquals(List.of("Blood glucose " + new BigDecimal(glucose).stripTrailingZeros().toPlainString()
                + " mmol/L recorded on 2026-11-10"), condition.evidence());
        assertEquals(1, result.recommendations().size());
    }

    // ==================== fever with symptom clusters ====================

    @ParameterizedTest(name = "{0} C, SpO2 {1}, \"{2}\" -> {3} {4}")
    @CsvSource({
            "37.9, , Dry cough, , ",
            "38.0, , Dry cough, MEDIUM, 0.6",
            "39.4, , Shortness of breath, MEDIUM, 0.6",
            "39.5, , WHEEZING, HIGH, 0.75",
            "38.2, 94, Sore throat, HIGH, 0.75",
            "38.2, 95, Chest pain, MEDIUM, 0.6",
            "38.5, , Feeling breathless, MEDIUM, 0.6",
            "38.5, , Difficulty breathing at night, MEDIUM, 0.6",
            "39.0, , Headache, , ",
    })
    void feverWithRespiratorySymptoms(String temperature, String spo2, String symptom, String level, String confidence) {
        List<Reading> readings = new ArrayList<>();
        readings.add(reading("TEMPERATURE", temperature, "C"));
        if (spo2 != null) {
            readings.add(reading("SPO2", spo2, "%"));
        }
        DetectionResult result = provider.assess(input(readings, List.of(log("MILD", symptom))));
        List<DetectedCondition> respiratory = result.conditions().stream()
                .filter(c -> c.code().equals("FEVER_RESPIRATORY")).toList();
        if (level == null) {
            assertTrue(respiratory.isEmpty());
            return;
        }
        DetectedCondition condition = respiratory.get(0);
        assertEquals("Fever with respiratory symptoms", condition.name());
        assertLevel(level, confidence, condition);
        assertEquals("Temperature " + new BigDecimal(temperature).stripTrailingZeros().toPlainString()
                + " C recorded on 2026-11-10", condition.evidence().get(0));
        assertEquals("Symptoms logged on 2026-11-10: " + symptom, condition.evidence().get(condition.evidence().size() - 1));
        assertTrue(result.recommendations().contains(
                "Assess the patient for a respiratory infection and check oxygen saturation."));
    }

    @ParameterizedTest(name = "{0} C, \"{1}\" -> {2}")
    @CsvSource({
            "37.5, Vomiting, , ",
            "38.0, Vomiting, MEDIUM, 0.6",
            "38.0, diarrhoea, MEDIUM, 0.6",
            "38.0, Nausea, MEDIUM, 0.6",
            "39.4, Abdominal pain, MEDIUM, 0.6",
            "39.5, Stomach pain, HIGH, 0.7",
            "40.0, Rash, , ",
    })
    void feverWithGastrointestinalSymptoms(String temperature, String symptom, String level, String confidence) {
        DetectionResult result = provider.assess(input(List.of(reading("TEMPERATURE", temperature, "C")),
                List.of(log("MODERATE", symptom))));
        if (level == null) {
            assertTrue(result.conditions().isEmpty());
            return;
        }
        DetectedCondition condition = only(result);
        assertEquals("FEVER_GASTROINTESTINAL", condition.code());
        assertEquals("Fever with gastrointestinal symptoms", condition.name());
        assertLevel(level, confidence, condition);
        assertEquals(List.of("Assess hydration and consider a gastrointestinal infection."), result.recommendations());
    }

    @Test
    void symptomsWithoutFeverMatchNoCluster() {
        DetectionResult result = provider.assess(input(List.of(), List.of(log("MILD", "Cough", "Vomiting"))));
        assertTrue(result.conditions().isEmpty());
    }

    @Test
    void everyMatchingLogIsEvidenceNewestFirst() {
        DetectionResult result = provider.assess(input(List.of(reading("TEMPERATURE", "38.4", "C")), List.of(
                log("MILD", "Dry cough", "Headache", "Shortness of breath"),
                new SymptomEntry(YESTERDAY, "MILD", List.of("Sore throat")))));
        DetectedCondition condition = only(result);
        assertEquals(List.of("Temperature 38.4 C recorded on 2026-11-10",
                "Symptoms logged on 2026-11-10: Dry cough, Shortness of breath",
                "Symptoms logged on 2026-11-09: Sore throat"), condition.evidence());
    }

    // ==================== severe symptoms ====================

    @ParameterizedTest(name = "newest log {0} -> {1}")
    @CsvSource({
            "MILD, , ",
            "MODERATE, , ",
            "SEVERE, MEDIUM, 0.5",
            "severe, MEDIUM, 0.5",
            "CRITICAL, HIGH, 0.6",
    })
    void severeSymptoms(String severity, String level, String confidence) {
        DetectionResult result = provider.assess(input(List.of(), List.of(log(severity, "Headache"))));
        if (level == null) {
            assertTrue(result.conditions().isEmpty());
            return;
        }
        DetectedCondition condition = only(result);
        assertEquals("SEVERE_SYMPTOMS", condition.code());
        assertEquals("Severe symptoms reported", condition.name());
        assertLevel(level, confidence, condition);
        assertEquals(List.of("Symptom log of 2026-11-10 was marked " + severity.toUpperCase()), condition.evidence());
        assertEquals(List.of("Contact the patient to assess the reported symptoms."), result.recommendations());
    }

    @Test
    void onlyTheNewestLogCountsForSeverity() {
        DetectionResult result = provider.assess(input(List.of(), List.of(log("MILD", "Headache"),
                new SymptomEntry(YESTERDAY, "CRITICAL", List.of("Headache")))));
        assertTrue(result.conditions().isEmpty());
    }

    // ==================== combination and ordering ====================

    @Test
    void conditionsAreOrderedByLevelThenConfidenceThenCode() {
        DetectionResult result = provider.assess(input(
                List.of(reading("TEMPERATURE", "39.6", "C"), reading("BP_SYSTOLIC", "145", "mmHg"),
                        reading("GLUCOSE", "12", "mmol/L"), reading("SPO2", "93", "%")),
                List.of(log("SEVERE", "Dry cough", "Vomiting"))));

        assertEquals(List.of("FEVER_RESPIRATORY", "FEVER_GASTROINTESTINAL", "HYPERTENSIVE_BP", "HYPERGLYCAEMIA",
                "SEVERE_SYMPTOMS"), result.conditions().stream().map(DetectedCondition::code).toList());
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertEquals(0, new BigDecimal("0.75").compareTo(result.confidenceScore()));
        assertEquals(List.of(
                "Assess the patient for a respiratory infection and check oxygen saturation.",
                "Assess hydration and consider a gastrointestinal infection.",
                "Repeat the blood pressure measurement and review blood pressure management.",
                "Repeat the glucose measurement and review glucose management.",
                "Contact the patient to assess the reported symptoms."), result.recommendations());
        // SpO2 93 is not low enough for its own rule but raises the respiratory cluster
        assertFalse(result.conditions().stream().anyMatch(c -> c.code().equals("LOW_OXYGEN_SATURATION")));
    }

    @Test
    void criticalFindingLeadsAndSetsTheOverallLevel() {
        DetectionResult result = provider.assess(input(
                List.of(reading("SPO2", "85", "%"), reading("BP_SYSTOLIC", "185", "mmHg")),
                List.of(log("CRITICAL", "Chest pain"))));
        assertEquals(List.of("HYPERTENSIVE_BP", "LOW_OXYGEN_SATURATION", "SEVERE_SYMPTOMS"),
                result.conditions().stream().map(DetectedCondition::code).toList());
        assertEquals(RiskLevel.CRITICAL, result.riskLevel());
    }

    @Test
    void evidenceIsLimitedInCountAndLength() {
        List<SymptomEntry> logs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            logs.add(new SymptomEntry(TODAY.minusDays(i), "MILD", List.of("Cough " + "x".repeat(250))));
        }
        DetectedCondition condition = only(provider.assess(input(List.of(reading("TEMPERATURE", "38.5", "C")), logs)));
        assertEquals(5, condition.evidence().size());
        condition.evidence().forEach(sentence -> assertTrue(sentence.length() <= 200, sentence));
        assertTrue(condition.evidence().get(1).endsWith("..."));
    }

    @Test
    void everyCodeHasARecommendation() {
        for (String code : List.of("HYPERTENSIVE_BP", "LOW_OXYGEN_SATURATION", "HYPERGLYCAEMIA", "HYPOGLYCAEMIA",
                "FEVER_RESPIRATORY", "FEVER_GASTROINTESTINAL", "SEVERE_SYMPTOMS")) {
            String sentence = RuleBasedRiskDetectionProvider.recommendation(code);
            assertTrue(sentence.length() <= 300 && sentence.endsWith("."), code);
        }
        assertThrows(IllegalArgumentException.class, () -> RuleBasedRiskDetectionProvider.recommendation("OTHER"));
    }

    @Test
    void disabledProviderIsNotConfiguredAndRefusesToAssess() {
        DisabledRiskDetectionProvider disabled = new DisabledRiskDetectionProvider();
        assertFalse(disabled.isConfigured());
        assertEquals("disabled", disabled.modelVersion());
        assertThrows(IllegalStateException.class, () -> disabled.assess(input(List.of(), List.of())));
    }
}
