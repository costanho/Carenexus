package com.carenexus.api.scheduling;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.carenexus.api.scheduling.AvailabilityFixtures.block;
import static com.carenexus.api.scheduling.AvailabilityFixtures.clinicNow;
import static com.carenexus.api.scheduling.AvailabilityFixtures.clinicToday;
import static com.carenexus.api.scheduling.AvailabilityFixtures.futureDay;
import static com.carenexus.api.scheduling.AvailabilityFixtures.insertAppointment;
import static com.carenexus.api.scheduling.AvailabilityFixtures.insertTimeOff;
import static com.carenexus.api.scheduling.AvailabilityFixtures.template;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * {@code GET /api/appointments/doctor/{doctorSystemId}/slots} with committed data: template and clinic-hours
 * fallback, taken times, time off, partial and past days, the booking horizon, excludeAppointmentId rules, every
 * caller role and the parameter errors.
 */
public class DoctorSlotsApiTest extends CommittedIntegrationTest {

    private static final String AVAILABILITY_URL = "/api/doctors/availability";

    private static String slotsUrl(String doctorSystemId, LocalDate date, String extra) {
        return "/api/appointments/doctor/" + doctorSystemId + "/slots?date=" + date + (extra == null ? "" : extra);
    }

    private JsonNode slots(User as, String doctorSystemId, LocalDate date, String extra) throws Exception {
        MvcResult result = doGet(slotsUrl(doctorSystemId, date, extra), as);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private static List<String> times(JsonNode body) {
        List<String> times = new ArrayList<>();
        body.path("slots").forEach(s -> times.add(s.path("start").asText().substring(11, 16)));
        return times;
    }

    private static List<String> availableTimes(JsonNode body) {
        List<String> times = new ArrayList<>();
        body.path("slots").forEach(s -> {
            if (s.path("available").asBoolean()) {
                times.add(s.path("start").asText().substring(11, 16));
            }
        });
        return times;
    }

    private void saveTemplate(User forDoctor, int slotMinutes, List<?> blocks) throws Exception {
        MvcResult result = doPut(AVAILABILITY_URL, forDoctor, template(slotMinutes, blocks));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    @Test
    void doctorWithoutTemplate_getsClinicHourSlots_forEveryRole() throws Exception {
        LocalDate day = futureDay(DayOfWeek.WEDNESDAY);

        for (User caller : List.of(patient, caregiver, otherDoctor, admin, doctor)) {
            JsonNode body = slots(caller, doctor.getSystemId(), day, null);
            assertEquals("DOC-00001", body.path("doctorSystemId").asText());
            assertEquals(day.toString(), body.path("date").asText());
            assertEquals("Africa/Harare", body.path("timeZone").asText());
            assertEquals("CLINIC_HOURS", body.path("source").asText());
            assertEquals(30, body.path("slotMinutes").asInt());
            assertEquals(30, body.path("durationMinutes").asInt());
            assertEquals(20, body.path("slots").size(), "08:00 to 17:30 in 30-minute steps");
            assertEquals(day + "T08:00:00", body.path("slots").get(0).path("start").asText());
            assertEquals(day + "T17:30:00", body.path("slots").get(19).path("start").asText());
            assertEquals(20, availableTimes(body).size());
        }

        JsonNode hour = slots(patient, doctor.getSystemId(), day, "&durationMinutes=60");
        assertEquals(60, hour.path("durationMinutes").asInt());
        assertEquals(30, hour.path("slotMinutes").asInt());
        assertEquals(19, hour.path("slots").size(), "the last 60-minute start is 17:00");
    }

    @Test
    void templateSlots_mergeTouchingBlocks_andMarkTakenTimesAndTimeOff() throws Exception {
        LocalDate thursday = futureDay(DayOfWeek.THURSDAY);
        saveTemplate(doctor, 30, List.of(
                block(4, "09:00", "12:00"),
                block(4, "12:00", "13:00"),
                block(4, "14:00", "15:00", false),
                block(5, "09:00", "10:00", false)));
        insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(), thursday.atTime(8, 45), 30, "SCHEDULED");
        insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(), thursday.atTime(10, 0), 30, "RESCHEDULED");
        insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(), thursday.atTime(11, 0), 30, "CANCELLED");
        insertAppointment(jdbc, otherPatient.getSystemId(), otherDoctor.getSystemId(), thursday.atTime(11, 30), 30, "SCHEDULED");
        insertTimeOff(jdbc, doctor.getSystemId(), thursday.atTime(12, 30), thursday.plusDays(1).atStartOfDay(), "Clinic meeting");

