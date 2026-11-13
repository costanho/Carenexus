package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.event.AppointmentBookedEvent;
import com.carenexus.api.common.event.AppointmentCancelledEvent;
import com.carenexus.api.common.event.AppointmentRescheduledEvent;
import com.carenexus.api.common.event.AppointmentStatusChangedEvent;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.ratelimit.RateLimitProperties;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.CareTeam;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.carenexus.api.core.repository.ConsultationRepository;
import com.carenexus.api.core.service.AppointmentService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.carenexus.api.core.appointments.AppointmentFixtures.body;
import static com.carenexus.api.core.appointments.AppointmentFixtures.booking;
import static com.carenexus.api.core.appointments.AppointmentFixtures.clinicDay;
import static com.carenexus.api.core.appointments.AppointmentFixtures.consultation;
import static com.carenexus.api.core.appointments.AppointmentFixtures.insert;
import static com.carenexus.api.core.appointments.AppointmentFixtures.wallClock;
import static com.carenexus.api.core.appointments.AppointmentFixtures.withVideoUnavailable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Release gate of the appointment API (contract be-appointments-core). Requests commit for real, so the advisory
 * locks, the V13 slot indexes, the idempotency claims and the call-session listener behave as in production.
 */
@RecordApplicationEvents
class AppointmentReleaseGateTest extends CommittedIntegrationTest {

    private static final String BASE = "/api/appointments";

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private ConsultationRepository consultationRepository;

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private RateLimitGuard rateLimitGuard;

    @Autowired
    private ApplicationEvents events;

    // ==================== HELPERS ====================

    private int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private String text(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private int book(User as, Map<String, Object> request) throws Exception {
        MvcResult result = doPost(BASE, as, request);
        assertEquals(201, status(result), text(result));
        return json(result).path("appointmentId").asInt();
    }

    private String dbStatus(int appointmentId) {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE appointment_id = ?", String.class, appointmentId);
    }

    private long auditRows(String action, int appointmentId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type = 'APPOINTMENT' "
                + "AND action = ? AND resource_id = ? AND status = 'SUCCESS'", Long.class, action, appointmentId);
        return count == null ? 0 : count;
    }

    private void assertError(MvcResult result, int expectedStatus, String expectedMessage) throws Exception {
        assertEquals(expectedStatus, status(result), text(result));
        assertEquals(expectedMessage, message(result), text(result));
    }

    private void assertCode(MvcResult result, int expectedStatus, String code) throws Exception {
        assertEquals(expectedStatus, status(result), text(result));
        assertEquals(code, json(result).path("code").asText(), text(result));
    }

    /** Started appointments of one test start 10, 45, 80, ... minutes ago, so they never share a slot. */
    private int startedCount;

    private Appointment startedAppointment(User forPatient, User withDoctor, String type) {
        int minutesAgo = 10 + 35 * startedCount++;
        return insert(appointmentRepository, forPatient.getSystemId(), withDoctor.getSystemId(),
                LocalDateTime.now(com.carenexus.api.core.appointments.AppointmentFixtures.CLINIC_ZONE)
                        .withSecond(0).withNano(0).minusMinutes(minutesAgo),
                AppointmentService.SCHEDULED, type);
    }

    // ==================== BOOKING: WHO ====================

    @Test
    void patientBooksForSelfButNotForAnotherPatient() throws Exception {
        LocalDateTime at = clinicDay(2, 10, 0);
        Map<String, Object> request = booking(null, doctor.getSystemId(), at, "IN_PERSON");
        MvcResult created = doPost(BASE, patient, request);
        assertEquals(201, status(created), text(created));
        JsonNode appointment = json(created);
        assertEquals(patient.getSystemId(), appointment.path("patientSystemId").asText());
        assertEquals("PATIENT User1", appointment.path("patientName").asText());
        assertEquals("Dr. DOCTOR User2", appointment.path("doctorName").asText());
        assertEquals("SCHEDULED", appointment.path("status").asText());
        assertEquals(at, LocalDateTime.parse(appointment.path("scheduledAt").asText()));
        // Null fields are present as null, never omitted.
        for (String field : List.of("facilityId", "facilityName", "facilityAddress", "contactPhone", "notes",
                "videoConsultationLink", "callStatus", "callJoinRoute", "joinWindowOpensAt", "joinWindowClosesAt")) {
            assertTrue(appointment.has(field) && appointment.get(field).isNull(), field + " in " + appointment);
        }
        int id = appointment.path("appointmentId").asInt();
        assertEquals(1, auditRows("CREATE", id));
        List<AppointmentBookedEvent> booked = events.stream(AppointmentBookedEvent.class).toList();
        assertEquals(1, booked.size());
        assertEquals(patient.getSystemId(), booked.get(0).actorSystemId());
        assertEquals("PATIENT", booked.get(0).actorRole());
        assertEquals(id, booked.get(0).appointmentId());

        MvcResult forOther = doPost(BASE, patient, booking(otherPatient.getSystemId(), doctor.getSystemId(),
                clinicDay(2, 11, 0), "IN_PERSON"));
        assertError(forOther, 403, "You can only book appointments for yourself");
    }

