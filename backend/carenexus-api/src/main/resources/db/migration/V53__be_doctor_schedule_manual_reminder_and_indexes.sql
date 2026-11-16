-- V53 (be-doctor-schedule): manual appointment reminder time and indexes for the doctor schedule views.
--
-- appointments.manual_reminder_sent_at: clinic wall-clock time (no zone) of the last MANUAL reminder a doctor sent
-- for the appointment (POST /api/doctors/appointments/{id}/send-reminder). NULL when none was ever sent. It is not
-- mapped on the Appointment entity: it is read and written only by DoctorScheduleQueryRepository with native SQL,
-- so a normal appointment save never overwrites it. The automatic reminder flags (reminder_sent,
-- reminder_1h_sent) are independent of it.

ALTER TABLE appointments ADD COLUMN IF NOT EXISTS manual_reminder_sent_at TIMESTAMP NULL;
COMMENT ON COLUMN appointments.manual_reminder_sent_at IS
    'Clinic wall-clock time of the last manual reminder sent by the doctor; NULL when never sent';

-- Doctor schedule window, search and needs-outcome lists: one doctor, ordered by start then id.
CREATE INDEX IF NOT EXISTS idx_appointments_doctor_scheduled_id
    ON appointments (doctor_system_id, scheduled_at, appointment_id);

-- Doctor dashboard and stats: consultation counts per status of one doctor.
CREATE INDEX IF NOT EXISTS idx_consultations_doctor_status
    ON consultations (doctor_system_id, status);
