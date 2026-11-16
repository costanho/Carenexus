package com.carenexus.api.releasegates.crossflow;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.lifecycle.AccountLifecycleTestSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.notificationsOf;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.notificationsWithKey;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.notificationsWithKeyPrefix;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-slice gate: account deactivation and closure (be-account-lifecycle, AFTER_COMMIT clean-up) cancel
 * appointments through AppointmentService.cancelBySystem (be-appointments-core), which notifies through
 * be-clinical-notifications (N3, HIGH, with the reason), keeps consultations consistent and revokes grants through
 * the caregiver module (be-caregiver-requests-consent: consent ledger and its access-change notification).
 */
class AccountLifecycleFlowGateTest extends AccountLifecycleTestSupport {

    private static final String CANCELLED_KEY = "APPT_CANCELLED:";

    private static Map<String, Object> only(List<Map<String, Object>> rows, String what) {
        assertEquals(1, rows.size(), what + ": " + rows);
        return rows.get(0);
    }

    private Map<String, Object> cancellationOf(User recipient, Appointment appointment) {
        return only(notificationsOf(jdbc, recipient.getSystemId()).stream()
                .filter(row -> (CANCELLED_KEY + appointment.getAppointmentId()).equals(row.get("dedupe_key")))
                .toList(), recipient.getSystemId());
    }

    @Test
    void deactivatedDoctor_bothFutureAppointmentsCancelled_eachPatientNotifiedOnce() throws Exception {
        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        Appointment first = appointment(patient, doctor, LocalDateTime.now().plusDays(1), "SCHEDULED");
        Appointment second = appointment(otherPatient, doctor, LocalDateTime.now().plusDays(3), "RESCHEDULED");
        Appointment started = appointment(patient, doctor, LocalDateTime.now().minusHours(1), "SCHEDULED");
        // A draft consultation of the started visit is not touched: that appointment is left for an outcome
        jdbc.update("INSERT INTO consultations (appointment_id, patient_system_id, doctor_system_id, status, "
                + "follow_up_required, created_at) VALUES (?, ?, ?, 'DRAFT', FALSE, LOCALTIMESTAMP)",
                started.getAppointmentId(), patient.getSystemId(), doctor.getSystemId());

        deactivate(doctor, "Left the practice");

        assertEquals("CANCELLED", statusOf(first));
        assertEquals("CANCELLED", statusOf(second));
        assertEquals("SCHEDULED", statusOf(started));
        assertEquals("DRAFT", jdbc.queryForObject("SELECT status FROM consultations WHERE appointment_id = ?",
                String.class, started.getAppointmentId()));

        // Each patient: exactly one cancellation notice, HIGH, with the patient-safe reason
        for (Object[] pair : new Object[][]{{patient, first}, {otherPatient, second}}) {
            User who = (User) pair[0];
            Appointment appointment = (Appointment) pair[1];
            Map<String, Object> row = cancellationOf(who, appointment);
            assertEquals("Appointment cancelled", row.get("title"));
            assertEquals("HIGH", row.get("priority"));
            assertEquals("APPOINTMENT", row.get("type"));
            assertTrue(((String) row.get("message")).endsWith("Reason: The doctor is no longer available"),
                    (String) row.get("message"));
            assertEquals(1, notificationsWithKeyPrefix(jdbc, who.getSystemId(), CANCELLED_KEY), who.getSystemId());
        }
        // The caregiver with the appointments flag hears about the patient's appointment only
        assertEquals(1, notificationsWithKey(jdbc, caregiver.getSystemId(), CANCELLED_KEY + first.getAppointmentId()));
        assertEquals(1, notificationsWithKeyPrefix(jdbc, caregiver.getSystemId(), CANCELLED_KEY));
        // The deactivated doctor is among the recipients (nothing filters the inactive account)
        assertEquals(2, notificationsWithKeyPrefix(jdbc, doctor.getSystemId(), CANCELLED_KEY));
        assertEquals(0, notificationsWithKeyPrefix(jdbc, otherDoctor.getSystemId(), CANCELLED_KEY));
        assertEquals(0, notificationsWithKeyPrefix(jdbc, admin.getSystemId(), CANCELLED_KEY));

        // The patient sees the cancelled appointment through the API
        assertEquals("CANCELLED", json(doGet("/api/appointments/" + first.getAppointmentId(), patient))
                .path("status").asText());
    }

    @Test
    void patientClosure_cancelsTheAppointments_thenRevokesTheGrants_notifyingEachPartyOnce() throws Exception {
        DependentAccess grant = grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        DependentAccess quietGrant = grantAccess(otherCaregiver, patient, a -> a.setCanViewMedications(true));
        Appointment withDoctor = appointment(patient, doctor, LocalDateTime.now().plusDays(1), "SCHEDULED");
        Appointment withColleague = appointment(patient, otherDoctor, LocalDateTime.now().plusDays(4), "SCHEDULED");

        closeOwnAccount(patient);

        assertFalse(isActive(patient));
        assertEquals("CANCELLED", statusOf(withDoctor));
        assertEquals("CANCELLED", statusOf(withColleague));
        for (DependentAccess access : List.of(grant, quietGrant)) {
            DependentAccess stored = dependentAccessRepository.findById(access.getAccessId()).orElseThrow();
            assertFalse(stored.getIsActive());
            assertNotNull(stored.getRevokedAt());
        }

        // Each doctor: one cancellation of their own appointment, with the closure reason
        Map<String, Object> toDoctor = cancellationOf(doctor, withDoctor);
        assertTrue(((String) toDoctor.get("message")).endsWith("Reason: The patient's CareNexus account was closed"),
                (String) toDoctor.get("message"));
        assertEquals(1, notificationsWithKeyPrefix(jdbc, doctor.getSystemId(), CANCELLED_KEY));
        cancellationOf(otherDoctor, withColleague);
        assertEquals(1, notificationsWithKeyPrefix(jdbc, otherDoctor.getSystemId(), CANCELLED_KEY));
        // The closed patient is among the recipients
        assertEquals(2, notificationsWithKeyPrefix(jdbc, patient.getSystemId(), CANCELLED_KEY));

        // The caregiver with the appointments flag: both cancellations first, then the revocation, each once
        List<Map<String, Object>> caregiverRows = notificationsOf(jdbc, caregiver.getSystemId());
        List<String> titles = caregiverRows.stream().map(row -> (String) row.get("title")).toList();
        assertEquals(List.of("Appointment cancelled", "Appointment cancelled", "Your access was removed"), titles,
                caregiverRows.toString());
        // The caregiver without the flag hears only about the revocation
        List<String> quietTitles = notificationsOf(jdbc, otherCaregiver.getSystemId()).stream()
                .map(row -> (String) row.get("title")).toList();
        assertEquals(List.of("Your access was removed"), quietTitles);

        // The revoked caregivers can no longer read the patient's appointments
        assertEquals(403, doGet("/api/appointments/" + withDoctor.getAppointmentId(), caregiver)
                .getResponse().getStatus());
    }
}
