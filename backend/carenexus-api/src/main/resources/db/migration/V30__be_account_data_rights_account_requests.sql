-- V30 (be-account-data-rights): data export, account closure and deletion requests.
--
-- account_requests holds one row per request a user made about their own account:
--   EXPORT    a JSON bundle of the user's own data, stored through FileStorage under exports/ (result_ref = the
--             storage key, never returned by the API); READY until expires_at, then COMPLETED and the file deleted.
--   CLOSURE   record of a self-service closure (always COMPLETED; users.closed_at / closure_reason hold the state).
--   DELETION  request for an administrator to anonymise the account (REQUESTED, then COMPLETED, REJECTED or CANCELLED).
--
-- users.closed_at and users.closure_reason already exist (V23). Nothing here deletes or rewrites existing rows.

CREATE TABLE IF NOT EXISTS account_requests (
    request_id             SERIAL PRIMARY KEY,
    system_id              VARCHAR(20)  NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
    type                   VARCHAR(20)  NOT NULL,
    status                 VARCHAR(20)  NOT NULL,
    reason                 VARCHAR(500),
    decision_reason        VARCHAR(500),
    result_ref             VARCHAR(255),
    expires_at             TIMESTAMP,
    processed_by_system_id VARCHAR(20)  REFERENCES users(system_id) ON DELETE SET NULL,
    requested_at           TIMESTAMP    NOT NULL,
    processed_at           TIMESTAMP,
    updated_at             TIMESTAMP    NOT NULL,
    CONSTRAINT account_requests_type_check
        CHECK (type IN ('EXPORT', 'CLOSURE', 'DELETION')),
    CONSTRAINT account_requests_status_check
        CHECK (status IN ('REQUESTED', 'PROCESSING', 'READY', 'COMPLETED', 'REJECTED', 'CANCELLED'))
);

COMMENT ON TABLE account_requests IS 'Data export, account closure and deletion requests of users about their own account';
COMMENT ON COLUMN account_requests.result_ref IS 'EXPORT only: FileStorage key of the bundle; never exposed by the API';
COMMENT ON COLUMN account_requests.decision_reason IS 'DELETION only: the administrator''s reason for a rejection';

-- At most one export in preparation per user.
CREATE UNIQUE INDEX IF NOT EXISTS ux_account_requests_export_in_progress
    ON account_requests (system_id)
    WHERE type = 'EXPORT' AND status IN ('REQUESTED', 'PROCESSING');

-- At most one deletion request awaiting review per user.
CREATE UNIQUE INDEX IF NOT EXISTS ux_account_requests_deletion_pending
    ON account_requests (system_id)
    WHERE type = 'DELETION' AND status = 'REQUESTED';

-- Admin list filters (type, status) newest first.
CREATE INDEX IF NOT EXISTS idx_account_requests_type_status
    ON account_requests (type, status, requested_at DESC);

-- A user's own lists (exports, deletion requests) newest first.
CREATE INDEX IF NOT EXISTS idx_account_requests_owner
    ON account_requests (system_id, type, requested_at DESC);

-- Expiry sweep of downloadable exports.
CREATE INDEX IF NOT EXISTS idx_account_requests_export_expiry
    ON account_requests (expires_at)
    WHERE type = 'EXPORT' AND status = 'READY';
