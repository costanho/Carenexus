-- CareNexus Auth Server - Initial Schema
-- Note: Main tables (users, refresh_tokens, audit_log) are created by db/init-schemas.sql
-- This migration handles auth-server-specific tables and indexes

-- Add auth-server specific indexes if not already present
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_role ON users(role);
CREATE INDEX IF NOT EXISTS idx_users_is_active ON users(is_active);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_is_revoked ON refresh_tokens(is_revoked);

CREATE INDEX IF NOT EXISTS idx_audit_log_user_id ON audit_log(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_action ON audit_log(action);
CREATE INDEX IF NOT EXISTS idx_audit_log_resource_type ON audit_log(resource_type);
CREATE INDEX IF NOT EXISTS idx_audit_log_created ON audit_log(created_at);

CREATE INDEX IF NOT EXISTS idx_care_team_patient_id ON care_team(patient_id);
CREATE INDEX IF NOT EXISTS idx_care_team_is_active ON care_team(is_active);

CREATE INDEX IF NOT EXISTS idx_dependent_access_dependent_id ON dependent_access(patient_id);
CREATE INDEX IF NOT EXISTS idx_dependent_access_caregiver_id ON dependent_access(caregiver_id);
CREATE INDEX IF NOT EXISTS idx_dependent_access_is_active ON dependent_access(is_active);
