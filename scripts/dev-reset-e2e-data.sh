#!/usr/bin/env bash
# Delete end-to-end test data from the LOCAL development database (DEV ONLY).
#
#   scripts/dev-reset-e2e-data.sh            # delete e2e data, print what was removed
#   scripts/dev-reset-e2e-data.sh --dry-run  # print what would be removed, change nothing
#
# The Playwright suites share the dev database with the demo (frontend/e2e). This removes, in one
# transaction and in foreign-key order, only rows the tests create. Test data is recognised by markers only
# (CONTRACTS section 13):
#   - throwaway accounts e2e-…@carenexus.test, managed dependents created by them or named "e2e…", and
#     every row that belongs to those accounts;
#   - free text that starts with "e2e"/"E2E" (env.ts runTag(), smoke.ts smokeTag(), api.ts clientId()):
#     appointment reasons and notes (plus "Demo video consultation…" and "Smoke test"), consultation notes,
#     prescription names and instructions, lab, imaging and record texts, document titles, referral reasons,
#     care plan titles, reminder titles, health reading and symptom notes, invoice notes and line items,
#     claim and payment-method labels, correction texts, access-request messages, time-off reasons,
#     care-team provider names, facility names, emergency contact names, legal document versions,
#     system setting values, idempotency keys and push tokens, conversation subjects and message bodies;
#   - everything that hangs off a marked row: consultations of e2e appointments, records, prescriptions,
#     results, care plans (goals, interventions), referrals (and the SPECIALIST care-team rows their acceptance
#     added), invoices, payments, payment events and claims of e2e visits, medication plans and doses,
#     detections and AI alerts raised from e2e readings or results, reminders, correction requests,
#     documents linked to e2e rows (and their stored files), notifications about any of them and the
#     outbox messages fanned out from those notifications.
# Seed users and seed data are never touched (reference tables such as billing_fee_schedule and the seeded
# legal_documents versions included). Running it again deletes nothing more. Tables that do not exist yet in
# the target database (an older schema) are skipped.
#
# Stored files of deleted documents and data exports (FileStorage keys documents/… and exports/…) are removed
# after the commit: from E2E_STORAGE_DIR when it is set (an API running on this machine with
# STORAGE_LOCAL_DIR), otherwise from inside the local API container (E2E_API_CONTAINER, default
# carenexus-api) when it uses the local storage provider. Without either, the files stay (harmless).
#
# Target: the local docker container carenexus-postgres (default), or a Postgres on localhost through the host
# psql when E2E_PG_HOST is set (localhost, 127.0.0.1 or ::1 only). It refuses any other target and any database
# without the dev seed in its Flyway history.
#   E2E_PG_USER (carenexus), E2E_PG_DB (carenexus_db), E2E_PG_PORT (5432, host mode only)
#
# Exit codes: 0 done, 1 the delete failed (rolled back, nothing changed), 2 refused (not a local dev database),
# 3 database not reachable.
set -euo pipefail

DRY_RUN=0
case "${1:-}" in
  "") ;;
  --dry-run) DRY_RUN=1 ;;
  -h|--help) sed -n '2,/^set -e/p' "$0" | sed '$d'; exit 0 ;;
  *) echo "Unknown option: $1 (use --dry-run)" >&2; exit 2 ;;
esac

CONTAINER=carenexus-postgres
API_CONTAINER="${E2E_API_CONTAINER:-carenexus-api}"
PG_USER="${E2E_PG_USER:-carenexus}"
PG_DB="${E2E_PG_DB:-carenexus_db}"

refuse() { echo "dev-reset-e2e-data: refusing: $*" >&2; exit 2; }
unreachable() { echo "dev-reset-e2e-data: database not reachable: $*" >&2; exit 3; }

DOCKER_MODE=0
if [[ -n "${E2E_PG_HOST:-}" ]]; then
  case "$E2E_PG_HOST" in
    localhost|127.0.0.1|::1) ;;
    *) refuse "E2E_PG_HOST=$E2E_PG_HOST is not localhost" ;;
  esac
  command -v psql >/dev/null 2>&1 || unreachable "psql is not installed (needed with E2E_PG_HOST)"
  PSQL=(psql -X -q -h "$E2E_PG_HOST" -p "${E2E_PG_PORT:-5432}" -U "$PG_USER" -d "$PG_DB")
  TARGET="$PG_USER@$E2E_PG_HOST:${E2E_PG_PORT:-5432}/$PG_DB"
else
  DOCKER_MODE=1
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

# The SQL is a quoted heredoc (no shell expansion inside); the only input is the psql variable `finish`. It sits
# in a function because bash 3.2 (macOS) misparses a heredoc written directly inside $( ... ).
run_sql() {
"${PSQL[@]}" -v ON_ERROR_STOP=1 -v finish="$FINISH" <<'SQL'
\pset footer off
BEGIN;
SET LOCAL lock_timeout = '10s';

-- Local dev databases only: the demo seed must be in the Flyway history.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE script = 'V2__insert_test_users.sql' AND success) THEN
    RAISE EXCEPTION 'refusing: no dev seed (V2__insert_test_users.sql) in flyway_schema_history, not a local dev database';
  END IF;
END $$;

