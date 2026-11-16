package com.carenexus.api.common.event;

import java.time.LocalDateTime;

/**
 * Published by AppointmentService after a new appointment was saved.
 * {@code actorSystemId} / {@code actorRole}: who booked it (patient, caregiver on the patient's behalf, doctor, admin).
 * {@code scheduledAt} is clinic wall-clock time.
 */
public record AppointmentBookedEvent(
        Integer appointmentId, String patientSystemId, String doctorSystemId, String actorSystemId,
        String actorRole, String type, LocalDateTime scheduledAt, Integer durationMinutes) {
}
