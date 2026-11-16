package com.carenexus.api.releasegates.crossflow;

import com.carenexus.api.calls.model.CallSession;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.appointmentStatus;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.auditRows;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.clinicNow;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.consultRecords;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.consultationStatus;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.count;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.insertAppointment;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.notificationsWithKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-slice gate: completing a consultation (be-consultations) runs, inside the one request transaction, the
 * appointment listener (be-appointments-core: appointment COMPLETED through the normal outcome path), the medical
 * record listener (be-medical-records: exactly one CONSULT record) and the clinical notification listener (N5), and
 * the appointment outcome syncs the call session through AppointmentCallListener (BEFORE_COMMIT): a call that has
 * not gone LIVE is ENDED with a DELETE_ROOM job, so it can no longer be started or joined. Marking NO_SHOW does the
 * same. A failure in any listener rolls the whole completion back.
 */
class ConsultationCompletionFlowGateTest extends CommittedIntegrationTest {

    private static final String CONSULTATIONS = "/api/consultations";
    private static final String DOCTOR_CONSULTATIONS = "/api/doctors/consultations";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String FAIL_FUNCTION = "crossflow_fail_consult_record";

    @AfterEach
    void dropFailureTrigger() {
        // TRUNCATE keeps triggers: every test removes the one it may have created.
        jdbc.execute("DROP TRIGGER IF EXISTS crossflow_fail_consult_record ON medical_records");
        jdbc.execute("DROP FUNCTION IF EXISTS " + FAIL_FUNCTION + "()");
    }

    // ==================== HELPERS ====================

