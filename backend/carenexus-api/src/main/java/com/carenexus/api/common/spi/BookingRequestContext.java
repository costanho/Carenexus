package com.carenexus.api.common.spi;

import java.time.LocalDateTime;

/**
 * The appointment being created or moved, as seen by a {@link BookingConstraint}.
 *
 * @param doctorSystemId  doctor being booked
 * @param patientSystemId patient the appointment is for
 * @param start           start time, clinic wall-clock ({@code carenexus.scheduling.time-zone})
 * @param durationMinutes length in minutes
 * @param type            appointment type (IN_PERSON, VIDEO, PHONE)
 * @param actorRole       role of the caller (PATIENT, DOCTOR, CAREGIVER, ADMIN)
 * @param appointmentId   id of the appointment being rescheduled or edited; null for a new booking
 */
public record BookingRequestContext(String doctorSystemId, String patientSystemId, LocalDateTime start,
                                    int durationMinutes, String type, String actorRole, Integer appointmentId) {
}
