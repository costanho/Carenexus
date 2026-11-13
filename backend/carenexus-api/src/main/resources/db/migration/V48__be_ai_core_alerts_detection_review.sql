-- ============================================================
-- V48: AI alerts and the detection review columns (be-ai-core)
--   1. ai_alerts        : system-id keys (already added by V3, ensured here), title, source of the alert,
--                         dedupe key (unique), resolved_at, alert_type CHECK widened with CRITICAL_RESULT,
--                         source_type CHECK, inbox and per-patient indexes
--   2. disease_detection: system-id patient key (already added by V3, ensured here), review_status
--                         (PENDING_REVIEW, CONFIRMED, REJECTED), review_notes, reviewed_by_system_id, reviewed_at
--
-- Nothing is dropped. V3 already re-keyed both tables to *_system_id and removed the integer patient_id,
-- doctor_id, acknowledged_by and reviewed_by columns; the guarded blocks below backfill and relax them only on a
-- database where they still exist. The V3 views (v_patient_dashboard, v_doctor_workload) read only
-- ai_alerts.alert_id, patient_system_id, doctor_system_id and is_acknowledged, which are unchanged.
-- No code path or seed script has written either table before this migration; existing rows are still handled:
-- every existing detection becomes PENDING_REVIEW.
-- ============================================================

-- ------------------------------------------------------------
-- 1. ai_alerts
-- ------------------------------------------------------------
ALTER TABLE ai_alerts ADD COLUMN IF NOT EXISTS patient_system_id         VARCHAR(20);
ALTER TABLE ai_alerts ADD COLUMN IF NOT EXISTS doctor_system_id          VARCHAR(20);
ALTER TABLE ai_alerts ADD COLUMN IF NOT EXISTS acknowledged_by_system_id VARCHAR(20);
ALTER TABLE ai_alerts ADD COLUMN IF NOT EXISTS title                     VARCHAR(160);
ALTER TABLE ai_alerts ADD COLUMN IF NOT EXISTS source_type               VARCHAR(30);
ALTER TABLE ai_alerts ADD COLUMN IF NOT EXISTS source_id                 INTEGER;
ALTER TABLE ai_alerts ADD COLUMN IF NOT EXISTS dedupe_key                VARCHAR(160);
ALTER TABLE ai_alerts ADD COLUMN IF NOT EXISTS resolved_at               TIMESTAMP;

DO $$
BEGIN
    -- Legacy integer keys (removed by V3 on every database migrated in order): backfill and make nullable.
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'ai_alerts' AND column_name = 'patient_id') THEN
        EXECUTE 'UPDATE ai_alerts a SET patient_system_id = p.system_id FROM patients p '
             || 'WHERE p.patient_id = a.patient_id AND a.patient_system_id IS NULL';
        EXECUTE 'ALTER TABLE ai_alerts ALTER COLUMN patient_id DROP NOT NULL';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'ai_alerts' AND column_name = 'doctor_id') THEN
        EXECUTE 'UPDATE ai_alerts a SET doctor_system_id = d.system_id FROM doctors d '
             || 'WHERE d.doctor_id = a.doctor_id AND a.doctor_system_id IS NULL';
        EXECUTE 'ALTER TABLE ai_alerts ALTER COLUMN doctor_id DROP NOT NULL';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'ai_alerts' AND column_name = 'acknowledged_by') THEN
        EXECUTE 'UPDATE ai_alerts a SET acknowledged_by_system_id = u.system_id FROM users u '
             || 'WHERE u.user_id = a.acknowledged_by AND a.acknowledged_by_system_id IS NULL';
        EXECUTE 'ALTER TABLE ai_alerts ALTER COLUMN acknowledged_by DROP NOT NULL';
    END IF;
END $$;

ALTER TABLE ai_alerts ALTER COLUMN patient_system_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'ai_alerts'::regclass AND conname = 'ai_alerts_patient_system_id_fkey') THEN
        ALTER TABLE ai_alerts ADD CONSTRAINT ai_alerts_patient_system_id_fkey
            FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'ai_alerts'::regclass AND conname = 'ai_alerts_doctor_system_id_fkey') THEN
        ALTER TABLE ai_alerts ADD CONSTRAINT ai_alerts_doctor_system_id_fkey
            FOREIGN KEY (doctor_system_id) REFERENCES users(system_id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'ai_alerts'::regclass AND conname = 'ai_alerts_acknowledged_by_system_id_fkey') THEN
        ALTER TABLE ai_alerts ADD CONSTRAINT ai_alerts_acknowledged_by_system_id_fkey
            FOREIGN KEY (acknowledged_by_system_id) REFERENCES users(system_id) ON DELETE SET NULL;
    END IF;
END $$;

DO $$
DECLARE
    old_check RECORD;
BEGIN
    -- Replace the V1 CHECK on alert_type (without CRITICAL_RESULT), whatever it was named.
    FOR old_check IN
        SELECT conname
          FROM pg_constraint
         WHERE conrelid = 'ai_alerts'::regclass
           AND contype = 'c'
           AND pg_get_constraintdef(oid) LIKE '%alert_type%'
           AND conname <> 'ck_ai_alerts_alert_type'
    LOOP
        EXECUTE format('ALTER TABLE ai_alerts DROP CONSTRAINT %I', old_check.conname);
    END LOOP;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'ai_alerts'::regclass AND conname = 'ck_ai_alerts_alert_type') THEN
        ALTER TABLE ai_alerts ADD CONSTRAINT ck_ai_alerts_alert_type CHECK (alert_type IN (
            'CRITICAL_VITALS', 'DISEASE_RISK', 'MEDICATION_INTERACTION', 'MISSED_MEDICATION',
            'FOLLOW_UP_DUE', 'ANOMALY_DETECTED', 'CRITICAL_RESULT'));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'ai_alerts'::regclass AND conname = 'ck_ai_alerts_source_type') THEN
        ALTER TABLE ai_alerts ADD CONSTRAINT ck_ai_alerts_source_type CHECK (source_type IS NULL OR source_type IN (
            'LAB_RESULT', 'IMAGING_RESULT', 'HEALTH_METRIC', 'SYMPTOM_LOG', 'PRESCRIPTION',
            'MEDICATION_SCHEDULE', 'CONSULTATION'));
    END IF;

    -- The source is either fully named or absent.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'ai_alerts'::regclass AND conname = 'ck_ai_alerts_source_pair') THEN
        ALTER TABLE ai_alerts ADD CONSTRAINT ck_ai_alerts_source_pair
            CHECK ((source_type IS NULL) = (source_id IS NULL));
    END IF;