        JsonNode body = slots(patient, doctor.getSystemId(), thursday, null);
        assertEquals("TEMPLATE", body.path("source").asText());
        assertEquals(List.of("09:00", "09:30", "10:00", "10:30", "11:00", "11:30", "12:00", "12:30"), times(body));
        assertEquals(List.of("09:30", "10:30", "11:00", "11:30", "12:00"), availableTimes(body));
        assertFalse(body.toString().contains("Clinic meeting"), "the time-off reason is never disclosed");

        JsonNode hour = slots(caregiver, doctor.getSystemId(), thursday, "&durationMinutes=60");
        assertEquals(List.of("09:00", "09:30", "10:00", "10:30", "11:00", "11:30", "12:00"), times(hour));
        assertEquals(List.of("10:30", "11:00", "11:30"), availableTimes(hour));

        // A weekday whose blocks are all inactive (Friday) or that has no block (Monday) has no slots at all.
        JsonNode friday = slots(patient, doctor.getSystemId(), thursday.plusDays(1), null);
        assertEquals("TEMPLATE", friday.path("source").asText());
        assertEquals(0, friday.path("slots").size());
        assertEquals(0, slots(patient, doctor.getSystemId(), futureDay(DayOfWeek.MONDAY), null).path("slots").size());

        // Removing the time off frees 12:30 at once.
        jdbc.update("DELETE FROM doctor_time_off");
        assertTrue(availableTimes(slots(patient, doctor.getSystemId(), thursday, null)).contains("12:30"));
    }

    @Test
    void pastDays_today_andDaysBeyondTheHorizon() throws Exception {
        JsonNode yesterday = slots(patient, doctor.getSystemId(), clinicToday().minusDays(1), null);
        assertEquals(20, yesterday.path("slots").size(), "past days are not an error");
        assertTrue(availableTimes(yesterday).isEmpty());

        JsonNode farAway = slots(patient, doctor.getSystemId(), clinicToday().plusDays(400), null);
        assertEquals(20, farAway.path("slots").size());
        assertTrue(availableTimes(farAway).isEmpty(), "beyond max-advance-days (365) nothing is bookable");

        LocalDate today = clinicToday();
        LocalDateTime before = clinicNow();
        JsonNode todays = slots(patient, doctor.getSystemId(), today, null);
        LocalDateTime after = clinicNow();
        for (JsonNode slot : todays.path("slots")) {
            LocalDateTime start = LocalDateTime.parse(slot.path("start").asText());
            if (!start.isAfter(before)) {
                assertFalse(slot.path("available").asBoolean(), "a start that has passed is not available: " + start);
            } else if (start.isAfter(after)) {
                assertTrue(slot.path("available").asBoolean(), "a later start today is available: " + start);
            }
        }
    }

    @Test
    void excludeAppointmentId_isHonouredOnlyForCallersWhoMayManageThatAppointment() throws Exception {
        LocalDate day = futureDay(DayOfWeek.TUESDAY);
        int appointmentId = insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(), day.atTime(10, 0), 30, "SCHEDULED");
        int otherDoctorsAppointment = insertAppointment(jdbc, otherPatient.getSystemId(), otherDoctor.getSystemId(),
                day.atTime(10, 0), 30, "SCHEDULED");
        String exclude = "&excludeAppointmentId=" + appointmentId;

        assertFalse(availableTimes(slots(patient, doctor.getSystemId(), day, null)).contains("10:00"));
        assertTrue(availableTimes(slots(patient, doctor.getSystemId(), day, exclude)).contains("10:00"), "own appointment");
        assertTrue(availableTimes(slots(doctor, doctor.getSystemId(), day, exclude)).contains("10:00"), "related doctor");
        assertTrue(availableTimes(slots(admin, doctor.getSystemId(), day, exclude)).contains("10:00"), "admin");

        assertFalse(availableTimes(slots(otherPatient, doctor.getSystemId(), day, exclude)).contains("10:00"));
        assertFalse(availableTimes(slots(otherDoctor, doctor.getSystemId(), day, exclude)).contains("10:00"));
        assertFalse(availableTimes(slots(caregiver, doctor.getSystemId(), day, exclude)).contains("10:00"), "no grant");
        assertFalse(availableTimes(slots(patient, doctor.getSystemId(), day, "&excludeAppointmentId=999999")).contains("10:00"));
        assertFalse(availableTimes(slots(admin, doctor.getSystemId(), day, "&excludeAppointmentId=" + otherDoctorsAppointment))
                .contains("10:00"), "an appointment of another doctor is ignored, even for an admin");

        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        assertFalse(availableTimes(slots(caregiver, doctor.getSystemId(), day, exclude)).contains("10:00"),
                "a grant without scheduling permission");
        grantAccess(caregiver, patient, a -> a.setCanScheduleAppointments(true));
        assertTrue(availableTimes(slots(caregiver, doctor.getSystemId(), day, exclude)).contains("10:00"),
                "a live grant with scheduling permission");
        grantAccess(caregiver, patient, a -> {
            a.setCanScheduleAppointments(true);
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
        });
        assertFalse(availableTimes(slots(caregiver, doctor.getSystemId(), day, exclude)).contains("10:00"), "expired grant");
        grantAccess(caregiver, patient, a -> {
            a.setCanScheduleAppointments(true);
            a.setIsActive(false);
        });
        assertFalse(availableTimes(slots(caregiver, doctor.getSystemId(), day, exclude)).contains("10:00"), "revoked grant");
    }

    @Test
    void parameterErrors_andUnknownDoctor() throws Exception {
        LocalDate day = futureDay(DayOfWeek.WEDNESDAY);

        MvcResult missingDate = doGet("/api/appointments/doctor/DOC-00001/slots", patient);
        assertEquals(400, missingDate.getResponse().getStatus());
        assertEquals("Missing required parameter 'date'", message(missingDate));

        MvcResult badDate = doGet("/api/appointments/doctor/DOC-00001/slots?date=12-11-2026", patient);
        assertEquals(400, badDate.getResponse().getStatus());
        assertEquals("Invalid value for parameter 'date'", message(badDate));

        assertEquals("Invalid value for parameter 'durationMinutes'",
                message(doGet(slotsUrl("DOC-00001", day, "&durationMinutes=half"), patient)));
        assertEquals("Invalid value for parameter 'excludeAppointmentId'",
                message(doGet(slotsUrl("DOC-00001", day, "&excludeAppointmentId=x"), patient)));

        for (String duration : List.of("7", "0", "245")) {
            MvcResult result = doGet(slotsUrl("DOC-00001", day, "&durationMinutes=" + duration), patient);
            assertEquals(400, result.getResponse().getStatus());
            assertEquals("durationMinutes: must be between 5 and 240 in steps of 5", message(result));
            assertEquals("VALIDATION_FAILED", json(result).path("code").asText());
        }

        MvcResult unknown = doGet(slotsUrl("DOC-99999", day, null), patient);
        assertEquals(404, unknown.getResponse().getStatus());
        assertEquals("Doctor not found", message(unknown));
        assertEquals(404, doGet(slotsUrl(patient.getSystemId(), day, null), patient).getResponse().getStatus());

        assertEquals(401, mockMvc.perform(get(slotsUrl("DOC-00001", day, null))).andReturn().getResponse().getStatus());
    }
}
