-- ============================================================
-- V19: durable provider job queue (bounded retries) and idempotent provider webhook log.
--   provider_events stores no payload (no names, no tokens), only what is needed for idempotency/ordering.
-- ============================================================
CREATE TABLE call_jobs (
  call_job_id          BIGSERIAL PRIMARY KEY,
  job_type             VARCHAR(30) NOT NULL CHECK (job_type IN ('CREATE_ROOM','DELETE_ROOM','REMOVE_PARTICIPANT')),
  status               VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
  call_session_id      BIGINT NOT NULL REFERENCES call_sessions(call_session_id) ON DELETE CASCADE,
  call_participant_id  BIGINT REFERENCES call_participants(call_participant_id) ON DELETE CASCADE,
  room_name            VARCHAR(64) NOT NULL,
  provider_identity    VARCHAR(64),
  attempts             INTEGER NOT NULL DEFAULT 0,
  max_attempts         INTEGER NOT NULL DEFAULT 10,
  next_attempt_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  locked_at            TIMESTAMPTZ,
  last_error           VARCHAR(500),
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at         TIMESTAMPTZ,
  CONSTRAINT call_jobs_remove_identity_check CHECK (job_type <> 'REMOVE_PARTICIPANT' OR provider_identity IS NOT NULL)
);
CREATE INDEX idx_call_jobs_due ON call_jobs (next_attempt_at) WHERE status = 'PENDING';
CREATE UNIQUE INDEX ux_call_jobs_open ON call_jobs (job_type, room_name, COALESCE(provider_identity, ''))
  WHERE status IN ('PENDING','RUNNING');

CREATE TABLE provider_events (
  provider_event_id   BIGSERIAL PRIMARY KEY,
  provider            VARCHAR(20) NOT NULL DEFAULT 'LIVEKIT',
  event_id            VARCHAR(100) NOT NULL,
  event_type          VARCHAR(50) NOT NULL,
  room_name           VARCHAR(64),
  provider_identity   VARCHAR(64),
  event_created_at    TIMESTAMPTZ,
  received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at        TIMESTAMPTZ,
  outcome             VARCHAR(30) CHECK (outcome IN ('APPLIED','IGNORED','STALE','REMOVAL_ENQUEUED')),
  CONSTRAINT ux_provider_events_event UNIQUE (provider, event_id)
);
CREATE INDEX idx_provider_events_room ON provider_events (room_name, event_created_at);
