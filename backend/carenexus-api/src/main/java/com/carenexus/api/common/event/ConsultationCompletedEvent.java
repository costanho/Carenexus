package com.carenexus.api.common.event;

import java.time.LocalDate;

/**
 * Published by ConsultationService when a consultation is completed (its notes become visible to the patient).
 * {@code appointmentId} and {@code followUpDate} may be null.
 */
public record ConsultationCompletedEvent(
        Integer consultationId, Integer appointmentId, String patientSystemId, String doctorSystemId,
        boolean followUpRequired, LocalDate followUpDate) {
}
