package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.communications.listener.clinical.StoredNotifications;
import com.carenexus.api.communications.listener.clinical.StoredNotifications.Row;
import com.carenexus.api.core.service.AppointmentService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.carenexus.api.core.appointments.AppointmentFixtures.body;
import static com.carenexus.api.core.appointments.AppointmentFixtures.booking;
import static com.carenexus.api.core.appointments.AppointmentFixtures.clinicDay;
import static com.carenexus.api.core.appointments.AppointmentFixtures.wallClock;
import static com.carenexus.api.core.results.ClinicalResultFixtures.labBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Release gate of the clinical notification flows (be-clinical-notifications): real HTTP requests that commit, so
 * the domain events, the clinical notification listeners, the publisher (actor rule, toggles, dedupe) and the
 * notification fan-out all run as in production.
 *
 * Assertions are per notification type (APPOINTMENT for N1 to N4, LAB_RESULT for N9 and N10): other slices may
 * address the same people with other types for the same action (for example the doctor's AI_ALERT for a critical
 * result), and the combined doctor-side assertion belongs to the integration gates of waves 4 and 5.
 */
class NotificationFlowReleaseGateTest extends CommittedIntegrationTest {

    private static final String APPOINTMENTS = "/api/appointments";
    private static final String LAB = "/api/lab-results";
    private static final String NOTIFICATIONS = "/api/notifications";
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm", Locale.ENGLISH);
    private static final String APPOINTMENT = "APPOINTMENT";
    private static final String LAB_RESULT = "LAB_RESULT";

    @Autowired
    private AppointmentService appointmentService;

    private StoredNotifications stored;
    private String patientName;
    private String doctorName;

    @BeforeEach
    void setUpStore() {
        stored = new StoredNotifications(jdbc);
        patientName = patient.getFirstName() + " " + patient.getLastName();
        doctorName = "Dr. " + doctor.getFirstName() + " " + doctor.getLastName();
    }

    // ==================== HELPERS ====================

