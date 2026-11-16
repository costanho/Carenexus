package com.carenexus.api.communications.notify;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.communications.notify.event.NotificationStoredEvent;
import com.carenexus.api.core.model.CareTeam;
import com.carenexus.api.core.model.UserSettings;
import com.carenexus.api.core.repository.UserSettingsRepository;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NotificationPublisher against PostgreSQL, with real commits: the stored row and its link, the preference matrix,
 * CRITICAL and SYSTEM bypass, dedupe that never aborts the caller's transaction, actor skip, the patient-circle and
 * care-team fan-out, and the stored event (once per stored row, never for a suppressed command).
 */
@RecordApplicationEvents
class NotificationPublisherTest extends CommittedIntegrationTest {

    @Autowired
    private NotificationPublisher publisher;

    @Autowired
    private UserSettingsRepository userSettingsRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationEvents events;

    private TransactionTemplate transaction;

    @BeforeEach
    void setUpTransaction() {
        transaction = new TransactionTemplate(transactionManager);
    }

    // ==================== helpers ====================

    private NotificationCommand.Builder labResult(int resultId) {
        return NotificationCommand.builder()
                .type(NotificationType.LAB_RESULT)
                .category(NotificationCategory.LAB_RESULT)
                .title("New lab result")
                .message("A lab result is available")
                .target(NotificationTarget.LAB_RESULTS)
                .resourceType("LAB_RESULT")
                .resourceId(resultId)
                .actorSystemId(doctor.getSystemId())
                .dedupeKey("LAB_RESULT:" + resultId);
    }

    private NotificationCommand.Builder of(NotificationCategory category, String dedupeKey) {
        return NotificationCommand.builder()
                .type(NotificationType.SYSTEM)
                .category(category)
                .title("Update")
                .message("Something changed")
                .dedupeKey(dedupeKey);
    }

    /** Saves the user's settings row with every category switched on, then shaped by {@code customizer}. */
    private void saveSettings(User user, Consumer<UserSettings> customizer) {
        UserSettings settings = userSettingsRepository.findBySystemId(user.getSystemId())
                .orElseGet(() -> NotificationPreferencesTest.allToggles(user.getSystemId(), true));
        NotificationPreferencesTest.TOGGLE_SETTERS.values().forEach(setter -> setter.accept(settings, true));
        customizer.accept(settings);
        userSettingsRepository.saveAndFlush(settings);
    }

