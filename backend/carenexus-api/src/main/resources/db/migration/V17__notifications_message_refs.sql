-- ============================================================
-- V17: in-app notifications reference the message/thread they announce.
--   notifications.system_id (V3) is the recipient. One notification per recipient per message.
--   Notification text is generic and never contains the message body.
-- ============================================================
ALTER TABLE notifications ADD COLUMN thread_id         INTEGER REFERENCES message_threads(thread_id) ON DELETE CASCADE;
ALTER TABLE notifications ADD COLUMN message_id        INTEGER REFERENCES messages(message_id) ON DELETE CASCADE;
ALTER TABLE notifications ADD COLUMN patient_system_id VARCHAR(20) REFERENCES users(system_id) ON DELETE CASCADE;
ALTER TABLE notifications ADD COLUMN actor_system_id   VARCHAR(20) REFERENCES users(system_id) ON DELETE SET NULL;

CREATE UNIQUE INDEX ux_notifications_recipient_message ON notifications (system_id, message_id) WHERE message_id IS NOT NULL;
CREATE INDEX idx_notifications_recipient_unread ON notifications (system_id, is_read, created_at DESC);
CREATE INDEX idx_notifications_thread ON notifications (thread_id) WHERE thread_id IS NOT NULL;