CREATE TEMP TABLE e2e_counts (step serial, what text, n bigint) ON COMMIT DROP;
-- Ids of marked rows per kind ('consultation', 'invoice', ...), and the stored files to remove after the commit.
CREATE TEMP TABLE e2e_ids (kind text NOT NULL, id bigint NOT NULL, PRIMARY KEY (kind, id)) ON COMMIT DROP;
CREATE TEMP TABLE e2e_files (storage_key text PRIMARY KEY) ON COMMIT DROP;
-- The upper-case type names other tables use to point at a row of a kind (notifications.resource_type,
-- documents.link_type, reminders.source_type, ai_alerts.source_type, record_correction_requests.resource_type).
CREATE TEMP TABLE e2e_kind_types (kind text NOT NULL, type_name text NOT NULL) ON COMMIT DROP;
INSERT INTO e2e_kind_types (kind, type_name) VALUES
  ('appointment', 'APPOINTMENT'), ('consultation', 'CONSULTATION'), ('prescription', 'PRESCRIPTION'),
  ('lab', 'LAB_RESULT'), ('imaging', 'IMAGING_RESULT'), ('record', 'MEDICAL_RECORD'), ('document', 'DOCUMENT'),
  ('care_plan', 'CARE_PLAN'), ('referral', 'REFERRAL'), ('metric', 'HEALTH_METRIC'), ('symptom', 'SYMPTOM_LOG'),
  ('detection', 'DISEASE_DETECTION'), ('ai_alert', 'AI_ALERT'), ('invoice', 'INVOICE'), ('payment', 'PAYMENT'),
  ('claim', 'INSURANCE_CLAIM'), ('payment_method', 'PAYMENT_METHOD'), ('reminder', 'REMINDER'),
  ('correction', 'CORRECTION_REQUEST'), ('care_team', 'CARE_TEAM'), ('access_request', 'ACCESS_REQUEST'),
  ('grant', 'DEPENDENT_ACCESS'), ('facility', 'FACILITY'), ('thread', 'SUPPORT_THREAD'), ('thread', 'MESSAGE_THREAD'),
  ('dose', 'MEDICATION_SCHEDULE');

-- ── Helpers (session-local; they work on whatever schema version the database is at) ──────────────────────

-- Whether a table (and optionally one of its columns) exists.
CREATE FUNCTION pg_temp.e2e_has(tbl text, col text DEFAULT NULL) RETURNS boolean LANGUAGE sql STABLE AS $f$
  SELECT CASE
    WHEN to_regclass(tbl) IS NULL THEN false
    WHEN col IS NULL THEN true
    ELSE EXISTS (SELECT 1 FROM pg_attribute
                  WHERE attrelid = to_regclass(tbl) AND attname = col AND attnum > 0 AND NOT attisdropped)
  END
$f$;

-- OR of the conditions whose columns exist in `tbl`. Each part is '<col>[,<col>…] => <condition>'; a part
-- whose listed columns are missing is left out. NULL when the table is missing or no part applies.
CREATE FUNCTION pg_temp.e2e_where(tbl text, parts text[]) RETURNS text LANGUAGE plpgsql STABLE AS $f$
DECLARE
  p text; cols text; cond text; c text; ok boolean;
  picked text[] := '{}';
BEGIN
  IF NOT pg_temp.e2e_has(tbl) THEN RETURN NULL; END IF;
  FOREACH p IN ARRAY parts LOOP
    cols := split_part(p, ' => ', 1);
    cond := substr(p, length(cols) + 5);
    ok := true;
    FOREACH c IN ARRAY string_to_array(cols, ',') LOOP
      IF trim(c) <> '' AND NOT pg_temp.e2e_has(tbl, trim(c)) THEN ok := false; END IF;
    END LOOP;
    IF ok THEN picked := picked || ('(' || cond || ')'); END IF;
  END LOOP;
  IF cardinality(picked) = 0 THEN RETURN NULL; END IF;
  RETURN array_to_string(picked, ' OR ');
END
$f$;

-- Records the ids of matching rows as `kind`.
CREATE FUNCTION pg_temp.e2e_mark(kind text, tbl text, idcol text, VARIADIC parts text[]) RETURNS void LANGUAGE plpgsql AS $f$
DECLARE w text := pg_temp.e2e_where(tbl, parts);
BEGIN
  IF w IS NULL OR NOT pg_temp.e2e_has(tbl, idcol) THEN RETURN; END IF;
  EXECUTE format('INSERT INTO e2e_ids (kind, id) SELECT %L, %I FROM %I WHERE %s ON CONFLICT DO NOTHING', kind, idcol, tbl, w);
END
$f$;

-- Deletes matching rows and records how many under `what` (nothing is recorded for a missing table).
CREATE FUNCTION pg_temp.e2e_delete(what text, tbl text, VARIADIC parts text[]) RETURNS void LANGUAGE plpgsql AS $f$
DECLARE w text := pg_temp.e2e_where(tbl, parts); n bigint := 0;
BEGIN
  IF NOT pg_temp.e2e_has(tbl) THEN RETURN; END IF;
  IF w IS NOT NULL THEN
    EXECUTE format('WITH d AS (DELETE FROM %I WHERE %s RETURNING 1) SELECT count(*) FROM d', tbl, w) INTO n;
  END IF;
  INSERT INTO e2e_counts (what, n) VALUES (what, n);
END
$f$;

-- Applies `assignment` to matching rows and records how many under `what`.
CREATE FUNCTION pg_temp.e2e_update(what text, tbl text, assignment text, VARIADIC parts text[]) RETURNS void LANGUAGE plpgsql AS $f$
DECLARE w text := pg_temp.e2e_where(tbl, parts); n bigint := 0;
BEGIN
  IF NOT pg_temp.e2e_has(tbl) THEN RETURN; END IF;
  IF w IS NOT NULL THEN
    EXECUTE format('WITH u AS (UPDATE %I SET %s WHERE %s RETURNING 1) SELECT count(*) FROM u', tbl, assignment, w) INTO n;
  END IF;
  INSERT INTO e2e_counts (what, n) VALUES (what, n);
END
$f$;

-- ── What is test data: accounts ─────────────────────────────────────────────────────────────────────────

-- Throwaway accounts registered by the suites (.test is a reserved domain).
CREATE TEMP TABLE e2e_users ON COMMIT DROP AS
  SELECT user_id, system_id FROM users WHERE email ~* '^e2e-[a-z0-9._+-]+@carenexus\.test$';

-- Managed dependents (no email, no password) whose name carries the marker, or whose only caregivers are
-- throwaway accounts (they were created through "Add a loved one" by an e2e caregiver).
INSERT INTO e2e_users (user_id, system_id)
  SELECT u.user_id, u.system_id FROM users u
   WHERE u.role = 'PATIENT' AND u.email IS NULL AND u.password_hash IS NULL
     AND NOT EXISTS (SELECT 1 FROM e2e_users e WHERE e.user_id = u.user_id)
     AND (u.first_name ~* '^e2e' OR u.last_name ~* '^e2e'
          OR (EXISTS (SELECT 1 FROM dependent_access g
                       WHERE g.patient_system_id = u.system_id
                         AND g.caregiver_system_id IN (SELECT system_id FROM e2e_users))
              AND NOT EXISTS (SELECT 1 FROM dependent_access g
                               WHERE g.patient_system_id = u.system_id
                                 AND NOT EXISTS (SELECT 1 FROM e2e_users e WHERE e.system_id = g.caregiver_system_id))));

