package com.carenexus.api.releasegates.crossflow;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.core.appointments.AppointmentFixtures.booking;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.allNotifications;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.clinicToday;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.count;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.notificationsWithKey;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cross-slice gate: the doctor's weekly hours and time off (be-availability / be-doctor-schedule) are enforced on the
 * booking endpoint of be-appointments-core through the BookingConstraint seam, over HTTP. Patient-side bookings
 * outside the hours or inside time off are refused with DOCTOR_UNAVAILABLE and leave no trace; the doctor's own
 * bookings are exempt. A caregiver booking inside the hours notifies the patient and the doctor exactly once
 * (be-clinical-notifications N1) and not the caregiver.
 */
class BookingAvailabilityFlowGateTest extends CommittedIntegrationTest {

    private static final String APPOINTMENTS = "/api/appointments";
    private static final String AVAILABILITY = "/api/doctors/availability";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String OUTSIDE = "The doctor does not see patients at this time. Please choose another time.";
    private static final String AWAY = "The doctor is away at this time. Please choose another time.";

    private LocalDate monday;

    @BeforeEach
    void doctorWorksMondayMornings() throws Exception {
        monday = clinicToday().plusDays(7).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("dayOfWeek", 1);
        block.put("startTime", "09:00");
        block.put("endTime", "12:00");
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("slotMinutes", 30);
        template.put("blocks", List.of(block));
        MvcResult saved = doPut(AVAILABILITY, doctor, template);
        assertEquals(200, saved.getResponse().getStatus(), saved.getResponse().getContentAsString());

        MvcResult away = doPost(AVAILABILITY + "/time-off", doctor, Map.of(
                "startsAt", monday.atTime(10, 0).format(ISO),
                "endsAt", monday.atTime(11, 0).format(ISO),
                "reason", "Teaching"));
        assertEquals(201, away.getResponse().getStatus(), away.getResponse().getContentAsString());
    }

    private MvcResult book(User as, LocalDateTime at) throws Exception {
        String patientId = "PATIENT".equals(as.getRole()) ? null : patient.getSystemId();
        return doPost(APPOINTMENTS, as, booking(patientId, doctor.getSystemId(), at, "IN_PERSON"));
    }

    private int booked(User as, LocalDateTime at) throws Exception {
        MvcResult result = book(as, at);
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result).path("appointmentId").asInt();
    }

    private void assertRefused(MvcResult result, String expectedMessage) throws Exception {
        assertEquals(400, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("DOCTOR_UNAVAILABLE", json(result).path("code").asText());
        assertEquals(expectedMessage, message(result));
    }

    private int appointments() {
        return count(jdbc, "SELECT COUNT(*) FROM appointments");
    }

    @Test
    void patientBookings_outsideTheWeeklyHoursOrInsideTimeOff_areRefused_andLeaveNoTrace() throws Exception {
        assertRefused(book(patient, monday.atTime(14, 0)), OUTSIDE);           // clinic open, doctor not working
        assertRefused(book(patient, monday.atTime(11, 45)), OUTSIDE);          // runs past the end of the block
        assertRefused(book(patient, monday.plusDays(1).atTime(9, 0)), OUTSIDE); // Tuesday: no hours
        assertRefused(book(patient, monday.atTime(10, 0)), AWAY);
        assertRefused(book(patient, monday.atTime(9, 45)), AWAY);              // overlaps the start of the time off

        assertEquals(0, appointments());
        assertEquals(0, allNotifications(jdbc, doctor.getSystemId()));
        assertEquals(0, allNotifications(jdbc, patient.getSystemId()));

        // Inside the hours and outside the time off the same patient books normally
        int id = booked(patient, monday.atTime(9, 0));
        assertEquals(1, notificationsWithKey(jdbc, doctor.getSystemId(), "APPT_BOOKED:" + id));
        assertEquals(0, allNotifications(jdbc, patient.getSystemId()), "the patient booked: not notified");
    }

    @Test
    void caregiverBookings_followTheSameHours_andNotifyThePatientAndTheDoctorOnce() throws Exception {
        grantAccess(caregiver, patient, a -> {
            a.setCanViewAppointments(true);
            a.setCanScheduleAppointments(true);
        });

        assertRefused(book(caregiver, monday.atTime(15, 0)), OUTSIDE);
        assertRefused(book(caregiver, monday.atTime(10, 30)), AWAY);
        assertEquals(0, appointments());

        int id = booked(caregiver, monday.atTime(11, 0));
        String key = "APPT_BOOKED:" + id;
        assertEquals(1, notificationsWithKey(jdbc, patient.getSystemId(), key));
        assertEquals(1, notificationsWithKey(jdbc, doctor.getSystemId(), key));
        assertEquals(1, allNotifications(jdbc, patient.getSystemId()));
        assertEquals(1, allNotifications(jdbc, doctor.getSystemId()));
        assertEquals(0, allNotifications(jdbc, caregiver.getSystemId()), "the booking caregiver is the actor");
    }

    @Test
    void theDoctorsOwnBookings_areExemptFromHoursAndTimeOff() throws Exception {
        int afternoon = booked(doctor, monday.atTime(14, 0));
        int duringTimeOff = booked(doctor, monday.atTime(10, 0));
        int tuesday = booked(doctor, monday.plusDays(1).atTime(16, 30));

        assertEquals(3, appointments());
        for (int id : List.of(afternoon, duringTimeOff, tuesday)) {
            assertEquals("SCHEDULED", CrossFlowFixtures.appointmentStatus(jdbc, id));
            assertEquals(1, notificationsWithKey(jdbc, patient.getSystemId(), "APPT_BOOKED:" + id));
            assertEquals(0, notificationsWithKey(jdbc, doctor.getSystemId(), "APPT_BOOKED:" + id));
        }

        // Another doctor's hours are not this doctor's: an unrelated patient may book DOC-00002 at any clinic time
        MvcResult other = doPost(APPOINTMENTS, otherPatient,
                booking(null, otherDoctor.getSystemId(), monday.atTime(14, 0), "IN_PERSON"));
        assertEquals(201, other.getResponse().getStatus(), other.getResponse().getContentAsString());
    }
}
