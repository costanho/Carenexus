# CareNexus Communications Contract (Care Direct + Care Proxy)

Status: historical. This was the specification for the five implementing agents on branch `cyprian/video-communication-messages`. Later work changed parts of it: support conversations (administrators answer `SUPPORT` threads, V65), the shared error envelope and validation messages, the `thread-create` and `message-send` rate limits, the 200-person recipient cap and the 400 for a missing confirm-time body. Those changes are listed in [README.md](README.md) sections 5 and 6 and win over this file; API-wide conventions are in [docs/api/CONTRACT.md](../api/CONTRACT.md). The migration numbering below ("the next schema migration is V22") is out of date: see [DATA-MODEL-NOTES.md](../architecture/DATA-MODEL-NOTES.md).
Scope source: COMMS-PLAN.md (user-supplied). Where this contract and older specs (PRD, ERD, Technical-Architecture) disagree, this contract and the code/migrations win.

Agents: `permissions`, `messaging-api`, `video-api`, `messaging-web`, `call-web`.
You work in parallel and do not talk to each other. Everything another agent needs from you is fixed here. Do not change a name, path, JSON field, status value or table/column defined here. If something here is impossible, implement the closest faithful version and say so in your final report; never silently rename.

---

## 0. Global rules (all agents)

1. Do not `git commit`, `stash`, `checkout`, `reset`, or touch the existing stash.
2. Edit only files you own (section 2). You may READ anything. Imports of files owned by others are fine. If such a file or symbol does not exist yet when you compile, that error is expected while the owner works: finish your code against this contract, re-run the check later, and do not create or edit the other agent's file.
3. Match existing style: Lombok (`@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor` on entities/DTOs, `@RequiredArgsConstructor` services), `@Transactional` on services, controllers thin, explicit `@JsonProperty` optional, `RuntimeException` for 400s, `ResourceNotFoundException` 404, `AuthorizationException` 403, `ConflictException` 409. Error body is the existing `GlobalExceptionHandler` JSON (`{timestamp,status,error,message,path}`); the frontend reads `message`.
   - Note: a plain `RuntimeException` whose message contains "not found" becomes 404. Do not put "not found" in 400/409 messages.
   - 5xx statuses are never echoed. For "provider unavailable" use `ConflictException` (409) with the message given here.
4. Never log: access/refresh tokens, LiveKit tokens, API key/secret, webhook Authorization header, room names together with identities, message bodies, notification text, request bodies. Log ids and outcomes only.
5. Never claim end-to-end encryption, encryption in general, emergency monitoring, guaranteed response times, or regulatory certification (HIPAA/GDPR/etc.) in UI copy, docs, comments shown to users, or API messages. Urgent = a visual highlight only.
6. Times:
   - Existing columns are `TIMESTAMP` (clinic wall clock, Africa/Harare; the JVM default zone is set to `carenexus.scheduling.time-zone` at startup) mapped to `LocalDateTime`. Leave them alone.
   - Every NEW time column in this contract is `TIMESTAMPTZ`, mapped as `java.time.Instant` with `@Column(name = "...", columnDefinition = "timestamptz")` (the `columnDefinition` is REQUIRED so `ddl-auto: validate` matches).
   - Every NEW response time field is an `Instant` (Jackson writes ISO-8601 UTC, e.g. `"2026-11-20T08:30:00Z"`). When a response exposes an existing `LocalDateTime` column as a new field, convert with `value.atZone(ZoneId.systemDefault()).toInstant()`.
   - Frontend displays times in `Africa/Harare` with `Intl.DateTimeFormat(..., { timeZone: 'Africa/Harare' })` and a "CAT" suffix where the zone matters (call windows).
