# CareNexus

CareNexus combines patient and doctor care workflows (Care Direct) with a caregiver/proxy experience (Care Proxy) and an administration area. This repository contains an Expo Router + react-native-web frontend (`frontend/`), a Spring Boot 3 / Java 17 API (`backend/carenexus-api/`) and its PostgreSQL Flyway migrations.

The web app is the primary client. Native mobile builds exist but lag behind the web app (see [TODO.md](TODO.md), "Native app phase"); video calling, billing, reminders, support conversations and administration are web only.

Every feature that depends on an outside service (email, SMS, push, file storage, virus scanning, payments, insurance, AI, wearables, licence registries, captcha) sits behind an interface with a working local implementation chosen by configuration. What remains before a public launch is plugging real providers into those interfaces, production infrastructure and legal review: [TODO.md](TODO.md).

Documentation:

| Topic | Where |
|---|---|
| HTTP conventions, error codes, pagination, idempotency, events, notification links, section keys | [docs/api/CONTRACT.md](docs/api/CONTRACT.md) |
| Tables, re-keyed legacy tables, unused tables, migration numbering, seed convention, corrections to the architecture and ERD documents | [docs/architecture/DATA-MODEL-NOTES.md](docs/architecture/DATA-MODEL-NOTES.md) |
| Billing: tables, state machines, endpoints, local gateway webhook | [docs/billing/CONTRACT.md](docs/billing/CONTRACT.md) |
| Messaging, support conversations and video calls | [docs/communications/README.md](docs/communications/README.md), [docs/communications/CONTRACT.md](docs/communications/CONTRACT.md) |
| Dependency versions and advisory policy | [docs/security/DEPENDENCIES.md](docs/security/DEPENDENCIES.md) |
| Dev seed rules | [backend/carenexus-api/src/main/resources/db/seed/README.md](backend/carenexus-api/src/main/resources/db/seed/README.md) |
| Live API reference | Swagger UI at `/swagger-ui.html` on a development API (`local` profile, or `SWAGGER_ENABLED=true`) |

---

## Local development

Requirements: Docker (with Compose v2.24 or newer), JDK 17 (only to run the API outside Docker), Node.js 22 LTS with npm.

### 1. Environment files

```bash
cp .env.example .env                                       # read by docker compose (git-ignored)
cp frontend/.env.example frontend/.env.development.local   # optional, only to override the API URL
```

Every committed value is a public, local-development placeholder. `.env.example` lists every setting the API reads, commented out with its default; uncomment a line only to change it. docker compose passes the whole `.env` to the API container. An API started on the host does not read `.env`: export the variables in that shell.

The main variables:

| Variable | Where | Local default | Purpose |
|---|---|---|---|
| `JWT_SECRET` | `.env` | dev placeholder in `.env.example` | HS512 signing secret, at least 64 bytes. Compose refuses to start without it. |
| `JWT_EXPIRATION` | `.env` | `900000` | Access token lifetime in ms. |
| `POSTGRES_PASSWORD` | `.env` | `local_dev_password` | Password of the compose database. |
| `CORS_ALLOWED_ORIGINS` | `.env` | `http://localhost:8081,http://localhost:19006` | Browser origins allowed to call the API. No wildcard. |
| `CARENEXUS_WEB_BASE_URL` | `.env` | `http://localhost:8081` | Web app URL used in links in emails and notifications. |
| `SWAGGER_ENABLED` | `.env` | `false` (docker) / on in the `local` profile | Serves Swagger UI (`/swagger-ui.html`) and `/v3/api-docs`. Only enable it on a trusted development machine. |
| `RATE_LIMIT_ENABLED` | `.env` | `true` | Set `false` for browser end-to-end runs (see step 5). |
| `LIVEKIT_URL`, `LIVEKIT_CLIENT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` | `.env` (optional) | the public `livekit-server --dev` pair from the `local`/`docker` profiles | Video calls. |
| `EXPO_PUBLIC_API_URL` | `frontend/.env.*` or `.env` (web container build argument) | `http://localhost:9000` | API base URL, inlined into the web bundle at build time. |
| `EXPO_PUBLIC_SUPPORT_EMAIL` | `frontend/.env.*` or `.env` (web container build argument) | empty | Help & Support fallback when `GET /api/settings/public` fails or has no support email. |
| `CSP_CONNECT_SRC` | `.env` (web container) | `http://localhost:9000 ws://localhost:7880` | API and LiveKit origins allowed by the web container's Content-Security-Policy. |

The other settings (accounts, providers, jobs, retention, rate-limit buckets, clinic defaults, server limits) are listed in `.env.example` and explained in `backend/carenexus-api/src/main/resources/application.yml`. Keys without a short variable are set with Spring's relaxed names, for example a rate-limit bucket (`CARENEXUS_RATELIMIT_BUCKETS_MESSAGESEND_LIMIT=120`) or a vital-sign reference range (`CARENEXUS_HEALTH_RANGES_HEARTRATE_HIGH=110`; a change grades readings stored afterwards only). Some values are also system settings that an administrator changes at runtime in **System settings** (clinic name, support contact, reminder lead times, the needs-outcome delay, billing currency, tax rate and payment terms); a stored setting wins over the environment value.

