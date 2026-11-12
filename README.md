# CareNexus

CareNexus combines patient and doctor care workflows (Care Direct) with a caregiver/proxy experience (Care Proxy). This repository contains an Expo Router + react-native-web frontend (`frontend/`), a Spring Boot 3 / Java 17 API (`backend/carenexus-api/`) and its PostgreSQL Flyway migrations.

The web app is the primary client. Native mobile builds exist, but video calling is web only.

- Messaging and video calls: [docs/communications/README.md](docs/communications/README.md) (scope, release gates, limits) and [docs/communications/CONTRACT.md](docs/communications/CONTRACT.md) (API, data model, statuses).
- Product reference material: [docs](docs).

---

## Local development

Requirements: Docker (with Compose v2), JDK 17 (only to run the API outside Docker), Node.js 22 LTS with npm.

### 1. Environment files

```bash
cp .env.example .env                                   # used by docker compose (git-ignored)
cp frontend/.env.example frontend/.env.development.local   # optional, only to override the API URL
```

Every committed value is a public, local-development placeholder. The API refuses the dev JWT secret and the LiveKit dev key pair outside the `local`/`docker` profiles.

| Variable | Where | Local default | Purpose |
|---|---|---|---|
| `JWT_SECRET` | `.env` | dev placeholder in `.env.example` | HS512 signing secret, at least 64 bytes. Compose refuses to start without it. |
| `JWT_EXPIRATION` | `.env` | `900000` | Access token lifetime in ms. |
| `POSTGRES_PASSWORD` | `.env` | `local_dev_password` | Password of the compose database. |
| `CORS_ALLOWED_ORIGINS` | `.env` | `http://localhost:8081,http://localhost:19006` | Browser origins allowed to call the API. No wildcard. |
| `LIVEKIT_URL` | `.env` (optional) | `http://livekit:7880` (docker) / `http://localhost:7880` (local) | Server-side LiveKit API URL, used by the API only. |
| `LIVEKIT_CLIENT_URL` | `.env` (optional) | `ws://localhost:7880` | LiveKit URL returned to browsers as `serverUrl`. |
| `LIVEKIT_API_KEY` | `.env` (optional) | `devkey` | LiveKit API key. |
| `LIVEKIT_API_SECRET` | `.env` (optional) | `secret` | LiveKit API secret. |
| `SWAGGER_ENABLED` | `.env` (optional) | `false` (docker) / on in the `local` profile | Serves Swagger UI (`/swagger-ui.html`) and `/v3/api-docs`. Off unless set to `true`; only enable it on a trusted development machine. |
| `MESSAGING_ACCESS_EXPIRY_SWEEP_MS` | environment (optional) | `60000` | How often expired caregiver grants are swept (their conversation memberships end with `ACCESS_EXPIRED`). |
| `EXPO_PUBLIC_API_URL` | `frontend/.env.*` | `http://localhost:9000` | API base URL, inlined into the web bundle at build time. |

The LiveKit values only need to be set to override the defaults that the `local` and `docker` Spring profiles provide. `LIVEKIT_ENABLED`, `LIVEKIT_WEBHOOK_ENABLED` and `CALLS_WORKER_ENABLED` are set by those profiles and only need to be set in staging and production (see below).

### 2. Start the stack

Everything in Docker (PostgreSQL, LiveKit in dev mode, API with the `docker` profile and demo seed data):

```bash
docker compose up -d --build postgres-db livekit carenexus-api
# or: scripts/dev-up.sh
```

Or run the API on the host (faster rebuilds), with PostgreSQL and LiveKit in Docker:

```bash
docker compose up -d postgres-db livekit
cd backend/carenexus-api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
# or: scripts/dev-up.sh --api-local
```

Then start the web app:

```bash
cd frontend && npm install && npx expo start --web
```

| Service | URL |
|---|---|
| Web app | http://localhost:8081 |
| API | http://localhost:9000 (health: `/actuator/health`) |
| LiveKit (dev) | ws://localhost:7880 (TCP 7881, UDP 7882) |
| PostgreSQL | localhost:5432, database `carenexus_db`, user `carenexus` |

Stop with `docker compose down` (add `-v` to also delete the database volume).

Demo data: the `local`/`docker` profiles also load the dev-only seed in `backend/carenexus-api/src/main/resources/db/seed`, including V20, which pre-approves messaging and calls for the demo caregiver Sarah Dube acting for Thomas Dube, and V21, which gives Thomas Dube a working dev password. Seed data never runs in staging or production.

Demo logins for messaging and video calls (published dev-only passwords, documented in the seed files):

| Role (login tab) | Email | Password |
|---|---|---|
| Patient | `thomas.dube@carenexus.com` | `patient@thomas` |
| Doctor | `james.banda@carenexus.com` | `doctor@banda` |
| Proxy / Carer | `sarah.dube@carenexus.com` | `proxy@sarah` |

