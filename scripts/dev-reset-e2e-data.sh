#!/usr/bin/env bash
# Delete end-to-end test data from the LOCAL development database (DEV ONLY).
#
#   scripts/dev-reset-e2e-data.sh            # delete e2e data, print what was removed
#   scripts/dev-reset-e2e-data.sh --dry-run  # print what would be removed, change nothing
#
# The Playwright suites share the dev database with the demo (frontend/e2e). This removes, in one
# transaction and in foreign-key order, only rows the tests and smoke scripts create:
#   - appointments whose reason starts with "E2E"/"e2e-" (E2E video call test, e2e-smoke-…,
#     e2e-fix-…, e2e-hands-on…), "Demo video consultation" or is exactly "Smoke test", with their
#     consultations, call sessions, participants, call jobs and LiveKit provider events
#   - conversations and messages tagged with an e2e run tag ("e2e-…" in the subject or body, or an
#     "e2e-" client id), the ad-hoc smoke/audit script messages, their notifications and members,
#     and conversations left empty by that
#   - throwaway accounts e2e-…@carenexus.test (smoke and fix suites) with their grants, dependents,
#     profiles, settings, refresh tokens and audit rows
# Seed users and seed data are never touched. Running it again deletes nothing more.
#
# Target: the local docker container carenexus-postgres (default), or a Postgres on localhost
# through the host psql when E2E_PG_HOST is set (localhost, 127.0.0.1 or ::1 only). It refuses any
# other target and any database without the dev seed in its Flyway history.
#   E2E_PG_USER (carenexus), E2E_PG_DB (carenexus_db), E2E_PG_PORT (5432, host mode only)
#
# Exit codes: 0 done, 1 the delete failed (rolled back, nothing changed), 2 refused (not a local
# dev database), 3 database not reachable.
set -euo pipefail

DRY_RUN=0
case "${1:-}" in
  "") ;;
  --dry-run) DRY_RUN=1 ;;
  -h|--help) sed -n '2,/^set -e/p' "$0" | sed '$d'; exit 0 ;;
  *) echo "Unknown option: $1 (use --dry-run)" >&2; exit 2 ;;
esac

CONTAINER=carenexus-postgres
PG_USER="${E2E_PG_USER:-carenexus}"
PG_DB="${E2E_PG_DB:-carenexus_db}"

refuse() { echo "dev-reset-e2e-data: refusing: $*" >&2; exit 2; }
unreachable() { echo "dev-reset-e2e-data: database not reachable: $*" >&2; exit 3; }

if [[ -n "${E2E_PG_HOST:-}" ]]; then
  case "$E2E_PG_HOST" in
    localhost|127.0.0.1|::1) ;;
    *) refuse "E2E_PG_HOST=$E2E_PG_HOST is not localhost" ;;
  esac
  command -v psql >/dev/null 2>&1 || unreachable "psql is not installed (needed with E2E_PG_HOST)"
  PSQL=(psql -X -q -h "$E2E_PG_HOST" -p "${E2E_PG_PORT:-5432}" -U "$PG_USER" -d "$PG_DB")
  TARGET="$PG_USER@$E2E_PG_HOST:${E2E_PG_PORT:-5432}/$PG_DB"
