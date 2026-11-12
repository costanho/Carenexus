-- ============================================================
-- V11: user_settings (mapped by UserSettings.java, used by /api/profile/settings*)
--   No earlier migration created this table, so every settings call failed with
--   'relation "user_settings" does not exist'. Rows are created lazily by
--   UserProfileService on first GET, so no backfill is needed.
--   Also adds the notification preference columns the proxy notification-settings
--   screen needs (critical_alerts ... weekly_summary).
-- ============================================================

CREATE TABLE IF NOT EXISTS user_settings (
  setting_id                 SERIAL PRIMARY KEY,
  system_id                  VARCHAR(20) NOT NULL UNIQUE REFERENCES users(system_id) ON DELETE CASCADE,

  -- delivery channels
  email_notifications        BOOLEAN NOT NULL DEFAULT TRUE,
  sms_notifications          BOOLEAN NOT NULL DEFAULT TRUE,
  push_notifications         BOOLEAN NOT NULL DEFAULT TRUE,

  -- notification types
  appointment_reminders      BOOLEAN NOT NULL DEFAULT TRUE,
  prescription_reminders     BOOLEAN NOT NULL DEFAULT TRUE,
  lab_result_alerts          BOOLEAN NOT NULL DEFAULT TRUE,
  imaging_result_alerts      BOOLEAN NOT NULL DEFAULT TRUE,
  consultation_notifications BOOLEAN NOT NULL DEFAULT TRUE,

  -- privacy / account
  profile_visibility         VARCHAR(20) NOT NULL DEFAULT 'PRIVATE'
                             CHECK (profile_visibility IN ('PRIVATE','CONTACTS_ONLY','PUBLIC')),
  data_sharing_enabled       BOOLEAN NOT NULL DEFAULT FALSE,
  two_factor_enabled         BOOLEAN NOT NULL DEFAULT FALSE,

  -- preferences
  language                   VARCHAR(10) NOT NULL DEFAULT 'en',
  timezone                   VARCHAR(50) DEFAULT 'UTC',
  theme                      VARCHAR(20) NOT NULL DEFAULT 'LIGHT' CHECK (theme IN ('LIGHT','DARK')),

  created_at                 TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                 TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Notification preferences used by the caregiver/proxy notification settings screen
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS critical_alerts           BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS medication_reminders      BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS vitals_alerts             BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS appointment_reminder_1h   BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS appointment_change_alerts BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS video_call_alerts         BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS message_notifications     BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS care_plan_update_alerts   BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS referral_alerts           BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS login_alerts              BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS access_change_alerts      BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS weekly_summary            BOOLEAN NOT NULL DEFAULT FALSE;

DROP TRIGGER IF EXISTS update_user_settings_updated_at ON user_settings;
CREATE TRIGGER update_user_settings_updated_at BEFORE UPDATE ON user_settings
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