Note: V21 is a dev seed, so the next schema migration in `db/migration` is V22.

LiveKit in dev mode uses the public `devkey`/`secret` pair and signs webhooks to `http://carenexus-api:9000` and `http://host.docker.internal:9000`, so the webhook reaches the API in either setup. Camera and microphone access in the browser needs a secure context, and `http://localhost` counts as one. Opening the app from another device on your network needs HTTPS.

### 3. Checks

```bash
cd backend/carenexus-api && ./mvnw verify   # unit + integration tests (Testcontainers, needs Docker)
cd frontend && npx tsc --noEmit             # type check
```

### 4. Browser end-to-end tests

Playwright tests in `frontend/e2e/` drive the real stack in Chromium with a fake camera and microphone: messaging between patient and doctor (polling, unread badges, urgent, retry without duplicates), the care-team history boundary, a full video call (start, admit, live video, leave, end), revoking a caregiver mid-call, camera-off and camera-blocked joins, and accessibility (axe WCAG 2.1 A/AA with no serious or critical violations, keyboard-only sending, no horizontal scrolling at 375 px).

```bash
docker compose up -d --build postgres-db livekit carenexus-api   # API :9000, LiveKit :7880 (needs JWT_SECRET, see step 1)
cd frontend
npm install
npx playwright install chromium   # once; installs Chromium and its headless shell
npm run e2e:build                 # static web export into dist/ (EXPO_PUBLIC_API_URL=http://localhost:9000)
npm run e2e                       # serves dist/ on :8081 if nothing listens there, then runs the tests
```