-- ── What is test data: appointments, calls and conversations ────────────────────────────────────────────

CREATE TEMP TABLE e2e_appts ON COMMIT DROP AS
  SELECT appointment_id FROM appointments
   WHERE reason_for_visit ~* '^e2e'
      OR notes ~* '^e2e'
      OR reason_for_visit LIKE 'Demo video consultation%'
      OR reason_for_visit = 'Smoke test'
      OR patient_system_id IN (SELECT system_id FROM e2e_users)
      OR doctor_system_id  IN (SELECT system_id FROM e2e_users);
INSERT INTO e2e_ids (kind, id) SELECT 'appointment', appointment_id FROM e2e_appts;

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
      OR body ~ '^(\[audit\] |Smoke test message$|Keyboard spot check from )'
      OR sender_system_id    IN (SELECT system_id FROM e2e_users)
      OR recipient_system_id IN (SELECT system_id FROM e2e_users)
      OR patient_system_id   IN (SELECT system_id FROM e2e_users);

-- Conversations that only ever held test messages (for example the patient-doctor private thread the
-- messaging specs reuse, or a demo user's support conversation used by a spec) go too; conversations with
-- any other message are kept.
INSERT INTO e2e_threads
  SELECT DISTINCT m.thread_id FROM e2e_msgs m
   WHERE m.thread_id IS NOT NULL
     AND m.thread_id NOT IN (SELECT thread_id FROM e2e_threads)
     AND NOT EXISTS (SELECT 1 FROM messages x WHERE x.thread_id = m.thread_id
                                                AND x.message_id NOT IN (SELECT message_id FROM e2e_msgs));
INSERT INTO e2e_ids (kind, id) SELECT 'thread', thread_id FROM e2e_threads;

-- ── What is test data: clinical, billing and account rows (marked in dependency order) ──────────────────

DO $$
DECLARE
  -- Shorthands used inside the condition strings below.
  eu  CONSTANT text := '(SELECT system_id FROM e2e_users)';
  ep  CONSTANT text := '(SELECT p.patient_id FROM patients p WHERE p.system_id IN (SELECT system_id FROM e2e_users))';
  refs CONSTANT text := '(SELECT t.type_name, i.id FROM e2e_ids i JOIN e2e_kind_types t ON t.kind = i.kind)';
BEGIN
  PERFORM pg_temp.e2e_mark('consultation', 'consultations', 'consultation_id',
    'appointment_id => appointment_id IN (SELECT appointment_id FROM e2e_appts)',
    'patient_system_id => patient_system_id IN ' || eu,
    'doctor_system_id => doctor_system_id IN ' || eu,
    $p$chief_complaint => chief_complaint ~* '^e2e'$p$,
    $p$clinical_notes => clinical_notes ~* '^e2e'$p$);

  PERFORM pg_temp.e2e_mark('prescription', 'prescriptions', 'prescription_id',
    'patient_system_id => patient_system_id IN ' || eu,
    'doctor_system_id => doctor_system_id IN ' || eu,
    $p$consultation_id => consultation_id IN (SELECT id FROM e2e_ids WHERE kind = 'consultation')$p$,
    $p$medication_name => medication_name ~* '^e2e'$p$,
    $p$generic_name => generic_name ~* '^e2e'$p$,
    $p$special_instructions => special_instructions ~* '^e2e'$p$);

  PERFORM pg_temp.e2e_mark('dose', 'medication_schedules', 'schedule_id',
    $p$prescription_id => prescription_id IN (SELECT id FROM e2e_ids WHERE kind = 'prescription')$p$,
    'patient_system_id => patient_system_id IN ' || eu,
    'patient_id => patient_id IN ' || ep,
    $p$notes => notes ~* '^e2e'$p$);

  PERFORM pg_temp.e2e_mark('lab', 'lab_results', 'result_id',
    'patient_system_id => patient_system_id IN ' || eu,
    'doctor_system_id => doctor_system_id IN ' || eu,
    $p$consultation_id => consultation_id IN (SELECT id FROM e2e_ids WHERE kind = 'consultation')$p$,
    $p$test_name => test_name ~* '^e2e'$p$,
    $p$review_notes => review_notes ~* '^e2e'$p$);

  PERFORM pg_temp.e2e_mark('imaging', 'imaging_results', 'imaging_id',
    'patient_system_id => patient_system_id IN ' || eu,
    'doctor_system_id => doctor_system_id IN ' || eu,
    $p$consultation_id => consultation_id IN (SELECT id FROM e2e_ids WHERE kind = 'consultation')$p$,
    $p$body_part => body_part ~* '^e2e'$p$,
    $p$findings => findings ~* '^e2e'$p$,
    $p$impression => impression ~* '^e2e'$p$,
    $p$radiologist_report => radiologist_report ~* '^e2e'$p$,
    $p$review_notes => review_notes ~* '^e2e'$p$);

  PERFORM pg_temp.e2e_mark('record', 'medical_records', 'record_id',
    'patient_system_id => patient_system_id IN ' || eu,
    'doctor_system_id => doctor_system_id IN ' || eu,
    $p$consultation_id => consultation_id IN (SELECT id FROM e2e_ids WHERE kind = 'consultation')$p$,
    $p$title => title ~* '^e2e'$p$,
    $p$description => description ~* '^e2e'$p$);

  PERFORM pg_temp.e2e_mark('care_plan', 'care_plans', 'plan_id',
    'patient_system_id => patient_system_id IN ' || eu,
    'doctor_system_id => doctor_system_id IN ' || eu,
    $p$consultation_id => consultation_id IN (SELECT id FROM e2e_ids WHERE kind = 'consultation')$p$,
    $p$title => title ~* '^e2e'$p$,
    $p$description => description ~* '^e2e'$p$);

  PERFORM pg_temp.e2e_mark('referral', 'referrals', 'referral_id',
    'patient_system_id => patient_system_id IN ' || eu,
    'referring_doctor_system_id => referring_doctor_system_id IN ' || eu,
    'specialist_system_id => specialist_system_id IN ' || eu,
    $p$consultation_id => consultation_id IN (SELECT id FROM e2e_ids WHERE kind = 'consultation')$p$,
    $p$reason => reason ~* '^e2e'$p$,
    $p$clinical_summary => clinical_summary ~* '^e2e'$p$);

  -- Care-team rows of throwaway accounts, external providers named "e2e…", and the SPECIALIST rows that
  -- accepting an e2e referral inserted (added after the referral was created). V10 renamed the key
  -- team_id -> care_team_id; the helpers skip a missing column silently, so the name must match.
  PERFORM pg_temp.e2e_mark('care_team', 'care_team', 'care_team_id',
    'patient_system_id => patient_system_id IN ' || eu,
    'provider_system_id => provider_system_id IN ' || eu,
    $p$provider_name => provider_name ~* '^e2e'$p$,
    $p$provider_organisation => provider_organisation ~* '^e2e'$p$,
    $p$role,patient_system_id,provider_system_id,assigned_at => role = 'SPECIALIST' AND EXISTS (
        SELECT 1 FROM referrals r JOIN e2e_ids i ON i.kind = 'referral' AND i.id = r.referral_id
         WHERE r.patient_system_id = care_team.patient_system_id
           AND r.specialist_system_id = care_team.provider_system_id
           AND care_team.assigned_at >= r.created_at)$p$);

  PERFORM pg_temp.e2e_mark('invoice', 'invoices', 'invoice_id',
    'patient_system_id => patient_system_id IN ' || eu,
    'doctor_system_id => doctor_system_id IN ' || eu,
    'created_by_system_id => created_by_system_id IN ' || eu,
    'patient_id => patient_id IN ' || ep,
    'appointment_id => appointment_id IN (SELECT appointment_id FROM e2e_appts)',
    $p$consultation_id => consultation_id IN (SELECT id FROM e2e_ids WHERE kind = 'consultation')$p$,
    $p$notes => notes ~* '^e2e'$p$,
    $p$line_items => line_items::text ~* '"description"\s*:\s*"e2e'$p$);

  PERFORM pg_temp.e2e_mark('payment_method', 'payment_methods', 'method_id',
    'system_id => system_id IN ' || eu,
    'user_id => user_id IN (SELECT user_id FROM e2e_users)',
    $p$display_label => display_label ~* '^e2e'$p$,
    $p$insurer_name => insurer_name ~* '^e2e'$p$,
    $p$policy_number => policy_number ~* '^e2e'$p$);

  PERFORM pg_temp.e2e_mark('payment', 'payments', 'payment_id',
    $p$invoice_id => invoice_id IN (SELECT id FROM e2e_ids WHERE kind = 'invoice')$p$,
    'appointment_id => appointment_id IN (SELECT appointment_id FROM e2e_appts)',
    'patient_system_id => patient_system_id IN ' || eu,
    'initiated_by_system_id => initiated_by_system_id IN ' || eu,
    'patient_id => patient_id IN ' || ep);

  PERFORM pg_temp.e2e_mark('claim', 'insurance_claims', 'claim_id',
    $p$invoice_id => invoice_id IN (SELECT id FROM e2e_ids WHERE kind = 'invoice')$p$,
    $p$payment_id => payment_id IN (SELECT id FROM e2e_ids WHERE kind = 'payment')$p$,
    'patient_system_id => patient_system_id IN ' || eu,
    'submitted_by_system_id => submitted_by_system_id IN ' || eu,
    'patient_id => patient_id IN ' || ep,
    $p$insurance_provider => insurance_provider ~* '^e2e'$p$,
    $p$policy_number => policy_number ~* '^e2e'$p$,
    $p$appeal_reason => appeal_reason ~* '^e2e'$p$);

  -- Health readings (a blood-pressure reading is two rows sharing reading_group_id: both go) and symptoms.
  PERFORM pg_temp.e2e_mark('metric', 'health_metrics', 'metric_id',
    'patient_system_id => patient_system_id IN ' || eu,
    'patient_id => patient_id IN ' || ep,
    'recorded_by_system_id => recorded_by_system_id IN ' || eu,
    $p$notes => notes ~* '^e2e'$p$);
  PERFORM pg_temp.e2e_mark('metric', 'health_metrics', 'metric_id',
    $p$reading_group_id => reading_group_id IN (
        SELECT m.reading_group_id FROM health_metrics m JOIN e2e_ids i ON i.kind = 'metric' AND i.id = m.metric_id
         WHERE m.reading_group_id IS NOT NULL)$p$);

  PERFORM pg_temp.e2e_mark('symptom', 'symptom_logs', 'symptom_id',
    'patient_system_id => patient_system_id IN ' || eu,
    'patient_id => patient_id IN ' || ep,
    'logged_by_system_id => logged_by_system_id IN ' || eu,
    $p$notes => notes ~* '^e2e'$p$);

  PERFORM pg_temp.e2e_mark('detection', 'disease_detection', 'detection_id',
    'patient_system_id => patient_system_id IN ' || eu,
    'doctor_system_id => doctor_system_id IN ' || eu,
    $p$symptom_id => symptom_id IN (SELECT id FROM e2e_ids WHERE kind = 'symptom')$p$,
    $p$source_type,source_id => (CASE source_type WHEN 'SYMPTOM_LOG' THEN 'symptom' WHEN 'HEALTH_METRIC' THEN 'metric' END, source_id::bigint)
        IN (SELECT kind, id FROM e2e_ids)$p$);

  PERFORM pg_temp.e2e_mark('ai_alert', 'ai_alerts', 'alert_id',
    'patient_system_id => patient_system_id IN ' || eu,
    $p$detection_id => detection_id IN (SELECT id FROM e2e_ids WHERE kind = 'detection')$p$,
    'source_type,source_id => (source_type, source_id::bigint) IN ' || refs);

  -- Documents of throwaway accounts, titled "e2e…", or linked to a marked row (by link or by a result's
  -- document_id).
  PERFORM pg_temp.e2e_mark('document', 'documents', 'document_id',
    'patient_system_id => patient_system_id IN ' || eu,
    'uploaded_by_system_id => uploaded_by_system_id IN ' || eu,
    $p$title => title ~* '^e2e'$p$,
    $p$description => description ~* '^e2e'$p$,
    'link_type,link_id => (link_type, link_id::bigint) IN ' || refs);
  IF pg_temp.e2e_has('lab_results', 'document_id') THEN
    INSERT INTO e2e_ids (kind, id)
      SELECT 'document', document_id FROM lab_results
       WHERE document_id IS NOT NULL AND result_id IN (SELECT id FROM e2e_ids WHERE kind = 'lab')
      ON CONFLICT DO NOTHING;
  END IF;
  IF pg_temp.e2e_has('imaging_results', 'document_id') THEN
    INSERT INTO e2e_ids (kind, id)
      SELECT 'document', document_id FROM imaging_results
       WHERE document_id IS NOT NULL AND imaging_id IN (SELECT id FROM e2e_ids WHERE kind = 'imaging')
      ON CONFLICT DO NOTHING;
  END IF;

  PERFORM pg_temp.e2e_mark('reminder', 'reminders', 'reminder_id',
    'patient_system_id => patient_system_id IN ' || eu,
    'recipient_system_id => recipient_system_id IN ' || eu,
    'created_by_system_id => created_by_system_id IN ' || eu,
    'patient_id => patient_id IN ' || ep,
    $p$title => title ~* '^e2e'$p$,
    $p$message => message ~* '^e2e'$p$,
    'source_type,source_id => (source_type, source_id::bigint) IN ' || refs);

  PERFORM pg_temp.e2e_mark('correction', 'record_correction_requests', 'request_id',
    'patient_system_id => patient_system_id IN ' || eu,
    'requested_by_system_id => requested_by_system_id IN ' || eu,
    'assigned_doctor_system_id => assigned_doctor_system_id IN ' || eu,
    'resolver_system_id => resolver_system_id IN ' || eu,
    $p$requested_text => requested_text ~* '^e2e'$p$,
    'resource_type,resource_id => (resource_type, resource_id::bigint) IN ' || refs);

  PERFORM pg_temp.e2e_mark('access_request', 'caregiver_access_requests', 'request_id',
    'caregiver_system_id => caregiver_system_id IN ' || eu,
    'patient_system_id => patient_system_id IN ' || eu,
    $p$patient_email => patient_email ~* '^e2e-[a-z0-9._+-]+@carenexus\.test$'$p$,
    $p$message => message ~* '^e2e'$p$);

  PERFORM pg_temp.e2e_mark('grant', 'dependent_access', 'access_id',
    'caregiver_system_id => caregiver_system_id IN ' || eu,
    'patient_system_id => patient_system_id IN ' || eu);

  PERFORM pg_temp.e2e_mark('facility', 'facilities', 'facility_id',
    $p$name => name ~* '^e2e'$p$);

  -- Notifications about any marked row, to or from throwaway accounts, or about e2e conversations.
  PERFORM pg_temp.e2e_mark('notification', 'notifications', 'notification_id',
    'message_id => message_id IN (SELECT message_id FROM e2e_msgs)',
    'thread_id => thread_id IN (SELECT thread_id FROM e2e_threads)',
    'system_id => system_id IN ' || eu,
    'patient_system_id => patient_system_id IN ' || eu,
    'actor_system_id => actor_system_id IN ' || eu,
    'resource_type,resource_id => (resource_type, resource_id::bigint) IN ' || refs);
END $$;

-- ── Appointments and calls ──────────────────────────────────────────────────────────────────────────────

WITH d AS (DELETE FROM provider_events WHERE room_name IN (SELECT room_name FROM e2e_sessions WHERE room_name IS NOT NULL) RETURNING 1)
INSERT INTO e2e_counts (what, n) SELECT 'provider events', count(*) FROM d;

-- call_jobs and call_participants cascade from call_sessions; counted here so the output shows them.
INSERT INTO e2e_counts (what, n)
  SELECT 'call jobs', count(*) FROM call_jobs WHERE call_session_id IN (SELECT call_session_id FROM e2e_sessions);
INSERT INTO e2e_counts (what, n)
  SELECT 'call participants', count(*) FROM call_participants WHERE call_session_id IN (SELECT call_session_id FROM e2e_sessions);
WITH d AS (DELETE FROM call_sessions WHERE call_session_id IN (SELECT call_session_id FROM e2e_sessions) RETURNING 1)
INSERT INTO e2e_counts (what, n) SELECT 'call sessions', count(*) FROM d;

-- ── Notifications and the outbox ────────────────────────────────────────────────────────────────────────

DO $$ BEGIN
  -- Emails, SMS and push messages to throwaway addresses or accounts, and the ones fanned out from deleted
  -- notifications (outbound_messages has no foreign keys).
  PERFORM pg_temp.e2e_delete('outbox messages', 'outbound_messages',
    $p$notification_id => notification_id IN (SELECT id FROM e2e_ids WHERE kind = 'notification')$p$,
    $p$recipient_address => recipient_address ~* '^e2e-[a-z0-9._+-]+@carenexus\.test$'$p$,
    'recipient_system_id => recipient_system_id IN (SELECT system_id FROM e2e_users)');
  PERFORM pg_temp.e2e_delete('notifications', 'notifications',
    $p$notification_id => notification_id IN (SELECT id FROM e2e_ids WHERE kind = 'notification')$p$);

-- ── AI, health tracking, reminders, corrections ─────────────────────────────────────────────────────────

  PERFORM pg_temp.e2e_delete('AI alerts', 'ai_alerts',
    $p$alert_id => alert_id IN (SELECT id FROM e2e_ids WHERE kind = 'ai_alert')$p$);
  PERFORM pg_temp.e2e_delete('disease detections', 'disease_detection',
    $p$detection_id => detection_id IN (SELECT id FROM e2e_ids WHERE kind = 'detection')$p$);
  PERFORM pg_temp.e2e_delete('health readings', 'health_metrics',
    $p$metric_id => metric_id IN (SELECT id FROM e2e_ids WHERE kind = 'metric')$p$);
  PERFORM pg_temp.e2e_delete('symptom logs', 'symptom_logs',
    $p$symptom_id => symptom_id IN (SELECT id FROM e2e_ids WHERE kind = 'symptom')$p$);
  PERFORM pg_temp.e2e_delete('reminders', 'reminders',
    $p$reminder_id => reminder_id IN (SELECT id FROM e2e_ids WHERE kind = 'reminder')$p$);
  PERFORM pg_temp.e2e_delete('correction requests', 'record_correction_requests',
    $p$request_id => request_id IN (SELECT id FROM e2e_ids WHERE kind = 'correction')$p$);

-- ── Documents (their stored files are removed after the commit) ─────────────────────────────────────────

  IF pg_temp.e2e_has('documents', 'storage_key') THEN
    INSERT INTO e2e_files (storage_key)
      SELECT storage_key FROM documents
       WHERE storage_key IS NOT NULL AND document_id IN (SELECT id FROM e2e_ids WHERE kind = 'document')
      ON CONFLICT DO NOTHING;
  END IF;
  PERFORM pg_temp.e2e_delete('documents', 'documents',
    $p$document_id => document_id IN (SELECT id FROM e2e_ids WHERE kind = 'document')$p$);

-- ── Billing (billing_fee_schedule is reference data and is never touched here) ──────────────────────────

  PERFORM pg_temp.e2e_delete('payment events', 'payment_events',
    $p$payment_id => payment_id IN (SELECT id FROM e2e_ids WHERE kind = 'payment')$p$);
  PERFORM pg_temp.e2e_delete('insurance claims', 'insurance_claims',
    $p$claim_id => claim_id IN (SELECT id FROM e2e_ids WHERE kind = 'claim')$p$);
  PERFORM pg_temp.e2e_delete('payments', 'payments',
    $p$payment_id => payment_id IN (SELECT id FROM e2e_ids WHERE kind = 'payment')$p$);
  PERFORM pg_temp.e2e_delete('invoices', 'invoices',
    $p$invoice_id => invoice_id IN (SELECT id FROM e2e_ids WHERE kind = 'invoice')$p$);
  PERFORM pg_temp.e2e_delete('payment methods', 'payment_methods',
    $p$method_id => method_id IN (SELECT id FROM e2e_ids WHERE kind = 'payment_method')$p$);

-- ── Care plans, care team, referrals ────────────────────────────────────────────────────────────────────

  PERFORM pg_temp.e2e_delete('care plan goals', 'care_plan_goals',
    $p$plan_id => plan_id IN (SELECT id FROM e2e_ids WHERE kind = 'care_plan')$p$);
  PERFORM pg_temp.e2e_delete('care plan interventions', 'care_plan_interventions',
    $p$plan_id => plan_id IN (SELECT id FROM e2e_ids WHERE kind = 'care_plan')$p$);
  PERFORM pg_temp.e2e_delete('care plans', 'care_plans',
    $p$plan_id => plan_id IN (SELECT id FROM e2e_ids WHERE kind = 'care_plan')$p$);
  PERFORM pg_temp.e2e_delete('care team rows', 'care_team',
    $p$care_team_id => care_team_id IN (SELECT id FROM e2e_ids WHERE kind = 'care_team')$p$);
  PERFORM pg_temp.e2e_delete('referrals', 'referrals',
    $p$referral_id => referral_id IN (SELECT id FROM e2e_ids WHERE kind = 'referral')$p$);

-- ── Medication, results, records, consultations ─────────────────────────────────────────────────────────

  PERFORM pg_temp.e2e_delete('medication schedule plans', 'medication_schedule_plans',
    $p$prescription_id => prescription_id IN (SELECT id FROM e2e_ids WHERE kind = 'prescription')$p$);
  PERFORM pg_temp.e2e_delete('medication doses', 'medication_schedules',
    $p$schedule_id => schedule_id IN (SELECT id FROM e2e_ids WHERE kind = 'dose')$p$);
  PERFORM pg_temp.e2e_delete('prescriptions', 'prescriptions',
    $p$prescription_id => prescription_id IN (SELECT id FROM e2e_ids WHERE kind = 'prescription')$p$);
  PERFORM pg_temp.e2e_delete('lab results', 'lab_results',
    $p$result_id => result_id IN (SELECT id FROM e2e_ids WHERE kind = 'lab')$p$);
  PERFORM pg_temp.e2e_delete('imaging results', 'imaging_results',
    $p$imaging_id => imaging_id IN (SELECT id FROM e2e_ids WHERE kind = 'imaging')$p$);
  PERFORM pg_temp.e2e_delete('medical records', 'medical_records',
    $p$record_id => record_id IN (SELECT id FROM e2e_ids WHERE kind = 'record')$p$);
  PERFORM pg_temp.e2e_delete('consultations', 'consultations',
    $p$consultation_id => consultation_id IN (SELECT id FROM e2e_ids WHERE kind = 'consultation')$p$);
END $$;

WITH d AS (DELETE FROM appointments WHERE appointment_id IN (SELECT appointment_id FROM e2e_appts) RETURNING 1)
INSERT INTO e2e_counts (what, n) SELECT 'appointments', count(*) FROM d;

-- ── Messaging (support conversations included) ──────────────────────────────────────────────────────────

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

-- ── Caregiver access, availability, account data, settings ──────────────────────────────────────────────

DO $$
DECLARE
  eu CONSTANT text := '(SELECT system_id FROM e2e_users)';
  ep CONSTANT text := '(SELECT p.patient_id FROM patients p WHERE p.system_id IN (SELECT system_id FROM e2e_users))';
BEGIN
  PERFORM pg_temp.e2e_delete('caregiver access requests', 'caregiver_access_requests',
    $p$request_id => request_id IN (SELECT id FROM e2e_ids WHERE kind = 'access_request')$p$);
  -- The consent ledger rows of throwaway accounts and of grants that involve one (grants of demo users to
  -- e2e caregivers included).
  PERFORM pg_temp.e2e_delete('consent records', 'consent_records',
    'patient_system_id => patient_system_id IN ' || eu,
    'caregiver_system_id => caregiver_system_id IN ' || eu,
    'doctor_system_id => doctor_system_id IN ' || eu,
    'granted_by_system_id => granted_by_system_id IN ' || eu,
    $p$access_id => access_id IN (SELECT id FROM e2e_ids WHERE kind = 'grant')$p$,
    'patient_id => patient_id IN ' || ep,
    'caregiver_id => caregiver_id IN (SELECT c.caregiver_id FROM caregivers c WHERE c.system_id IN (SELECT system_id FROM e2e_users))');
  -- Grants to or from throwaway accounts (for example Thomas's grants to smoke caregivers).
  PERFORM pg_temp.e2e_delete('caregiver grants', 'dependent_access',
    $p$access_id => access_id IN (SELECT id FROM e2e_ids WHERE kind = 'grant')$p$);

  PERFORM pg_temp.e2e_delete('doctor time off', 'doctor_time_off',
    'doctor_system_id => doctor_system_id IN ' || eu,
    $p$reason => reason ~* '^e2e'$p$);
  PERFORM pg_temp.e2e_delete('doctor availability blocks', 'doctor_availability',
    'doctor_system_id => doctor_system_id IN ' || eu);

  -- Account tokens, MFA and legal acceptance rows (all keyed by system_id).
  PERFORM pg_temp.e2e_delete('password reset tokens', 'password_reset_tokens',
    'system_id => system_id IN ' || eu,
    'requested_by_system_id => requested_by_system_id IN ' || eu);
  PERFORM pg_temp.e2e_delete('email verification tokens', 'email_verification_tokens',
    'system_id => system_id IN ' || eu);
  PERFORM pg_temp.e2e_delete('email change requests', 'email_change_requests',
    'system_id => system_id IN ' || eu,
    $p$new_email => new_email ~* '^e2e-[a-z0-9._+-]+@carenexus\.test$'$p$);
  PERFORM pg_temp.e2e_delete('MFA challenges', 'mfa_challenges', 'system_id => system_id IN ' || eu);
  PERFORM pg_temp.e2e_delete('MFA recovery codes', 'mfa_recovery_codes', 'system_id => system_id IN ' || eu);
  PERFORM pg_temp.e2e_delete('MFA enrolments', 'user_mfa', 'system_id => system_id IN ' || eu);
  -- Versions published by the legal spec are labelled "e2e…"; deleting them makes the seeded version current again.
  PERFORM pg_temp.e2e_delete('legal acceptances', 'legal_acceptances',
    'system_id => system_id IN ' || eu,
    $p$version => version ~* '^e2e'$p$);
  PERFORM pg_temp.e2e_delete('legal document versions', 'legal_documents',
    $p$version => version ~* '^e2e'$p$,
    $p$title => title ~* '^e2e'$p$);

  -- Data export, closure and deletion requests (no cascade from users); export files go after the commit.
  IF pg_temp.e2e_has('account_requests', 'result_ref') THEN
    INSERT INTO e2e_files (storage_key)
      SELECT result_ref FROM account_requests
       WHERE result_ref IS NOT NULL AND system_id IN (SELECT system_id FROM e2e_users)
      ON CONFLICT DO NOTHING;
  END IF;
  PERFORM pg_temp.e2e_delete('account requests', 'account_requests',
    'system_id => system_id IN ' || eu,
    'processed_by_system_id => processed_by_system_id IN ' || eu);

  PERFORM pg_temp.e2e_delete('push devices', 'push_devices',
    'system_id => system_id IN ' || eu,
    $p$token => token ~* '^e2e'$p$);
  PERFORM pg_temp.e2e_delete('idempotency keys', 'idempotency_keys',
    'owner_system_id => owner_system_id IN ' || eu,
    $p$idem_key => idem_key ~* '^e2e'$p$);

  -- Administrator overrides written by a throwaway administrator or holding a marker value; deleting an
  -- override restores the setting's default.
  PERFORM pg_temp.e2e_delete('system setting overrides', 'system_settings',
    'updated_by_system_id => updated_by_system_id IN ' || eu,
    $p$value => value ~* '^e2e' OR value ~* '@carenexus\.test$'$p$);

  PERFORM pg_temp.e2e_delete('facilities', 'facilities',
    $p$facility_id => facility_id IN (SELECT id FROM e2e_ids WHERE kind = 'facility')$p$);
  PERFORM pg_temp.e2e_delete('emergency contacts', 'emergency_contacts',
    'patient_system_id => patient_system_id IN ' || eu,
    $p$name => name ~* '^e2e'$p$);
  -- Free-text health status notes a spec wrote on a demo patient (the column belongs to the patient row).
  PERFORM pg_temp.e2e_update('health status notes cleared', 'patients',
    'health_status_note = NULL',
    $p$health_status_note => health_status_note ~* '^e2e'$p$);

  PERFORM pg_temp.e2e_delete('refresh tokens', 'refresh_tokens', 'system_id => system_id IN ' || eu);
  PERFORM pg_temp.e2e_delete('audit rows', 'audit_log',
    'system_id => system_id IN ' || eu,
    'patient_system_id => patient_system_id IN ' || eu,
    'target_system_id => target_system_id IN ' || eu);
  -- Legacy tables keyed by the integer profile ids (unused since V44, listed so a row can never block).
  PERFORM pg_temp.e2e_delete('legacy permissions', 'permissions',
    'granted_by => granted_by IN (SELECT user_id FROM e2e_users)',
    'patient_id => patient_id IN ' || ep,
    'caregiver_id => caregiver_id IN (SELECT c.caregiver_id FROM caregivers c WHERE c.system_id IN (SELECT system_id FROM e2e_users))');
END $$;

-- ── Throwaway accounts ──────────────────────────────────────────────────────────────────────────────────

-- Any remaining reference to a throwaway account (or its patient, doctor or caregiver profile) through a
-- RESTRICT / NO ACTION foreign key would block the delete: a nullable column is cleared, a required one
-- takes its row with it. Everything else cascades from users (profiles, settings, members, MFA, ...).
DO $$
DECLARE
  fk record; n bigint; cleared bigint := 0; removed bigint := 0;
BEGIN
  FOR fk IN
    SELECT c.conrelid::regclass AS tbl, a.attname AS col, a.attnotnull AS required,
           c.confrelid::regclass AS reftbl, ra.attname AS refcol
      FROM pg_constraint c
      JOIN pg_attribute a  ON a.attrelid  = c.conrelid  AND a.attnum  = c.conkey[1]
      JOIN pg_attribute ra ON ra.attrelid = c.confrelid AND ra.attnum = c.confkey[1]
     WHERE c.contype = 'f' AND cardinality(c.conkey) = 1 AND c.confdeltype IN ('a', 'r')
       AND c.confrelid IN ('users'::regclass, 'patients'::regclass, 'doctors'::regclass, 'caregivers'::regclass)
     ORDER BY a.attnotnull, c.conrelid::regclass::text, a.attname
  LOOP
    IF fk.required THEN
      EXECUTE format('WITH d AS (DELETE FROM %s WHERE %I IN (SELECT %I FROM %s WHERE system_id IN (SELECT system_id FROM e2e_users)) RETURNING 1) SELECT count(*) FROM d',
                     fk.tbl, fk.col, fk.refcol, fk.reftbl) INTO n;
      removed := removed + n;
    ELSE
      EXECUTE format('WITH u AS (UPDATE %s SET %I = NULL WHERE %I IN (SELECT %I FROM %s WHERE system_id IN (SELECT system_id FROM e2e_users)) RETURNING 1) SELECT count(*) FROM u',
                     fk.tbl, fk.col, fk.col, fk.refcol, fk.reftbl) INTO n;
      cleared := cleared + n;
    END IF;
  END LOOP;
  INSERT INTO e2e_counts (what, n) VALUES ('other rows of throwaway accounts', removed), ('references to throwaway accounts cleared', cleared);
END $$;

WITH d AS (DELETE FROM users WHERE user_id IN (SELECT user_id FROM e2e_users) RETURNING 1)
INSERT INTO e2e_counts (what, n) SELECT 'throwaway users', count(*) FROM d;

\pset tuples_only on
\pset format unaligned
SELECT lpad(sum(n)::text, 6) || '  ' || what FROM e2e_counts GROUP BY what ORDER BY min(step);
SELECT 'file ' || storage_key FROM e2e_files ORDER BY storage_key;
:finish ;
SQL
}

set +e
OUT="$(run_sql)"
STATUS=$?
set -e

if [[ $STATUS -ne 0 ]]; then
  [[ -n "$OUT" ]] && printf '%s\n' "$OUT" >&2
  echo "dev-reset-e2e-data: failed (psql exit $STATUS); the transaction was rolled back, nothing changed" >&2
  # psql exits 2 when the connection is lost; anything else is an SQL error.
  [[ $STATUS -eq 2 ]] && exit 3
  exit 1
fi

# Count lines go to stdout as they are ("<n>  <what>", parsed by frontend/e2e/global-setup.ts); "file <key>"
# lines are the storage keys of deleted documents and exports.
FILES=()
while IFS= read -r line; do
  case "$line" in
    "file "*) FILES+=("${line#file }") ;;
    "") ;;
    *) printf '%s\n' "$line" ;;
  esac
