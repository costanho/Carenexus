package com.carenexus.api.reminders.job;

import com.carenexus.api.common.job.JobSupport;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.reminders.ReminderFixtures;
import com.carenexus.api.reminders.model.Reminder;
import com.carenexus.api.reminders.repository.ReminderJdbc;
import com.carenexus.api.reminders.repository.ReminderRepository;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.reminders.ReminderFixtures.clockAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReminderDispatchJob driven with a fixed clock: due reminders notify their recipient once per due time, advance by
 * frequency, end after the last occurrence, respect the recipient's preferences (the row still advances) and show
 * the recipient's own time when their time-zone setting differs from the clinic zone.
 */
class ReminderDispatchJobTest extends CommittedIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    private static final LocalTime SEVEN_THIRTY = LocalTime.of(7, 30);

    @Autowired
    private JobSupport jobSupport;

    @Autowired
    private ReminderRepository reminderRepository;

    @Autowired
    private ReminderJdbc reminderJdbc;

    @Autowired
    private NotificationPublisher notificationPublisher;

    private ReminderDispatchJob jobAt(LocalDateTime at) {
        return new ReminderDispatchJob(jobSupport, reminderRepository, reminderJdbc, notificationPublisher,
                clockAt(at));
    }

    private Reminder save(Reminder reminder) {
        return reminderRepository.saveAndFlush(reminder);
    }

    private Reminder reload(Reminder reminder) {
        return reminderRepository.findById(reminder.getReminderId()).orElseThrow();
    }

    private List<Map<String, Object>> notificationsOf(String systemId) {
        return jdbc.queryForList("SELECT type, category, title, message, action_url, dedupe_key, patient_system_id "
                + "FROM notifications WHERE system_id = ? ORDER BY notification_id", systemId);
    }

    @Test
    void dailyReminder_notifiesOnce_advances_andEndsAfterTheEndDate() {
        Reminder daily = save(ReminderFixtures.manual(patient.getSystemId(), doctor.getSystemId(),
                Reminder.TYPE_VITAL_SIGN, Reminder.FREQUENCY_DAILY, DAY, DAY.plusDays(1), SEVEN_THIRTY,
                DAY.atTime(SEVEN_THIRTY)));

        // Not yet due
        assertEquals(0, jobAt(DAY.atTime(7, 29)).sweepOnce());
        assertTrue(notificationsOf(patient.getSystemId()).isEmpty());

        LocalDateTime firstRun = DAY.atTime(7, 30, 20);
        assertEquals(1, jobAt(firstRun).sweepOnce());
        Reminder afterFirst = reload(daily);
        assertTrue(afterFirst.active());
        assertEquals(DAY.plusDays(1).atTime(SEVEN_THIRTY), afterFirst.getNextDueAt());
        assertEquals(firstRun, afterFirst.getLastSentAt());

        List<Map<String, Object>> sent = notificationsOf(patient.getSystemId());
        assertEquals(1, sent.size());
        Map<String, Object> first = sent.get(0);
        assertEquals("SYSTEM", first.get("type"));
        assertEquals("SYSTEM", first.get("category"));
        assertEquals("Morning blood pressure", first.get("title"));
        assertEquals("Measure your blood pressure before breakfast. Due Tue 10 Nov 2026 at 07:30 clinic time.",
                first.get("message"));
        assertEquals("/dashboard/patient?section=prescriptions&tab=reminders", first.get("action_url"));
        assertEquals("REMINDER:" + daily.getReminderId() + ":2026-11-10T07:30", first.get("dedupe_key"));
        assertEquals(patient.getSystemId(), first.get("patient_system_id"));

        // Re-running at the same moment finds nothing due
        assertEquals(0, jobAt(firstRun).sweepOnce());
        assertEquals(1, notificationsOf(patient.getSystemId()).size());

        // The last day: sent, then ended
        assertEquals(1, jobAt(DAY.plusDays(1).atTime(7, 31)).sweepOnce());
        Reminder ended = reload(daily);
        assertFalse(ended.active());
        assertNull(ended.getNextDueAt());
        assertEquals(2, notificationsOf(patient.getSystemId()).size());
        assertEquals(0, jobAt(DAY.plusDays(5).atTime(7, 31)).sweepOnce());
    }

    @Test
    void oneDueTime_neverNotifiesTwice_evenWhenTheRowIsRewound() {
        Reminder once = save(ReminderFixtures.manual(patient.getSystemId(), patient.getSystemId(),
                Reminder.TYPE_MEDICATION, Reminder.FREQUENCY_ONCE, DAY, null, SEVEN_THIRTY,
                DAY.atTime(SEVEN_THIRTY)));
        assertEquals(1, jobAt(DAY.atTime(8, 0)).sweepOnce());
        assertFalse(reload(once).active());

        // Simulates a crash after the publish: the row is due again for the same time
        jdbc.update("UPDATE reminders SET is_active = TRUE, next_due_at = ? WHERE reminder_id = ?",
                DAY.atTime(SEVEN_THIRTY), once.getReminderId());
        assertEquals(1, jobAt(DAY.atTime(8, 1)).sweepOnce());

        List<Map<String, Object>> sent = notificationsOf(patient.getSystemId());
        assertEquals(1, sent.size(), "the dedupe key holds one notification per due time");
        assertEquals("MEDICATION", sent.get(0).get("type"));
        assertEquals("MEDICATION_REMINDER", sent.get(0).get("category"));
        assertFalse(reload(once).active());
    }

    @Test
    void missedOccurrences_areNotReplayed() {
        Reminder weekly = save(ReminderFixtures.manual(patient.getSystemId(), patient.getSystemId(),
                Reminder.TYPE_CHECK_IN, Reminder.FREQUENCY_WEEKLY, DAY, null, SEVEN_THIRTY,
                DAY.atTime(SEVEN_THIRTY)));
        // The job was down for three weeks
        assertEquals(1, jobAt(DAY.plusDays(22).atTime(9, 0)).sweepOnce());
        assertEquals(DAY.plusDays(28).atTime(SEVEN_THIRTY), reload(weekly).getNextDueAt());
        assertEquals(1, notificationsOf(patient.getSystemId()).size());
    }

    @Test
    void switchedOffPreference_sendsNothing_butTheReminderStillAdvances() {
        jdbc.update("INSERT INTO user_settings (system_id, medication_reminders) VALUES (?, FALSE)",
                patient.getSystemId());
        Reminder daily = save(ReminderFixtures.manual(patient.getSystemId(), patient.getSystemId(),
                Reminder.TYPE_MEDICATION, Reminder.FREQUENCY_DAILY, DAY, null, SEVEN_THIRTY,
                DAY.atTime(SEVEN_THIRTY)));
        Reminder vitals = save(ReminderFixtures.manual(patient.getSystemId(), patient.getSystemId(),
                Reminder.TYPE_VITAL_SIGN, Reminder.FREQUENCY_DAILY, DAY, null, SEVEN_THIRTY,
                DAY.atTime(SEVEN_THIRTY)));

        LocalDateTime run = DAY.atTime(7, 45);
        assertEquals(2, jobAt(run).sweepOnce());

        List<Map<String, Object>> sent = notificationsOf(patient.getSystemId());
        assertEquals(1, sent.size(), "only the never-suppressible VITAL_SIGN reminder is delivered");
        assertEquals("REMINDER:" + vitals.getReminderId() + ":2026-11-10T07:30", sent.get(0).get("dedupe_key"));
        Reminder suppressed = reload(daily);
        assertEquals(run, suppressed.getLastSentAt());
        assertEquals(DAY.plusDays(1).atTime(SEVEN_THIRTY), suppressed.getNextDueAt());
    }

    @Test
    void timeZoneSetting_addsTheRecipientsLocalTime() {
        jdbc.update("INSERT INTO user_settings (system_id, timezone) VALUES (?, 'Europe/London')",
                patient.getSystemId());
        jdbc.update("INSERT INTO user_settings (system_id, timezone) VALUES (?, NULL)", otherPatient.getSystemId());
        save(ReminderFixtures.manual(patient.getSystemId(), patient.getSystemId(), Reminder.TYPE_VITAL_SIGN,
                Reminder.FREQUENCY_ONCE, DAY, null, LocalTime.of(1, 0), DAY.atTime(1, 0)));
        save(ReminderFixtures.manual(otherPatient.getSystemId(), otherPatient.getSystemId(), Reminder.TYPE_VITAL_SIGN,
                Reminder.FREQUENCY_ONCE, DAY, null, LocalTime.of(1, 0), DAY.atTime(1, 0)));

        assertEquals(2, jobAt(DAY.atTime(1, 0)).sweepOnce());

        assertEquals("Measure your blood pressure before breakfast. Due Tue 10 Nov 2026 at 01:00 clinic time "
                        + "(Mon 9 Nov 23:00 your time).",
                notificationsOf(patient.getSystemId()).get(0).get("message"));
        assertEquals("Measure your blood pressure before breakfast. Due Tue 10 Nov 2026 at 01:00 clinic time.",
                notificationsOf(otherPatient.getSystemId()).get(0).get("message"));
    }

    @Test
    void automaticReminders_reachTheirRecipient_withTheSourceLink() {
        // Care-plan reminders follow carePlanUpdateAlerts, which is off by default
        jdbc.update("INSERT INTO user_settings (system_id, care_plan_update_alerts) VALUES (?, TRUE)",
                doctor.getSystemId());
        jdbc.update("INSERT INTO user_settings (system_id, care_plan_update_alerts) VALUES (?, TRUE)",
                patient.getSystemId());
        LocalDateTime eight = DAY.atTime(8, 0);
        save(ReminderFixtures.automatic(patient.getSystemId(), doctor.getSystemId(), Reminder.TYPE_CARE_PLAN,
                Reminder.SOURCE_CARE_PLAN, 41, DAY, eight));
        save(ReminderFixtures.automatic(patient.getSystemId(), patient.getSystemId(), Reminder.TYPE_CARE_PLAN,
                Reminder.SOURCE_CARE_PLAN, 41, DAY, eight));
        save(ReminderFixtures.automatic(patient.getSystemId(), doctor.getSystemId(), Reminder.TYPE_LAB_FOLLOWUP,
                Reminder.SOURCE_IMAGING_RESULT, 7, DAY, eight));
        save(ReminderFixtures.automatic(patient.getSystemId(), patient.getSystemId(), Reminder.TYPE_CHECK_IN,
                Reminder.SOURCE_CONSULTATION, 42, DAY, eight));
        // A care-plan reminder for a recipient who left the toggle off is processed but not delivered
        save(ReminderFixtures.automatic(otherPatient.getSystemId(), otherPatient.getSystemId(),
                Reminder.TYPE_CARE_PLAN, Reminder.SOURCE_CARE_PLAN, 43, DAY, eight));

        assertEquals(5, jobAt(eight).sweepOnce());

        List<Map<String, Object>> doctorSent = notificationsOf(doctor.getSystemId());
        assertEquals(2, doctorSent.size());
        assertEquals("/dashboard/doctor?section=care-plans&id=41", doctorSent.get(0).get("action_url"));
        assertEquals("CARE_PLAN", doctorSent.get(0).get("category"));
        assertEquals("SYSTEM", doctorSent.get(0).get("type"));
        assertEquals("/dashboard/doctor?section=imaging&id=7", doctorSent.get(1).get("action_url"));
        assertEquals("LAB_RESULT", doctorSent.get(1).get("category"));
        assertEquals("LAB_RESULT", doctorSent.get(1).get("type"));

        List<Map<String, Object>> patientSent = notificationsOf(patient.getSystemId());
        assertEquals(2, patientSent.size());
        assertEquals("/dashboard/patient?section=care-plans&id=41", patientSent.get(0).get("action_url"));
        assertEquals("/dashboard/patient?section=consultation-history&id=42", patientSent.get(1).get("action_url"));

        assertTrue(notificationsOf(otherPatient.getSystemId()).isEmpty());
        // Caregivers are never notified by reminders
        assertTrue(notificationsOf(caregiver.getSystemId()).isEmpty());
    }

    @Test
    void handlesMoreThanOneBatchInARun() {
        List<Reminder> rows = new ArrayList<>();
        for (int i = 0; i < ReminderDispatchJob.BATCH_SIZE + 5; i++) {
            rows.add(ReminderFixtures.manual(patient.getSystemId(), patient.getSystemId(), Reminder.TYPE_VITAL_SIGN,
                    Reminder.FREQUENCY_ONCE, DAY, null, SEVEN_THIRTY, DAY.atTime(SEVEN_THIRTY)));
        }
        reminderRepository.saveAllAndFlush(rows);

        assertEquals(ReminderDispatchJob.BATCH_SIZE + 5, jobAt(DAY.atTime(9, 0)).sweepOnce());
        assertEquals(ReminderDispatchJob.BATCH_SIZE + 5, notificationsOf(patient.getSystemId()).size());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM reminders WHERE is_active", Integer.class));
    }
}
