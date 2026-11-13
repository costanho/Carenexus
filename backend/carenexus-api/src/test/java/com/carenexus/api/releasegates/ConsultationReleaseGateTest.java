package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.event.AppointmentCancelledEvent;
import com.carenexus.api.common.event.AppointmentStatusChangedEvent;
import com.carenexus.api.common.event.ConsultationCompletedEvent;
import com.carenexus.api.core.consultations.ConsultationFixtures;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.model.MedicalRecord;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.carenexus.api.core.repository.ConsultationRepository;
import com.carenexus.api.core.repository.MedicalRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Release gate for consultations (be-consultations): create rules, the transition table, edit and delete locks,
 * duplicate protection (service check, appointment lock, V35 index), validation, visibility for every role, the
 * completion event and the appointment listener.
 *
 * Completion listeners of other slices (appointment, medical record, invoice) are not part of this workspace; the
 * end-to-end chain is asserted by the integration-gate slices. Here the event is counted with
 * {@link ApplicationEvents}, and the appointment events are published by the test.
 */
@RecordApplicationEvents
class ConsultationReleaseGateTest extends CommittedIntegrationTest {

    private static final String BASE = "/api/consultations";
    private static final String DOC = "/api/doctors/consultations";

    private static final String A1 = "Your role is not authorized to perform this action";
    private static final String A2 = "Only the doctor who created this item can change it";
    private static final String A3 = "Patients can only access their own data";
    private static final String A4 = "You are not part of this patient's care team";
    private static final String A5 = "You don't have active proxy access to this patient";
    private static final String A6 = "Your proxy access for this patient does not allow viewing consultations";
    private static final String A7 = "Your clinician account is not active";
    private static final String A8 = "You can only access your own doctor data";
    private static final String A9 = "You don't have permission to access this resource";

    @Autowired
    private AppointmentRepository appointmentRepository;
    @Autowired
    private ConsultationRepository consultationRepository;
    @Autowired
    private MedicalRecordRepository medicalRecordRepository;
    @Autowired
    private Clock clock;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ApplicationEvents events;

    private ConsultationFixtures fx;

    @BeforeEach
    void consultationFixtures() {
        fx = new ConsultationFixtures(appointmentRepository, consultationRepository, clock);
    }

    // ==================== CREATE ====================

    @Test
    void create_opensADraftForTheDoctorsOwnStartedAppointment_withTheFullResponseShape() throws Exception {
        Appointment appointment = fx.startedAppointment(patient, doctor);

        MvcResult result = doPost(BASE, doctor, body("appointmentId", appointment.getAppointmentId(),
                "chiefComplaint", "  Persistent cough  ", "clinicalNotes", "   ", "doctorSystemId", "DOC-99999"));

        assertEquals(201, status(result), result.getResponse().getContentAsString());
        JsonNode c = json(result);
        assertEquals("DRAFT", c.path("status").asText());
        assertEquals(appointment.getAppointmentId().intValue(), c.path("appointmentId").asInt());
        assertEquals(patient.getSystemId(), c.path("patientSystemId").asText());
        assertEquals(doctor.getSystemId(), c.path("doctorSystemId").asText(), "the author is always the caller");
        assertEquals("IN_PERSON", c.path("appointmentType").asText());
        assertEquals("SCHEDULED", c.path("appointmentStatus").asText());
        assertEquals("Persistent cough", c.path("chiefComplaint").asText(), "text is trimmed");
        assertNullField(c, "clinicalNotes");
        assertNullField(c, "patientName");
        assertNullField(c, "medicalRecordId");
        assertNullField(c, "startedAt");
        assertNullField(c, "completedAt");
        assertNullField(c, "followUpDate");
        assertFalse(c.path("followUpRequired").asBoolean());
        assertFalse(c.path("createdAt").isNull());
        assertEquals(1, auditRows("CREATE", c.path("consultationId").asInt()));
    }

    @Test
    void create_acceptsInProgressCaseInsensitively_andCompletedOrRescheduledAppointments() throws Exception {
        Appointment scheduled = fx.startedAppointment(patient, doctor);
        MvcResult started = doPost(BASE, doctor, body("appointmentId", scheduled.getAppointmentId(), "status", " in_progress "));
        assertEquals(201, status(started));
        assertEquals("IN_PROGRESS", json(started).path("status").asText());
        assertFalse(json(started).path("startedAt").isNull(), "creating IN_PROGRESS sets startedAt");

        Appointment completed = fx.startedAppointment(patient, doctor, "COMPLETED");
        assertEquals(201, status(doPost(BASE, doctor, body("appointmentId", completed.getAppointmentId()))));
        Appointment rescheduled = fx.startedAppointment(patient, doctor, "RESCHEDULED");
        assertEquals(201, status(doPost(BASE, doctor, body("appointmentId", rescheduled.getAppointmentId()))));
    }

