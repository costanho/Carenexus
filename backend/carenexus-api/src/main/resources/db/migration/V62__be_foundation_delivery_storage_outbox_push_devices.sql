-- ============================================================
-- V62: outbound delivery (be-foundation-delivery-storage)
--   outbound_messages : one transactional outbox for every email, SMS and push message (account mail and
--                       notification fan-out alike). Rows are inserted PENDING in the caller's transaction and
--                       moved to SENT / FAILED / SKIPPED only by OutboundMessageWorker.
--   push_devices      : push tokens registered by the signed-in user's devices.
-- outbound_messages has no foreign keys on purpose: recipient_system_id and notification_id are plain references,
-- so notification retention and account anonymisation never block on outbox rows.
-- ============================================================

CREATE TABLE IF NOT EXISTS outbound_messages (
  message_id           SERIAL PRIMARY KEY,
  channel              VARCHAR(10)  NOT NULL CHECK (channel IN ('EMAIL','SMS','PUSH')),
  recipient_system_id  VARCHAR(20),
  recipient_address    VARCHAR(320),                 -- email address or phone number; NULL for PUSH
  template_id          VARCHAR(80),                  -- file name under messages/ without .txt; NULL for PUSH
  subject              VARCHAR(300),                 -- EMAIL subject or PUSH title; NULL for SMS
  body                 TEXT         NOT NULL,        -- rendered when queued
  data                 JSONB,                        -- PUSH data payload (string to string)
  notification_id      INTEGER,                      -- in-app notification this message was fanned out from
  dedupe_key           VARCHAR(200),
  status               VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING','SENT','FAILED','SKIPPED')),
  attempts             INTEGER      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  max_attempts         INTEGER      NOT NULL DEFAULT 5 CHECK (max_attempts >= 1),
  next_attempt_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  last_error           VARCHAR(300),
  provider             VARCHAR(40),
  provider_message_id  VARCHAR(200),
  created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
  sent_at              TIMESTAMPTZ,
  -- Unique across the whole table (not per recipient). NULL keys never conflict.
  CONSTRAINT ux_outbound_messages_dedupe_key UNIQUE (dedupe_key)
);

-- Worker: due rows (status = 'PENDING' AND next_attempt_at <= now).
CREATE INDEX IF NOT EXISTS idx_outbound_messages_due ON outbound_messages (status, next_attempt_at);
-- Retention sweeper: SENT / SKIPPED rows older than the window.
CREATE INDEX IF NOT EXISTS idx_outbound_messages_retention ON outbound_messages (status, sent_at);
-- Development outbox list: newest first.
CREATE INDEX IF NOT EXISTS idx_outbound_messages_created ON outbound_messages (created_at DESC, message_id DESC);
CREATE INDEX IF NOT EXISTS idx_outbound_messages_notification ON outbound_messages (notification_id)
  WHERE notification_id IS NOT NULL;

COMMENT ON TABLE outbound_messages IS
  'Transactional outbox for email, SMS and push. Written only through OutboundMessageService; sent by OutboundMessageWorker.';

CREATE TABLE IF NOT EXISTS push_devices (
  device_id     SERIAL PRIMARY KEY,
  system_id     VARCHAR(20)  NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
  platform      VARCHAR(10)  NOT NULL CHECK (platform IN ('IOS','ANDROID','WEB')),
  token         VARCHAR(512) NOT NULL,
  last_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  -- A token belongs to one account at a time: registering it again moves the row to the new account.
  CONSTRAINT ux_push_devices_token UNIQUE (token)
);

CREATE INDEX IF NOT EXISTS idx_push_devices_system ON push_devices (system_id, last_seen_at DESC);

COMMENT ON TABLE push_devices IS
  'Push tokens per account (at most 10, least recently seen dropped). Not clinical data: rows are hard-deleted.';
