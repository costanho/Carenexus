-- ============================================================
-- V16: group-capable messaging. Threads get a conversation type, creator and last message pointer;
--   thread_members holds membership, the join-time history boundary, read position and per-user archive;
--   messages get a retry key; the single recipient column becomes legacy (nullable).
--   Legacy threads/messages are preserved and their participants become members who see full history.
-- ============================================================

ALTER TABLE message_threads ADD COLUMN conversation_type    VARCHAR(20) NOT NULL DEFAULT 'PRIVATE';
ALTER TABLE message_threads ADD CONSTRAINT message_threads_conversation_type_check
  CHECK (conversation_type IN ('PRIVATE','CARE_TEAM'));
ALTER TABLE message_threads ADD COLUMN created_by_system_id VARCHAR(20) REFERENCES users(system_id) ON DELETE SET NULL;
ALTER TABLE message_threads ADD COLUMN client_thread_id     VARCHAR(64);
ALTER TABLE message_threads ADD COLUMN last_message_id      INTEGER;
ALTER TABLE message_threads ADD COLUMN updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE message_threads SET conversation_type = 'CARE_TEAM' WHERE thread_type = 'CARE_TEAM';

CREATE UNIQUE INDEX ux_message_threads_client_thread
  ON message_threads (created_by_system_id, client_thread_id) WHERE client_thread_id IS NOT NULL;
CREATE INDEX idx_message_threads_patient_last ON message_threads (patient_system_id, last_message_at DESC);

ALTER TABLE messages ALTER COLUMN recipient_system_id DROP NOT NULL;
COMMENT ON COLUMN messages.recipient_system_id IS 'LEGACY single recipient; new messages leave it NULL and use thread_members';
ALTER TABLE messages ADD COLUMN client_message_id VARCHAR(64);
CREATE UNIQUE INDEX ux_messages_thread_sender_client
  ON messages (thread_id, sender_system_id, client_message_id) WHERE client_message_id IS NOT NULL;
CREATE INDEX idx_messages_thread_message ON messages (thread_id, message_id);

CREATE TABLE thread_members (
  thread_member_id          BIGSERIAL PRIMARY KEY,
  thread_id                 INTEGER NOT NULL REFERENCES message_threads(thread_id) ON DELETE CASCADE,
  system_id                 VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
  member_role               VARCHAR(20) NOT NULL CHECK (member_role IN ('PATIENT','DOCTOR','CAREGIVER')),
  joined_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
  history_start_message_id  INTEGER NOT NULL DEFAULT 0,
  last_read_message_id      INTEGER NOT NULL DEFAULT 0,
  archived_at               TIMESTAMPTZ,
  added_by_system_id        VARCHAR(20) REFERENCES users(system_id) ON DELETE SET NULL,
  left_at                   TIMESTAMPTZ,
  removed_at                TIMESTAMPTZ,
  removed_by_system_id      VARCHAR(20) REFERENCES users(system_id) ON DELETE SET NULL,
  removal_reason            VARCHAR(30) CHECK (removal_reason IN ('LEFT','REMOVED_BY_MEMBER','ACCESS_REVOKED')),
  CONSTRAINT ux_thread_members_thread_user UNIQUE (thread_id, system_id)
);
COMMENT ON TABLE thread_members IS 'Conversation membership. Active = left_at IS NULL AND removed_at IS NULL. A member sees messages with message_id > history_start_message_id';
CREATE INDEX idx_thread_members_active_user ON thread_members (system_id) WHERE left_at IS NULL AND removed_at IS NULL;

-- Legacy backfill
UPDATE message_threads t SET last_message_id = m.max_id
  FROM (SELECT thread_id, MAX(message_id) AS max_id FROM messages GROUP BY thread_id) m
 WHERE m.thread_id = t.thread_id;

INSERT INTO thread_members (thread_id, system_id, member_role, joined_at, history_start_message_id, last_read_message_id)
SELECT p.thread_id, p.system_id, u.role, t.created_at AT TIME ZONE 'Africa/Harare', 0, COALESCE(t.last_message_id, 0)
  FROM (SELECT thread_id, patient_system_id AS system_id FROM message_threads
        UNION SELECT thread_id, sender_system_id FROM messages
        UNION SELECT thread_id, recipient_system_id FROM messages WHERE recipient_system_id IS NOT NULL) p
  JOIN message_threads t ON t.thread_id = p.thread_id
  JOIN users u ON u.system_id = p.system_id AND u.role IN ('PATIENT','DOCTOR','CAREGIVER')
ON CONFLICT (thread_id, system_id) DO NOTHING;
