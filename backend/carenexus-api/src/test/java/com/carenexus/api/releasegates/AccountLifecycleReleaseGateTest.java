package com.carenexus.api.releasegates;

import com.carenexus.api.common.event.AppointmentCancelledEvent;
import com.carenexus.api.common.event.UserDeactivatedEvent;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.lifecycle.AccountLifecycleTestSupport;
import com.carenexus.api.lifecycle.service.AccountDeactivationEffectsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Release gate for the downstream handling of deactivated and closed accounts (be-account-lifecycle):
 * <ul>
 *   <li>an administrator deactivates a doctor with two future appointments: both are CANCELLED, with exactly one
 *       AppointmentCancelledEvent each (bySystem, the patient-safe reason), before the response is sent;</li>
 *   <li>a deactivated caregiver loses every grant and every conversation membership about the patients;</li>
 *   <li>a patient's own closure cancels the upcoming appointments and revokes the caregivers' grants;</li>
 *   <li>a failure in one step is recorded in the audit row and never undoes the deactivation or the other steps.</li>
 * </ul>
 * Runs with committed transactions, so the AFTER_COMMIT listener runs exactly as in production.
 */
@RecordApplicationEvents
class AccountLifecycleReleaseGateTest extends AccountLifecycleTestSupport {

    @Autowired
    private ApplicationEvents events;

    private List<AppointmentCancelledEvent> cancellations() {
        return events.stream(AppointmentCancelledEvent.class).toList();
    }

    @Test
    void deactivatedDoctor_hasBothFutureAppointmentsCancelled_withOneCancellationEventEach() throws Exception {
        Appointment first = appointment(patient, doctor, LocalDateTime.now().plusDays(1), "SCHEDULED");
        Appointment second = appointment(otherPatient, doctor, LocalDateTime.now().plusDays(3), "RESCHEDULED");
        Appointment past = appointment(patient, doctor, LocalDateTime.now().minusDays(1), "SCHEDULED");
        Appointment colleagues = appointment(patient, otherDoctor, LocalDateTime.now().plusDays(2), "SCHEDULED");

        MvcResult result = doPost("/api/admin/users/DOC-00001/deactivate", admin, Map.of("reason", "Left the practice"));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(2, json(result).path("futureActiveAppointments").asInt(), "counted before the clean-up");

        // The clean-up has finished when the response arrives.
        assertEquals("CANCELLED", statusOf(first));
        assertEquals("CANCELLED", statusOf(second));
        assertEquals("SCHEDULED", statusOf(past), "an appointment that has started is left for an outcome");
        assertEquals("SCHEDULED", statusOf(colleagues));

        List<AppointmentCancelledEvent> cancelled = cancellations();
        assertEquals(2, cancelled.size(), cancelled.toString());
        assertEquals(Set.of(first.getAppointmentId(), second.getAppointmentId()),
                cancelled.stream().map(AppointmentCancelledEvent::appointmentId).collect(Collectors.toSet()));
        for (AppointmentCancelledEvent event : cancelled) {
            assertTrue(event.bySystem());
            assertEquals("The doctor is no longer available", event.reason());
            assertEquals("DOC-00001", event.doctorSystemId());
            assertNull(event.actorSystemId());
            assertNull(event.actorRole());
        }

        Map<String, Object> audit = effectsRow("DOC-00001");
        assertEquals("ADM-00001", audit.get("system_id"));
        assertEquals("ADMIN", audit.get("actor_role"));
        assertEquals("ACCOUNT", audit.get("resource_type"));
        assertEquals("SUCCESS", audit.get("status"));
        assertNull(audit.get("patient_system_id"));
        assertEquals(summary("ADMIN", "DOCTOR", 2, 0, 0, 0, 0, "none"), audit.get("error_message"));

        // The administrator's account view no longer reports upcoming appointments.
        assertEquals(0, json(doGet("/api/admin/users/DOC-00001", admin)).path("futureActiveAppointments").asInt());
    }