    private int rowsFor(User user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE system_id = ?", Integer.class,
                user.getSystemId());
    }

    private Map<String, Object> row(Integer notificationId) {
        return jdbc.queryForMap("SELECT * FROM notifications WHERE notification_id = ?", notificationId);
    }

    private List<NotificationStoredEvent> storedEvents() {
        return events.stream(NotificationStoredEvent.class).toList();
    }

    // ==================== THE STORED ROW ====================

    @Test
    void publish_storesTheRow_withTheLinkForTheRecipientsRole_andPublishesOneStoredEvent() {
        LocalDateTime expires = LocalDateTime.now().plusDays(2).withNano(0);
        Optional<Integer> id = publisher.publish(NotificationCommand.builder()
                .recipientSystemId(doctor.getSystemId())
                .type(NotificationType.APPOINTMENT)
                .category(NotificationCategory.APPOINTMENT_CHANGE)
                .priority(NotificationPriority.HIGH)
                .title("  New appointment  ")
                .message("Thomas Dube booked an appointment")
                .target(NotificationTarget.APPOINTMENTS)
                .param("date", "2026-11-12")
                .resourceType("appointment")
                .resourceId(412)
                .patientSystemId(patient.getSystemId())
                .actorSystemId(patient.getSystemId())
                .dedupeKey("APPT_BOOKED:412")
                .expiresAt(expires)
                .build());

        assertTrue(id.isPresent());
        Map<String, Object> row = row(id.get());
        assertEquals(doctor.getSystemId(), row.get("system_id"));
        assertEquals("APPOINTMENT", row.get("type"));
        assertEquals("APPOINTMENT_CHANGE", row.get("category"));
        assertEquals("HIGH", row.get("priority"));
        assertEquals("New appointment", row.get("title"));
        assertEquals("Thomas Dube booked an appointment", row.get("message"));
        assertEquals("APPOINTMENTS", row.get("target"));
        assertEquals("APPOINTMENT", row.get("resource_type"), "resource types are stored in upper case");
        assertEquals(412, row.get("resource_id"));
        assertEquals("APPT_BOOKED:412", row.get("dedupe_key"));
        assertEquals(patient.getSystemId(), row.get("patient_system_id"));
        assertEquals(patient.getSystemId(), row.get("actor_system_id"));
        // A doctor's appointment link carries no patient filter
        assertEquals("/dashboard/doctor?section=appointments&id=412&date=2026-11-12", row.get("action_url"));
        assertEquals(Boolean.FALSE, row.get("is_read"));
        assertNull(row.get("read_at"));
        assertNull(row.get("thread_id"));
        assertNull(row.get("message_id"));
        assertEquals(NotificationType.APPOINTMENT.icon(), row.get("icon"));
        assertEquals(Timestamp.valueOf(expires), row.get("expires_at"));
        assertNotNull(row.get("created_at"));

        List<NotificationStoredEvent> stored = storedEvents();
        assertEquals(1, stored.size());
        assertEquals(new NotificationStoredEvent(id.get(), doctor.getSystemId(), "APPOINTMENT_CHANGE", "HIGH",
                "APPT_BOOKED:412"), stored.get(0));
    }

    @Test
    void publish_minimalCommand_isInformational_andItsEventHasNoDedupeKey() {
        Optional<Integer> id = publisher.publish(NotificationCommand.builder()
                .recipientSystemId(patient.getSystemId())
                .type(NotificationType.SYSTEM)
                .category(NotificationCategory.SYSTEM)
                .title("Password changed")
                .message("Your password was changed")
                .build());

        assertTrue(id.isPresent());
        Map<String, Object> row = row(id.get());
        assertEquals("NORMAL", row.get("priority"));
        assertNull(row.get("action_url"));
        assertNull(row.get("target"));
        assertNull(row.get("resource_type"));
        assertNull(row.get("resource_id"));
        assertNull(row.get("dedupe_key"));
        assertNull(row.get("patient_system_id"));
        assertNull(row.get("actor_system_id"));
        assertNull(row.get("expires_at"));
        assertEquals(new NotificationStoredEvent(id.get(), patient.getSystemId(), "SYSTEM", "NORMAL", null),
                storedEvents().get(0));
    }

    @Test
    void publish_targetWithoutASectionForTheRole_hasNoLink() {
        Optional<Integer> id = publisher.publish(of(NotificationCategory.SYSTEM, "DOCTOR_PENDING:9")
                .recipientSystemId(doctor.getSystemId())
                .target(NotificationTarget.ADMIN_CLINICIAN_QUEUE)
                .build());
        Optional<Integer> adminId = publisher.publish(of(NotificationCategory.SYSTEM, "DOCTOR_PENDING:9")
                .recipientSystemId(admin.getSystemId())
                .target(NotificationTarget.ADMIN_CLINICIAN_QUEUE)
                .build());

        assertNull(row(id.orElseThrow()).get("action_url"));
        assertEquals("ADMIN_CLINICIAN_QUEUE", row(id.get()).get("target"));
        assertEquals("/dashboard/admin?section=clinician-queue", row(adminId.orElseThrow()).get("action_url"));
    }

    @Test
    void publish_cutsLongTitlesAndMessages() {
        Optional<Integer> id = publisher.publish(of(NotificationCategory.SYSTEM, null)
                .recipientSystemId(patient.getSystemId())
                .title("T".repeat(200))
                .message("m".repeat(900))
                .build());

        Map<String, Object> row = row(id.orElseThrow());
        assertEquals("T".repeat(80), row.get("title"));
        assertEquals("m".repeat(500), row.get("message"));
    }

    @Test
    void publish_inactiveAccount_stillGetsTheInAppRow_unknownRecipientIsSkippedWithoutFailing() {
        patient.setIsActive(false);
        userRepository.saveAndFlush(patient);

        Boolean committed = transaction.execute(tx -> {
            assertTrue(publisher.publish(of(NotificationCategory.SYSTEM, "A:1")
                    .recipientSystemId(patient.getSystemId()).build()).isPresent());
            // No account: nothing is stored, no foreign-key failure, the transaction stays usable
            assertTrue(publisher.publish(of(NotificationCategory.SYSTEM, "A:1")
                    .recipientSystemId("PAT-99999").build()).isEmpty());
            assertTrue(publisher.publish(of(NotificationCategory.SYSTEM, "A:2")
                    .recipientSystemId(doctor.getSystemId()).build()).isPresent());
            return !tx.isRollbackOnly();
        });

        assertEquals(Boolean.TRUE, committed);
        assertEquals(1, rowsFor(patient));
        assertEquals(1, rowsFor(doctor));
        assertEquals(2, storedEvents().size());
    }

    @Test
    void publish_missingRequiredField_isAProgrammingError() {
        NotificationCommand.Builder valid = of(NotificationCategory.SYSTEM, "K:1").recipientSystemId(patient.getSystemId());

        assertThrows(IllegalArgumentException.class, () -> publisher.publish(null));
        assertThrows(IllegalArgumentException.class, () -> publisher.publish(valid.build().toBuilder().recipientSystemId(" ").build()));
        assertThrows(IllegalArgumentException.class, () -> publisher.publish(valid.build().toBuilder().type(null).build()));
        assertThrows(IllegalArgumentException.class, () -> publisher.publish(valid.build().toBuilder().category(null).build()));
        assertThrows(IllegalArgumentException.class, () -> publisher.publish(valid.build().toBuilder().title(" ").build()));
        assertThrows(IllegalArgumentException.class, () -> publisher.publish(valid.build().toBuilder().message(null).build()));
        assertThrows(IllegalArgumentException.class,
                () -> publisher.publish(valid.build().toBuilder().dedupeKey("K".repeat(161)).build()));
        assertThrows(IllegalArgumentException.class,
                () -> publisher.publish(valid.build().toBuilder().resourceType("R".repeat(41)).build()));
        assertThrows(IllegalArgumentException.class,
                () -> publisher.publishToPatientCircle(null, PatientPermission.LAB_RESULTS, valid.build()));
        assertThrows(IllegalArgumentException.class, () -> publisher.publishToCareTeam(patient.getSystemId(), null));

        assertEquals(0, rowsFor(patient));
        assertTrue(storedEvents().isEmpty());
    }

    // ==================== PREFERENCES ====================

    @Test
    void preferenceMatrix_everyCategoryFollowsItsOwnToggle_inTheDatabase() {
        int run = 0;
        for (NotificationCategory off : NotificationPreferencesTest.TOGGLE_SETTERS.keySet()) {
            saveSettings(patient, s -> NotificationPreferencesTest.TOGGLE_SETTERS.get(off).accept(s, false));
            run++;
            for (NotificationCategory category : NotificationCategory.values()) {
                Optional<Integer> id = publisher.publish(of(category, "MATRIX:" + run + ":" + category)
                        .recipientSystemId(patient.getSystemId()).build());
                assertEquals(category != off, id.isPresent(), category + " with " + off + " switched off");
            }
        }
        // 17 rounds, each storing every category except the one switched off
        int categories = NotificationCategory.values().length;
        assertEquals(17 * (categories - 1), rowsFor(patient));
        assertEquals(17 * (categories - 1), storedEvents().size(), "no event for a suppressed notification");
    }

    @Test
    void userWithoutASettingsRow_getsTheColumnDefaults_carePlanUpdatesAreOff() {
        for (NotificationCategory category : NotificationCategory.values()) {
            Optional<Integer> id = publisher.publish(of(category, "DEFAULTS:" + category)
                    .recipientSystemId(caregiver.getSystemId()).build());
            assertEquals(category != NotificationCategory.CARE_PLAN, id.isPresent(), category.name());
        }
    }

    @Test
    void criticalPriority_andSystemCategory_bypassEveryToggle() {
        userSettingsRepository.saveAndFlush(NotificationPreferencesTest.allToggles(patient.getSystemId(), false));

        // Everything is off: a normal notification of any switchable category is suppressed
        for (NotificationCategory category : NotificationPreferencesTest.TOGGLE_SETTERS.keySet()) {
            assertTrue(publisher.publish(of(category, "OFF:" + category)
                    .recipientSystemId(patient.getSystemId()).priority(NotificationPriority.HIGH).build()).isEmpty(),
                    category.name());
        }
        assertEquals(0, rowsFor(patient));
        assertTrue(storedEvents().isEmpty());

        // CRITICAL priority is always delivered, whatever the category
        for (NotificationCategory category : NotificationCategory.values()) {
            assertTrue(publisher.publish(of(category, "CRIT:" + category)
                    .recipientSystemId(patient.getSystemId()).priority(NotificationPriority.CRITICAL).build()).isPresent(),
                    category.name());
        }
        // SYSTEM is always delivered, whatever the priority
        for (NotificationPriority priority : NotificationPriority.values()) {
            assertTrue(publisher.publish(of(NotificationCategory.SYSTEM, "SYS:" + priority)
                    .recipientSystemId(patient.getSystemId()).priority(priority).build()).isPresent(), priority.name());
        }
        int expected = NotificationCategory.values().length + NotificationPriority.values().length;
        assertEquals(expected, rowsFor(patient));
        assertEquals(expected, storedEvents().size());
    }

    // ==================== DEDUPE ====================

    @Test
    void dedupe_secondPublishIsEmpty_andTheSurroundingTransactionStillCommits() {
        Boolean rollbackOnly = transaction.execute(tx -> {
            Optional<Integer> first = publisher.publish(labResult(88).recipientSystemId(patient.getSystemId()).build());
            Optional<Integer> second = publisher.publish(labResult(88).recipientSystemId(patient.getSystemId())
                    .title("Changed title").build());
            assertTrue(first.isPresent());
            assertTrue(second.isEmpty(), "same recipient and dedupe key");

            // The transaction is still usable after the duplicate: later writes succeed and commit
            assertTrue(publisher.publish(labResult(89).recipientSystemId(patient.getSystemId()).build()).isPresent());
            // The key is unique per recipient, not globally
            assertTrue(publisher.publish(labResult(88).recipientSystemId(caregiver.getSystemId()).build()).isPresent());
            jdbc.update("UPDATE users SET updated_at = updated_at WHERE system_id = ?", patient.getSystemId());
            return tx.isRollbackOnly();
        });

        assertEquals(Boolean.FALSE, rollbackOnly);
        assertEquals(2, rowsFor(patient), "committed: LAB_RESULT:88 once and LAB_RESULT:89");
        assertEquals(1, rowsFor(caregiver));
        assertEquals("New lab result", jdbc.queryForObject(
                "SELECT title FROM notifications WHERE system_id = ? AND dedupe_key = 'LAB_RESULT:88'", String.class,
                patient.getSystemId()), "the first row is kept unchanged");
        assertEquals(3, storedEvents().size(), "one event per stored row, none for the duplicate");

        // A repeat in a later transaction is still a duplicate
        assertTrue(publisher.publish(labResult(88).recipientSystemId(patient.getSystemId()).build()).isEmpty());
        assertEquals(2, rowsFor(patient));
        assertEquals(3, storedEvents().size());
    }

    @Test
    void withoutADedupeKey_everyPublishIsStored() {
        NotificationCommand command = of(NotificationCategory.SYSTEM, null).recipientSystemId(patient.getSystemId()).build();

        assertTrue(publisher.publish(command).isPresent());
        assertTrue(publisher.publish(command).isPresent());
        assertTrue(publisher.publish(command.toBuilder().dedupeKey("   ").build()).isPresent(), "a blank key is no key");

        assertEquals(3, rowsFor(patient));
    }

    @Test
    void rollbackOfTheOrigin_removesTheNotification() {
        transaction.executeWithoutResult(tx -> {
            assertTrue(publisher.publish(labResult(90).recipientSystemId(patient.getSystemId()).build()).isPresent());
            tx.setRollbackOnly();
        });

        assertEquals(0, rowsFor(patient));
        // The key was never committed, so the retried origin can publish it
        assertTrue(publisher.publish(labResult(90).recipientSystemId(patient.getSystemId()).build()).isPresent());
        assertEquals(1, rowsFor(patient));
    }

    // ==================== ACTOR ====================

    @Test
    void theActorIsNotNotifiedAboutTheirOwnAction_exceptForSystemNotifications() {
        Optional<Integer> own = publisher.publish(of(NotificationCategory.APPOINTMENT_CHANGE, "APPT_BOOKED:1")
                .recipientSystemId(patient.getSystemId()).actorSystemId(patient.getSystemId()).build());
        Optional<Integer> ownCritical = publisher.publish(of(NotificationCategory.APPOINTMENT_CHANGE, "APPT_BOOKED:2")
                .recipientSystemId(patient.getSystemId()).actorSystemId(patient.getSystemId())
                .priority(NotificationPriority.CRITICAL).build());
        Optional<Integer> system = publisher.publish(of(NotificationCategory.SYSTEM, "PASSWORD_CHANGED:1")
                .recipientSystemId(patient.getSystemId()).actorSystemId(patient.getSystemId()).build());
        Optional<Integer> byOther = publisher.publish(of(NotificationCategory.APPOINTMENT_CHANGE, "APPT_BOOKED:1")
                .recipientSystemId(patient.getSystemId()).actorSystemId(caregiver.getSystemId()).build());

        assertTrue(own.isEmpty());
        assertTrue(ownCritical.isEmpty(), "priority does not override the actor rule");
        assertTrue(system.isPresent());
        assertTrue(byOther.isPresent(), "the skipped command did not use up the dedupe key");
        assertEquals(2, rowsFor(patient));
        assertEquals(2, storedEvents().size());
    }

    // ==================== FAN-OUT ====================

    @Test
    void patientCircle_patientAndCaregiversWithTheFlag_notWithoutIt_notWithAnExpiredOrRevokedGrant() {
        User expiredCaregiver = createAndSaveUser(8, "CGV-00003", "expired.cg@example.com", "CAREGIVER");
        User revokedCaregiver = createAndSaveUser(9, "CGV-00004", "revoked.cg@example.com", "CAREGIVER");
        User fullAccessCaregiver = createAndSaveUser(10, "CGV-00005", "full.cg@example.com", "CAREGIVER");
        grantAccess(caregiver, patient, a -> a.setCanViewLabResults(true));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewAppointments(true));     // active grant, no lab flag
        grantAccess(expiredCaregiver, patient, a -> {
            a.setCanViewLabResults(true);
            a.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        });
        grantAccess(revokedCaregiver, patient, a -> {
            a.setCanViewLabResults(true);
            a.setRevokedAt(LocalDateTime.now().minusMinutes(1));
            a.setIsActive(false);
        });
        grantAccess(fullAccessCaregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));

        // The template's own recipient is ignored
        publisher.publishToPatientCircle(patient.getSystemId(), PatientPermission.LAB_RESULTS,
                labResult(88).recipientSystemId(otherPatient.getSystemId()).build());

        assertEquals(1, rowsFor(patient));
        assertEquals(1, rowsFor(caregiver));
        assertEquals(1, rowsFor(fullAccessCaregiver));
        assertEquals(0, rowsFor(otherCaregiver), "no lab-results flag");
        assertEquals(0, rowsFor(expiredCaregiver), "expired grant");
        assertEquals(0, rowsFor(revokedCaregiver), "revoked grant");
        assertEquals(0, rowsFor(otherPatient));
        assertEquals(0, rowsFor(doctor));

        // Each recipient gets the link for their own role, with the patient set
        Map<String, Object> toPatient = jdbc.queryForMap("SELECT * FROM notifications WHERE system_id = ?", patient.getSystemId());
        Map<String, Object> toCaregiver = jdbc.queryForMap("SELECT * FROM notifications WHERE system_id = ?", caregiver.getSystemId());
        assertEquals("/dashboard/patient?section=lab-results&id=88", toPatient.get("action_url"));
        assertEquals("/dashboard/proxy?section=documents-results&patient=PAT-00001&id=88&tab=labs", toCaregiver.get("action_url"));
        assertEquals(patient.getSystemId(), toPatient.get("patient_system_id"));
        assertEquals(patient.getSystemId(), toCaregiver.get("patient_system_id"));
        assertEquals("LAB_RESULT:88", toCaregiver.get("dedupe_key"));
        assertEquals(3, storedEvents().size());

        // The same event again: every recipient is deduplicated on their own key
        publisher.publishToPatientCircle(patient.getSystemId(), PatientPermission.LAB_RESULTS, labResult(88).build());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class));
        assertEquals(3, storedEvents().size());
    }

    @Test
    void patientCircle_appliesTheActorRuleAndPreferencesPerRecipient_nullPermissionIsPatientOnly() {
        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewAppointments(true));
        saveSettings(otherCaregiver, s -> s.setAppointmentChangeAlerts(false));

        // The patient booked: the patient is the actor, one caregiver switched the category off
        publisher.publishToPatientCircle(patient.getSystemId(), PatientPermission.APPOINTMENTS,
                of(NotificationCategory.APPOINTMENT_CHANGE, "APPT_BOOKED:412")
                        .type(NotificationType.APPOINTMENT).target(NotificationTarget.APPOINTMENTS).resourceId(412)
                        .actorSystemId(patient.getSystemId()).build());

        assertEquals(0, rowsFor(patient), "actor");
        assertEquals(1, rowsFor(caregiver));
        assertEquals(0, rowsFor(otherCaregiver), "preference off");
        assertEquals(1, storedEvents().size());

        // No caregiver permission: only the patient
        publisher.publishToPatientCircle(patient.getSystemId(), null,
                of(NotificationCategory.ACCESS_CHANGE, "ACCESS_GRANTED:5").build());

        assertEquals(1, rowsFor(patient));
        assertEquals(1, rowsFor(caregiver));
        assertEquals(2, storedEvents().size());
    }

    @Test
    void patientCircle_communicationPermission_needsThePatientsApproval() {
        grantApprovedAccess(caregiver, patient);
        grantAccess(otherCaregiver, patient, a -> a.setCanMessageCareTeam(true));       // requested, not approved

        publisher.publishToPatientCircle(patient.getSystemId(), PatientPermission.MESSAGING,
                of(NotificationCategory.VIDEO_CALL, "CALL_READY:3").actorSystemId(doctor.getSystemId()).build());

        assertEquals(1, rowsFor(patient));
        assertEquals(1, rowsFor(caregiver));
        assertEquals(0, rowsFor(otherCaregiver));
    }

    @Test
    void careTeam_notifiesTheRelatedDoctors_withTheDoctorLink() {
        // The fixture care team has one doctor; a second, a specialist, joins it
        careTeamRepository.saveAndFlush(CareTeam.builder()
                .patientSystemId(patient.getSystemId())
                .providerSystemId(otherDoctor.getSystemId())
                .role("SPECIALIST")
                .assignedAt(LocalDateTime.now())
                .isActive(true)
                .build());
        User unrelatedDoctor = createAndSaveUser(11, "DOC-00003", "unrelated.doc@example.com", "DOCTOR");

        publisher.publishToCareTeam(patient.getSystemId(), NotificationCommand.builder()
                .type(NotificationType.SYSTEM)
                .category(NotificationCategory.VITALS)
                .title("Reading outside range")
                .message("A reading for Thomas Dube is outside its range")
                .target(NotificationTarget.HEALTH_TRACKING)
                .resourceType("HEALTH_METRIC")
                .resourceId(31)
                .actorSystemId(patient.getSystemId())
                .dedupeKey("VITALS_ABNORMAL:31")
                .build());

        assertEquals(1, rowsFor(doctor));
        assertEquals(1, rowsFor(otherDoctor));
        assertEquals(0, rowsFor(unrelatedDoctor));
        assertEquals(0, rowsFor(patient), "the care-team fan-out does not include the patient");
        Map<String, Object> toDoctor = jdbc.queryForMap("SELECT * FROM notifications WHERE system_id = ?", doctor.getSystemId());
        assertEquals("/dashboard/doctor?section=patients&patient=PAT-00001&id=31&tab=vitals", toDoctor.get("action_url"));
        assertEquals(patient.getSystemId(), toDoctor.get("patient_system_id"));
        assertEquals(2, storedEvents().size());

        // A doctor who is the actor is skipped; a patient without doctors notifies nobody
        publisher.publishToCareTeam(patient.getSystemId(), of(NotificationCategory.CONSULTATION, "CONSULT_DONE:7")
                .actorSystemId(doctor.getSystemId()).build());
        assertEquals(1, rowsFor(doctor));
        assertEquals(2, rowsFor(otherDoctor));

        User lonePatient = createAndSaveUser(12, "PAT-00003", "lone@example.com", "PATIENT");
        publisher.publishToCareTeam(lonePatient.getSystemId(), of(NotificationCategory.CONSULTATION, "CONSULT_DONE:8").build());
        assertEquals(3, storedEvents().size());
        assertFalse(storedEvents().stream().anyMatch(e -> e.recipientSystemId().equals(lonePatient.getSystemId())));
    }
}
