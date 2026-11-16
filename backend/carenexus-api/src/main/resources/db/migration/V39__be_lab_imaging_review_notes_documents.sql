-- ============================================================
-- V39: lab and imaging results (be-lab-imaging)
--   1. review_notes on both tables (note written by the reviewing doctor)
--   2. document_id on both tables: the attached document (documents, V1), ON DELETE SET NULL
--   3. imaging_results.status may be CANCELLED (results are cancelled, never hard-deleted)
--   4. indexes for the paged lists (newest first) and the doctor review queues
--   5. one-off normalisation of lab criticality: status CRITICAL <=> is_critical on a recorded result
-- file_url / thumbnail_url stay in place; the API no longer writes or returns them.
-- Written defensively (IF NOT EXISTS / guarded blocks) because dev databases already contain seed data.
-- Nothing is dropped.
-- ============================================================

-- ------------------------------------------------------------
-- 1. Review notes
-- ------------------------------------------------------------
ALTER TABLE lab_results     ADD COLUMN IF NOT EXISTS review_notes VARCHAR(2000);
ALTER TABLE imaging_results ADD COLUMN IF NOT EXISTS review_notes VARCHAR(2000);

-- ------------------------------------------------------------
-- 2. Attachment reference (a document of the same patient; checked by the API)
-- ------------------------------------------------------------
ALTER TABLE lab_results     ADD COLUMN IF NOT EXISTS document_id INTEGER;
ALTER TABLE imaging_results ADD COLUMN IF NOT EXISTS document_id INTEGER;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'lab_results_document_id_fkey' AND conrelid = 'lab_results'::regclass) THEN
        ALTER TABLE lab_results
            ADD CONSTRAINT lab_results_document_id_fkey
            FOREIGN KEY (document_id) REFERENCES documents(document_id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'imaging_results_document_id_fkey' AND conrelid = 'imaging_results'::regclass) THEN
        ALTER TABLE imaging_results
            ADD CONSTRAINT imaging_results_document_id_fkey
            FOREIGN KEY (document_id) REFERENCES documents(document_id) ON DELETE SET NULL;
    END IF;
END $$;

COMMENT ON COLUMN lab_results.document_id IS 'Attached document (documents.document_id) of the same patient; null when none';
COMMENT ON COLUMN imaging_results.document_id IS 'Attached document (documents.document_id) of the same patient; null when none';
COMMENT ON COLUMN lab_results.file_url IS 'Legacy free-text URL; no longer written or returned (attachments use document_id)';
COMMENT ON COLUMN imaging_results.file_url IS 'Legacy free-text URL; no longer written or returned (attachments use document_id)';
COMMENT ON COLUMN imaging_results.thumbnail_url IS 'Legacy free-text URL; no longer written or returned (attachments use document_id)';

-- ------------------------------------------------------------
-- 3. imaging_results.status gains CANCELLED (same set as lab_results)
-- ------------------------------------------------------------
DO $$
DECLARE
    c RECORD;
BEGIN
    FOR c IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'imaging_results'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) ILIKE '%status%'
          AND pg_get_constraintdef(oid) NOT ILIKE '%CANCELLED%'
    LOOP
        EXECUTE format('ALTER TABLE imaging_results DROP CONSTRAINT %I', c.conname);
    END LOOP;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'imaging_results_status_check' AND conrelid = 'imaging_results'::regclass) THEN
        ALTER TABLE imaging_results
            ADD CONSTRAINT imaging_results_status_check
            CHECK (status IN ('PENDING', 'REQUIRES_REVIEW', 'COMPLETED', 'CRITICAL', 'CANCELLED'));
    END IF;
END $$;

-- ------------------------------------------------------------
-- 4. Indexes: lists filter by patient or author and sort newest first; the review queues read unreviewed
--    REQUIRES_REVIEW / CRITICAL rows; consultation and document lookups.
-- ------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_lab_results_patient_date
    ON lab_results (patient_system_id, test_date DESC, result_id DESC);
CREATE INDEX IF NOT EXISTS idx_lab_results_doctor_date
    ON lab_results (doctor_system_id, test_date DESC, result_id DESC);
CREATE INDEX IF NOT EXISTS idx_lab_results_date
    ON lab_results (test_date DESC, result_id DESC);
CREATE INDEX IF NOT EXISTS idx_lab_results_pending_review
    ON lab_results (doctor_system_id, patient_system_id)
    WHERE reviewed_at IS NULL AND status IN ('REQUIRES_REVIEW', 'CRITICAL');
CREATE INDEX IF NOT EXISTS idx_lab_results_consultation ON lab_results (consultation_id);
CREATE INDEX IF NOT EXISTS idx_lab_results_document ON lab_results (document_id) WHERE document_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_imaging_results_patient_date
    ON imaging_results (patient_system_id, image_date DESC, imaging_id DESC);
CREATE INDEX IF NOT EXISTS idx_imaging_results_doctor_date
    ON imaging_results (doctor_system_id, image_date DESC, imaging_id DESC);
CREATE INDEX IF NOT EXISTS idx_imaging_results_date
    ON imaging_results (image_date DESC, imaging_id DESC);
CREATE INDEX IF NOT EXISTS idx_imaging_results_pending_review
    ON imaging_results (doctor_system_id, patient_system_id)
    WHERE reviewed_at IS NULL AND status IN ('REQUIRES_REVIEW', 'CRITICAL');
CREATE INDEX IF NOT EXISTS idx_imaging_results_consultation ON imaging_results (consultation_id);
CREATE INDEX IF NOT EXISTS idx_imaging_results_document ON imaging_results (document_id) WHERE document_id IS NOT NULL;

-- ------------------------------------------------------------
-- 5. Lab criticality, once: status CRITICAL sets the flag; a flagged COMPLETED result is CRITICAL;
--    a cancelled result is never flagged.
-- ------------------------------------------------------------
UPDATE lab_results SET is_critical = TRUE  WHERE status = 'CRITICAL' AND is_critical = FALSE;
UPDATE lab_results SET status = 'CRITICAL' WHERE status = 'COMPLETED' AND is_critical = TRUE;
UPDATE lab_results SET is_critical = FALSE WHERE status = 'CANCELLED' AND is_critical = TRUE;
