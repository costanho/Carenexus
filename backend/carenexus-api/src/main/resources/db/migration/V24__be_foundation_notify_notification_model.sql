-- ============================================================
-- V24: notification model for the generic publisher (communications.notify)
--   1. notifications: preference category, target (what the notification opens), the referenced resource and a
--      per-recipient dedupe key, so listeners and jobs can publish the same event twice without a second row.
--   2. user_settings: quiet hours and the billing notification toggle.
-- Written defensively (IF NOT EXISTS) because dev databases already contain seed data. Nothing is dropped.
-- ============================================================

-- ------------------------------------------------------------
-- 1. notifications
-- ------------------------------------------------------------
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS category      VARCHAR(40);
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS target        VARCHAR(40);
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS resource_type VARCHAR(40);
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS resource_id   INTEGER;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS dedupe_key    VARCHAR(160);

COMMENT ON COLUMN notifications.category      IS 'Preference category (NotificationCategory): decides which user_settings toggle can switch the notification off';
COMMENT ON COLUMN notifications.target        IS 'What the notification opens (NotificationTarget); NULL for thread messages and informational rows';
COMMENT ON COLUMN notifications.resource_type IS 'Upper snake case kind of the referenced row, e.g. APPOINTMENT, LAB_RESULT, INVOICE';
COMMENT ON COLUMN notifications.resource_id   IS 'Id of the referenced row';
COMMENT ON COLUMN notifications.dedupe_key    IS 'Idempotency key of the publishing event or job, unique per recipient: <EVENT>:<resourceId>[:<discriminator>]';

-- Rows written before the publisher existed: thread messages and caregiver approval requests.
UPDATE notifications
   SET category = CASE WHEN type = 'MESSAGE' THEN 'MESSAGE' ELSE 'SYSTEM' END
 WHERE category IS NULL;

UPDATE notifications
   SET target = 'CAREGIVER_ACCESS'
 WHERE target IS NULL
   AND action_url = '/dashboard/patient?section=my-proxies';

-- One notification per recipient and dedupe key. The publisher inserts with
-- ON CONFLICT (system_id, dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING, so a repeated event never fails.
CREATE UNIQUE INDEX IF NOT EXISTS ux_notifications_recipient_dedupe
    ON notifications (system_id, dedupe_key)
    WHERE dedupe_key IS NOT NULL;

-- ------------------------------------------------------------
-- 2. user_settings
--    Quiet hours are wall-clock times in the user's time zone (user_settings.timezone; clinic zone when unset).
--    They delay external channels only; in-app notifications are always stored.
-- ------------------------------------------------------------
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS quiet_hours_enabled   BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS quiet_hours_start     TIME;
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS quiet_hours_end       TIME;
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS billing_notifications BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN user_settings.quiet_hours_enabled   IS 'Hold non-critical email, SMS and push messages during the quiet window';
COMMENT ON COLUMN user_settings.quiet_hours_start     IS 'Start of the quiet window (wall-clock time in the user''s time zone)';
COMMENT ON COLUMN user_settings.quiet_hours_end       IS 'End of the quiet window (wall-clock time in the user''s time zone)';
COMMENT ON COLUMN user_settings.billing_notifications IS 'Invoice, payment and insurance claim notifications (category BILLING)';