### 2. Start the stack

Everything in Docker (PostgreSQL, LiveKit in dev mode, the API with the `docker` profile and demo seed data, and the web app):

```bash
docker compose up -d --build
```

| Service | Container | URL |
|---|---|---|
| Web app (static export behind nginx) | `carenexus-web` | http://localhost:8081 |
| API | `carenexus-api` | http://localhost:9000 (health: `/actuator/health`) |
| LiveKit (dev) | `carenexus-livekit` | ws://localhost:7880 (TCP 7881, UDP 7882) |
| PostgreSQL | `carenexus-postgres` | localhost:5432, database `carenexus_db`, user `carenexus` |

The web container serves the exported bundle, so it does not reload on code changes. While working on the frontend, start only the backend and run the Expo dev server, which uses the same port 8081:

```bash
docker compose up -d --build postgres-db livekit carenexus-api   # or: scripts/dev-up.sh
cd frontend && npm install && npx expo start --web
```

Or run the API on the host (faster rebuilds), with PostgreSQL and LiveKit in Docker:

```bash
docker compose up -d postgres-db livekit
cd backend/carenexus-api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
# or: scripts/dev-up.sh --api-local
```

Stop with `docker compose down` (add `-v` to also delete the database volume). Uploaded documents and data exports of the docker stack are kept in the API container's temporary directory (`STORAGE_LOCAL_DIR` default) and are lost when the container is recreated.

The web image is built from `frontend/Dockerfile`: `expo export --platform web`, then `nginxinc/nginx-unprivileged` on port 8081 with a single-page-app fallback (any route without a file extension that is not an exported page gets `index.html`; missing assets are 404), long caching for hashed bundles, `no-cache` for HTML, and the headers `Content-Security-Policy`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Permissions-Policy` (camera and microphone for this origin only) and `Cross-Origin-Opener-Policy`. `EXPO_PUBLIC_*` values are build arguments (rebuild after a change); `CSP_CONNECT_SRC` is read when the container starts. The image serves plain HTTP: TLS and HSTS belong to the proxy in front of it.

### 3. Demo data and accounts

The `local`/`docker` profiles also load the dev-only seed in `backend/carenexus-api/src/main/resources/db/seed` (versioned V2, V4-V8, V20, V21 and the repeatable `R__dev_seed_*` scripts). Seed data never runs in staging or production, and the API refuses to start outside the developer profiles on a database that has it.

Demo logins (published dev-only passwords, documented in the seed files and `frontend/e2e/support/env.ts`):

| Role (sign-in tab) | Email | Password |
|---|---|---|
| Patient | `thomas.dube@carenexus.com` | `patient@thomas` |
| Patient (records and lab results) | `linda.moyo@carenexus.com` | `patient@123` |
| Doctor | `james.banda@carenexus.com` | `doctor@banda` |
| Proxy / Carer (acts for Thomas Dube) | `sarah.dube@carenexus.com` | `proxy@sarah` |
| Administrator (any tab; the server role decides) | `admin@carenexus.com` | `admin@nexus` |

The first sign-in of a demo account goes through `/auth/accept-terms` (the seeded legal documents are version `1`). In the developer profiles sign-in does not require a verified email and no role requires two-factor authentication.

Nothing leaves the machine in development: emails, SMS and push messages are rendered into the `outbound_messages` table by the local providers. Read them (verification, password reset and invitation links included) in the admin **Outbox** section or with `GET /api/admin/dev/outbound-messages?channel=&to=&template=` (administrator token; developer profiles, and outside production while the email provider is `local`). Card and M-PESA payments stay pending until you press **Simulate success** or **Simulate failure** on the payment (local gateway, developer profiles only).

LiveKit in dev mode uses the public `devkey`/`secret` pair and signs webhooks to `http://carenexus-api:9000` and `http://host.docker.internal:9000`, so the webhook reaches the API in either setup. Camera and microphone access in the browser needs a secure context, and `http://localhost` counts as one. Opening the app from another device on your network needs HTTPS.

### 4. Checks

```bash
cd backend/carenexus-api && ./mvnw verify   # unit, integration and release-gate tests (Testcontainers, needs Docker)
cd frontend && npm run lint && npx tsc --noEmit
scripts/check-migrations.sh                 # released Flyway files unchanged, versions unique, no new versioned seed
```

CI (`.github/workflows/ci.yml`) runs these on every push and pull request, plus the web export, `npm audit` (critical advisories fail the build, high advisories are reported), the OWASP dependency check of the backend (CVSS 9 and above fails), a gitleaks secrets scan (`.github/gitleaks.toml` allow-lists only the committed dev placeholders) and the browser end-to-end tests. A nightly run adds Firefox and WebKit. Details and the advisory policy: [docs/security/DEPENDENCIES.md](docs/security/DEPENDENCIES.md). If the repository belongs to a GitHub organization, add a `GITLEAKS_LICENSE` repository secret for the gitleaks action.

### 5. Browser end-to-end tests

