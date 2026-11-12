package com.carenexus.api.releasegates.crossflow;

import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.appointmentStatus;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.auditRows;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.clinicNow;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.consultationStatus;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.insertAppointment;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cross-slice gate: appointment changes (be-appointments-core) keep the appointment's consultation
 * (be-consultations) consistent through ConsultationAppointmentListener, over HTTP with committed transactions.
 * Cancelling or marking NO_SHOW cancels a DRAFT or IN_PROGRESS consultation in the same transaction; once a
 * COMPLETED consultation exists the appointment can be neither cancelled nor marked NO_SHOW.
 */
class AppointmentConsultationConsistencyGateTest extends CommittedIntegrationTest {

    private static final String APPOINTMENTS = "/api/appointments";
    private static final String CONSULTATIONS = "/api/consultations";
    private static final String COMPLETED_CANCEL =
            "This appointment has a completed consultation and can no longer be cancelled";
    private static final String COMPLETED_NO_SHOW =
            "This appointment has a completed consultation and cannot be marked as no-show";

    private int startedAppointment(int minutesAgo) {
        return insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(),
                clinicNow().minusMinutes(minutesAgo), "IN_PERSON", "SCHEDULED");
    }

    private int openConsultation(int appointmentId, String status) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appointmentId", appointmentId);
        body.put("status", status);
        body.put("chiefComplaint", "Headache");
        body.put("diagnosis", "Tension-type headache");
        MvcResult created = doPost(CONSULTATIONS, doctor, body);
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        return json(created).path("consultationId").asInt();
    }

    private void assertStatus(MvcResult result, int expected) throws Exception {
        assertEquals(expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    @Test
    void cancellingTheAppointment_cancelsItsDraftConsultation_inTheSameRequest() throws Exception {
        int appointmentId = startedAppointment(30);
        int draft = openConsultation(appointmentId, "DRAFT");

        assertStatus(doDelete(APPOINTMENTS + "/" + appointmentId, doctor), 204);

        assertEquals("CANCELLED", appointmentStatus(jdbc, appointmentId));
        assertEquals("CANCELLED", consultationStatus(jdbc, draft));
        assertEquals(1, auditRows(jdbc, "UPDATE", "CONSULTATION", draft), "the system cancellation is audited");
        // The doctor sees it as cancelled, and the cancelled appointment cannot get a new consultation
        assertEquals("CANCELLED", json(doGet(CONSULTATIONS + "/" + draft, doctor)).path("status").asText());
        MvcResult reopen = doPost(CONSULTATIONS, doctor, Map.of("appointmentId", appointmentId));
        assertStatus(reopen, 409);
        assertEquals("Cannot create a consultation for a cancelled appointment", message(reopen));
    }

    @Test
    void cancellingThroughTheStatusRoute_alsoCancelsAnInProgressConsultation() throws Exception {
        int appointmentId = startedAppointment(30);
        int inProgress = openConsultation(appointmentId, "IN_PROGRESS");

        assertStatus(doPatch(APPOINTMENTS + "/" + appointmentId + "/status?status=CANCELLED", doctor, null), 200);

        assertEquals("CANCELLED", appointmentStatus(jdbc, appointmentId));
        assertEquals("CANCELLED", consultationStatus(jdbc, inProgress));
    }

    @Test
    void markingNoShow_cancelsTheOpenConsultation() throws Exception {
        int appointmentId = startedAppointment(45);
        int inProgress = openConsultation(appointmentId, "IN_PROGRESS");

        assertStatus(doPatch(APPOINTMENTS + "/" + appointmentId + "/status?status=NO_SHOW", doctor, null), 200);

        assertEquals("NO_SHOW", appointmentStatus(jdbc, appointmentId));
        assertEquals("CANCELLED", consultationStatus(jdbc, inProgress));
        MvcResult reopen = doPost(CONSULTATIONS, doctor, Map.of("appointmentId", appointmentId));
        assertStatus(reopen, 409);
        assertEquals("Cannot create a consultation for a missed appointment", message(reopen));
    }

    @Test
    void aCompletedConsultation_blocksCancelAndNoShow_forEveryCaller() throws Exception {
        grantAccess(caregiver, patient, a -> {
            a.setCanViewAppointments(true);
            a.setCanScheduleAppointments(true);
        });
        // Completed through HTTP: the appointment is COMPLETED by the chain and is now terminal
        int completedVisit = startedAppointment(60);
        int consultationId = openConsultation(completedVisit, "IN_PROGRESS");
        assertStatus(doPatch(CONSULTATIONS + "/" + consultationId + "/status?status=COMPLETED", doctor, null), 200);
        assertEquals("COMPLETED", appointmentStatus(jdbc, completedVisit));
        assertStatus(doDelete(APPOINTMENTS + "/" + completedVisit, doctor), 409);
        assertStatus(doPatch(APPOINTMENTS + "/" + completedVisit + "/status?status=NO_SHOW", doctor, null), 409);
        assertEquals("COMPLETED", appointmentStatus(jdbc, completedVisit));
        assertEquals("COMPLETED", consultationStatus(jdbc, consultationId));

        // An appointment that is still active but already has a COMPLETED consultation (a consultation completed
        // before the appointment rule existed, or an appointment set back by data repair) is protected as well.
        LocalDateTime future = clinicNow().plusDays(2).withHour(10).withMinute(0);
        int stillActive = insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(), future, "IN_PERSON",
                "SCHEDULED");
        jdbc.update("INSERT INTO consultations (appointment_id, patient_system_id, doctor_system_id, chief_complaint, "
                + "diagnosis, status, follow_up_required, created_at, completed_at) VALUES (?, ?, ?, 'Review', "
                + "'Stable', 'COMPLETED', FALSE, LOCALTIMESTAMP, LOCALTIMESTAMP)", stillActive, patient.getSystemId(),
                doctor.getSystemId());

        MvcResult byDoctor = doDelete(APPOINTMENTS + "/" + stillActive, doctor);
        assertStatus(byDoctor, 409);
        assertEquals(COMPLETED_CANCEL, message(byDoctor));
        MvcResult byPatient = doDelete(APPOINTMENTS + "/" + stillActive, patient);
        assertStatus(byPatient, 409);
        assertEquals(COMPLETED_CANCEL, message(byPatient));
        MvcResult byCaregiver = doPatch(APPOINTMENTS + "/" + stillActive + "/status?status=CANCELLED", caregiver, null);
        assertStatus(byCaregiver, 409);
        assertEquals(COMPLETED_CANCEL, message(byCaregiver));
        MvcResult byAdmin = doDelete(APPOINTMENTS + "/" + stillActive, admin);
        assertStatus(byAdmin, 409);
        assertEquals(COMPLETED_CANCEL, message(byAdmin));
        assertEquals("SCHEDULED", appointmentStatus(jdbc, stillActive));

        int pastActive = startedAppointment(90);
        jdbc.update("INSERT INTO consultations (appointment_id, patient_system_id, doctor_system_id, chief_complaint, "
                + "diagnosis, status, follow_up_required, created_at, completed_at) VALUES (?, ?, ?, 'Review', "
                + "'Stable', 'COMPLETED', FALSE, LOCALTIMESTAMP, LOCALTIMESTAMP)", pastActive, patient.getSystemId(),
                doctor.getSystemId());
        MvcResult noShow = doPatch(APPOINTMENTS + "/" + pastActive + "/status?status=NO_SHOW", doctor, null);
        assertStatus(noShow, 409);
        assertEquals(COMPLETED_NO_SHOW, message(noShow));
        assertEquals("SCHEDULED", appointmentStatus(jdbc, pastActive));
    }
}
