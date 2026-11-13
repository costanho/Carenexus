-- ============================================================
-- V23: backend foundation
--   1. system_id sequences (one per role prefix) replacing the racy "current maximum + 1" query
--   2. dependent_access billing permissions (can_view_billing, can_manage_billing)
--   3. users: email verification, forced password change, password age, account closure
--   4. doctors: clinician verification status and licence details
--   5. idempotency_keys: stored responses of create requests that carry an Idempotency-Key header
--   6. audit_log.target_system_id: the account an account/security event is about
-- Written defensively (IF NOT EXISTS / guarded blocks) because dev databases already contain seed data.
-- Nothing is dropped.
-- ============================================================

-- ------------------------------------------------------------
-- 1. system_id sequences. Each starts at the current numeric maximum of its prefix + 1, so the first nextval
--    is the next free number. SystemIdGenerator formats the value as <PREFIX>-%05d and skips a number that is
--    already taken (ids inserted explicitly by seed scripts or fixtures after this migration).
-- ------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS system_id_pat_seq AS BIGINT START WITH 1 INCREMENT BY 1 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS system_id_doc_seq AS BIGINT START WITH 1 INCREMENT BY 1 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS system_id_cgv_seq AS BIGINT START WITH 1 INCREMENT BY 1 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS system_id_adm_seq AS BIGINT START WITH 1 INCREMENT BY 1 NO CYCLE;

-- setval(seq, n, false): the next nextval returns n.
SELECT setval('system_id_pat_seq',
              COALESCE((SELECT MAX(CAST(SUBSTRING(system_id FROM 5) AS BIGINT)) FROM users WHERE system_id ~ '^PAT-[0-9]+$'), 0) + 1,
              false);
SELECT setval('system_id_doc_seq',
              COALESCE((SELECT MAX(CAST(SUBSTRING(system_id FROM 5) AS BIGINT)) FROM users WHERE system_id ~ '^DOC-[0-9]+$'), 0) + 1,
              false);
SELECT setval('system_id_cgv_seq',
              COALESCE((SELECT MAX(CAST(SUBSTRING(system_id FROM 5) AS BIGINT)) FROM users WHERE system_id ~ '^CGV-[0-9]+$'), 0) + 1,
              false);
SELECT setval('system_id_adm_seq',
              COALESCE((SELECT MAX(CAST(SUBSTRING(system_id FROM 5) AS BIGINT)) FROM users WHERE system_id ~ '^ADM-[0-9]+$'), 0) + 1,
              false);

COMMENT ON SEQUENCE system_id_pat_seq IS 'Numeric part of PAT-nnnnn system ids (SystemIdGenerator)';
COMMENT ON SEQUENCE system_id_doc_seq IS 'Numeric part of DOC-nnnnn system ids (SystemIdGenerator)';
COMMENT ON SEQUENCE system_id_cgv_seq IS 'Numeric part of CGV-nnnnn system ids (SystemIdGenerator)';
COMMENT ON SEQUENCE system_id_adm_seq IS 'Numeric part of ADM-nnnnn system ids (SystemIdGenerator)';

-- ------------------------------------------------------------
-- 2. Caregiver billing permissions. Off for every existing grant: billing access is never implied by an
--    older custom grant (FULL_ACCESS grants every data permission through the access level, as before).
-- ------------------------------------------------------------
ALTER TABLE dependent_access ADD COLUMN IF NOT EXISTS can_view_billing   BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE dependent_access ADD COLUMN IF NOT EXISTS can_manage_billing BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN dependent_access.can_view_billing   IS 'Caregiver may view the patient''s invoices, payments and claims';
COMMENT ON COLUMN dependent_access.can_manage_billing IS 'Caregiver may pay invoices and manage payment methods and claims for the patient';

-- ------------------------------------------------------------
-- 3. users: account lifecycle columns.
--    email_verified_at is backfilled to created_at: accounts that exist before verification was introduced
--    are treated as verified. New accounts start with NULL until they confirm their address.
-- ------------------------------------------------------------
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified_at    TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_changed_at  TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS closed_at            TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS closure_reason       VARCHAR(500);

UPDATE users SET email_verified_at = created_at WHERE email_verified_at IS NULL;

COMMENT ON COLUMN users.email_verified_at    IS 'When the login email was confirmed; NULL = not verified yet';
COMMENT ON COLUMN users.must_change_password IS 'TRUE while the password was set by someone else (temporary password); cleared on change';
COMMENT ON COLUMN users.password_changed_at  IS 'When the password was last changed or reset; NULL = never since registration';
COMMENT ON COLUMN users.closed_at            IS 'When the account was closed at the user''s request or by an administrator';
COMMENT ON COLUMN users.closure_reason       IS 'Short reason recorded with the closure';