    private int startedAppointment(String type) {
        return insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(),
                clinicNow().minusMinutes(20), type, "SCHEDULED");
    }

    private int openConsultation(int appointmentId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appointmentId", appointmentId);
        body.put("status", "IN_PROGRESS");
        body.put("chiefComplaint", "Persistent cough for three weeks");
        body.put("diagnosis", "Post-viral cough");
        body.put("clinicalNotes", "Chest clear on auscultation.");
        MvcResult created = doPost(CONSULTATIONS, doctor, body);
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        return json(created).path("consultationId").asInt();
    }

    private MvcResult complete(int consultationId) throws Exception {
        return doPatch(DOCTOR_CONSULTATIONS + "/" + consultationId + "/mark-completed", doctor, null);
    }

    private String callUrl(int appointmentId) {
        return "/api/appointments/" + appointmentId + "/call";
    }

    private String callStatus(int appointmentId) {
        return jdbc.queryForObject("SELECT status FROM call_sessions WHERE appointment_id = ?", String.class,
                appointmentId);
    }

    private int appointmentStatusEvents(int appointmentId) {
        return auditRows(jdbc, "UPDATE", "APPOINTMENT", appointmentId);
    }

    // ==================== COMPLETION CHAIN ====================

    @Test
    void completingTheConsultation_completesTheAppointment_createsOneConsultRecord_andNotifiesThePatientOnce()
            throws Exception {
        grantAccess(caregiver, patient, a -> a.setCanViewConsultations(true));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewAppointments(true));
        int appointmentId = startedAppointment("IN_PERSON");
        int consultationId = openConsultation(appointmentId);
        assertEquals(0, consultRecords(jdbc, consultationId));

        MvcResult completed = complete(consultationId);
        assertEquals(200, completed.getResponse().getStatus(), completed.getResponse().getContentAsString());
        JsonNode body = json(completed);
        assertEquals("COMPLETED", body.path("status").asText());
        assertEquals("COMPLETED", body.path("appointmentStatus").asText(), body.toString());
        assertFalse(body.path("medicalRecordId").isNull(), "the CONSULT record is linked in the same response");

        // Appointment listener: COMPLETED through the normal outcome path (one audit UPDATE row)
        assertEquals("COMPLETED", appointmentStatus(jdbc, appointmentId));
        assertEquals(1, appointmentStatusEvents(appointmentId));
        JsonNode appointment = json(doGet("/api/appointments/" + appointmentId, patient));
        assertEquals("COMPLETED", appointment.path("status").asText());

        // Record listener: exactly one CONSULT record, written by the system for the treating doctor
        assertEquals(1, consultRecords(jdbc, consultationId));
        Map<String, Object> record = jdbc.queryForMap("SELECT record_id, patient_system_id, doctor_system_id, source "
                + "FROM medical_records WHERE consultation_id = ?", consultationId);
        assertEquals(body.path("medicalRecordId").asInt(), record.get("record_id"));
        assertEquals(patient.getSystemId(), record.get("patient_system_id"));
        assertEquals(doctor.getSystemId(), record.get("doctor_system_id"));
        assertEquals("CONSULTATION", record.get("source"));
        MvcResult records = doGet("/api/medical-records?patientSystemId=" + patient.getSystemId()
                + "&consultationId=" + consultationId, patient);
        assertEquals(200, records.getResponse().getStatus(), records.getResponse().getContentAsString());
        assertEquals(1, json(records).path("totalElements").asInt());

        // Notification listener: N5 once to the patient and the caregiver with the consultations flag only
        String key = "CONSULT_COMPLETED:" + consultationId;
        assertEquals(1, notificationsWithKey(jdbc, patient.getSystemId(), key));
        assertEquals(1, notificationsWithKey(jdbc, caregiver.getSystemId(), key));
        assertEquals(0, notificationsWithKey(jdbc, otherCaregiver.getSystemId(), key));
        assertEquals(0, notificationsWithKey(jdbc, doctor.getSystemId(), key), "the doctor is the actor");

        // A repeated completion is a no-op: nothing is written again
        MvcResult again = complete(consultationId);
        assertEquals(200, again.getResponse().getStatus(), again.getResponse().getContentAsString());
        assertEquals(1, consultRecords(jdbc, consultationId));
        assertEquals(1, appointmentStatusEvents(appointmentId));
        assertEquals(1, notificationsWithKey(jdbc, patient.getSystemId(), key));
    }

    @Test
    void completionOfAVideoAppointment_closesTheCallThatHasNotStarted() throws Exception {
        // The doctor books a video visit that opens in a few minutes: the call session is prepared by the
        // AppointmentCallListener inside the booking transaction, and the job worker readies the room.
        MvcResult booked = doPost("/api/appointments", doctor, Map.of(
                "patientSystemId", patient.getSystemId(),
                "doctorSystemId", doctor.getSystemId(),
                "scheduledAt", slotInMinutes(10).format(ISO),
                "durationMinutes", 30,
                "type", "VIDEO",
                "reasonForVisit", "Follow-up"));
        assertEquals(201, booked.getResponse().getStatus(), booked.getResponse().getContentAsString());
        int appointmentId = json(booked).path("appointmentId").asInt();
        assertEquals(CallSession.PREPARING, callStatus(appointmentId));
        callJobService.runOnce();
        assertEquals(CallSession.READY, callStatus(appointmentId));

        // Time passes: the visit has started (the call was never started)
        LocalDateTime started = clinicNow().minusMinutes(5);
        jdbc.update("UPDATE appointments SET scheduled_at = ? WHERE appointment_id = ?",
                java.sql.Timestamp.valueOf(started), appointmentId);
        assertTrue(json(doGet(callUrl(appointmentId), doctor)).path("canStart").asBoolean(),
                "before completion the doctor can start the call");
        String room = jdbc.queryForObject("SELECT room_name FROM call_sessions WHERE appointment_id = ?",
                String.class, appointmentId);
        assertTrue(fakeCallProvider.createdRooms.contains(room));

        int consultationId = openConsultation(appointmentId);
        MvcResult completed = complete(consultationId);
        assertEquals(200, completed.getResponse().getStatus(), completed.getResponse().getContentAsString());
        assertEquals("COMPLETED", appointmentStatus(jdbc, appointmentId));
        assertEquals(1, consultRecords(jdbc, consultationId));

        // Consultation completed -> appointment COMPLETED -> AppointmentCallListener (BEFORE_COMMIT): the session
        // that never went LIVE is ended in the same transaction and a DELETE_ROOM job is queued
        assertEquals(CallSession.ENDED, callStatus(appointmentId));
        assertEquals(CallSession.END_APPOINTMENT_ENDED, jdbc.queryForObject(
                "SELECT end_reason FROM call_sessions WHERE appointment_id = ?", String.class, appointmentId));
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM call_jobs j JOIN call_sessions s "
                + "ON s.call_session_id = j.call_session_id WHERE s.appointment_id = ? AND j.job_type = 'DELETE_ROOM' "
                + "AND j.room_name = ?", appointmentId, room));
        callJobService.runOnce();
        assertTrue(fakeCallProvider.deletedRooms.contains(room), "the provider room is deleted by the job worker");

        // Nobody can start or join the call any more
        JsonNode status = json(doGet(callUrl(appointmentId), doctor));
        assertEquals(CallSession.ENDED, status.path("callStatus").asText(), status.toString());
        assertFalse(status.path("canStart").asBoolean(), status.toString());
        JsonNode patientView = json(doGet(callUrl(appointmentId), patient));
        assertEquals(CallSession.ENDED, patientView.path("callStatus").asText(), patientView.toString());
        assertFalse(patientView.path("canRequestJoin").asBoolean(), patientView.toString());
        MvcResult start = doPost(callUrl(appointmentId) + "/start", doctor, null);
        assertEquals(409, start.getResponse().getStatus(), start.getResponse().getContentAsString());
        assertEquals("This call has ended", message(start));
        MvcResult join = doPost(callUrl(appointmentId) + "/join-request", patient, null);
        assertEquals(409, join.getResponse().getStatus(), join.getResponse().getContentAsString());
        assertEquals("This call has ended", message(join));
        assertEquals(0, fakeCallProvider.issuedTokens.size());

        // The appointment reads the ended call
        JsonNode appointment = json(doGet("/api/appointments/" + appointmentId, patient));
        assertEquals(CallSession.ENDED, appointment.path("callStatus").asText(), appointment.toString());
    }

    @Test
    void markingAVideoAppointmentNoShow_endsTheCallThatHasNotStarted_andTheResponseShowsIt() throws Exception {
        int appointmentId = insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(),
                clinicNow().minusMinutes(5), "VIDEO", "SCHEDULED");
        // A legacy session inserted outside the app is created on the first read (NEEDS_TIME_CONFIRMATION)
        JsonNode before = json(doGet(callUrl(appointmentId), doctor));
        assertEquals(CallSession.NEEDS_TIME_CONFIRMATION, before.path("callStatus").asText(), before.toString());

        MvcResult noShow = doPatch("/api/appointments/" + appointmentId + "/status?status=NO_SHOW", doctor, null);
        assertEquals(200, noShow.getResponse().getStatus(), noShow.getResponse().getContentAsString());
        assertEquals("NO_SHOW", json(noShow).path("status").asText());
        assertEquals(CallSession.ENDED, json(noShow).path("callStatus").asText(), "the response shows the call as it commits");
        assertEquals(CallSession.ENDED, callStatus(appointmentId));
        assertFalse(json(doGet(callUrl(appointmentId), patient)).path("canRequestJoin").asBoolean());
    }

    @Test
    void aFailingCompletionListener_rollsTheWholeCompletionBack() throws Exception {
        int appointmentId = startedAppointment("IN_PERSON");
        int consultationId = openConsultation(appointmentId);
        // The CONSULT record insert fails in the database: the record listener throws inside the completion
        jdbc.execute("CREATE OR REPLACE FUNCTION " + FAIL_FUNCTION + "() RETURNS trigger AS $$ BEGIN "
                + "RAISE EXCEPTION 'crossflow test failure'; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER crossflow_fail_consult_record BEFORE INSERT ON medical_records "
                + "FOR EACH ROW EXECUTE FUNCTION " + FAIL_FUNCTION + "()");

        MvcResult failed = complete(consultationId);
        assertEquals(500, failed.getResponse().getStatus(), failed.getResponse().getContentAsString());

        assertEquals("IN_PROGRESS", consultationStatus(jdbc, consultationId), "the completion is rolled back");
        assertEquals("SCHEDULED", appointmentStatus(jdbc, appointmentId), "the appointment change is rolled back");
        assertEquals(0, appointmentStatusEvents(appointmentId));
        assertEquals(0, consultRecords(jdbc, consultationId));
        assertEquals(0, notificationsWithKey(jdbc, patient.getSystemId(), "CONSULT_COMPLETED:" + consultationId));
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM consultations WHERE consultation_id = ? "
                + "AND completed_at IS NOT NULL", consultationId));

        // Once the failure is gone the same request completes the whole chain
        dropFailureTrigger();
        MvcResult completed = complete(consultationId);
        assertEquals(200, completed.getResponse().getStatus(), completed.getResponse().getContentAsString());
        assertEquals("COMPLETED", appointmentStatus(jdbc, appointmentId));
        assertEquals(1, consultRecords(jdbc, consultationId));
        assertEquals(1, notificationsWithKey(jdbc, patient.getSystemId(), "CONSULT_COMPLETED:" + consultationId));
    }
}
