# CareNexus data model notes

How the PostgreSQL schema is organised after the completion plan, what is mapped and what is deliberately unused,
how migrations are numbered and how dev data is seeded. The source of truth is the Flyway migrations in
`backend/carenexus-api/src/main/resources/db/migration` together with the JPA entities; Hibernate runs with
`ddl-auto=validate`, so the application does not start when an entity and its table disagree. The last section
corrects statements of the older Technical Architecture and ERD documents that no longer hold.

## 1. Conventions

- People are referenced by `*_system_id VARCHAR(20)` pointing at `users(system_id)` (`PAT-`, `DOC-`, `CGV-`, `ADM-`
  followed by at least five digits). `patients`, `doctors` and `caregivers` are profile tables with their own
  identity key (`patient_id`, `doctor_id`, `caregiver_id`) and a unique, NOT NULL `system_id` that references
  `users(system_id)` (V3 replaced the original `user_id` link; there is no `user_id` column on these tables). Join a
  profile to its user on `system_id`. New system ids come from the sequences `system_id_pat_seq`,
  `system_id_doc_seq`, `system_id_cgv_seq` and `system_id_adm_seq` (V23), which skip ids already in use.
- Rows have integer identity keys. Enum-like strings carry a CHECK constraint; list filters have an index; one-of
  rules (one open support thread per user, one active consultation per appointment, one non-cancelled invoice per
  visit) are partial unique indexes.
- Times: tables that existed before the communications release keep `TIMESTAMP` columns holding clinic wall-clock
  time (`Africa/Harare`; the JVM default zone is set to the clinic zone at startup). Columns added for messaging,
  calls, account tokens and several later tables are `TIMESTAMPTZ`. Check the migration before comparing a column
  with `now()` in native SQL.
- Clinical rows are never hard-deleted: they get a status (`CANCELLED`, `DISCONTINUED`) or `is_deleted` plus
  `deleted_at`. Health metrics are the exception (a reading entered in error is deleted with its blood-pressure
  partner); symptom logs are soft-deleted.
- Since V23, migrations never drop a table or a column; re-keying keeps the old integer column as a nullable,
  unmapped legacy column (section 3). V3 is the exception from before that rule: when it introduced system ids it
  dropped the original integer links (`user_id` on `patients`, `doctors`, `caregivers`, `refresh_tokens`,
  `audit_log` and `notifications`; `patient_id`, `doctor_id` and similar on `appointments`, `consultations`,
  `care_team`, `medical_records`, `prescriptions`, `lab_results`, `imaging_results`, `referrals`,
  `dependent_access`, `emergency_contacts`, `care_plans`, `documents`, `messages`, `message_threads`,
  `disease_detection` and `ai_alerts`). Check the migration before writing native SQL against an integer key.
- Free text that can identify a patient never goes into events, logs or notification text.

## 2. Tables

The schema has 64 tables (plus `flyway_schema_history` and two views). 60 are mapped to JPA entities; one more is
used through JDBC; three are legacy tables kept unused.

### 2.1 Mapped tables by module

| Module (`com.carenexus.api.`) | Tables |
|---|---|
| `auth` | `users`, `refresh_tokens` |
| `core` | `patients`, `doctors`, `caregivers`, `emergency_contacts`, `appointments`, `consultations`, `prescriptions`, `lab_results`, `imaging_results`, `medical_records`, `care_team`, `dependent_access`, `user_settings` |
| `common` | `audit_log` |
| `communications` | `message_threads`, `thread_members`, `messages`, `notifications` |
| `calls` | `call_sessions`, `call_participants`, `call_jobs`, `provider_events` |
| `account` | `password_reset_tokens`, `email_verification_tokens`, `email_change_requests`, `user_mfa`, `mfa_recovery_codes`, `mfa_challenges`, `legal_documents`, `legal_acceptances`, `account_requests` |
| `admin.settings` | `system_settings` |
| `facility` | `facilities` |
| `scheduling` | `doctor_availability`, `doctor_time_off` |
| `medication` | `medication_schedules`, `medication_schedule_plans` |
| `documents` | `documents` |
| `caregiveraccess` | `caregiver_access_requests`, `consent_records` (the consent ledger) |
| `referrals` | `referrals` |
| `careplans` | `care_plans`, `care_plan_goals`, `care_plan_interventions` |
| `health` | `health_metrics`, `symptom_logs` |
| `ai` | `ai_alerts`, `disease_detection` |
| `billing` | `invoices`, `payments`, `payment_methods`, `insurance_claims`, `payment_events`, `billing_fee_schedule` |
| `reminders` | `reminders` |
| `corrections` | `record_correction_requests` |
| `delivery` | `outbound_messages`, `push_devices` |