Playwright specs in `frontend/e2e/` drive the real stack: smoke navigation of every section for all four roles (from the sidebar and by URL), deep links for every notification target, accessibility (axe WCAG 2.1 A/AA with no serious or critical violations, `label-content-name-mismatch` on, no horizontal scrolling at 375 px, phone drawer open), messaging and support conversations, video calls with a fake camera and microphone, and the clinical, billing, account and administration flows (`flow-*.spec.ts`).

```bash
echo RATE_LIMIT_ENABLED=false >> .env                            # the suites exceed the sign-in and conversation buckets
docker compose up -d --build postgres-db livekit carenexus-api   # API :9000, LiveKit :7880 (needs JWT_SECRET, see step 1)
cd frontend
npm install
npx playwright install chromium   # once
npm run e2e:build                 # static web export into dist/ (EXPO_PUBLIC_API_URL=http://localhost:9000)
npm run e2e                       # serves dist/ on :8081 if nothing listens there, then runs the tests
```

Stop `carenexus-web` first (`docker compose stop carenexus-web`) if you want the tests to use the freshly built `dist/` rather than the container on the same port.

- Before and after every run, global setup and teardown run `scripts/dev-reset-e2e-data.sh` (also `npm run e2e:clean`, preview with `npm run e2e:clean -- --dry-run`). In one transaction it deletes only marked test data: throwaway `e2e-…@carenexus.test` accounts and everything they own, and free text starting with `e2e`/`E2E` together with every row that hangs off it (appointments, consultations, records, prescriptions, results, documents and their stored files, referrals, care plans, reminders, health readings, alerts, invoices, payments, claims, payment methods, correction requests, access requests, consent records, conversations, notifications and the outbox rows sent from them, legal versions, system-setting overrides, facilities and more; the full list is in the script header). Seed data and reference tables are never touched. It only targets the local `carenexus-postgres` container, or a Postgres on localhost via `E2E_PG_HOST`, and refuses databases without the dev seed. Set `E2E_CLEANUP=0` to keep the data for inspection.
- Global setup also prepares the demo accounts (accepts the current terms for them); `E2E_PREPARE=0` skips that.
- Stored files of deleted documents and exports are removed from `E2E_STORAGE_DIR` (an API on the host with `STORAGE_LOCAL_DIR`) or from inside the API container (`E2E_API_CONTAINER`, default `carenexus-api`).
- `E2E_ADMIN_EMAIL` / `E2E_ADMIN_PASSWORD` point the suites at another local administrator. `E2E_WEB_URL` and `E2E_API_URL` point the tests at other ports. Some tests move appointments into the past with SQL in the local database (`E2E_PG_CONTAINER`, `E2E_PG_USER`, `E2E_PG_DB`), so Docker must be reachable.
- `E2E_CROSS_BROWSER=1 npx playwright test --project=firefox --project=webkit` runs the smoke, accessibility and deep-link specs in Firefox and WebKit (`npx playwright install firefox webkit` first); tests tagged `@chromium-only` (fake camera) are skipped there. `npm run e2e:chrome` uses installed Google Chrome.
- Specs run one at a time (shared demo accounts). The HTML report is written to `frontend/e2e-report`, traces of failures to `frontend/e2e-results`.

---

## Environments and profiles

Two settings decide how an API instance behaves: the Spring profile and `CARENEXUS_ENVIRONMENT`.

| | Local development | Staging | Production |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` (API on host) or `docker` (compose) | none | none |
| `CARENEXUS_ENVIRONMENT` | `development` (default) | `staging` | `production` |
| Seed data | yes (`db/seed`) | no | no |
| Development secrets (JWT, LiveKit, MFA key, billing webhook) | accepted | refused | refused |
| Sign-in needs a verified email / MFA for administrators | no / no | yes / yes | yes / yes |
| Admin **Outbox** (`GET /api/admin/dev/outbound-messages`) | yes | yes while `DELIVERY_EMAIL_PROVIDER=local` (the default): an administrator relays verification and password-reset links from it | never (404); verification and password recovery need a real email provider |
| Local provider implementations | allowed | allowed, one WARN line each | startup fails unless `CARENEXUS_ALLOW_LOCAL_PROVIDERS=true` |
| Video | LiveKit server container, `--dev` mode | LiveKit Cloud project (staging) | LiveKit Cloud project (production) |
| Transport | `http://localhost` | HTTPS only | HTTPS only |

Rules enforced at startup:

