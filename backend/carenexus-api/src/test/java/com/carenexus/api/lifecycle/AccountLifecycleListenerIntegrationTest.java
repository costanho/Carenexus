package com.carenexus.api.lifecycle;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.event.AppointmentCancelledEvent;
import com.carenexus.api.common.event.CaregiverGrantRecordedEvent;
import com.carenexus.api.common.event.UserDeactivatedEvent;
import com.carenexus.api.common.event.UserReactivatedEvent;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.core.repository.ConsultationRepository;
import com.carenexus.api.lifecycle.service.AccountDeactivationEffectsService;
import com.carenexus.api.lifecycle.service.DeactivationEffects;
import com.carenexus.api.lifecycle.service.DeactivationStep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests of the account clean-up (api/be-account-lifecycle.md 5.2 to 5.5): the selection rules per step,
 * paging, each publisher of UserDeactivatedEvent, events without an actor, repeated events, reactivation and the
 * audit row.
 */
@RecordApplicationEvents
class AccountLifecycleListenerIntegrationTest extends AccountLifecycleTestSupport {

    @Autowired
    private ApplicationEvents events;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private ConsultationRepository consultationRepository;

    @Autowired
    private AccountDeactivationEffectsService effectsService;

    // ==================== DOCTOR ====================

    @Test
    void doctor_onlyFutureActiveAppointmentsOfTheDoctorAreCancelled_andPendingReferralsAreCounted() throws Exception {
        Appointment scheduled = appointment(patient, doctor, LocalDateTime.now().plusDays(1), "SCHEDULED");
        Appointment rescheduled = appointment(otherPatient, doctor, LocalDateTime.now().plusDays(2), "RESCHEDULED");
        Appointment started = appointment(patient, doctor, LocalDateTime.now().minusMinutes(10), "SCHEDULED");
        Appointment alreadyCancelled = appointment(patient, doctor, LocalDateTime.now().plusDays(3), "CANCELLED");
        Appointment colleagues = appointment(otherPatient, otherDoctor, LocalDateTime.now().plusDays(1), "SCHEDULED");
        Appointment documented = appointment(otherPatient, doctor, LocalDateTime.now().plusDays(4), "SCHEDULED");
        consultationRepository.saveAndFlush(Consultation.builder()
                .appointmentId(documented.getAppointmentId()).patientSystemId(otherPatient.getSystemId())
                .doctorSystemId(doctor.getSystemId()).chiefComplaint("Early notes").status("COMPLETED").build());
        pendingReferral(otherDoctor, doctor, patient, "PENDING");
        pendingReferral(otherDoctor, doctor, otherPatient, "PENDING");
        pendingReferral(otherDoctor, doctor, patient, "ACCEPTED");
        pendingReferral(doctor, otherDoctor, patient, "PENDING");

        deactivate(doctor, "Retired");

        assertEquals("CANCELLED", statusOf(scheduled));
        assertEquals("CANCELLED", statusOf(rescheduled));
        assertEquals("SCHEDULED", statusOf(started));
        assertEquals("CANCELLED", statusOf(alreadyCancelled));
        assertEquals("SCHEDULED", statusOf(colleagues));
        assertEquals("SCHEDULED", statusOf(documented), "a visit with a completed consultation is left as it is");
        assertEquals(2, events.stream(AppointmentCancelledEvent.class).count());
        assertEquals(4, count("SELECT COUNT(*) FROM referrals"), "referrals are never changed");
        assertEquals(3, count("SELECT COUNT(*) FROM referrals WHERE status = 'PENDING'"));

        Map<String, Object> audit = effectsRow("DOC-00001");
        assertEquals(summary("ADMIN", "DOCTOR", 2, 0, 0, 0, 2, "none"), audit.get("error_message"));
        assertNull(audit.get("resource_id"));
        assertNull(audit.get("http_method"));
        assertNull(audit.get("request_path"));
        assertNull(audit.get("ip_address"));
        assertNull(audit.get("user_agent"));
        assertNotNull(audit.get("timestamp"));
        // The services called write their own rows for the administrator.
        assertEquals(2, count("SELECT COUNT(*) FROM audit_log WHERE action = 'UPDATE' AND resource_type = 'APPOINTMENT' "
                + "AND system_id = 'ADM-00001'"));
    }

