-- ============================================================
-- V22: caregiver grant expiry clean-up.
--   thread_members.removal_reason gains ACCESS_EXPIRED (membership ended because the caregiver's grant expired).
--   dependent_access.expiry_processed_at records when the expiry sweeper last ended conversation memberships for a
--   lapsed grant; a grant is due again when expires_at moves past that time (extension followed by a new lapse).
-- ============================================================
ALTER TABLE thread_members DROP CONSTRAINT IF EXISTS thread_members_removal_reason_check;
ALTER TABLE thread_members ADD CONSTRAINT thread_members_removal_reason_check
  CHECK (removal_reason IN ('LEFT','REMOVED_BY_MEMBER','ACCESS_REVOKED','ACCESS_EXPIRED'));

ALTER TABLE dependent_access ADD COLUMN expiry_processed_at TIMESTAMP;
COMMENT ON COLUMN dependent_access.expiry_processed_at IS 'When the expiry sweeper ended this lapsed grant''s conversation memberships; due again once expires_at > this value';

CREATE INDEX idx_dependent_access_expiring ON dependent_access (expires_at)
  WHERE is_active = TRUE AND revoked_at IS NULL AND expires_at IS NOT NULL;