    @Test
    void caregiverNeedsAnActiveGrantWithSchedulingRights() throws Exception {
        Map<String, Object> request = booking(patient.getSystemId(), doctor.getSystemId(), clinicDay(2, 10, 0), "IN_PERSON");

        assertError(doPost(BASE, caregiver, request), 403, "You don't have active proxy access to this patient");

        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        assertError(doPost(BASE, caregiver, request), 403,
                "Your proxy access for this patient does not allow managing appointments");

        grantAccess(caregiver, patient, a -> {
            a.setAccessLevel("VIEW_ONLY");
            a.setCanScheduleAppointments(true);
        });
        assertError(doPost(BASE, caregiver, request), 403,
                "Your proxy access for this patient does not allow managing appointments");

        grantAccess(caregiver, patient, a -> {
            a.setCanScheduleAppointments(true);
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
        });
        assertError(doPost(BASE, caregiver, request), 403, "You don't have active proxy access to this patient");

        Map<String, Object> withoutPatient = booking(null, doctor.getSystemId(), clinicDay(2, 10, 0), "IN_PERSON");
        assertError(doPost(BASE, caregiver, withoutPatient), 400, "Patient ID is required");

        grantAccess(caregiver, patient, a -> a.setCanScheduleAppointments(true));
        int id = book(caregiver, request);
        assertEquals("CAREGIVER", events.stream(AppointmentBookedEvent.class).toList().get(0).actorRole());

        // From the moment the grant is revoked, every call for that patient is refused; the booking stays.
        grantAccess(caregiver, patient, a -> {
            a.setCanScheduleAppointments(true);
            a.setIsActive(false);
        });
        assertError(doDelete(BASE + "/" + id, caregiver), 403, "You don't have active proxy access to this patient");
        assertEquals("SCHEDULED", dbStatus(id));
    }

    @Test
    void doctorBooksOnlyIntoOwnScheduleForRelatedPatients() throws Exception {
        Map<String, Object> intoOther = booking(patient.getSystemId(), otherDoctor.getSystemId(), slotInMinutes(60), "IN_PERSON");
        assertError(doPost(BASE, doctor, intoOther), 403, "Doctors can only book appointments in their own schedule");

        Map<String, Object> unrelated = booking(otherPatient.getSystemId(), null, slotInMinutes(60), "IN_PERSON");
        assertError(doPost(BASE, doctor, unrelated), 403, "You are not part of this patient's care team");

        // Doctors may book outside clinic hours and default to themselves.
        MvcResult created = doPost(BASE, doctor, booking(patient.getSystemId(), null, clinicDay(2, 20, 0), "IN_PERSON"));
        assertEquals(201, status(created), text(created));
        assertEquals(doctor.getSystemId(), json(created).path("doctorSystemId").asText());

        Map<String, Object> noPatient = booking(null, null, slotInMinutes(60), "IN_PERSON");
        assertError(doPost(BASE, doctor, noPatient), 400, "Patient ID is required");

        // Admin books for anyone.
        assertEquals(201, status(doPost(BASE, admin,
                booking(otherPatient.getSystemId(), doctor.getSystemId(), clinicDay(3, 9, 0), "IN_PERSON"))));
    }

    @Test
    void unauthenticatedCallsAre401WithTheEnvelope() throws Exception {
        for (MvcResult result : List.of(
                mockMvc.perform(get(BASE)).andReturn(),
                mockMvc.perform(get(BASE + "/booking-rules")).andReturn(),
                mockMvc.perform(post(BASE).contentType("application/json").content("{}")).andReturn())) {
            assertEquals(401, status(result), text(result));
            JsonNode error = json(result);
            assertEquals(401, error.path("status").asInt());
            assertEquals("Authentication required", error.path("message").asText());
            assertTrue(error.path("timestamp").asText().endsWith("Z"), error.toString());
            assertFalse(error.path("path").asText().isEmpty(), error.toString());
        }
    }

    // ==================== BOOKING: SLOT RULES ====================

