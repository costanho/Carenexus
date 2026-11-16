package com.carenexus.api.ai.provider;

import com.carenexus.api.ai.provider.DetectionInput.Reading;
import com.carenexus.api.ai.provider.DetectionInput.SymptomEntry;
import com.carenexus.api.ai.provider.DetectionResult.DetectedCondition;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic screening rules, model version {@code rules-v1} (api/be-ai-detection.md section 2.3). The default
 * provider in every environment; it is a local implementation for {@code LocalProviderGuard}.
 *
 * <table>
 *   <caption>Rules</caption>
 *   <tr><th>Code</th><th>Rule (level, confidence)</th></tr>
 *   <tr><td>HYPERTENSIVE_BP</td><td>systolic &ge; 140 or diastolic &ge; 90: MEDIUM 0.7; &ge; 160 / &ge; 100: HIGH 0.8;
 *       &ge; 180 / &ge; 120: CRITICAL 0.9</td></tr>
 *   <tr><td>LOW_OXYGEN_SATURATION</td><td>SpO2 &lt; 92: HIGH 0.85; &lt; 88: CRITICAL 0.9</td></tr>
 *   <tr><td>HYPERGLYCAEMIA</td><td>glucose &ge; 11.1 mmol/L: MEDIUM 0.65; &ge; 16.7: HIGH 0.75; &ge; 22.2: CRITICAL 0.85</td></tr>
 *   <tr><td>HYPOGLYCAEMIA</td><td>glucose &lt; 3.9 mmol/L: MEDIUM 0.7; &lt; 3.0: HIGH 0.8; &lt; 2.2: CRITICAL 0.9</td></tr>
 *   <tr><td>FEVER_RESPIRATORY</td><td>fever (temperature &ge; 38.0 C) and a respiratory symptom: MEDIUM 0.6; HIGH 0.75
 *       when the temperature is &ge; 39.5 or SpO2 &lt; 95</td></tr>
 *   <tr><td>FEVER_GASTROINTESTINAL</td><td>fever and a gastrointestinal symptom: MEDIUM 0.6; HIGH 0.7 when the
 *       temperature is &ge; 39.5</td></tr>
 *   <tr><td>SEVERE_SYMPTOMS</td><td>newest symptom log SEVERE: MEDIUM 0.5; CRITICAL: HIGH 0.6</td></tr>
 * </table>
 * Readings are the latest per type of the input; symptom keywords match case-insensitively as substrings. A code
 * appears at most once, at the highest level its rule reached.
 */
public class RuleBasedRiskDetectionProvider implements RiskDetectionProvider {

    public static final String MODEL_VERSION = "rules-v1";

    public static final String HYPERTENSIVE_BP = "HYPERTENSIVE_BP";
    public static final String LOW_OXYGEN_SATURATION = "LOW_OXYGEN_SATURATION";
    public static final String HYPERGLYCAEMIA = "HYPERGLYCAEMIA";
    public static final String HYPOGLYCAEMIA = "HYPOGLYCAEMIA";
    public static final String FEVER_RESPIRATORY = "FEVER_RESPIRATORY";
    public static final String FEVER_GASTROINTESTINAL = "FEVER_GASTROINTESTINAL";
    public static final String SEVERE_SYMPTOMS = "SEVERE_SYMPTOMS";

    static final List<String> RESPIRATORY_KEYWORDS = List.of("cough", "shortness of breath", "breathless",
            "difficulty breathing", "wheez", "sore throat", "chest pain");
    static final List<String> GASTROINTESTINAL_KEYWORDS = List.of("vomit", "diarrh", "nausea", "abdominal pain",
            "stomach pain");

    static final int MAX_EVIDENCE = 5;
    static final int MAX_EVIDENCE_LENGTH = 200;

    private static final BigDecimal FEVER = new BigDecimal("38.0");
    private static final BigDecimal HIGH_FEVER = new BigDecimal("39.5");

    private static final Comparator<DetectedCondition> ORDER = Comparator
            .comparing(DetectedCondition::riskLevel, Comparator.reverseOrder())
            .thenComparing(DetectedCondition::confidence, Comparator.reverseOrder())
            .thenComparing(DetectedCondition::code);

