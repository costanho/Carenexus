-- V69 (be-reminder-jobs): claim flags of the automatic reminder jobs (package reminders.auto).
--
-- appointments.reminder_sent (V1, mapped as Appointment.reminderSent) already marks "an automatic reminder went
-- out for the current start time"; it is set by the first-reminder claim and also by the second-reminder claim.
-- appointments.reminder_1h_sent marks the second automatic reminder (default 60 minutes before the start; the
-- lead time is the system setting reminders.second-lead-minutes, the column name keeps the historical "1h").
-- Both flags are reset when the start time changes (AppointmentReminderRearmListener).
--
-- medication_schedules.reminder_sent_at is stamped (clinic wall-clock) when the dose reminder for that row was
-- claimed. It is never cleared: a dose row is reminded once; a re-inserted row starts at NULL.
--
-- Neither new column is mapped on an entity: the jobs read and write them with native SQL only.
-- No backfill: appointments already inside a window are reminded on the first sweep; doses whose time passed more
-- than 60 minutes ago are never reminded.

ALTER TABLE appointments ADD COLUMN IF NOT EXISTS reminder_1h_sent BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE medication_schedules ADD COLUMN IF NOT EXISTS reminder_sent_at TIMESTAMP;

-- Second-reminder claim: active appointments not yet reminded, by start time. The first-reminder claim uses the
-- V34 index idx_appointments_reminder_lookup (status, reminder_sent, scheduled_at).
CREATE INDEX IF NOT EXISTS idx_appointments_reminder_1h
    ON appointments (scheduled_at)
    WHERE reminder_1h_sent = FALSE AND status IN ('SCHEDULED', 'RESCHEDULED');

-- Dose-reminder claim: pending doses not yet reminded, by dose date and time.
CREATE INDEX IF NOT EXISTS idx_medication_schedules_reminder_due
    ON medication_schedules (schedule_date, scheduled_time)
    WHERE reminder_sent_at IS NULL AND is_taken = FALSE AND missed = FALSE;

COMMENT ON COLUMN appointments.reminder_1h_sent IS
    'The second automatic reminder was claimed for the current start time (be-reminder-jobs; reset on reschedule)';
COMMENT ON COLUMN medication_schedules.reminder_sent_at IS
    'Clinic wall-clock time the automatic dose reminder was claimed (be-reminder-jobs); never cleared';
