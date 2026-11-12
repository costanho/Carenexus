-- ============================================================
-- V18: managed video call sessions (one per VIDEO appointment) and per-request participants/admissions.
--   Times are TIMESTAMPTZ (unambiguous instants). room_name and provider_identity are opaque random values.
--   Existing active VIDEO appointments get a NEEDS_TIME_CONFIRMATION session: their stored wall-clock
--   time predates this release and must be confirmed by the doctor before joining is enabled.
-- ============================================================
CREATE TABLE call_sessions (
  call_session_id              BIGSERIAL PRIMARY KEY,
  appointment_id               INTEGER NOT NULL UNIQUE REFERENCES appointments(appointment_id) ON DELETE CASCADE,
  status                       VARCHAR(30) NOT NULL CHECK (status IN
                                 ('PREPARING','READY','FAILED','LIVE','ENDED','CANCELLED','NEEDS_TIME_CONFIRMATION')),
  room_name                    VARCHAR(64) NOT NULL UNIQUE,
  scheduled_start_utc          TIMESTAMPTZ,
  scheduled_end_utc            TIMESTAMPTZ,
  join_opens_at                TIMESTAMPTZ,
  join_closes_at               TIMESTAMPTZ,
  time_confirmed_at            TIMESTAMPTZ,
  time_confirmed_by_system_id  VARCHAR(20) REFERENCES users(system_id) ON DELETE SET NULL,
  room_created_at              TIMESTAMPTZ,
  started_at                   TIMESTAMPTZ,
  started_by_system_id         VARCHAR(20) REFERENCES users(system_id) ON DELETE SET NULL,
  ended_at                     TIMESTAMPTZ,
  ended_by_system_id           VARCHAR(20) REFERENCES users(system_id) ON DELETE SET NULL,
  end_reason                   VARCHAR(30) CHECK (end_reason IN ('DOCTOR_ENDED','APPOINTMENT_CANCELLED','TYPE_CHANGED','EXPIRED')),
  last_error                   VARCHAR(500),
  version                      INTEGER NOT NULL DEFAULT 0,
  created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT call_sessions_schedule_check CHECK (
    status IN ('NEEDS_TIME_CONFIRMATION','ENDED','CANCELLED')
    OR (scheduled_start_utc IS NOT NULL AND scheduled_end_utc IS NOT NULL
        AND join_opens_at IS NOT NULL AND join_closes_at IS NOT NULL))
);
CREATE INDEX idx_call_sessions_status_closes ON call_sessions (status, join_closes_at);

CREATE TABLE call_participants (
  call_participant_id   BIGSERIAL PRIMARY KEY,
  call_session_id       BIGINT NOT NULL REFERENCES call_sessions(call_session_id) ON DELETE CASCADE,
  system_id             VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
  participant_role      VARCHAR(20) NOT NULL CHECK (participant_role IN ('DOCTOR','PATIENT','CAREGIVER')),
  acting_for_patient    BOOLEAN NOT NULL DEFAULT FALSE,
  status                VARCHAR(20) NOT NULL CHECK (status IN ('REQUESTED','ADMITTED','DENIED','LEFT','REMOVED')),
  provider_identity     VARCHAR(64) NOT NULL UNIQUE,
  display_name          VARCHAR(200) NOT NULL,
  requested_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  decided_at            TIMESTAMPTZ,
  decided_by_system_id  VARCHAR(20) REFERENCES users(system_id) ON DELETE SET NULL,
  admitted_at           TIMESTAMPTZ,
  left_at               TIMESTAMPTZ,
  removed_at            TIMESTAMPTZ,
  revoked_at            TIMESTAMPTZ,
  removal_reason        VARCHAR(30) CHECK (removal_reason IN
                          ('REMOVED_BY_DOCTOR','ACCESS_REVOKED','LOGGED_OUT','CALL_ENDED','APPOINTMENT_CANCELLED','RESCHEDULED','EXPIRED')),
  removal_pending       BOOLEAN NOT NULL DEFAULT FALSE,
  last_token_issued_at  TIMESTAMPTZ,
  provider_joined_at    TIMESTAMPTZ,
  provider_left_at      TIMESTAMPTZ,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT call_participants_acting_check CHECK (NOT acting_for_patient OR participant_role = 'CAREGIVER')
);
COMMENT ON COLUMN call_participants.revoked_at IS 'Revocation cutoff: no token is issued after this instant and any provider presence after it is removed';
CREATE UNIQUE INDEX ux_call_participants_open ON call_participants (call_session_id, system_id) WHERE status IN ('REQUESTED','ADMITTED');
CREATE INDEX idx_call_participants_session ON call_participants (call_session_id, status);
CREATE INDEX idx_call_participants_user_open ON call_participants (system_id) WHERE status IN ('REQUESTED','ADMITTED');

-- Legacy VIDEO appointments: time must be confirmed before calls are enabled.
INSERT INTO call_sessions (appointment_id, status, room_name)
SELECT a.appointment_id, 'NEEDS_TIME_CONFIRMATION', 'cn_' || replace(gen_random_uuid()::text, '-', '')
  FROM appointments a
 WHERE a.type = 'VIDEO' AND a.status IN ('SCHEDULED','RESCHEDULED')
ON CONFLICT (appointment_id) DO NOTHING;