    @Test
    void deactivatedCaregiver_losesEveryGrant_andEveryConversationMembership() throws Exception {
        DependentAccess grant = grantApprovedAccess(caregiver, patient);
        DependentAccess unrelated = grantApprovedAccess(otherCaregiver, patient);
        int threadId = careTeamThreadWithCaregiver(patient, doctor, caregiver);
        assertNull(removalReason(threadId, caregiver));

        deactivate(caregiver, "Requested by the family");

        DependentAccess stored = dependentAccessRepository.findById(grant.getAccessId()).orElseThrow();
        assertFalse(stored.getIsActive());
        assertNotNull(stored.getRevokedAt());
        assertEquals("ACCESS_REVOKED", removalReason(threadId, caregiver));
        assertEquals(1, count("SELECT COUNT(*) FROM thread_members WHERE thread_id = ? AND system_id = ? "
                + "AND removed_at IS NOT NULL", threadId, caregiver.getSystemId()));
        assertTrue(dependentAccessRepository.findById(unrelated.getAccessId()).orElseThrow().getIsActive(),
                "another caregiver's grant is untouched");

        Map<String, Object> audit = effectsRow("CGV-00001");
        assertEquals("SUCCESS", audit.get("status"));
        assertEquals(summary("ADMIN", "CAREGIVER", 0, 1, 0, 0, 0, "none"), audit.get("error_message"));
    }

    @Test
    void patientClosure_cancelsUpcomingAppointments_andRevokesCaregiverGrants() throws Exception {
        Appointment first = appointment(patient, doctor, LocalDateTime.now().plusDays(1), "SCHEDULED");
        Appointment second = appointment(patient, otherDoctor, LocalDateTime.now().plusDays(5), "SCHEDULED");
        Appointment othersVisit = appointment(otherPatient, doctor, LocalDateTime.now().plusDays(2), "SCHEDULED");
        DependentAccess grant = grantApprovedAccess(caregiver, patient);

        closeOwnAccount(patient);

        assertFalse(isActive(patient));
        assertEquals("CANCELLED", statusOf(first));
        assertEquals("CANCELLED", statusOf(second));
        assertEquals("SCHEDULED", statusOf(othersVisit));
        assertEquals(2, cancellations().size());
        cancellations().forEach(event ->
                assertEquals(AccountDeactivationEffectsService.PATIENT_CLOSED_REASON, event.reason()));
        assertEquals("The patient's CareNexus account was closed", AccountDeactivationEffectsService.PATIENT_CLOSED_REASON);

        DependentAccess stored = dependentAccessRepository.findById(grant.getAccessId()).orElseThrow();
        assertFalse(stored.getIsActive());
        assertNotNull(stored.getRevokedAt());

        Map<String, Object> audit = effectsRow("PAT-00001");
        assertEquals("PAT-00001", audit.get("system_id"));
        assertEquals("PATIENT", audit.get("actor_role"));
        assertEquals("PAT-00001", audit.get("patient_system_id"));
        assertEquals("SUCCESS", audit.get("status"));
        assertEquals(summary("CLOSURE", "PATIENT", 2, 1, 0, 0, 0, "none"), audit.get("error_message"));
    }

    @Test
    void aFailingStep_isRecorded_butNeverUndoesTheDeactivationOrTheOtherSteps() throws Exception {
        Appointment failing = appointment(patient, doctor, LocalDateTime.now().plusDays(1), "SCHEDULED");
        Appointment fine = appointment(otherPatient, doctor, LocalDateTime.now().plusDays(2), "SCHEDULED");
        pendingReferral(otherDoctor, doctor, patient, "PENDING");
        failWhenChanged("appointments", "appointment_id", failing.getAppointmentId());

        MvcResult result = doPost("/api/admin/users/DOC-00001/deactivate", admin, Map.of("reason", "Left"));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());

        assertFalse(isActive(doctor), "the deactivation stays committed");
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'USER_DEACTIVATE' AND target_system_id = 'DOC-00001'"));
        assertEquals("SCHEDULED", statusOf(failing), "the failed unit is rolled back alone");
        assertEquals("CANCELLED", statusOf(fine));
        assertEquals(List.of(fine.getAppointmentId()),
                cancellations().stream().map(AppointmentCancelledEvent::appointmentId).toList());

        Map<String, Object> audit = effectsRow("DOC-00001");
        assertEquals("FAILURE", audit.get("status"));
        assertEquals(summary("ADMIN", "DOCTOR", 1, 0, 0, 0, 1, "APPOINTMENTS:1"), audit.get("error_message"));
        assertEquals(List.of(new UserDeactivatedEvent("DOC-00001", "DOCTOR", "ADM-00001", "ADMIN")),
                events.stream(UserDeactivatedEvent.class).toList());
    }
}