-- ------------------------------------------------------------
-- 4. doctors: clinician verification.
--    Doctors that are active today were approved by an administrator under the old is_active flag, so they
--    are backfilled to APPROVED; everyone else stays PENDING.
-- ------------------------------------------------------------
ALTER TABLE doctors ADD COLUMN IF NOT EXISTS verification_status   VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE doctors ADD COLUMN IF NOT EXISTS verified_by_system_id VARCHAR(20);
ALTER TABLE doctors ADD COLUMN IF NOT EXISTS verified_at           TIMESTAMP;
ALTER TABLE doctors ADD COLUMN IF NOT EXISTS rejection_reason      VARCHAR(500);
ALTER TABLE doctors ADD COLUMN IF NOT EXISTS licensing_body        VARCHAR(150);
ALTER TABLE doctors ADD COLUMN IF NOT EXISTS license_country       VARCHAR(2);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'doctors_verification_status_check') THEN
        ALTER TABLE doctors
            ADD CONSTRAINT doctors_verification_status_check
            CHECK (verification_status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED'));
    END IF;

    -- ON DELETE SET NULL: the decision outlives the administrator's account.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'doctors_verified_by_system_id_fkey') THEN
        ALTER TABLE doctors
            ADD CONSTRAINT doctors_verified_by_system_id_fkey
            FOREIGN KEY (verified_by_system_id) REFERENCES users(system_id) ON DELETE SET NULL;
    END IF;
END
$$;

UPDATE doctors SET verification_status = 'APPROVED'
 WHERE is_active = TRUE AND verification_status = 'PENDING';

COMMENT ON COLUMN doctors.verification_status   IS 'Clinician verification: PENDING, APPROVED, REJECTED, SUSPENDED';
COMMENT ON COLUMN doctors.verified_by_system_id IS 'Administrator who made the last verification decision';
COMMENT ON COLUMN doctors.verified_at           IS 'When the last verification decision was made';
COMMENT ON COLUMN doctors.rejection_reason      IS 'Reason given for REJECTED or SUSPENDED';
COMMENT ON COLUMN doctors.licensing_body        IS 'Regulator that issued the licence (for example a medical council)';
COMMENT ON COLUMN doctors.license_country       IS 'ISO 3166-1 alpha-2 country of the licence';

CREATE INDEX IF NOT EXISTS idx_doctors_verification_status ON doctors (verification_status, created_at);

-- One doctor per licence number; doctors without a licence number on file are not constrained.
CREATE UNIQUE INDEX IF NOT EXISTS ux_doctors_license_no ON doctors (license_no) WHERE license_no IS NOT NULL;

-- ------------------------------------------------------------
-- 5. idempotency_keys: one row per (caller, endpoint, Idempotency-Key). response_json is NULL while the first
--    request is still running and holds the JSON response afterwards; a repeat of the same key returns it.
--    Rows are purged after expires_at (24 hours).
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS idempotency_keys (
  key_id          BIGSERIAL PRIMARY KEY,
  owner_system_id VARCHAR(20)  NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
  scope           VARCHAR(160) NOT NULL,
  idem_key        VARCHAR(64)  NOT NULL,
  response_json   TEXT,
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at      TIMESTAMP    NOT NULL,
  CONSTRAINT idempotency_keys_owner_scope_key UNIQUE (owner_system_id, scope, idem_key)
);
COMMENT ON TABLE idempotency_keys IS 'Stored responses of create requests sent with an Idempotency-Key header (IdempotencyService)';
COMMENT ON COLUMN idempotency_keys.scope         IS '"<METHOD> <path template>", for example "POST /api/prescriptions"';
COMMENT ON COLUMN idempotency_keys.response_json IS 'JSON body returned by the first request; NULL while it is still in progress';
CREATE INDEX IF NOT EXISTS idx_idempotency_keys_expires_at ON idempotency_keys (expires_at);

-- ------------------------------------------------------------
-- 6. audit_log.target_system_id: the account an account or security event is about (password reset, email
--    verification, clinician verification, role change, closure). No FK: audit rows outlive the account.
-- ------------------------------------------------------------
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS target_system_id VARCHAR(20);
COMMENT ON COLUMN audit_log.target_system_id IS 'Account an account/security event is about (may equal system_id, the actor)';
CREATE INDEX IF NOT EXISTS idx_audit_log_target ON audit_log (target_system_id, timestamp) WHERE target_system_id IS NOT NULL;
