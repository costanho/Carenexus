-- V38 (be-medication): medication schedules keyed by the patient's system id, and per-prescription dose plans.
--
-- medication_schedules (V1) holds one row per dose: one prescription, one clinic calendar day, one time of day.
-- It is the single source of medication reminders: be-reminder-jobs reads due rows with native SQL using the
-- columns schedule_id, prescription_id, patient_system_id, schedule_date, scheduled_time, medication_name,
-- is_taken and missed (CONTRACTS.v2 6.1).
--
-- Changes (non-destructive; nothing is dropped):
--   * patient_system_id VARCHAR(20) NOT NULL REFERENCES users(system_id), backfilled through patients (and, for
--     a row whose legacy patient_id cannot be resolved, through its prescription);
--   * marked_by_system_id: who set the current status (patient or caregiver; NULL while pending and when the
--     server marked the dose missed);
--   * the legacy integer patient_id becomes nullable and is no longer written;
--   * one dose per prescription, day and time (duplicates in existing dev data are removed first, keeping the
--     lowest schedule_id), an index for the per-patient day lists, and a partial index for the missed-dose sweep;
--   * a dose is never both taken and missed.
-- New table medication_schedule_plans: the daily times ("HH:mm", clinic wall-clock) of one prescription.

ALTER TABLE medication_schedules ADD COLUMN IF NOT EXISTS patient_system_id VARCHAR(20);
ALTER TABLE medication_schedules ADD COLUMN IF NOT EXISTS marked_by_system_id VARCHAR(20);

UPDATE medication_schedules ms
SET patient_system_id = p.system_id
FROM patients p
WHERE ms.patient_system_id IS NULL
  AND ms.patient_id = p.patient_id
  AND p.system_id IS NOT NULL;

UPDATE medication_schedules ms
SET patient_system_id = rx.patient_system_id
FROM prescriptions rx
WHERE ms.patient_system_id IS NULL
  AND ms.prescription_id = rx.prescription_id;

-- Every row references a prescription (NOT NULL FK, ON DELETE CASCADE), and every prescription has a patient
-- system id since V3, so no row is left without one. This guard only protects a hand-edited database: a dose
-- that cannot be attributed to any patient is unusable and would block the NOT NULL constraint below.
DELETE FROM medication_schedules WHERE patient_system_id IS NULL;

-- One dose per prescription, day and time: keep the lowest schedule_id of each duplicate group.
DELETE FROM medication_schedules a
USING medication_schedules b
WHERE a.prescription_id = b.prescription_id
  AND a.schedule_date = b.schedule_date
  AND a.scheduled_time = b.scheduled_time
  AND a.schedule_id > b.schedule_id;

-- A dose is pending, taken or missed. Legacy rows flagged as both count as taken.
UPDATE medication_schedules SET missed = FALSE WHERE is_taken = TRUE AND missed = TRUE;

ALTER TABLE medication_schedules ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE medication_schedules ALTER COLUMN patient_id DROP NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_medication_schedules_patient_system_id') THEN
        ALTER TABLE medication_schedules
            ADD CONSTRAINT fk_medication_schedules_patient_system_id
            FOREIGN KEY (patient_system_id) REFERENCES users(system_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_medication_schedules_dose') THEN
        ALTER TABLE medication_schedules
            ADD CONSTRAINT uq_medication_schedules_dose UNIQUE (prescription_id, schedule_date, scheduled_time);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_medication_schedules_status') THEN
        ALTER TABLE medication_schedules
            ADD CONSTRAINT ck_medication_schedules_status CHECK (NOT (is_taken AND missed));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_ms_patient_system_date
    ON medication_schedules (patient_system_id, schedule_date);

-- The sweeper looks for pending doses whose time (plus the grace period) has passed.
CREATE INDEX IF NOT EXISTS idx_ms_pending_due
    ON medication_schedules (schedule_date, scheduled_time)
    WHERE is_taken = FALSE AND missed = FALSE;

COMMENT ON COLUMN medication_schedules.patient_system_id IS 'Patient the dose belongs to (users.system_id)';
COMMENT ON COLUMN medication_schedules.marked_by_system_id IS
    'Who set the current status (patient or caregiver); NULL while pending and when the server marked it missed';
COMMENT ON COLUMN medication_schedules.patient_id IS 'Legacy integer patient id; no longer written (use patient_system_id)';

CREATE TABLE IF NOT EXISTS medication_schedule_plans (
    prescription_id      INTEGER PRIMARY KEY REFERENCES prescriptions(prescription_id) ON DELETE CASCADE,
    times                JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_by_system_id VARCHAR(20) REFERENCES users(system_id),
    updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_medication_schedule_plans_times_array CHECK (jsonb_typeof(times) = 'array')
);

COMMENT ON TABLE medication_schedule_plans IS
    'Daily dose times of a prescription: JSON array of "HH:mm" clinic wall-clock strings, distinct and ascending';