END $$;

-- One alert per dedupe key (NULL keys never conflict). AiAlertService inserts with ON CONFLICT (dedupe_key) DO NOTHING.
CREATE UNIQUE INDEX IF NOT EXISTS ux_ai_alerts_dedupe_key ON ai_alerts (dedupe_key);

-- Doctor inbox (alerts addressed to a doctor, unacknowledged first in the badge count)
CREATE INDEX IF NOT EXISTS idx_ai_alerts_doctor_ack_created
    ON ai_alerts (doctor_system_id, is_acknowledged, created_at DESC);

-- One patient's alerts, newest first (patient view and the related-patient part of the doctor inbox)
CREATE INDEX IF NOT EXISTS idx_ai_alerts_patient_created
    ON ai_alerts (patient_system_id, created_at DESC, alert_id DESC);

-- Join to the detection for the patient-facing filter
CREATE INDEX IF NOT EXISTS idx_ai_alerts_detection ON ai_alerts (detection_id) WHERE detection_id IS NOT NULL;

COMMENT ON COLUMN ai_alerts.title IS 'Short heading (max 160); NULL means the default title of the alert type';
COMMENT ON COLUMN ai_alerts.source_type IS 'Record that triggered the alert (LAB_RESULT, IMAGING_RESULT, HEALTH_METRIC, SYMPTOM_LOG, PRESCRIPTION, MEDICATION_SCHEDULE, CONSULTATION)';
COMMENT ON COLUMN ai_alerts.source_id IS 'Id of the source record; set together with source_type';
COMMENT ON COLUMN ai_alerts.dedupe_key IS 'Idempotency key of the raiser; a repeat inserts nothing';
COMMENT ON COLUMN ai_alerts.resolved_at IS 'Reserved: not written or exposed by the API yet';

-- ------------------------------------------------------------
-- 2. disease_detection
-- ------------------------------------------------------------
ALTER TABLE disease_detection ADD COLUMN IF NOT EXISTS patient_system_id     VARCHAR(20);
ALTER TABLE disease_detection ADD COLUMN IF NOT EXISTS reviewed_by_system_id VARCHAR(20);
ALTER TABLE disease_detection ADD COLUMN IF NOT EXISTS review_status         VARCHAR(20);
ALTER TABLE disease_detection ADD COLUMN IF NOT EXISTS review_notes          TEXT;
ALTER TABLE disease_detection ADD COLUMN IF NOT EXISTS reviewed_at           TIMESTAMP;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'disease_detection' AND column_name = 'patient_id') THEN
        EXECUTE 'UPDATE disease_detection dd SET patient_system_id = p.system_id FROM patients p '
             || 'WHERE p.patient_id = dd.patient_id AND dd.patient_system_id IS NULL';
        EXECUTE 'ALTER TABLE disease_detection ALTER COLUMN patient_id DROP NOT NULL';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'disease_detection' AND column_name = 'reviewed_by') THEN
        EXECUTE 'UPDATE disease_detection dd SET reviewed_by_system_id = d.system_id FROM doctors d '
             || 'WHERE d.doctor_id = dd.reviewed_by AND dd.reviewed_by_system_id IS NULL';
    END IF;
END $$;

ALTER TABLE disease_detection ALTER COLUMN patient_system_id SET NOT NULL;

-- Existing rows have never been reviewed through the workflow (no code path wrote is_confirmed): PENDING_REVIEW,
-- so nothing becomes visible to a patient or caregiver without a doctor's review.
UPDATE disease_detection
   SET review_status = 'PENDING_REVIEW'
 WHERE review_status IS NULL;

ALTER TABLE disease_detection ALTER COLUMN review_status SET DEFAULT 'PENDING_REVIEW';
ALTER TABLE disease_detection ALTER COLUMN review_status SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'disease_detection'::regclass AND conname = 'ck_disease_detection_review_status') THEN
        ALTER TABLE disease_detection ADD CONSTRAINT ck_disease_detection_review_status
            CHECK (review_status IN ('PENDING_REVIEW', 'CONFIRMED', 'REJECTED'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'disease_detection'::regclass
                      AND conname = 'disease_detection_reviewed_by_system_id_fkey') THEN
        ALTER TABLE disease_detection ADD CONSTRAINT disease_detection_reviewed_by_system_id_fkey
            FOREIGN KEY (reviewed_by_system_id) REFERENCES users(system_id) ON DELETE SET NULL;
    END IF;
END $$;

COMMENT ON COLUMN disease_detection.review_status IS 'Doctor review of the screening result: PENDING_REVIEW, CONFIRMED, REJECTED. Only CONFIRMED results reach the patient and caregivers';
COMMENT ON COLUMN disease_detection.review_notes IS 'Reviewing doctor''s note; never shown to the patient or caregivers';
