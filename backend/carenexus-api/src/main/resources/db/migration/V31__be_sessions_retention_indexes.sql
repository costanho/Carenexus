-- ============================================================
-- V31 (be-sessions-retention): indexes for the session list and the retention sweeper
-- ============================================================
-- Indexes only; nothing is created, changed or dropped otherwise. Every statement is IF NOT EXISTS so the
-- migration is safe on databases that already hold data.
--
-- Deliberately NOT referenced here:
--   * outbound_messages: its retention index (status, sent_at) ships with V62.
--   * the account token tables (password_reset_tokens, email_verification_tokens, email_change_requests,
--     mfa_challenges): created by V26 to V28, which own their indexes. The sweeper guards them with to_regclass.

-- 1. refresh_tokens
--    The sweeper deletes rows that expired, or were revoked, more than carenexus.retention.tokens-days ago.
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_revoked_at ON refresh_tokens (revoked_at) WHERE is_revoked;
--    GET /api/users/me/sessions groups a user's rows by session; DELETE and revoke-others look one session up.
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_system_session ON refresh_tokens (system_id, session_id);

-- 2. notifications
--    Read notifications older than carenexus.retention.read-notifications-days, and rows past expires_at.
CREATE INDEX IF NOT EXISTS idx_notifications_read_created ON notifications (created_at) WHERE is_read;
CREATE INDEX IF NOT EXISTS idx_notifications_expires_at ON notifications (expires_at) WHERE expires_at IS NOT NULL;

-- 3. idempotency_keys: rows past expires_at. V23 already ships this index under the same name, so this is a no-op
--    there; it is kept so the retention indexes are complete in one place.
CREATE INDEX IF NOT EXISTS idx_idempotency_keys_expires_at ON idempotency_keys (expires_at);

-- 4. call tables: finished call jobs and provider webhook events older than carenexus.retention.call-records-days.
CREATE INDEX IF NOT EXISTS idx_call_jobs_completed_at ON call_jobs (completed_at) WHERE completed_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_provider_events_received_at ON provider_events (received_at);