    @Test
    void doctor_appointmentsBeyondOnePageAreAllCancelled() throws Exception {
        int total = 205;
        LocalDateTime first = LocalDateTime.now().plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        for (int i = 0; i < total; i++) {
            User who = i % 2 == 0 ? patient : otherPatient;
            jdbc.update("INSERT INTO appointments (patient_system_id, doctor_system_id, scheduled_at, type, status) "
                    + "VALUES (?, ?, ?, 'IN_PERSON', 'SCHEDULED')", who.getSystemId(), doctor.getSystemId(),
                    first.plusMinutes(30L * i));
        }

        deactivate(doctor, "Left");

        assertEquals(0, count("SELECT COUNT(*) FROM appointments WHERE doctor_system_id = 'DOC-00001' "
                + "AND status <> 'CANCELLED'"));
        assertEquals(total, events.stream(AppointmentCancelledEvent.class).count());
        assertEquals(summary("ADMIN", "DOCTOR", total, 0, 0, 0, 0, "none"),
                effectsRow("DOC-00001").get("error_message"));
    }

    @Test
    void doctor_ownClosure_cancelsTheDoctorsAppointments_withTheDoctorAsActor() throws Exception {
        Appointment visit = appointment(patient, doctor, LocalDateTime.now().plusDays(1), "SCHEDULED");

        closeOwnAccount(doctor);

        assertEquals("CANCELLED", statusOf(visit));
        AppointmentCancelledEvent event = events.stream(AppointmentCancelledEvent.class).findFirst().orElseThrow();
        assertEquals("The doctor is no longer available", event.reason());
        Map<String, Object> audit = effectsRow("DOC-00001");
        assertEquals("DOC-00001", audit.get("system_id"));
        assertEquals("DOCTOR", audit.get("actor_role"));
        assertEquals(summary("CLOSURE", "DOCTOR", 1, 0, 0, 0, 0, "none"), audit.get("error_message"));
    }

    // ==================== CAREGIVER ====================

    @Test
    void caregiver_activeAndExpiredGrantsAreRevoked_alreadyRevokedOnesAreLeft() throws Exception {
        User thirdPatient = createAndSaveUser(11, "PAT-00011", "third@example.com", "PATIENT");
        DependentAccess active = grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        DependentAccess expired = grantAccess(caregiver, otherPatient,
                a -> a.setExpiresAt(LocalDateTime.now().minusDays(2)));
        LocalDateTime revokedAt = LocalDateTime.now().minusDays(10).withNano(0);
        DependentAccess revoked = grantAccess(caregiver, thirdPatient, a -> {
            a.setIsActive(false);
            a.setRevokedAt(revokedAt);
        });
        DependentAccess othersGrant = grantAccess(otherCaregiver, patient, a -> a.setCanViewRecords(true));

        deactivate(caregiver, "Duplicate account");

        for (DependentAccess grant : List.of(active, expired)) {
            DependentAccess stored = dependentAccessRepository.findById(grant.getAccessId()).orElseThrow();
            assertFalse(stored.getIsActive());
            assertNotNull(stored.getRevokedAt());
        }
        assertEquals(revokedAt, dependentAccessRepository.findById(revoked.getAccessId()).orElseThrow().getRevokedAt(),
                "a grant revoked earlier is not touched again");
        assertTrue(dependentAccessRepository.findById(othersGrant.getAccessId()).orElseThrow().getIsActive());

        List<CaregiverGrantRecordedEvent> recorded = events.stream(CaregiverGrantRecordedEvent.class).toList();
        assertEquals(2, recorded.size());
        recorded.forEach(event -> {
            assertEquals(CaregiverGrantRecordedEvent.REVOKED, event.kind());
            assertEquals("ADM-00001", event.actorSystemId());
        });
        assertEquals(2, count("SELECT COUNT(*) FROM audit_log WHERE action = 'DELETE' AND system_id = 'ADM-00001'"));

        assertEquals(summary("ADMIN", "CAREGIVER", 0, 2, 0, 0, 0, "none"),
                effectsRow("CGV-00001").get("error_message"));
    }