- `local`, `docker` and `test` are development profiles. They load demo accounts with published passwords, accept committed development secrets and use local providers. **The `docker` profile is never a production profile**, even though the API runs in a container: a production container runs without any profile. `EnvironmentStartupChecks` refuses to start when a development profile is combined with `CARENEXUS_ENVIRONMENT=staging` or `production`, and an unknown environment value fails startup.
- Outside the development profiles `AuthStartupChecks` refuses a database that shows dev seed data: a seed script in `flyway_schema_history`, an active account with a seeded demo email, or an active account with a seeded password hash. To reuse such a database, purge the demo rows and the seed scripts' history rows first (see the comment in `application.yml`), or start from a database migrated with `db/migration` only.
- `JwtUtil` refuses a missing, short or committed `JWT_SECRET`; `LiveKitStartupChecks` refuses the dev LiveKit secret; `MfaStartupChecks` requires `AUTH_MFA_ENCRYPTION_KEY` (and refuses the committed dev value); `BillingStartupChecks` refuses the dev or a short `BILLING_LOCAL_WEBHOOK_SECRET` (without one, card and M-PESA payments are unavailable).
- `LocalProviderGuard`: with `CARENEXUS_ENVIRONMENT=production` every seam that runs on its local implementation fails startup unless `CARENEXUS_ALLOW_LOCAL_PROVIDERS=true`; in staging each logs one WARN line. Email, SMS, push, file storage, file scanning, the payment gateway, insurance claims, risk detection (`rules`), medication interactions (`rules` or `disabled`) and the licence registry (`manual`) count as local. A channel set to `disabled` (email, SMS, push, risk detection, wearables) always starts. The in-memory rate-limit store is exempt: it enforces every limit and only logs one WARN outside the development profiles, because each API instance counts separately. With what ships today a production start therefore needs `CARENEXUS_ALLOW_LOCAL_PROVIDERS=true`, at least for the medication-interaction checker and the licence registry, which have no real implementation yet; setting it is an explicit acceptance of every local seam in the table below.
- `STORAGE_LOCAL_DIR` must be a writable directory (it is created at startup).

### First administrator

Administrators cannot register themselves. On a fresh production database set `CARENEXUS_BOOTSTRAP_ADMIN_EMAIL` and `CARENEXUS_BOOTSTRAP_ADMIN_PASSWORD` (at least 12 characters) for the first start: when no active administrator exists, the account is created once with a verified email; when one exists the variables are ignored with a log line. Remove both variables after the first start, sign in, and enrol two-factor authentication (required for administrators outside development). Further administrators are created in the admin **Users** section.

### What staging and production must set

| Variable | Example / rule |
|---|---|
| `CARENEXUS_ENVIRONMENT` | `staging` or `production`; no `SPRING_PROFILES_ACTIVE`. |
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Managed PostgreSQL 16 over TLS. |
| `JWT_SECRET` | Fresh random value, at least 64 bytes (`openssl rand -base64 64`). |
| `JWT_EXPIRATION` | `900000` (15 minutes) unless there is a reason to change it. |
| `AUTH_MFA_ENCRYPTION_KEY` | Base64 of 32 random bytes (`openssl rand -base64 32`). Mandatory. Keep it: changing it makes enrolled authenticators unreadable. |
| `AUTH_MFA_REQUIRED_ROLES` | `ADMIN` (default); any of `PATIENT`, `DOCTOR`, `CAREGIVER`, `ADMIN`. |
| `CORS_ALLOWED_ORIGINS` | The exact HTTPS web origin(s), e.g. `https://app.example.com`. No wildcard, no `http://`. |
| `CARENEXUS_WEB_BASE_URL` | The public web origin, e.g. `https://app.example.com` (links in emails). |
| `CARENEXUS_BOOTSTRAP_ADMIN_EMAIL`, `CARENEXUS_BOOTSTRAP_ADMIN_PASSWORD` | First start only, see above. |
| `CARENEXUS_ALLOW_LOCAL_PROVIDERS` | `true` only as a deliberate decision while local implementations remain (see above). |
| `BILLING_LOCAL_WEBHOOK_SECRET` | Only while the local gateway is used (staging): a random value of at least 32 characters. |
| `STORAGE_LOCAL_DIR` | Only while local storage is used: a persistent, backed-up volume. |
| `SUPPORT_CONTACT_EMAIL`, `SUPPORT_CONTACT_PHONE`, `CLINIC_NAME` | Fallbacks; administrators set the real values in **System settings**. |
| `LIVEKIT_ENABLED` | `true` |
| `LIVEKIT_URL` | Server API URL of the Cloud project, e.g. `https://<project>.livekit.cloud`. |
| `LIVEKIT_CLIENT_URL` | Browser URL of the same project, e.g. `wss://<project>.livekit.cloud`. |
| `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` | Key pair created in that Cloud project. |
| `LIVEKIT_WEBHOOK_ENABLED` | `true` once the webhook below is configured. |
| `CALLS_WORKER_ENABLED` | `true` (default). The worker that creates and cleans up rooms; set `false` only on extra replicas if you must limit it. |
| `SWAGGER_ENABLED` | Leave unset (`false`). The API documentation is never public in staging or production. |
| `EXPO_PUBLIC_API_URL` (web build) | The public HTTPS API origin, e.g. `https://api.example.com`. |
| `CSP_CONNECT_SRC` (web container) | API origin plus the LiveKit project's `https://` and `wss://` URLs. |

Startup fails when LiveKit is enabled outside `local`/`docker`/`test` with the dev secret `secret` or a secret shorter than 32 characters. If LiveKit is not configured at all, the API still starts; calls report "temporarily unavailable" and the webhook returns 404.

---

## What is local and what needs a real provider

Each seam is an interface with a working local implementation, selected by a property. A real adapter is a new class implementing the interface plus a branch (or bean) in the seam's configuration class with its own provider id and properties; nothing else changes. An unknown provider id fails startup instead of falling back to the local implementation.

