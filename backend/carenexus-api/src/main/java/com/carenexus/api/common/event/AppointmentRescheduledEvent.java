package com.carenexus.api.common.event;

import java.time.LocalDateTime;

/**
 * Published by AppointmentService after an appointment was moved to another time (clinic wall-clock times).
 */
public record AppointmentRescheduledEvent(
        Integer appointmentId, String patientSystemId, String doctorSystemId, String actorSystemId,
        String actorRole, LocalDateTime previousScheduledAt, LocalDateTime scheduledAt) {
}