    private int book(User as, User forPatient, LocalDateTime at) throws Exception {
        // A patient books for themselves without a patient id; a caregiver names the patient
        String patientId = "PATIENT".equals(as.getRole()) ? null : forPatient.getSystemId();
        MvcResult result = doPost(APPOINTMENTS, as, booking(patientId, doctor.getSystemId(), at, "IN_PERSON"));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result).path("appointmentId").asInt();
    }

    private int createLab(Map<String, Object> overrides) throws Exception {
        Map<String, Object> request = new HashMap<>(labBody(patient.getSystemId()));
        request.putAll(overrides);
        MvcResult result = doPost(LAB, doctor, request);
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result).path("resultId").asInt();
    }

    private List<Row> appointmentRows(User recipient) {
        return stored.of(recipient.getSystemId(), APPOINTMENT);
    }

    private List<Row> labRows(User recipient) {
        return stored.of(recipient.getSystemId(), LAB_RESULT);
    }

    private Row only(List<Row> rows) {
        assertEquals(1, rows.size(), rows.toString());
        return rows.get(0);
    }

    private void registerDevice(User user, String token) {
        jdbc.update("INSERT INTO push_devices (system_id, platform, token) VALUES (?, 'ANDROID', ?)",
                user.getSystemId(), token);
    }

    private int outboundRowsFor(User recipient) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM outbound_messages WHERE recipient_system_id = ?",
                Integer.class, recipient.getSystemId());
        return count == null ? 0 : count;
    }

    // ==================== APPOINTMENTS (N1 to N3) ====================

    @Test
    void patientBooks_onlyTheDoctorIsNotified_once_andSeesItInTheList() throws Exception {
        LocalDateTime at = clinicDay(2, 10, 0);
        int id = book(patient, patient, at);

        Row toDoctor = only(appointmentRows(doctor));
        assertEquals("Appointment booked", toDoctor.title());
        assertEquals(patientName + " has a new appointment with you on " + WHEN.format(at), toDoctor.message());
        assertEquals("APPOINTMENT_CHANGE", toDoctor.category());
        assertEquals("NORMAL", toDoctor.priority());
        assertEquals("/dashboard/doctor?section=appointments&id=" + id, toDoctor.actionUrl());
        assertEquals("APPT_BOOKED:" + id, toDoctor.dedupeKey());
        assertTrue(appointmentRows(patient).isEmpty(), "the patient booked: not notified");
        assertTrue(appointmentRows(otherDoctor).isEmpty());
        assertTrue(appointmentRows(otherPatient).isEmpty());

        // The doctor reads it through the notifications API with the contract shape
        MvcResult list = doGet(NOTIFICATIONS + "?type=APPOINTMENT", doctor);
        assertEquals(200, list.getResponse().getStatus(), list.getResponse().getContentAsString());
        JsonNode content = json(list).path("content");
        assertEquals(1, content.size(), content.toString());
        JsonNode row = content.get(0);
        assertEquals("Appointment booked", row.path("title").asText());
        assertEquals("APPOINTMENTS", row.path("target").asText());
        assertEquals("APPOINTMENT", row.path("resourceType").asText());
        assertEquals(id, row.path("resourceId").asInt());
        assertEquals(patient.getSystemId(), row.path("patientSystemId").asText());
        assertEquals("/dashboard/doctor?section=appointments&id=" + id, row.path("actionUrl").asText());
    }

    @Test
    void caregiverBooks_patientAndDoctorAreNotified_notTheCaregiver() throws Exception {
        grantAccess(caregiver, patient, a -> {
            a.setCanViewAppointments(true);
            a.setCanScheduleAppointments(true);
        });
        grantAccess(otherCaregiver, patient, a -> a.setCanViewAppointments(true));
        LocalDateTime at = clinicDay(3, 11, 30);
        int id = book(caregiver, patient, at);

        assertEquals("An appointment with " + doctorName + " was booked for you on " + WHEN.format(at),
                only(appointmentRows(patient)).message());
        assertEquals("/dashboard/patient?section=my-appointments&id=" + id, only(appointmentRows(patient)).actionUrl());
        assertEquals(patientName + " has a new appointment with you on " + WHEN.format(at),
                only(appointmentRows(doctor)).message());
        Row toOtherCaregiver = only(appointmentRows(otherCaregiver));
        assertEquals("An appointment with " + doctorName + " was booked for " + patientName + " on " + WHEN.format(at),
                toOtherCaregiver.message());
        assertEquals("/dashboard/proxy?section=appointments&patient=" + patient.getSystemId() + "&id=" + id,
                toOtherCaregiver.actionUrl());
        assertTrue(appointmentRows(caregiver).isEmpty(), "the booking caregiver is the actor");
    }

    @Test
    void cancelAndReschedule_notifyTheOtherParty() throws Exception {
        LocalDateTime first = clinicDay(2, 9, 0);
        int cancelled = book(patient, patient, first);
        LocalDateTime second = clinicDay(4, 14, 0);
        int moved = book(patient, patient, second);

        // The patient cancels: the doctor is told, the patient is not
        MvcResult cancel = doDelete(APPOINTMENTS + "/" + cancelled, patient);
        assertEquals(204, cancel.getResponse().getStatus(), cancel.getResponse().getContentAsString());
        Row cancelledForDoctor = only(stored.titled(doctor.getSystemId(), "Appointment cancelled"));
        assertEquals("Your appointment with " + patientName + " on " + WHEN.format(first) + " was cancelled",
                cancelledForDoctor.message());
        assertEquals("APPT_CANCELLED:" + cancelled, cancelledForDoctor.dedupeKey());
        assertTrue(stored.titled(patient.getSystemId(), "Appointment cancelled").isEmpty());

        // The doctor reschedules: the patient is told, the doctor is not
        LocalDateTime newTime = clinicDay(5, 15, 30);
        MvcResult reschedule = doPatch(APPOINTMENTS + "/" + moved + "/reschedule", doctor,
                body("scheduledAt", wallClock(newTime)));
        assertEquals(200, reschedule.getResponse().getStatus(), reschedule.getResponse().getContentAsString());
        Row rescheduledForPatient = only(stored.titled(patient.getSystemId(), "Appointment rescheduled"));
        assertEquals("Your appointment with " + doctorName + " was moved to " + WHEN.format(newTime),
                rescheduledForPatient.message());
        assertTrue(stored.titled(doctor.getSystemId(), "Appointment rescheduled").isEmpty());

        // A repeated cancel is a no-op and notifies nobody again
        assertEquals(204, doDelete(APPOINTMENTS + "/" + cancelled, patient).getResponse().getStatus());
        assertEquals(1, stored.titled(doctor.getSystemId(), "Appointment cancelled").size());
    }

    @Test
    void systemCancellation_notifiesDoctorAndPatientCircleAtHighPriorityWithTheReason() throws Exception {
        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        LocalDateTime at = clinicDay(2, 10, 0);
        int id = book(patient, patient, at);

        appointmentService.cancelBySystem(id, "The doctor is no longer available");

        for (User recipient : List.of(doctor, patient, caregiver)) {
            Row row = only(stored.titled(recipient.getSystemId(), "Appointment cancelled"));
            assertEquals("HIGH", row.priority(), row.toString());
            assertTrue(row.message().endsWith(" on " + WHEN.format(at)
                    + " was cancelled. Reason: The doctor is no longer available"), row.message());
        }
        assertEquals("Your appointment with " + doctorName + " on " + WHEN.format(at)
                        + " was cancelled. Reason: The doctor is no longer available",
                only(stored.titled(patient.getSystemId(), "Appointment cancelled")).message());
    }

    @Test
    void switchedOffToggle_suppressesThatRecipientOnly_andQueuesNoOutboundMessage() throws Exception {
        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        stored.setting(doctor.getSystemId(), "appointment_change_alerts", false);

        book(patient, patient, clinicDay(2, 10, 0));

        assertTrue(appointmentRows(doctor).isEmpty(), "the doctor switched appointment changes off");
        assertEquals(0, outboundRowsFor(doctor));
        assertEquals(1, appointmentRows(caregiver).size(), "other recipients are unaffected");
    }

    // ==================== RESULTS (N9, N10) ====================

    @Test
    void completedLabResult_caregiverWithoutTheLabFlagReceivesNothing() throws Exception {
        grantAccess(caregiver, patient, a -> {
            a.setCanViewAppointments(true);
            a.setCanViewImaging(true);
            a.setCanViewRecords(true);
        });

        int pending = createLab(Map.of());
        assertTrue(labRows(patient).isEmpty(), "a pending result tells nobody");

        MvcResult completed = doPatch(LAB + "/" + pending + "/status?status=COMPLETED", doctor, null);
        assertEquals(200, completed.getResponse().getStatus(), completed.getResponse().getContentAsString());

        Row toPatient = only(labRows(patient));
        assertEquals("New lab result", toPatient.title());
        assertEquals("A new lab result is available in your records", toPatient.message());
        assertEquals("NORMAL", toPatient.priority());
        assertEquals("/dashboard/patient?section=lab-results&id=" + pending, toPatient.actionUrl());
        assertTrue(labRows(caregiver).isEmpty(), "no can_view_lab_results and no FULL_ACCESS");
        assertTrue(labRows(doctor).isEmpty());
    }

    @Test
    void criticalLabResult_oneHighNotificationPerCircleMember_noneOnRepeat_noneForAnyDoctor() throws Exception {
        User fullAccessCaregiver = createAndSaveUser(8, "CGV-00003", "full.cg@example.com", "CAREGIVER");
        grantAccess(caregiver, patient, a -> a.setCanViewLabResults(true));
        grantAccess(fullAccessCaregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewAppointments(true));
        // Channels: the patient switched SMS off (no push device); the lab caregiver has a push device
        stored.setting(patient.getSystemId(), "sms_notifications", false);
        registerDevice(caregiver, "device-token-caregiver-flow-0001");

        int id = createLab(Map.of("status", "COMPLETED", "isCritical", true));

        for (User member : List.of(patient, caregiver, fullAccessCaregiver)) {
            Row row = only(labRows(member));
            assertEquals("HIGH", row.priority(), row.toString());
            assertEquals("LAB_RESULT", row.category());
            assertEquals("Important lab result", row.title());
            assertEquals("RESULT_CRITICAL:LAB:" + id, row.dedupeKey());
        }
        assertEquals("A lab result needs your attention. Please contact your care team.",
                only(labRows(patient)).message());
        assertEquals("A lab result for " + patientName + " needs attention. Please contact the care team.",
                only(labRows(caregiver)).message());
        assertEquals("/dashboard/proxy?section=documents-results&patient=" + patient.getSystemId() + "&id=" + id
                + "&tab=labs", only(labRows(caregiver)).actionUrl());
        assertTrue(labRows(otherCaregiver).isEmpty(), "no lab flag");
        assertTrue(labRows(doctor).isEmpty(), "doctors are reached only through the AI alert");
        assertTrue(labRows(otherDoctor).isEmpty());
        assertTrue(stored.all().stream().noneMatch(row -> row.message().contains("10.8")
                || row.message().contains("haemoglobin")), "no result value in any text");

        // A repeated update of the critical result notifies nobody again
        MvcResult update = doPut(LAB + "/" + id, doctor, Map.of("unit", "g/dL"));
        assertEquals(200, update.getResponse().getStatus(), update.getResponse().getContentAsString());
        for (User member : List.of(patient, caregiver, fullAccessCaregiver)) {
            assertEquals(1, labRows(member).size(), member.getSystemId());
        }
        assertTrue(labRows(doctor).isEmpty());

        // outbound_messages rows exist only for the channels enabled for each recipient
        assertEquals(List.of("EMAIL"), stored.channels(only(labRows(patient)).notificationId()));
        assertEquals(List.of("EMAIL", "SMS", "PUSH"), stored.channels(only(labRows(caregiver)).notificationId()));
        assertEquals(List.of("EMAIL", "SMS"), stored.channels(only(labRows(fullAccessCaregiver)).notificationId()));
        assertEquals(0, outboundRowsFor(otherCaregiver));
    }
}
