# What remains

The web app and the API are feature-complete for launch scope. Every outside service sits behind an interface with a
working local implementation (README, "What is local and what needs a real provider"). What is left is listed here:
real provider adapters, infrastructure, legal and clinical review, and the native app phase. Nothing below needs a
schema change unless it says so; the next free migration is V71.

## 1. Real provider adapters

Each adapter is a new class implementing the interface, plus a branch or bean in the seam's configuration class with
its own provider id and properties. Until each one exists, a production start needs
`CARENEXUS_ALLOW_LOCAL_PROVIDERS=true` for that seam (or the seam set to `disabled` where that is allowed).

| Seam | Implement | Wire in | Notes |
|---|---|---|---|
| Email | `delivery.email.EmailSender` (SMTP or SES) | `delivery.config.DeliveryConfig` (`carenexus.delivery.email.provider`) | Templates in `resources/messages/*.txt` are plain text with a `Subject:` line; links use `CARENEXUS_WEB_BASE_URL`. Map provider errors to `DeliveryException(retryable)`. |
| SMS | `delivery.sms.SmsSender` (Twilio or Africa's Talking) | `DeliveryConfig` (`carenexus.delivery.sms.provider`) | Bodies never carry clinical values except dose reminders, which name the medication (not the dose); confirm that is acceptable for SMS. |
| Push | `delivery.push.PushSender` (Expo push or FCM) | `DeliveryConfig` (`carenexus.delivery.push.provider`) | Devices register with `POST /api/notifications/devices`; no client registers a token yet (native and web-push work). |
| Object storage | `storage.FileStorage` (S3-compatible, `signedUrl` support) | `storage.config.StorageConfig` (`carenexus.storage.provider`) | Until then use a persistent, backed-up `STORAGE_LOCAL_DIR`. |
| File scanning | `storage.FileScanner` (ClamAV or a cloud scanner) | `StorageConfig` (`carenexus.storage.scanner`) | Keep the magic-byte type allow-list in front of the virus scan. |
| Payment gateway | `billing.provider.PaymentGateway` (M-PESA Daraja, card processor) | `billing.config.BillingGatewayConfig` (`carenexus.billing.provider`) | Webhook route `POST /api/integrations/payments/{provider}/webhook` already verifies, dedupes and applies events; see docs/billing/CONTRACT.md section 12. |
| Insurance claims | `billing.provider.InsuranceClaimProvider` | `billing.config.BillingClaimsConfig` (`carenexus.billing.claims-provider`) | Today an administrator moves each claim. |
| Risk detection | `ai.provider.RiskDetectionProvider` (validated model) | `ai.detection.config.AiDetectionConfig` (`carenexus.ai.provider`) | `rules-v1` is deterministic; screenings run on a bounded pool with `AI_ASSESSMENT_TIMEOUT_MS`. |
| Medication interactions | `ai.interaction.MedicationInteractionChecker` (drug-interaction database) | `ai.generator.config.AiGeneratorConfig` (`carenexus.ai.interaction-provider`) | The static table and `disabled` both count as local, so production needs `CARENEXUS_ALLOW_LOCAL_PROVIDERS=true` until this exists. |
| Licence registry | `admin.clinician.provider.LicenseRegistryClient` (medical council registry) | `admin.clinician.config.LicenseRegistryConfig` (`carenexus.auth.licence-registry`) | Today every licence goes to manual review in the clinician queue. |
| Human verification | `auth.provider.HumanVerification` (captcha) | `auth.config.HumanVerificationConfig` (`carenexus.auth.human-verification`) | The API already accepts `humanVerificationToken` on registration and forgot-password; the web forms need the widget. |
| Wearables | `health.provider.WearableConnector` | `health.config.HealthConfig` (`carenexus.health.wearables.provider`) | Readings must enter through `HealthMetricIngestService.ingest(...)`. |
| Terminology | `terminology.provider.TerminologyProvider` (full ICD-10 service) | `terminology.config.TerminologyConfig` | Optional: the bundled subset is accepted in production; codes outside it are stored as unverified. |
| Rate-limit store | `common.ratelimit.RateLimiter` (Redis) | `common.ratelimit.RateLimitConfig` (`carenexus.rate-limit.store`) | Needed once more than one API instance runs; the memory store counts per instance. |

## 2. Infrastructure

- Production hosting for the API (no Spring profile, `CARENEXUS_ENVIRONMENT=production`), managed PostgreSQL 16 with
  TLS, automated backups and a tested restore, and a secrets store for `JWT_SECRET`, `AUTH_MFA_ENCRYPTION_KEY`, the
  database password, the LiveKit keys and the provider credentials (README, "What staging and production must set").
- Web hosting: build `frontend/Dockerfile` per environment (`EXPO_PUBLIC_API_URL`) or put the export on a CDN with
  the same SPA fallback and headers (`frontend/docker/`). TLS and HSTS at the edge. Replace `'unsafe-inline'` in the
  CSP `script-src` with hashes once the export is pinned.
- LiveKit Cloud projects for staging and production, the signed webhook, usage alerts, and the Cloud staging proofs
  listed in docs/communications/README.md section 3 (removal during a live call, revocation cutoff, removal failure
  handling, signed webhooks, cleanup jobs, usage alerts).
- Monitoring: scrape `/actuator/prometheus` with an administrator token; alert on health, `carenexus.jobs.runs`
  failures, FAILED outbound messages, payments stuck in PENDING and call sessions stuck in PREPARING or FAILED; ship
  logs (they carry `req=` and `user=`).
- A shared rate-limit store and object storage before running more than one API instance (section 1).
- The docker stack keeps uploaded files in the API container's temporary directory; a named volume needs the API
  image to create the storage directory owned by its runtime user.
- `AUTH_MFA_ENCRYPTION_KEY` cannot be rotated in place (no re-encryption tool); plan one before the first rotation.
- CI: add a `GITLEAKS_LICENSE` secret if the repository moves to an organization; the end-to-end jobs need
  `RATE_LIMIT_ENABLED=false` in their generated `.env`.
- First production start: create the first administrator with `CARENEXUS_BOOTSTRAP_ADMIN_*`, then remove the
  variables and enrol two-factor authentication.

## 3. Legal and clinical review

- Terms of service and privacy notice: the V29 texts are placeholders marked "pending legal review". Publish
  reviewed versions in the admin **Legal documents** section; users accept them at their next web sign-in.
- Regulatory assessment for each launch market (data protection, health records, telemedicine). Nothing is
  certified; docs, UI copy and API messages must not claim compliance, end-to-end encryption, emergency monitoring
  or response times.
- Retention periods (README, "Retention policy") and the data export and account closure flows: confirm with the
  legal review; clinical records and the audit log are kept indefinitely today.
- Caregivers attest guardianship of managed dependents; nobody verifies legal authority. Decide whether launch
  needs verification.
- Clinical safety review of the rule-based parts: vital-sign reference ranges (`carenexus.health.ranges.*`), the
  `rules-v1` risk rules, the static medication-interaction table, the anomaly and missed-dose thresholds, and the
  ICD-10 subset.
- Decided and not planned: changing an account's role (README, "Documented decisions").

## 4. Native app phase

The plan changed the web app and the API only. Native screens (`frontend/src/components/mobile`,
`frontend/src/app/**/mobile`) keep compiling and the API keeps serving the requests they send, but they do not
use the new flows. Each known native runtime gap is listed with the deferred review findings
(docs/reviews/PR-3-6-REVIEW.md and the plan's deferred mobile findings) that touch the same screens, so one native
pass can close both.

### 4.1 Sign-in, registration and account

| Native gap | Deferred findings on the same screens |
|---|---|
| No accept-terms screen. Native registration (`app/auth/register/mobile/register-mobile.tsx`) sends no legal versions; the server accepts that and sets `legalActionRequired`, but native ignores the flag, so a native-only user never accepts the terms. | - |
| No verify-email screen. With `AUTH_REQUIRE_VERIFIED_EMAIL=true` (default outside development) native sign-in shows the server's `EMAIL_NOT_VERIFIED` message as an error and offers no resend. | - |
| No forced-password-change screen. `mustChangePassword` (administrator-created accounts, invited managed dependents) is ignored on native. | - |
| No MFA challenge or enrolment step. `login-mobile.tsx` stores tokens on every 200, so an account whose login returns an `MFA` or `MFA_SETUP` challenge sees "Login failed: no user data returned" and cannot sign in on native (administrators by default; any role listed in `AUTH_MFA_REQUIRED_ROLES`). | - |
| Doctor registration sends no licence fields. The doctor stays PENDING until an administrator enters the licence in the clinician queue. | PR 3 item 8, `doctor-mobile-patients.tsx:204`: "N visits" and "Last" count every appointment (cancelled and future included) and sort by it; the backend now returns correct completed counts, the native labels still use the old fields. |
| Administrators are signed out on native with "Administration is available on the web". | - |
| Native logins send no device fields, so they never raise the "New sign-in" alert. | - |

### 4.2 Caregiver (proxy) screens

| Native gap | Deferred findings on the same screens |
|---|---|
| Add-dependent sends no `guardianAttested`. Dependents with sign-in details are still created; a managed dependent without credentials cannot be created from native (the server requires the attestation). | PR 3 item 14, `patient-proxy-mobile-add-dependent.tsx:328`: Copy password leaves the new password on the clipboard (not marked sensitive, never cleared, no feedback on failure). Deferred finding `patient-proxy-mobile-add-dependent.tsx:437`: an ambiguous failure on create; the API accepts `Idempotency-Key` on `POST /api/caregiver/dependents`, native does not send one. PR 3 item 13, `patient-proxy-dashboard-mobile.tsx:55`: Android hardware Back on step 2 or 3 of Add Loved One (or in a profile editor) discards everything typed. |
| The health-profile save in `patient-proxy-mobile-profile.tsx:464-467` sends `healthStatus`, which only a related doctor or an administrator may set, so that save now answers 400. The personal-details save still works. | PR 3 item 15, `patient-proxy-mobile-profile.tsx:443`: a cleared phone is reported as saved although the server keeps the old number, and a failed follow-up GET reports a failed save. `:458`: gender and blood type are mandatory in the caregiver health editor. `:492`: a caregiver can cut their own access without confirmation. `:800` (PR 3 and PR 4 item 4.8): the access grid ignores FULL_ACCESS and shows raw flags (the backend now normalises flags on save). |
| Medications and Documents & Results read the legacy array routes (`GET /api/prescriptions/patient/{id}`, `GET /api/lab-results/patient/{id}`), capped at 200 rows, newest first. | `patient-proxy-mobile-medications.tsx:230`: a dependent whose load failed is shown as having no medications. |
| Booking uses a free-form time picker. Once a doctor saves weekly hours or time off, out-of-hours bookings answer 400 `DOCTOR_UNAVAILABLE` with a readable message instead of offering slots. | - |
| Billing, reminders, care-plan editing, documents upload, referrals, health tracking entry, caregiver access requests and the consent history are web only. | - |

### 4.3 Patient screens

| Native gap | Deferred findings on the same screens |
|---|---|
| Lab results, imaging results, medical records and consultation history receive the newest 100 rows (one page of the canonical endpoint) and have no "Load more". | `use-doctor-lab-results.ts:72` / PR 3 item 1 (patient and doctor lab screens show "Normal" for a missing flag, see 4.4). |
| Appointment status reads the first 100 appointments; booking uses a free time picker without slots (out-of-hours bookings answer 400 once the doctor has weekly hours). | `patient-mobile-appointment-status.tsx:509`: a failed refresh after a list has loaded is silent. `:352`: `rescheduledId` is never cleared. |
| Prescriptions badge on the stored status. | `patient-mobile-prescription.tsx:61`: an expired prescription is shown as Active (the API now returns `effectiveStatus` and expires prescriptions hourly). |
| Billing, reminders, documents, referrals, care plans, health tracking, health alerts, caregiver access requests and data rights are web only. | - |

### 4.4 Doctor screens

| Native gap | Deferred findings on the same screens |
|---|---|
| `doctor-mobile-appointment.tsx` and `doctor-mobile-schedule.tsx` build their Today / This week / Upcoming filters from the device clock (the shared action helpers they call are now clinic time). | `doctor-main-dashboard-area.tsx:238`: the Upcoming stat is truncated to 10 (the API returns the real count). |
| The patient list reads `GET /api/doctors/patients` (legacy array, capped at 200). | PR 3 item 8 (see 4.1). |
| Consultations and prescriptions show only the newest 100 rows; the shared hooks offer `hasMore` / `loadMore` and `effective_status`, which the native screens do not use, so prescriptions badge on the stored status. | - |
| `doctor-mobile-lab-results.tsx` `flagColor()` falls through to a green "Normal" pill for a null or unknown flag (lines 62-67, used at 466), and the abnormal counts at lines 133 and 329 now include flag-less sub-tests. Render no pill for null, green only for `NORMAL`, red or amber with the flag text otherwise, and count with `isAbnormalLabFlag`. | `use-doctor-lab-results.ts:72` (PR 3 item 1). |
| Availability, time off, referrals, care plans, documents, billing, clinical alerts review and corrections are web only. | - |

### 4.5 Messaging and notifications

| Native gap | Deferred findings on the same screens |
|---|---|
| Support conversations are not offered: native cannot start one. One started on the web appears in the native list and opens, but the thread list and view print `null` where the patient name would be. | PR 4 item 4.3, native call site `mobile-messages-screen.tsx:49`: `const myRole = user?.role ?? mode;` can become `const myRole = mode;` (MemberManager now normalises the role itself, so the Leave button already works; this is cleanup). |
| `POST /api/communications/threads` and `.../messages` can answer 429 `RATE_LIMITED`; validation messages now read `<field>: <text>`. Native shows them as plain errors. | PR 5 item 5.1, `storage.native.ts:379`: on Android the draft flush timer does not fire in the background, so a draft typed just before the app is paused can be lost. PR 5 item 5.2, `storage.native.ts:104`: orphan chunk deletion stops at 64 chunks, so a very large uncommitted outbox value can leave message text in the keychain. |
| `mobile-notifications-screen.tsx` passes neither `onNavigate` nor `onActivated`, so every notification except messages and caregiver access is informational on native (marked read, no navigation). Native needs an equivalent of the web `followActionUrl`. A caregiver-access notification opens My Proxies without the request id or tab. | - |
| No push token is registered, so push delivery has no devices (see section 1). | - |

### 4.6 Not built on native

Billing and payments, reminders, administration, support conversations, documents, referrals, care plans (editing),
health tracking entry, clinical alerts, corrections, availability and data rights have no native screens. Video
calls are web only by design.
