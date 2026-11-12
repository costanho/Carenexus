-- ============================================================
-- V33: system settings (be-system-settings)
--   system_settings: one row per setting an administrator has overridden (SettingKey, dotted name).
--   A setting without a row uses its application property, else the default in code, so this table ships
--   empty and no row is ever required. Values are stored as text in the canonical form of their type
--   (SystemSettingsService validates and normalises before writing).
-- References only users (V1). Written defensively (IF NOT EXISTS). Nothing is dropped.
-- ============================================================

CREATE TABLE IF NOT EXISTS system_settings (
  setting_key          VARCHAR(80) PRIMARY KEY,
  value                TEXT        NOT NULL,
  -- The administrator who last wrote the override. Kept nullable so removing an account never removes a setting.
  updated_by_system_id VARCHAR(20) REFERENCES users(system_id) ON DELETE SET NULL,
  updated_at           TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE system_settings IS 'Administrator overrides of system settings (SystemSettingsService); a missing row means the default applies';
COMMENT ON COLUMN system_settings.setting_key          IS 'Dotted name of the setting, for example billing.tax-rate (SettingKey.key())';
COMMENT ON COLUMN system_settings.value                IS 'Override in the canonical text form of the setting''s type';
COMMENT ON COLUMN system_settings.updated_by_system_id IS 'Administrator who last wrote the override';
COMMENT ON COLUMN system_settings.updated_at           IS 'When the override was last written (clinic wall-clock)';
