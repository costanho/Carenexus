-- ============================================================
-- V26 (be-account-password): one-time links that set a new password
-- ============================================================
-- A row is written for every link sent by POST /api/auth/password/forgot and for every administrator reset or
-- caregiver invitation (AdminPasswordResetRequestedEvent). Only the SHA-256 hex of the token is stored; the raw
-- token exists only in the emailed link.
--
-- State follows from the timestamps (there is no status column):
--   used_at IS NULL AND expires_at > now   active (at most one per user)
--   used_at IS NOT NULL                     redeemed, or superseded by a newer link, a redeemed link or a change
--   expires_at <= now                       expired
-- The column names expires_at and used_at are fixed: the retention sweeper deletes by them.
-- Timestamps are clinic wall-clock LocalDateTime written from the application Clock, like refresh_tokens.

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    token_id               SERIAL PRIMARY KEY,
    system_id              VARCHAR(20) NOT NULL REFERENCES users (system_id) ON DELETE CASCADE,
    token_hash             VARCHAR(64) NOT NULL,
    expires_at             TIMESTAMP   NOT NULL,
    used_at                TIMESTAMP   NULL,
    requested_ip           VARCHAR(45) NULL,
    requested_by_system_id VARCHAR(20) NULL,
    created_at             TIMESTAMP   NOT NULL,
    CONSTRAINT ux_password_reset_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT password_reset_tokens_token_hash_hex CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT password_reset_tokens_expiry_after_creation CHECK (expires_at > created_at)
);

COMMENT ON TABLE password_reset_tokens IS
    'One-time set-password links (self-service reset, administrator reset, invitation of a managed dependent)';
COMMENT ON COLUMN password_reset_tokens.token_hash IS 'SHA-256 hex of the emailed token; the raw token is never stored';
COMMENT ON COLUMN password_reset_tokens.used_at IS
    'When the link was redeemed or superseded (a newer link, another redeemed link or a password change)';
COMMENT ON COLUMN password_reset_tokens.requested_ip IS 'Client address of a self-service request; null otherwise';
COMMENT ON COLUMN password_reset_tokens.requested_by_system_id IS
    'Administrator or caregiver who requested the link; null = self-service (forgot password)';

CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_system_id ON password_reset_tokens (system_id);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_expires_at ON password_reset_tokens (expires_at);