    @Test
    void caregiver_aFailingGrantIsCountedAndTheOthersAreStillRevoked() throws Exception {
        DependentAccess failing = grantApprovedAccess(caregiver, patient);
        DependentAccess fine = grantApprovedAccess(caregiver, otherPatient);
        failWhenChanged("dependent_access", "access_id", failing.getAccessId());

        deactivate(caregiver, "Left");

        assertFalse(isActive(caregiver));
        assertTrue(dependentAccessRepository.findById(failing.getAccessId()).orElseThrow().getIsActive());
        assertFalse(dependentAccessRepository.findById(fine.getAccessId()).orElseThrow().getIsActive());
        Map<String, Object> audit = effectsRow("CGV-00001");
        assertEquals("FAILURE", audit.get("status"));
        assertEquals(summary("ADMIN", "CAREGIVER", 0, 1, 0, 0, 0, "GRANTS:1"), audit.get("error_message"));
    }

    /** Runs once be-caregiver-requests-consent (V44) is merged; skipped on a schema without the table. */
    @Test
    void caregiver_onlyTheCaregiversOwnPendingAccessRequestsAreCancelled() throws Exception {
        assumeTrue(accessRequestsTableExists(), "caregiver_access_requests (V44) is not migrated");
        int forPatient = accessRequest(caregiver, patient, patient.getEmail(), "PENDING");
        int forOtherPatient = accessRequest(caregiver, otherPatient, otherPatient.getEmail(), "PENDING");
        int unmatchedEmail = accessRequest(caregiver, null, "nobody@example.com", "PENDING");
        int declined = accessRequest(caregiver, patient, patient.getEmail(), "DECLINED");
        int othersPending = accessRequest(otherCaregiver, patient, patient.getEmail(), "PENDING");

        deactivate(caregiver, "Duplicate account");

        for (int requestId : List.of(forPatient, forOtherPatient, unmatchedEmail)) {
            assertEquals("CANCELLED", accessRequestStatus(requestId));
        }
        assertEquals("DECLINED", accessRequestStatus(declined));
        assertEquals("PENDING", accessRequestStatus(othersPending), "another caregiver's request is left alone");
        assertEquals(summary("ADMIN", "CAREGIVER", 0, 0, 0, 3, 0, "none"),
                effectsRow("CGV-00001").get("error_message"));
    }

    // ==================== PATIENT ====================

    /**
     * Runs once be-reminders (V54) and be-caregiver-requests-consent (V44) are merged; skipped while either is
     * missing from the schema.
     */
    @Test
    void patient_closure_switchesOffOnlyThePatientsOwnReminders_andCancelsOnlyPendingAccessRequests() throws Exception {
        assumeTrue(reminderRecipientColumnExists(), "reminders.recipient_system_id (V54) is not migrated");
        assumeTrue(accessRequestsTableExists(), "caregiver_access_requests (V44) is not migrated");
        int patientsReminder = activeReminder(patient, patient);
        int doctorsReminder = activeReminder(patient, doctor);
        int otherPatientsReminder = activeReminder(otherPatient, otherPatient);
        int pending = accessRequest(caregiver, patient, patient.getEmail(), "PENDING");
        int otherPending = accessRequest(otherCaregiver, patient, patient.getEmail(), "PENDING");
        int approved = accessRequest(caregiver, patient, patient.getEmail(), "APPROVED");
        int aboutOtherPatient = accessRequest(caregiver, otherPatient, otherPatient.getEmail(), "PENDING");

        closeOwnAccount(patient);

        boolean[] closed = reminderState(patientsReminder);
        assertFalse(closed[0], "the closed patient's reminder is switched off");
        assertTrue(closed[1], "and has no next due time");
        boolean[] doctors = reminderState(doctorsReminder);
        assertTrue(doctors[0], "a reminder about the patient addressed to the doctor keeps running");
        assertFalse(doctors[1]);
        assertTrue(reminderState(otherPatientsReminder)[0]);

        assertEquals("CANCELLED", accessRequestStatus(pending));
        assertEquals("CANCELLED", accessRequestStatus(otherPending));
        assertEquals("APPROVED", accessRequestStatus(approved));
        assertEquals("PENDING", accessRequestStatus(aboutOtherPatient));

        Map<String, Object> audit = effectsRow("PAT-00001");
        assertEquals("PAT-00001", audit.get("system_id"));
        assertEquals("SUCCESS", audit.get("status"));
        assertEquals(summary("CLOSURE", "PATIENT", 0, 0, 1, 2, 0, "none"), audit.get("error_message"));
    }