`idempotency_keys` (V23) has no entity: `common.idempotency.IdempotencyService` reads and writes it with JDBC.

Reference data that production needs ships in versioned migrations, not in the seed: the legal document versions
(`legal_documents`, V29, version `1` of the terms and the privacy notice, placeholder text pending legal review) and
the default consultation fees (`billing_fee_schedule`, V49).

### 2.2 Unmapped columns written with native SQL

Some columns are deliberately left out of the entities and read or written with native SQL by the module that owns
them. `ddl-auto=validate` ignores unmapped columns.

| Column | Migration | Used by |
|---|---|---|
| `users.deactivation_reason` | V32 | administrator deactivation |
| `appointments.manual_reminder_sent_at` | V53 | the doctor's manual reminder |
| `appointments.reminder_1h_sent`, `medication_schedules.reminder_sent_at` | V69 | automatic reminder jobs |
| `patients.health_status_note`, `health_status_updated_at`, `health_status_updated_by` | V52 | doctor-set health status (also exported and cleared by the data-rights flows) |
| the legacy integer keys listed in section 3 | V1 | none (kept for old rows) |

Sequences besides the system-id ones: `invoice_no_seq` (V49, `INV-yyyy-nnnnnn`) and `claim_no_seq` (V57).

### 2.3 Unused tables and views

| Object | Status | Why |
|---|---|---|
| `permissions` (V1) | unused, `COMMENT ON TABLE` in V44 | caregiver permissions are the flags on `dependent_access` |
| `caregiver_alerts` (V1) | unused, comment in V44 | caregiver alerts are notifications |
| `caregiver_activity_log` (V1) | unused, comment in V44 | the patient's view of caregiver activity is read from `audit_log` |
| `v_patient_dashboard`, `v_doctor_workload` (V1, recreated in V3) | not read by the code | dashboards query the tables; migrations check the views before touching a column they read |

`facilities` and `consent_records`, listed as unused in earlier gap analyses, are now in use (facility administration
and the consent ledger). Multi-facility scheduling is still out of scope: a facility is an attribute of an in-person
appointment and a care-team assignment.

## 3. Re-keyed legacy tables

V1 created several tables with integer foreign keys to `patients.patient_id`, `caregivers.caregiver_id`,
`doctors.doctor_id` or `users.user_id`, while the rest of the schema uses system ids (the "mixed FK strategy" of the
architecture document). Each of them was re-keyed by the migration of the slice that took it into use: a
`*_system_id VARCHAR(20)` column referencing `users(system_id)` was added and backfilled through the profile tables,
made NOT NULL where every row has a value, and the old integer column became nullable and unmapped.

| Table | Migration | New key columns | Legacy columns kept (nullable, unmapped) |
|---|---|---|---|
| `medication_schedules` | V38 | `patient_system_id`, `marked_by_system_id` | `patient_id` |
| `documents` | V41 | `patient_system_id`, `uploaded_by_system_id` (plus `storage_key`) | `file_url` (V3 already dropped `patient_id` and `uploaded_by`) |
| `consent_records` | V44 | `patient_system_id`, `caregiver_system_id`, `doctor_system_id`, `granted_by_system_id` | `patient_id`, `caregiver_id`, `doctor_id` |
| `health_metrics`, `symptom_logs` | V47 | `patient_system_id`, `recorded_by_system_id` / `logged_by_system_id` | `patient_id` |
| `ai_alerts` | V48 | `patient_system_id`, `doctor_system_id`, `acknowledged_by_system_id` | none (V3 dropped the integer columns; V48 only relaxes them where an old database still has them) |
| `disease_detection` | V48, V66 | `patient_system_id`, `reviewed_by_system_id` (V48), `doctor_system_id` (V66) | none (as for `ai_alerts`) |
| `payment_methods` | V49 | `system_id` (owner) | `user_id`, `token` |
| `invoices` | V49 | `patient_system_id`, `doctor_system_id`, `created_by_system_id` | `patient_id` |
| `payments` | V49 | `patient_system_id`, `initiated_by_system_id` | `patient_id` |
| `insurance_claims` | V49 | `patient_system_id`, `submitted_by_system_id`, `reviewed_by_system_id` | `patient_id`, `documents` |
| `reminders` | V54 | `patient_system_id`, `recipient_system_id`, `created_by_system_id` | `patient_id` |
| `care_team` | V42 | `provider_system_id` became nullable; external providers are stored by name, phone and organisation | - |

