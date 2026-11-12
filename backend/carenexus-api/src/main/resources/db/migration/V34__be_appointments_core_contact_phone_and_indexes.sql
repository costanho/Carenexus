-- V34 (be-appointments-core): contact phone for PHONE appointments, plus list and reminder lookup indexes.
--
-- appointments.contact_phone: the number the doctor calls for a PHONE appointment (NULL for other types). Filled
-- from the request, else from the patient's number on file: COALESCE(users.phone, patients.contact_phone).
--
-- patients.contact_phone: a patient's own contact number when the account has none (managed dependents created by
-- a caregiver). be-caregiver-core (V43) adds the same column with the same type; both use ADD COLUMN IF NOT EXISTS
-- so either may run first on any database.

ALTER TABLE appointments ADD COLUMN IF NOT EXISTS contact_phone VARCHAR(20) NULL;
COMMENT ON COLUMN appointments.contact_phone IS 'Number the doctor calls for a PHONE appointment; NULL for other types';

ALTER TABLE patients ADD COLUMN IF NOT EXISTS contact_phone VARCHAR(20) NULL;

-- Canonical list GET /api/appointments: filters by patient or doctor, ordered by start time.
CREATE INDEX IF NOT EXISTS idx_appointments_patient_scheduled
    ON appointments (patient_system_id, scheduled_at);
CREATE INDEX IF NOT EXISTS idx_appointments_doctor_scheduled
    ON appointments (doctor_system_id, scheduled_at);

-- Reminder jobs: active appointments whose reminder has not gone out, by start time.
CREATE INDEX IF NOT EXISTS idx_appointments_reminder_lookup
    ON appointments (status, reminder_sent, scheduled_at);
