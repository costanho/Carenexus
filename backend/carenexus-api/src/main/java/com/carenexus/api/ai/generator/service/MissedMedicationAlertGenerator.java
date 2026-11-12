package com.carenexus.api.ai.generator.service;

import com.carenexus.api.ai.alert.AiAlertCommand;
import com.carenexus.api.ai.alert.AiAlertService;
import com.carenexus.api.ai.alert.model.AiAlertPriority;
import com.carenexus.api.ai.alert.model.AiAlertSourceType;
import com.carenexus.api.ai.alert.model.AiAlertType;
import com.carenexus.api.ai.generator.config.AiGeneratorProperties;
import com.carenexus.api.common.event.MedicationDoseMissedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Row 4 of api/be-ai-generators.md 5.1: a missed dose that ends a run of at least
 * {@code carenexus.ai.missed-dose-threshold} (default 3) consecutive misses raises one MISSED_MEDICATION alert
 * (priority MEDIUM) for every doctor related to the patient. The alert's source is the prescription, not the dose.
 * <p>
 * Its title and message are also shown to the patient and permitted caregivers, so they name neither the
 * medication nor anything clinical. Dedupe key {@code MISSED_MEDICATION:<prescriptionId>:<scheduleDate>}: at most
 * one per prescription and day, whichever path (sweeper, manual mark, re-mark after undo) published the event.
 */
@Component
@RequiredArgsConstructor
public class MissedMedicationAlertGenerator {

    private final AiAlertService aiAlertService;
    private final AiGeneratorProperties properties;

    /** @return the new alert id; empty below the threshold or when the day's alert exists */
    public Optional<Integer> generate(MedicationDoseMissedEvent event) {
        if (event == null || event.consecutiveMisses() < properties.getMissedDoseThreshold()
                || event.prescriptionId() == null || event.patientSystemId() == null
                || event.scheduleDate() == null) {
            return Optional.empty();
        }
        return aiAlertService.raise(new AiAlertCommand(
                event.patientSystemId(),
                null,
                AiAlertType.MISSED_MEDICATION.name(),
                AiAlertPriority.MEDIUM.name(),
                AlertTexts.MISSED_MEDICATION_TITLE,
                AlertTexts.missedMedicationMessage(event.consecutiveMisses()),
                false,
                AlertTexts.MISSED_MEDICATION_ACTION,
                AiAlertSourceType.PRESCRIPTION.name(),
                event.prescriptionId(),
                null,
                dedupeKey(event.prescriptionId(), event.scheduleDate())));
    }

    public static String dedupeKey(Integer prescriptionId, LocalDate scheduleDate) {
        return AiAlertType.MISSED_MEDICATION.name() + ":" + prescriptionId + ":" + scheduleDate;
    }
}
