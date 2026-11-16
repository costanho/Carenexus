package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.event.AppointmentRescheduledEvent;
import com.carenexus.api.medication.MedicationFixtures;
import com.carenexus.api.reminders.auto.job.AppointmentReminderJob;
import com.carenexus.api.reminders.auto.job.MedicationDoseReminderJob;
import com.carenexus.api.reminders.auto.job.NeedsOutcomeNudgeJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.core.appointments.AppointmentFixtures.body;
import static com.carenexus.api.core.appointments.AppointmentFixtures.booking;
import static com.carenexus.api.core.appointments.AppointmentFixtures.clinicDay;
import static com.carenexus.api.core.appointments.AppointmentFixtures.wallClock;
import static com.carenexus.api.reminders.auto.ReminderJobsFixtures.clinicNow;
import static com.carenexus.api.reminders.auto.ReminderJobsFixtures.count;
import static com.carenexus.api.reminders.auto.ReminderJobsFixtures.countAllReminders;
import static com.carenexus.api.reminders.auto.ReminderJobsFixtures.countByKey;
import static com.carenexus.api.reminders.auto.ReminderJobsFixtures.doseReminderSentAt;
import static com.carenexus.api.reminders.auto.ReminderJobsFixtures.insertAppointment;
import static com.carenexus.api.reminders.auto.ReminderJobsFixtures.notification;
import static com.carenexus.api.reminders.auto.ReminderJobsFixtures.reminderSent;
import static com.carenexus.api.reminders.auto.ReminderJobsFixtures.secondReminderSent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Release gate of be-reminder-jobs: the automatic appointment reminders, the dose reminders and the needs-outcome
 * nudge, driven with {@code sweepOnce(clinicNow)} (scheduled jobs are off in tests) against committed data, so the
 * notification publisher, its preference checks and dedupe keys really run.
 */
class ReminderJobsReleaseGateTest extends CommittedIntegrationTest {

    private static final String FIRST = "APPOINTMENT_REMINDER";
    private static final String SECOND = "APPOINTMENT_REMINDER_1H";
    private static final String DOSE = "MEDICATION_REMINDER";
    private static final String FIRST_KEY = "APPT_REMINDER_24H:";
    private static final String SECOND_KEY = "APPT_REMINDER_1H:";
    private static final String NUDGE_KEY = "APPT_NEEDS_OUTCOME:";
    private static final String SETTINGS = "/api/admin/settings/";
    private static final String PROFILE_SETTINGS = "/api/profile/settings";
    private static final DateTimeFormatter KEY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @Autowired
    private AppointmentReminderJob appointmentJob;

    @Autowired
    private MedicationDoseReminderJob doseJob;

    @Autowired
    private NeedsOutcomeNudgeJob nudgeJob;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // ==================== HELPERS ====================