    @Test
    void invalidBookingsAre400() throws Exception {
        Map<String, Object> past = booking(null, doctor.getSystemId(), clinicDay(-1, 10, 0), "IN_PERSON");
        assertError(doPost(BASE, patient, past), 400, "Appointment cannot be scheduled in the past");

        Map<String, Object> evening = booking(null, doctor.getSystemId(), clinicDay(2, 19, 0), "IN_PERSON");
        assertError(doPost(BASE, patient, evening), 400, "Appointments can only be booked between 08:00 and 18:00");

        Map<String, Object> endsLate = booking(null, doctor.getSystemId(), clinicDay(2, 17, 45), "IN_PERSON");
        assertError(doPost(BASE, patient, endsLate), 400, "Appointments can only be booked between 08:00 and 18:00");

        Map<String, Object> oddDuration = booking(null, doctor.getSystemId(), clinicDay(2, 10, 0), "IN_PERSON");
        oddDuration.put("durationMinutes", 7);
        assertError(doPost(BASE, patient, oddDuration), 400,
                "Appointment duration must be between 5 and 240 minutes, in steps of 5 minutes");

        Map<String, Object> tooLong = booking(null, doctor.getSystemId(), clinicDay(2, 10, 0), "IN_PERSON");
        tooLong.put("durationMinutes", 300);
        MvcResult validation = doPost(BASE, patient, tooLong);
        assertError(validation, 400, "durationMinutes: must be between 5 and 240");
        assertEquals("VALIDATION_FAILED", json(validation).path("code").asText());
        assertEquals("durationMinutes", json(validation).path("errors").get(0).path("field").asText());

        Map<String, Object> offGrid = booking(null, doctor.getSystemId(), clinicDay(2, 10, 3), "IN_PERSON");
        assertError(doPost(BASE, patient, offGrid), 400, "Appointments must start on a 5-minute boundary");

        Map<String, Object> badType = booking(null, doctor.getSystemId(), clinicDay(2, 10, 0), "HOLOGRAM");
        assertError(doPost(BASE, patient, badType), 400, "Invalid appointment type. Must be VIDEO, IN_PERSON, or PHONE");

        Map<String, Object> badId = booking("patient-1", doctor.getSystemId(), clinicDay(2, 10, 0), "IN_PERSON");
        MvcResult badIdResult = doPost(BASE, patient, badId);
        assertError(badIdResult, 400, "patientSystemId: must be a valid ID such as PAT-00001");
        assertEquals("VALIDATION_FAILED", json(badIdResult).path("code").asText());

        Map<String, Object> noTime = booking(null, doctor.getSystemId(), null, "IN_PERSON");
        assertError(doPost(BASE, patient, noTime), 400, "Scheduled date/time is required");

        Map<String, Object> self = booking(null, patient.getSystemId(), clinicDay(2, 10, 0), "IN_PERSON");
        MvcResult selfResult = doPost(BASE, patient, self);
        assertEquals(400, status(selfResult), text(selfResult));

        Map<String, Object> unknownDoctor = booking(null, "DOC-99999", clinicDay(2, 10, 0), "IN_PERSON");
        assertError(doPost(BASE, patient, unknownDoctor), 404, "Doctor not found");

        jdbc.update("UPDATE doctors SET is_active = FALSE WHERE system_id = ?", otherDoctor.getSystemId());
        Map<String, Object> inactive = booking(null, otherDoctor.getSystemId(), clinicDay(2, 10, 0), "IN_PERSON");
        assertError(doPost(BASE, patient, inactive), 400, "This doctor is not accepting appointments at the moment");

        Map<String, Object> unknownFacility = booking(null, doctor.getSystemId(), clinicDay(2, 10, 0), "IN_PERSON");
        unknownFacility.put("facilityId", 9999);
        assertError(doPost(BASE, patient, unknownFacility), 404, "Facility not found");

        MvcResult malformed = mockMvc.perform(post(BASE).header("Authorization", tokenFor(patient))
                .contentType("application/json").content("{\"scheduledAt\": \"tomorrow\"}")).andReturn();
        assertError(malformed, 400, "Malformed request body");

        assertEquals(0L, appointmentRepository.count());
    }

    @Test
    void facilityNameAndAddressComeFromTheFacility() throws Exception {
        Integer active = jdbc.queryForObject("INSERT INTO facilities (name, address, is_active) "
                + "VALUES ('CareNexus Central Clinic', '12 Samora Machel Avenue, Harare', TRUE) RETURNING facility_id",
                Integer.class);
        Integer inactive = jdbc.queryForObject("INSERT INTO facilities (name, address, is_active) "
                + "VALUES ('Closed Clinic', NULL, FALSE) RETURNING facility_id", Integer.class);

        Map<String, Object> request = booking(null, doctor.getSystemId(), clinicDay(2, 10, 0), "IN_PERSON");
        request.put("facilityId", active);
        MvcResult created = doPost(BASE, patient, request);
        assertEquals(201, status(created), text(created));
        assertEquals("CareNexus Central Clinic", json(created).path("facilityName").asText());
        assertEquals("12 Samora Machel Avenue, Harare", json(created).path("facilityAddress").asText());

        Map<String, Object> closed = booking(null, doctor.getSystemId(), clinicDay(2, 11, 0), "IN_PERSON");
        closed.put("facilityId", inactive);
        assertError(doPost(BASE, patient, closed), 404, "Facility not found");

        // A facility deactivated after booking still shows on the appointment.
        jdbc.update("UPDATE facilities SET is_active = FALSE WHERE facility_id = ?", active);
        int id = json(created).path("appointmentId").asInt();
        assertEquals("CareNexus Central Clinic", json(doGet(BASE + "/" + id, patient)).path("facilityName").asText());
    }

    @Test
    void overlappingSlotsAre409AndBackToBackIsAllowed() throws Exception {
        book(patient, booking(null, doctor.getSystemId(), clinicDay(2, 10, 0), "IN_PERSON"));

        MvcResult taken = doPost(BASE, otherPatient, booking(null, doctor.getSystemId(), clinicDay(2, 10, 15), "IN_PERSON"));
        assertError(taken, 409, "This time slot is no longer available. Please choose another time.");
        assertTrue(json(taken).path("code").isMissingNode(), text(taken));

        MvcResult busy = doPost(BASE, patient, booking(null, otherDoctor.getSystemId(), clinicDay(2, 10, 15), "IN_PERSON"));
        assertError(busy, 409, "The patient already has another appointment at this time");

        book(otherPatient, booking(null, doctor.getSystemId(), clinicDay(2, 10, 30), "IN_PERSON"));
    }

