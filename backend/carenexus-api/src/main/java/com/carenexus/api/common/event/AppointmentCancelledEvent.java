package com.carenexus.api.common.event;

import java.time.LocalDateTime;

/**
 * Published by AppointmentService after an appointment was cancelled.
 * {@code bySystem} is true when a job or a cascade (for example a deactivated doctor) cancelled it; then
 * {@code actorSystemId} / {@code actorRole} may be null. {@code reason} is the short reason entered by the actor, never clinical detail.
 */
public record AppointmentCancelledEvent(
        Integer appointmentId, String patientSystemId, String doctorSystemId, String actorSystemId,
        String actorRole, LocalDateTime scheduledAt, String reason, boolean bySystem) {
}