    @Test
    void create_refusesAppointmentsThatCannotBeDocumented_andCallersWhoMayNotDocument() throws Exception {
        Appointment cancelled = fx.startedAppointment(patient, doctor, "CANCELLED");
        assertError(doPost(BASE, doctor, body("appointmentId", cancelled.getAppointmentId())), 409,
                "Cannot create a consultation for a cancelled appointment");
        Appointment noShow = fx.startedAppointment(patient, doctor, "NO_SHOW");
        assertError(doPost(BASE, doctor, body("appointmentId", noShow.getAppointmentId())), 409,
                "Cannot create a consultation for a missed appointment");
        Appointment future = fx.futureAppointment(patient, doctor);
        assertError(doPost(BASE, doctor, body("appointmentId", future.getAppointmentId())), 409,
                "A consultation can only be opened once the appointment has started");

        Appointment theirs = fx.startedAppointment(otherPatient, otherDoctor);
        assertError(doPost(BASE, doctor, body("appointmentId", theirs.getAppointmentId())), 403,
                "You can only document consultations for your own appointments");
        assertError(doPost(BASE, doctor, body("appointmentId", 987654)), 404, "Appointment not found");

        Appointment mine = fx.startedAppointment(patient, doctor);
        assertError(doPost(BASE, doctor, body("appointmentId", mine.getAppointmentId(),
                "patientSystemId", otherPatient.getSystemId())), 400, "Appointment does not belong to this patient");

        for (User notADoctor : List.of(patient, caregiver, admin)) {
            assertError(doPost(BASE, notADoctor, body("appointmentId", mine.getAppointmentId())), 403, A1);
        }

        Doctor profile = doctorRepository.findBySystemId(doctor.getSystemId()).orElseThrow();
        profile.setIsActive(false);
        doctorRepository.saveAndFlush(profile);
        assertError(doPost(BASE, doctor, body("appointmentId", mine.getAppointmentId())), 403, A7);

        assertEquals(0, consultationRepository.count(), "no refused create wrote a row");
    }

    @Test
    void create_validatesTheBodyBeforeAnyRoleCheck() throws Exception {
        Appointment appointment = fx.startedAppointment(patient, doctor);
        Integer id = appointment.getAppointmentId();

        assertValidation(doPost(BASE, doctor, body("chiefComplaint", "x")), "appointmentId: is required");
        assertValidation(doPost(BASE, patient, body("chiefComplaint", "x")), "appointmentId: is required");
        assertValidation(doPost(BASE, doctor, body("appointmentId", 0)), "appointmentId: must be a positive number");
        assertValidation(doPost(BASE, doctor, body("appointmentId", id, "diagnosis", "d".repeat(10001))),
                "diagnosis: must be at most 10000 characters");
        assertValidation(doPost(BASE, doctor, body("appointmentId", id, "durationMinutes", 481)),
                "durationMinutes: must be between 1 and 480");
        assertValidation(doPost(BASE, doctor, body("appointmentId", id, "durationMinutes", 0)),
                "durationMinutes: must be between 1 and 480");
        assertValidation(doPost(BASE, doctor, body("appointmentId", id, "status", "COMPLETED")),
                "status: must be DRAFT or IN_PROGRESS");
        assertValidation(doPost(BASE, doctor, body("appointmentId", id, "patientSystemId", "P".repeat(21))),
                "patientSystemId: must be at most 20 characters");
        assertValidation(doPost(BASE, doctor, body("appointmentId", id, "followUpRequired", true)),
                "followUpDate: is required when a follow-up is required");
        assertValidation(doPost(BASE, doctor, body("appointmentId", id, "followUpRequired", true,
                "followUpDate", today().minusDays(1).toString())), "followUpDate: must not be before today");
        assertError(doPost(BASE, doctor, body("appointmentId", id, "followUpDate", "24-11-2026")), 400,
                "Malformed request body");
        assertEquals(0, consultationRepository.count());

        MvcResult ignoredDate = doPost(BASE, doctor, body("appointmentId", id, "followUpRequired", false,
                "followUpDate", today().minusDays(3).toString(), "durationMinutes", 480));
        assertEquals(201, status(ignoredDate));
        assertNullField(json(ignoredDate), "followUpDate");
        assertEquals(480, json(ignoredDate).path("durationMinutes").asInt());

        Appointment second = fx.startedAppointment(patient, doctor);
        MvcResult todayFollowUp = doPost(BASE, doctor, body("appointmentId", second.getAppointmentId(),
                "followUpRequired", true, "followUpDate", today().toString()));
        assertEquals(201, status(todayFollowUp), "today in the clinic zone is allowed");
        assertEquals(today().toString(), json(todayFollowUp).path("followUpDate").asText());
    }

    @Test
    void create_oneLiveConsultationPerAppointment_aCancelledOneDoesNotBlock() throws Exception {
        Appointment appointment = fx.startedAppointment(patient, doctor);
        Map<String, Object> request = body("appointmentId", appointment.getAppointmentId());

        MvcResult first = doPost(BASE, doctor, request);
        assertEquals(201, status(first));
        assertError(doPost(BASE, doctor, request), 409, "A consultation already exists for this appointment");

        int firstId = json(first).path("consultationId").asInt();
        assertEquals("CANCELLED", json(doPatch(DOC + "/" + firstId + "/mark-cancelled", doctor, null)).path("status").asText());
        assertEquals(201, status(doPost(BASE, doctor, request)), "a cancelled consultation does not block a new one");

        // The V35 index is the database safety net behind the service check.
        assertThrows(DataIntegrityViolationException.class, () -> fx.consultation(appointment, "DRAFT", null, null));
    }