    @Override
    public String modelVersion() {
        return MODEL_VERSION;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public DetectionResult assess(DetectionInput input) {
        if (input == null) {
            throw new IllegalArgumentException("Detection input is required");
        }
        List<DetectedCondition> found = new ArrayList<>();
        addIfPresent(found, hypertensiveBloodPressure(input));
        addIfPresent(found, lowOxygenSaturation(input));
        addIfPresent(found, hyperglycaemia(input));
        addIfPresent(found, hypoglycaemia(input));
        addIfPresent(found, feverRespiratory(input));
        addIfPresent(found, feverGastrointestinal(input));
        addIfPresent(found, severeSymptoms(input));
        if (found.isEmpty()) {
            return DetectionResult.noFinding();
        }
        found.sort(ORDER);
        List<String> recommendations = found.stream().map(c -> recommendation(c.code())).toList();
        DetectedCondition primary = found.get(0);
        return new DetectionResult(primary.riskLevel(), primary.confidence(), found, recommendations);
    }

    /** The recommendation sentence of a {@code rules-v1} code. */
    static String recommendation(String code) {
        return switch (code) {
            case HYPERTENSIVE_BP -> "Repeat the blood pressure measurement and review blood pressure management.";
            case LOW_OXYGEN_SATURATION -> "Check oxygen saturation again and assess the patient's breathing.";
            case HYPERGLYCAEMIA -> "Repeat the glucose measurement and review glucose management.";
            case HYPOGLYCAEMIA -> "Contact the patient about the low glucose reading and review glucose-lowering medication.";
            case FEVER_RESPIRATORY -> "Assess the patient for a respiratory infection and check oxygen saturation.";
            case FEVER_GASTROINTESTINAL -> "Assess hydration and consider a gastrointestinal infection.";
            case SEVERE_SYMPTOMS -> "Contact the patient to assess the reported symptoms.";
            default -> throw new IllegalArgumentException("Unknown rules-v1 code: " + code);
        };
    }

    // ==================== rules ====================

    private DetectedCondition hypertensiveBloodPressure(DetectionInput input) {
        Reading systolic = input.reading("BP_SYSTOLIC");
        Reading diastolic = input.reading("BP_DIASTOLIC");
        RiskLevel level = RiskLevel.max(
                systolic == null ? null : tier(systolic.value(), "140", "160", "180"),
                diastolic == null ? null : tier(diastolic.value(), "90", "100", "120"));
        if (level == null) {
            return null;
        }
        List<String> evidence = new ArrayList<>();
        if (systolic != null && diastolic != null && systolic.recordedOn().equals(diastolic.recordedOn())) {
            evidence.add("Blood pressure " + number(systolic.value()) + "/" + number(diastolic.value()) + " mmHg"
                    + recordedOn(systolic));
        } else {
            if (systolic != null) {
                evidence.add(describe("Systolic pressure", systolic));
            }
            if (diastolic != null) {
                evidence.add(describe("Diastolic pressure", diastolic));
            }
        }
        String confidence = switch (level) {
            case CRITICAL -> "0.9";
            case HIGH -> "0.8";
            default -> "0.7";
        };
        return condition(HYPERTENSIVE_BP, "Blood pressure in the hypertensive range", level, confidence, evidence);
    }

    private DetectedCondition lowOxygenSaturation(DetectionInput input) {
        Reading spo2 = input.reading("SPO2");
        if (spo2 == null || !below(spo2.value(), "92")) {
            return null;
        }
        boolean critical = below(spo2.value(), "88");
        return condition(LOW_OXYGEN_SATURATION, "Low oxygen saturation",
                critical ? RiskLevel.CRITICAL : RiskLevel.HIGH, critical ? "0.9" : "0.85",
                List.of(describe("Oxygen saturation", spo2)));
    }

    private DetectedCondition hyperglycaemia(DetectionInput input) {
        Reading glucose = input.reading("GLUCOSE");
        if (glucose == null) {
            return null;
        }
        RiskLevel level = tier(glucose.value(), "11.1", "16.7", "22.2");
        if (level == null) {
            return null;
        }
        String confidence = switch (level) {
            case CRITICAL -> "0.85";
            case HIGH -> "0.75";
            default -> "0.65";
        };
        return condition(HYPERGLYCAEMIA, "High blood glucose", level, confidence,
                List.of(describe("Blood glucose", glucose)));
    }

    private DetectedCondition hypoglycaemia(DetectionInput input) {
        Reading glucose = input.reading("GLUCOSE");
        if (glucose == null || !below(glucose.value(), "3.9")) {
            return null;
        }
        RiskLevel level;
        String confidence;
        if (below(glucose.value(), "2.2")) {
            level = RiskLevel.CRITICAL;
            confidence = "0.9";
        } else if (below(glucose.value(), "3.0")) {
            level = RiskLevel.HIGH;
            confidence = "0.8";
        } else {
            level = RiskLevel.MEDIUM;
            confidence = "0.7";
        }
        return condition(HYPOGLYCAEMIA, "Low blood glucose", level, confidence,
                List.of(describe("Blood glucose", glucose)));
    }

    private DetectedCondition feverRespiratory(DetectionInput input) {
        Reading temperature = fever(input);
        if (temperature == null) {
            return null;
        }
        List<String> symptomEvidence = matchingSymptoms(input, RESPIRATORY_KEYWORDS);
        if (symptomEvidence.isEmpty()) {
            return null;
        }
        Reading spo2 = input.reading("SPO2");
        boolean lowSaturation = spo2 != null && below(spo2.value(), "95");
        boolean high = temperature.value().compareTo(HIGH_FEVER) >= 0 || lowSaturation;

        List<String> evidence = new ArrayList<>();
        evidence.add(describe("Temperature", temperature));
        if (lowSaturation) {
            evidence.add(describe("Oxygen saturation", spo2));
        }
        evidence.addAll(symptomEvidence);
        return condition(FEVER_RESPIRATORY, "Fever with respiratory symptoms",
                high ? RiskLevel.HIGH : RiskLevel.MEDIUM, high ? "0.75" : "0.6", evidence);
    }

    private DetectedCondition feverGastrointestinal(DetectionInput input) {
        Reading temperature = fever(input);
        if (temperature == null) {
            return null;
        }
        List<String> symptomEvidence = matchingSymptoms(input, GASTROINTESTINAL_KEYWORDS);
        if (symptomEvidence.isEmpty()) {
            return null;
        }
        boolean high = temperature.value().compareTo(HIGH_FEVER) >= 0;
        List<String> evidence = new ArrayList<>();
        evidence.add(describe("Temperature", temperature));
        evidence.addAll(symptomEvidence);
        return condition(FEVER_GASTROINTESTINAL, "Fever with gastrointestinal symptoms",
                high ? RiskLevel.HIGH : RiskLevel.MEDIUM, high ? "0.7" : "0.6", evidence);
    }

    private DetectedCondition severeSymptoms(DetectionInput input) {
        if (input.symptomLogs().isEmpty()) {
            return null;
        }
        SymptomEntry newest = input.symptomLogs().get(0);
        String severity = newest.severity() == null ? "" : newest.severity().trim().toUpperCase(Locale.ROOT);
        boolean critical = "CRITICAL".equals(severity);
        if (!critical && !"SEVERE".equals(severity)) {
            return null;
        }
        return condition(SEVERE_SYMPTOMS, "Severe symptoms reported",
                critical ? RiskLevel.HIGH : RiskLevel.MEDIUM, critical ? "0.6" : "0.5",
                List.of("Symptom log of " + newest.loggedOn() + " was marked " + severity));
    }

    // ==================== helpers ====================

    /** The latest temperature when it is a fever, else null. */
    private static Reading fever(DetectionInput input) {
        Reading temperature = input.reading("TEMPERATURE");
        return temperature != null && temperature.value() != null && temperature.value().compareTo(FEVER) >= 0
                ? temperature : null;
    }

    /**
     * One evidence sentence per symptom log (newest first) that contains a symptom matching one of the keywords,
     * naming the matching symptoms.
     */
    private static List<String> matchingSymptoms(DetectionInput input, List<String> keywords) {
        List<String> sentences = new ArrayList<>();
        for (SymptomEntry log : input.symptomLogs()) {
            Set<String> matches = new LinkedHashSet<>();
            for (String symptom : log.symptoms()) {
                if (symptom == null) {
                    continue;
                }
                String lower = symptom.toLowerCase(Locale.ROOT);
                if (keywords.stream().anyMatch(lower::contains)) {
                    matches.add(symptom.trim());
                }
            }
            if (!matches.isEmpty()) {
                sentences.add("Symptoms logged on " + log.loggedOn() + ": " + String.join(", ", matches));
            }
        }
        return sentences;
    }

    /** MEDIUM, HIGH or CRITICAL when {@code value} reaches the given thresholds (inclusive), else null. */
    private static RiskLevel tier(BigDecimal value, String medium, String high, String critical) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(new BigDecimal(critical)) >= 0) {
            return RiskLevel.CRITICAL;
        }
        if (value.compareTo(new BigDecimal(high)) >= 0) {
            return RiskLevel.HIGH;
        }
        if (value.compareTo(new BigDecimal(medium)) >= 0) {
            return RiskLevel.MEDIUM;
        }
        return null;
    }

    private static boolean below(BigDecimal value, String bound) {
        return value != null && value.compareTo(new BigDecimal(bound)) < 0;
    }

    private static void addIfPresent(List<DetectedCondition> found, DetectedCondition condition) {
        if (condition != null) {
            found.add(condition);
        }
    }

    private static DetectedCondition condition(String code, String name, RiskLevel level, String confidence,
                                               List<String> evidence) {
        List<String> limited = evidence.stream()
                .limit(MAX_EVIDENCE)
                .map(RuleBasedRiskDetectionProvider::cut)
                .toList();
        return new DetectedCondition(code, name, level, new BigDecimal(confidence), limited);
    }

    /** For example "Blood glucose 12.4 mmol/L recorded on 2026-11-10" or "Oxygen saturation 89% recorded on ...". */
    private static String describe(String label, Reading reading) {
        String unit = reading.unit() == null ? "" : reading.unit().trim();
        String valueWithUnit = number(reading.value()) + (unit.isEmpty() ? "" : "%".equals(unit) ? "%" : " " + unit);
        return label + " " + valueWithUnit + recordedOn(reading);
    }

    private static String recordedOn(Reading reading) {
        return reading.recordedOn() == null ? "" : " recorded on " + reading.recordedOn();
    }

    /** Plain decimal without trailing zeros: 148.000 becomes "148", 39.60 becomes "39.6". */
    private static String number(BigDecimal value) {
        if (value == null) {
            return "";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
    }

    private static String cut(String sentence) {
        if (sentence.codePointCount(0, sentence.length()) <= MAX_EVIDENCE_LENGTH) {
            return sentence;
        }
        return sentence.substring(0, sentence.offsetByCodePoints(0, MAX_EVIDENCE_LENGTH - 3)).trim() + "...";
    }
}