    /**
     * With nothing to end, a patient closure succeeds with all counts 0 on any schema: without V44 / V54 both guards
     * skip their step without a failure, with them both statements run and match no row.
     */
    @Test
    void patient_closure_withNothingToEnd_succeedsWithZeroCounts() throws Exception {
        closeOwnAccount(patient);

        Map<String, Object> audit = effectsRow("PAT-00001");
        assertEquals("SUCCESS", audit.get("status"));
        assertEquals(summary("CLOSURE", "PATIENT", 0, 0, 0, 0, 0, "none"), audit.get("error_message"));
    }

    @Test
    void patient_administrativeDeactivationChangesNothing_butIsAudited() throws Exception {
        Appointment visit = appointment(patient, doctor, LocalDateTime.now().plusDays(1), "SCHEDULED");
        DependentAccess grant = grantApprovedAccess(caregiver, patient);

        deactivate(patient, "Duplicate account");

        assertEquals("SCHEDULED", statusOf(visit));
        assertTrue(dependentAccessRepository.findById(grant.getAccessId()).orElseThrow().getIsActive());
        assertEquals(0, events.stream(AppointmentCancelledEvent.class).count());
        Map<String, Object> audit = effectsRow("PAT-00001");
        assertEquals("PAT-00001", audit.get("patient_system_id"));
        assertEquals("SUCCESS", audit.get("status"));
        assertEquals(summary("ADMIN", "PATIENT", 0, 0, 0, 0, 0, "none"), audit.get("error_message"));
    }

    @Test
    void patient_approvedDeletionOfAnActiveAccount_isHandledAsAClosure() throws Exception {
        Appointment visit = appointment(patient, doctor, LocalDateTime.now().plusDays(1), "SCHEDULED");
        DependentAccess grant = grantApprovedAccess(caregiver, patient);
        MvcResult requested = doPost("/api/users/me/deletion-request", patient, Map.of("password", PASSWORD));
        assertEquals(201, requested.getResponse().getStatus(), requested.getResponse().getContentAsString());
        int requestId = json(requested).path("requestId").asInt();

        MvcResult approved = doPost("/api/admin/account-requests/" + requestId + "/approve", admin, null);
        assertEquals(200, approved.getResponse().getStatus(), approved.getResponse().getContentAsString());

        assertEquals("CANCELLED", statusOf(visit));
        assertFalse(dependentAccessRepository.findById(grant.getAccessId()).orElseThrow().getIsActive());
        Map<String, Object> audit = effectsRow("PAT-00001");
        assertEquals("ADM-00001", audit.get("system_id"));
        assertEquals("ADMIN", audit.get("actor_role"));
        assertEquals(summary("CLOSURE", "PATIENT", 1, 1, 0, 0, 0, "none"), audit.get("error_message"));
    }

    // ==================== EVENTS PUBLISHED OUTSIDE A REQUEST ====================

