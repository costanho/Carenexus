-- ============================================================
-- V66: disease detection pipeline columns (be-ai-detection), building on V48
--   source_type / source_id : what triggered an automatic screening (SYMPTOM_LOG or HEALTH_METRIC); both NULL for a
--                             screening a doctor ran by hand (doctor_system_id then holds that doctor)
--   dedupe_key              : 'DISEASE_RISK:<patient>:<primary condition code>:<yyyy-MM-dd clinic date>', unique, so
--                             there is at most one detection per patient, primary condition and clinic day. The
--                             pipeline inserts with ON CONFLICT (dedupe_key) DO NOTHING
--   review_notes            : TEXT (V48 already adds it as TEXT; widened here on a database where it is narrower)
--   index (patient_system_id, review_status, created_at DESC) for the per-patient list and its status filter
--
-- Nothing is dropped. Existing rows keep NULL source and dedupe key (NULL keys never conflict).
-- ============================================================

ALTER TABLE disease_detection ADD COLUMN IF NOT EXISTS doctor_system_id VARCHAR(20);
ALTER TABLE disease_detection ADD COLUMN IF NOT EXISTS source_type      VARCHAR(30);
ALTER TABLE disease_detection ADD COLUMN IF NOT EXISTS source_id        INTEGER;
ALTER TABLE disease_detection ADD COLUMN IF NOT EXISTS dedupe_key       VARCHAR(160);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'disease_detection'
                  AND column_name = 'review_notes' AND data_type <> 'text') THEN
        ALTER TABLE disease_detection ALTER COLUMN review_notes TYPE TEXT;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'disease_detection'::regclass AND conname = 'ck_disease_detection_source_type') THEN
        ALTER TABLE disease_detection ADD CONSTRAINT ck_disease_detection_source_type
            CHECK (source_type IS NULL OR source_type IN ('SYMPTOM_LOG', 'HEALTH_METRIC'));
    END IF;

    -- The source is either fully named or absent.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'disease_detection'::regclass AND conname = 'ck_disease_detection_source_pair') THEN
        ALTER TABLE disease_detection ADD CONSTRAINT ck_disease_detection_source_pair
            CHECK ((source_type IS NULL) = (source_id IS NULL));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'disease_detection'::regclass
                      AND conname = 'disease_detection_doctor_system_id_fkey') THEN
        ALTER TABLE disease_detection ADD CONSTRAINT disease_detection_doctor_system_id_fkey
            FOREIGN KEY (doctor_system_id) REFERENCES users(system_id) ON DELETE SET NULL;
    END IF;
END $$;

-- One detection per patient, primary condition and clinic day (NULL keys never conflict).
CREATE UNIQUE INDEX IF NOT EXISTS ux_disease_detection_dedupe_key ON disease_detection (dedupe_key);

-- Per-patient list: patients and caregivers see CONFIRMED rows only; doctors may filter by review status.
CREATE INDEX IF NOT EXISTS idx_dd_patient_review_created
    ON disease_detection (patient_system_id, review_status, created_at DESC);

-- Per-patient list without a status filter (doctors and administrators), newest first.
CREATE INDEX IF NOT EXISTS idx_dd_patient_created
    ON disease_detection (patient_system_id, created_at DESC, detection_id DESC);

COMMENT ON COLUMN disease_detection.source_type IS 'What triggered an automatic screening: SYMPTOM_LOG or HEALTH_METRIC; NULL when a doctor ran it (doctor_system_id)';
COMMENT ON COLUMN disease_detection.source_id IS 'symptom_logs.symptom_id or health_metrics.metric_id; set together with source_type';
COMMENT ON COLUMN disease_detection.dedupe_key IS 'DISEASE_RISK:<patient>:<primary condition code>:<clinic date>; equals the dedupe key of the DISEASE_RISK alert';
COMMENT ON COLUMN disease_detection.input_data IS 'De-identified snapshot the risk provider received; never returned by the API';
