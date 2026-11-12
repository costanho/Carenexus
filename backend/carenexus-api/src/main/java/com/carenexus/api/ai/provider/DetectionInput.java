package com.carenexus.api.ai.provider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The de-identified snapshot a {@link RiskDetectionProvider} assesses. It holds no name, system id, date of birth,
 * contact detail or free-text note: only an age band, the recorded sex, coded history and the recent measurements.
 * The same object is stored in {@code disease_detection.input_data} and is never returned by the API.
 *
 * @param assessedOn        clinic calendar date of the screening
 * @param ageBand           for example {@code "40-49"} or {@code "80+"}; null when the date of birth is unknown
 * @param sex               MALE, FEMALE or OTHER as recorded; null when unknown
 * @param chronicConditions chronic conditions recorded on the patient profile (at most 20)
 * @param symptomLogs       non-deleted symptom logs of the last {@link #SYMPTOM_WINDOW_DAYS} clinic days, newest
 *                          first, at most {@link #MAX_SYMPTOM_LOGS}
 * @param latestReadings    the latest reading per metric type recorded in the last {@link #READING_WINDOW_DAYS}
 *                          clinic days (BMI is never included)
 * @param activeMedications names of the patient's active, unexpired prescriptions
 */
public record DetectionInput(LocalDate assessedOn, String ageBand, String sex, List<String> chronicConditions,
                             List<SymptomEntry> symptomLogs, List<Reading> latestReadings,
                             List<String> activeMedications) {

    /** Symptom logs of this many clinic days (today included) are screened. */
    public static final int SYMPTOM_WINDOW_DAYS = 14;
    /** Readings of this many clinic days (today included) are screened. */
    public static final int READING_WINDOW_DAYS = 7;
    /** At most this many symptom logs are screened. */
    public static final int MAX_SYMPTOM_LOGS = 10;

    public DetectionInput {
        chronicConditions = chronicConditions == null ? List.of() : List.copyOf(chronicConditions);
        symptomLogs = symptomLogs == null ? List.of() : List.copyOf(symptomLogs);
        latestReadings = latestReadings == null ? List.of() : List.copyOf(latestReadings);
        activeMedications = activeMedications == null ? List.of() : List.copyOf(activeMedications);
    }

    /** True when there is at least one recent symptom log or reading to screen. */
    public boolean hasData() {
        return !symptomLogs.isEmpty() || !latestReadings.isEmpty();
    }

    /** The latest reading of {@code type} (a {@code health_metrics.type} value), or null. */
    public Reading reading(String type) {
        for (Reading reading : latestReadings) {
            if (reading.type().equals(type)) {
                return reading;
            }
        }
        return null;
    }

    /**
     * One symptom log.
     *
     * @param loggedOn clinic date of the log
     * @param severity MILD, MODERATE, SEVERE or CRITICAL
     * @param symptoms the symptom strings as logged
     */
    public record SymptomEntry(LocalDate loggedOn, String severity, List<String> symptoms) {

        public SymptomEntry {
            symptoms = symptoms == null ? List.of() : List.copyOf(symptoms);
        }
    }

    /**
     * One reading.
     *
     * @param type       {@code health_metrics.type}, for example SPO2
     * @param value      the measurement in {@code unit}
     * @param unit       the stored unit, for example {@code mmol/L}
     * @param recordedOn clinic date of the measurement
     */
    public record Reading(String type, BigDecimal value, String unit, LocalDate recordedOn) {
    }
}
