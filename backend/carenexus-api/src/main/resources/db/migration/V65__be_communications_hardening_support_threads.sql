-- ============================================================
-- V65 (be-communications-hardening): support threads.
--   A support thread is a message_threads row with conversation_type = 'SUPPORT' (legacy thread_type
--   'PATIENT_SUPPORT', allowed since V1). Its opener is created_by_system_id; it has no patient, so
--   message_threads.patient_system_id and messages.patient_system_id become nullable (support rows store NULL).
--   Administrators answer as members with member_role 'ADMIN' (SUPPORT threads only, enforced by the API).
--   A support thread is open until an administrator closes it (closed_at, clinic wall-clock like created_at);
--   a user has at most one open support thread (partial unique index).
-- ============================================================

ALTER TABLE message_threads DROP CONSTRAINT IF EXISTS message_threads_conversation_type_check;
ALTER TABLE message_threads ADD CONSTRAINT message_threads_conversation_type_check
  CHECK (conversation_type IN ('PRIVATE','CARE_TEAM','SUPPORT'));

ALTER TABLE thread_members DROP CONSTRAINT IF EXISTS thread_members_member_role_check;
ALTER TABLE thread_members ADD CONSTRAINT thread_members_member_role_check
  CHECK (member_role IN ('PATIENT','DOCTOR','CAREGIVER','ADMIN'));

ALTER TABLE message_threads ALTER COLUMN patient_system_id DROP NOT NULL;
ALTER TABLE messages ALTER COLUMN patient_system_id DROP NOT NULL;

ALTER TABLE message_threads ADD COLUMN IF NOT EXISTS closed_at TIMESTAMP NULL;
ALTER TABLE message_threads ADD COLUMN IF NOT EXISTS closed_by_system_id VARCHAR(20) NULL
  REFERENCES users(system_id) ON DELETE SET NULL;

COMMENT ON COLUMN message_threads.patient_system_id IS 'Patient the conversation is about; NULL only for SUPPORT threads';
COMMENT ON COLUMN messages.patient_system_id IS 'Patient of the thread; NULL only for messages of SUPPORT threads';
COMMENT ON COLUMN message_threads.closed_at IS 'SUPPORT threads only: when an administrator closed the thread (clinic wall-clock); NULL = open';
COMMENT ON COLUMN message_threads.closed_by_system_id IS 'SUPPORT threads only: the administrator who closed the thread';

-- One open support thread per user
CREATE UNIQUE INDEX IF NOT EXISTS ux_message_threads_open_support
  ON message_threads (created_by_system_id)
  WHERE conversation_type = 'SUPPORT' AND closed_at IS NULL;

-- Administrator inbox: support threads, newest activity first
CREATE INDEX IF NOT EXISTS idx_message_threads_support_activity
  ON message_threads (last_message_at DESC, thread_id DESC)
  WHERE conversation_type = 'SUPPORT';
