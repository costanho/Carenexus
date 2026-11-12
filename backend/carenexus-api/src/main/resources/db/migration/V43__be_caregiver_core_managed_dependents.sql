-- ============================================================
-- V43 (be-caregiver-core): managed dependents, guardian attestation, activity feed indexes.
--   patients.contact_phone: a contact number for a patient whose number is shared with another account (a family
--     phone) or who has no number of their own. users.phone stays unique; the effective phone everywhere is
--     COALESCE(users.phone, patients.contact_phone). V34 (be-appointments-core) adds the same column the same way,
--     so both use ADD COLUMN IF NOT EXISTS with the same type.
--   dependent_access.guardian_attested_at: when the caregiver who created the dependent attested to being the
--     patient's parent or legal guardian (required for a managed dependent without sign-in details).
--   Two indexes so the caregiver activity feed filters and orders in the database (window + page).
-- ============================================================

ALTER TABLE patients ADD COLUMN IF NOT EXISTS contact_phone VARCHAR(20) NULL;
COMMENT ON COLUMN patients.contact_phone IS
  'Contact number when users.phone is empty (shared family number or none of their own); effective phone = COALESCE(users.phone, patients.contact_phone)';

ALTER TABLE dependent_access ADD COLUMN IF NOT EXISTS guardian_attested_at TIMESTAMPTZ NULL;
COMMENT ON COLUMN dependent_access.guardian_attested_at IS
  'When the creating caregiver attested to being the patient''s parent or legal guardian; NULL when not attested';

CREATE INDEX IF NOT EXISTS idx_appointments_patient_scheduled
  ON appointments (patient_system_id, scheduled_at DESC, appointment_id DESC);

CREATE INDEX IF NOT EXISTS idx_consultations_patient_created
  ON consultations (patient_system_id, created_at DESC, consultation_id DESC);
