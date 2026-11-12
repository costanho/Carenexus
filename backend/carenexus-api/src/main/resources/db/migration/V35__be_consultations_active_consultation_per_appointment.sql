-- V35 (be-consultations): at most one live consultation per appointment, plus indexes for the consultation lists.
--
-- ConsultationService already refuses a second non-cancelled consultation for an appointment, and serialises
-- creates by locking the appointment row. This partial unique index is the database safety net: whatever code path
-- inserts, two consultations with a status other than CANCELLED can never document the same appointment. A
-- consultation cancelled by the doctor does not block a new one for the same (still active) appointment.
--
-- Existing data: like V13, the index is only created when the current rows satisfy it. A database that already
-- holds duplicates does not fail the migration; the index is skipped with a NOTICE so the rows can be cleaned up
-- and the index created by hand (same statement as below).

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM consultations
        WHERE status <> 'CANCELLED'
        GROUP BY appointment_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE NOTICE 'V35: skipped ux_consultations_appointment_active: an appointment has several non-cancelled consultations';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS ux_consultations_appointment_active
            ON consultations (appointment_id)
            WHERE status <> 'CANCELLED';
    END IF;
END
$$;

-- Canonical paged list and legacy lists: filter by patient or doctor, newest first (created_at, then id).
CREATE INDEX IF NOT EXISTS idx_consultations_patient_created
    ON consultations (patient_system_id, created_at DESC, consultation_id DESC);
CREATE INDEX IF NOT EXISTS idx_consultations_doctor_created
    ON consultations (doctor_system_id, created_at DESC, consultation_id DESC);

-- ConsultationResponse.medicalRecordId: batch lookup of the CONSULT record of each consultation.
CREATE INDEX IF NOT EXISTS idx_mr_consultation_lookup
    ON medical_records (consultation_id)
    WHERE consultation_id IS NOT NULL;