    private int appointment(LocalDateTime start, String status) {
        return insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(), start, 30, status);
    }

    private static String key(String prefix, int id, LocalDateTime start) {
        return prefix + id + ":" + start.format(KEY_TIME);
    }

    private void setSetting(String key, String value) throws Exception {
        MvcResult result = doPut(SETTINGS + key, admin, Map.of("value", value));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private void patchSettings(User user, Map<String, Object> changes) throws Exception {
        MvcResult result = doPatch(PROFILE_SETTINGS, user, changes);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private int prescription(String medication) {
        return MedicationFixtures.insertPrescription(jdbc, patient.getSystemId(), doctor.getSystemId(), medication,
                "Once daily", "ACTIVE", LocalDate.now().minusDays(3), null);
    }

    private int dose(int prescriptionId, LocalDateTime at, String status) {
        return MedicationFixtures.insertDose(jdbc, prescriptionId, patient.getSystemId(), at.toLocalDate(),
                at.toLocalTime(), status, null);
    }

    private User thirdCaregiver() {
        return createAndSaveUser(8, "CGV-00003", "third.cg@example.com", "CAREGIVER");
    }

    // ==================== FIRST AND SECOND APPOINTMENT REMINDER ====================

    @Test
    void firstReminder_reachesPatientAndPermittedCaregiverExactlyOnce_acrossTwoSweeps() throws Exception {
        LocalDateTime now = clinicNow();
        LocalDateTime start = now.plusHours(23);
        int id = appointment(start, "SCHEDULED");
        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewMedications(true)); // no appointment flag

        assertEquals(1, appointmentJob.sweepOnce(now));
        assertEquals(0, appointmentJob.sweepOnce(now.plusMinutes(1)));

        assertEquals(1, count(jdbc, patient.getSystemId(), FIRST));
        assertEquals(1, count(jdbc, caregiver.getSystemId(), FIRST));
        assertEquals(0, count(jdbc, otherCaregiver.getSystemId(), FIRST));
        assertEquals(0, countByKey(jdbc, doctor.getSystemId(), "APPT_REMINDER"));
        assertEquals(0, count(jdbc, patient.getSystemId(), SECOND));
        assertTrue(reminderSent(jdbc, id));
        assertFalse(secondReminderSent(jdbc, id));

        String dedupe = key(FIRST_KEY, id, start);
        Map<String, Object> toPatient = notification(jdbc, patient.getSystemId(), dedupe);
        assertEquals("APPOINTMENT", toPatient.get("type"));
        assertEquals("Appointment reminder", toPatient.get("title"));
        assertEquals("NORMAL", toPatient.get("priority"));
        assertEquals("APPOINTMENTS", toPatient.get("target"));
        assertEquals("APPOINTMENT", toPatient.get("resource_type"));
        assertEquals(id, toPatient.get("resource_id"));
        assertEquals(patient.getSystemId(), toPatient.get("patient_system_id"));
        assertNull(toPatient.get("actor_system_id"));
        assertEquals(Timestamp.valueOf(start.plusMinutes(30)), toPatient.get("expires_at"));
        assertEquals("/dashboard/patient?section=my-appointments&id=" + id, toPatient.get("action_url"));
        String when = start.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy 'at' HH:mm", java.util.Locale.ENGLISH));
        assertEquals("You have an appointment with Dr. DOCTOR User2 on " + when + " clinic time.",
                toPatient.get("message"));

        Map<String, Object> toCaregiver = notification(jdbc, caregiver.getSystemId(), dedupe);
        assertEquals("PATIENT User1 has an appointment with Dr. DOCTOR User2 on " + when + " clinic time.",
                toCaregiver.get("message"));
        assertEquals("/dashboard/proxy?section=appointments&patient=" + patient.getSystemId() + "&id=" + id,
                toCaregiver.get("action_url"));

        // The flag is visible to clients as reminderSent, and the reminder is in the patient's list
        MvcResult appointment = doGet("/api/appointments/" + id, patient);
        assertEquals(200, appointment.getResponse().getStatus());
        assertTrue(json(appointment).path("reminderSent").asBoolean());
        MvcResult list = doGet("/api/notifications", patient);
        assertEquals(200, list.getResponse().getStatus());
        assertTrue(list.getResponse().getContentAsString().contains("Appointment reminder"));
    }

    @Test
    void secondReminder_goesOutOnceInTheSecondWindow() {
        LocalDateTime now = clinicNow();
        LocalDateTime start = now.plusHours(23);
        int id = appointment(start, "SCHEDULED");
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));

        assertEquals(1, appointmentJob.sweepOnce(now));
        LocalDateTime halfHourBefore = start.minusMinutes(30);
        assertEquals(1, appointmentJob.sweepOnce(halfHourBefore));
        assertEquals(0, appointmentJob.sweepOnce(halfHourBefore.plusMinutes(1)));

        for (User recipient : List.of(patient, caregiver)) {
            assertEquals(1, count(jdbc, recipient.getSystemId(), FIRST));
            assertEquals(1, count(jdbc, recipient.getSystemId(), SECOND));
        }
        Map<String, Object> second = notification(jdbc, patient.getSystemId(), key(SECOND_KEY, id, start));
        assertEquals("Appointment coming up", second.get("title"));
        assertEquals("APPOINTMENT", second.get("type"));
        assertTrue(secondReminderSent(jdbc, id));
        assertTrue(reminderSent(jdbc, id));
        assertEquals(0, countByKey(jdbc, doctor.getSystemId(), "APPT_REMINDER"));
    }

    @Test
    void lateBookingInsideTheSecondWindow_getsOnlyTheSecondReminder() {
        LocalDateTime now = clinicNow();
        int id = appointment(now.plusMinutes(30), "SCHEDULED");

        assertEquals(1, appointmentJob.sweepOnce(now));
        assertEquals(0, appointmentJob.sweepOnce(now.plusMinutes(1)));

        assertEquals(0, count(jdbc, patient.getSystemId(), FIRST));
        assertEquals(1, count(jdbc, patient.getSystemId(), SECOND));
        assertTrue(reminderSent(jdbc, id));
        assertTrue(secondReminderSent(jdbc, id));
    }

    @Test
    void inactiveAndStartedAppointments_areNeverClaimed() {
        LocalDateTime now = clinicNow();
        List<Integer> ids = List.of(
                appointment(now.plusHours(23), "CANCELLED"),
                appointment(now.plusHours(22), "COMPLETED"),
                appointment(now.plusMinutes(30), "NO_SHOW"),
                appointment(now.plusMinutes(40), "CANCELLED"),
                appointment(now.minusMinutes(5), "SCHEDULED"),
                appointment(now, "RESCHEDULED"),
                appointment(now.plusHours(25), "SCHEDULED"));

        assertEquals(0, appointmentJob.sweepOnce(now));

        assertEquals(0, countAllReminders(jdbc));
        for (int id : ids) {
            assertFalse(reminderSent(jdbc, id));
            assertFalse(secondReminderSent(jdbc, id));
        }
    }

    @Test
    void rescheduledAppointments_areClaimable() {
        LocalDateTime now = clinicNow();
        int id = appointment(now.plusHours(20), "RESCHEDULED");

        assertEquals(1, appointmentJob.sweepOnce(now));
        assertEquals(1, count(jdbc, patient.getSystemId(), FIRST));
        assertTrue(reminderSent(jdbc, id));
    }

    // ==================== RE-ARMING ====================

    @Test
    void reschedule_rearmsBothReminders_andTheNewTimeEarnsItsOwn() throws Exception {
        LocalDateTime first = clinicDay(2, 10, 0);
        LocalDateTime second = clinicDay(2, 11, 0);
        MvcResult booked = doPost("/api/appointments", patient, booking(null, doctor.getSystemId(), first, "IN_PERSON"));
        assertEquals(201, booked.getResponse().getStatus(), booked.getResponse().getContentAsString());
        int id = json(booked).path("appointmentId").asInt();

        assertEquals(1, appointmentJob.sweepOnce(first.minusHours(23)));
        assertEquals(1, appointmentJob.sweepOnce(first.minusMinutes(30)));
        assertTrue(reminderSent(jdbc, id));
        assertTrue(secondReminderSent(jdbc, id));

        MvcResult moved = doPatch("/api/appointments/" + id + "/reschedule", patient,
                body("scheduledAt", wallClock(second)));
        assertEquals(200, moved.getResponse().getStatus(), moved.getResponse().getContentAsString());
        assertFalse(json(moved).path("reminderSent").asBoolean());
        assertFalse(reminderSent(jdbc, id));
        assertFalse(secondReminderSent(jdbc, id));

        assertEquals(1, appointmentJob.sweepOnce(second.minusHours(23)));
        assertEquals(1, appointmentJob.sweepOnce(second.minusMinutes(30)));
        assertEquals(2, count(jdbc, patient.getSystemId(), FIRST));
        assertEquals(2, count(jdbc, patient.getSystemId(), SECOND));
        notification(jdbc, patient.getSystemId(), key(FIRST_KEY, id, first));
        notification(jdbc, patient.getSystemId(), key(FIRST_KEY, id, second));
    }

    @Test
    void rearmListener_ignoresAnUnchangedStartAndUnknownIds() {
        LocalDateTime now = clinicNow();
        LocalDateTime start = now.plusHours(5);
        int id = appointment(start, "SCHEDULED");
        jdbc.update("UPDATE appointments SET reminder_sent = TRUE, reminder_1h_sent = TRUE WHERE appointment_id = ?", id);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> eventPublisher.publishEvent(new AppointmentRescheduledEvent(id,
                patient.getSystemId(), doctor.getSystemId(), patient.getSystemId(), "PATIENT", start, start)));
        assertTrue(reminderSent(jdbc, id));
        assertTrue(secondReminderSent(jdbc, id));

        tx.executeWithoutResult(status -> eventPublisher.publishEvent(new AppointmentRescheduledEvent(999_999,
                patient.getSystemId(), doctor.getSystemId(), patient.getSystemId(), "PATIENT", start,
                start.plusHours(1))));
        assertTrue(reminderSent(jdbc, id));

        tx.executeWithoutResult(status -> eventPublisher.publishEvent(new AppointmentRescheduledEvent(id,
                patient.getSystemId(), doctor.getSystemId(), patient.getSystemId(), "PATIENT", start.minusHours(1),
                start)));
        assertFalse(reminderSent(jdbc, id));
        assertFalse(secondReminderSent(jdbc, id));
    }

    // ==================== LEAD-TIME SETTINGS ====================

    @Test
    void firstLeadSetting_changesWhichAppointmentsAreClaimed() throws Exception {
        LocalDateTime now = clinicNow();
        int id = appointment(now.plusHours(40), "SCHEDULED");

        assertEquals(0, appointmentJob.sweepOnce(now));
        assertFalse(reminderSent(jdbc, id));
        assertEquals(0, count(jdbc, patient.getSystemId(), FIRST));

        setSetting("reminders.appointment-lead-hours", "48");
        assertEquals(1, appointmentJob.sweepOnce(now.plusMinutes(1)));
        assertTrue(reminderSent(jdbc, id));
        assertEquals(1, count(jdbc, patient.getSystemId(), FIRST));

        // Back to 24 hours: the flag is already set, nothing is claimed again
        setSetting("reminders.appointment-lead-hours", "24");
        assertEquals(0, appointmentJob.sweepOnce(now.plusHours(18)));
        assertEquals(1, count(jdbc, patient.getSystemId(), FIRST));
    }

    @Test
    void secondLeadSetting_changesWhichAppointmentsGetTheSecondReminder() throws Exception {
        LocalDateTime now = clinicNow();
        int id = appointment(now.plusMinutes(90), "SCHEDULED");

        // Default 60 minutes: 90 minutes ahead is in the first window only
        assertEquals(1, appointmentJob.sweepOnce(now));
        assertEquals(1, count(jdbc, patient.getSystemId(), FIRST));
        assertEquals(0, count(jdbc, patient.getSystemId(), SECOND));
        assertFalse(secondReminderSent(jdbc, id));

        setSetting("reminders.second-lead-minutes", "120");
        assertEquals(1, appointmentJob.sweepOnce(now.plusMinutes(1)));
        assertEquals(1, count(jdbc, patient.getSystemId(), SECOND));
        assertEquals(1, count(jdbc, patient.getSystemId(), FIRST));
        assertTrue(secondReminderSent(jdbc, id));
    }

    @Test
    void secondLeadNotShorterThanTheFirst_leavesOnlyTheSecondReminder() throws Exception {
        LocalDateTime now = clinicNow();
        setSetting("reminders.appointment-lead-hours", "1");
        setSetting("reminders.second-lead-minutes", "120");
        int near = appointment(now.plusMinutes(100), "SCHEDULED");
        int far = appointment(now.plusMinutes(150), "SCHEDULED");

        assertEquals(1, appointmentJob.sweepOnce(now));
        assertEquals(0, count(jdbc, patient.getSystemId(), FIRST));
        assertEquals(1, count(jdbc, patient.getSystemId(), SECOND));
        assertTrue(secondReminderSent(jdbc, near));
        assertFalse(reminderSent(jdbc, far));
    }

    // ==================== CAREGIVERS AND PREFERENCES ====================

    @Test
    void caregiverWithoutFlag_expiredOrRevoked_getsNoAppointmentReminder() {
        User third = thirdCaregiver();
        grantAccess(caregiver, patient, a -> {
            a.setCanViewAppointments(true);
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
        });
        grantAccess(otherCaregiver, patient, a -> {
            a.setCanViewAppointments(true);
            a.setRevokedAt(LocalDateTime.now().minusHours(1));
        });
        grantAccess(third, patient, a -> a.setCanViewRecords(true));
        LocalDateTime now = clinicNow();
        LocalDateTime start = now.plusHours(23);
        appointment(start, "SCHEDULED");

        appointmentJob.sweepOnce(now);
        appointmentJob.sweepOnce(start.minusMinutes(30));

        assertEquals(1, count(jdbc, patient.getSystemId(), FIRST));
        assertEquals(1, count(jdbc, patient.getSystemId(), SECOND));
        for (User nobody : List.of(caregiver, otherCaregiver, third)) {
            assertEquals(0, count(jdbc, nobody.getSystemId(), FIRST));
            assertEquals(0, count(jdbc, nobody.getSystemId(), SECOND));
        }
    }

    @Test
    void switchedOffToggles_suppressOnlyTheirOwnReminder() throws Exception {
        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        patchSettings(patient, Map.of("appointmentReminders", false));
        patchSettings(caregiver, Map.of("appointmentReminder1h", false));
        LocalDateTime now = clinicNow();
        LocalDateTime start = now.plusHours(23);
        int id = appointment(start, "SCHEDULED");

        assertEquals(1, appointmentJob.sweepOnce(now));
        assertEquals(0, count(jdbc, patient.getSystemId(), FIRST));
        assertEquals(1, count(jdbc, caregiver.getSystemId(), FIRST));
        // The flag is set even though the patient suppressed the category
        assertTrue(reminderSent(jdbc, id));

        assertEquals(1, appointmentJob.sweepOnce(start.minusMinutes(30)));
        assertEquals(1, count(jdbc, patient.getSystemId(), SECOND));
        assertEquals(0, count(jdbc, caregiver.getSystemId(), SECOND));
    }

    @Test
    void recipientTimeZone_addsTheLocalTimeOnlyWhenTheWallClockDiffers() throws Exception {
        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        grantAccess(otherCaregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        patchSettings(patient, Map.of("timezone", "Asia/Tokyo"));       // UTC+9, the clinic is UTC+2
        patchSettings(caregiver, Map.of("timezone", "Africa/Maputo"));  // same offset as the clinic
        LocalDateTime now = clinicNow();
        LocalDateTime start = now.plusHours(23);
        int id = appointment(start, "SCHEDULED");

        appointmentJob.sweepOnce(now);

        String dedupe = key(FIRST_KEY, id, start);
        LocalDateTime tokyo = start.atZone(com.carenexus.api.reminders.auto.ReminderJobsFixtures.CLINIC_ZONE)
                .withZoneSameInstant(java.time.ZoneId.of("Asia/Tokyo")).toLocalDateTime();
        String patientText = (String) notification(jdbc, patient.getSystemId(), dedupe).get("message");
        assertTrue(patientText.endsWith(" clinic time (" + (tokyo.toLocalDate().equals(start.toLocalDate())
                ? tokyo.format(DateTimeFormatter.ofPattern("HH:mm"))
                : tokyo.format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm", java.util.Locale.ENGLISH)))
                + " your time)."), patientText);
        String caregiverText = (String) notification(jdbc, caregiver.getSystemId(), dedupe).get("message");
        assertFalse(caregiverText.contains("your time"), caregiverText);
        String unsetText = (String) notification(jdbc, otherCaregiver.getSystemId(), dedupe).get("message");
        assertFalse(unsetText.contains("your time"), unsetText);
    }

    // ==================== DOSE REMINDERS ====================

    @Test
    void dueDose_isRemindedOnceToPatientAndCaregiversWithTheMedicationFlag() {
        grantAccess(caregiver, patient, a -> a.setCanViewMedications(true));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewAppointments(true)); // no medication flag
        LocalDateTime now = clinicNow();
        LocalDateTime due = now.minusMinutes(5);
        int rx = prescription("Metformin");
        int doseId = dose(rx, due, "PENDING");

        assertEquals(1, doseJob.sweepOnce(now));
        assertEquals(0, doseJob.sweepOnce(now.plusMinutes(1)));

        assertEquals(1, count(jdbc, patient.getSystemId(), DOSE));
        assertEquals(1, count(jdbc, caregiver.getSystemId(), DOSE));
        assertEquals(0, count(jdbc, otherCaregiver.getSystemId(), DOSE));
        assertEquals(0, count(jdbc, doctor.getSystemId(), DOSE));
        assertEquals(now, doseReminderSentAt(jdbc, doseId));

        String dedupe = "DOSE_REMINDER:" + doseId;
        String time = due.format(DateTimeFormatter.ofPattern("HH:mm"));
        String date = due.toLocalDate().toString();
        Map<String, Object> toPatient = notification(jdbc, patient.getSystemId(), dedupe);
        assertEquals("MEDICATION", toPatient.get("type"));
        assertEquals("Medication reminder", toPatient.get("title"));
        assertEquals("MEDICATION_SCHEDULE", toPatient.get("target"));
        assertEquals("MEDICATION_SCHEDULE", toPatient.get("resource_type"));
        assertNull(toPatient.get("resource_id"));
        assertEquals(Timestamp.valueOf(due.plusHours(24)), toPatient.get("expires_at"));
        assertEquals("It is time to take Metformin. Due at " + time + " clinic time.", toPatient.get("message"));
        assertEquals("/dashboard/patient?section=prescriptions&tab=schedule&date=" + date, toPatient.get("action_url"));

        Map<String, Object> toCaregiver = notification(jdbc, caregiver.getSystemId(), dedupe);
        assertEquals("PATIENT User1 has a dose of Metformin due at " + time + " clinic time.",
                toCaregiver.get("message"));
        assertEquals("/dashboard/proxy?section=medications&patient=" + patient.getSystemId()
                + "&tab=schedule&date=" + date, toCaregiver.get("action_url"));

        // Undo after the reminder: the stamp stays, no second reminder
        jdbc.update("UPDATE medication_schedules SET is_taken = TRUE WHERE schedule_id = ?", doseId);
        jdbc.update("UPDATE medication_schedules SET is_taken = FALSE WHERE schedule_id = ?", doseId);
        assertEquals(0, doseJob.sweepOnce(now.plusMinutes(2)));
        assertEquals(1, count(jdbc, patient.getSystemId(), DOSE));
    }

    @Test
    void takenMissedLateAndFutureDoses_areNeverReminded() {
        LocalDateTime now = clinicNow();
        int rx = prescription("Metformin");
        int taken = dose(rx, now.minusMinutes(5), "TAKEN");
        int missed = dose(rx, now.minusMinutes(10), "MISSED");
        int tooLate = dose(rx, now.minusMinutes(61), "PENDING");
        int future = dose(rx, now.plusMinutes(5), "PENDING");

        assertEquals(0, doseJob.sweepOnce(now));

        assertEquals(0, count(jdbc, patient.getSystemId(), DOSE));
        for (int id : List.of(taken, missed, tooLate, future)) {
            assertNull(doseReminderSentAt(jdbc, id));
        }
        // The future dose is reminded at its own time
        assertEquals(1, doseJob.sweepOnce(now.plusMinutes(5)));
        assertNotNull(doseReminderSentAt(jdbc, future));
        assertEquals(1, count(jdbc, patient.getSystemId(), DOSE));
    }

    @Test
    void doseMovedToANewTime_isRemindedAtTheNewTimeOnly() {
        LocalDateTime now = clinicNow();
        LocalDateTime oldTime = now.plusMinutes(30);
        LocalDateTime newTime = now.plusMinutes(150);
        int rx = prescription("Metformin");
        int oldDose = dose(rx, oldTime, "PENDING");
        // A schedule edit deletes the future PENDING doses and inserts the new plan (be-medication)
        jdbc.update("DELETE FROM medication_schedules WHERE schedule_id = ?", oldDose);
        int newDose = dose(rx, newTime, "PENDING");

        assertEquals(0, doseJob.sweepOnce(oldTime.plusMinutes(1)));
        assertEquals(0, count(jdbc, patient.getSystemId(), DOSE));

        assertEquals(1, doseJob.sweepOnce(newTime.plusMinutes(1)));
        assertEquals(1, count(jdbc, patient.getSystemId(), DOSE));
        notification(jdbc, patient.getSystemId(), "DOSE_REMINDER:" + newDose);
    }

    @Test
    void medicationRemindersSwitchedOff_suppressesTheDoseReminder() throws Exception {
        grantAccess(caregiver, patient, a -> a.setCanViewMedications(true));
        patchSettings(patient, Map.of("medicationReminders", false));
        LocalDateTime now = clinicNow();
        int doseId = dose(prescription("Metformin"), now.minusMinutes(1), "PENDING");

        assertEquals(1, doseJob.sweepOnce(now));

        assertEquals(0, count(jdbc, patient.getSystemId(), DOSE));
        assertEquals(1, count(jdbc, caregiver.getSystemId(), DOSE));
        assertNotNull(doseReminderSentAt(jdbc, doseId));
    }

    @Test
    void twoDosesAtTheSameTime_giveTwoReminders() {
        LocalDateTime now = clinicNow();
        LocalDateTime due = now.minusMinutes(2);
        int first = dose(prescription("Metformin"), due, "PENDING");
        int second = dose(prescription("Amlodipine"), due, "PENDING");

        assertEquals(2, doseJob.sweepOnce(now));

        assertEquals(2, count(jdbc, patient.getSystemId(), DOSE));
        notification(jdbc, patient.getSystemId(), "DOSE_REMINDER:" + first);
        notification(jdbc, patient.getSystemId(), "DOSE_REMINDER:" + second);
    }

    // ==================== NEEDS-OUTCOME NUDGE ====================

    @Test
    void needsOutcomeNudge_reachesTheDoctorOnce_andNobodyElse() {
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        LocalDateTime now = clinicNow();
        LocalDateTime start = now.minusHours(26);
        int id = appointment(start, "SCHEDULED");
        int recent = appointment(now.minusHours(23), "SCHEDULED");              // ended under 24 h ago
        int done = appointment(now.minusHours(30), "COMPLETED");
        int stale = appointment(now.minusDays(9), "RESCHEDULED");             // outside the 7-day look-back

        assertEquals(1, nudgeJob.sweepOnce(now));
        assertEquals(0, nudgeJob.sweepOnce(now.plusHours(1)));

        assertEquals(1, countByKey(jdbc, doctor.getSystemId(), NUDGE_KEY));
        Map<String, Object> nudge = notification(jdbc, doctor.getSystemId(), key(NUDGE_KEY, id, start));
        assertEquals("APPOINTMENT", nudge.get("type"));
        assertEquals("APPOINTMENT_REMINDER", nudge.get("category"));
        assertEquals("Appointment needs an outcome", nudge.get("title"));
        assertNull(nudge.get("expires_at"));
        assertEquals("/dashboard/doctor?section=appointments&id=" + id + "&tab=needs-outcome", nudge.get("action_url"));
        String when = start.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy 'at' HH:mm", java.util.Locale.ENGLISH));
        assertEquals("Your appointment with PATIENT User1 on " + when + " clinic time still has no outcome. "
                + "Mark it completed, no-show or cancelled.", nudge.get("message"));

        assertEquals(0, countByKey(jdbc, patient.getSystemId(), NUDGE_KEY));
        assertEquals(0, countByKey(jdbc, caregiver.getSystemId(), NUDGE_KEY));
        for (int other : List.of(recent, done, stale)) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE dedupe_key LIKE ?",
                    Integer.class, NUDGE_KEY + other + ":%"));
        }
    }

    @Test
    void needsOutcomeNudge_respectsTheDoctorsToggle() throws Exception {
        patchSettings(doctor, Map.of("appointmentReminders", false));
        LocalDateTime now = clinicNow();
        appointment(now.minusHours(30), "SCHEDULED");

        assertEquals(0, nudgeJob.sweepOnce(now));
        assertEquals(0, countByKey(jdbc, doctor.getSystemId(), NUDGE_KEY));
    }

    @Test
    void oneSweepOfSeveralPatients_reachesEachPatientsOwnCircle() {
        LocalDateTime now = clinicNow();
        User third = thirdCaregiver();
        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        grantAccess(otherCaregiver, otherPatient, a -> a.setAccessLevel("FULL_ACCESS"));
        grantAccess(third, otherPatient, a -> a.setCanViewMedications(true));      // no appointment flag
        int mine = appointment(now.plusHours(20), "SCHEDULED");
        int mineLater = appointment(now.plusHours(21), "SCHEDULED");
        int theirs = insertAppointment(jdbc, otherPatient.getSystemId(), otherDoctor.getSystemId(),
                now.plusHours(22), 30, "SCHEDULED");

        assertEquals(3, appointmentJob.sweepOnce(now));

        assertEquals(2, count(jdbc, patient.getSystemId(), FIRST));
        assertEquals(2, count(jdbc, caregiver.getSystemId(), FIRST));
        assertEquals(1, count(jdbc, otherPatient.getSystemId(), FIRST));
        assertEquals(1, count(jdbc, otherCaregiver.getSystemId(), FIRST));
        assertEquals(0, count(jdbc, third.getSystemId(), FIRST));
        assertEquals(0, countByKey(jdbc, caregiver.getSystemId(), FIRST_KEY + theirs + ":"));
        assertEquals(0, countByKey(jdbc, otherCaregiver.getSystemId(), FIRST_KEY + mine + ":"));
        assertEquals(0, countByKey(jdbc, otherCaregiver.getSystemId(), FIRST_KEY + mineLater + ":"));
    }

    @Test
    void needsOutcomeNudge_findsLongAppointmentsByTheirEnd_andIgnoresOnesThatEndedBeforeTheLookBack() {
        LocalDateTime now = clinicNow();
        // Started 9 days and 2 hours ago and lasted 23 hours: it ended 8 days and 3 hours ago, outside the window
        int longAndOld = insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(),
                now.minusDays(9).minusHours(2), 1380, "SCHEDULED");
        // Started 8 days and 20 hours ago and lasted 24 hours: it ended 7 days and 20 hours ago, inside the
        // window (end + 24 h is later than now - 7 days)
        LocalDateTime longStart = now.minusDays(8).minusHours(20);
        int longInside = insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(), longStart, 1440,
                "SCHEDULED");

        assertEquals(1, nudgeJob.sweepOnce(now));
        assertEquals(1, countByKey(jdbc, doctor.getSystemId(), key(NUDGE_KEY, longInside, longStart)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE dedupe_key LIKE ?",
                Integer.class, NUDGE_KEY + longAndOld + ":%"));
    }

    @Test
    void sweeps_onAnEmptyDatabase_doNothing() {
        LocalDateTime now = clinicNow();
        assertEquals(0, appointmentJob.sweepOnce(now));
        assertEquals(0, doseJob.sweepOnce(now));
        assertEquals(0, nudgeJob.sweepOnce(now));
        assertEquals(0, appointmentJob.sweepOnce());
        assertEquals(0, doseJob.sweepOnce());
        assertEquals(0, nudgeJob.sweepOnce());
        assertEquals(0, countAllReminders(jdbc));
    }
}
