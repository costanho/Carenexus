package com.carenexus.api.scheduling;

import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.spi.BookingConstraint;
import com.carenexus.api.common.spi.BookingRequestContext;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.carenexus.api.scheduling.service.AvailabilityBookingConstraint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.carenexus.api.scheduling.AvailabilityFixtures.block;
import static com.carenexus.api.scheduling.AvailabilityFixtures.futureDay;
import static com.carenexus.api.scheduling.AvailabilityFixtures.insertTimeOff;
import static com.carenexus.api.scheduling.AvailabilityFixtures.template;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The booking constraint bean as AppointmentService calls it (the HTTP path through the appointment endpoints is
 * asserted by the integration-gate slices once both slices are merged): patient-side bookings outside the template
 * or inside time off are refused with DOCTOR_UNAVAILABLE; doctor and admin bookings are exempt.
 */
public class AvailabilityBookingConstraintTest extends CommittedIntegrationTest {

    private static final String OUTSIDE = "The doctor does not see patients at this time. Please choose another time.";
    private static final String AWAY = "The doctor is away at this time. Please choose another time.";

    @Autowired
    private AvailabilityBookingConstraint constraint;

    @Autowired
    private List<BookingConstraint> bookingConstraints;

    private BookingRequestContext booking(LocalDateTime start, int minutes, String role) {
        return new BookingRequestContext(doctor.getSystemId(), patient.getSystemId(), start, minutes, "IN_PERSON", role, null);
    }

    private void assertRefused(BookingRequestContext ctx, String message) {
        BadRequestException refusal = assertThrows(BadRequestException.class, () -> constraint.check(ctx));
        assertEquals(AvailabilityBookingConstraint.DOCTOR_UNAVAILABLE, refusal.getCode());
        assertEquals(message, refusal.getMessage());
    }

    private void saveTemplate(List<?> blocks) throws Exception {
        MvcResult result = doPut("/api/doctors/availability", doctor, template(30, blocks));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    @Test
    void isRegisteredAsABookingConstraint() {
        assertTrue(bookingConstraints.stream().anyMatch(c -> c instanceof AvailabilityBookingConstraint));
    }

    @Test
    void patientAndCaregiverBookings_mustLieInsideOneWorkingWindow() throws Exception {
        LocalDate monday = futureDay(DayOfWeek.MONDAY);
        saveTemplate(List.of(
                block(1, "08:00", "10:00"),
                block(1, "10:00", "13:00"),          // touches: one window 08:00-13:00
                block(1, "14:00", "18:00"),
                block(2, "09:00", "10:00"),
                block(3, "09:00", "12:00", false)));

        for (String role : List.of("PATIENT", "CAREGIVER", "patient")) {
            assertDoesNotThrow(() -> constraint.check(booking(monday.atTime(8, 0), 30, role)));
            assertDoesNotThrow(() -> constraint.check(booking(monday.atTime(9, 45), 30, role)), "spans touching blocks");
            assertDoesNotThrow(() -> constraint.check(booking(monday.atTime(12, 30), 30, role)), "ends exactly at 13:00");
            assertDoesNotThrow(() -> constraint.check(booking(monday.atTime(14, 15), 45, role)), "off the slot grid");
            assertRefused(booking(monday.atTime(12, 45), 30, role), OUTSIDE);
            assertRefused(booking(monday.atTime(13, 15), 30, role), OUTSIDE);
            assertRefused(booking(monday.atTime(12, 0), 150, role), OUTSIDE);
            assertRefused(booking(monday.atTime(7, 30), 30, role), OUTSIDE);
            assertRefused(booking(monday.plusDays(2).atTime(9, 0), 30, role), OUTSIDE);   // Wednesday: inactive block only
            assertRefused(booking(monday.plusDays(3).atTime(9, 0), 30, role), OUTSIDE);   // Thursday: no block
        }
    }

    @Test
    void doctorAndAdminBookings_areExempt() throws Exception {
        LocalDate monday = futureDay(DayOfWeek.MONDAY);
        saveTemplate(List.of(block(1, "09:00", "10:00")));
        insertTimeOff(jdbc, doctor.getSystemId(), monday.atStartOfDay(), monday.plusDays(1).atStartOfDay(), null);

        for (String role : List.of("DOCTOR", "ADMIN", "admin")) {
            assertDoesNotThrow(() -> constraint.check(booking(monday.atTime(16, 0), 30, role)));
            assertDoesNotThrow(() -> constraint.check(booking(monday.atTime(9, 0), 30, role)));
        }
    }

    @Test
    void timeOff_refusesOverlappingPatientSideBookings_withOrWithoutTemplate() throws Exception {
        LocalDate tuesday = futureDay(DayOfWeek.TUESDAY);
        insertTimeOff(jdbc, doctor.getSystemId(), tuesday.atTime(10, 0), tuesday.atTime(12, 0), "Training");

        // No template: clinic hours are AppointmentService's business, only time off is checked here.
        assertDoesNotThrow(() -> constraint.check(booking(tuesday.atTime(9, 30), 30, "PATIENT")), "ends as time off starts");
        assertDoesNotThrow(() -> constraint.check(booking(tuesday.atTime(12, 0), 30, "PATIENT")), "starts as time off ends");
        assertDoesNotThrow(() -> constraint.check(booking(tuesday.atTime(19, 0), 30, "PATIENT")));
        assertRefused(booking(tuesday.atTime(9, 45), 30, "PATIENT"), AWAY);
        assertRefused(booking(tuesday.atTime(11, 30), 60, "CAREGIVER"), AWAY);

        // With a template the template is checked first, then time off.
        saveTemplate(List.of(block(2, "08:00", "13:00")));
        assertRefused(booking(tuesday.atTime(10, 30), 30, "PATIENT"), AWAY);
        assertRefused(booking(tuesday.atTime(14, 0), 30, "PATIENT"), OUTSIDE);
        assertDoesNotThrow(() -> constraint.check(booking(tuesday.atTime(8, 0), 30, "PATIENT")));

        // Another doctor's time off does not matter.
        BookingRequestContext otherDoctorBooking = new BookingRequestContext(otherDoctor.getSystemId(),
                otherPatient.getSystemId(), tuesday.atTime(10, 30), 30, "VIDEO", "PATIENT", 12);
        assertDoesNotThrow(() -> constraint.check(otherDoctorBooking));
    }

    @Test
    void emptyTemplate_returnsTheDoctorToClinicHours() throws Exception {
        LocalDate monday = futureDay(DayOfWeek.MONDAY);
        saveTemplate(List.of(block(1, "09:00", "10:00")));
        assertRefused(booking(monday.atTime(15, 0), 30, "PATIENT"), OUTSIDE);

        saveTemplate(List.of());
        assertDoesNotThrow(() -> constraint.check(booking(monday.atTime(15, 0), 30, "PATIENT")));
    }
}