else
  command -v docker >/dev/null 2>&1 || unreachable "docker is not installed"
  # Docker Desktop on macOS listens on a per-user socket; Linux uses the default /var/run one.
  if [[ -z "${DOCKER_HOST:-}" && -S "$HOME/.docker/run/docker.sock" ]]; then
    export DOCKER_HOST="unix://$HOME/.docker/run/docker.sock"
  fi
  # Only a local engine: a tcp:// or ssh:// DOCKER_HOST (or docker context) could be a shared host.
  ENGINE="${DOCKER_HOST:-$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null || echo unix:///var/run/docker.sock)}"
  [[ "$ENGINE" == unix://* || "$ENGINE" == npipe://* ]] || refuse "docker engine $ENGINE is not local"
  RUNNING="$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" || unreachable "container $CONTAINER not found (is docker running?)"
  [[ "$RUNNING" == "true" ]] || unreachable "container $CONTAINER is not running"
  PSQL=(docker exec -i "$CONTAINER" psql -X -q -U "$PG_USER" -d "$PG_DB")
  TARGET="docker container $CONTAINER ($PG_DB)"
fi

# Local dev databases only: the demo seed must be in the Flyway history (checked again in the SQL).
if ! SEEDED="$("${PSQL[@]}" -At -c "SELECT count(*) FROM flyway_schema_history WHERE script = 'V2__insert_test_users.sql' AND success" 2>&1)"; then
  [[ "$SEEDED" == *"does not exist"* ]] && refuse "$TARGET has no Flyway history, not a CareNexus dev database"
  unreachable "$TARGET: ${SEEDED##*$'\n'}"
fi
[[ "$SEEDED" == "1" ]] || refuse "$TARGET has no dev seed (V2__insert_test_users.sql) in its Flyway history"

if [[ "$DRY_RUN" == 1 ]]; then FINISH=ROLLBACK; VERB="would delete"; MODE="dry run"; else FINISH=COMMIT; VERB="deleted"; MODE="deleting"; fi
echo "dev-reset-e2e-data: $TARGET ($MODE)"

set +e
"${PSQL[@]}" -v ON_ERROR_STOP=1 <<SQL
\pset footer off
BEGIN;
SET LOCAL lock_timeout = '10s';

-- Local dev databases only: the demo seed must be in the Flyway history.
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE script = 'V2__insert_test_users.sql' AND success) THEN
    RAISE EXCEPTION 'refusing: no dev seed (V2__insert_test_users.sql) in flyway_schema_history, not a local dev database';
  END IF;
END \$\$;

CREATE TEMP TABLE e2e_counts (step serial, what text, n bigint) ON COMMIT DROP;

-- ── What is test data ───────────────────────────────────────────────────────

-- Throwaway accounts registered by the smoke and fix suites (.test is a reserved domain).
CREATE TEMP TABLE e2e_users ON COMMIT DROP AS
  SELECT user_id, system_id FROM users WHERE email ~* '^e2e-[a-z0-9-]+@carenexus\.test\$';

CREATE TEMP TABLE e2e_appts ON COMMIT DROP AS
  SELECT appointment_id FROM appointments
   WHERE reason_for_visit ~* '^e2e'
      OR reason_for_visit LIKE 'Demo video consultation%'
      OR reason_for_visit = 'Smoke test'
      OR patient_system_id IN (SELECT system_id FROM e2e_users)
      OR doctor_system_id  IN (SELECT system_id FROM e2e_users);

CREATE TEMP TABLE e2e_sessions ON COMMIT DROP AS
  SELECT call_session_id, room_name FROM call_sessions WHERE appointment_id IN (SELECT appointment_id FROM e2e_appts);

-- Run tags (env.ts runTag, smoke.ts smokeTag) look like "e2e-<base36>-<xxxx>" / "e2e-smoke-…", and
-- api.ts clientId() is "e2e-<uuid>". The last line covers the ad-hoc smoke and audit scripts.
CREATE TEMP TABLE e2e_threads ON COMMIT DROP AS
  SELECT thread_id FROM message_threads
   WHERE subject ~* '(^|[^a-z0-9])e2e-'
      OR client_thread_id ~* '^(e2e-|smoke-)'
      OR subject = 'Smoke test'
      OR patient_system_id    IN (SELECT system_id FROM e2e_users)
      OR created_by_system_id IN (SELECT system_id FROM e2e_users);

CREATE TEMP TABLE e2e_msgs ON COMMIT DROP AS
  SELECT message_id, thread_id FROM messages
   WHERE thread_id IN (SELECT thread_id FROM e2e_threads)
      OR body ~* '(^|[^a-z0-9])e2e-'
      OR client_message_id ~* '^e2e-'
      OR body ~ '^(\[audit\] |Smoke test message\$|Keyboard spot check from )'
      OR sender_system_id    IN (SELECT system_id FROM e2e_users)
      OR recipient_system_id IN (SELECT system_id FROM e2e_users)
      OR patient_system_id   IN (SELECT system_id FROM e2e_users);

-- Conversations that only ever held test messages (for example the patient-doctor private thread
-- the messaging specs reuse) go too; conversations with any other message are kept.
INSERT INTO e2e_threads
  SELECT DISTINCT m.thread_id FROM e2e_msgs m
   WHERE m.thread_id IS NOT NULL
     AND m.thread_id NOT IN (SELECT thread_id FROM e2e_threads)
     AND NOT EXISTS (SELECT 1 FROM messages x WHERE x.thread_id = m.thread_id
                                                AND x.message_id NOT IN (SELECT message_id FROM e2e_msgs));

-- ── Appointments and calls ──────────────────────────────────────────────────

WITH d AS (DELETE FROM provider_events WHERE room_name IN (SELECT room_name FROM e2e_sessions WHERE room_name IS NOT NULL) RETURNING 1)
INSERT INTO e2e_counts (what, n) SELECT 'provider events', count(*) FROM d;

-- call_jobs and call_participants cascade from call_sessions; counted here so the output shows them.
INSERT INTO e2e_counts (what, n)
  SELECT 'call jobs', count(*) FROM call_jobs WHERE call_session_id IN (SELECT call_session_id FROM e2e_sessions);
INSERT INTO e2e_counts (what, n)
  SELECT 'call participants', count(*) FROM call_participants WHERE call_session_id IN (SELECT call_session_id FROM e2e_sessions);
WITH d AS (DELETE FROM call_sessions WHERE call_session_id IN (SELECT call_session_id FROM e2e_sessions) RETURNING 1)
INSERT INTO e2e_counts (what, n) SELECT 'call sessions', count(*) FROM d;

WITH d AS (DELETE FROM consultations WHERE appointment_id IN (SELECT appointment_id FROM e2e_appts) RETURNING 1)
INSERT INTO e2e_counts (what, n) SELECT 'consultations', count(*) FROM d;
WITH d AS (DELETE FROM appointments WHERE appointment_id IN (SELECT appointment_id FROM e2e_appts) RETURNING 1)
INSERT INTO e2e_counts (what, n) SELECT 'appointments', count(*) FROM d;

-- ── Messaging ───────────────────────────────────────────────────────────────

WITH d AS (DELETE FROM notifications
            WHERE message_id IN (SELECT message_id FROM e2e_msgs)
               OR thread_id  IN (SELECT thread_id FROM e2e_threads)
               OR system_id IN (SELECT system_id FROM e2e_users)
               OR patient_system_id IN (SELECT system_id FROM e2e_users) RETURNING 1)
INSERT INTO e2e_counts (what, n) SELECT 'notifications', count(*) FROM d;
WITH d AS (DELETE FROM messages WHERE message_id IN (SELECT message_id FROM e2e_msgs) RETURNING 1)
INSERT INTO e2e_counts (what, n) SELECT 'messages', count(*) FROM d;
WITH d AS (DELETE FROM thread_members WHERE thread_id IN (SELECT thread_id FROM e2e_threads) RETURNING 1)
INSERT INTO e2e_counts (what, n) SELECT 'thread members', count(*) FROM d;
WITH d AS (DELETE FROM message_threads WHERE thread_id IN (SELECT thread_id FROM e2e_threads) RETURNING 1)
INSERT INTO e2e_counts (what, n) SELECT 'threads', count(*) FROM d;

-- Kept conversations that lost test messages point at their newest remaining message again.
UPDATE message_threads t
   SET last_message_id = r.max_id, last_message_at = r.max_at
  FROM (SELECT thread_id, MAX(message_id) AS max_id, MAX(sent_at) AS max_at FROM messages
         WHERE thread_id IN (SELECT DISTINCT thread_id FROM e2e_msgs) GROUP BY thread_id) r
 WHERE t.thread_id = r.thread_id;

-- ── Throwaway accounts ──────────────────────────────────────────────────────

-- Grants to or from them (for example Thomas's grants to smoke caregivers) and dependents they
-- created; the rest cascades from users (patients, caregivers, settings, refresh tokens, members).
WITH d AS (DELETE FROM dependent_access
            WHERE caregiver_system_id IN (SELECT system_id FROM e2e_users)
               OR patient_system_id   IN (SELECT system_id FROM e2e_users) RETURNING 1)
INSERT INTO e2e_counts (what, n) SELECT 'caregiver grants', count(*) FROM d;
WITH d AS (DELETE FROM refresh_tokens WHERE system_id IN (SELECT system_id FROM e2e_users) RETURNING 1)
INSERT INTO e2e_counts (what, n) SELECT 'refresh tokens', count(*) FROM d;
WITH d AS (DELETE FROM audit_log
            WHERE system_id IN (SELECT system_id FROM e2e_users)
               OR patient_system_id IN (SELECT system_id FROM e2e_users) RETURNING 1)
INSERT INTO e2e_counts (what, n) SELECT 'audit rows', count(*) FROM d;

-- Rows that would block the user delete (ON DELETE RESTRICT). The suites do not create these
-- for throwaway accounts today; they are listed so a new test cannot make the reset fail.
DELETE FROM permissions WHERE granted_by IN (SELECT user_id FROM e2e_users);
DELETE FROM consent_records WHERE patient_id IN (SELECT patient_id FROM patients WHERE system_id IN (SELECT system_id FROM e2e_users));
DELETE FROM care_plans       WHERE patient_system_id IN (SELECT system_id FROM e2e_users);
DELETE FROM documents        WHERE patient_system_id IN (SELECT system_id FROM e2e_users) OR uploaded_by_system_id IN (SELECT system_id FROM e2e_users);
DELETE FROM referrals        WHERE patient_system_id IN (SELECT system_id FROM e2e_users) OR referring_doctor_system_id IN (SELECT system_id FROM e2e_users);
DELETE FROM prescriptions    WHERE patient_system_id IN (SELECT system_id FROM e2e_users);
DELETE FROM lab_results      WHERE patient_system_id IN (SELECT system_id FROM e2e_users);
DELETE FROM imaging_results  WHERE patient_system_id IN (SELECT system_id FROM e2e_users);
DELETE FROM medical_records  WHERE patient_system_id IN (SELECT system_id FROM e2e_users);

WITH d AS (DELETE FROM users WHERE user_id IN (SELECT user_id FROM e2e_users) RETURNING 1)
INSERT INTO e2e_counts (what, n) SELECT 'throwaway users', count(*) FROM d;

\pset tuples_only on
\pset format unaligned
\pset fieldsep ' '
SELECT lpad(n::text, 6) || '  ' || what FROM e2e_counts ORDER BY step;
$FINISH;
SQL
STATUS=$?
set -e

if [[ $STATUS -ne 0 ]]; then
  echo "dev-reset-e2e-data: failed (psql exit $STATUS); the transaction was rolled back, nothing changed" >&2
  # psql exits 2 when the connection is lost; anything else is an SQL error.
  [[ $STATUS -eq 2 ]] && exit 3
  exit 1
fi
echo "dev-reset-e2e-data: done ($VERB, seed data untouched)"
