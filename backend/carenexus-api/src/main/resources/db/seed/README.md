# Development seed data (NOT schema)

These Flyway scripts insert demo users (with published passwords, including an ADMIN) and mock
clinical data. They are only on the Flyway path for the `local` and `docker` Spring profiles
(`spring.flyway.locations: classpath:db/migration,classpath:db/seed`). Real environments use
`classpath:db/migration` only.

## Rules

- Do not edit the versioned files here. Databases that already applied them validate by checksum; any
  change (even a comment) breaks `flyway validate` until `flyway repair` is run.
- They were moved here from `db/migration` unchanged, keeping their version numbers. Flyway merges both
  locations by version, so existing dev databases keep validating.
- V7 updates appointments by literal id and depends on V6; always keep them together.
- **Never add another versioned (`V<n>__`) file to this folder.** Schema migrations in `db/migration`
  have moved past the seed versions, and Flyway does not run out of order: a new versioned seed with a
  number below the latest schema migration fails validation on every existing database, and one with a
  number above it collides with a future schema migration.
- **New dev-only data is a repeatable script:** `R__dev_seed_<NN>_<slice>.sql`, where `<NN>` is the schema
  version of the migration that created the tables it fills (for example `R__dev_seed_45_referrals.sql`).
  Repeatable scripts run after all versioned migrations and re-run whenever their checksum changes, so they
  must be idempotent (`ON CONFLICT DO NOTHING` / `WHERE NOT EXISTS`) and guarded on the demo users existing.
  Do not insert `users` rows with a hand-picked `system_id` in a repeatable script unless the script is
  idempotent on that id: new ids come from the `system_id_*_seq` sequences (V23), which skip ids already in use.
- Reference data that production needs (legal document versions, default fee rows) does not belong here:
  it goes in the versioned schema migration in `db/migration`.
- Treat the documented demo credentials as burned. Never load this folder into a shared database.

## Versions in use

| Location | Versions |
|---|---|
| `db/seed` (dev only, versioned, frozen) | V2, V4-V8 (demo users including the administrator `admin@carenexus.com`, and mock data), V20 (caregiver communication approvals), V21 (a working dev password for the demo patient Thomas Dube) |
| `db/seed` (dev only, repeatable) | see the table below |
| `db/migration` (schema) | V1, V3, V9-V19, V22-V57, V62-V69 (V58-V61 and V70 were allocated and stay unused). **The next schema migration is V71.** |

The versioned sets must stay disjoint; `MigrationPathsTest` fails the build when a version appears in both, and it
migrates an empty database through both paths (schema only, and schema plus seed). `scripts/check-migrations.sh`
(also a CI job) fails when a released `.sql` file in either folder is changed, renamed or deleted, when a version is
used twice, when a file name breaks Flyway naming, and when a new versioned file appears in this folder.

Repeatable dev seeds (they run after every versioned migration, re-run when their checksum changes, and do nothing
when the demo users are missing):

| Script | Fills (migration that created the tables) | Content |
|---|---|---|
| `R__dev_seed_36_availability.sql` | `doctor_availability` (V36) | weekly hours 08:00-13:00 and 14:00-18:00, 30-minute slots, for every seeded doctor without a saved template; no time off |
| `R__dev_seed_38_be_medication.sql` | `medication_schedule_plans` (V38) | dose plans for the demo patients' prescriptions created before V38, so the schedule job creates their doses |
| `R__dev_seed_45_referrals.sql` | `referrals` (V45) | demo referrals issued by Dr. James Banda |
| `R__dev_seed_46_care_plans.sql` | `care_plans`, goals, interventions (V46) | demo care plans for Linda Moyo and Thomas Dube |
| `R__dev_seed_47_health.sql` | `health_metrics`, `symptom_logs` (V47) | demo vitals and symptom logs for Linda Moyo |
| `R__dev_seed_49_billing.sql` | `invoices` (V49) | three invoices for Linda Moyo issued by Dr. James Banda |
| `R__dev_seed_57_billing_claims.sql` | `payments`, `insurance_claims` (V57) | a COMPLETED M-PESA payment for INV-2026-900002 and claim CLM-2026-900001 (SUBMITTED) for INV-2026-900001; skipped when the demo patient or those invoices are missing |

Billing note: V49 re-keyed `invoices`, `payments`, `insurance_claims` and `payment_methods` to system ids (the
integer keys stay as nullable legacy columns) and created `invoice_no_seq`; V57 created `claim_no_seq`. The full
list of re-keyed tables and the migration table with what each version contains are in
`docs/architecture/DATA-MODEL-NOTES.md` at the repository root.

Schema versions V23 to V70 were allocated per work package of the completion plan, so parallel changes never
picked the same number; the allocation is recorded in `docs/architecture/DATA-MODEL-NOTES.md`, section 4. From V71
on, take the next number after the highest file in `db/migration`.
