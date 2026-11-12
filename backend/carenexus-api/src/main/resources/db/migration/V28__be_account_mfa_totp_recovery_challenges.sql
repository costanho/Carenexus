-- ============================================================
-- V28 (be-account-mfa): two-factor authentication with TOTP
--   user_mfa           : one row per account that started or finished enrolment. enabled_at NULL = setup pending
--                        (not enabled); the secret is AES-GCM encrypted by the application, never stored in clear.
--                        last_used_step is the 30-second TOTP step of the last accepted code (replay protection).
--   mfa_recovery_codes : ten one-time codes per enrolled account, stored as SHA-256 hex only.
--   mfa_challenges     : the second sign-in step. A login answers with a challenge token (stored as SHA-256 hex):
--                        purpose LOGIN (client type MFA, MFA enabled) or SETUP (client type MFA_SETUP, forced
--                        enrolment). Consumed challenges are deleted; expired rows are deleted by the retention
--                        sweeper by expires_at.
-- Every table is keyed by users(system_id) and disappears with the account (ON DELETE CASCADE).
-- Timestamps are TIMESTAMP written with LocalDateTime.now(clock), like refresh_tokens.
-- ============================================================

CREATE TABLE IF NOT EXISTS user_mfa (
  system_id         VARCHAR(20)  PRIMARY KEY REFERENCES users(system_id) ON DELETE CASCADE,
  secret_encrypted  VARCHAR(255) NOT NULL,
  enabled_at        TIMESTAMP,
  last_used_step    BIGINT,
  created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE user_mfa IS
  'TOTP enrolment per account: encrypted secret, enabled_at (NULL = setup pending), last accepted 30 s step';

CREATE TABLE IF NOT EXISTS mfa_recovery_codes (
  code_id     SERIAL       PRIMARY KEY,
  system_id   VARCHAR(20)  NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
  code_hash   VARCHAR(64)  NOT NULL,
  used_at     TIMESTAMP,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ux_mfa_recovery_codes_owner_hash UNIQUE (system_id, code_hash)
);

-- Lookup of an account's codes (count unused, find by hash, replace all).
CREATE INDEX IF NOT EXISTS idx_mfa_recovery_codes_system ON mfa_recovery_codes (system_id);

COMMENT ON TABLE mfa_recovery_codes IS
  'One-time MFA recovery codes (SHA-256 hex of the normalised code); used_at set when consumed';

CREATE TABLE IF NOT EXISTS mfa_challenges (
  challenge_id              SERIAL       PRIMARY KEY,
  challenge_hash            VARCHAR(64)  NOT NULL,
  system_id                 VARCHAR(20)  NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
  purpose                   VARCHAR(10)  NOT NULL,
  pending_secret_encrypted  VARCHAR(255),
  device_type               VARCHAR(50),
  device_info               VARCHAR(255),
  attempts                  INTEGER      NOT NULL DEFAULT 0,
  expires_at                TIMESTAMP    NOT NULL,
  created_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ux_mfa_challenges_hash UNIQUE (challenge_hash),
  CONSTRAINT mfa_challenges_purpose_allowed CHECK (purpose IN ('LOGIN', 'SETUP')),
  CONSTRAINT mfa_challenges_attempts_not_negative CHECK (attempts >= 0)
);

-- Retention sweeper (be-sessions-retention) deletes by expires_at.
CREATE INDEX IF NOT EXISTS idx_mfa_challenges_expires ON mfa_challenges (expires_at);
-- Disable and admin reset delete every challenge of the account.
CREATE INDEX IF NOT EXISTS idx_mfa_challenges_system ON mfa_challenges (system_id);

COMMENT ON TABLE mfa_challenges IS
  'Second sign-in step (LOGIN = enter a code, SETUP = forced enrolment). Token stored as SHA-256 hex; consumed rows are deleted';