    @Test
    void twoParallelBookingsOfOneSlotGiveExactlyOne201() throws Exception {
        LocalDateTime at = clinicDay(3, 9, 0);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (User who : List.of(patient, otherPatient)) {
                Callable<Integer> task = () -> {
                    start.await(5, TimeUnit.SECONDS);
                    return status(doPost(BASE, who, booking(null, doctor.getSystemId(), at, "IN_PERSON")));
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(201, 409), statuses);
        } finally {
            pool.shutdownNow();
        }
        Long active = jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE doctor_system_id = ? "
                + "AND status IN ('SCHEDULED','RESCHEDULED')", Long.class, doctor.getSystemId());
        assertEquals(1L, active);
    }

    @Test
    void idempotentReplayReturnsTheSameAppointment() throws Exception {
        String key = "book-" + clientId().substring(0, 20);
        Map<String, Object> request = booking(null, doctor.getSystemId(), clinicDay(2, 10, 0), "IN_PERSON");

        MvcResult first = doPostWithHeaders(BASE, patient, request, Map.of("Idempotency-Key", key));
        assertEquals(201, status(first), text(first));
        MvcResult replay = doPostWithHeaders(BASE, patient, request, Map.of("Idempotency-Key", key));
        assertEquals(200, status(replay), text(replay));
        assertEquals(json(first).path("appointmentId").asInt(), json(replay).path("appointmentId").asInt());
        assertEquals(1L, appointmentRepository.count());
        assertEquals(1, events.stream(AppointmentBookedEvent.class).count());

        // A failed request releases its key, so the same key works after fixing the input.
        String retryKey = "retry-" + clientId().substring(0, 20);
        Map<String, Object> wrong = booking(null, doctor.getSystemId(), clinicDay(-1, 10, 0), "IN_PERSON");
        assertEquals(400, status(doPostWithHeaders(BASE, patient, wrong, Map.of("Idempotency-Key", retryKey))));
        Map<String, Object> fixed = booking(null, doctor.getSystemId(), clinicDay(2, 11, 0), "IN_PERSON");
        assertEquals(201, status(doPostWithHeaders(BASE, patient, fixed, Map.of("Idempotency-Key", retryKey))));

        assertError(doPostWithHeaders(BASE, patient, fixed, Map.of("Idempotency-Key", "short")), 400,
                "Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'");
    }

    @Test
    void bookingIsRateLimitedPerCaller() throws Exception {
        rateLimitGuard.reset("appointment-book", otherPatient.getSystemId());
        rateLimitProperties.setEnabled(true);
        try {
            Map<String, Object> request = booking(null, "DOC-99999", clinicDay(2, 10, 0), "IN_PERSON");
            for (int i = 0; i < 20; i++) {
                assertEquals(404, status(doPost(BASE, otherPatient, request)));
            }
            MvcResult limited = doPost(BASE, otherPatient, request);
            assertCode(limited, 429, "RATE_LIMITED");
            assertFalse(limited.getResponse().getHeader("Retry-After").isEmpty());
            // Another caller has their own budget.
            assertEquals(404, status(doPost(BASE, patient, request)));
        } finally {
            rateLimitProperties.setEnabled(false);
            rateLimitGuard.reset("appointment-book", otherPatient.getSystemId());
            rateLimitGuard.reset("appointment-book", patient.getSystemId());
        }
    }

    // ==================== CHANGES ====================

    @Test
    void rescheduleFreesTheOldSlotAndPublishesTheMove() throws Exception {
        LocalDateTime first = clinicDay(2, 10, 0);
        LocalDateTime second = clinicDay(2, 11, 0);
        int id = book(patient, booking(null, doctor.getSystemId(), first, "IN_PERSON"));
        jdbc.update("UPDATE appointments SET reminder_sent = TRUE WHERE appointment_id = ?", id);

        MvcResult same = doPatch(BASE + "/" + id + "/reschedule", patient, body("scheduledAt", wallClock(first)));
        assertEquals(200, status(same), text(same));
        assertEquals("SCHEDULED", json(same).path("status").asText());
        assertEquals(0, events.stream(AppointmentRescheduledEvent.class).count());

        MvcResult moved = doPatch(BASE + "/" + id + "/reschedule", patient, body("scheduledAt", wallClock(second)));
        assertEquals(200, status(moved), text(moved));
        assertEquals("RESCHEDULED", json(moved).path("status").asText());
        assertFalse(json(moved).path("reminderSent").asBoolean());
        assertEquals(second, LocalDateTime.parse(json(moved).path("scheduledAt").asText()));
        List<AppointmentRescheduledEvent> rescheduled = events.stream(AppointmentRescheduledEvent.class).toList();
        assertEquals(1, rescheduled.size());
        assertEquals(first, rescheduled.get(0).previousScheduledAt());
        assertEquals(second, rescheduled.get(0).scheduledAt());

        book(otherPatient, booking(null, doctor.getSystemId(), first, "IN_PERSON"));

        MvcResult missing = doPatch(BASE + "/" + id + "/reschedule", patient, body("durationMinutes", 30));
        assertError(missing, 400, "scheduledAt: is required");
        assertEquals("VALIDATION_FAILED", json(missing).path("code").asText());

        // PUT with only text changes runs no slot rule and publishes no domain event.
        MvcResult notes = doPut(BASE + "/" + id, patient, body("notes", "Bring the readings"));
        assertEquals(200, status(notes), text(notes));
        assertEquals("Bring the readings", json(notes).path("notes").asText());
        assertEquals(1, events.stream(AppointmentRescheduledEvent.class).count());
        assertTrue(auditRows("UPDATE", id) >= 2);

        // Not a participant.
        assertError(doPut(BASE + "/" + id, otherPatient, body("notes", "x")), 403,
                "You are not allowed to change this appointment");
        assertError(doPut(BASE + "/" + id, otherDoctor, body("notes", "x")), 403,
                "You are not allowed to change this appointment");
    }

    @Test
    void patientCannotCancelOrMoveAnAppointmentThatHasStarted() throws Exception {
        Appointment started = startedAppointment(patient, doctor, "IN_PERSON");
        String url = BASE + "/" + started.getAppointmentId();

        assertError(doDelete(url, patient), 400, "This appointment has already started and can no longer be cancelled");
        assertError(doPatch(url + "/reschedule", patient, body("scheduledAt", wallClock(clinicDay(2, 10, 0)))), 400,
                "This appointment has already started and can no longer be rescheduled");

        assertEquals(204, status(doDelete(url, doctor)));
        assertEquals("CANCELLED", dbStatus(started.getAppointmentId()));
        AppointmentCancelledEvent cancelled = events.stream(AppointmentCancelledEvent.class).findFirst().orElseThrow();
        assertEquals(doctor.getSystemId(), cancelled.actorSystemId());
        assertFalse(cancelled.bySystem());
        assertEquals(null, cancelled.reason());

        // Repeating the cancel is a no-op: 204 and no second event.
        assertEquals(204, status(doDelete(url, doctor)));
        assertEquals(1, events.stream(AppointmentCancelledEvent.class).count());
    }

    @Test
    void onlyTheAppointmentsDoctorMarksOutcomesAndOnlyAfterTheStart() throws Exception {
        int future = book(patient, booking(null, doctor.getSystemId(), clinicDay(2, 10, 0), "IN_PERSON"));
        assertError(doPatch(BASE + "/" + future + "/status?status=COMPLETED", doctor, null), 400,
                "An appointment that has not started yet cannot be marked as completed");

        Appointment started = startedAppointment(patient, doctor, "IN_PERSON");
        String statusUrl = BASE + "/" + started.getAppointmentId() + "/status?status=";
        assertError(doPatch(statusUrl + "COMPLETED", patient, null), 403,
                "Only the appointment's doctor can mark it as completed");
        assertError(doPatch(statusUrl + "no-show", patient, null), 403,
                "Only the appointment's doctor can mark it as no-show");
        assertError(doPatch(statusUrl + "COMPLETED", otherDoctor, null), 403,
                "You are not part of this patient's care team");
        assertError(doPatch(statusUrl + "RESCHEDULED", doctor, null), 400,
                "To reschedule an appointment, choose a new date and time");
        assertError(doPatch(statusUrl + "LATER", doctor, null), 400,
                "Invalid appointment status. Must be SCHEDULED, COMPLETED, CANCELLED, NO_SHOW, or RESCHEDULED");
        assertError(doPatch(BASE + "/" + started.getAppointmentId() + "/status", doctor, null), 400,
                "Missing required parameter 'status'");

        MvcResult completed = doPatch(statusUrl + "completed", doctor, null);
        assertEquals(200, status(completed), text(completed));
        assertEquals("COMPLETED", json(completed).path("status").asText());
        AppointmentStatusChangedEvent changed = events.stream(AppointmentStatusChangedEvent.class).findFirst().orElseThrow();
        assertEquals("SCHEDULED", changed.previousStatus());
        assertEquals("COMPLETED", changed.status());
        assertEquals(doctor.getSystemId(), changed.actorSystemId());

        // The same status again is a no-op without an event.
        assertEquals(200, status(doPatch(statusUrl + "COMPLETED", doctor, null)));
        assertEquals(1, events.stream(AppointmentStatusChangedEvent.class).count());

        Appointment noShow = startedAppointment(otherPatient, doctor, "IN_PERSON");
        MvcResult marked = doPatch(BASE + "/" + noShow.getAppointmentId() + "/status?status=NO_SHOW", admin, null);
        assertEquals(200, status(marked), text(marked));
        assertEquals("NO_SHOW", dbStatus(noShow.getAppointmentId()));
    }

    @Test
    void terminalStatesRejectEveryChange() throws Exception {
        Appointment completed = startedAppointment(patient, doctor, "IN_PERSON");
        completed.setStatus(AppointmentService.COMPLETED);
        appointmentRepository.saveAndFlush(completed);
        String url = BASE + "/" + completed.getAppointmentId();
        String message = "This appointment is already marked as completed and can no longer be changed";

        assertError(doPut(url, doctor, body("notes", "late note")), 409, message);
        assertError(doPatch(url + "/reschedule", doctor, body("scheduledAt", wallClock(clinicDay(2, 10, 0)))), 409, message);
        assertError(doDelete(url, doctor), 409, message);
        assertError(doPatch(url + "/status?status=NO_SHOW", doctor, null), 409, message);
        assertError(doPatch(url + "/status?status=CANCELLED", doctor, null), 409, message);

        int cancelled = book(patient, booking(null, doctor.getSystemId(), clinicDay(2, 10, 0), "IN_PERSON"));
        assertEquals(204, status(doDelete(BASE + "/" + cancelled, patient)));
        assertError(doPut(BASE + "/" + cancelled, patient, body("notes", "x")), 409,
                "This appointment is already marked as cancelled and can no longer be changed");
        assertEquals(200, status(doPatch(BASE + "/" + cancelled + "/status?status=CANCELLED", patient, null)));

        int moved = book(patient, booking(null, doctor.getSystemId(), clinicDay(2, 12, 0), "IN_PERSON"));
        assertEquals(200, status(doPatch(BASE + "/" + moved + "/reschedule", patient,
                body("scheduledAt", wallClock(clinicDay(2, 13, 0))))));
        assertError(doPatch(BASE + "/" + moved + "/status?status=SCHEDULED", doctor, null), 409,
                "A rescheduled appointment cannot be set back to scheduled");

        assertError(doGet(BASE + "/999999", patient), 404, "Appointment not found");
        assertError(doGet(BASE + "/abc", patient), 400, "Invalid value for parameter 'id'");
    }

    @Test
    void completedConsultationBlocksCancelAndNoShow() throws Exception {
        Appointment started = startedAppointment(patient, doctor, "IN_PERSON");
        consultation(consultationRepository, started, "COMPLETED");
        String url = BASE + "/" + started.getAppointmentId();

        assertError(doDelete(url, doctor), 409, "This appointment has a completed consultation and can no longer be cancelled");
        assertError(doPatch(url + "/status?status=NO_SHOW", doctor, null), 409,
                "This appointment has a completed consultation and cannot be marked as no-show");
        assertEquals("SCHEDULED", dbStatus(started.getAppointmentId()));

        // A draft consultation does not block the cancellation.
        Appointment other = startedAppointment(otherPatient, doctor, "IN_PERSON");
        consultation(consultationRepository, other, "DRAFT");
        assertEquals(204, status(doDelete(BASE + "/" + other.getAppointmentId(), doctor)));
    }

    // ==================== VIDEO AND PHONE ====================

    @Test
    void videoIsRefusedWhileNoCallProviderIsConfigured() throws Exception {
        assertTrue(json(doGet(BASE + "/booking-rules", patient)).path("videoCallsAvailable").asBoolean());
        int inPerson = book(patient, booking(null, doctor.getSystemId(), clinicDay(2, 10, 0), "IN_PERSON"));

        withVideoUnavailable(appointmentService, () -> {
            JsonNode rules = json(doGet(BASE + "/booking-rules", patient));
            assertFalse(rules.path("videoCallsAvailable").asBoolean());
            assertEquals("Africa/Harare", rules.path("timeZone").asText());
            assertEquals(365, rules.path("maxAdvanceDays").asInt());

            MvcResult video = doPost(BASE, patient, booking(null, doctor.getSystemId(), clinicDay(2, 11, 0), "video"));
            assertCode(video, 409, "VIDEO_UNAVAILABLE");
            assertEquals("Video appointments are not available at the moment. Please choose another appointment type.",
                    message(video));

            assertCode(doPut(BASE + "/" + inPerson, patient, body("type", "VIDEO")), 409, "VIDEO_UNAVAILABLE");
            assertEquals("IN_PERSON", jdbc.queryForObject("SELECT type FROM appointments WHERE appointment_id = ?",
                    String.class, inPerson));
        });

        MvcResult video = doPost(BASE, patient, booking(null, doctor.getSystemId(), clinicDay(2, 11, 0), "VIDEO"));
        assertEquals(201, status(video), text(video));
        assertEquals("PREPARING", json(video).path("callStatus").asText());
        int id = json(video).path("appointmentId").asInt();
        assertEquals("/dashboard/call/" + id, json(video).path("callJoinRoute").asText());
    }

    @Test
    void phoneAppointmentsNeedANumberThatOnlyParticipantsSee() throws Exception {
        jdbc.update("UPDATE users SET phone = NULL WHERE system_id = ?", patient.getSystemId());
        MvcResult noNumber = doPost(BASE, patient, booking(null, doctor.getSystemId(), clinicDay(2, 10, 0), "PHONE"));
        assertCode(noNumber, 400, "CONTACT_PHONE_REQUIRED");
        assertEquals("A contact phone number is required for a phone appointment", message(noNumber));

        Map<String, Object> blank = booking(null, doctor.getSystemId(), clinicDay(2, 10, 0), "PHONE");
        blank.put("contactPhone", "");
        assertCode(doPost(BASE, patient, blank), 400, "VALIDATION_FAILED");

        // Number on the patient profile.
        jdbc.update("UPDATE patients SET contact_phone = '+263771234567' WHERE system_id = ?", patient.getSystemId());
        MvcResult fromProfile = doPost(BASE, patient, booking(null, doctor.getSystemId(), clinicDay(2, 10, 0), "PHONE"));
        assertEquals(201, status(fromProfile), text(fromProfile));
        assertEquals("+263771234567", json(fromProfile).path("contactPhone").asText());

        // The account phone wins over the profile number.
        jdbc.update("UPDATE users SET phone = '+263772000111' WHERE system_id = ?", patient.getSystemId());
        MvcResult fromAccount = doPost(BASE, patient, booking(null, doctor.getSystemId(), clinicDay(2, 11, 0), "PHONE"));
        assertEquals("+263772000111", json(fromAccount).path("contactPhone").asText());

        // A sent number is used; other types never store one.
        Map<String, Object> explicit = booking(null, doctor.getSystemId(), clinicDay(2, 12, 0), "PHONE");
        explicit.put("contactPhone", "+263 77 300 4444");
        MvcResult sent = doPost(BASE, patient, explicit);
        assertEquals("+263 77 300 4444", json(sent).path("contactPhone").asText());
        int phoneId = json(sent).path("appointmentId").asInt();

        Map<String, Object> inPerson = booking(null, doctor.getSystemId(), clinicDay(2, 13, 0), "IN_PERSON");
        inPerson.put("contactPhone", "+263771234567");
        MvcResult inPersonResult = doPost(BASE, patient, inPerson);
        assertTrue(json(inPersonResult).path("contactPhone").isNull(), text(inPersonResult));

        // Visibility: appointment's doctor, patient, admin, caregiver who may view appointments.
        String url = BASE + "/" + phoneId;
        for (User viewer : List.of(patient, doctor, admin)) {
            assertEquals("+263 77 300 4444", json(doGet(url, viewer)).path("contactPhone").asText(), viewer.getSystemId());
        }
        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        assertEquals("+263 77 300 4444", json(doGet(url, caregiver)).path("contactPhone").asText());

        // A related doctor who is not the appointment's doctor sees the appointment but not the number.
        careTeamRepository.saveAndFlush(CareTeam.builder()
                .patientSystemId(patient.getSystemId())
                .providerSystemId(otherDoctor.getSystemId())
                .role("SPECIALIST")
                .assignedAt(LocalDateTime.now())
                .isActive(true)
                .build());
        MvcResult related = doGet(url, otherDoctor);
        assertEquals(200, status(related), text(related));
        assertTrue(json(related).path("contactPhone").isNull(), text(related));

        // A caregiver who may manage but not view gets no number in the edit response.
        grantAccess(caregiver, patient, a -> a.setCanScheduleAppointments(true));
        MvcResult managed = doPut(url, caregiver, body("notes", "Call after 10"));
        assertEquals(200, status(managed), text(managed));
        assertTrue(json(managed).path("contactPhone").isNull(), text(managed));

        // Changing the type away from PHONE clears the number; back to PHONE uses the number on file.
        MvcResult toInPerson = doPut(url, patient, body("type", "IN_PERSON"));
        assertTrue(json(toInPerson).path("contactPhone").isNull(), text(toInPerson));
        assertEquals(null, jdbc.queryForObject("SELECT contact_phone FROM appointments WHERE appointment_id = ?",
                String.class, phoneId));
        MvcResult backToPhone = doPut(url, patient, body("type", "phone"));
        assertEquals("+263772000111", json(backToPhone).path("contactPhone").asText());
    }

    // ==================== LISTS ====================

    @Test
    void canonicalListIsPagedFilteredAndScopedByRole() throws Exception {
        int a = book(patient, booking(null, doctor.getSystemId(), clinicDay(2, 9, 0), "IN_PERSON"));
        int b = book(patient, booking(null, doctor.getSystemId(), clinicDay(3, 9, 0), "IN_PERSON"));
        int c = book(patient, booking(null, otherDoctor.getSystemId(), clinicDay(4, 9, 0), "IN_PERSON"));
        int other = book(otherPatient, booking(null, otherDoctor.getSystemId(), clinicDay(2, 9, 0), "IN_PERSON"));
        assertEquals(204, status(doDelete(BASE + "/" + c, patient)));

        JsonNode page = json(doGet(BASE, patient));
        assertEquals(List.of(c, b, a), ids(page));
        assertEquals(0, page.path("page").asInt());
        assertEquals(20, page.path("size").asInt());
        assertEquals(3, page.path("totalElements").asInt());
        assertEquals(1, page.path("totalPages").asInt());

        JsonNode upcoming = json(doGet(BASE + "?status=SCHEDULED,rescheduled&order=asc&from="
                + wallClock(clinicDay(0, 0, 0)), patient));
        assertEquals(List.of(a, b), ids(upcoming));
        JsonNode oneDay = json(doGet(BASE + "?from=" + wallClock(clinicDay(3, 0, 0)) + "&to="
                + wallClock(clinicDay(4, 0, 0)), patient));
        assertEquals(List.of(b), ids(oneDay));
        JsonNode byDoctor = json(doGet(BASE + "?doctorSystemId=" + otherDoctor.getSystemId(), patient));
        assertEquals(List.of(c), ids(byDoctor));
        JsonNode cancelled = json(doGet(BASE + "?status=CANCELLED&status=NO_SHOW", patient));
        assertEquals(List.of(c), ids(cancelled));

        JsonNode clamped = json(doGet(BASE + "?size=1000&page=-3", patient));
        assertEquals(100, clamped.path("size").asInt());
        assertEquals(0, clamped.path("page").asInt());
        JsonNode second = json(doGet(BASE + "?size=1&page=1", patient));
        assertEquals(List.of(b), ids(second));
        assertEquals(3, second.path("totalPages").asInt());
        JsonNode empty = json(doGet(BASE + "?status=NO_SHOW", patient));
        assertEquals(0, empty.path("content").size());
        assertEquals(0, empty.path("totalPages").asInt());

        assertError(doGet(BASE + "?patientSystemId=" + otherPatient.getSystemId(), patient), 403,
                "Patients can only access their own data");
        MvcResult badId = doGet(BASE + "?patientSystemId=nobody", patient);
        assertError(badId, 400, "patientSystemId: must be a valid ID such as PAT-00001");
        assertEquals("VALIDATION_FAILED", json(badId).path("code").asText());
        assertError(doGet(BASE + "?status=LATER", patient), 400,
                "Invalid appointment status. Must be SCHEDULED, COMPLETED, CANCELLED, NO_SHOW, or RESCHEDULED");
        assertError(doGet(BASE + "?order=sideways", patient), 400, "Invalid value for parameter 'order'");
        assertError(doGet(BASE + "?from=" + wallClock(clinicDay(3, 0, 0)) + "&to=" + wallClock(clinicDay(2, 0, 0)),
                patient), 400, "to must be after from");
        assertError(doGet(BASE + "?from=yesterday", patient), 400, "Invalid value for parameter 'from'");

        // Doctor: only own appointments, whatever the patient filter.
        assertEquals(List.of(b, a), ids(json(doGet(BASE, doctor))));
        assertEquals(List.of(b, a), ids(json(doGet(BASE + "?patientSystemId=" + patient.getSystemId(), doctor))));
        assertError(doGet(BASE + "?doctorSystemId=" + otherDoctor.getSystemId(), doctor), 403,
                "You can only access your own doctor data");

        // Caregiver: must name a patient and needs the view permission.
        assertError(doGet(BASE, caregiver), 400, "patientSystemId is required");
        assertError(doGet(BASE + "?patientSystemId=" + patient.getSystemId(), caregiver), 403,
                "You don't have active proxy access to this patient");
        grantAccess(caregiver, patient, g -> g.setCanScheduleAppointments(true));
        assertError(doGet(BASE + "?patientSystemId=" + patient.getSystemId(), caregiver), 403,
                "Your proxy access for this patient does not allow viewing appointments");
        grantAccess(caregiver, patient, g -> {
            g.setCanViewAppointments(true);
            g.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        });
        assertError(doGet(BASE + "?patientSystemId=" + patient.getSystemId(), caregiver), 403,
                "You don't have active proxy access to this patient");
        grantAccess(caregiver, patient, g -> g.setCanViewAppointments(true));
        assertEquals(List.of(c, b, a), ids(json(doGet(BASE + "?patientSystemId=" + patient.getSystemId(), caregiver))));

        // Admin: everything.
        assertEquals(4, json(doGet(BASE, admin)).path("totalElements").asInt());
        assertEquals(List.of(other), ids(json(doGet(BASE + "?patientSystemId=" + otherPatient.getSystemId(), admin))));

        Long listReads = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type = 'APPOINTMENT' "
                + "AND action = 'READ' AND resource_id IS NULL AND patient_system_id = ? AND system_id = ?",
                Long.class, patient.getSystemId(), caregiver.getSystemId());
        assertTrue(listReads != null && listReads >= 1);
    }

    @Test
    void legacyArraysAreCappedNewestFirstAndRemovedRoutesAreGone() throws Exception {
        int a = book(patient, booking(null, doctor.getSystemId(), clinicDay(2, 9, 0), "IN_PERSON"));
        int b = book(patient, booking(null, doctor.getSystemId(), clinicDay(5, 9, 0), "IN_PERSON"));

        MvcResult patientList = doGet(BASE + "/patient/" + patient.getSystemId(), patient);
        assertEquals(200, status(patientList), text(patientList));
        assertTrue(json(patientList).isArray());
        assertEquals(List.of(b, a), arrayIds(json(patientList)));
        assertError(doGet(BASE + "/patient/" + patient.getSystemId(), otherPatient), 403,
                "Patients can only access their own data");

        MvcResult doctorList = doGet(BASE + "/doctor/" + doctor.getSystemId(), doctor);
        assertEquals(List.of(b, a), arrayIds(json(doctorList)));
        assertError(doGet(BASE + "/doctor/" + doctor.getSystemId(), otherDoctor), 403,
                "You can only access your own doctor data");

        MvcResult busy = doGet(BASE + "/doctor/" + doctor.getSystemId() + "/busy?date=" + clinicDay(2, 0, 0).toLocalDate(),
                otherPatient);
        assertEquals(200, status(busy), text(busy));
        JsonNode slot = json(busy).get(0);
        assertEquals(clinicDay(2, 9, 0), LocalDateTime.parse(slot.path("start").asText()));
        assertEquals(clinicDay(2, 9, 30), LocalDateTime.parse(slot.path("end").asText()));
        assertEquals(30, slot.path("durationMinutes").asInt());
        assertEquals(3, slot.size());
        assertError(doGet(BASE + "/doctor/" + doctor.getSystemId() + "/busy", patient), 400,
                "Missing required parameter 'date'");
        assertError(doGet(BASE + "/doctor/DOC-99999/busy?date=2026-11-20", patient), 404, "Doctor not found");

        for (String removed : List.of("/me/upcoming", "/me/completed", "/patient/" + patient.getSystemId() + "/upcoming",
                "/doctor/" + doctor.getSystemId() + "/schedule?start=2026-01-01T00:00:00&end=2027-01-01T00:00:00")) {
            assertEquals(404, status(doGet(BASE + removed, patient)), removed);
        }
    }

    @Test
    void readsAndChangesAreAudited() throws Exception {
        int id = book(patient, booking(null, doctor.getSystemId(), clinicDay(2, 10, 0), "IN_PERSON"));
        assertEquals(1, auditRows("CREATE", id));
        assertEquals(200, status(doGet(BASE + "/" + id, doctor)));
        assertEquals(1, auditRows("READ", id));
        assertEquals(200, status(doPut(BASE + "/" + id, patient, body("reasonForVisit", "Headache"))));
        assertEquals(1, auditRows("UPDATE", id));
        assertEquals(204, status(doDelete(BASE + "/" + id, patient)));
        assertEquals(2, auditRows("UPDATE", id));

        assertEquals(403, status(doGet(BASE + "/" + id, otherPatient)));
        Long denied = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCESS_DENIED' "
                + "AND system_id = ?", Long.class, otherPatient.getSystemId());
        assertTrue(denied != null && denied >= 1);
    }

    private static List<Integer> ids(JsonNode page) {
        return arrayIds(page.path("content"));
    }

    private static List<Integer> arrayIds(JsonNode array) {
        List<Integer> ids = new ArrayList<>();
        array.forEach(n -> ids.add(n.path("appointmentId").asInt()));
        return ids;
    }
}
