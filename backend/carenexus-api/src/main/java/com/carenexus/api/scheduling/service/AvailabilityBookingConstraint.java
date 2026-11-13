package com.carenexus.api.scheduling.service;

import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.spi.BookingConstraint;
import com.carenexus.api.common.spi.BookingRequestContext;
import com.carenexus.api.scheduling.repository.DoctorTimeOffRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Doctor availability rules for patient-side bookings, called by AppointmentService inside the booking transaction
 * (create, reschedule and edit) after its built-in checks.
 *
 * - DOCTOR and ADMIN bookings are exempt (a doctor may book a follow-up outside their hours or during time off).
 * - For every other actor (PATIENT, CAREGIVER):
 *   1. when the doctor has a template, the appointment must lie completely inside one working window of its day
 *      (active blocks, touching blocks merged, clipped to clinic hours); a doctor without a template is bounded by
 *      clinic hours, which AppointmentService already enforces;
 *   2. the appointment must not overlap any time-off range of the doctor.
 * Refusals are {@code 400} with code {@link #DOCTOR_UNAVAILABLE}. The appointment id, type and patient are ignored.
 */
@Component
@RequiredArgsConstructor
public class AvailabilityBookingConstraint implements BookingConstraint {

    /** Error code of a refusal; web treats it like a slot conflict (shows the message and reloads the slots). */
    public static final String DOCTOR_UNAVAILABLE = "DOCTOR_UNAVAILABLE";

    static final String OUTSIDE_HOURS_MESSAGE = "The doctor does not see patients at this time. Please choose another time.";
    static final String TIME_OFF_MESSAGE = "The doctor is away at this time. Please choose another time.";

    private final DoctorAvailabilityService availabilityService;
    private final DoctorTimeOffRepository timeOffRepository;

    @Override
    public void check(BookingRequestContext ctx) {
        if (ctx == null || ctx.doctorSystemId() == null || ctx.start() == null) {
            return;
        }
        String role = ctx.actorRole() == null ? "" : ctx.actorRole().trim().toUpperCase(Locale.ROOT);
        if (AuthorizationService.DOCTOR.equals(role) || AuthorizationService.ADMIN.equals(role)) {
            return;
        }
        LocalDateTime start = ctx.start().truncatedTo(ChronoUnit.MINUTES);
        int duration = Math.max(ctx.durationMinutes(), 1);
        LocalDateTime end = start.plusMinutes(duration);

        DoctorAvailabilityService.WorkingHours hours = availabilityService.workingHours(ctx.doctorSystemId(), start.toLocalDate());
        if (hours.fromTemplate() && !AvailabilityCalculator.fitsInsideOneWindow(hours.windows(), start, duration)) {
            throw new BadRequestException(DOCTOR_UNAVAILABLE, OUTSIDE_HOURS_MESSAGE);
        }
        if (timeOffRepository.countOverlapping(ctx.doctorSystemId(), start, end) > 0) {
            throw new BadRequestException(DOCTOR_UNAVAILABLE, TIME_OFF_MESSAGE);
        }
    }
}
