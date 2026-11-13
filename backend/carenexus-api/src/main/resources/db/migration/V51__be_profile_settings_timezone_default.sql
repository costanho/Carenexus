-- ============================================================
-- V51 (be-profile-settings): user_settings.timezone means "the user's own zone", NULL = clinic zone
--
-- V11 gave the column the default 'UTC', and UserSettings wrote 'UTC' into every row it created, although no
-- screen ever let a user choose a time zone. Readers of the column (quiet hours in the notification fan-out,
-- "your time" in reminder text) treat NULL as the clinic zone (carenexus.scheduling.time-zone), so every
-- untouched account would otherwise be evaluated in UTC.
--
--   1. the column default becomes NULL;
--   2. rows that still hold the old default 'UTC' are set to NULL. A user who really wants UTC chooses it again
--      through PATCH /api/profile/settings.
--
-- No other schema change: the settings columns that the API no longer exposes (profile_visibility,
-- data_sharing_enabled, language, theme, weekly_summary, two_factor_enabled) stay in the table with their values.
-- ============================================================

ALTER TABLE user_settings ALTER COLUMN timezone SET DEFAULT NULL;

UPDATE user_settings
   SET timezone = NULL
 WHERE timezone = 'UTC';
