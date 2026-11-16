-- ============================================================
-- V27 (be-account-email): email verification and confirmed email change.
--   email_verification_tokens : one-time links that confirm the sign-in email of an account.
--   email_change_requests     : pending changes of the sign-in email, confirmed through a link sent to the new address.
-- Only the SHA-256 hex hash of a token is stored. Both tables carry expires_at and used_at, which the retention
-- sweeper deletes by. Timestamps are TIMESTAMPTZ (java.time.Instant).
-- An invalidated (superseded) verification token is stored as used (used_at set). A cancelled or replaced email
-- change request has cancelled_at set.
-- ============================================================

CREATE TABLE IF NOT EXISTS email_verification_tokens (
  token_id    SERIAL PRIMARY KEY,
  system_id   VARCHAR(20)  NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
  -- The address the link was sent to: the token stops working when the account's email is no longer this address.
  email       VARCHAR(150) NOT NULL,
  token_hash  VARCHAR(64)  NOT NULL,
  expires_at  TIMESTAMPTZ  NOT NULL,
  used_at     TIMESTAMPTZ  NULL,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT ux_email_verification_tokens_hash UNIQUE (token_hash)
);

-- Outstanding tokens of an account (invalidated when a newer one is issued or one is used).
CREATE INDEX IF NOT EXISTS idx_email_verification_tokens_open
  ON email_verification_tokens (system_id) WHERE used_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_email_verification_tokens_expires
  ON email_verification_tokens (expires_at);

COMMENT ON TABLE email_verification_tokens IS
  'One-time email verification links (SHA-256 hash only). used_at is also set for superseded tokens.';

CREATE TABLE IF NOT EXISTS email_change_requests (
  request_id    SERIAL PRIMARY KEY,
  system_id     VARCHAR(20)  NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
  new_email     VARCHAR(150) NOT NULL,
  token_hash    VARCHAR(64)  NOT NULL,
  expires_at    TIMESTAMPTZ  NOT NULL,
  -- Set when the change was confirmed and applied.
  used_at       TIMESTAMPTZ  NULL,
  -- Set when the request was cancelled by the owner or replaced by a newer request.
  cancelled_at  TIMESTAMPTZ  NULL,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT ux_email_change_requests_hash UNIQUE (token_hash)
);

-- At most one open (neither confirmed nor cancelled) request per account; a new request cancels the open one first.
CREATE UNIQUE INDEX IF NOT EXISTS ux_email_change_requests_open
  ON email_change_requests (system_id) WHERE used_at IS NULL AND cancelled_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_email_change_requests_expires
  ON email_change_requests (expires_at);

COMMENT ON TABLE email_change_requests IS
  'Pending sign-in email changes (SHA-256 token hash only); applied only after the new address is confirmed.';