| Seam | Interface (`com.carenexus.api.`) | Local implementation (what it does) | Property (env) | Where a real adapter plugs in | Counts as local in production |
|---|---|---|---|---|---|
| Email | `delivery.email.EmailSender` | `LocalOutboxEmailSender`: renders into `outbound_messages`, marks SENT with provider `local`, logs one redacted line; `DisabledEmailSender` marks SKIPPED | `carenexus.delivery.email.provider=local` (`DELIVERY_EMAIL_PROVIDER`, `local`/`disabled`) | new `EmailSender` (SMTP, SES, ...) + a case in `delivery.config.DeliveryConfig` | yes (`disabled`: no) |
| SMS | `delivery.sms.SmsSender` | `LocalOutboxSmsSender` / `DisabledSmsSender` | `carenexus.delivery.sms.provider=local` (`DELIVERY_SMS_PROVIDER`) | new `SmsSender` (Twilio, Africa's Talking, ...) + `DeliveryConfig` | yes (`disabled`: no) |
| Push | `delivery.push.PushSender` | `LocalOutboxPushSender` / `DisabledPushSender` (devices registered through `POST /api/notifications/devices`) | `carenexus.delivery.push.provider=local` (`DELIVERY_PUSH_PROVIDER`) | new `PushSender` (Expo push, FCM) + `DeliveryConfig` | yes (`disabled`: no) |
| File storage | `storage.FileStorage` | `LocalFileStorage`: files under `STORAGE_LOCAL_DIR`, atomic writes, SHA-256, downloads stream through the API | `carenexus.storage.provider=local` (`STORAGE_PROVIDER`, `STORAGE_LOCAL_DIR`) | new `FileStorage` (S3-compatible, signed URLs) + `storage.config.StorageConfig` | yes |
| File scanning | `storage.FileScanner` | `LocalFileScanner`: magic-byte type detection (PDF, JPEG, PNG, DICOM), rejects the EICAR test string | `carenexus.storage.scanner=local` (`STORAGE_SCANNER`) | new `FileScanner` (ClamAV, cloud scanning) + `StorageConfig` | yes |
| Payment gateway | `billing.provider.PaymentGateway` | `LocalPaymentGateway`: cash, bank transfer and insurance settle at once; card and M-PESA stay PENDING until the HMAC-signed local callback (or **Simulate**) | `carenexus.billing.provider=local` (`BILLING_PROVIDER`, `BILLING_LOCAL_WEBHOOK_SECRET`) | new `PaymentGateway` (M-PESA Daraja, card processor) + `billing.config.BillingGatewayConfig`; webhook at `POST /api/integrations/payments/{provider}/webhook` ([docs/billing/CONTRACT.md](docs/billing/CONTRACT.md) section 12) | yes |
| Insurance claims | `billing.provider.InsuranceClaimProvider` | `ManualInsuranceClaimProvider`: an administrator moves the claim status | `carenexus.billing.claims-provider=manual` (`BILLING_CLAIMS_PROVIDER`) | an `InsuranceClaimProvider` bean (replaces the manual one) + `billing.config.BillingClaimsConfig` | yes |
| Risk detection | `ai.provider.RiskDetectionProvider` | `RuleBasedRiskDetectionProvider` (`rules-v1`, deterministic rules over symptoms and readings); `DisabledRiskDetectionProvider` | `carenexus.ai.provider=rules` (`AI_PROVIDER`, `rules`/`disabled`; `AI_ASSESSMENT_TIMEOUT_MS`) | new provider + `ai.detection.config.AiDetectionConfig` | `rules`: yes; `disabled`: no |
| Medication interactions | `ai.interaction.MedicationInteractionChecker` | `StaticRuleInteractionChecker` (fixed table of known pairs); `DisabledInteractionChecker` | `carenexus.ai.interaction-provider=rules` (`AI_INTERACTION_PROVIDER`, `rules`/`disabled`) | new checker (drug-interaction database) + `ai.generator.config.AiGeneratorConfig` | yes, both values |
| Terminology (ICD-10) | `terminology.provider.TerminologyProvider` | `BundledIcd10Provider`: bundled subset (`TERMINOLOGY_BUNDLED_RESOURCE`) plus format check; well-formed codes outside it are stored as unverified | `carenexus.terminology.provider=bundled` (`TERMINOLOGY_PROVIDER`) | new provider (full ICD-10 service) + `terminology.config.TerminologyConfig` | no (the bundled catalogue is the intended production implementation) |
| Wearables | `health.provider.WearableConnector` | `DisabledWearableConnector`; every reading enters through `HealthMetricIngestService.ingest(patientSystemId, source, deviceId, readings)` | `carenexus.health.wearables.provider=disabled` (`HEALTH_WEARABLES_PROVIDER`) | new connector (device cloud) + `health.config.HealthConfig` | no |
| Licence registry | `admin.clinician.provider.LicenseRegistryClient` | `ManualReviewLicenseRegistryClient`: always `UNVERIFIED_MANUAL_REVIEW`; an administrator approves in the clinician queue | `carenexus.auth.licence-registry=manual` (`AUTH_LICENCE_REGISTRY`) | a `LicenseRegistryClient` bean (medical council registry) + `admin.clinician.config.LicenseRegistryConfig` | yes |
| Human verification | `auth.provider.HumanVerification` | `NoopHumanVerification` (accepts every request) on registration and forgot-password | `carenexus.auth.human-verification=none` (`AUTH_HUMAN_VERIFICATION`) | a `HumanVerification` bean (captcha) + `auth.config.HumanVerificationConfig`; registration and forgot-password already accept an optional `humanVerificationToken`, the web forms need the widget | not checked by the guard |
| Rate-limit store | `common.ratelimit.RateLimiter` | `InMemoryRateLimiter`: fixed windows per API instance, bounded key map | `carenexus.rate-limit.store=memory` (`RATE_LIMIT_STORE`) | a shared `RateLimiter` bean (Redis) + `common.ratelimit.RateLimitConfig` | exempt (one WARN outside development) |
| Video calls | `calls.provider.CallProvider` | `LiveKitCallProvider` (local LiveKit server in dev mode, LiveKit Cloud in staging and production); `DisabledCallProvider` when not configured | `carenexus.livekit.enabled` (`LIVEKIT_*`) | already real: point `LIVEKIT_*` at a Cloud project | not guarded; `LiveKitStartupChecks` refuses dev secrets |

Every provider call that can fail is retried from a table (the outbox for messages, `call_jobs` for video rooms, payment reconciliation for payments), and webhooks verify a signature over the raw body, cap the body at 64 KB and store an idempotent event row without the payload.

---

## Background jobs

All jobs stop with `CARENEXUS_JOBS_ENABLED=false` and run on one instance at a time (Postgres advisory lock). Each run is counted as `carenexus.jobs.runs{job, outcome=success|failure|skipped}` and timed as `carenexus.jobs.duration{job}`.

| Job | What it does | Interval property (default) |
|---|---|---|
| `outbound` | sends due email, SMS and push messages with exponential backoff; FAILED after `DELIVERY_MAX_ATTEMPTS` | `carenexus.jobs.outbound.interval-ms` (5 s) |
| `reminders` | due manual reminders | `carenexus.jobs.reminders.interval-ms` (60 s) |
| `appointment-reminders` | automatic appointment reminders (lead times from System settings) | `carenexus.jobs.appointment-reminders.interval-ms` (60 s) |
| `medication-reminders` | dose reminders from due `medication_schedules` rows | `carenexus.jobs.medication-reminders.interval-ms` (60 s) |
| `medication-schedule` | keeps dose schedules filled and marks missed doses | `carenexus.jobs.medication-schedule.interval-ms` (15 min) |
| `needs-outcome-nudge` | daily nudge to doctors about past appointments without an outcome | `carenexus.jobs.needs-outcome-nudge.interval-ms` (24 h) |
| `follow-up-due` | alerts for overdue follow-ups | `carenexus.jobs.follow-up-due.interval-ms` (24 h) |
| `prescription-expiry` | prescriptions past their end date become EXPIRED | `carenexus.jobs.prescription-expiry.interval-ms` (1 h) |
| `access-requests` | caregiver access requests left pending too long expire | `carenexus.jobs.access-requests.interval-ms` (24 h) |
| `access-expiry-notice` | notice before a caregiver grant expires | `carenexus.jobs.access-expiry-notice.interval-ms` (24 h) |
| `caregiver-access-expiry` | ends conversation memberships of expired caregiver grants | `carenexus.messaging.access-expiry-sweep-interval-ms` (60 s) |
| `data-export-expiry` | deletes personal data exports after their download window | `carenexus.jobs.data-export-expiry.interval-ms` (1 h) |
| `retention` | purges expired tokens and old operational rows (next section) | `carenexus.jobs.retention.interval-ms` (1 h) |
| `invoice-overdue` | unpaid invoices past due date plus grace become OVERDUE | `carenexus.jobs.invoice-overdue.interval-ms` (1 h) |
| `payment-reconcile` | pending payments are re-checked with the gateway | `carenexus.jobs.payment-reconcile.interval-ms` (5 min) |
| call worker / call sweeper | create and delete video rooms; end calls whose join window closed | `carenexus.calls.worker-interval-ms` (5 s), `carenexus.calls.sweeper-interval-ms` (60 s) |

Each job also has an `initial-delay-ms` where `application.yml` lists one. Environment overrides: `JOBS_<NAME>_INTERVAL_MS` (for example `JOBS_INVOICE_OVERDUE_INTERVAL_MS`).

## Retention policy

The `retention` job deletes, in batches, rows whose retention has run out. `audit_log`, clinical tables, users, consent records, legal acceptances, account requests, call sessions and participants, push devices and MFA secrets are never purged by it.

| Data | Table | Deleted when | Property (env, default) |
|---|---|---|---|
| Refresh tokens | `refresh_tokens` | expired or revoked more than the retention ago; the first row of a session that still has an active token is kept (it holds the sign-in time) | `carenexus.retention.tokens-days` (`RETENTION_TOKENS_DAYS`, 30) |
| Password reset tokens | `password_reset_tokens` | as soon as used or expired | - |
| Email verification tokens | `email_verification_tokens` | as soon as used or expired | - |
| Email change requests | `email_change_requests` | as soon as used or expired | - |
| MFA sign-in challenges | `mfa_challenges` | once expired | - |
| Idempotency keys | `idempotency_keys` | once expired (24 h after the request) | - |
| Notifications | `notifications` | read and created more than the retention ago, or once `expires_at` has passed (read or unread) | `carenexus.retention.read-notifications-days` (`RETENTION_READ_NOTIFICATIONS_DAYS`, 180) |
| Outbound messages | `outbound_messages` | SENT or SKIPPED and `sent_at` older than the retention (PENDING and FAILED rows are kept) | `carenexus.retention.outbound-messages-days` (`RETENTION_OUTBOUND_MESSAGES_DAYS`, 30) |
| Video call jobs | `call_jobs` | SUCCEEDED, FAILED or CANCELLED and completed more than the retention ago | `carenexus.retention.call-records-days` (`RETENTION_CALL_RECORDS_DAYS`, 30) |
| Provider webhook events | `provider_events` | received more than the retention ago | `carenexus.retention.call-records-days` (30) |
| Personal data export files | stored file of a READY export in `account_requests` (job `data-export-expiry`; the row is kept and becomes COMPLETED) | after the download window | `carenexus.retention.export-days` (`RETENTION_EXPORT_DAYS`, 7) |

The interval is `carenexus.jobs.retention.interval-ms` (`JOBS_RETENTION_INTERVAL_MS`, 1 hour).

## Observability

- Every response carries `X-Request-Id` (a well-formed incoming one is kept, otherwise a UUID is generated); error bodies carry the same value as `requestId`, and every log line shows `req=<id> user=<systemId>`. Ask users for the request id in support conversations.
- `/actuator/health` (plus `/liveness` and `/readiness` probes) is public and shows no details. `/actuator/prometheus` needs the scrape token `METRICS_SCRAPE_TOKEN` (32+ characters, sent by Prometheus as `Authorization: Bearer <token>`, e.g. with `authorization.credentials_file`) or an administrator token; set the scrape token in staging and production, since administrator tokens expire after 15 minutes. Application metrics: `carenexus.jobs.runs{job,outcome}`, `carenexus.jobs.duration{job}`, `carenexus.outbound.messages{channel,outcome}`, plus the JVM, HTTP server, Hikari and Tomcat meters.
- The API never logs tokens, passwords, secrets, Authorization headers, message bodies, clinical values or request bodies. Keep it that way in new code.
- Alert on API health, on `outcome=failure` job runs, on FAILED outbound messages, on payments stuck in PENDING and on call sessions stuck in `PREPARING` or `FAILED`.

---

## Documented decisions

These were decided during the completion plan and are deliberate; the code, tests and the documents in `docs/` follow them.

- **Registration returns no tokens.** `POST /api/auth/register` answers 201 with the account and no session (the PRD's US-01 said tokens are returned). Email verification and clinician approval depend on it: the user signs in after confirming the email, and a doctor after an administrator approves the licence. A duplicate email or phone answers 409.
- **Legal versions at registration are optional.** `acceptedTermsVersion` / `acceptedPrivacyVersion` may be omitted (the native register screen sends neither): the account then has no acceptance rows, `flags.legalActionRequired` is true, and the web sends the user to `/auth/accept-terms` after sign-in. A version that is not the current one is rejected with 400 `LEGAL_ACCEPTANCE_REQUIRED`. The seeded current version of both documents is `1`.
- **Clinician fields at registration are optional on the server.** The web form requires licence number and specialization; a DOCTOR registration without them (native) is stored as PENDING. Approval requires a licence number (409 `LICENCE_REQUIRED`); an administrator can enter or correct it (`PUT /api/admin/doctors/{systemId}/licence`) and approve from PENDING, REJECTED or SUSPENDED.
- **Guardian attestation.** `guardianAttested = true` is required when a caregiver creates a managed dependent (no email and no password). For a dependent created with sign-in details it is recorded when sent and not required, so the native add-dependent screen keeps working. The web form always asks for it. This is an attestation by the caregiver, not a verification of legal authority.
- **Web session storage is `sessionStorage`.** Tokens and session flags live in the tab's `sessionStorage` on web (cleared when the tab closes) and in the secure store on native.
- **Removed settings.** `profileVisibility`, `dataSharingEnabled`, `language`, `theme`, `weeklySummary` and `twoFactorEnabled` are no longer in the API or the UI because nothing enforced them (two-factor authentication has its own endpoints). Their columns stay in `user_settings`. Kept: every notification toggle (all enforced), channels, quiet hours and `timezone`.
- **Support conversations.** Conversation type `SUPPORT`: a patient, caregiver or doctor has at most one open support conversation; administrators answer from the admin **Messages** section. Administrators have messaging access to support conversations only, never to patient-doctor or care-team conversations. Details: [docs/communications/README.md](docs/communications/README.md).
- **Legacy unused tables.** `permissions`, `caregiver_alerts` and `caregiver_activity_log` (V1) stay unused, with `COMMENT ON TABLE` notes (V44): caregiver permissions live in `dependent_access`, caregiver alerts go through notifications, and the patient's view of caregiver activity is read from `audit_log`. `consent_records` is re-keyed and used as the consent ledger. The V1/V3 views `v_patient_dashboard` and `v_doctor_workload` are not used by the code.
- **Role changes are not supported.** The PRD persona line says the administrator "manages users, roles, facilities, and system configuration". Administrators manage users (create administrators, deactivate, reactivate, reset passwords, revoke sessions, approve clinicians), facilities and system settings, but cannot change an account's role: each role has its own profile table (`patients`, `doctors`, `caregivers`) with different required data, so a changed role would leave an account without a valid profile. A person who needs another role gets a second account.
- **Legal text is pending review.** The terms of service and privacy notice shipped in V29 are placeholder text marked "pending legal review". Administrators publish reviewed versions in the admin **Legal documents** section; users then accept the new version at their next sign-in.
- **One doctor-facing notification per critical result.** A critical lab or imaging result notifies the patient and caregivers with the lab-results permission (be-clinical-notifications). Doctors are reached only through the `CRITICAL_RESULT` clinical alert, one `AI_ALERT` notification per related doctor, never twice.
- **Medication reminders follow the dose schedule.** They are sent from due `medication_schedules` rows, so they always match what the patient sees; prescriptions create no reminder rows.
- **Health status is clinical.** Only a related doctor or an administrator sets a patient's health status; the login email changes only through the confirmed email-change flow.
- **No guarantees beyond the code.** No end-to-end encryption, regulatory certification, emergency monitoring or response-time claims (see [docs/communications/README.md](docs/communications/README.md), section 4).

---

## Secrets and rotation

Secrets come from the hosting platform's secret store, never from committed files. `.env` is git-ignored, and CI scans commits for leaked secrets.

| Secret | How to rotate | Effect |
|---|---|---|
| `JWT_SECRET` | Generate a new value, deploy. | Every issued access token becomes invalid. Users sign in again. |
| `AUTH_MFA_ENCRYPTION_KEY` | Not rotatable in place today: the key decrypts stored TOTP secrets. | A new key makes every enrolled authenticator unreadable; each user would have to re-enrol after an administrator resets their two-factor authentication (`POST /api/admin/users/{systemId}/mfa/reset`). |
| `BILLING_LOCAL_WEBHOOK_SECRET` | New value, deploy; callbacks signed with the old one are refused. | Local gateway only. |
| `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` | Create a second key pair in the Cloud project, point the webhook at the new key, deploy the API with the new pair, then delete the old key. | Room tokens last 5 minutes, so tokens signed with the old key age out quickly. Connected participants may need to rejoin once the old key is deleted. |
| Database password | Rotate in the database, update `SPRING_DATASOURCE_PASSWORD`, restart. | Short reconnect. |
| `CARENEXUS_BOOTSTRAP_ADMIN_PASSWORD` | Remove after the first start; the administrator changes the password after signing in. | - |

Rotate immediately if a secret appears in a log, a commit, a screenshot or a support ticket. The API never logs tokens, LiveKit credentials, webhook headers or message bodies.

### HTTPS and allowed origins

- Serve the web app and the API over HTTPS only (TLS at the load balancer or reverse proxy, HTTP redirected to HTTPS, HSTS set there). Browsers only allow camera and microphone access on secure origins.
- `CORS_ALLOWED_ORIGINS` lists the exact web origin(s). Add a staging origin only to the staging API.
- `EXPO_PUBLIC_API_URL` is baked into the web bundle at build time, so build the web image separately for each environment, and set `CSP_CONNECT_SRC` to the same API origin plus the LiveKit URLs.
- `LIVEKIT_CLIENT_URL` must be `wss://` in staging and production.
- Behind a proxy the API takes the client address from `X-Forwarded-For` only when the request comes from a trusted internal proxy address (`server.forward-headers-strategy: native`); rate limits key on that address.

### LiveKit Cloud webhook

1. In the LiveKit Cloud project for the environment, open **Settings → Webhooks** and add the endpoint `https://<api-host>/api/integrations/livekit/webhook`.
2. Choose the same API key the API uses (`LIVEKIT_API_KEY`). LiveKit signs each delivery with that key's secret, and the API verifies the signature against the raw body.
3. Set `LIVEKIT_WEBHOOK_ENABLED=true` and redeploy.
4. Check: an unsigned request (for example `curl -X POST https://<api-host>/api/integrations/livekit/webhook -d '{}'`) gets `401`, and a delivery from LiveKit gets `200 {}`. Duplicate deliveries are recorded once and otherwise ignored.

The endpoint is public (no CareNexus login). Its only protection is the signature check, so do not put it behind a proxy that rewrites the body.

### Usage alerts

Turn on usage and billing alerts in each LiveKit Cloud project (participant minutes and bandwidth), and alert on API health (`/actuator/health`) and on call sessions stuck in `PREPARING` or `FAILED`.
