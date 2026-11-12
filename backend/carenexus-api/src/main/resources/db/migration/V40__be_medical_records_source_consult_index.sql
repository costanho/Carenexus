-- V40 (be-medical-records): record origin, one live CONSULT record per consultation, list indexes.
--
-- source: MANUAL for records filed through POST /api/medical-records (every row that exists before this
-- migration), CONSULTATION for the record created automatically when a consultation is completed.
--
-- ux_medical_records_consultation_consult: at most one live (not soft-deleted) CONSULT record per consultation.
-- It makes the automatic consultation record idempotent (INSERT ... ON CONFLICT DO NOTHING) and backs the 409 for
-- a second CONSULT record filed by hand. Records without a consultation are not constrained (NULLs are distinct).
-- Like V13, the index is only created when the existing rows satisfy it; otherwise the migration raises a NOTICE
-- and the duplicates must be cleaned up and the index created by hand.
--
-- The two list indexes serve GET /api/medical-records (newest first, per patient or per author).

ALTER TABLE medical_records ADD COLUMN IF NOT EXISTS source VARCHAR(20) NOT NULL DEFAULT 'MANUAL';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'medical_records_source_check'
    ) THEN
        ALTER TABLE medical_records
            ADD CONSTRAINT medical_records_source_check CHECK (source IN ('MANUAL', 'CONSULTATION'));
    END IF;

    IF EXISTS (
        SELECT 1 FROM medical_records
        WHERE record_type = 'CONSULT' AND is_deleted = FALSE AND consultation_id IS NOT NULL
        GROUP BY consultation_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE NOTICE 'V40: skipped ux_medical_records_consultation_consult: a consultation has several live CONSULT records';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS ux_medical_records_consultation_consult
            ON medical_records (consultation_id)
            WHERE record_type = 'CONSULT' AND is_deleted = FALSE;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_medical_records_patient_created
    ON medical_records (patient_system_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_medical_records_doctor_created
    ON medical_records (doctor_system_id, created_at DESC);

COMMENT ON COLUMN medical_records.source IS 'MANUAL (filed by a doctor) or CONSULTATION (created when the consultation was completed)';
