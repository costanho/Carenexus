-- V47 (be-health-tracking): re-key health_metrics and symptom_logs on users.system_id and add the columns the
-- health module needs.
--
-- health_metrics
--   patient_system_id     VARCHAR(20) NOT NULL, FK users(system_id), backfilled through patients.patient_id
--   recorded_by_system_id VARCHAR(20) NULL, FK users(system_id) ON DELETE SET NULL (who entered the reading)
--   reading_group_id      VARCHAR(36) NULL: UUID string shared by the readings stored by one request
--                         (pairs BP_SYSTOLIC with BP_DIASTOLIC); NULL on legacy rows
--   severity              NORMAL | ABNORMAL | CRITICAL, fixed when the reading is stored; is_abnormal is kept
--                         equal to (severity <> 'NORMAL') by a CHECK
--   patient_id            becomes nullable (legacy integer key, no longer written)
-- symptom_logs
--   patient_system_id     VARCHAR(20) NOT NULL, FK users(system_id), backfilled through patients.patient_id
--   logged_by_system_id   VARCHAR(20) NULL, FK users(system_id) ON DELETE SET NULL (the author)
--   is_deleted, deleted_at soft delete (a deleted log stays for disease_detection rows that reference it)
--   patient_id            becomes nullable
--
-- Non-destructive: no table or column is dropped. Written defensively because dev databases contain data.

-- ==================== health_metrics ====================

ALTER TABLE health_metrics ADD COLUMN IF NOT EXISTS patient_system_id VARCHAR(20);
ALTER TABLE health_metrics ADD COLUMN IF NOT EXISTS recorded_by_system_id VARCHAR(20);
ALTER TABLE health_metrics ADD COLUMN IF NOT EXISTS reading_group_id VARCHAR(36);
ALTER TABLE health_metrics ADD COLUMN IF NOT EXISTS severity VARCHAR(10) NOT NULL DEFAULT 'NORMAL';

UPDATE health_metrics hm
   SET patient_system_id = p.system_id
  FROM patients p
 WHERE hm.patient_id = p.patient_id
   AND hm.patient_system_id IS NULL;

-- Severity of legacy rows: the only information available is the abnormal flag.
UPDATE health_metrics SET severity = 'ABNORMAL' WHERE is_abnormal = TRUE AND severity = 'NORMAL';

ALTER TABLE health_metrics ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE health_metrics ALTER COLUMN patient_id DROP NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'health_metrics_patient_system_id_fkey') THEN
        ALTER TABLE health_metrics ADD CONSTRAINT health_metrics_patient_system_id_fkey
            FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'health_metrics_recorded_by_system_id_fkey') THEN
        ALTER TABLE health_metrics ADD CONSTRAINT health_metrics_recorded_by_system_id_fkey
            FOREIGN KEY (recorded_by_system_id) REFERENCES users(system_id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'health_metrics_severity_check') THEN
        ALTER TABLE health_metrics ADD CONSTRAINT health_metrics_severity_check
            CHECK (severity IN ('NORMAL', 'ABNORMAL', 'CRITICAL'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'health_metrics_abnormal_matches_severity_check') THEN
        ALTER TABLE health_metrics ADD CONSTRAINT health_metrics_abnormal_matches_severity_check
            CHECK (is_abnormal = (severity <> 'NORMAL'));
    END IF;
END $$;

-- History and latest-per-type (filter by type, newest first) and the all-types history.
CREATE INDEX IF NOT EXISTS idx_hm_patient_type_recorded
    ON health_metrics (patient_system_id, type, recorded_at DESC, metric_id DESC);
CREATE INDEX IF NOT EXISTS idx_hm_patient_recorded
    ON health_metrics (patient_system_id, recorded_at DESC, metric_id DESC);
-- Blood-pressure partner lookup on delete; listeners dedupe per reading group.
CREATE INDEX IF NOT EXISTS idx_hm_reading_group
    ON health_metrics (reading_group_id) WHERE reading_group_id IS NOT NULL;

COMMENT ON COLUMN health_metrics.patient_system_id IS 'Patient the reading belongs to (users.system_id)';
COMMENT ON COLUMN health_metrics.recorded_by_system_id IS 'Who entered the reading; NULL for legacy rows and device connectors';
COMMENT ON COLUMN health_metrics.reading_group_id IS 'UUID shared by the readings stored by one request (blood-pressure pairs)';
COMMENT ON COLUMN health_metrics.severity IS 'NORMAL, ABNORMAL or CRITICAL, fixed when the reading is stored';
COMMENT ON COLUMN health_metrics.patient_id IS 'Legacy integer key, no longer written; use patient_system_id';

-- ==================== symptom_logs ====================

ALTER TABLE symptom_logs ADD COLUMN IF NOT EXISTS patient_system_id VARCHAR(20);
ALTER TABLE symptom_logs ADD COLUMN IF NOT EXISTS logged_by_system_id VARCHAR(20);
ALTER TABLE symptom_logs ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE symptom_logs ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;

UPDATE symptom_logs sl
   SET patient_system_id = p.system_id
  FROM patients p
 WHERE sl.patient_id = p.patient_id
   AND sl.patient_system_id IS NULL;

ALTER TABLE symptom_logs ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE symptom_logs ALTER COLUMN patient_id DROP NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'symptom_logs_patient_system_id_fkey') THEN
        ALTER TABLE symptom_logs ADD CONSTRAINT symptom_logs_patient_system_id_fkey
            FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'symptom_logs_logged_by_system_id_fkey') THEN
        ALTER TABLE symptom_logs ADD CONSTRAINT symptom_logs_logged_by_system_id_fkey
            FOREIGN KEY (logged_by_system_id) REFERENCES users(system_id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'symptom_logs_duration_days_check') THEN
        IF EXISTS (SELECT 1 FROM symptom_logs WHERE duration_days < 0 OR duration_days > 3650) THEN
            RAISE NOTICE 'V47: skipped symptom_logs_duration_days_check: rows outside 0..3650 exist';
        ELSE
            ALTER TABLE symptom_logs ADD CONSTRAINT symptom_logs_duration_days_check
                CHECK (duration_days IS NULL OR duration_days BETWEEN 0 AND 3650);
        END IF;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_sl_patient_logged
    ON symptom_logs (patient_system_id, logged_at DESC, symptom_id DESC);

COMMENT ON COLUMN symptom_logs.patient_system_id IS 'Patient the log belongs to (users.system_id)';
COMMENT ON COLUMN symptom_logs.logged_by_system_id IS 'Author of the log; NULL for legacy rows';
COMMENT ON COLUMN symptom_logs.is_deleted IS 'Soft delete by the author within 24 hours; deleted logs are hidden from every read';
COMMENT ON COLUMN symptom_logs.patient_id IS 'Legacy integer key, no longer written; use patient_system_id';
