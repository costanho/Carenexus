-- ============================================================
-- V32 (be-admin-users): administrator account controls, audit search, clinician queue
-- ============================================================
-- Adds the reason an administrator gave when deactivating an account, and indexes for the admin list filters.
-- Drops nothing and creates no table.

-- Reason given by the administrator at the last deactivation (shown to administrators only). Not mapped on the
-- User entity: read and written by the admin module with native SQL. Cleared when the account is reactivated.
ALTER TABLE users ADD COLUMN IF NOT EXISTS deactivation_reason VARCHAR(500);

COMMENT ON COLUMN users.deactivation_reason IS
    'Reason given by the administrator at the last deactivation; null while active or never deactivated by an administrator';

-- GET /api/admin/users: newest account first, optionally filtered by role and active state.
CREATE INDEX IF NOT EXISTS idx_users_created_at_user_id ON users (created_at DESC, user_id DESC);
CREATE INDEX IF NOT EXISTS idx_users_role_active_created ON users (role, is_active, created_at DESC);

-- Last-administrator protection and the "notify every active administrator" lookup.
CREATE INDEX IF NOT EXISTS idx_users_active_admins ON users (system_id) WHERE role = 'ADMIN' AND is_active = TRUE;

-- GET /api/admin/audit-logs: actor, resource type and status filters, newest first.
-- (patient_system_id, timestamp), (action, timestamp) and (target_system_id, timestamp) exist since V14 / V23.
CREATE INDEX IF NOT EXISTS idx_audit_log_actor_time ON audit_log (system_id, timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_resource_type_time ON audit_log (resource_type, timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_status_time ON audit_log (status, timestamp DESC);

-- GET /api/admin/doctors: every status, oldest registration first. The per-status queue uses
-- idx_doctors_verification_status (verification_status, created_at) from V23.
CREATE INDEX IF NOT EXISTS idx_doctors_created_at_id ON doctors (created_at, doctor_id);

-- Activity counts on the user detail: active sessions of an account.
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_active_by_user ON refresh_tokens (system_id, expires_at) WHERE is_revoked = FALSE;
