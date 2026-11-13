-- ============================================================
-- V25 (be-auth-core): sessions that survive refresh-token rotation
-- ============================================================
-- A session is the chain of refresh tokens that share one session_id. The id is created at login, copied to the
-- new row on every rotation and returned to the client as tokens.sessionId, so "this device" keeps one stable id
-- for the life of the session (session list, sign out one session, sign out all others).
--
-- Existing rows each become their own session (one distinct id per row). The expression below needs no
-- extension and gives a different value for every row.

ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS session_id UUID;

UPDATE refresh_tokens
   SET session_id = md5(random()::text || clock_timestamp()::text || token_id::text)::uuid
 WHERE session_id IS NULL;

ALTER TABLE refresh_tokens ALTER COLUMN session_id SET NOT NULL;

COMMENT ON COLUMN refresh_tokens.session_id IS
    'Session the token belongs to: created at login, shared by every rotated token of that session';

-- Lookups by session: revoke one session, revoke all others, reuse detection (is there a newer token in the session).
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_session_id ON refresh_tokens (session_id);