    @Test
    void create_concurrentRequestsForOneAppointment_produceExactlyOneRow() throws Exception {
        Appointment appointment = fx.startedAppointment(patient, doctor);
        Map<String, Object> request = body("appointmentId", appointment.getAppointmentId());

        List<MvcResult> results = concurrently(6, () -> doPost(BASE, doctor, request));

        int created = 0;
        for (MvcResult r : results) {
            if (status(r) == 201) {
                created++;
            } else {
                assertError(r, 409, "A consultation already exists for this appointment");
            }
        }
        assertEquals(1, created);
        assertEquals(1, consultationRepository.findByAppointmentId(appointment.getAppointmentId()).size());
    }

    @Test
    void create_idempotencyKeyReplaysTheFirstResponse() throws Exception {
        Appointment appointment = fx.startedAppointment(patient, doctor);
        Map<String, Object> request = body("appointmentId", appointment.getAppointmentId());
        Map<String, String> headers = Map.of("Idempotency-Key", clientId());

        MvcResult first = doPostWithHeaders(BASE, doctor, request, headers);
        MvcResult replay = doPostWithHeaders(BASE, doctor, request, headers);

        assertEquals(201, status(first));
        assertEquals(200, status(replay));
        assertEquals(json(first).path("consultationId").asInt(), json(replay).path("consultationId").asInt());
        assertEquals(1, consultationRepository.count());
        assertError(doPostWithHeaders(BASE, doctor, request, Map.of("Idempotency-Key", "bad")), 400,
                "Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'");
    }

    // ==================== TRANSITIONS ====================

    @Test
    void transitions_everyAllowedTransitionApplies_andSetsItsTimestampsOnce() throws Exception {
        String[][] allowed = {
                {"DRAFT", "IN_PROGRESS"}, {"DRAFT", "COMPLETED"}, {"DRAFT", "CANCELLED"},
                {"IN_PROGRESS", "COMPLETED"}, {"IN_PROGRESS", "CANCELLED"}};
        for (String[] t : allowed) {
            Consultation c = fx.consultation(patient, doctor, t[0]);
            MvcResult r = doPatch(BASE + "/" + c.getConsultationId() + "/status?status=" + t[1].toLowerCase(), doctor, null);
            assertEquals(200, status(r), t[0] + " -> " + t[1] + ": " + r.getResponse().getContentAsString());
            JsonNode body = json(r);
            assertEquals(t[1], body.path("status").asText());
            assertEquals(t[1], consultationRepository.findById(c.getConsultationId()).orElseThrow().getStatus());
            assertEquals(1, auditRows("UPDATE", c.getConsultationId()));

            if (t[1].equals("IN_PROGRESS")) {
                assertFalse(body.path("startedAt").isNull());
            }
            if (t[0].equals("DRAFT") && t[1].equals("COMPLETED")) {
                assertNullField(body, "startedAt");
            }
            if (t[0].equals("IN_PROGRESS")) {
                assertEquals(c.getStartedAt().withNano(0),
                        consultationRepository.findById(c.getConsultationId()).orElseThrow().getStartedAt().withNano(0),
                        "startedAt is never overwritten");
            }
            if (t[1].equals("COMPLETED")) {
                assertFalse(body.path("completedAt").isNull());
            }
        }

        // The doctor routes are the same transitions.
        Consultation viaRoutes = fx.consultation(patient, doctor, "DRAFT");
        String route = DOC + "/" + viaRoutes.getConsultationId();
        assertEquals("IN_PROGRESS", json(doPatch(route + "/mark-in-progress", doctor, null)).path("status").asText());
        assertEquals("COMPLETED", json(doPatch(route + "/mark-completed", doctor, null)).path("status").asText());
    }

    @Test
    void transitions_everyForbiddenTransitionIs409_andChangesNothing() throws Exception {
        String[][] forbidden = {
                {"IN_PROGRESS", "DRAFT", "A consultation that is in progress cannot go back to draft"},
                {"COMPLETED", "DRAFT", "A completed consultation cannot be changed"},
                {"COMPLETED", "IN_PROGRESS", "A completed consultation cannot be changed"},
                {"COMPLETED", "CANCELLED", "A completed consultation cannot be changed"},
                {"CANCELLED", "DRAFT", "A cancelled consultation cannot be changed"},
                {"CANCELLED", "IN_PROGRESS", "A cancelled consultation cannot be changed"},
                {"CANCELLED", "COMPLETED", "A cancelled consultation cannot be changed"}};
        for (String[] t : forbidden) {
            Consultation c = fx.consultation(patient, doctor, t[0]);
            assertError(doPatch(BASE + "/" + c.getConsultationId() + "/status?status=" + t[1], doctor, null), 409, t[2]);
            assertEquals(t[0], consultationRepository.findById(c.getConsultationId()).orElseThrow().getStatus());
            assertEquals(0, auditRows("UPDATE", c.getConsultationId()));
        }
        assertEquals(0, events.stream(ConsultationCompletedEvent.class).count());
    }