7. Backend check: `cd /Users/cyprian/Documents/Projects/Carenexus/backend/carenexus-api && ./mvnw -q -DskipTests compile` (concurrent compiles by others can give transient errors in files you do not own or `target/` lock errors: retry once, then ignore those). Frontend check: `cd /Users/cyprian/Documents/Projects/Carenexus/frontend && npx tsc --noEmit` (errors only in your files must be zero; missing-module errors for another agent's not-yet-created file are acceptable).
8. Flyway: migrations are additive and never edited after creation. Schema migrations V15-V19 below; V20 and V21 are DEV-ONLY seeds in `db/seed` (local/docker profiles). The next schema migration after this work is V22. Dev seeds V2,V4-V8 run before V15 on a fresh local/docker database; all DDL below is written to work on a fresh DB, on a DB with the seed data, and on a DB without it.

---

## 1. Key decisions (read first)

| Topic | Decision |
|---|---|
| Call route | `/dashboard/call/{appointmentId}` (file `frontend/src/app/dashboard/call/[appointmentId].tsx`). Not `/call/...`: the root `_layout.tsx` guard redirects every authenticated non-`dashboard` route to the role home, and `dashboard/_layout.tsx` already verifies the session. `callJoinRoute` in API responses is exactly `"/dashboard/call/" + appointmentId`. |
| Legacy meeting URLs | `appointments.video_consultation_link` is kept in the DB (legacy data) but is never written for new/updated appointments, never returned (every response field `videoConsultationLink` is always `null`), and never used for access. Request field `videoConsultationLink` is accepted and ignored. Property `carenexus.video.base-url` / env `VIDEO_BASE_URL` is removed. |
| Communication consent | Messaging and joining calls are two separate caregiver permissions, each effective only with a patient approval stamp (`*_approved_at` + `*_approved_by_system_id = patient_system_id`). `FULL_ACCESS` never implies them. Existing grants are reset to "needs patient approval" by V15. |
| Messaging privacy | Admins cannot read or send messages. Every request re-checks membership + current relationship/permission. Join-time history boundary is a message-id boundary. |
| Calls | One `call_sessions` row per VIDEO appointment. Doctor starts and ends; doctor admits each participant; leaving never changes appointment or consultation status. Tokens: 5 min, data channel denied. Realtime = polling, no websockets. |
| Events | Cross-module side effects via Spring application events in `com.carenexus.api.common.event`, consumed with `@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)` + `@Transactional(propagation = REQUIRES_NEW)` in a separate bean. |
| Mobile | Native calling is out of scope. Mobile Join buttons are replaced by a note. Messaging UI is web only; mobile navbars are not changed. |

---

## 2. File ownership (every file has exactly one owner)

Paths: backend root `backend/carenexus-api/src/main/java/com/carenexus/api/` (abbrev. `api/`), resources `backend/carenexus-api/src/main/resources/` (abbrev. `res/`), frontend root `frontend/src/` (abbrev. `src/`). "(new)" = you create it.

### 2.1 permissions
Backend
- `res/db/migration/V15__caregiver_communication_consent.sql` (new)
- `res/db/seed/V20__dev_seed_communication_approvals.sql` (new)
- `api/common/event/CaregiverAccessChangedEvent.java` (new), `api/common/event/UserLoggedOutEvent.java` (new)
- `api/common/service/PatientPermission.java`, `api/common/service/AuthorizationService.java`
- `api/common/config/SecurityConfig.java` (adds the path rules for EVERY agent, section 5.6)
- `api/core/model/DependentAccess.java`, `api/core/repository/DependentAccessRepository.java`, `api/core/repository/CareTeamRepository.java`
- `api/core/service/RelationshipService.java`, `api/core/service/CaregiverService.java`
- `api/core/controller/CaregiverController.java`, `api/core/controller/PatientCaregiverAccessController.java` (new)
- `api/core/dto/request/UpdateDependentAccessRequest.java`, `api/core/dto/request/CreateDependentRequest.java`, `api/core/dto/request/GrantCaregiverAccessRequest.java` (new)
- `api/core/dto/response/CaregiverDependentResponse.java`
- `api/auth/service/AuthService.java`, `api/auth/controller/AuthController.java`
- all existing files under `backend/carenexus-api/src/test/**` and new tests under `src/test/java/com/carenexus/api/core/**` and `.../common/**`
Frontend
- `src/hooks/use-caregiver-dependents.ts` (also adds the call fields of 9.3 to `CaregiverAppointment`)
- `src/hooks/use-caregiver-access.ts` (new)
- `src/components/web/direct/patient/patient-web-caregiver-access.tsx` (new)
- `src/components/web/proxy/patient/patient-proxy-web-profile.tsx`, `src/components/web/proxy/patient/patient-proxy-web-add-dependent.tsx`
- `src/lib/auth/**` (no change expected)

### 2.2 messaging-api
- `res/db/migration/V16__messaging_threads_members.sql` (new), `res/db/migration/V17__notifications_message_refs.sql` (new)
- everything under `api/communications/**` (new package: `controller`, `service`, `repository`, `model`, `dto/request`, `dto/response`, `listener`)
- tests under `backend/carenexus-api/src/test/java/com/carenexus/api/communications/**` (new)

### 2.3 video-api
- `res/db/migration/V18__call_sessions.sql` (new), `res/db/migration/V19__call_jobs_provider_events.sql` (new)
- everything under `api/calls/**` (new package: `config`, `controller`, `service`, `repository`, `model`, `dto`, `provider`, `job`, `listener`, `event`)
- `api/core/model/Appointment.java`, `api/core/repository/AppointmentRepository.java`
- `api/core/service/AppointmentService.java`, `api/core/service/DoctorScheduleService.java`, `api/core/service/DoctorPatientService.java` (only their appointment-to-DTO mapping and call fields)
- `api/core/controller/AppointmentController.java`, `api/core/controller/DoctorAppointmentController.java`
- `api/core/dto/response/AppointmentResponse.java`, `api/core/dto/response/ScheduleEntryResponse.java`, `api/core/dto/request/CreateAppointmentRequest.java`, `api/core/dto/request/RescheduleAppointmentRequest.java`
- `backend/carenexus-api/pom.xml`, `res/application.yml` (contains every agent's YAML, section 10.2)
- `docker-compose.yml`, `.env.example`, `README.md` (optional "Video calls" section), `docs/communications/LOCAL-DEV.md` (new, optional)
- tests under `backend/carenexus-api/src/test/java/com/carenexus/api/calls/**` (new)

### 2.4 messaging-web
- shells: `src/components/web/direct/patient-dashboard-web.tsx`, `src/components/web/direct/doctor-dashboard-web.tsx`, `src/components/web/proxy/patient-proxy-dashboard-web.tsx`
- sidebars: `src/components/web/direct/patient/patient-left-sidebar.tsx`, `src/components/web/direct/doctor/doctor-left-sidebar.tsx`, `src/components/web/proxy/patient/patient-proxy-sidebar.tsx`
- top navbars: `src/components/web/direct/patient/patient-top-navbar.tsx`, `src/components/web/direct/doctor/doctor-top-navbar.tsx`, `src/components/web/proxy/patient/patient-proxy-topnavbar.tsx`
- `src/components/web/proxy/patient/patient-proxy-messages.tsx`
- new: `src/components/web/messages/**`, `src/components/web/notifications/**`, `src/lib/communications/**`, `src/hooks/use-threads.ts`, `src/hooks/use-thread-messages.ts`, `src/hooks/use-message-recipients.ts`, `src/hooks/use-notifications.ts`, `src/hooks/use-page-active.ts`

### 2.5 call-web
- `frontend/package.json`, `frontend/package-lock.json` (LiveKit install, section 10.4)
- new: `src/app/dashboard/call/[appointmentId].tsx`, `src/components/web/call/**`, `src/lib/calls/**`, `src/hooks/use-call-status.ts`, optional `src/types/livekit.d.ts`
- hooks: `src/hooks/use-appointments.ts` (keep the existing `apiErrorMessage` export unchanged; others import it), `src/hooks/use-doctor-schedule.ts`, `src/hooks/use-doctor-appointment-actions.ts`, `src/hooks/use-doctor-dashboard.ts`
- web appointment components (Join buttons): `src/components/web/direct/doctor/doctor-web-schedule.tsx`, `src/components/web/direct/doctor/doctor-web-appointment.tsx`, `src/components/web/direct/doctor/doctor-main-dashboard-area.tsx`, `src/components/web/direct/patient/patient-right-sidebar.tsx`, `src/components/web/direct/patient/patient-web-appointment-status.tsx`, `src/components/web/proxy/patient/patient-proxy-appointments.tsx`, `src/components/web/proxy/patient/patient-proxy-main-dashboard.tsx`
- mobile Join buttons (replace with note only): `src/components/mobile/direct/doctor/doctor-mobile-schedule.tsx`, `src/components/mobile/direct/doctor/doctor-main-dashboard-area.tsx`, `src/components/mobile/direct/doctor/doctor-mobile-appointment.tsx`, `src/components/mobile/direct/patient/patient-mobile-appointment-status.tsx`, `src/components/mobile/proxy/patient/patient-proxy-main-dashboard.tsx`

### 2.6 Everything else
Not owned by anyone: do not modify (e.g. `GlobalExceptionHandler`, `AuditService`, `UserContext`, `JwtAuthenticationFilter`, `CarenexusApiApplication`, booking forms, other screens, `src/app/_layout.tsx`, `src/app/dashboard/_layout.tsx`, `src/lib/ui/dialogs.ts`). This document is owned by the architect.

---

## 3. Phase 0: publish shared signatures first

Do these before anything else (within your first steps), with the final names, so parallel agents compile:

- permissions: `PatientPermission.MESSAGING` and `PatientPermission.JOIN_CALLS` (and the `describe()` switch cases); `CaregiverAccessChangedEvent`, `UserLoggedOutEvent`; `RelationshipService.getRelatedDoctorsForPatient(String)`; new `DependentAccess` fields; `SecurityConfig` rules (5.6); frontend `use-caregiver-dependents.ts` new fields (5.7, 9.3); a default-exported placeholder `patient-web-caregiver-access.tsx`.
- video-api: `AppointmentResponse` and `ScheduleEntryResponse` new fields (9.1); `application.yml` keys for all agents (10.2); `pom.xml` dependency (10.1).
- call-web: the route file `src/app/dashboard/call/[appointmentId].tsx` (can be a placeholder at first).

---

## 4. Database migrations (exact DDL)

### 4.1 V15 (permissions): `V15__caregiver_communication_consent.sql`

```sql
-- ============================================================
-- V15: separate, patient-approved communication permissions on dependent_access
--   can_message_care_team (exists since V1) and the new can_join_calls are REQUESTED permissions.
--   They only take effect with a patient approval stamp: *_approved_at set AND
--   *_approved_by_system_id = patient_system_id. FULL_ACCESS never implies them.
--   Reapproval rule: every existing grant starts without an approval stamp, so existing
--   messaging grants (possibly self-edited by caregivers before) need the patient to approve again.
-- ============================================================

ALTER TABLE dependent_access ADD COLUMN IF NOT EXISTS can_join_calls                  BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE dependent_access ADD COLUMN IF NOT EXISTS messaging_approved_at           TIMESTAMPTZ;
ALTER TABLE dependent_access ADD COLUMN IF NOT EXISTS messaging_approved_by_system_id VARCHAR(20);
ALTER TABLE dependent_access ADD COLUMN IF NOT EXISTS calls_approved_at               TIMESTAMPTZ;
ALTER TABLE dependent_access ADD COLUMN IF NOT EXISTS calls_approved_by_system_id     VARCHAR(20);

ALTER TABLE dependent_access ADD CONSTRAINT dependent_access_messaging_approved_by_fkey
  FOREIGN KEY (messaging_approved_by_system_id) REFERENCES users(system_id) ON DELETE SET NULL;
ALTER TABLE dependent_access ADD CONSTRAINT dependent_access_calls_approved_by_fkey
  FOREIGN KEY (calls_approved_by_system_id) REFERENCES users(system_id) ON DELETE SET NULL;

-- Reapproval: explicit reset of any approval (no-op on a fresh database, documents the rule).
UPDATE dependent_access
   SET messaging_approved_at = NULL, messaging_approved_by_system_id = NULL,
       calls_approved_at = NULL, calls_approved_by_system_id = NULL;

-- An approval can only exist for a requested permission and only from the patient themselves.
ALTER TABLE dependent_access ADD CONSTRAINT dependent_access_messaging_approval_check CHECK (
  (messaging_approved_at IS NULL AND messaging_approved_by_system_id IS NULL)
  OR (messaging_approved_at IS NOT NULL AND can_message_care_team
      AND messaging_approved_by_system_id = patient_system_id));
ALTER TABLE dependent_access ADD CONSTRAINT dependent_access_calls_approval_check CHECK (
  (calls_approved_at IS NULL AND calls_approved_by_system_id IS NULL)
  OR (calls_approved_at IS NOT NULL AND can_join_calls
      AND calls_approved_by_system_id = patient_system_id));

COMMENT ON COLUMN dependent_access.can_join_calls IS 'Requested permission to join video calls; effective only with calls_approved_at';
COMMENT ON COLUMN dependent_access.messaging_approved_at IS 'When the patient approved can_message_care_team';
COMMENT ON COLUMN dependent_access.calls_approved_at IS 'When the patient approved can_join_calls';
```

`DependentAccess` entity additions:
```java
@Column(name = "can_join_calls", nullable = false) @Builder.Default private Boolean canJoinCalls = false;
@Column(name = "messaging_approved_at", columnDefinition = "timestamptz") private Instant messagingApprovedAt;
@Column(name = "messaging_approved_by_system_id", length = 20) private String messagingApprovedBySystemId;
@Column(name = "calls_approved_at", columnDefinition = "timestamptz") private Instant callsApprovedAt;
@Column(name = "calls_approved_by_system_id", length = 20) private String callsApprovedBySystemId;
```
Service rule that keeps the CHECK satisfied: whenever `canMessageCareTeam` becomes false, clear both messaging approval columns in the same save; same for calls.

### 4.2 V16 (messaging-api): `V16__messaging_threads_members.sql`

```sql
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
  removal_reason            VARCHAR(30) CHECK (removal_reason IN ('LEFT','REMOVED_BY_MEMBER','ACCESS_REVOKED')),  -- V22 adds 'ACCESS_EXPIRED' (4.7)
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
```
Notes: `messages.is_urgent` (V1) is the urgent flag; `messages.sent_at` (TIMESTAMP) stays the send time. `message_threads.is_archived` is legacy/global and is not used (per-user archive is `thread_members.archived_at`). New threads set legacy `thread_type`: PRIVATE patient+doctor -> `PATIENT_DOCTOR`; PRIVATE caregiver+doctor -> `CAREGIVER_DOCTOR`; CARE_TEAM -> `CARE_TEAM`.

### 4.3 V17 (messaging-api): `V17__notifications_message_refs.sql`

```sql
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
```
`Notification` entity maps all columns: `notification_id` (Integer, IDENTITY), `system_id`, `type` (CHECK includes `MESSAGE`), `title` (NOT NULL, 255), `message` (TEXT NOT NULL), `is_read`, `read_at` (TIMESTAMP), `action_url`, `icon`, `priority` (`LOW|NORMAL|HIGH|CRITICAL`), `expires_at` (TIMESTAMP), `created_at` (TIMESTAMP, set in `@PrePersist` to `LocalDateTime.now()`), plus the four new columns.

### 4.4 V18 (video-api): `V18__call_sessions.sql`

```sql
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
```
Dev seed check: V6 inserts several SCHEDULED VIDEO appointments with legacy `meet.carenexus.com` links; they become NEEDS_TIME_CONFIRMATION sessions and their links are never returned.

### 4.5 V19 (video-api): `V19__call_jobs_provider_events.sql`

```sql
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
```
`max_attempts` per type (set on insert): CREATE_ROOM 8, DELETE_ROOM 10, REMOVE_PARTICIPANT 30. Backoff after a failed attempt n: `next_attempt_at = now + min(5s * 2^(n-1), 300s)`.

### 4.6 V20 (permissions, DEV SEED ONLY): `res/db/seed/V20__dev_seed_communication_approvals.sql`

```sql
-- DEV ONLY (local/docker profiles). Demo pair for messaging and calls: Thomas Dube approves his
-- caregiver Sarah Dube for messaging and joining calls. Everything else stays "needs approval".
UPDATE dependent_access
   SET can_message_care_team = TRUE,
       can_join_calls = TRUE,
       messaging_approved_at = now(), messaging_approved_by_system_id = patient_system_id,
       calls_approved_at = now(),     calls_approved_by_system_id = patient_system_id
 WHERE caregiver_system_id = (SELECT system_id FROM users WHERE email = 'sarah.dube@carenexus.com')
   AND patient_system_id   = (SELECT system_id FROM users WHERE email = 'thomas.dube@carenexus.com');
```
(Safe when the rows do not exist: updates nothing.)

---

### 4.7 V22 (platform): `V22__caregiver_access_expiry.sql`
```sql
ALTER TABLE thread_members DROP CONSTRAINT IF EXISTS thread_members_removal_reason_check;
ALTER TABLE thread_members ADD CONSTRAINT thread_members_removal_reason_check
  CHECK (removal_reason IN ('LEFT','REMOVED_BY_MEMBER','ACCESS_REVOKED','ACCESS_EXPIRED'));
ALTER TABLE dependent_access ADD COLUMN expiry_processed_at TIMESTAMP;
CREATE INDEX idx_dependent_access_expiring ON dependent_access (expires_at)
  WHERE is_active = TRUE AND revoked_at IS NULL AND expires_at IS NOT NULL;
```
`DependentAccess.expiryProcessedAt` (LocalDateTime) is written only by `CaregiverAccessExpirySweeper` (6.1). A grant is due when it is active, not revoked, `expires_at <= now` and `expiry_processed_at IS NULL OR expiry_processed_at < expires_at` (so a grant that is extended and lapses again is processed again).

## 5. Permissions (owner: permissions)

### 5.1 PatientPermission
Add, keeping existing values:
```java
/** Send/read saved messages about the patient. Caregiver: can_message_care_team + patient approval. */
MESSAGING("MESSAGE", ...),
/** Request to join / join the patient's video calls. Caregiver: can_join_calls + patient approval. */
JOIN_CALLS("CALL", ...),
```
- `MESSAGE_CARE_TEAM` stays as a deprecated alias with exactly the MESSAGING rule (no code may rely on the old FULL_ACCESS shortcut for messaging).
- New method `public boolean isCommunication()` true for MESSAGING, JOIN_CALLS, MESSAGE_CARE_TEAM.
- `isGrantedBy(access)` for communication permissions: `TRUE.equals(flag) && approvedAt != null && access.getPatientSystemId().equals(approvedBySystemId)`. Access level is ignored for them (FULL_ACCESS does not grant, VIEW_ONLY does not block). Active/expiry is checked by the caller as today (repository "active" queries).
  - MESSAGING/MESSAGE_CARE_TEAM: `canMessageCareTeam`, `messagingApprovedAt`, `messagingApprovedBySystemId`.
  - JOIN_CALLS: `canJoinCalls`, `callsApprovedAt`, `callsApprovedBySystemId`.
- `AuthorizationService.describe`: MESSAGING / MESSAGE_CARE_TEAM -> "messaging the care team"; JOIN_CALLS -> "joining video calls".
- `requirePatientAccess(p, MESSAGING|JOIN_CALLS)`: PATIENT self; DOCTOR related (`isDoctorAssignedToPatient`); CAREGIVER active grant + rule above; ADMIN allowed (callers in messaging/calls reject admins themselves, see 7.1 and 8.2).
- Add to AuthorizationService: `public boolean hasCaregiverPermission(String caregiverSystemId, String patientSystemId, PatientPermission permission)` (non-throwing, no audit, uses `getActiveDependentAccess`). Used by listeners/sweepers that run without a request context.

### 5.2 RelationshipService additions
```java
/** Doctors (active users, role DOCTOR) related to the patient: active care_team doctor roles, any non-cancelled
 *  appointment, or any consultation. De-duplicated, stable order. */
public List<String> getRelatedDoctorsForPatient(String patientSystemId)
```
Add to CareTeamRepository: `findAppointmentDoctorSystemIdsForPatient(patientSystemId)` (status <> 'CANCELLED') and `findConsultationDoctorSystemIdsForPatient(patientSystemId)`.

### 5.3 Consent rules (CaregiverService)
- Only the PATIENT owning the grant (caller systemId == patient_system_id, role PATIENT) can approve. Approval happens when the patient sets `canMessageCareTeam: true` or `canJoinCalls: true` (on grant or PATCH), including when the flag is already true but unapproved. Approval stamps `*_approved_at = Instant.now()`, `*_approved_by_system_id = patient`.
- Setting a flag false (patient, admin or caregiver) clears its approval in the same save.
- ADMIN may change flags through the legacy `/api/patients/me/caregivers/{id}` PATCH, but admin changes never set approval stamps; admin may revoke.
- CAREGIVER (PATCH `/api/caregiver/dependents/{accessId}`): may only narrow. Any false->true change of `canMessageCareTeam` or `canJoinCalls` is rejected 403 "You can only reduce your own access. Ask the patient to grant additional permissions." (in addition to the existing isGrantedBy loop).
- `createDependent` (caregiver creates a dependent account): `canMessageCareTeam` / `canJoinCalls` from the request are stored as requests (flags) with NO approval; FULL_ACCESS does not set them.
- Revocation (existing): `is_active=false`, `revoked_at=now`. Re-granting a revoked row re-uses it (UNIQUE caregiver/patient) with fresh values; approvals are stamped again only for flags the patient sets true in that grant.
- After EVERY successful change (patient grant/PATCH/revoke, admin PATCH/revoke, caregiver narrow, createDependent) publish `CaregiverAccessChangedEvent` (section 6) with the effective values computed after save.
- Audit as today: `auditService.logClinicalAccess("CREATE"|"UPDATE"|"DELETE", "DEPENDENT_ACCESS", accessId, patientSystemId)`.

### 5.4 Endpoints: `/api/patients/me/caregiver-access` (PatientCaregiverAccessController)
Caller role PATIENT (caller = patient from JWT). DELETE also allows ADMIN (any grant by id). Keep the existing `/api/patients/me/caregivers` endpoints working (same service, now also returning the new fields).

| Method | Path | Body | Success | Errors |
|---|---|---|---|---|
| GET | `/api/patients/me/caregiver-access` | - | 200 `CaregiverAccess[]` (all rows of this patient with `revoked_at IS NULL`, active and expired, newest first) | 403 non-patient |
| POST | `/api/patients/me/caregiver-access` | `GrantCaregiverAccessRequest` | 201 `CaregiverAccess` | 400 validation; 404 "No caregiver account found for this email"; 409 "This caregiver already has access. Change their permissions instead." |
| PATCH | `/api/patients/me/caregiver-access/{accessId}` | `UpdateDependentAccessRequest` | 200 `CaregiverAccess` | 403 not own grant; 404 "Access record not found"; 409 "This access has been revoked" |
| DELETE | `/api/patients/me/caregiver-access/{accessId}` | - | 204 (idempotent) | 403; 404 |

`GrantCaregiverAccessRequest`:
```json
{ "caregiverEmail": "sarah.dube@carenexus.com", "relationship": "daughter", "accessLevel": "CUSTOM",
  "expiresAt": "2027-01-31", "canViewRecords": true, "canViewAppointments": true, "canViewMedications": false,
  "canViewLabResults": false, "canViewImaging": false, "canViewConsultations": false,
  "canScheduleAppointments": false, "canManageCarePlan": false, "canMessageCareTeam": true, "canJoinCalls": true }
```
`caregiverEmail` required (case-insensitive, must be an active user with role CAREGIVER and a caregivers row), `relationship` required (1-80), `accessLevel` optional (default `VIEW_ONLY`; one of FULL_ACCESS, VIEW_ONLY, EDIT_ONLY, CUSTOM; FULL_ACCESS sets all view/manage flags but not the communication flags), `expiresAt` optional (same parser as today, must be in the future: 400 "Access expiry must be in the future"), flags optional default false.
`UpdateDependentAccessRequest`: existing fields + `"canJoinCalls": Boolean`.

`CaregiverAccess` = `CaregiverDependentResponse` (same class for patient and caregiver views) with existing fields plus:
```json
{ "canJoinCalls": true,
  "messagingApprovedAt": "2026-11-03T10:00:00Z", "callsApprovedAt": null,
  "messagingAllowed": true, "joinCallsAllowed": false,
  "pendingApproval": true,
  "accessStatus": "ACTIVE" }
```
- `messagingAllowed` / `joinCallsAllowed`: effective now (active, not expired, flag + approval).
- `pendingApproval`: active and (`canMessageCareTeam` without messaging approval, or `canJoinCalls` without calls approval).
- `accessStatus`: `ACTIVE` | `EXPIRED` | `REVOKED`.

### 5.5 Logout
`AuthService.logout(systemId, ip)` and `AuthService.logoutRefreshToken(...)` (when a token row is found) publish `UserLoggedOutEvent(systemId)`. The HTTP behaviour of `/api/auth/logout` is unchanged.

### 5.6 SecurityConfig (permissions adds all of these)
Inside `authorizeHttpRequests`, before `.anyRequest()`:
```java
.requestMatchers(HttpMethod.POST, "/api/integrations/livekit/webhook").permitAll()
.requestMatchers("/api/patients/me/caregiver-access", "/api/patients/me/caregiver-access/**").hasAnyRole("PATIENT", "ADMIN")
.requestMatchers("/api/communications/**", "/api/notifications/**").authenticated()
.requestMatchers("/api/integrations/**").denyAll()
```
Place the webhook `permitAll` before any rule that could match it. `/api/appointments/**` stays "authenticated" (default). LiveKit sends `Authorization: <jwt>` without "Bearer", which `JwtAuthenticationFilter` ignores; the webhook controller verifies it.

### 5.7 Frontend (permissions)
`src/hooks/use-caregiver-dependents.ts`: add to `CaregiverDependent`:
```ts
canJoinCalls:        boolean;
messagingApprovedAt: string | null;
callsApprovedAt:     string | null;
messagingAllowed:    boolean;
joinCallsAllowed:    boolean;
pendingApproval:     boolean;
accessStatus:        'ACTIVE' | 'EXPIRED' | 'REVOKED';
```
and to `CaregiverAppointment` (consumed by call-web):
```ts
callStatus:         CallStatus | null;   // type alias defined in this file: 'PREPARING'|'READY'|'FAILED'|'LIVE'|'ENDED'|'CANCELLED'|'NEEDS_TIME_CONFIRMATION'
callJoinRoute:      string | null;
joinWindowOpensAt:  string | null;
joinWindowClosesAt: string | null;
```
(`export type CallStatus = ...` lives in this file so the proxy hook does not depend on call-web files.)

`src/hooks/use-caregiver-access.ts` exports: `type CaregiverAccess` (shape of 5.4), `useCaregiverAccess(): { grants, loading, error, refetch }`, `grantCaregiverAccess(body)`, `updateCaregiverAccess(accessId, body)`, `revokeCaregiverAccess(accessId)`.

`src/components/web/direct/patient/patient-web-caregiver-access.tsx`: `export default function PatientWebCaregiverAccess(props: { onBack?: () => void })`. Patient "My caregivers" screen: list (name, email, relationship, level, expiry, status), per-permission toggles including "Message my care team" and "Join my video calls", a "Needs your approval" badge with an Approve action for `pendingApproval`, "Grant access" form, Revoke with `confirmAction`. Copy: "Caregivers can message your care team or join your video calls only after you approve. Removing access also removes them from conversations and from any call in progress." messaging-web wires this component to the patient sidebar key `my-proxies`.

Caregiver side (`patient-proxy-web-profile.tsx`, `patient-proxy-web-add-dependent.tsx`): show messaging/calls as "Allowed", "Waiting for {patient} to approve" or "Not allowed" from `messagingAllowed` / `joinCallsAllowed` / flags; the caregiver can turn them off but never on (add-dependent may request them, with the note "Messaging and video calls need the patient's approval").

---

## 6. Application events (package `com.carenexus.api.common.event`)

```java
public record CaregiverAccessChangedEvent(
        Integer accessId,
        String patientSystemId,
        String caregiverSystemId,
        boolean revoked,            // true when the grant was revoked (or is no longer active)
        boolean messagingAllowed,   // effective PatientPermission.MESSAGING after the change
        boolean joinCallsAllowed) { // effective PatientPermission.JOIN_CALLS after the change
}

public record UserLoggedOutEvent(String systemId) { }

// Published by CaregiverService when MESSAGING becomes effective again after it was not (re-grant of a revoked
// row, extension of a lapsed grant, re-approval). Handled SYNCHRONOUSLY (plain @EventListener, same transaction).
public record CaregiverMessagingRegainedEvent(Integer accessId, String patientSystemId, String caregiverSystemId,
        boolean wasExpired) { }

// Published by CaregiverService when a grant starts (pending = true) or stops waiting for the patient's approval of
// can_message_care_team / can_join_calls. Transitions only. Handled SYNCHRONOUSLY.
public record CaregiverApprovalPendingEvent(Integer accessId, String patientSystemId, String caregiverSystemId,
        boolean pending, boolean messagingRequested, boolean callsRequested) { }
```
Published by permissions via `ApplicationEventPublisher.publishEvent(...)` inside the changing transaction. The expiry sweeper (6.1) also publishes `CaregiverAccessChangedEvent(revoked = true)` for every lapsed grant it processes.

`com.carenexus.api.calls.event.AppointmentCallRelevantChangeEvent` (video-api internal):
```java
public record AppointmentCallRelevantChangeEvent(Integer appointmentId, Kind kind) {
    public enum Kind { CREATED_VIDEO, CANCELLED, RESCHEDULED, TYPE_CHANGED }
}
```
Published by `AppointmentService` (create of a VIDEO appointment; cancel; start-time change; type change. If an update changes both type and time publish TYPE_CHANGED only). Listener in calls: `@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)` calling `CallSessionService.syncWithAppointment(appointmentId, kind)`, so session/job rows commit atomically with the appointment change. Provider calls never run inside this listener (they are jobs).

Listeners (each in its own `@Component`, method annotated `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)` and `@Transactional(propagation = Propagation.REQUIRES_NEW)`; exceptions are caught and logged by class name, never rethrown):

| Event | Listener (owner) | Action |
|---|---|---|
| CaregiverAccessChangedEvent, `revoked \|\| !messagingAllowed` | `communications.listener.CommunicationsAccessListener` (messaging-api) | For every active `thread_members` row of the caregiver in threads with `patient_system_id = patientSystemId`: `removed_at = now`, `removal_reason = 'ACCESS_REVOKED'`, `archived_at = null`; mark that caregiver's unread notifications for those threads read. Not re-added automatically when access returns. |
| CaregiverAccessChangedEvent, `revoked \|\| !joinCallsAllowed` | `calls.listener.CallAccessListener` (video-api) | For the caregiver's open participant rows (REQUESTED/ADMITTED) in sessions of this patient's appointments: status REMOVED, `removal_reason='ACCESS_REVOKED'`, `removed_at=revoked_at=now`; if it was ADMITTED: `removal_pending=true` and enqueue REMOVE_PARTICIPANT. |
| UserLoggedOutEvent | `calls.listener.CallAccessListener` (video-api) | Same as above for all open rows of `systemId` (any role) with reason `LOGGED_OUT`. |

Synchronous listeners (plain `@EventListener`, run inside the publisher's transaction; a failure fails the grant change):

| Event | Listener | Action |
|---|---|---|
| CaregiverMessagingRegainedEvent | `communications.listener.CommunicationsAccessListener.onMessagingRegained` | Ends every still-active membership of the caregiver in this patient's threads (`removal_reason = wasExpired ? 'ACCESS_EXPIRED' : 'ACCESS_REVOKED'`), marks their notifications for those threads read. A membership that survived a revocation or lapse (for example because the AFTER_COMMIT clean-up failed and only logged) can therefore never carry its old history boundary into the new grant: the caregiver must be added again and sees only messages sent after that. |
| CaregiverApprovalPendingEvent | `communications.listener.CaregiverApprovalNotificationListener` | `pending`: SYSTEM notification for the patient (7.4). Not pending: that patient's unread approval notifications from this caregiver are marked read. |

### 6.1 Grant expiry (`communications.job.CaregiverAccessExpirySweeper`)
Nothing is published when a grant simply passes `expires_at`. Request-time checks already deny it; the sweeper makes membership match. Every `carenexus.messaging.access-expiry-sweep-interval-ms` (default 60000, env `MESSAGING_ACCESS_EXPIRY_SWEEP_MS`) it takes up to 200 due grants (4.7) and, each in its own transaction with the row locked and re-checked: ends the caregiver's memberships in this patient's threads with `ACCESS_EXPIRED` (and marks their notifications for those threads read), stamps `expiry_processed_at = now`, and publishes `CaregiverAccessChangedEvent(revoked = true, messagingAllowed = false, joinCallsAllowed = false)` so calls drop the caregiver's open rows. Extending a lapsed grant (PATCH `expiresAt`) never restores memberships (CaregiverMessagingRegainedEvent above), whether or not the sweeper already ran.

---

## 7. Messaging API (owner: messaging-api, package `com.carenexus.api.communications`)

Suggested classes: `model/MessageThread`, `model/ThreadMember`, `model/Message`, `model/Notification`; `repository/*Repository`; `service/CommunicationsAccessService` (all checks below), `service/RecipientService`, `service/ThreadService`, `service/MessageService`, `service/NotificationService`; `controller/CommunicationsController` (`/api/communications/recipients`, `/threads/**` except messages), `controller/ThreadMessageController` (`/threads/{id}/messages`, `/read`), `controller/NotificationController`; `dto/response/PageResponse<T>`; `listener/CommunicationsAccessListener`.

### 7.1 Access rules (checked on EVERY request)
- ADMIN: 403 "Administrators cannot access conversations" on all `/api/communications/**` endpoints.
- Caller "can act for patient P":
  - PATIENT: caller == P.
  - DOCTOR: `relationshipService.isDoctorAssignedToPatient(caller, P)`.
  - CAREGIVER: active grant with `PatientPermission.MESSAGING` (use `authorizationService.requirePatientAccess(P, PatientPermission.MESSAGING)` so denials are audited).
- Thread access = caller is an ACTIVE member (`left_at IS NULL AND removed_at IS NULL`) AND can act for `thread.patient_system_id`. Unknown thread id: 404 "Conversation not found". Thread exists but the caller fails either condition: 403 "You no longer have access to this conversation". The frontend treats 403 on a thread as the "permission removed" state.
- Visible messages for a member: `message_id > history_start_message_id` and `is_deleted = false`. This boundary applies to messages, previews, unread counts and notifications (a member never gets notifications for messages before joining).
- Sender is always the caller from the JWT; patient context always comes from the thread (never from the request on send).
- Membership eligibility for patient P: the patient P (role PATIENT); doctors in `getRelatedDoctorsForPatient(P)`; caregivers with an active grant where `PatientPermission.MESSAGING.isGrantedBy` is true. ADMIN never.
- Allowed thread shapes:
  - PRIVATE: exactly 2 members, one DOCTOR and one PATIENT (=P) or one DOCTOR and one CAREGIVER (for P).
  - CARE_TEAM: always contains P, at least one DOCTOR, any number of eligible caregivers/doctors.

### 7.2 Labels
`name` = "First Last". `label`: DOCTOR "Dr. First Last"; PATIENT "First Last"; CAREGIVER "First Last (Caregiver for PatientFirst PatientLast)".

### 7.3 Endpoints
Pagination envelope (thread list, notifications): `{ "content": [...], "page": 0, "size": 20, "totalElements": 3, "totalPages": 1 }`; params `page` (default 0), `size` (default 20, max 50).

**GET `/api/communications/recipients?patientSystemId=PAT-00001`**
`patientSystemId` optional for PATIENT (defaults to self), required otherwise (400 "Patient ID is required"). Caller must be able to act for P (403 otherwise). Caller is excluded from the list. 200:
```json
{ "patientSystemId": "PAT-00001", "patientName": "Thomas Dube",
  "recipients": [
    { "systemId": "DOC-00002", "name": "Amara Osei", "role": "DOCTOR", "label": "Dr. Amara Osei",
      "canStartPrivate": true, "canAddToCareTeam": true, "canAddToExistingCareTeam": true },
    { "systemId": "CGV-00001", "name": "Sarah Dube", "role": "CAREGIVER", "label": "Sarah Dube (Caregiver for Thomas Dube)",
      "canStartPrivate": false, "canAddToCareTeam": true, "canAddToExistingCareTeam": true } ] }
```
`canStartPrivate` is true only when the pair (caller, recipient) is an allowed PRIVATE shape. `canAddToCareTeam`: the person may be included when the caller STARTS a CARE_TEAM conversation (any caller who can act for P, caregivers included). `canAddToExistingCareTeam`: the caller may add the person to an EXISTING CARE_TEAM conversation, true only when the caller is the patient or a doctor (caregivers get 403 from `POST /threads/{id}/members`, so it is false for every recipient when the caller is a caregiver). Use `canAddToExistingCareTeam` (or `Thread.canManageMembers`) for "Add people" UI on an existing thread.

**GET `/api/communications/threads?patientSystemId=&archived=false&page=0&size=20`**
Threads where the caller is an active member and passes the access rule; `patientSystemId` optional filter; `archived=false` (default) excludes threads with the caller's `archived_at` set, `archived=true` returns only those. Order `last_message_at DESC NULLS LAST, thread_id DESC`. Avoid N+1 (compute the caregiver's allowed patients / doctor's related patients once per request). 200 `PageResponse<Thread>`.

`Thread`:
```json
{ "threadId": 41, "type": "CARE_TEAM", "subject": "Blood pressure follow-up",
  "patientSystemId": "PAT-00001", "patientName": "Thomas Dube",
  "members": [ { "systemId": "PAT-00001", "name": "Thomas Dube", "role": "PATIENT", "label": "Thomas Dube" } ],
  "lastMessage": { "messageId": 902, "senderSystemId": "DOC-00002", "senderLabel": "Dr. Amara Osei",
                   "preview": "first 120 characters of the body", "urgent": false, "sentAt": "2026-11-03T08:15:00Z" },
  "unreadCount": 2, "hasUrgentUnread": false, "archived": false,
  "lastMessageAt": "2026-11-03T08:15:00Z", "createdAt": "2026-11-01T07:00:00Z",
  "canManageMembers": true }
```
`members` = active members only. `lastMessage` = newest visible message for the caller (respecting the boundary) or null. `unreadCount` = visible messages with `message_id > last_read_message_id` and `sender_system_id <> caller`. `canManageMembers` = CARE_TEAM and caller role PATIENT or DOCTOR.

**POST `/api/communications/threads`**
```json
{ "patientSystemId": "PAT-00001", "type": "PRIVATE", "subject": "Question about my medication",
  "memberSystemIds": ["DOC-00002"], "clientThreadId": "0b6f6c1e-0c1e-4a71-9f61-4b8f2d0d8f10" }
```
- Caller must be able to act for P and is added automatically. For CARE_TEAM the patient P is added automatically. `subject` optional (max 200, 400 "Subject must be at most 200 characters"). `clientThreadId` optional (8-64 chars `[A-Za-z0-9_-]`).
- PRIVATE: `memberSystemIds` must contain exactly the one other member; shape must be allowed (400 "A private conversation is between a doctor and the patient or one of their caregivers"). If an active PRIVATE thread with exactly these two active members and the same patient exists, return it with 200 (no duplicate). Otherwise create, 201.
- CARE_TEAM: all members eligible (400 "{name} cannot be added to this conversation"), at least one doctor (400 "A care team conversation needs at least one doctor"). If `clientThreadId` was already used by the caller, return that thread with 200. This also holds for concurrent retries: when two requests with the same `clientThreadId` race and the unique index `ux_message_threads_client_thread` rejects the second insert, the controller looks the stored thread up in a new transaction (access re-checked) and returns it with 200, never 409/500.
- `POST /threads/{id}/members` takes the same thread row lock as sending (`SELECT ... FOR UPDATE` on `message_threads`) before reading `MAX(message_id)` for the new member's history boundary, so a concurrent send is either fully before or fully after the boundary.
- New members: `history_start_message_id = 0` (thread is new), `last_read_message_id = 0`, `added_by_system_id = caller`.
- Response: `Thread`.

**GET `/api/communications/threads/{threadId}`** -> 200 `Thread`.

**POST `/api/communications/threads/{threadId}/members`** `{ "systemIds": ["CGV-00001"] }`
CARE_TEAM only (409 "Members of a private conversation cannot be changed"); caller active member with role PATIENT or DOCTOR (403 "Only the patient or a doctor can change members"); each new member eligible (400 as above). Adding a never-member: insert; re-adding a former member: same row, `left_at = removed_at = removal_reason = archived_at = null`. In both cases `joined_at = now`, `history_start_message_id = last_read_message_id = current MAX(message_id) of the thread (0 if none)`. Active members are ignored. 200 `Thread`.

**DELETE `/api/communications/threads/{threadId}/members/{systemId}`**
CARE_TEAM only (409 as above). The patient P cannot be removed (409 "The patient cannot be removed from their care team conversation"). Self (doctor/caregiver): `left_at = now`, reason LEFT. Others: caller must be PATIENT or DOCTOR member; `removed_at = now`, `removed_by_system_id = caller`, reason REMOVED_BY_MEMBER. A thread must keep at least one doctor (409 "A care team conversation needs at least one doctor"). 204.

**PUT `/api/communications/threads/{threadId}/archive`** `{ "archived": true }` -> sets/clears the caller's `archived_at`. 204.

**GET `/api/communications/threads/{threadId}/messages?before=&after=&limit=30`**
At most one of `before`/`after` (400 "Use either before or after, not both"); `limit` default 30, max 100. No cursor: newest `limit` visible messages. `before=id`: visible messages with `message_id < id`, newest `limit`. `after=id`: visible messages with `message_id > id`, oldest `limit` (used for 5 s polling). Messages always returned ascending by `messageId`. 200:
```json
{ "threadId": 41,
  "messages": [ { "messageId": 902, "threadId": 41, "clientMessageId": "c5d...", "senderSystemId": "DOC-00002",
                  "senderName": "Amara Osei", "senderRole": "DOCTOR", "senderLabel": "Dr. Amara Osei",
                  "body": "Please take the new dose with food.", "urgent": false,
                  "sentAt": "2026-11-03T08:15:00Z", "mine": false } ],
  "hasMore": true, "lastReadMessageId": 899 }
```
`hasMore` = more messages exist in the requested direction (no cursor or `before`: older; `after`: newer). `clientMessageId` is only included (non-null) for the caller's own messages. Audit `logClinicalAccess("READ", "MESSAGE_THREAD", threadId, patient)` only when `after` is absent (not on polling).

**POST `/api/communications/threads/{threadId}/messages`**
```json
{ "clientMessageId": "c5d6a0de-6a43-4d2e-9b8e-2c1f9b1f6f0a", "body": "Is it fine to take it at night?", "urgent": false }
```
- `clientMessageId` required (8-64 chars `[A-Za-z0-9_-]`, 400 "A valid clientMessageId is required"). `body` trimmed, 1-4000 chars (400 "Message cannot be empty" / "Messages can be at most 4000 characters"). `urgent` default false.
- Duplicate `(thread, sender=caller, clientMessageId)`: return the stored message with 200 (body of the retry is ignored). New: 201.
- One transaction: insert message (`patient_system_id` from thread, `recipient_system_id = NULL`, `is_urgent`, `sent_at = now`), update thread `last_message_id`, `last_message_at`, `updated_at`; sender's `last_read_message_id = new id`; for every OTHER active member who still passes the access rule: clear `archived_at` and insert a notification (7.4). Then audit `logClinicalAccess("CREATE", "MESSAGE", messageId, patient)`.
- Race on the unique index: the service pre-checks; if the insert still raises `DataIntegrityViolationException`, the controller catches it and returns the existing message (looked up in a new transaction) with 200.
- Response: `Message` (shape above).

**POST `/api/communications/threads/{threadId}/read`** `{ "lastReadMessageId": 902 }`
Sets the caller's `last_read_message_id = max(current, min(value, thread.last_message_id))`; marks the caller's notifications of this thread with `message_id <= value` read (`is_read = true`, `read_at = now`). 200 `{ "lastReadMessageId": 902, "unreadCount": 0 }`.

### 7.4 Notifications
MESSAGE notifications are created only by `NotificationService` inside the send transaction: one row per recipient, `type='MESSAGE'`, `priority = urgent ? 'HIGH' : 'NORMAL'`, `title = urgent ? "Urgent message" : "New message"`, `message = "{senderLabel} sent a message about {patientName}"`, except `"{senderLabel} sent you a message"` when the recipient IS the patient or the sender IS the patient (never "Thomas Dube sent a message about Thomas Dube"), `thread_id`, `message_id`, `patient_system_id`, `actor_system_id = sender`, `action_url = NULL`, `icon = 'chatbubble-outline'`. Never include the body or the subject. Unique `(system_id, message_id)` protects retries.

Preference: a recipient whose `user_settings.message_notifications` is false (UI key `new_message`, `PATCH /api/profile/settings/notifications { "messageNotifications": false }`) gets no MESSAGE notification rows. In-app notifications are the only channel, so this is the whole opt-out. The inbox is unaffected: `Thread.unreadCount`, `hasUrgentUnread` and urgent highlighting come from read positions, not notifications. `unreadMessageCount` (it counts MESSAGE notifications) stays 0 for such a user. No settings row means on.

Caregiver approval requests (SYSTEM): when a grant starts waiting for the patient's approval (6, CaregiverApprovalPendingEvent; today: a caregiver-created dependent with requested flags, or an admin turning a flag on), the patient gets `type='SYSTEM'`, `title = "Caregiver approval needed"`, `message = "{caregiverName} is waiting for your approval to message your care team" | "... to join your video calls" | "... to message your care team and join your video calls"`, `priority = 'NORMAL'`, `icon = 'people-outline'`, `action_url = '/dashboard/patient?section=my-proxies'`, `patient_system_id = patient`, `actor_system_id = caregiver`, no thread/message ids. It is marked read when the request is approved, withdrawn or the grant is revoked. Not governed by `message_notifications` (it asks for consent). Grants that were already pending before this change (V15 re-approval) do not get one.

| Method | Path | Success |
|---|---|---|
| GET | `/api/notifications?unreadOnly=false&page=0&size=20` | 200 `PageResponse<Notification>` newest first; MESSAGE notifications whose thread the caller can no longer access are excluded |
| GET | `/api/notifications/unread-count` | 200 `{ "unreadCount": 5, "unreadMessageCount": 3 }` (same exclusion) |
| POST | `/api/notifications/{notificationId}/read` | 204; 404 "Notification not found" if not the caller's |
| POST | `/api/notifications/read-all` | 204 |

`Notification`: `{ "notificationId": 7, "type": "MESSAGE", "title": "New message", "message": "Dr. Amara Osei sent a message about Thomas Dube", "priority": "NORMAL", "read": false, "readAt": null, "createdAt": "2026-11-03T08:15:00Z", "threadId": 41, "messageId": 902, "patientSystemId": "PAT-00001", "actionUrl": null, "target": null }`.
`target`: what activating the notification opens. `null` for MESSAGE (use `threadId`); `"CAREGIVER_ACCESS"` for approval requests: the patient web dashboard opens its caregiver-access section (sidebar key `my-proxies`, `PatientWebCaregiverAccess`). `actionUrl` carries the same target as a route (`/dashboard/patient?section=my-proxies`); clients should switch on `target`.
Notifications are for any role including ADMIN (their own rows only).

---

## 8. Calls API (owner: video-api, package `com.carenexus.api.calls`)

Suggested classes: `config/LiveKitProperties` (`@ConfigurationProperties("carenexus.livekit")`), `config/CallsProperties` (`carenexus.calls`), `config/CallsSchedulingConfig` (`@EnableScheduling`), `config/LiveKitStartupChecks`; `model/CallSession`, `model/CallParticipant`, `model/CallJob`, `model/ProviderEvent`; `provider/CallProvider` (interface) + `provider/LiveKitCallProvider`; `service/CallSessionService`, `service/CallAccessService`, `service/CallTokenService`, `service/CallInfoService` (batch call fields for appointment DTOs), `service/LiveKitWebhookService`; `job/CallJobWorker`, `job/CallExpirySweeper`; `listener/CallAccessListener`, `listener/AppointmentCallListener`; `controller/CallController`, `controller/LiveKitWebhookController`; `dto/*`.

### 8.1 Session lifecycle
Join window: `join_opens_at = scheduled_start_utc - 15 min`, `join_closes_at = scheduled_start_utc + duration + 60 min` (from `carenexus.calls.*`). `scheduled_start_utc = appointment.scheduledAt.atZone(ZoneId.of(clinicZone)).toInstant()` for times entered after this release (create, reschedule, confirm-time). `room_name = "cn_" + 32 random hex`; `provider_identity = "p_" + 32 random hex` (both `SecureRandom`; never contain system ids or appointment ids).

| Trigger | Effect |
|---|---|
| VIDEO appointment created (CREATED_VIDEO) or type changed to VIDEO with no usable session | session PREPARING (or reset an existing CANCELLED/ENDED one: new `room_name`, cleared start/end fields), schedule, enqueue CREATE_ROOM with `next_attempt_at = max(now, join_opens_at - 5 min)` |
| CREATE_ROOM succeeds | READY, `room_created_at` (only from PREPARING) |
| CREATE_ROOM attempts exhausted | FAILED, `last_error` (never shown verbatim to clients) |
| Doctor POST start, READY, window open, appointment SCHEDULED/RESCHEDULED | LIVE, `started_at/by`; doctor participant row ADMITTED (auto) |
| Doctor POST start on LIVE | doctor rejoin: ensure an ADMITTED doctor row (create if none open) |
| Doctor POST start on PREPARING/FAILED (window open) | set PREPARING, enqueue CREATE_ROOM now (if none open), 409 "The call room is being prepared. Try again in a few seconds." |
| Doctor POST end on LIVE | ENDED (`DOCTOR_ENDED`); every open participant REMOVED (`CALL_ENDED`, `revoked_at=now`); enqueue DELETE_ROOM. Appointment and consultation status unchanged. |
| Appointment cancelled | CANCELLED (`APPOINTMENT_CANCELLED`); open participants REMOVED (`APPOINTMENT_CANCELLED`); cancel pending CREATE_ROOM; DELETE_ROOM if room was created or session was LIVE |
| Appointment rescheduled (any non-CANCELLED status) | open participants REMOVED (`RESCHEDULED`); cancel pending CREATE_ROOM; DELETE_ROOM for the old room if created/LIVE; new `room_name`; new schedule (time is now unambiguous); PREPARING; CREATE_ROOM at new window; clears NEEDS_TIME_CONFIRMATION |
| Type changed from VIDEO | CANCELLED (`TYPE_CHANGED`) as for cancel |
| Doctor POST confirm-time on NEEDS_TIME_CONFIRMATION | schedule from the confirmed wall-clock time, `time_confirmed_at/by`, PREPARING, CREATE_ROOM |
| `CallExpirySweeper` (every 60 s): status in PREPARING/READY/FAILED/LIVE and `join_closes_at < now` | ENDED (`EXPIRED`); open participants REMOVED (`EXPIRED`); cancel pending CREATE_ROOM; DELETE_ROOM if created/LIVE |
| Sweeper, LIVE sessions | re-validate ADMITTED caregivers (`hasCaregiverPermission(..., JOIN_CALLS)`) and remove failures (`ACCESS_REVOKED`); this covers grant expiry |
| Active VIDEO appointment without a session (data inserted outside the app) | created lazily on first read as NEEDS_TIME_CONFIRMATION |

Appointment COMPLETED / NO_SHOW does not touch the session (no event). ENDED and CANCELLED are final until a reschedule/type change resets them. Use `@Version` on `CallSession` for concurrent updates.

### 8.2 Who may do what
- GET status: the appointment's doctor; the appointment's patient; a caregiver with `JOIN_CALLS` for the patient; ADMIN (read only). Others 403 "You are not allowed to access this call".
- start, end, confirm-time, admit, deny, remove: only the appointment's doctor (403 "Only the appointment's doctor can manage this call").
- join-request: the appointment's patient or a caregiver with `JOIN_CALLS` (re-checked). Doctor: 400 "Doctors join by starting the call". Admin: 403.
- token, leave: a participant of this session (rules below). Admin: 403.
- Re-check consent when admitting and when issuing a token (patient: still the appointment's patient; caregiver: `JOIN_CALLS` granted now; doctor: still the appointment's doctor).

### 8.3 Endpoints (`CallController`, base `/api/appointments/{appointmentId}/call`)
Errors common to all: 404 "Appointment not found"; 404 "This appointment has no video call" (not VIDEO / no session); 403 per 8.2.

**`CallStatus` response** (returned by GET and every POST except token):
```json
{ "appointmentId": 12, "callStatus": "LIVE",
  "scheduledStartUtc": "2026-11-20T08:30:00Z", "joinWindowOpensAt": "2026-11-20T08:15:00Z",
  "joinWindowClosesAt": "2026-11-20T10:00:00Z", "serverTime": "2026-11-20T08:31:02Z", "joinWindowOpen": true,
  "scheduledAtLocal": "2026-11-20T10:30:00",
  "patientName": "Thomas Dube", "doctorName": "Dr. Amara Osei",
  "myRole": "CAREGIVER",
  "myParticipant": { "participantId": 77, "role": "CAREGIVER", "label": "Sarah Dube (Caregiver for Thomas Dube)",
                     "actingForPatient": false, "status": "REQUESTED", "requestedAt": "2026-11-20T08:30:40Z",
                     "admittedAt": null, "connected": false, "removalPending": false, "me": true },
  "participants": [ ],
  "canStart": false, "canEnd": false, "canRequestJoin": false, "canConfirmTime": false,
  "removalPending": false }
```
- `scheduledAtLocal` = the appointment's stored wall-clock `scheduledAt` (shown on confirm-time and sent back as its body).
- `myParticipant` = caller's open row (REQUESTED/ADMITTED), else the caller's most recent row, else null.
- `participants`: for the doctor/admin, every row with status REQUESTED or ADMITTED plus REMOVED rows with `removal_pending = true`; for others, ADMITTED rows only.
- `connected` = `provider_joined_at` not null and (`provider_left_at` null or older than `provider_joined_at`).
- `canStart` (doctor; READY or LIVE, window open, appointment SCHEDULED/RESCHEDULED), `canEnd` (doctor; LIVE), `canRequestJoin` (patient/caregiver; LIVE, window open, no open row), `canConfirmTime` (doctor; NEEDS_TIME_CONFIRMATION).
- `removalPending` (doctor/admin only, else false) = any REMOVE_PARTICIPANT or DELETE_ROOM job of this session is PENDING, RUNNING or FAILED (provider never confirmed), or any participant has `removal_pending = true`.
- Labels: DOCTOR "Dr. First Last"; PATIENT "First Last (Patient)"; CAREGIVER "First Last (Caregiver for PatientFirst PatientLast)", or with `actingForPatient` "First Last (Caregiver speaking for PatientFirst PatientLast)".

| Method + path | Body | Success | Specific errors (409 unless stated) |
|---|---|---|---|
| GET `` | - | 200 CallStatus | - |
| POST `/start` | - | 200 CallStatus | "Confirm the appointment time before starting the call"; "The call room is being prepared. Try again in a few seconds."; "This call opens 15 minutes before the appointment"; "This call has closed"; "This appointment is cancelled"; "This call has ended" |
| POST `/join-request` | `{ "actingForPatient": false }` (caregivers only; ignored for patients) | 200 CallStatus (idempotent: existing open row returned; while REQUESTED `actingForPatient` may be updated) | "The doctor has not started the call yet"; window messages; "This call has ended" |
| POST `/admissions/{participantId}/admit` | - | 200 CallStatus (ADMITTED: no-op) | 404 "Participant not found"; "This join request is no longer waiting"; "This person no longer has permission to join this call" (row -> DENIED) |
| POST `/admissions/{participantId}/deny` | - | 200 CallStatus | 404; "This join request is no longer waiting" |
| POST `/admissions/{participantId}/remove` | - | 200 CallStatus | 404; "This person is not in the call" (not ADMITTED); 400 "You cannot remove yourself" |
| POST `/token` | - | 200 `{ "serverUrl": "ws://localhost:7880", "token": "<jwt>", "expiresAt": "2026-11-20T08:36:02Z", "identity": "p_..." }` with header `Cache-Control: no-store` | "This call is not live"; "You have not been admitted to this call yet"; "Your access to this call has been removed"; window messages; "Video calls are temporarily unavailable. Please try again later." (provider not configured); 403 when consent re-check fails |
| POST `/leave` | - | 200 CallStatus (no open row: no-op) | - |
| POST `/end` | - | 200 CallStatus (ENDED/CANCELLED: no-op) | "The call has not started" |
| POST `/confirm-time` | `{ "scheduledAt": "2026-11-20T10:30:00" }` | 200 CallStatus | "This call's time is already confirmed"; "The appointment time has changed. Reload and confirm again." (body != stored `scheduledAt`) |

Participant state machine: REQUESTED -> ADMITTED (admit) | DENIED (deny / consent failed) | LEFT (leave before admission) | REMOVED (revocation/logout/end/cancel/reschedule/expiry). ADMITTED -> LEFT (leave) | REMOVED. Terminal rows stay as history; a new join-request creates a NEW row with a NEW `provider_identity` (at most one open row per user per session, index `ux_call_participants_open`). Doctor rows are created ADMITTED by start.
- leave: open row -> LEFT, `left_at`, `revoked_at = now`; if the row had `provider_joined_at` or a token, enqueue REMOVE_PARTICIPANT (cleanup of a stale connection). Leaving never ends the call (also for the doctor) and never changes appointment/consultation status.
- remove/revoke: REMOVED, `removed_at = revoked_at = now`, reason; if it was ADMITTED: `removal_pending = true` + REMOVE_PARTICIPANT job; job success (or provider "not found") sets `removal_pending = false`.
- token: requires session LIVE, window open, caller's open row ADMITTED, `revoked_at` null, consent re-check; sets `last_token_issued_at`. Audit `logClinicalAccess("CREATE", "CALL_TOKEN", appointmentId, patient)`. Also audit START, END, ADMIT, DENY, REMOVE with resource type `CALL` and resource id = appointmentId. Never log the token.

### 8.4 LiveKit provider
- Maven: `io.livekit:livekit-server:0.16.0` (released 2026-09-15; 0.17.0 of 2026-11-02 is too new). It needs okhttp 5 and Kotlin 2.3, which Spring Boot 3.3.3 pins lower, so `pom.xml` `<properties>` also gets `<okhttp.version>5.3.2</okhttp.version>` and `<kotlin.version>2.3.0</kotlin.version>` (section 10.1).
- API used (verified against the 0.16.0 jar):
  - `RoomServiceClient.Companion.createClient(url, apiKey, apiSecret)` (Java: `RoomServiceClient.createClient(...)`); calls return `retrofit2.Call<T>`, run with `.execute()` and check `response.isSuccessful()`.
  - `createRoom(String name, Integer emptyTimeoutSeconds, Integer maxParticipants)`; `deleteRoom(String name)`; `removeParticipant(String room, String identity)`. HTTP 404 / Twirp `not_found` on delete/remove = success.
  - `AccessToken(apiKey, apiSecret)`: `setIdentity(identity)`, `setName(label)`, `setMetadata(json)`, `setExpiration(Date.from(expiresAt))` (expiresAt = now + 5 min, `carenexus.livekit.token-ttl-seconds`), `addGrants(new RoomJoin(true), new RoomName(roomName), new CanPublish(true), new CanSubscribe(true), new CanPublishData(false), new CanPublishSources(List.of("camera", "microphone")), new CanUpdateOwnMetadata(false))`, `toJwt()`.
  - `WebhookReceiver(apiKey, apiSecret).receive(rawBody, authorizationHeader)` returns `livekit.LivekitWebhook.WebhookEvent` (`getId()`, `getEvent()`, `getCreatedAt()` epoch seconds, `getRoom().getName()`, `getParticipant().getIdentity()`); throws on a bad signature.
- Room settings: `emptyTimeout = clamp(seconds until join_closes_at, 300, 86400)`, `maxParticipants = carenexus.livekit.max-participants` (6). LiveKit's built-in chat uses the data channel; `CanPublishData(false)` disables it and the web UI renders no chat. Screen share is excluded by `CanPublishSources`.
- Token `name` = participant label (8.3), `metadata` = `{"role":"CAREGIVER","actingForPatient":true}`; no system ids or emails in token claims.
- `CallProvider.isConfigured()` = `enabled && url, apiKey, apiSecret non-blank`. Not configured: CREATE_ROOM jobs fail with `last_error = "LiveKit is not configured"` (session ends FAILED), token returns the 409 "temporarily unavailable" message, the webhook returns 404. Startup never fails for missing config; `LiveKitStartupChecks` fails startup only when enabled outside the `local`/`docker`/`test` profiles with `api-secret` equal to `secret` or shorter than 32 characters.
- Removal semantics: on revocation the row is REMOVED with `revoked_at` cutoff, no further token is issued (checked on every token request), and REMOVE_PARTICIPANT runs `removeParticipant`. If the provider call fails, the job keeps retrying (30 attempts, capped backoff) and `removalPending = true` is exposed in CallStatus; never report the removal as done before the provider confirms. Token expiry alone does not disconnect a connected participant (LiveKit refreshes tokens for connected clients), which is why removal is explicit.

### 8.5 Jobs (`CallJobWorker`)
`@Scheduled(fixedDelayString = "${carenexus.calls.worker-interval-ms:5000}")`, only when `carenexus.calls.worker-enabled` (default true). Each run: reset RUNNING jobs with `locked_at < now - 2 min` to PENDING; claim up to 10 due jobs with a native `SELECT ... WHERE status = 'PENDING' AND next_attempt_at <= now() ORDER BY next_attempt_at LIMIT 10 FOR UPDATE SKIP LOCKED`, mark RUNNING; execute each in its own `TransactionTemplate`; on success SUCCEEDED + `completed_at` (+ session/participant updates); on failure `attempts++`, `last_error` (exception class + short reason, no secrets), backoff, or FAILED at `max_attempts` (CREATE_ROOM -> session FAILED unless the session already moved on). Enqueue = insert PENDING unless an open job with the same `(job_type, room_name, provider_identity)` exists. CREATE_ROOM for a session no longer PREPARING -> CANCELLED.

### 8.6 Webhook (`LiveKitWebhookController`)
`POST /api/integrations/livekit/webhook`, `consumes = MediaType.ALL_VALUE`, body read as raw `String` (LiveKit sends `application/webhook+json`), header `Authorization`. Disabled (`carenexus.livekit.webhook-enabled=false` or provider not configured) -> 404. Invalid signature -> 401 `{"message":"Invalid webhook signature"}`. Body over 64 KB -> 400. Otherwise always 200 `{}` (also for duplicates and ignored events).
Processing: `event_id = event.getId()` (blank -> sha256 of the body); insert into `provider_events` first; unique violation -> duplicate, return 200 without processing. Then:
- `participant_joined`: find participant by identity. Unknown identity in a known room, row not ADMITTED, `revoked_at` set, or session not LIVE -> enqueue REMOVE_PARTICIPANT (`REMOVAL_ENQUEUED`). Else set `provider_joined_at` if the event is newer (`APPLIED`) or mark `STALE`.
- `participant_left`: set `provider_left_at` if newer; never changes participant status (network drops are not a Leave).
- `room_finished`, `room_started`, others: record, no state change (`IGNORED`). A finished room never ends a LIVE session (doctor may rejoin; LiveKit auto-creates the room on join).
- Unknown room -> `IGNORED`.

---

## 9. Appointment responses (owner: video-api)

### 9.1 `AppointmentResponse` and `ScheduleEntryResponse` additions
```java
private String callStatus;          // null unless type VIDEO with a session; one of the 7 session statuses
private String callJoinRoute;       // "/dashboard/call/{appointmentId}" when callStatus != null, else null
private Instant joinWindowOpensAt;  // null when NEEDS_TIME_CONFIRMATION / no session
private Instant joinWindowClosesAt;
```
`videoConsultationLink` stays in both DTOs (so clients do not break) but is always `null`. `AppointmentService.createAppointment`/`updateAppointment` no longer write `video_consultation_link` and ignore the request field; `resolveVideoLink`/`generateVideoLink` and `carenexus.video.base-url` are removed. `DoctorScheduleService` and `DoctorPatientService` map the same four fields (and null links) via `CallInfoService.describe(Collection<Integer> appointmentIds)` (one batch query, no N+1). The call route never contains a provider URL, room name or token.

### 9.2 Frontend `Appointment` (use-appointments.ts, snake_case mapping, call-web)
`call_status`, `call_join_route`, `join_window_opens_at`, `join_window_closes_at` (strings or null). Schedule entry types in `use-doctor-schedule.ts` get `callStatus`, `callJoinRoute`, `joinWindowOpensAt`, `joinWindowClosesAt`.

### 9.3 Proxy appointments
`CaregiverAppointment` fields are added by permissions (5.7); the backend `CaregiverDependentActivityResponse` already embeds `AppointmentResponse`.

---

## 10. Configuration and dependencies

### 10.1 pom.xml (video-api)
```xml
<properties>
  <java.version>17</java.version>
  <okhttp.version>5.3.2</okhttp.version>
  <kotlin.version>2.3.0</kotlin.version>
</properties>
...
<dependency>
  <groupId>io.livekit</groupId>
  <artifactId>livekit-server</artifactId>
  <version>0.16.0</version>
</dependency>
```

### 10.2 application.yml (video-api writes all of it)
Base document: remove `carenexus.video`; add
```yaml
carenexus:
  livekit:
    enabled: ${LIVEKIT_ENABLED:false}
    url: ${LIVEKIT_URL:}                 # server-side API URL (http/https), used by the API only
    client-url: ${LIVEKIT_CLIENT_URL:}   # returned to browsers as serverUrl (ws/wss)
    api-key: ${LIVEKIT_API_KEY:}
    api-secret: ${LIVEKIT_API_SECRET:}
    webhook-enabled: ${LIVEKIT_WEBHOOK_ENABLED:false}
    token-ttl-seconds: 300
    max-participants: 6
  calls:
    join-opens-minutes-before: 15
    join-closes-minutes-after-end: 60
    worker-enabled: ${CALLS_WORKER_ENABLED:true}
    worker-interval-ms: 5000
    sweeper-interval-ms: 60000
  messaging:
    max-body-length: 4000
    access-expiry-sweep-interval-ms: ${MESSAGING_ACCESS_EXPIRY_SWEEP_MS:60000}   # 6.1
```
API docs: `springdoc.api-docs.enabled` / `springdoc.swagger-ui.enabled` are `${SWAGGER_ENABLED:false}` in the base document AND in the `docker` profile (Swagger UI and `/v3/api-docs` are not public unless `SWAGGER_ENABLED=true`); only the `local` profile turns them on.
`local` profile (DEV ONLY values):
```yaml
carenexus:
  livekit:
    enabled: true
    url: ${LIVEKIT_URL:http://localhost:7880}
    client-url: ${LIVEKIT_CLIENT_URL:ws://localhost:7880}
    api-key: ${LIVEKIT_API_KEY:devkey}
    api-secret: ${LIVEKIT_API_SECRET:secret}
    webhook-enabled: true
```
`docker` profile: same but `url: ${LIVEKIT_URL:http://livekit:7880}`. Staging/production supply `LIVEKIT_*` (LiveKit Cloud `wss://...`) from secrets; never commit real keys.

### 10.3 docker-compose.yml (video-api)
Add (and remove `VIDEO_BASE_URL` from the API service; add `depends_on: livekit: condition: service_started` and `extra_hosts: ["host.docker.internal:host-gateway"]` to the livekit service so it can reach an API running on the host):
```yaml
  livekit:
    image: livekit/livekit-server:v1.13.9
    container_name: carenexus-livekit
    # DEV ONLY: --dev uses the public dev key pair devkey/secret.
    command: ["--dev", "--bind", "0.0.0.0", "--node-ip", "127.0.0.1"]
    environment:
      LIVEKIT_CONFIG: |
        port: 7880
        rtc:
          tcp_port: 7881
          udp_port: 7882
          use_external_ip: false
        room:
          auto_create: true
        webhook:
          api_key: devkey
          urls:
            - http://carenexus-api:9000/api/integrations/livekit/webhook
            - http://host.docker.internal:9000/api/integrations/livekit/webhook
    ports:
      - "7880:7880"
      - "7881:7881"
      - "7882:7882/udp"
    extra_hosts:
      - "host.docker.internal:host-gateway"
```
`.env.example`: drop `VIDEO_BASE_URL`; document optional `LIVEKIT_URL`, `LIVEKIT_CLIENT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` overrides (dev defaults come from the profile). Local run: `docker compose up postgres-db livekit` + API with `local` profile + `npm run web`.

### 10.4 Frontend packages (call-web)
`cd frontend && npm install --save-exact livekit-client@2.23.0 @livekit/components-react@2.9.24 @livekit/components-styles@1.2.0`
- Released 2026-10-16, 2026-08-11 and 2025-11-14 (all at least two weeks old).
- `@livekit/components-react@2.9.24` peers: `react >=18`, `react-dom >=18` (React 19.2.3 OK), `livekit-client ^2.20.1`, `tslib ^2.6.2` (tslib 2.8.1 already installed), optional `@livekit/krisp-noise-filter` (do not install).
- These libraries are DOM-only: import them ONLY from `*.web.tsx` files (section 11.3).
- If importing `@livekit/components-styles` trips TypeScript, add `declare module '@livekit/components-styles';` in `src/types/livekit.d.ts`.

---

## 11. Frontend

### 11.1 Shared rules (messaging-web, call-web, permissions)
- HTTP only through `api` from `@/lib/auth/auth.interceptor` (adds the bearer token and refreshes on 401). Error text via `apiErrorMessage(e, fallback)` from `@/hooks/use-appointments`.
- Dialogs via `confirmAction` / `showMessage` from `@/lib/ui/dialogs`.
- Polling helper pattern (each frontend agent writes its own, no cross-agent hook dependency): run only while `document.visibilityState === 'visible'` and `navigator.onLine`; pause on `visibilitychange` to hidden and on `offline`; on return to visible or `online`, fetch immediately and resume. Clear timers on unmount. Guard `typeof document !== 'undefined'`.
- Loading, empty, error and offline states for every screen; keyboard accessible controls with `accessibilityLabel`; usable at 375 px width.
- Never `console.log` message bodies, tokens or responses.
- Copy must not mention encryption, end-to-end, monitoring, emergency response or certification. The only allowed mention of emergencies is this urgent helper text (exact): "Urgent highlights your message for the care team. It does not make a reply faster. For emergencies, call your local emergency number."

### 11.2 messaging-web
Files (suggested): `src/lib/communications/types.ts` (types mirroring section 7), `src/lib/communications/api.ts` (one function per endpoint), `src/lib/communications/drafts.ts`, `src/hooks/use-page-active.ts`, `src/hooks/use-threads.ts`, `src/hooks/use-thread-messages.ts`, `src/hooks/use-message-recipients.ts`, `src/hooks/use-notifications.ts`, `src/components/web/messages/messages-screen.tsx` (shared by the three roles), thread list, thread view, composer, new-conversation dialog, member manager, `src/components/web/notifications/notification-bell.tsx`.

Behaviour:
- Polling: open thread `GET .../messages?after={newestId}` every 5 s while active; thread list every 15 s; `/api/notifications/unread-count` every 30 s. After loading new messages into a visible thread, `POST /read` with the newest id.
- Sending: `clientMessageId = crypto.randomUUID()` per message (keep it for retries). States per local message: sending, sent (replaced by server copy, matched by `clientMessageId`), failed (Retry re-POSTs the same `clientMessageId`; Discard). Offline: show "You're offline. Messages will be sent when you retry." and keep the message as failed.
- Drafts: `sessionStorage` key `carenexus.draft.v1.{currentUserSystemId}.{threadId}` (new conversation: `...new.{patientSystemId}`); saved on change (debounced), restored on open, cleared on successful send. Wrap storage access in try/catch.
- States: empty inbox, empty thread, loading, error with Retry, offline banner, permission removed (403 on a thread: banner "You no longer have access to this conversation.", composer disabled, thread removed from the list on next refresh).
- Urgent: toggle in the composer with the helper text above; urgent messages get a highlighted style and an "Urgent" tag; never imply monitoring.
- History boundary is server-side; the UI just renders what it gets ("Messages sent before you joined are not shown" note on CARE_TEAM threads for members added later is optional).
- Patient context: patient = self; caregiver = select a dependent from `useCaregiverDependents()` with `messagingAllowed` (others shown disabled with "Waiting for {name} to approve messaging" when `canMessageCareTeam && !messagingAllowed`); doctor = choose a patient from `useDoctorPatients()` when starting a conversation, list shows all.
- Navigation entry points:
  - Patient: `patient-left-sidebar.tsx` enable `messages` and `notifications` (notifications opens the bell panel or a list section), enable `my-proxies` and render `PatientWebCaregiverAccess` (lazy default import from `./patient/patient-web-caregiver-access`) in `patient-dashboard-web.tsx`; `join-consultation` navigates to `my-appointments`.
  - Doctor: `doctor-left-sidebar.tsx` enable `messages`, `notifications`; `doctor-dashboard-web.tsx` renders the messages section.
  - Proxy: `patient-proxy-sidebar.tsx` remove `comingSoon` from `messages`; `patient-proxy-messages.tsx` renders the shared messages screen in caregiver mode.
  - Bell with unread badge in all three top navbars; clicking a MESSAGE notification opens the messages section on its `threadId` (shell state, e.g. `openThreadId`) and marks it read. Sidebar Messages item shows `unreadMessageCount` badge.

### 11.3 call-web
Route `src/app/dashboard/call/[appointmentId].tsx`: reads `appointmentId` via `useLocalSearchParams`, validates it is a positive integer, renders `<CallScreen appointmentId={n} />` imported from `@/components/web/call/call-screen`.
- `src/components/web/call/call-screen.web.tsx`: web implementation (may import livekit). `src/components/web/call/call-screen.tsx`: native fallback with the same export `export function CallScreen(props: { appointmentId: number }): React.JSX.Element` showing "Video consultations are available in the CareNexus web app." Metro picks `.web.tsx` on web, so native bundles never include LiveKit.
- `src/lib/calls/types.ts` (CallStatus response, participant, token types mirroring 8.3), `src/lib/calls/api.ts` (one function per endpoint), `src/hooks/use-call-status.ts` (polling).
Screen flow:
1. Load `GET .../call`. Show appointment, doctor/patient names and window times in Africa/Harare ("Opens at 10:15 CAT").
2. NEEDS_TIME_CONFIRMATION: doctor sees "Confirm appointment time" (shows `scheduledAtLocal` in clinic time, button posts confirm-time with exactly that value; link to reschedule instead); others see "Waiting for the doctor to confirm the appointment time."
3. Before the window: "This call opens at HH:mm CAT" (poll 15 s). PREPARING inside the window: "Preparing the call room..." (poll 3 s). FAILED: "The call room could not be prepared." Doctor gets Retry (POST start). ENDED/CANCELLED: final message and "Back to dashboard" (`router.replace(ROLE_HOME[role])`).
4. Device check (always before joining): camera/mic preview via `getUserMedia`/`createLocalTracks`, device selectors, mic level indicator. Handle NotAllowedError (explain how to allow in the browser, offer "Join with audio only" / "Join without camera"), NotFoundError (no camera/mic: audio-only or listen-only), in-use errors. Choices carried into the room.
5. Doctor: "Start call" (READY) / "Rejoin call" (LIVE) -> POST start -> POST token -> connect. Patient/caregiver: waiting area; when `canRequestJoin` show "Ask to join" (caregiver chooses "Attending with {patient}" or "Speaking for {patient}" -> `actingForPatient`), then poll GET every 3 s until `myParticipant.status` is ADMITTED -> POST token -> connect. DENIED: "The doctor did not admit you to this call." REMOVED: reason-specific message ("Your access to this call was removed.", "The call has ended.").
6. In call (`LiveKitRoom` with `serverUrl`/`token`, or `Room` from livekit-client): participant tiles with the server-provided `name` label (caregiver labels make "Caregiver for {patient}" visible), mic/camera toggles, device switch, connection quality, `RoomAudioRenderer`, "Reconnecting..." banner on `RoomEvent.Reconnecting`, recovered on `Reconnected`; on unexpected `Disconnected` request a new token (if still ADMITTED) and reconnect once, else show the status message. Never render chat; do not use the `VideoConference` prefab (it includes chat). No screen share, no recording UI.
7. Doctor admission panel (in call): polls GET every 3 s; lists REQUESTED participants with label and Admit / Deny, admitted participants with Remove; shows "Removal pending" for `removalPending`. Sound/visual cue for new requests.
8. While connected, poll GET every 5 s; if `callStatus` is ENDED/CANCELLED or `myParticipant.status` is not ADMITTED, disconnect and show the message.
9. Leave (everyone): POST leave, disconnect, back to dashboard; copy "Leaving does not end the appointment." End call (doctor only): `confirmAction('End call for everyone?', 'Everyone will be disconnected. Your consultation notes are not changed.', 'End call', true)` -> POST end.
Join buttons (all listed appointment components): for VIDEO appointments with `callJoinRoute` and status SCHEDULED/RESCHEDULED, show "Join video call" (`router.push(callJoinRoute)`); before the window label "Opens at HH:mm CAT" (still navigable); NEEDS_TIME_CONFIRMATION label "Confirm time" for doctors. For patients and caregivers NEEDS_TIME_CONFIRMATION is informational: `describeCallJoin` returns `{ route: null, label: "Time to be confirmed", informational: true }` and `CallJoinButton` renders a neutral status label, never a button. `describeCallJoin` returns null (no control) when `callJoinRoute` is not `/dashboard/call/{positive integer}`, and the call route shows "Call not available" for an invalid id without calling the API.
Call safety (`call-screen.web.tsx`, `call-room.web.tsx`, `call-ui.tsx`):
- Only the doctor's Start call / Rejoin call / Retry buttons call POST start; no effect, timer, poll result or reconnect does. No call control is focused automatically.
- Start call / Rejoin call / Retry and the in-call End call ignore presses for `ARM_DELAY_MS` (1 s) after they appear (`CallButton armDelayMs`, `useArmed`), so a click or Enter aimed at what was there a moment earlier cannot start or end the call. End call is set apart from Leave and control buttons have a fixed minimum width so a label change does not shift them.
- End call always asks `confirmAction(...)` first. Leave only POSTs leave, which never ends the call (also for doctors).
- Device check: each `getUserMedia` request times out after `DEVICE_TIMEOUT_MS` (8 s). A microphone that does not answer shows "Could not access the microphone" with "Check again" and "Join without microphone" (camera: "Could not access the camera", "Check again", "Join without camera"). A stream that arrives after its preview was stopped, released or timed out is stopped at once.
- The camera/microphone published on joining follow the device check (`cameraEnabled = camera on AND working`). The user's on/off choice, including in-call toggles, is kept in `sessionStorage` (`carenexus.call.devicePrefs`) and is the starting point of the next lobby visit in that tab. Proxy: show only when the dependent's `joinCallsAllowed` is true. Remove every `Linking.openURL(videoConsultationLink)`. Mobile components: replace Join with the text "Join video consultations from the CareNexus web app."

---

## 12. Acceptance checks per agent (run before reporting)

- permissions: compile; caregiver cannot set any communication flag true; FULL_ACCESS without approval does not grant MESSAGING/JOIN_CALLS; patient PATCH approves; revoke publishes the event; logout publishes the event; V15 applies on a DB with seed data (CHECKs satisfied).
- messaging-api: compile; admin 403; non-member 403; newly added member sees no earlier messages/previews/unread/notifications; duplicate `clientMessageId` returns the same message with 200; message + notifications in one transaction; revoked caregiver loses thread access immediately (request-time check) and membership is marked ACCESS_REVOKED by the listener.
- video-api: compile; VIDEO create -> PREPARING session; cancel/reschedule/type change produce the documented states and jobs; legacy VIDEO appointments are NEEDS_TIME_CONFIRMATION; token refused after revocation; webhook rejects bad signatures and ignores duplicates; `videoConsultationLink` always null in every response.
- messaging-web / call-web / permissions frontend: `npx tsc --noEmit` clean for your files; screens have loading/empty/error/offline states; no forbidden claims in copy.

Each agent's final report lists: files changed, any deviation from this contract (with reason), and anything left unverified.

---

## 13. Implementation notes (where the code differs from the text above)
These are what the code actually does. Where they conflict with an earlier section, they win.
- **Messaging, member errors:** the 400 `{name} cannot be added to this conversation` uses a name only for doctors (`Dr. First Last`). Anyone else, and any unknown id, gets `This person cannot be added to this conversation`, so a caller cannot find out the name behind a system id.
- **Messaging, `Thread.lastMessageAt`:** this is the `sentAt` of the newest message the caller can see, or null. The thread list is still ordered by `last_message_at`.
- **Messaging, `unreadMessageCount`:** counts the caller's unread, visible MESSAGE notifications.
- **Messaging, filtered thread list:** `GET /threads?patientSystemId=` for a patient the caller cannot act for returns an empty page, not 403.
- **Messaging, `DELETE /members/{systemId}`:** returns 204 when the person has already left or been removed, and 404 `Member not found` when they were never a member.
- **Messaging, extra error messages:** `Conversation type must be PRIVATE or CARE_TEAM`, `clientThreadId must be 8-64 letters, digits, '-' or '_'`, `Choose at least one person to add`, `archived must be true or false`, `lastReadMessageId is required`, `Request body is required`, and 403 `You cannot start a conversation about this patient`.
- **Calls, `POST /start`:** returns 409 `This call has closed` on a COMPLETED or NO_SHOW appointment.
- **Calls, `POST /join-request`:** a doctor other than the appointment's doctor gets 403 `You are not allowed to access this call`.
- **Calls, audit:** the CALL audits are recorded as CREATE (start, token) or UPDATE (end, admit, deny, remove, confirm-time), because `AuditService` only accepts READ/CREATE/UPDATE/DELETE.
- **Calls, provider not configured:** `DisabledCallProvider` is used instead, and its provider jobs fail without retrying (`LiveKit is not configured`), so the session moves to FAILED.
- **Calls, `CallAccessListener`:** the work runs in a `REQUIRES_NEW` `TransactionTemplate`, not under a method-level `@Transactional`. The behaviour is the same.
- **Frontend, `CallParticipant.removedReason`:** optional. The backend does not send it yet, and the UI does not rely on it.
- **Auth, `POST /api/auth/login`:** a wrong password and an unknown email both answer 401 `Invalid email or password` (throttling and audit unchanged; 429 when throttled). Missing email or password is 400 `Email and password are required`. An inactive account with the right password stays 400 (`Account is inactive`, or the clinician-pending message). The auth interceptor never refreshes on `/api/auth/*` 401s, so the login screen shows the server message.
- **Consultations (`/api/consultations/**`):** DRAFT and IN_PROGRESS consultations (unfinished notes) are visible to doctors and admins only. Patients and caregivers never get them in lists (`/patient/{id}`, `/appointment/{id}`, `/me/draft` and `/me/in-progress` are empty, caregiver activity feed), and `GET /{id}` of one is 404 `Consultation not found` for them. Every `ConsultationResponse` carries `appointmentType` (`IN_PERSON | VIDEO | PHONE`, the linked appointment's type).
- **Appointments, doctor reschedule:** checked, no backend change needed. The appointment's doctor may move it with `PATCH /api/appointments/{id}/reschedule` or `PATCH /api/doctors/appointments/{id}/mark-rescheduled` (body `{ "scheduledAt": "...", "durationMinutes": 30 }`); the missing piece is the web UI action.
- **Frontend dashboard role gate:** `src/app/dashboard/_layout.tsx` always renders the Stack and gates each screen (`screenLayout`): a screen of another role never mounts; the user is replaced to their own `ROLE_HOME`. The role homes are declared first so a navigator fallback never lands on `call/[appointmentId]`.
