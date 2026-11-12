-- ============================================================
-- V14: clinical audit columns on audit_log
--   * patient_system_id: whose data was read/changed (no FK: audit rows must outlive the patient)
--   * actor_role / http_method / request_path: request context for the audit trail
--   * audit_id widened to BIGINT (high-volume table; matches the Long id in AuditLog.java,
--     which also lets spring.jpa.hibernate.ddl-auto=validate pass)
-- Request bodies are never stored here (they contain PHI).
-- ============================================================

ALTER TABLE audit_log ALTER COLUMN audit_id TYPE BIGINT;
ALTER SEQUENCE IF EXISTS audit_log_audit_id_seq AS BIGINT;

ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS patient_system_id VARCHAR(20);
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS actor_role        VARCHAR(20);
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS http_method       VARCHAR(10);
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS request_path      VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_audit_log_patient ON audit_log(patient_system_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_log_action  ON audit_log(action, timestamp);