    @Test
    void eventWithoutActor_revokesAsTheAccountItself_andARepeatedEventOnlyAddsAnEmptyAuditRow() {
        DependentAccess grant = grantApprovedAccess(caregiver, patient);
        jdbc.update("UPDATE users SET is_active = FALSE WHERE system_id = 'CGV-00001'");

        // No transaction is active here: the listener runs at once (fallbackExecution).
        publisher.publishEvent(new UserDeactivatedEvent(" CGV-00001 ", " caregiver ", null, " suspension "));

        assertFalse(dependentAccessRepository.findById(grant.getAccessId()).orElseThrow().getIsActive());
        CaregiverGrantRecordedEvent recorded = events.stream(CaregiverGrantRecordedEvent.class).findFirst().orElseThrow();
        assertEquals("CGV-00001", recorded.actorSystemId());

        publisher.publishEvent(new UserDeactivatedEvent("CGV-00001", "CAREGIVER", null, "SUSPENSION"));

        List<Map<String, Object>> rows = effectsRows("CGV-00001");
        assertEquals(2, rows.size());
        for (Map<String, Object> row : rows) {
            assertNull(row.get("system_id"));
            assertNull(row.get("actor_role"));
            assertEquals("SUCCESS", row.get("status"));
        }
        assertEquals(summary("SUSPENSION", "CAREGIVER", 0, 1, 0, 0, 0, "none"), rows.get(0).get("error_message"));
        assertEquals(summary("SUSPENSION", "CAREGIVER", 0, 0, 0, 0, 0, "none"), rows.get(1).get("error_message"));
        assertEquals(1, events.stream(CaregiverGrantRecordedEvent.class).count(), "nothing is revoked twice");
    }

    @Test
    void eventWithoutRole_usesTheStoredRole_andAnAdministratorOrUnknownAccountOnlyGetsTheAuditRow() {
        Appointment visit = appointment(patient, doctor, LocalDateTime.now().plusDays(1), "SCHEDULED");

        DeactivationEffects doctorRun = effectsService.run(new UserDeactivatedEvent("DOC-00001", null, "ADM-00001", null));
        assertNotNull(doctorRun);
        assertEquals("DOCTOR", doctorRun.role());
        assertEquals("ADMIN", doctorRun.cause());
        assertEquals(1, doctorRun.appointmentsCancelled());
        assertEquals(0, doctorRun.failures(DeactivationStep.APPOINTMENTS));
        assertEquals("CANCELLED", statusOf(visit));

        User secondAdmin = createAndSaveUser(12, "ADM-00012", "second.admin@example.com", "ADMIN");
        DeactivationEffects adminRun = effectsService.run(
                new UserDeactivatedEvent(secondAdmin.getSystemId(), "ADMIN", "ADM-00001", "ADMIN"));
        assertEquals(summary("ADMIN", "ADMIN", 0, 0, 0, 0, 0, "none"), adminRun.summary());
        assertEquals(summary("ADMIN", "ADMIN", 0, 0, 0, 0, 0, "none"), effectsRow("ADM-00012").get("error_message"));

        assertNull(effectsService.run(new UserDeactivatedEvent("  ", "DOCTOR", "ADM-00001", "ADMIN")));
        assertEquals(2, count("SELECT COUNT(*) FROM audit_log WHERE action = ?", EFFECTS));
    }

    // ==================== REACTIVATION ====================

    @Test
    void reactivation_restoresNothing_andWritesNoEffectsRow() throws Exception {
        Appointment visit = appointment(patient, doctor, LocalDateTime.now().plusDays(1), "SCHEDULED");
        DependentAccess grant = grantApprovedAccess(caregiver, patient);
        deactivate(doctor, "Leave of absence");
        deactivate(caregiver, "Leave of absence");

        MvcResult doctorBack = doPost("/api/admin/users/DOC-00001/activate", admin, null);
        assertEquals(200, doctorBack.getResponse().getStatus(), doctorBack.getResponse().getContentAsString());
        assertEquals(200, doPost("/api/admin/users/CGV-00001/activate", admin, null).getResponse().getStatus());
        assertEquals(2, events.stream(UserReactivatedEvent.class).count());

        assertEquals("CANCELLED", statusOf(visit));
        assertFalse(dependentAccessRepository.findById(grant.getAccessId()).orElseThrow().getIsActive());
        assertEquals(2, count("SELECT COUNT(*) FROM audit_log WHERE action = ?", EFFECTS));
        assertEquals(1, events.stream(AppointmentCancelledEvent.class).count());
    }
}
