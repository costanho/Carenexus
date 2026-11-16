package com.carenexus.api.ai.generator.service;

import com.carenexus.api.health.model.MetricType;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The stored text of every generated alert (api/be-ai-generators.md 5.2). Texts never contain a measured value, a
 * result value, a diagnosis or a patient name. MISSED_MEDICATION and FOLLOW_UP_DUE titles and messages are shown
 * verbatim to the patient and permitted caregivers; suggested actions are for clinicians only.
 */
public final class AlertTexts {

    public static final String CRITICAL_LAB_TITLE = "Critical lab result";
    public static final String CRITICAL_LAB_MESSAGE =
            "A lab result for this patient was flagged as critical and needs review.";
    public static final String CRITICAL_IMAGING_TITLE = "Critical imaging result";
    public static final String CRITICAL_IMAGING_MESSAGE =
            "An imaging result for this patient was flagged as critical and needs review.";
    public static final String CRITICAL_RESULT_ACTION = "Review the result and contact the patient.";

    public static final String CRITICAL_VITALS_TITLE = "Critical vital sign reading";
    public static final String CRITICAL_VITALS_ACTION = "Review the reading and contact the patient.";
    public static final String ABNORMAL_VITALS_TITLE = "Abnormal vital sign reading";
    public static final String REVIEW_READINGS_ACTION = "Review the patient's recent readings.";

    public static final String ANOMALY_TITLE = "Unusual reading";

    public static final String MISSED_MEDICATION_TITLE = "Missed medication doses";
    public static final String MISSED_MEDICATION_ACTION =
            "Check adherence with the patient and review the prescription.";

    public static final String INTERACTION_TITLE = "Possible medication interaction";
    public static final String INTERACTION_ACTION =
            "Review both prescriptions before the patient continues treatment.";

    public static final String FOLLOW_UP_TITLE = "Follow-up due";
    public static final String FOLLOW_UP_ACTION = "Contact the patient to book the follow-up appointment.";

    /** Label of an unknown metric type. */
    static final String GENERIC_METRIC_LABEL = "vital sign";

    private static final DateTimeFormatter FOLLOW_UP_DATE = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    private AlertTexts() {
    }

    /**
     * The metric label of api/be-health-tracking.md 2.1 in lower case except the abbreviation, for example
     * {@code systolic pressure}, {@code oxygen saturation}, {@code BMI}.
     */
    public static String metricLabel(String type) {
        return MetricType.parse(type)
                .map(t -> t == MetricType.BMI ? t.label() : t.label().toLowerCase(Locale.ROOT))
                .orElse(GENERIC_METRIC_LABEL);
    }

    public static String criticalVitalsMessage(String type) {
        return "A " + metricLabel(type) + " reading for this patient is in the critical range.";
    }

    public static String abnormalVitalsMessage(String type) {
        return "A " + metricLabel(type) + " reading for this patient is outside the reference range.";
    }

    public static String anomalyMessage(String type) {
        return "A " + metricLabel(type)
                + " reading differs markedly from this patient's usual readings over the last 30 days.";
    }

    public static String missedMedicationMessage(int consecutiveMisses) {
        return consecutiveMisses + " scheduled doses in a row were not marked as taken.";
    }

    public static String interactionMessage(String newMedicationName, String activeMedicationName) {
        return newMedicationName + " may interact with " + activeMedicationName
                + ", which this patient is currently prescribed.";
    }

    public static String followUpMessage(LocalDate followUpDate) {
        return "The follow-up recommended for " + FOLLOW_UP_DATE.format(followUpDate)
                + " has passed and no later appointment is booked.";
    }
}
