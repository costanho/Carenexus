package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.ratelimit.RateLimitProperties;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.carenexus.api.core.repository.DoctorScheduleQueryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.core.appointments.AppointmentFixtures.clinicDay;
import static com.carenexus.api.core.appointments.AppointmentFixtures.insert;
import static com.carenexus.api.core.appointments.AppointmentFixtures.wallClock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Release gates of the doctor schedule and doctor appointment views (API contract be-doctor-schedule): the legacy
 * array roots stay arrays, every route is self-scoped, paging is clamped, the date window is inclusive / exclusive,
 * the needs-outcome list follows the system setting, and the manual reminder is owner-only, rate limited and sends
 * exactly one notification per recipient and clock hour.
 */
class DoctorScheduleReleaseGateTest extends CommittedIntegrationTest {

    private static final String SCHEDULE = "/api/doctors/schedule";
    private static final String APPOINTMENTS = "/api/doctors/appointments";
    private static final String NEEDS_OUTCOME_SETTING = "appointments.needs-outcome-after-minutes";
    private static final ZoneId CLINIC = ZoneId.of("Africa/Harare");

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private RateLimitGuard rateLimitGuard;

    @Autowired
    private DoctorScheduleQueryRepository scheduleRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // ==================== SHAPES ====================

    @Test
    void legacyRootsStillReturnArrays() throws Exception {
        Appointment past = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                clinicNow().minusDays(2).withNano(0), "COMPLETED", "IN_PERSON");
        insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), clinicDay(3, 10, 0),
                "SCHEDULED", "VIDEO");

        MvcResult schedule = doGet(SCHEDULE, doctor);
        assertEquals(200, status(schedule), text(schedule));
        JsonNode rows = json(schedule);
        assertTrue(rows.isArray(), "GET /api/doctors/schedule stays a bare array");
        assertEquals(2, rows.size());
        assertEquals("APPOINTMENT", rows.get(0).path("type").asText());
        assertTrue(rows.get(0).has("needsOutcome"));
        assertTrue(rows.get(0).has("reminderSentAt"));
        assertTrue(rows.get(0).has("facilityName"));
        assertTrue(rows.get(0).has("contactPhone"));

        MvcResult appointments = doGet(APPOINTMENTS, doctor);
        assertEquals(200, status(appointments), text(appointments));
        JsonNode list = json(appointments);
        assertTrue(list.isArray(), "GET /api/doctors/appointments stays a bare array");
        assertEquals(2, list.size());
        // AppointmentResponse rows, newest first.
        assertTrue(list.get(0).has("doctorName"));
        assertEquals(past.getAppointmentId().intValue(), list.get(1).path("appointmentId").asInt());
    }

    @Test
    void legacyArraysAreCappedAt200Appointments() throws Exception {
        LocalDateTime base = clinicNow().minusDays(400).withHour(9).withMinute(0).withSecond(0).withNano(0);
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < 205; i++) {
            batch.add(new Object[]{patient.getSystemId(), doctor.getSystemId(), base.plusDays(i)});
        }
        jdbc.batchUpdate("INSERT INTO appointments (patient_system_id, doctor_system_id, scheduled_at, duration_minutes, "
                + "type, status, reminder_sent, created_at, updated_at) "
                + "VALUES (?, ?, ?, 30, 'IN_PERSON', 'COMPLETED', FALSE, now(), now())", batch);

        JsonNode schedule = json(doGet(SCHEDULE, doctor));
        assertEquals(200, schedule.size());
        assertEquals(wallClock(base.plusDays(204)), schedule.get(0).path("scheduledAt").asText());

        JsonNode appointments = json(doGet(APPOINTMENTS, doctor));
        assertEquals(200, appointments.size());

        JsonNode page = json(doGet(APPOINTMENTS + "/search?size=1", doctor));
        assertEquals(205, page.path("totalElements").asInt());
    }

    // ==================== SCOPE ====================

    @Test
    void patientsAndCaregiversAreRefusedOnEveryRoute() throws Exception {
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        Appointment future = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                clinicDay(3, 10, 0), "SCHEDULED", "IN_PERSON");
        List<String> reads = List.of(SCHEDULE, SCHEDULE + "/window?from=2026-01-01T00:00:00&to=2027-01-01T00:00:00",
                SCHEDULE + "/dashboard", APPOINTMENTS, APPOINTMENTS + "/search", APPOINTMENTS + "/needs-outcome",
                APPOINTMENTS + "/stats");
        for (User caller : List.of(patient, caregiver)) {
            for (String url : reads) {
                MvcResult result = doGet(url, caller);
                assertEquals(403, status(result), caller.getRole() + " " + url);
                assertEquals("You don't have permission to access this resource", message(result));
            }
            String id = String.valueOf(future.getAppointmentId());
            for (String action : List.of("mark-completed", "mark-no-show", "mark-cancelled")) {
                assertEquals(403, status(doPatch(APPOINTMENTS + "/" + id + "/" + action, caller, null)));
            }
            assertEquals(403, status(doPost(APPOINTMENTS + "/" + id + "/send-reminder", caller, null)));
        }
        assertEquals("SCHEDULED", appointmentRepository.findById(future.getAppointmentId()).orElseThrow().getStatus());
    }

    @Test
    void anotherDoctorNeverSeesTheRowsAndAnAdminHasNoSchedule() throws Exception {
        insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), clinicDay(3, 10, 0),
                "SCHEDULED", "IN_PERSON");
        insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                clinicNow().minusHours(3).withNano(0), "SCHEDULED", "IN_PERSON");

        assertEquals(0, json(doGet(SCHEDULE, otherDoctor)).size());
        assertEquals(0, json(doGet(APPOINTMENTS, otherDoctor)).size());
        assertEquals(0, json(doGet(APPOINTMENTS + "/search", otherDoctor)).path("totalElements").asInt());
        assertEquals(0, json(doGet(APPOINTMENTS + "/needs-outcome", otherDoctor)).path("totalElements").asInt());
        assertEquals(0, json(doGet(SCHEDULE + "/window?from=2000-01-01T00:00:00&to=2100-01-01T00:00:00",
                otherDoctor)).path("totalElements").asInt());
        JsonNode otherStats = json(doGet(APPOINTMENTS + "/stats", otherDoctor));
        assertEquals(0, otherStats.path("totalAppointments").asInt());
        JsonNode otherDashboard = json(doGet(SCHEDULE + "/dashboard", otherDoctor));
        assertEquals(0, otherDashboard.path("totalAppointments").asInt());
        assertEquals(0, otherDashboard.path("needsOutcomeCount").asInt());

        // The owner sees both.
        assertEquals(2, json(doGet(APPOINTMENTS + "/search", doctor)).path("totalElements").asInt());

        // An administrator passes the self check but has no doctor profile and no appointments of their own.
        for (String url : List.of(SCHEDULE, SCHEDULE + "/dashboard",
                SCHEDULE + "/window?from=2000-01-01T00:00:00&to=2100-01-01T00:00:00")) {
            MvcResult result = doGet(url, admin);
            assertEquals(404, status(result), url);
            assertEquals("Doctor profile not found", message(result));
        }
        assertEquals(0, json(doGet(APPOINTMENTS, admin)).size());
        JsonNode adminSearch = json(doGet(APPOINTMENTS + "/search", admin));
        assertEquals(0, adminSearch.path("totalElements").asInt());
        assertEquals(0, adminSearch.path("totalPages").asInt());
        assertEquals(0, json(doGet(APPOINTMENTS + "/needs-outcome", admin)).path("totalElements").asInt());
        JsonNode adminStats = json(doGet(APPOINTMENTS + "/stats", admin));
        assertEquals(0, adminStats.path("totalAppointments").asInt());
        assertEquals(0.0, adminStats.path("completionRate").asDouble());
    }

    @Test
    void removedTwinsAnswer404() throws Exception {
        Appointment future = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                clinicDay(3, 10, 0), "SCHEDULED", "IN_PERSON");
        for (String url : List.of(SCHEDULE + "/" + doctor.getSystemId(), SCHEDULE + "/today", SCHEDULE + "/upcoming",
                SCHEDULE + "/past", SCHEDULE + "/date-range?startDate=2026-01-01&endDate=2026-12-31",
                SCHEDULE + "/status/SCHEDULED", SCHEDULE + "/patient/" + patient.getSystemId(),
                APPOINTMENTS + "/" + doctor.getSystemId(), APPOINTMENTS + "/scheduled", APPOINTMENTS + "/completed",
                APPOINTMENTS + "/status/SCHEDULED", APPOINTMENTS + "/patient/" + patient.getSystemId(),
                APPOINTMENTS + "/" + doctor.getSystemId() + "/stats")) {
            assertEquals(404, status(doGet(url, doctor)), url);
        }
        MvcResult statusPatch = doPatch(APPOINTMENTS + "/" + future.getAppointmentId() + "/status?status=COMPLETED",
                doctor, null);
        assertEquals(404, status(statusPatch));
    }

    // ==================== PAGING AND WINDOW ====================

    @Test
    void pagingIsClampedAndNegativePageIsZero() throws Exception {
        for (int i = 0; i < 3; i++) {
            insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), clinicDay(3 + i, 10, 0),
                    "SCHEDULED", "IN_PERSON");
        }
        JsonNode big = json(doGet(APPOINTMENTS + "/search?size=500", doctor));
        assertEquals(100, big.path("size").asInt());
        assertEquals(3, big.path("content").size());

        JsonNode negative = json(doGet(APPOINTMENTS + "/search?page=-4&size=0", doctor));
        assertEquals(0, negative.path("page").asInt());
        assertEquals(1, negative.path("size").asInt());
        assertEquals(3, negative.path("totalPages").asInt());
        assertEquals(3, negative.path("totalElements").asInt());

        JsonNode defaults = json(doGet(APPOINTMENTS + "/needs-outcome", doctor));
        assertEquals(20, defaults.path("size").asInt());
        assertEquals(0, defaults.path("page").asInt());

        MvcResult badPage = doGet(APPOINTMENTS + "/search?page=first", doctor);
        assertEquals(400, status(badPage));
        assertEquals("Invalid value for parameter 'page'", message(badPage));

        JsonNode window = json(doGet(SCHEDULE + "/window?from=2000-01-01T00:00:00&to=2100-01-01T00:00:00&size=1000",
                doctor));
        assertEquals(100, window.path("size").asInt());
    }

    @Test
    void windowIsFromInclusiveToExclusive() throws Exception {
        LocalDateTime start = clinicDay(5, 9, 0);
        Appointment atFrom = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), start,
                "SCHEDULED", "IN_PERSON");
        Appointment inside = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                start.plusHours(1), "CANCELLED", "IN_PERSON");
        insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), start.plusHours(2),
                "SCHEDULED", "IN_PERSON");
        insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), start.minusMinutes(5),
                "SCHEDULED", "IN_PERSON");

        String url = SCHEDULE + "/window?from=" + wallClock(start) + "&to=" + wallClock(start.plusHours(2));
        JsonNode page = json(doGet(url, doctor));
        assertEquals(2, page.path("totalElements").asInt());
        JsonNode content = page.path("content");
        assertEquals(atFrom.getAppointmentId().intValue(), content.get(0).path("appointmentId").asInt());
        assertEquals(inside.getAppointmentId().intValue(), content.get(1).path("appointmentId").asInt());
        assertEquals("APT_" + atFrom.getAppointmentId(), content.get(0).path("entryId").asText());
        assertEquals("APPOINTMENT", content.get(0).path("type").asText());
        assertEquals(wallClock(start), content.get(0).path("scheduledAt").asText());

        JsonNode desc = json(doGet(url + "&order=DESC", doctor)).path("content");
        assertEquals(inside.getAppointmentId().intValue(), desc.get(0).path("appointmentId").asInt());

        MvcResult same = doGet(SCHEDULE + "/window?from=" + wallClock(start) + "&to=" + wallClock(start), doctor);
        assertEquals(400, status(same));
        assertEquals("to must be after from", message(same));

        MvcResult missing = doGet(SCHEDULE + "/window?to=" + wallClock(start), doctor);
        assertEquals(400, status(missing));
        assertEquals("Missing required parameter 'from'", message(missing));

        MvcResult badOrder = doGet(url + "&order=sideways", doctor);
        assertEquals(400, status(badOrder));
        assertEquals("Invalid value for parameter 'order'", message(badOrder));

        MvcResult badDate = doGet(SCHEDULE + "/window?from=tomorrow&to=" + wallClock(start), doctor);
        assertEquals(400, status(badDate));
        assertEquals("Invalid value for parameter 'from'", message(badDate));

        // The same window on /search.
        assertEquals(2, json(doGet(APPOINTMENTS + "/search?from=" + wallClock(start) + "&to="
                + wallClock(start.plusHours(2)), doctor)).path("totalElements").asInt());
    }

    @Test
    void searchFiltersByStatusAndPatient() throws Exception {
        insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), clinicNow().minusDays(3).withNano(0),
                "COMPLETED", "IN_PERSON");
        insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), clinicNow().minusDays(2).withNano(0),
                "CANCELLED", "IN_PERSON");
        insert(appointmentRepository, otherPatient.getSystemId(), doctor.getSystemId(), clinicDay(2, 10, 0),
                "SCHEDULED", "IN_PERSON");
        insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), clinicDay(3, 10, 0),
                "RESCHEDULED", "IN_PERSON");

        assertEquals(2, json(doGet(APPOINTMENTS + "/search?status=completed, CANCELLED", doctor))
                .path("totalElements").asInt());
        assertEquals(2, json(doGet(APPOINTMENTS + "/search?status=SCHEDULED&status=RESCHEDULED", doctor))
                .path("totalElements").asInt());
        assertEquals(3, json(doGet(APPOINTMENTS + "/search?patientSystemId=" + patient.getSystemId(), doctor))
                .path("totalElements").asInt());
        assertEquals(0, json(doGet(APPOINTMENTS + "/search?patientSystemId=PAT-99999", doctor))
                .path("totalElements").asInt());

        JsonNode upcoming = json(doGet(APPOINTMENTS + "/search?status=SCHEDULED,RESCHEDULED&order=asc&from="
                + wallClock(clinicNow().withNano(0)), doctor)).path("content");
        assertEquals(2, upcoming.size());
        assertEquals(otherPatient.getSystemId(), upcoming.get(0).path("patientSystemId").asText());

        MvcResult badStatus = doGet(APPOINTMENTS + "/search?status=DONE", doctor);
        assertEquals(400, status(badStatus));
        assertEquals("Invalid appointment status. Must be SCHEDULED, COMPLETED, CANCELLED, NO_SHOW, or RESCHEDULED",
                message(badStatus));

        MvcResult badPatient = doGet(APPOINTMENTS + "/search?patientSystemId=thomas", doctor);
        assertEquals(400, status(badPatient));
        assertEquals("VALIDATION_FAILED", json(badPatient).path("code").asText());
        assertEquals("patientSystemId: must be a valid ID such as PAT-00001", message(badPatient));
    }

    // ==================== NEEDS OUTCOME ====================

    @Test
    void needsOutcomeListsEndedActiveAppointmentsAndFollowsTheSetting() throws Exception {
        LocalDateTime now = clinicNow().withSecond(0).withNano(0);
        // Ended 40 minutes ago (start 70 minutes ago, 30 minutes long).
        Appointment ended = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                now.minusMinutes(70), "SCHEDULED", "IN_PERSON");
        // Ended 10 hours ago, rescheduled.
        Appointment older = insert(appointmentRepository, otherPatient.getSystemId(), doctor.getSystemId(),
                now.minusHours(11), "RESCHEDULED", "PHONE");
        // Ended 10 minutes ago: not yet.
        insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), now.minusMinutes(40),
                "SCHEDULED", "IN_PERSON");
        // Closed or future or another doctor's: never.
        insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), now.minusDays(1), "COMPLETED", "IN_PERSON");
        insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), now.minusDays(2), "NO_SHOW", "IN_PERSON");
        insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), clinicDay(2, 10, 0), "SCHEDULED", "IN_PERSON");
        insert(appointmentRepository, otherPatient.getSystemId(), otherDoctor.getSystemId(), now.minusHours(5),
                "SCHEDULED", "IN_PERSON");

        JsonNode page = json(doGet(APPOINTMENTS + "/needs-outcome", doctor));
        assertEquals(2, page.path("totalElements").asInt());
        JsonNode content = page.path("content");
        assertEquals(ended.getAppointmentId().intValue(), content.get(0).path("appointmentId").asInt());
        assertEquals(older.getAppointmentId().intValue(), content.get(1).path("appointmentId").asInt());
        for (JsonNode row : content) {
            assertTrue(row.path("needsOutcome").asBoolean());
            assertEquals("APPOINTMENT", row.path("type").asText());
        }
        assertEquals("RESCHEDULED", content.get(1).path("appointmentStatus").asText());

        JsonNode filtered = json(doGet(APPOINTMENTS + "/needs-outcome?patientSystemId=" + otherPatient.getSystemId(),
                doctor));
        assertEquals(1, filtered.path("totalElements").asInt());
        MvcResult badPatient = doGet(APPOINTMENTS + "/needs-outcome?patientSystemId=", doctor);
        assertEquals(400, status(badPatient));
        assertEquals("VALIDATION_FAILED", json(badPatient).path("code").asText());

        assertEquals(2, json(doGet(SCHEDULE + "/dashboard", doctor)).path("needsOutcomeCount").asInt());
        assertEquals(2, json(doGet(APPOINTMENTS + "/stats", doctor)).path("needsOutcomeCount").asInt());
        JsonNode flagged = json(doGet(APPOINTMENTS + "/search?status=SCHEDULED,RESCHEDULED&size=100", doctor))
                .path("content");
        assertEquals(2, countFlagged(flagged));

        // The administrator raises the threshold to 60 minutes: the appointment that ended 40 minutes ago leaves
        // the list, the flag and both counts on the next request.
        setNeedsOutcomeMinutes("60");
        JsonNode raised = json(doGet(APPOINTMENTS + "/needs-outcome", doctor));
        assertEquals(1, raised.path("totalElements").asInt());
        assertEquals(older.getAppointmentId().intValue(), raised.path("content").get(0).path("appointmentId").asInt());
        assertEquals(1, json(doGet(SCHEDULE + "/dashboard", doctor)).path("needsOutcomeCount").asInt());
        assertEquals(1, json(doGet(APPOINTMENTS + "/stats", doctor)).path("needsOutcomeCount").asInt());
        assertEquals(1, countFlagged(json(doGet(APPOINTMENTS + "/search?status=SCHEDULED,RESCHEDULED&size=100", doctor))
                .path("content")));

        // Lowering it to the minimum (5) also lists the one that ended 10 minutes ago.
        setNeedsOutcomeMinutes("5");
        assertEquals(3, json(doGet(APPOINTMENTS + "/needs-outcome", doctor)).path("totalElements").asInt());

        // Recording an outcome removes the row.
        MvcResult completed = doPatch(APPOINTMENTS + "/" + ended.getAppointmentId() + "/mark-completed", doctor, null);
        assertEquals(200, status(completed), text(completed));
        assertEquals(2, json(doGet(APPOINTMENTS + "/needs-outcome", doctor)).path("totalElements").asInt());
    }

    // ==================== MANUAL REMINDER ====================

    @Test
    void onlyTheAppointmentsDoctorMaySendAReminderForAnActiveFutureAppointment() throws Exception {
        Appointment future = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                clinicDay(2, 10, 0), "SCHEDULED", "IN_PERSON");
        String url = APPOINTMENTS + "/" + future.getAppointmentId() + "/send-reminder";

        // Another doctor, even one related to the patient, is refused and the denial is audited.
        careTeamRepository.saveAndFlush(com.carenexus.api.core.model.CareTeam.builder()
                .patientSystemId(patient.getSystemId()).providerSystemId(otherDoctor.getSystemId())
                .role("SPECIALIST").assignedAt(LocalDateTime.now()).isActive(true).build());
        MvcResult other = doPost(url, otherDoctor, null);
        assertEquals(403, status(other));
        assertEquals("Only the appointment's doctor can send a reminder", message(other));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCESS_DENIED' "
                + "AND resource_type = 'APPOINTMENT' AND resource_id = ? AND system_id = ?",
                future.getAppointmentId(), otherDoctor.getSystemId()));

        MvcResult byAdmin = doPost(url, admin, null);
        assertEquals(403, status(byAdmin));
        assertEquals("Only the appointment's doctor can send a reminder", message(byAdmin));

        MvcResult unknown = doPost(APPOINTMENTS + "/999999/send-reminder", doctor, null);
        assertEquals(404, status(unknown));
        assertEquals("Appointment not found", message(unknown));

        MvcResult badId = doPost(APPOINTMENTS + "/abc/send-reminder", doctor, null);
        assertEquals(400, status(badId));
        assertEquals("Invalid value for parameter 'appointmentId'", message(badId));

        Appointment completed = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                clinicDay(4, 10, 0), "CANCELLED", "IN_PERSON");
        MvcResult closed = doPost(APPOINTMENTS + "/" + completed.getAppointmentId() + "/send-reminder", doctor, null);
        assertEquals(409, status(closed));
        assertEquals("A reminder can only be sent for a scheduled appointment", message(closed));

        Appointment started = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                clinicNow().minusMinutes(10).withNano(0), "SCHEDULED", "IN_PERSON");
        MvcResult late = doPost(APPOINTMENTS + "/" + started.getAppointmentId() + "/send-reminder", doctor, null);
        assertEquals(409, status(late));
        assertEquals("A reminder can only be sent before the appointment starts", message(late));

        assertEquals(0, count("SELECT COUNT(*) FROM notifications WHERE category = 'APPOINTMENT_REMINDER'"));
        assertEquals(0, count("SELECT COUNT(*) FROM appointments WHERE manual_reminder_sent_at IS NOT NULL"));
    }

    @Test
    void oneSendNotifiesEachRecipientOnceAndARepeatInTheSameHourSendsNothing() throws Exception {
        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        grantAccess(otherCaregiver, patient, a -> {
            a.setCanViewAppointments(true);
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
        });
        Appointment future = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                clinicDay(2, 10, 30), "SCHEDULED", "IN_PERSON");
        String url = APPOINTMENTS + "/" + future.getAppointmentId() + "/send-reminder";
        awayFromAnHourBoundary();

        MvcResult first = doPost(url, doctor, null);
        assertEquals(200, status(first), text(first));
        JsonNode sent = json(first);
        assertEquals(future.getAppointmentId().intValue(), sent.path("appointmentId").asInt());
        assertFalse(sent.path("alreadySent").asBoolean());
        String sentAt = sent.path("reminderSentAt").asText();
        assertEquals(19, sentAt.length(), "clinic wall-clock to the second: " + sentAt);

        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE system_id = ?", patient.getSystemId()));
        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE system_id = ?", caregiver.getSystemId()));
        assertEquals(0, count("SELECT COUNT(*) FROM notifications WHERE system_id = ?", otherCaregiver.getSystemId()));
        assertEquals(0, count("SELECT COUNT(*) FROM notifications WHERE system_id = ?", doctor.getSystemId()));
        assertEquals(2, count("SELECT COUNT(*) FROM notifications"));

        Map<String, Object> row = jdbc.queryForMap("SELECT type, category, title, message, target, resource_type, "
                + "resource_id, patient_system_id, actor_system_id, dedupe_key, action_url FROM notifications "
                + "WHERE system_id = ?", patient.getSystemId());
        assertEquals("APPOINTMENT", row.get("type"));
        assertEquals("APPOINTMENT_REMINDER", row.get("category"));
        assertEquals("Appointment reminder", row.get("title"));
        String expectedStart = clinicDay(2, 10, 30).format(DateTimeFormatter.ofPattern("EEE d MMM yyyy",
                java.util.Locale.ENGLISH));
        assertTrue(String.valueOf(row.get("message")).startsWith("Reminder from "), String.valueOf(row.get("message")));
        assertTrue(String.valueOf(row.get("message")).contains(expectedStart + " at 10:30 (clinic time)."),
                String.valueOf(row.get("message")));
        assertEquals("APPOINTMENTS", row.get("target"));
        assertEquals("APPOINTMENT", row.get("resource_type"));
        assertEquals(future.getAppointmentId(), ((Number) row.get("resource_id")).intValue());
        assertEquals(patient.getSystemId(), row.get("patient_system_id"));
        assertEquals(doctor.getSystemId(), row.get("actor_system_id"));
        assertEquals("APPT_REMINDER_MANUAL:" + future.getAppointmentId() + ":" + sentAt.substring(0, 13),
                row.get("dedupe_key"));
        assertEquals("/dashboard/patient?section=my-appointments&id=" + future.getAppointmentId(), row.get("action_url"));
        assertEquals("/dashboard/proxy?section=appointments&patient=" + patient.getSystemId() + "&id="
                        + future.getAppointmentId(),
                jdbc.queryForObject("SELECT action_url FROM notifications WHERE system_id = ?", String.class,
                        caregiver.getSystemId()));

        // Audited once for the patient; the automatic reminder flag is untouched.
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'UPDATE' AND resource_type = 'APPOINTMENT' "
                + "AND resource_id = ? AND patient_system_id = ?", future.getAppointmentId(), patient.getSystemId()));
        assertFalse(appointmentRepository.findById(future.getAppointmentId()).orElseThrow().getReminderSent());

        MvcResult repeat = doPost(url, doctor, null);
        assertEquals(200, status(repeat), text(repeat));
        assertTrue(json(repeat).path("alreadySent").asBoolean());
        assertEquals(sentAt, json(repeat).path("reminderSentAt").asText());
        assertEquals(2, count("SELECT COUNT(*) FROM notifications"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'UPDATE' AND resource_type = 'APPOINTMENT' "
                + "AND resource_id = ?", future.getAppointmentId()));

        // The row of every list shows the manual reminder time.
        JsonNode rows = json(doGet(APPOINTMENTS + "/search", doctor)).path("content");
        assertEquals(sentAt, rows.get(0).path("reminderSentAt").asText());
        assertFalse(rows.get(0).path("reminderSent").asBoolean());

        // A reminder sent in an earlier clock hour no longer blocks a new one.
        jdbc.update("UPDATE appointments SET manual_reminder_sent_at = manual_reminder_sent_at - INTERVAL '2 hours' "
                + "WHERE appointment_id = ?", future.getAppointmentId());
        MvcResult nextHour = doPost(url, doctor, null);
        assertEquals(200, status(nextHour));
        assertFalse(json(nextHour).path("alreadySent").asBoolean());
    }

    @Test
    void caregiverRecipients_needAnActiveGrantThatShowsAppointments() throws Exception {
        User fullAccess = createAndSaveUser(8, "CGV-00003", "full.cg@example.com", "CAREGIVER");
        User revoked = createAndSaveUser(9, "CGV-00004", "revoked.cg@example.com", "CAREGIVER");
        grantAccess(fullAccess, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        grantAccess(caregiver, patient, a -> {
            a.setCanViewMedications(true);
            a.setCanViewLabResults(true);
            a.setCanScheduleAppointments(true); // may book, but not see appointments
        });
        grantAccess(revoked, patient, a -> {
            a.setCanViewAppointments(true);
            a.setIsActive(false);
            a.setRevokedAt(LocalDateTime.now().minusHours(1));
        });
        grantAccess(otherCaregiver, otherPatient, a -> a.setAccessLevel("FULL_ACCESS"));
        Appointment future = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                clinicDay(3, 9, 0), "SCHEDULED", "IN_PERSON");
        awayFromAnHourBoundary();

        MvcResult sent = doPost(APPOINTMENTS + "/" + future.getAppointmentId() + "/send-reminder", doctor, null);
        assertEquals(200, status(sent), text(sent));

        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE system_id = ?", patient.getSystemId()));
        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE system_id = ?", fullAccess.getSystemId()),
                "FULL_ACCESS includes appointments");
        assertEquals(0, count("SELECT COUNT(*) FROM notifications WHERE system_id = ?", caregiver.getSystemId()),
                "other flags do not include appointments");
        assertEquals(0, count("SELECT COUNT(*) FROM notifications WHERE system_id = ?", revoked.getSystemId()),
                "a revoked grant gets nothing");
        assertEquals(0, count("SELECT COUNT(*) FROM notifications WHERE system_id = ?", otherCaregiver.getSystemId()));
        assertEquals(2, count("SELECT COUNT(*) FROM notifications"));
    }

    @Test
    void theClaimItselfRefusesAnAppointmentThatIsNoLongerActiveOrHasStarted() {
        LocalDateTime now = clinicNow().withNano(0);
        LocalDateTime hourStart = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        Appointment cancelled = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                clinicDay(2, 9, 0), "CANCELLED", "IN_PERSON");
        Appointment completed = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                clinicDay(2, 10, 0), "COMPLETED", "IN_PERSON");
        Appointment started = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                now.minusMinutes(5).withSecond(0), "SCHEDULED", "IN_PERSON");
        Appointment rescheduled = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                clinicDay(2, 11, 0), "RESCHEDULED", "IN_PERSON");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // The service checked status and start on its own read; a change committed by another request between
        // that read and the claim must still stop the reminder, so the locked UPDATE re-checks both.
        for (Appointment refused : List.of(cancelled, completed, started)) {
            Integer claimed = tx.execute(s -> scheduleRepository.claimManualReminder(refused.getAppointmentId(), now,
                    hourStart));
            assertEquals(0, claimed.intValue(), refused.getStatus() + " at " + refused.getScheduledAt());
        }
        Integer won = tx.execute(s -> scheduleRepository.claimManualReminder(rescheduled.getAppointmentId(), now,
                hourStart));
        assertEquals(1, won.intValue());
        assertEquals(1, count("SELECT COUNT(*) FROM appointments WHERE manual_reminder_sent_at IS NOT NULL"));
    }

    @Test
    void theEleventhReminderInAnHourIsRateLimited() throws Exception {
        Appointment future = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                clinicDay(2, 10, 0), "SCHEDULED", "IN_PERSON");
        String url = APPOINTMENTS + "/" + future.getAppointmentId() + "/send-reminder";
        rateLimitGuard.reset("reminder-manual", doctor.getSystemId());
        rateLimitProperties.setEnabled(true);
        try {
            for (int i = 0; i < 10; i++) {
                MvcResult ok = doPost(url, doctor, null);
                assertEquals(200, status(ok), "call " + (i + 1) + ": " + text(ok));
            }
            MvcResult limited = doPost(url, doctor, null);
            assertEquals(429, status(limited), text(limited));
            assertEquals("RATE_LIMITED", json(limited).path("code").asText());
            assertNotNull(limited.getResponse().getHeader("Retry-After"));
            assertFalse(limited.getResponse().getHeader("Retry-After").isEmpty());
            assertTrue(message(limited).startsWith("Too many requests. Please try again in "), message(limited));
        } finally {
            rateLimitProperties.setEnabled(false);
            rateLimitGuard.reset("reminder-manual", doctor.getSystemId());
        }
        // However often the doctor pressed the button, each recipient got one reminder in this hour (at most two if
        // the calls crossed an hour boundary).
        int patientRows = count("SELECT COUNT(*) FROM notifications WHERE system_id = ?", patient.getSystemId());
        assertTrue(patientRows >= 1 && patientRows <= 2, "patient reminders: " + patientRows);
    }

    // ==================== HELPERS ====================

    private void setNeedsOutcomeMinutes(String value) throws Exception {
        MvcResult result = doPut("/api/admin/settings/" + NEEDS_OUTCOME_SETTING, admin, Map.of("value", value));
        assertEquals(200, status(result), text(result));
    }

    private static int countFlagged(JsonNode rows) {
        int flagged = 0;
        for (JsonNode row : rows) {
            if (row.path("needsOutcome").asBoolean()) {
                flagged++;
            }
        }
        return flagged;
    }

    /** Waits (at most a few seconds) when the clinic clock is about to cross into the next hour. */
    private static void awayFromAnHourBoundary() throws InterruptedException {
        LocalDateTime now = clinicNow();
        if (now.getMinute() == 59 && now.getSecond() >= 50) {
            Thread.sleep((61 - now.getSecond()) * 1000L);
        }
    }

    private static LocalDateTime clinicNow() {
        return LocalDateTime.now(CLINIC);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String text(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }
}
