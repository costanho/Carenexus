package com.carenexus.api.common.event;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Published by the medication module when a scheduled dose passed its window without being taken.
 * {@code consecutiveMisses} counts this dose and the missed doses directly before it.
 */
public record MedicationDoseMissedEvent(
        Integer scheduleId, Integer prescriptionId, String patientSystemId, LocalDate scheduleDate,
        LocalTime scheduledTime, int consecutiveMisses) {
}
