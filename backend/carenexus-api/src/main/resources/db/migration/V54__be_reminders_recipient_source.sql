-- V54 (be-reminders): reminders keyed by system ids, with a recipient, a creator, the next due time and a source.
--
-- reminders (V1) was keyed by the legacy integer patients.patient_id and had no code writing it. Changes
-- (non-destructive; nothing is dropped):
--   * patient_system_id VARCHAR(20) NOT NULL REFERENCES users(system_id): whose data the reminder is about,
--     backfilled through patients;
--   * recipient_system_id VARCHAR(20) NOT NULL REFERENCES users(system_id): who is notified (the patient, or the
--     doctor for result-review and the doctor's copy of a care-plan review reminder), backfilled to the patient;
--   * created_by_system_id: who created a manual reminder (NULL for reminders created by events);
--   * next_due_at: clinic wall-clock time of the next occurrence; non-null exactly when is_active;
--   * source_type / source_id: MANUAL (no source id), or the consultation, lab result, imaging result or care plan
--     the reminder was created from;
--   * the legacy integer patient_id becomes nullable and is no longer written;
--   * one automatic reminder per source, type, recipient and time (partial unique index used by the event
--     listeners' INSERT ... ON CONFLICT), plus the list and dispatch indexes.
-- The V1 type and frequency CHECKs are unchanged (CUSTOM stays allowed by the table but is never written).

ALTER TABLE reminders ADD COLUMN IF NOT EXISTS patient_system_id VARCHAR(20);
ALTER TABLE reminders ADD COLUMN IF NOT EXISTS recipient_system_id VARCHAR(20);
ALTER TABLE reminders ADD COLUMN IF NOT EXISTS created_by_system_id VARCHAR(20);
ALTER TABLE reminders ADD COLUMN IF NOT EXISTS next_due_at TIMESTAMP;
ALTER TABLE reminders ADD COLUMN IF NOT EXISTS source_type VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE reminders ADD COLUMN IF NOT EXISTS source_id INTEGER;

UPDATE reminders r
SET patient_system_id = p.system_id
FROM patients p
WHERE r.patient_system_id IS NULL
  AND r.patient_id = p.patient_id;

-- patient_id is NOT NULL with ON DELETE CASCADE and patients.system_id is NOT NULL since V3, so every row has a
-- patient. This guard only protects a hand-edited database: a row without a patient cannot be shown to anyone.
DELETE FROM reminders WHERE patient_system_id IS NULL;

UPDATE reminders SET recipient_system_id = patient_system_id WHERE recipient_system_id IS NULL;

-- Rows written before this version (by hand: no code wrote this table) have no next due time. They are paused,
-- so the dispatcher never sends a stale occurrence; the patient can resume them, which computes the next time.
UPDATE reminders SET is_active = FALSE WHERE next_due_at IS NULL AND is_active = TRUE;

ALTER TABLE reminders ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE reminders ALTER COLUMN recipient_system_id SET NOT NULL;
ALTER TABLE reminders ALTER COLUMN patient_id DROP NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_reminders_patient_system_id') THEN
        ALTER TABLE reminders
            ADD CONSTRAINT fk_reminders_patient_system_id
            FOREIGN KEY (patient_system_id) REFERENCES users(system_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_reminders_recipient_system_id') THEN
        ALTER TABLE reminders
            ADD CONSTRAINT fk_reminders_recipient_system_id
            FOREIGN KEY (recipient_system_id) REFERENCES users(system_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_reminders_source_type') THEN
        ALTER TABLE reminders
            ADD CONSTRAINT ck_reminders_source_type
            CHECK (source_type IN ('CONSULTATION', 'LAB_RESULT', 'IMAGING_RESULT', 'CARE_PLAN', 'MANUAL'));
    END IF;
    -- A manual reminder has no source id; an automatic one always has one (so the unique index below applies).
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_reminders_source_id') THEN
        ALTER TABLE reminders
            ADD CONSTRAINT ck_reminders_source_id
            CHECK ((source_type = 'MANUAL') = (source_id IS NULL));
    END IF;
    -- An active reminder always knows when it fires next; a paused or ended one never does.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_reminders_next_due') THEN
        ALTER TABLE reminders
            ADD CONSTRAINT ck_reminders_next_due
            CHECK (is_active = (next_due_at IS NOT NULL));
    END IF;
END $$;

-- One automatic reminder per source, type, recipient and time of day (target of the listeners' ON CONFLICT).
CREATE UNIQUE INDEX IF NOT EXISTS ux_reminders_source
    ON reminders (source_type, source_id, type, recipient_system_id, reminder_time)
    WHERE source_type <> 'MANUAL';

-- GET /api/reminders/patient/{id}: rows whose patient and recipient are that patient.
CREATE INDEX IF NOT EXISTS idx_reminders_patient_recipient
    ON reminders (patient_system_id, recipient_system_id);

-- ReminderDispatchJob: active reminders whose next due time has come.
CREATE INDEX IF NOT EXISTS idx_reminders_due
    ON reminders (next_due_at)
    WHERE is_active;

COMMENT ON COLUMN reminders.patient_system_id IS 'Patient the reminder is about (users.system_id)';
COMMENT ON COLUMN reminders.recipient_system_id IS 'Who is notified: the patient, or a doctor for review reminders';
COMMENT ON COLUMN reminders.created_by_system_id IS 'Creator of a manual reminder; NULL for reminders created by events';
COMMENT ON COLUMN reminders.next_due_at IS 'Clinic wall-clock time of the next occurrence; NULL when paused or ended';
COMMENT ON COLUMN reminders.source_type IS 'MANUAL, CONSULTATION, LAB_RESULT, IMAGING_RESULT or CARE_PLAN';
COMMENT ON COLUMN reminders.source_id IS 'Consultation, lab result, imaging result or care plan id; NULL for MANUAL';
COMMENT ON COLUMN reminders.patient_id IS 'Legacy integer key (V1); no longer written, use patient_system_id';
