package com.carenexus.api.reminders.listener;

import com.carenexus.api.common.event.CarePlanChangedEvent;
import com.carenexus.api.common.event.ClinicalResultRecordedEvent;
import com.carenexus.api.common.event.ConsultationCompletedEvent;
import com.carenexus.api.reminders.ReminderFixtures;
import com.carenexus.api.reminders.model.Reminder;
import com.carenexus.api.reminders.repository.ReminderJdbc;
import com.carenexus.api.reminders.repository.ReminderRepository;
import com.carenexus.api.reminders.service.ReminderText;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static com.carenexus.api.reminders.ReminderFixtures.clockAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Automatic reminders created from clinical events: the follow-up reminder of a completed consultation, the
 * ordering doctor's result-review reminder, and the two care-plan review reminders (patient and doctor) that move
 * with the review date and disappear when the plan no longer needs a review.
 */
class ReminderEventListenerTest extends CommittedIntegrationTest {

    /** Tuesday 10 Nov 2026, 09:00 clinic time: today's 08:00 has passed. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();

    @Autowired
    private ReminderJdbc reminderJdbc;

    @Autowired
    private ReminderRepository reminderRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ReminderEventListener listenerAt(LocalDateTime at) {
        return new ReminderEventListener(reminderJdbc, clockAt(at));
    }

    private List<Reminder> rows(String sourceType, int sourceId) {
        return reminderRepository.findAll().stream()
                .filter(r -> sourceType.equals(r.getSourceType()) && r.getSourceId() == sourceId)
                .sorted(Comparator.comparing(Reminder::getReminderId))
                .toList();
    }

    private Reminder rowFor(String sourceType, int sourceId, String recipient) {
        return rows(sourceType, sourceId).stream()
                .filter(r -> recipient.equals(r.getRecipientSystemId()))
                .findFirst().orElseThrow();
    }

    // ==================== FOLLOW-UP ====================

    @Test
    void followUp_firesThreeDaysBefore_onceForThePatient() {
        ReminderEventListener listener = listenerAt(NOW);
        LocalDate followUp = TODAY.plusDays(10);
        ConsultationCompletedEvent event = new ConsultationCompletedEvent(42, 9, patient.getSystemId(),
                doctor.getSystemId(), true, followUp);
        listener.onConsultationCompleted(event);
        listener.onConsultationCompleted(event);

        List<Reminder> rows = rows(Reminder.SOURCE_CONSULTATION, 42);
        assertEquals(1, rows.size(), "a repeated event changes nothing");
        Reminder reminder = rows.get(0);
        assertEquals(patient.getSystemId(), reminder.getPatientSystemId());
        assertEquals(patient.getSystemId(), reminder.getRecipientSystemId());
        assertEquals(Reminder.TYPE_CHECK_IN, reminder.getType());
        assertEquals(Reminder.FREQUENCY_ONCE, reminder.getFrequency());
        assertEquals(followUp.minusDays(3), reminder.getStartDate());
        assertEquals(followUp.minusDays(3).atTime(8, 0), reminder.getNextDueAt());
        assertTrue(reminder.active());
        assertNull(reminder.getCreatedBySystemId());
        assertEquals("Follow-up visit due", reminder.getTitle());
        assertEquals("Your doctor asked to see you again on Fri 20 Nov 2026. Book a follow-up appointment if you "
                + "have not already.", reminder.getMessage());
    }

    @Test
    void followUp_soon_firesTomorrow_andNoneWhenItIsTodayAfterEight() {
        ReminderEventListener listener = listenerAt(NOW);
        listener.onConsultationCompleted(new ConsultationCompletedEvent(43, null, patient.getSystemId(),
                doctor.getSystemId(), true, TODAY.plusDays(1)));
        assertEquals(TODAY.plusDays(1).atTime(8, 0), rows(Reminder.SOURCE_CONSULTATION, 43).get(0).getNextDueAt());

        listener.onConsultationCompleted(new ConsultationCompletedEvent(44, null, patient.getSystemId(),
                doctor.getSystemId(), true, TODAY));
        assertTrue(rows(Reminder.SOURCE_CONSULTATION, 44).isEmpty());

        // Before 08:00 on the follow-up day the reminder still fires that morning
        listenerAt(TODAY.atTime(7, 0)).onConsultationCompleted(new ConsultationCompletedEvent(45, null,
                patient.getSystemId(), doctor.getSystemId(), true, TODAY));
        assertEquals(TODAY.atTime(8, 0), rows(Reminder.SOURCE_CONSULTATION, 45).get(0).getNextDueAt());

        listener.onConsultationCompleted(new ConsultationCompletedEvent(46, null, patient.getSystemId(),
                doctor.getSystemId(), false, null));
        assertTrue(rows(Reminder.SOURCE_CONSULTATION, 46).isEmpty());
    }

    // ==================== RESULT REVIEW ====================

    @Test
    void resultRequiringReview_remindsTheOrderingDoctorDailyForSevenDays_untilReviewed() {
        ReminderEventListener listener = listenerAt(NOW);
        listener.onClinicalResultRecorded(new ClinicalResultRecordedEvent(ClinicalResultRecordedEvent.LAB, 7,
                patient.getSystemId(), doctor.getSystemId(), doctor.getSystemId(), "REQUIRES_REVIEW", false, false,
                true));
        listener.onClinicalResultRecorded(new ClinicalResultRecordedEvent(ClinicalResultRecordedEvent.IMAGING, 7,
                patient.getSystemId(), doctor.getSystemId(), doctor.getSystemId(), "REQUIRES_REVIEW", false, false,
                true));

        Reminder lab = rows(Reminder.SOURCE_LAB_RESULT, 7).get(0);
        assertEquals(doctor.getSystemId(), lab.getRecipientSystemId());
        assertEquals(patient.getSystemId(), lab.getPatientSystemId());
        assertEquals(Reminder.TYPE_LAB_FOLLOWUP, lab.getType());
        assertEquals(Reminder.FREQUENCY_DAILY, lab.getFrequency());
        assertEquals(TODAY.plusDays(1), lab.getStartDate());
        assertEquals(TODAY.plusDays(7), lab.getEndDate());
        assertEquals(TODAY.plusDays(1).atTime(8, 0), lab.getNextDueAt());
        assertEquals("Result waiting for review", lab.getTitle());
        assertEquals("A lab result is waiting for your review.", lab.getMessage());
        assertEquals("An imaging result is waiting for your review.",
                rows(Reminder.SOURCE_IMAGING_RESULT, 7).get(0).getMessage());

        // A later REQUIRES_REVIEW event keeps the existing reminder as it is
        listenerAt(NOW.plusDays(2)).onClinicalResultRecorded(new ClinicalResultRecordedEvent(
                ClinicalResultRecordedEvent.LAB, 7, patient.getSystemId(), doctor.getSystemId(), doctor.getSystemId(),
                "REQUIRES_REVIEW", false, false, false));
        assertEquals(1, rows(Reminder.SOURCE_LAB_RESULT, 7).size());
        assertEquals(TODAY.plusDays(1), rows(Reminder.SOURCE_LAB_RESULT, 7).get(0).getStartDate());

        // Reviewed: the lab reminder disappears, the imaging one (another source) stays
        listener.onClinicalResultRecorded(new ClinicalResultRecordedEvent(ClinicalResultRecordedEvent.LAB, 7,
                patient.getSystemId(), doctor.getSystemId(), doctor.getSystemId(), "COMPLETED", false, false, false));
        assertTrue(rows(Reminder.SOURCE_LAB_RESULT, 7).isEmpty());
        assertEquals(1, rows(Reminder.SOURCE_IMAGING_RESULT, 7).size());
    }

    @Test
    void resultWithoutDoctorOrForAnUnknownUser_isSkipped() {
        ReminderEventListener listener = listenerAt(NOW);
        listener.onClinicalResultRecorded(new ClinicalResultRecordedEvent(ClinicalResultRecordedEvent.LAB, 8,
                patient.getSystemId(), null, null, "REQUIRES_REVIEW", false, false, true));
        listener.onClinicalResultRecorded(new ClinicalResultRecordedEvent(ClinicalResultRecordedEvent.LAB, 9,
                patient.getSystemId(), "DOC-99999", null, "REQUIRES_REVIEW", false, false, true));
        assertTrue(reminderRepository.findAll().isEmpty());
    }

    // ==================== CARE PLAN REVIEW ====================

    @Test
    void carePlanReview_patientAndDoctorRemindersCoexist() {
        LocalDate review = TODAY.plusDays(14);
        listenerAt(NOW).onCarePlanChanged(new CarePlanChangedEvent(41, patient.getSystemId(), doctor.getSystemId(),
                doctor.getSystemId(), CarePlanChangedEvent.CREATED, "ACTIVE", review));

        List<Reminder> rows = rows(Reminder.SOURCE_CARE_PLAN, 41);
        assertEquals(2, rows.size());
        Reminder forPatient = rowFor(Reminder.SOURCE_CARE_PLAN, 41, patient.getSystemId());
        Reminder forDoctor = rowFor(Reminder.SOURCE_CARE_PLAN, 41, doctor.getSystemId());
        for (Reminder row : rows) {
            assertEquals(patient.getSystemId(), row.getPatientSystemId());
            assertEquals(Reminder.TYPE_CARE_PLAN, row.getType());
            assertEquals(Reminder.FREQUENCY_ONCE, row.getFrequency());
            assertEquals(review, row.getStartDate());
            assertEquals(review.atTime(8, 0), row.getNextDueAt());
            assertEquals("Care plan review due", row.getTitle());
        }
        assertEquals("Your care plan is due for review on Tue 24 Nov 2026.", forPatient.getMessage());
        assertEquals("A care plan you wrote is due for review on Tue 24 Nov 2026.", forDoctor.getMessage());
    }

    @Test
    void carePlanReview_movedDateMovesAndReArmsBoth_sameDateKeepsAPause() {
        ReminderEventListener listener = listenerAt(NOW);
        LocalDate review = TODAY.plusDays(14);
        listener.onCarePlanChanged(new CarePlanChangedEvent(41, patient.getSystemId(), doctor.getSystemId(),
                doctor.getSystemId(), CarePlanChangedEvent.CREATED, "ACTIVE", review));
        Reminder forPatient = rowFor(Reminder.SOURCE_CARE_PLAN, 41, patient.getSystemId());
        jdbc.update("UPDATE reminders SET is_active = FALSE, next_due_at = NULL WHERE reminder_id = ?",
                forPatient.getReminderId());

        // An edit that keeps the date leaves the patient's pause in place
        listener.onCarePlanChanged(new CarePlanChangedEvent(41, patient.getSystemId(), doctor.getSystemId(),
                doctor.getSystemId(), CarePlanChangedEvent.UPDATED, "ON_HOLD", review));
        assertFalse(rowFor(Reminder.SOURCE_CARE_PLAN, 41, patient.getSystemId()).active());

        // A new date moves both rows (same ids) and re-arms the paused one
        LocalDate moved = TODAY.plusDays(30);
        listener.onCarePlanChanged(new CarePlanChangedEvent(41, patient.getSystemId(), doctor.getSystemId(),
                doctor.getSystemId(), CarePlanChangedEvent.REVIEWED, "ACTIVE", moved));
        List<Reminder> rows = rows(Reminder.SOURCE_CARE_PLAN, 41);
        assertEquals(2, rows.size());
        Reminder movedPatient = rowFor(Reminder.SOURCE_CARE_PLAN, 41, patient.getSystemId());
        assertEquals(forPatient.getReminderId(), movedPatient.getReminderId());
        assertTrue(movedPatient.active());
        assertEquals(moved, movedPatient.getStartDate());
        assertEquals(moved.atTime(8, 0), movedPatient.getNextDueAt());
        assertEquals(ReminderText.carePlanReviewMessageForPatient(moved), movedPatient.getMessage());
        Reminder movedDoctor = rowFor(Reminder.SOURCE_CARE_PLAN, 41, doctor.getSystemId());
        assertEquals(moved.atTime(8, 0), movedDoctor.getNextDueAt());
        assertEquals(ReminderText.carePlanReviewMessageForDoctor(moved), movedDoctor.getMessage());
    }

    @Test
    void carePlanReview_clearedDateOrTerminalStatus_deletesBothReminders() {
        ReminderEventListener listener = listenerAt(NOW);
        LocalDate review = TODAY.plusDays(14);
        listener.onCarePlanChanged(new CarePlanChangedEvent(41, patient.getSystemId(), doctor.getSystemId(),
                doctor.getSystemId(), CarePlanChangedEvent.CREATED, "ACTIVE", review));
        listener.onCarePlanChanged(new CarePlanChangedEvent(41, patient.getSystemId(), doctor.getSystemId(),
                doctor.getSystemId(), CarePlanChangedEvent.REVIEWED, "ACTIVE", null));
        assertTrue(rows(Reminder.SOURCE_CARE_PLAN, 41).isEmpty());

        listener.onCarePlanChanged(new CarePlanChangedEvent(41, patient.getSystemId(), doctor.getSystemId(),
                doctor.getSystemId(), CarePlanChangedEvent.UPDATED, "ACTIVE", review));
        assertEquals(2, rows(Reminder.SOURCE_CARE_PLAN, 41).size());
        listener.onCarePlanChanged(new CarePlanChangedEvent(41, patient.getSystemId(), doctor.getSystemId(),
                doctor.getSystemId(), CarePlanChangedEvent.STATUS_CHANGED, "COMPLETED", review));
        assertTrue(rows(Reminder.SOURCE_CARE_PLAN, 41).isEmpty());
    }

    @Test
    void carePlanReview_dateAlreadyPast_isStoredInactive() {
        listenerAt(NOW).onCarePlanChanged(new CarePlanChangedEvent(41, patient.getSystemId(), doctor.getSystemId(),
                doctor.getSystemId(), CarePlanChangedEvent.CREATED, "ACTIVE", TODAY));
        List<Reminder> rows = rows(Reminder.SOURCE_CARE_PLAN, 41);
        assertEquals(2, rows.size());
        rows.forEach(row -> {
            assertFalse(row.active());
            assertNull(row.getNextDueAt());
        });
    }

    @Test
    void publishedEvents_reachTheListenerInsideThePublishersTransaction() {
        LocalDate review = ReminderFixtures.clinicToday().plusDays(20);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            eventPublisher.publishEvent(new CarePlanChangedEvent(51, patient.getSystemId(), doctor.getSystemId(),
                    doctor.getSystemId(), CarePlanChangedEvent.CREATED, "ACTIVE", review));
        });
        assertEquals(2, rows(Reminder.SOURCE_CARE_PLAN, 51).size());

        // Rolled back with the publisher's transaction
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            eventPublisher.publishEvent(new CarePlanChangedEvent(53, patient.getSystemId(), doctor.getSystemId(),
                    doctor.getSystemId(), CarePlanChangedEvent.CREATED, "ACTIVE", review));
            status.setRollbackOnly();
        });
        assertTrue(rows(Reminder.SOURCE_CARE_PLAN, 53).isEmpty());
    }
}