    @Test
    void transitions_theCurrentStatusIsANoOp() throws Exception {
        for (String s : List.of("DRAFT", "IN_PROGRESS", "COMPLETED", "CANCELLED")) {
            Consultation c = fx.consultation(patient, doctor, s);
            MvcResult r = doPatch(BASE + "/" + c.getConsultationId() + "/status?status=" + s, doctor, null);
            assertEquals(200, status(r), s);
            assertEquals(s, json(r).path("status").asText());
            assertEquals(0, auditRows("UPDATE", c.getConsultationId()), "a no-op writes nothing: " + s);
            Consultation after = consultationRepository.findById(c.getConsultationId()).orElseThrow();
            if (c.getCompletedAt() != null) {
                assertEquals(c.getCompletedAt().withNano(0), after.getCompletedAt().withNano(0));
            }
        }
        assertEquals(0, events.stream(ConsultationCompletedEvent.class).count());
    }

    @Test
    void completion_requiresAChiefComplaintAndADiagnosis() throws Exception {
        Consultation noComplaint = fx.consultation(fx.startedAppointment(patient, doctor), "DRAFT", null, "Flu");
        assertValidation(doPatch(DOC + "/" + noComplaint.getConsultationId() + "/mark-completed", doctor, null),
                "chiefComplaint: is required to complete a consultation");
        Consultation blankDiagnosis = fx.consultation(fx.startedAppointment(patient, doctor), "IN_PROGRESS", "Cough", "  ");
        assertValidation(doPatch(DOC + "/" + blankDiagnosis.getConsultationId() + "/mark-completed", doctor, null),
                "diagnosis: is required to complete a consultation");

        assertEquals("DRAFT", consultationRepository.findById(noComplaint.getConsultationId()).orElseThrow().getStatus());
        assertEquals("IN_PROGRESS", consultationRepository.findById(blankDiagnosis.getConsultationId()).orElseThrow().getStatus());
        assertEquals(0, events.stream(ConsultationCompletedEvent.class).count());
    }

    @Test
    void completion_publishesExactlyOneEvent_neverOnANoOpOrACancellation() throws Exception {
        Consultation c = fx.consultation(patient, doctor, "IN_PROGRESS");
        c.setFollowUpRequired(true);
        c.setFollowUpDate(today().plusDays(14));
        consultationRepository.saveAndFlush(c);

        MvcResult completed = doPatch(DOC + "/" + c.getConsultationId() + "/mark-completed", doctor, null);
        assertEquals(200, status(completed));
        String completedAt = json(completed).path("completedAt").asText();

        List<ConsultationCompletedEvent> published = events.stream(ConsultationCompletedEvent.class).toList();
        assertEquals(1, published.size());
        ConsultationCompletedEvent event = published.get(0);
        assertEquals(c.getConsultationId(), event.consultationId());
        assertEquals(c.getAppointmentId(), event.appointmentId());
        assertEquals(patient.getSystemId(), event.patientSystemId());
        assertEquals(doctor.getSystemId(), event.doctorSystemId());
        assertTrue(event.followUpRequired());
        assertEquals(today().plusDays(14), event.followUpDate());

        // Repeating the completion is a no-op: no second event, completedAt kept.
        MvcResult again = doPatch(BASE + "/" + c.getConsultationId() + "/status?status=COMPLETED", doctor, null);
        assertEquals(200, status(again));
        assertEquals(completedAt, json(again).path("completedAt").asText());
        assertEquals(1, events.stream(ConsultationCompletedEvent.class).count());

        Consultation toCancel = fx.consultation(patient, doctor, "DRAFT");
        assertEquals(200, status(doPatch(DOC + "/" + toCancel.getConsultationId() + "/mark-cancelled", doctor, null)));
        assertEquals(1, events.stream(ConsultationCompletedEvent.class).count(), "cancelling publishes nothing");

        Consultation viaPut = fx.consultation(fx.startedAppointment(patient, doctor), "DRAFT", null, null);
        MvcResult put = doPut(BASE + "/" + viaPut.getConsultationId(), doctor,
                body("chiefComplaint", "Headache", "diagnosis", "Tension headache", "status", "completed"));
        assertEquals(200, status(put), put.getResponse().getContentAsString());
        assertEquals("COMPLETED", json(put).path("status").asText());
        assertEquals(2, events.stream(ConsultationCompletedEvent.class).count());
        assertEquals(1, auditRows("UPDATE", viaPut.getConsultationId()), "one UPDATE row for content plus status");
    }