- The tests use the demo logins above and create their own data (tagged `e2e-...`; VIDEO appointments use the reason "E2E video call test"). Before and after every run, Playwright's globalSetup/globalTeardown run `scripts/dev-reset-e2e-data.sh`. It deletes all e2e test data from the local dev database: e2e appointments with their call sessions, jobs and LiveKit events; e2e conversations, messages and notifications; and the throwaway `e2e-...@carenexus.test` accounts with their grants. Seed data is never touched. Set `E2E_CLEANUP=0` to keep the data for inspection. Run it by hand with `npm run e2e:clean` (preview with `npm run e2e:clean -- --dry-run`). It only targets the local `carenexus-postgres` container, or a Postgres on localhost via `E2E_PG_HOST`, and it refuses databases without the dev seed. When Docker is not reachable the cleanup is skipped with a warning. The tests restore Sarah's messaging and call approval when they finish. Run them against a local dev database only.
- They run one at a time (shared demo accounts) and take about two minutes. The HTML report is written to `frontend/e2e-report`, traces of failures to `frontend/e2e-results`.
- `npm run e2e:serve` serves the exported app on http://localhost:8081 for a manual demo (CORS already allows that origin).
- `E2E_WEB_URL` and `E2E_API_URL` point the tests at other ports.
- Smoke suite (`e2e/smoke-*.spec.ts`, helpers in `e2e/support/smoke.ts`): for each role it signs in through the login page, opens every sidebar section, checks "Coming soon" items are disabled, the profile menu, reload and the refresh-token path, sign out and the route guards, and a 375 px pass. It also covers the sign-in errors (wrong password, wrong role) and the registration page (validation, duplicate email, new account then sign in); books, reschedules and cancels appointments through the UI, and has the doctor mark started appointments completed / no-show; checks the clinical screens against the seed data (Thomas, plus Linda Moyo for records and labs, and the caregiver's documents and appointments); grants/changes/revokes caregiver access, adds a new loved one through the 3-step form, saves caregiver notification settings; and covers notifications (including Mark all as read), messaging and the dashboard Join button. Every screen fails on page errors, console errors, API responses >= 500 or unexpected 4xx, and text artifacts such as `undefined` or `NaN`. Smoke data is tagged `e2e-smoke-...` (the global cleanup deletes it after the run, including the throwaway `e2e-smoke-...@carenexus.test` accounts each run registers). The complete/no-show test moves an appointment into the past with SQL in the local `carenexus-postgres` container (`e2e/support/db.ts`; override with `E2E_PG_CONTAINER`, `E2E_PG_USER`, `E2E_PG_DB`), so Docker must be reachable.
- Real Google Chrome instead of the bundled Chromium: `npm run e2e:chrome` (`E2E_CHANNEL=chrome playwright test --project=chrome`). The `chrome` project exists only when `E2E_CHANNEL=chrome`, so `npm run e2e` is unchanged.
- CI: the `e2e` job in `.github/workflows/ci.yml` runs on every pull request and on manual runs (Actions > CI > Run workflow). It uses the bundled Chromium (`--project=chromium`) with secrets generated per run; locally, `npm run e2e:chrome` uses installed Google Chrome.

CI (`.github/workflows/ci.yml`) runs the checks of step 3 on every push and pull request. It also runs `npm audit` (critical advisories fail the build, high advisories are reported only) and a gitleaks secrets scan (`.github/gitleaks.toml` allow-lists only the committed dev placeholders). If the repository belongs to a GitHub organization, add a `GITLEAKS_LICENSE` repository secret for the gitleaks action.

---

## Environments

| | Local development | Staging | Production |
|---|---|---|---|
| Spring profile | `local` (API on host) or `docker` (compose) | none of `local`/`docker`/`test` | none of `local`/`docker`/`test` |
| Video provider | LiveKit server container, `--dev` mode | LiveKit Cloud project (staging) | LiveKit Cloud project (production) |
| Seed data | yes (`db/seed`) | no | no |
| Transport | `http://localhost` | HTTPS only | HTTPS only |

Use a **separate LiveKit Cloud project for each environment**, each with its own API key pair and webhook. Never reuse staging keys in production.

Staging and production must set:

| Variable | Example / rule |
|---|---|
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Managed PostgreSQL over TLS. |
| `JWT_SECRET` | Fresh random value, at least 64 bytes (`openssl rand -base64 64`). |
| `JWT_EXPIRATION` | `900000` (15 minutes) unless there is a reason to change it. |
| `CORS_ALLOWED_ORIGINS` | The exact HTTPS web origin(s), e.g. `https://app.example.com`. No wildcard, no `http://`. |
| `LIVEKIT_ENABLED` | `true` |
| `LIVEKIT_URL` | Server API URL of the Cloud project, e.g. `https://<project>.livekit.cloud`. |
| `LIVEKIT_CLIENT_URL` | Browser URL of the same project, e.g. `wss://<project>.livekit.cloud`. |
| `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` | Key pair created in that Cloud project. |
| `LIVEKIT_WEBHOOK_ENABLED` | `true` once the webhook below is configured. |
| `CALLS_WORKER_ENABLED` | `true` (default). The worker that creates and cleans up rooms; set `false` only on extra replicas if you must limit it. |
| `EXPO_PUBLIC_API_URL` (web build) | The public HTTPS API origin, e.g. `https://api.example.com`. |
| `SWAGGER_ENABLED` | Leave unset (`false`). The API documentation is never public in staging or production. |

Startup fails when LiveKit is enabled outside `local`/`docker`/`test` with the dev secret `secret` or a secret shorter than 32 characters. If LiveKit is not configured at all, the API still starts; calls report "temporarily unavailable" and the webhook returns 404.

### Secrets and rotation

Secrets come from the hosting platform's secret store, never from committed files. `.env` is git-ignored, and CI scans commits for leaked secrets.

| Secret | How to rotate | Effect |
|---|---|---|
| `JWT_SECRET` | Generate a new value, deploy. | Every issued access token becomes invalid. Users sign in again. |
| `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` | Create a second key pair in the Cloud project, point the webhook at the new key, deploy the API with the new pair, then delete the old key. | Room tokens last 5 minutes, so tokens signed with the old key age out quickly. Connected participants may need to rejoin once the old key is deleted. |
| Database password | Rotate in the database, update `SPRING_DATASOURCE_PASSWORD`, restart. | Short reconnect. |

Rotate immediately if a secret appears in a log, a commit, a screenshot or a support ticket. The API never logs tokens, LiveKit credentials, webhook headers or message bodies. Keep it that way in any new code.

### HTTPS and allowed origins

- Serve the web app and the API over HTTPS only (TLS at the load balancer or reverse proxy, HTTP redirected to HTTPS). Browsers only allow camera and microphone access on secure origins.
- `CORS_ALLOWED_ORIGINS` lists the exact web origin(s). Add a staging origin only to the staging API.
- `EXPO_PUBLIC_API_URL` is baked into the web bundle at build time, so build the web app separately for each environment.
- `LIVEKIT_CLIENT_URL` must be `wss://` in staging and production.

### LiveKit Cloud webhook

1. In the LiveKit Cloud project for the environment, open **Settings → Webhooks** and add the endpoint `https://<api-host>/api/integrations/livekit/webhook`.
2. Choose the same API key the API uses (`LIVEKIT_API_KEY`). LiveKit signs each delivery with that key's secret, and the API verifies the signature against the raw body.
3. Set `LIVEKIT_WEBHOOK_ENABLED=true` and redeploy.
4. Check: an unsigned request (for example `curl -X POST https://<api-host>/api/integrations/livekit/webhook -d '{}'`) gets `401`, and a delivery from LiveKit gets `200 {}`. Duplicate deliveries are recorded once and otherwise ignored.

The endpoint is public (no CareNexus login). Its only protection is the signature check, so do not put it behind a proxy that rewrites the body.

### Usage alerts

Turn on usage and billing alerts in each LiveKit Cloud project (participant minutes and bandwidth), and alert on API health (`/actuator/health`) and on call sessions stuck in `PREPARING` or `FAILED`.
