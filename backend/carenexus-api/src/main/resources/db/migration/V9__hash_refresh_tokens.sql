-- ============================================================
-- V9: Refresh tokens are now stored as SHA-256 hex digests
-- ============================================================
-- Until now refresh_tokens.token_hash held the raw bearer token (a UUID), so anyone who could read
-- the table or a backup could resume any session. The API now stores sha256(token) and rotates the
-- token on every refresh. Existing rows are raw secrets: delete them. Affected users simply sign in
-- again (the JWT signing secret is being rotated at the same time, which logs everyone out anyway).

DELETE FROM refresh_tokens;

-- Revoke-all on logout / reuse detection looks tokens up by user.
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_system_id ON refresh_tokens(system_id);