    @Test
    void statusRoutes_errorsAndWhoMayCall() throws Exception {
        Consultation c = fx.consultation(patient, doctor, "DRAFT");
        String url = BASE + "/" + c.getConsultationId() + "/status";

        assertError(doPatch(url, doctor, null), 400, "Missing required parameter 'status'");
        assertError(doPatch(url + "?status=FINISHED", doctor, null), 400,
                "Invalid consultation status. Must be DRAFT, IN_PROGRESS, COMPLETED, or CANCELLED");
        assertError(doPatch(BASE + "/987654/status?status=CANCELLED", doctor, null), 404, "Consultation not found");
        assertError(doPatch(url + "?status=CANCELLED", otherDoctor, null), 403, A2);
        assertError(doPatch(url + "?status=CANCELLED", admin, null), 403, A2);
        assertError(doPatch(url + "?status=CANCELLED", patient, null), 403, A2);
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCESS_DENIED' "
                + "AND resource_type = 'CONSULTATION' AND resource_id = ?", Integer.class, c.getConsultationId()) >= 3);

        assertError(doPatch(DOC + "/" + c.getConsultationId() + "/mark-cancelled", patient, null), 403, A9);
        assertError(doPatch(DOC + "/" + c.getConsultationId() + "/mark-cancelled", caregiver, null), 403, A9);
        assertError(doPatch(DOC + "/abc/mark-cancelled", doctor, null), 400, "Invalid value for parameter 'consultationId'");
        assertError(doGet(BASE + "/abc", doctor), 400, "Invalid value for parameter 'id'");
        assertEquals("DRAFT", consultationRepository.findById(c.getConsultationId()).orElseThrow().getStatus());
    }

    // ==================== PUT ====================

    @Test
    void put_isAPartialUpdate_withTheFollowUpRuleOnTheMergedState() throws Exception {
        Consultation c = fx.consultation(fx.startedAppointment(patient, doctor), "DRAFT", "Cough", "Bronchitis");
        String url = BASE + "/" + c.getConsultationId();
        LocalDate inTwoWeeks = today().plusDays(14);

        MvcResult first = doPut(url, doctor, body("clinicalNotes", " Chest clear ", "diagnosis", "   ",
                "durationMinutes", 25, "followUpRequired", true, "followUpDate", inTwoWeeks.toString(),
                "appointmentId", 999, "patientSystemId", otherPatient.getSystemId()));
        assertEquals(200, status(first), first.getResponse().getContentAsString());
        JsonNode saved = json(first);
        assertEquals("Cough", saved.path("chiefComplaint").asText(), "absent fields are unchanged");
        assertNullField(saved, "diagnosis");
        assertEquals("Chest clear", saved.path("clinicalNotes").asText());
        assertEquals(25, saved.path("durationMinutes").asInt());
        assertTrue(saved.path("followUpRequired").asBoolean());
        assertEquals(inTwoWeeks.toString(), saved.path("followUpDate").asText());
        assertEquals(c.getAppointmentId().intValue(), saved.path("appointmentId").asInt(), "fixed fields are ignored");
        assertEquals(patient.getSystemId(), saved.path("patientSystemId").asText());

        MvcResult keepDate = doPut(url, doctor, body("clinicalNotes", null, "durationMinutes", null));
        assertEquals(inTwoWeeks.toString(), json(keepDate).path("followUpDate").asText(), "the stored date stays valid");
        assertEquals("Chest clear", json(keepDate).path("clinicalNotes").asText(), "null leaves a field unchanged");
        assertEquals(25, json(keepDate).path("durationMinutes").asInt(), "duration cannot be cleared");

        MvcResult cleared = doPut(url, doctor, body("followUpRequired", false, "followUpDate", today().plusDays(3).toString()));
        assertFalse(json(cleared).path("followUpRequired").asBoolean());
        assertNullField(json(cleared), "followUpDate");

        assertValidation(doPut(url, doctor, body("followUpRequired", true)),
                "followUpDate: is required when a follow-up is required");
        assertValidation(doPut(url, doctor, body("followUpRequired", true, "followUpDate", today().minusDays(1).toString())),
                "followUpDate: must not be before today");
        assertValidation(doPut(url, doctor, body("treatmentPlan", "t".repeat(10001))),
                "treatmentPlan: must be at most 10000 characters");
        assertValidation(doPut(url, doctor, body("durationMinutes", 600)), "durationMinutes: must be between 1 and 480");
        assertValidation(doPut(url, doctor, body("status", "FINISHED")),
                "status: must be one of DRAFT, IN_PROGRESS, COMPLETED, CANCELLED");
        assertEquals(3, auditRows("UPDATE", c.getConsultationId()));
    }

    @Test
    void put_isAtomic_refusedAfterATerminalState_andOnlyForTheAuthor() throws Exception {
        Consultation draft = fx.consultation(fx.startedAppointment(patient, doctor), "DRAFT", "Cough", null);
        String url = BASE + "/" + draft.getConsultationId();
        assertValidation(doPut(url, doctor, body("clinicalNotes", "never saved", "status", "COMPLETED")),
                "diagnosis: is required to complete a consultation");
        Consultation unchanged = consultationRepository.findById(draft.getConsultationId()).orElseThrow();
        assertEquals(null, unchanged.getClinicalNotes(), "content and status change are one transaction");
        assertEquals("DRAFT", unchanged.getStatus());

        assertError(doPut(url, otherDoctor, body("clinicalNotes", "x")), 403, A2);
        assertError(doPut(url, admin, body("clinicalNotes", "x")), 403, A2);
        assertError(doPut(url, patient, body("clinicalNotes", "x")), 403, A2);
        assertError(doPut(BASE + "/987654", doctor, body("clinicalNotes", "x")), 404, "Consultation not found");

        Consultation completed = fx.consultation(patient, doctor, "COMPLETED");
        assertError(doPut(BASE + "/" + completed.getConsultationId(), doctor, Map.of()), 409,
                "A completed consultation cannot be changed");
        assertError(doPut(BASE + "/" + completed.getConsultationId(), doctor, body("status", "COMPLETED")), 409,
                "A completed consultation cannot be changed");
        Consultation cancelled = fx.consultation(patient, doctor, "CANCELLED");
        assertError(doPut(BASE + "/" + cancelled.getConsultationId(), doctor, body("clinicalNotes", "late")), 409,
                "A cancelled consultation cannot be changed");
        assertEquals(0, events.stream(ConsultationCompletedEvent.class).count());
    }

    // ==================== DELETE ====================

    @Test
    void delete_onlyADraft_byItsAuthorOrAnAdmin_unlinkingRecords() throws Exception {
        Consultation draft = fx.consultation(patient, doctor, "DRAFT");
        MedicalRecord record = linkedRecord(draft);

        assertError(doDelete(BASE + "/" + draft.getConsultationId(), otherDoctor), 403, A2);
        assertError(doDelete(BASE + "/" + draft.getConsultationId(), patient), 403, A2);
        assertEquals(204, status(doDelete(BASE + "/" + draft.getConsultationId(), doctor)));
        assertError(doDelete(BASE + "/" + draft.getConsultationId(), doctor), 404, "Consultation not found");
        assertFalse(consultationRepository.existsById(draft.getConsultationId()));
        assertEquals(null, medicalRecordRepository.findById(record.getRecordId()).orElseThrow().getConsultationId(),
                "the database unlinks the record, it does not delete it");
        assertEquals(1, auditRows("DELETE", draft.getConsultationId()));

        Consultation adminDeletes = fx.consultation(patient, doctor, "DRAFT");
        assertEquals(204, status(doDelete(BASE + "/" + adminDeletes.getConsultationId(), admin)));

        for (String s : List.of("IN_PROGRESS", "COMPLETED", "CANCELLED")) {
            Consultation c = fx.consultation(patient, doctor, s);
            assertError(doDelete(BASE + "/" + c.getConsultationId(), doctor), 409, "Only a draft consultation can be deleted");
            assertTrue(consultationRepository.existsById(c.getConsultationId()));
        }
    }

    // ==================== VISIBILITY ====================

    @Test
    void patientsAndCaregivers_seeOnlyFinalisedConsultations_withTheLiveGrant() throws Exception {
        Consultation draft = fx.consultation(patient, doctor, "DRAFT");
        fx.consultation(patient, doctor, "IN_PROGRESS");
        Consultation completed = fx.consultation(patient, doctor, "COMPLETED");
        fx.consultation(patient, doctor, "CANCELLED");
        fx.consultation(otherPatient, otherDoctor, "COMPLETED");

        JsonNode own = json(doGet(BASE, patient));
        assertEquals(2, own.path("totalElements").asInt(), own.toString());
        own.path("content").forEach(c -> assertTrue(List.of("COMPLETED", "CANCELLED").contains(c.path("status").asText())));
        assertEquals(0, json(doGet(BASE + "?status=draft", patient)).path("totalElements").asInt());
        assertEquals(1, json(doGet(BASE + "?status=COMPLETED", patient)).path("totalElements").asInt());
        assertError(doGet(BASE + "?patientSystemId=" + otherPatient.getSystemId(), patient), 403, A3);
        assertError(doGet(BASE + "/" + draft.getConsultationId(), patient), 404, "Consultation not found");
        assertEquals(200, status(doGet(BASE + "/" + completed.getConsultationId(), patient)));
        assertEquals(1, auditRows("READ", completed.getConsultationId()));
        assertEquals(2, json(doGet(BASE + "/patient/" + patient.getSystemId(), patient)).size());
        assertEquals(0, json(doGet(BASE + "/appointment/" + draft.getAppointmentId(), patient)).size());
        assertError(doGet(BASE + "/" + completed.getConsultationId(), otherPatient), 403, A3);

        String forPatient = BASE + "?patientSystemId=" + patient.getSystemId();
        assertError(doGet(forPatient, caregiver), 403, A5);
        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        assertError(doGet(forPatient, caregiver), 403, A6);
        grantAccess(caregiver, patient, a -> a.setCanViewConsultations(true));
        assertEquals(2, json(doGet(forPatient, caregiver)).path("totalElements").asInt());
        assertEquals(1, json(doGet(forPatient + "&status=COMPLETED", caregiver)).path("totalElements").asInt());
        assertError(doGet(BASE + "/" + draft.getConsultationId(), caregiver), 404, "Consultation not found");
        assertValidation(doGet(BASE, caregiver), "patientSystemId: is required");
        assertError(doGet(BASE + "?patientSystemId=" + patient.getSystemId(), otherCaregiver), 403, A5);
        grantAccess(caregiver, patient, a -> {
            a.setAccessLevel("FULL_ACCESS");
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
        });
        assertError(doGet(forPatient, caregiver), 403, A5);
    }

    @Test
    void doctorsAndAdmins_seeEverythingTheyMay_pagedNewestFirst() throws Exception {
        assertError(doGet(BASE + "?patientSystemId=" + patient.getSystemId(), otherDoctor), 403, A4);
        assertError(doGet(BASE + "?doctorSystemId=" + otherDoctor.getSystemId(), doctor), 403, A8);
        assertValidation(doGet(BASE + "?status=FINISHED", doctor),
                "status: must be one of DRAFT, IN_PROGRESS, COMPLETED, CANCELLED");
        assertError(doGet(BASE + "?size=abc", doctor), 400, "Invalid value for parameter 'size'");

        List<Integer> mine = new ArrayList<>();
        for (String s : List.of("DRAFT", "IN_PROGRESS", "COMPLETED", "CANCELLED")) {
            mine.add(fx.consultation(patient, doctor, s).getConsultationId());
        }
        // otherDoctor now sees the patient through their own appointment and writes a draft.
        Consultation othersDraft = fx.consultation(patient, otherDoctor, "DRAFT");

        JsonNode own = json(doGet(BASE, doctor));
        assertEquals(4, own.path("totalElements").asInt(), "a doctor's default list is their own consultations");
        assertEquals(mine.get(3).intValue(), own.path("content").get(0).path("consultationId").asInt(), "newest first");

        JsonNode history = json(doGet(BASE + "?patientSystemId=" + patient.getSystemId(), doctor));
        assertEquals(5, history.path("totalElements").asInt(), "the patient's full history, other doctors' drafts included");
        JsonNode onlyMine = json(doGet(BASE + "?patientSystemId=" + patient.getSystemId()
                + "&doctorSystemId=" + doctor.getSystemId(), doctor));
        assertEquals(4, onlyMine.path("totalElements").asInt());
        assertEquals(200, status(doGet(BASE + "/" + othersDraft.getConsultationId(), doctor)), "related doctors see drafts");

        JsonNode adminAll = json(doGet(BASE + "?size=500&page=-3", admin));
        assertEquals(5, adminAll.path("totalElements").asInt());
        assertEquals(100, adminAll.path("size").asInt(), "size is clamped");
        assertEquals(0, adminAll.path("page").asInt());
        JsonNode adminFiltered = json(doGet(BASE + "?doctorSystemId=" + otherDoctor.getSystemId() + "&size=1", admin));
        assertEquals(1, adminFiltered.path("totalElements").asInt());
        JsonNode pageOfOne = json(doGet(BASE + "?size=1&page=1", doctor));
        assertEquals(1, pageOfOne.path("content").size());
        assertEquals(4, pageOfOne.path("totalPages").asInt());
        assertEquals(mine.get(2).intValue(), pageOfOne.path("content").get(0).path("consultationId").asInt());

        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'READ' "
                + "AND resource_type = 'CONSULTATION' AND resource_id IS NULL AND patient_system_id = ?",
                Integer.class, patient.getSystemId()) >= 1, "list reads are audited per patient");
    }

    @Test
    void responsesReportTheLinkedConsultRecord() throws Exception {
        Consultation completed = fx.consultation(patient, doctor, "COMPLETED");
        assertNullField(json(doGet(BASE + "/" + completed.getConsultationId(), patient)), "medicalRecordId");

        MedicalRecord record = linkedRecord(completed);
        assertEquals(record.getRecordId().intValue(),
                json(doGet(BASE + "/" + completed.getConsultationId(), patient)).path("medicalRecordId").asInt());
        assertEquals(record.getRecordId().intValue(),
                json(doGet(BASE, patient)).path("content").get(0).path("medicalRecordId").asInt());
        assertEquals("SCHEDULED", json(doGet(BASE, patient)).path("content").get(0).path("appointmentStatus").asText());

        record.setIsDeleted(true);
        record.setDeletedAt(LocalDateTime.now());
        medicalRecordRepository.saveAndFlush(record);
        assertNullField(json(doGet(BASE + "/" + completed.getConsultationId(), patient)), "medicalRecordId");
    }

    // ==================== APPOINTMENT LISTENER ====================

    @Test
    void appointmentCancelledOrNoShow_cancelsTheOpenConsultation_andLeavesFinalOnesAlone() {
        Consultation draft = fx.consultation(patient, doctor, "DRAFT");
        Consultation inProgress = fx.consultation(patient, doctor, "IN_PROGRESS");
        Consultation completed = fx.consultation(patient, doctor, "COMPLETED");
        Consultation untouched = fx.consultation(patient, doctor, "DRAFT");

        publish(new AppointmentCancelledEvent(draft.getAppointmentId(), patient.getSystemId(), doctor.getSystemId(),
                patient.getSystemId(), "PATIENT", fx.clinicNow(), "Feeling better", false));
        assertEquals("CANCELLED", statusOf(draft));
        assertEquals(1, auditRows("UPDATE", draft.getConsultationId()));
        publish(new AppointmentCancelledEvent(draft.getAppointmentId(), patient.getSystemId(), doctor.getSystemId(),
                null, null, fx.clinicNow(), null, true));
        assertEquals(1, auditRows("UPDATE", draft.getConsultationId()), "a repeated event changes nothing");

        publish(new AppointmentStatusChangedEvent(inProgress.getAppointmentId(), patient.getSystemId(),
                doctor.getSystemId(), doctor.getSystemId(), "SCHEDULED", "NO_SHOW"));
        assertEquals("CANCELLED", statusOf(inProgress));

        publish(new AppointmentCancelledEvent(completed.getAppointmentId(), patient.getSystemId(), doctor.getSystemId(),
                null, null, fx.clinicNow(), null, true));
        assertEquals("COMPLETED", statusOf(completed));

        publish(new AppointmentStatusChangedEvent(untouched.getAppointmentId(), patient.getSystemId(),
                doctor.getSystemId(), doctor.getSystemId(), "SCHEDULED", "COMPLETED"));
        assertEquals("DRAFT", statusOf(untouched), "only NO_SHOW cancels");
    }

    // ==================== LEGACY ROUTES AND STATS ====================

    @Test
    void legacyDoctorListsAndStats_keepTheirShape() throws Exception {
        Consultation completedOld = fx.consultation(patient, doctor, "COMPLETED");
        Consultation draft = fx.consultation(patient, doctor, "DRAFT");
        Consultation inProgress = fx.consultation(patient, doctor, "IN_PROGRESS");
        fx.consultation(patient, doctor, "CANCELLED");
        Consultation completedNew = fx.consultation(patient, doctor, "COMPLETED");

        JsonNode active = json(doGet(DOC, doctor));
        assertTrue(active.isArray());
        assertEquals(List.of(inProgress.getConsultationId(), draft.getConsultationId(),
                completedNew.getConsultationId(), completedOld.getConsultationId()), ids(active));
        assertEquals(0, json(doGet(DOC, admin)).size());
        assertEquals(5, json(doGet(BASE + "/doctor/" + doctor.getSystemId(), doctor)).size());
        assertEquals(1, json(doGet(DOC + "/draft", doctor)).size());
        assertEquals(2, json(doGet(BASE + "/me/completed", doctor)).size());
        assertError(doGet(BASE + "/me/draft", caregiver), 400, "Unsupported user role");
        assertEquals(0, json(doGet(BASE + "/me/draft", patient)).size());

        JsonNode stats = json(doGet(DOC + "/stats", doctor));
        assertEquals(5, stats.path("totalConsultations").asInt());
        assertEquals(1, stats.path("draftCount").asInt());
        assertEquals(1, stats.path("inProgressCount").asInt());
        assertEquals(2, stats.path("completedCount").asInt());
        assertEquals(1, stats.path("cancelledCount").asInt());
        assertEquals(200.0 / 3, stats.path("completionRate").asDouble(), 1e-9);
        assertEquals(0, json(doGet(DOC + "/stats", admin)).path("totalConsultations").asInt());
        assertEquals(5, json(doGet(DOC + "/" + doctor.getSystemId() + "/stats", admin)).path("totalConsultations").asInt());
        assertEquals(0.0, json(doGet(DOC + "/" + otherDoctor.getSystemId() + "/stats", otherDoctor))
                .path("completionRate").asDouble());
        assertError(doGet(DOC + "/" + otherDoctor.getSystemId() + "/stats", doctor), 403, A8);
        assertError(doGet(DOC + "/stats", patient), 403, A9);
    }

    // ==================== HELPERS ====================

    private static Map<String, Object> body(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private void assertError(MvcResult result, int expectedStatus, String expectedMessage) throws Exception {
        assertEquals(expectedStatus, status(result), result.getResponse().getContentAsString());
        assertEquals(expectedMessage, message(result));
    }

    private void assertValidation(MvcResult result, String expectedMessage) throws Exception {
        assertError(result, 400, expectedMessage);
        assertEquals("VALIDATION_FAILED", json(result).path("code").asText());
    }

    private static void assertNullField(JsonNode node, String field) {
        assertTrue(node.has(field), field + " must be present: " + node);
        assertTrue(node.get(field).isNull(), field + " must be null: " + node);
    }

    private int auditRows(String action, Integer consultationId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? "
                + "AND resource_type = 'CONSULTATION' AND resource_id = ?", Integer.class, action, consultationId);
        return count == null ? 0 : count;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private String statusOf(Consultation consultation) {
        return consultationRepository.findById(consultation.getConsultationId()).orElseThrow().getStatus();
    }

    private static List<Integer> ids(JsonNode array) {
        List<Integer> ids = new ArrayList<>();
        array.forEach(n -> ids.add(n.path("consultationId").asInt()));
        return ids;
    }

    /** Publishes an appointment event inside a transaction, as AppointmentService does. */
    private void publish(Object event) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> eventPublisher.publishEvent(event));
    }

    private MedicalRecord linkedRecord(Consultation consultation) {
        LocalDateTime now = LocalDateTime.now();
        MedicalRecord record = medicalRecordRepository.saveAndFlush(MedicalRecord.builder()
                .patientSystemId(consultation.getPatientSystemId())
                .doctorSystemId(consultation.getDoctorSystemId())
                .consultationId(consultation.getConsultationId())
                .recordType("CONSULT")
                .title("Consultation")
                .isDeleted(false)
                .createdAt(now)
                .updatedAt(now)
                .build());
        assertNotNull(record.getRecordId());
        return record;
    }

    /** Runs the same request from {@code n} threads released together; returns the HTTP results. */
    private List<MvcResult> concurrently(int n, Callable<MvcResult> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return request.call();
                }));
            }
            start.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
