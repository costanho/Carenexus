package com.carenexus.api.ai.generator.service;

import com.carenexus.api.ai.alert.model.AiAlertPriority;
import com.carenexus.api.ai.generator.job.FollowUpDueJob;
import com.carenexus.api.ai.generator.repository.AiGeneratorJdbc.Baseline;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure rules of the generators (api/be-ai-generators.md 5.1 to 5.3): the vitals severity mapping, the anomaly
 * rule, the pinned dedupe keys and the stored texts.
 */
class AlertRulesTest {

    private static final BigDecimal THRESHOLD = new BigDecimal("25");

    private static Baseline baseline(int readings, String median) {
        return new Baseline(readings, median == null ? null : new BigDecimal(median));
    }

    @Test
    void vitalsSeverityGivesThePriority() {
        assertEquals(AiAlertPriority.HIGH, VitalSignAlertGenerator.priorityFor("ABNORMAL"));
        assertEquals(AiAlertPriority.CRITICAL, VitalSignAlertGenerator.priorityFor("CRITICAL"));
    }

    @Test
    void anomalyNeedsADeviationStrictlyAboveTheThreshold() {
        assertTrue(AnomalyGenerator.isAnomalous(new BigDecimal("85"), baseline(5, "64"), THRESHOLD), "32.8 %");
        assertTrue(AnomalyGenerator.isAnomalous(new BigDecimal("40"), baseline(5, "64"), THRESHOLD), "37.5 % below");
        assertFalse(AnomalyGenerator.isAnomalous(new BigDecimal("80"), baseline(5, "64"), THRESHOLD), "exactly 25 %");
        assertFalse(AnomalyGenerator.isAnomalous(new BigDecimal("48"), baseline(5, "64"), THRESHOLD), "exactly 25 % below");
        assertFalse(AnomalyGenerator.isAnomalous(new BigDecimal("70"), baseline(5, "64"), THRESHOLD), "9.4 %");
        assertTrue(AnomalyGenerator.isAnomalous(new BigDecimal("70"), baseline(5, "64"), new BigDecimal("9")),
                "the threshold is configurable");
    }

    @Test
    void anomalyNeedsEnoughHistoryAndANonZeroMedian() {
        assertFalse(AnomalyGenerator.isAnomalous(new BigDecimal("200"), baseline(4, "64"), THRESHOLD));
        assertFalse(AnomalyGenerator.isAnomalous(new BigDecimal("200"), baseline(0, null), THRESHOLD));
        assertFalse(AnomalyGenerator.isAnomalous(new BigDecimal("200"), baseline(9, "0"), THRESHOLD));
        assertFalse(AnomalyGenerator.isAnomalous(new BigDecimal("200"), null, THRESHOLD));
    }

    @Test
    void dedupeKeysArePinned() {
        assertEquals("CRITICAL_RESULT:LAB:87", CriticalResultAlertGenerator.dedupeKey("LAB", 87));
        assertEquals("CRITICAL_RESULT:IMAGING:5", CriticalResultAlertGenerator.dedupeKey("IMAGING", 5));
        assertEquals("CRITICAL_VITALS:301", VitalSignAlertGenerator.dedupeKey(301));
        assertEquals("ANOMALY_DETECTED:PAT-00001:HEART_RATE:2026-03-04", AnomalyGenerator.dedupeKey("PAT-00001",
                "HEART_RATE", LocalDateTime.of(2026, 3, 4, 23, 59)));
        assertEquals("MISSED_MEDICATION:58:2026-03-04",
                MissedMedicationAlertGenerator.dedupeKey(58, LocalDate.of(2026, 3, 4)));
        assertEquals("MEDICATION_INTERACTION:12:aspirin 75 mg",
                MedicationInteractionAlertGenerator.dedupeKey(12, "  Aspirin 75 mg "));
        assertEquals("FOLLOW_UP_DUE:9", FollowUpDueJob.dedupeKey(9));
    }

    @Test
    void theInteractionKeyCutsLongNamesTo100Characters() {
        String key = MedicationInteractionAlertGenerator.dedupeKey(1234567, "A".repeat(300));
        assertEquals("MEDICATION_INTERACTION:1234567:" + "a".repeat(100), key);
        assertTrue(key.length() <= 160);
    }

    @Test
    void textsNameTheMetricButNeverAValue() {
        assertEquals("A systolic pressure reading for this patient is in the critical range.",
                AlertTexts.criticalVitalsMessage("BP_SYSTOLIC"));
        assertEquals("A oxygen saturation reading for this patient is outside the reference range.",
                AlertTexts.abnormalVitalsMessage("SPO2"));
        assertEquals("A heart rate reading differs markedly from this patient's usual readings over the last 30 days.",
                AlertTexts.anomalyMessage("HEART_RATE"));
        assertEquals("blood glucose", AlertTexts.metricLabel("GLUCOSE"));
        assertEquals("respiratory rate", AlertTexts.metricLabel("RESPIRATORY_RATE"));
        assertEquals("diastolic pressure", AlertTexts.metricLabel("BP_DIASTOLIC"));
        assertEquals("vital sign", AlertTexts.metricLabel("UNKNOWN"));
    }

    @Test
    void patientFacingTextsNameNoMedicationAndUseTheContractDateFormat() {
        assertEquals("3 scheduled doses in a row were not marked as taken.", AlertTexts.missedMedicationMessage(3));
        assertEquals("The follow-up recommended for 3 Nov 2026 has passed and no later appointment is booked.",
                AlertTexts.followUpMessage(LocalDate.of(2026, 11, 3)));
        assertEquals("Warfarin 5 mg may interact with Aspirin, which this patient is currently prescribed.",
                AlertTexts.interactionMessage("Warfarin 5 mg", "Aspirin"));
    }
}