`referrals`, `care_plans`, `documents`, `disease_detection` and `ai_alerts` already had system-id columns from V3 for
some relations; the migrations above completed them. The dev seed for billing is `R__dev_seed_49_billing.sql` and
`R__dev_seed_57_billing_claims.sql`.

## 4. Migration numbering

- Schema migrations live in `db/migration`, dev-only data in `db/seed`. Real environments run `db/migration` only;
  the `local` and `docker` profiles add `db/seed`.
- Before the completion plan: schema V1, V3, V9-V19, V22; seed V2, V4-V8, V20, V21 (the seed scripts were moved out of
  `db/migration` unchanged and keep their versions, so existing development databases still validate).
- The plan allocated V23 to V70, one block per work package, so parallel work never picked the same number. Version
  order is not the order in which the files landed; the only rule is that a migration references tables and columns
  that exist at V22 or are created by a lower version.

| Version | Content | Version | Content |
|---|---|---|---|
| V23 | system-id sequences, caregiver billing flags, account and doctor verification columns, `idempotency_keys`, `audit_log.target_system_id` | V47 | health metrics and symptom logs re-keyed, severity, reading groups |
| V24 | notification model (category, target, resource, dedupe key), quiet hours, billing toggle | V48 | AI alerts and detections re-keyed, review columns |
| V25 | `refresh_tokens.session_id` | V49 | billing alignment: payment methods, invoices, payments, claims re-keyed; `payment_events`, `billing_fee_schedule`, `invoice_no_seq` |
| V26 | `password_reset_tokens` | V50 | `record_correction_requests` |
| V27 | `email_verification_tokens`, `email_change_requests` | V51 | `user_settings.timezone` default |
| V28 | `user_mfa`, `mfa_recovery_codes`, `mfa_challenges` | V52 | patient health-status note, doctor list indexes |
| V29 | `legal_documents` (with version `1`), `legal_acceptances` | V53 | manual appointment reminder column, schedule indexes |
| V30 | `account_requests` (exports, closure) | V54 | reminders re-keyed, recipient and source |
| V31 | session and retention indexes | V55 | follow-up index |
| V32 | `users.deactivation_reason`, admin indexes | V56 | payment next action |
| V33 | `system_settings` | V57 | claim numbers (`claim_no_seq`), claim queue index |
| V34 | appointment and patient `contact_phone`, indexes | V58 | allocated (clinical notifications), not used |
| V35 | one active consultation per appointment | V59 | allocated (account lifecycle), not used |
| V36 | `doctor_availability`, `doctor_time_off` | V60 | allocated (observability), not used |
| V37 | prescription status and expiry index | V61 | allocated (OpenAPI and audit gates), not used |
| V38 | medication schedules re-keyed, `medication_schedule_plans` | V62 | `outbound_messages`, `push_devices` |
| V39 | lab and imaging review notes, document links | V63 | uninvoiced consultation index |
| V40 | medical record source, consultation index | V64 | `facilities.updated_at`, index |
| V41 | documents storage keys and re-keying | V65 | support threads (conversation type `SUPPORT`, admin members, closed state) |
| V42 | care team external providers, primary doctor | V66 | detection source and dedupe key |
| V43 | managed dependents, guardian attestation | V67 | payment methods admin index |
| V44 | `caregiver_access_requests`, consent ledger re-keyed, legacy table comments | V68 | call session end reason `APPOINTMENT_ENDED` |
| V45 | referral workflow | V69 | reminder job flags |
| V46 | care plan goals and interventions | V70 | allocated (billing and access gates), not used |