done <<< "$OUT"

# Only keys of the shape FileStorage writes are passed on (no "..", no absolute paths).
KEYS=()
if [[ ${#FILES[@]} -gt 0 ]]; then
  for key in "${FILES[@]}"; do
    if [[ "$key" =~ ^(documents|exports)/[0-9]{4}/[0-9]{2}/[A-Za-z0-9._-]+$ && "$key" != *..* ]]; then
      KEYS+=("$key")
    fi
  done
fi

remove_files() {
  if [[ -n "${E2E_STORAGE_DIR:-}" ]]; then
    [[ "$E2E_STORAGE_DIR" == /* && "$E2E_STORAGE_DIR" != "/" && -d "$E2E_STORAGE_DIR" ]] \
      || { echo "dev-reset-e2e-data: E2E_STORAGE_DIR=$E2E_STORAGE_DIR is not an existing absolute directory; stored files kept" >&2; return 1; }
    local n=0 key
    for key in "${KEYS[@]}"; do
      if [[ -f "$E2E_STORAGE_DIR/$key" ]]; then rm -f -- "$E2E_STORAGE_DIR/$key" && n=$((n + 1)); fi
    done
    echo "$n"
    return 0
  fi
  if [[ "$DOCKER_MODE" == 1 ]] && [[ "$(docker inspect -f '{{.State.Running}}' "$API_CONTAINER" 2>/dev/null)" == "true" ]]; then
    # Inside the API container: the local storage provider keeps files under STORAGE_LOCAL_DIR, by default
    # ${java.io.tmpdir}/carenexus-storage (/tmp in the image). Other providers are left alone.
    printf '%s\n' "${KEYS[@]}" | docker exec -i "$API_CONTAINER" sh -c '
      [ "${STORAGE_PROVIDER:-local}" = local ] || { echo 0; exit 0; }
      dir="${STORAGE_LOCAL_DIR:-/tmp/carenexus-storage}"; n=0
      while IFS= read -r k; do
        [ -n "$k" ] || continue
        if [ -f "$dir/$k" ]; then rm -f -- "$dir/$k" && n=$((n + 1)); fi
      done
      echo "$n"'
    return $?
  fi
  echo "dev-reset-e2e-data: no local storage reachable (set E2E_STORAGE_DIR); ${#KEYS[@]} stored file(s) kept" >&2
  return 1
}

if [[ ${#KEYS[@]} -gt 0 ]]; then
  if [[ "$DRY_RUN" == 1 ]]; then
    printf '%6d  stored files\n' "${#KEYS[@]}"
  elif REMOVED="$(remove_files)"; then
    printf '%6d  stored files\n' "${REMOVED:-0}"
  else
    printf '%6d  stored files\n' 0
  fi
fi

echo "dev-reset-e2e-data: done ($VERB, seed data untouched)"
