package com.carenexus.api.common.event;

/**
 * Published by AppointmentService when an appointment reaches an outcome status (COMPLETED, NO_SHOW).
 * Booking, rescheduling and cancellation have their own events.
 */
public record AppointmentStatusChangedEvent(
        Integer appointmentId, String patientSystemId, String doctorSystemId, String actorSystemId,
        String previousStatus, String status) {
}