An allocated version that was not used stays unused; do not fill the gaps later (Flyway would treat a new file
below the latest applied version as out of order and fail validation on existing databases).

**Next migration: V71.** Take the next number after the highest file in `db/migration`. Name files
`V<NN>__<area>_<what>.sql`; a second file of the same change is `V<NN>.1__...`. Never edit, rename or delete a
released migration (`scripts/check-migrations.sh` and CI fail on it); write defensively (`IF NOT EXISTS`, guarded
`DO $$` blocks) because development databases contain seed data. `MigrationPathsTest` migrates an empty database with
`db/migration` alone and with `db/migration` plus `db/seed`, and checks that the two folders never share a version.

## 5. Seed convention

The full rules are in `backend/carenexus-api/src/main/resources/db/seed/README.md`. In short:

- Never add a versioned file to `db/seed`. New dev-only data is a repeatable script
  `R__dev_seed_<NN>_<slice>.sql`, where `<NN>` is the version of the migration that created the tables it fills.
  Repeatable scripts run after all versioned migrations and again whenever their checksum changes, so they are
  idempotent (`ON CONFLICT DO NOTHING`, `WHERE NOT EXISTS`) and do nothing when the demo users are missing.
- Reference data production needs goes into a versioned migration in `db/migration`.
- The demo passwords are published; outside the developer profiles the API refuses a database that the seed was
  applied to.

## 6. Corrections to the Technical Architecture and ERD documents

The product documents (Technical Architecture, ERD, PRD) predate the completion plan. Where they disagree with this
file, the migrations or the code, the latter win. Known stale statements:

| Document | Statement | Correct |
|---|---|---|
| Technical Architecture, component diagram | "Flyway migrations (V1–V8)" | Schema migrations V1 to V69 (with gaps, section 4); dev seed in a separate folder |
| Technical Architecture, migration strategy | "V1 (schema) → V2 (test users) → V3 (system_id refactor) → V4–V8 (test data)" | V2 and V4-V8 (and V20, V21) are dev seed in `db/seed`, loaded only by the `local` and `docker` profiles; new dev data is repeatable `R__dev_seed_*` |
| Technical Architecture, "Mixed FK strategy — intentional" | medication schedules, reminders, symptom logs, health metrics, billing tables, consent records and the caregiver legacy tables use integer FKs | All tables in use are re-keyed to system ids (section 3); only the three unused legacy tables keep integer keys |
| Technical Architecture, production considerations | "Set SPRING_PROFILES_ACTIVE=docker" for production | Production runs **without** a Spring profile and with `CARENEXUS_ENVIRONMENT=production`. `docker` is a development profile (seed data, development secrets, local providers); the API refuses to start when it is combined with staging or production (README, "Environments and profiles") |
| Technical Architecture, production considerations | "Disable Swagger UI in production by removing the springdoc exposure config" | Swagger UI and `/v3/api-docs` are off unless `SWAGGER_ENABLED=true`; leave it unset outside development |
| Technical Architecture, environment variables | `JWT_SECRET` "32+ char string" | at least 64 bytes; committed values are refused |
| Technical Architecture, frontend mock server | an Express mock server in `frontend/mock-server` and `docker-compose.mock.yml` | Removed: there is no mock server. The web app runs against the real API; `docker-compose.yml` has a `carenexus-web` service |
| Technical Architecture, environment variables | `EXPO_PUBLIC_API_URL=http://localhost:8080` | `http://localhost:9000` |
| ERD | "36 application tables, Flyway's history table and 2 views" | 64 tables (section 2), `flyway_schema_history`, 2 unused views |
| ERD, integer FK list | `patient_id` FKs from `medication_schedules`, `health_metrics`, `symptom_logs`, `reminders`, `invoices`, `payments`, `insurance_claims`, `consent_records`; `user_id` from `payment_methods` | re-keyed to system ids (section 3); the integer columns are nullable legacy columns |
| ERD, `permissions` "Unclear from the diagram" | - | unused legacy table (section 2.3) |
| PRD, US-01 | "a user account is created and JWT tokens are returned" | registration returns no tokens; the user signs in after email verification (and, for doctors, approval) |
| PRD, administrator persona | "Manages users, roles, facilities, and system configuration" | role changes are not supported (README, "Documented decisions") |
