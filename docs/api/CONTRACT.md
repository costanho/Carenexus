# CareNexus API contract: conventions

The rules every endpoint of the CareNexus API follows: identity, route security, error envelope and codes, pagination,
idempotency, rate limits, domain events, notifications and their deep links, and the web section keys those links
point at. They were fixed in the completion plan's shared contracts and are written here as the code implements them.
Where this file and the code disagree, the code wins and this file is the one to fix.

Per-endpoint shapes (paths, fields, status codes, error texts) are in the OpenAPI document: Swagger UI at
`/swagger-ui.html` and `/v3/api-docs` on a development API (`local` profile, or `SWAGGER_ENABLED=true`). Every
operation has a summary, a description saying who may call it and its side effects, and its documented error
responses use the `ErrorResponse` schema (a release-gate test, `OpenApiCoverageTest`, fails the build otherwise).
Domain contracts with more detail: [billing](../billing/CONTRACT.md), [messaging, support and video
calls](../communications/README.md). Data model notes: [DATA-MODEL-NOTES.md](../architecture/DATA-MODEL-NOTES.md).

## 1. Basics

- Base path `/api`, JSON in and out (camelCase field names), UTF-8. Uploads are `multipart/form-data`; downloads
  answer `Content-Disposition: attachment` with `X-Content-Type-Options: nosniff`.
- Authentication: `Authorization: Bearer <access token>` (HS512 JWT, 15 minutes by default). Identity always comes
  from the token: a `patientSystemId` or `doctorSystemId` in a body that names the caller is ignored or must equal
  the caller.
- People are addressed by system id: `PAT-00001`, `DOC-00001`, `CGV-00001`, `ADM-00001` (pattern
  `^(PAT|DOC|CGV|ADM)-\d{5,}$`). Rows are addressed by integer id.
- Time: appointment, schedule and availability times are clinic wall-clock `LocalDateTime` values without an offset
  (`2026-11-20T10:15:00`) in `carenexus.scheduling.time-zone` (default `Africa/Harare`). Audit, token, outbox,
  messaging and call timestamps are ISO instants in UTC (`2026-11-20T08:15:00Z`). Dates are `yyyy-MM-dd`; dose and
  availability times `HH:mm`.
- Money: JSON number with two decimals (`BigDecimal`, HALF_UP), currency an ISO 4217 code (default `KES`).
- Status changes go through an explicit transition table per resource: an illegal transition is 409, a change to the
  current status is a no-op that returns the current state. Clinical rows are never hard-deleted (status `CANCELLED`
  or `is_deleted`).
- Every response carries `X-Request-Id`. A client may send its own (8-64 characters of `[A-Za-z0-9._:-]`); otherwise
  the server generates one. CORS allows the headers `Authorization`, `Content-Type`, `Accept`, `X-Requested-With`,
  `Idempotency-Key`, `X-Request-Id` and exposes `Content-Disposition`, `Retry-After`, `X-Request-Id`. Responses carry
  `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` and, over HTTPS, HSTS.

### 1.1 Status codes

| Code | Used for |
|---|---|
| 200 | read, update, an idempotent create that was replayed, a find-or-create that found the row |
| 201 | create, with the created resource in the body |
| 202 | a request that must not reveal whether its target exists: a caregiver's access request to a patient email (`POST /api/caregiver/access-requests`). Forgot-password and resend-verification answer 200 with the same generic message whether or not the account exists |
| 204 | delete or acknowledge without a body |
| 400 | invalid input (Bean Validation, malformed JSON, wrong parameter type, business rule on the input) |
| 401 | missing, invalid or expired token; failed sign-in; bad webhook signature |
| 403 | authenticated but not allowed (role, relationship, caregiver permission, account state) |
| 404 | unknown id, and rows the caller may not know exist |
| 409 | duplicate, illegal transition, conflicting state, idempotent request still in progress |
| 413 | upload larger than `UPLOAD_MAX_FILE_SIZE` (10 MB) |
| 429 | rate limit, with `Retry-After` |
| 503 | an external provider is unavailable (`PROVIDER_UNAVAILABLE`) |

## 2. Route security

First match wins (`SecurityConfig`); services then check ownership, relationship and caregiver permission on
every request.

| Route | Who |
|---|---|
| `POST /api/auth/{login,refresh,register,logout}`, `POST /api/auth/password/{forgot,reset}`, `POST /api/auth/email/{verify,resend}`, `POST /api/auth/email-change/confirm`, `POST /api/auth/mfa/**`, `GET /api/legal/**`, `/actuator/health/**`, `/actuator/info` | public (each one-time-token or challenge endpoint authenticates the token it receives) |
| `POST /api/integrations/livekit/webhook`, `POST /api/integrations/payments/*/webhook` | public, signature over the raw body |
| any other `/api/integrations/**` | denied |
| `/actuator/prometheus` | ADMIN |
| `/api/admin/**` | ADMIN |
| `GET /api/doctors/profile/directory/**`, `GET /api/doctors/profile/public/**`, `GET /api/doctors/profile/list/active` | any signed-in user |
| any other `/api/doctors/**` | DOCTOR or ADMIN (nothing a patient or caregiver calls lives under `/api/doctors`) |
| `/api/caregiver/**` | CAREGIVER |
| `/api/patients/me/caregiver-access/**`, `/api/patients/me/caregivers/**` | PATIENT or ADMIN |
| everything else | signed in; the service decides |

Patient data is authorised by one call per service method (`AuthorizationService.requirePatientAccess(patient,
permission)`): the patient, a related doctor (care team, appointments, consultations), a caregiver whose active and
unexpired grant has the permission (FULL_ACCESS grants every flag, VIEW_ONLY blocks writes), or an administrator.
Unconfirmed AI detections are never returned to patients or caregivers. Denials are audited.

## 3. Errors

Every non-2xx response, including the security filter's 401/403, uses one envelope (`ErrorResponse`). Optional fields
are omitted when null.

```json
{ "timestamp": "2026-11-10T09:00:00Z", "status": 400, "error": "Bad Request",
  "message": "scheduledAt: must be in the future", "path": "/api/appointments",
  "code": "VALIDATION_FAILED", "errors": [ { "field": "scheduledAt", "message": "must be in the future" } ],
  "requestId": "3f1c9a6e-0b6d-4a4e-9a51-2c1f0d6b7e10" }
```

- `message` is one human sentence under 120 characters that clients show as is. For validation it is
  `<first field>: <message>` and `errors` lists every invalid field.
- `code` is present only where clients branch on the reason.
- `requestId` equals the `X-Request-Id` response header; every server log line of that request shows `req=<id>`.
- `resourceType` / `resourceId` appear on some authorization denials.
- 5xx bodies never echo exception text.
- The LiveKit webhook is the one exception: it answers a bare `{"message": ...}` body (`OpenApiCoverageTest`
  allow-lists it).

### 3.1 Error codes

| Code | Status | Meaning |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Bean Validation or an input rule; see `errors` |
| `RATE_LIMITED` | 429 | rate-limit bucket exhausted; `Retry-After` header |
| `EMAIL_NOT_VERIFIED` | 403 | sign-in refused until the email address is confirmed (`AUTH_REQUIRE_VERIFIED_EMAIL`) |
| `ACCOUNT_PENDING_VERIFICATION` | 403 | doctor sign-in while the licence awaits an administrator |
| `ACCOUNT_REJECTED` | 403 | doctor sign-in after a rejection |
| `ACCOUNT_CLOSED` | 403 / 409 | sign-in to a closed account (403); administrator action on a closed account (409) |
| `MFA_REQUIRED` | 409 | turning off two-factor authentication that the role requires |
| `MFA_CODE_INVALID` | 400 | wrong or already used TOTP or recovery code |
| `MFA_CHALLENGE_INVALID` | 401 | sign-in challenge token unknown, expired, used, locked or of the wrong type |
| `LEGAL_ACCEPTANCE_REQUIRED` | 400 | a legal document version that is not the current one |
| `LICENCE_REQUIRED` | 409 | approving a doctor without a licence number |
| `DOCTOR_NOT_VERIFIED` | 409 | activating a doctor whose licence is not approved |
| `DOCTOR_UNAVAILABLE` | 400 | booking outside the doctor's weekly hours or during time off |
| `CONTACT_PHONE_REQUIRED` | 400 | phone appointment without a contact phone |
| `TOKEN_INVALID` | 400 | email verification or email change link unknown, used, superseded or expired |
| `PASSWORD_RESET_TOKEN_INVALID` | 400 | password reset link unknown, used, superseded or expired |
| `CURRENT_PASSWORD_INCORRECT` | 400 | password change with a wrong current password |
| `PASSWORD_UNCHANGED` | 400 | new password equals the current one |
| `INVALID_CURRENT_PASSWORD` | 400 | email change with a wrong current password |
| `EMAIL_UNCHANGED` | 400 | email change to the current address |
| `PASSWORD_INCORRECT` | 400 | data export or account closure with a wrong password |
| `HUMAN_VERIFICATION_FAILED` | 400 | the human-verification provider refused the request (never with the shipped `none` provider) |
| `FILE_REJECTED` | 400 | upload refused by the file scanner (type not allowed, test virus signature) |
| `VIDEO_UNAVAILABLE` | 409 | the video provider is not configured or not reachable for this call |
| `IDEMPOTENCY_IN_PROGRESS` | 409 | the same `Idempotency-Key` is still being processed |
| `PROVIDER_UNAVAILABLE` | 503 | an external provider (payment gateway, claims provider) is unavailable |

`MFA_SETUP_REQUIRED` is declared but not returned: a sign-in that needs enrolment answers 200 with a `challenge` of type
`MFA_SETUP` (section 6).

## 4. Pagination

Every list endpoint added by the completion plan returns one envelope, `PageResponse<T>`:

```json
{ "content": [ ... ], "page": 0, "size": 20, "totalElements": 57, "totalPages": 3 }
```

- Parameters `page` (0-based; negative becomes 0) and `size` (default 20, clamped to 1..100, never rejected). A few
  endpoints document a smaller maximum (the thread list 1..50).
- Filtering and ordering happen in the database, newest first unless the endpoint says otherwise, with a
  deterministic tie-break on the id.
- Naturally small lists stay plain arrays with a documented hard cap: emergency contacts, a patient's caregiver
  grants, payment methods, a day's slots, weekly availability, `GET /api/doctors/patients/options` (500).
- Canonical paged lists: `GET /api/appointments`, `/api/consultations`, `/api/prescriptions`, `/api/lab-results`,
  `/api/imaging-results`, `/api/medical-records` (each with `patientSystemId` and `doctorSystemId` filters), plus
  `GET /api/doctors/patients/search` (with `summary`), `GET /api/doctors/schedule/window?from=&to=`,
  `GET /api/doctors/appointments/search`.

### 4.1 Legacy array routes

A path that existed before the plan keeps its response shape. Array lists are capped at 200 rows, newest first, and
marked deprecated (`@Deprecated`, `deprecated: true` in OpenAPI) while a file under `frontend/src` (native screens
included) or `frontend/e2e` still calls them; a route is deleted once nothing calls it. Deprecated today:

- `GET /api/appointments/patient/{id}`, `/api/appointments/doctor/{id}`
- `GET /api/consultations/patient/{id}`, `/doctor/{id}`, `/me/{completed,in-progress,cancelled,draft}`
- `GET /api/prescriptions/patient/{id}`, `/doctor/{id}`; `GET /api/lab-results/patient/{id}`, `/doctor/{id}`;
  `GET /api/imaging-results/patient/{id}`; `GET /api/medical-records/patient/{id}`, `/doctor/{id}`
- `GET /api/doctors/appointments`, `/api/doctors/consultations` (and its status and patient variants),
  `/api/doctors/medical-records`, `/api/doctors/patients`, `/api/doctors/prescriptions`, `/api/doctors/schedule`,
  `/api/doctors/profile/list/active`; `PATCH /api/doctors/appointments/{id}/mark-rescheduled`,
  `PATCH /api/doctors/consultations/{id}/status`
- `GET/PATCH/DELETE /api/patients/me/caregivers[/{accessId}]` (replaced by `/api/patients/me/caregiver-access`)
- `PUT /api/profile/settings`, `PATCH /api/profile/settings/notifications` (replaced by `PATCH /api/profile/settings`)

## 5. Idempotency

`Idempotency-Key` request header, optional, 8-64 characters of `[A-Za-z0-9_-]`. The key is scoped to the caller's
system id and the operation (`"<METHOD> <path template>"`).

- No key: the request just runs.
- First request with a key: runs and stores the response; 201 as usual.
- Repeat with the same key and scope within 24 hours: the stored body is returned with 200 and nothing runs again.
- Repeat while the first one is still running: 409 `IDEMPOTENCY_IN_PROGRESS`. A claim whose request died is taken over
  after 5 minutes; a request whose transaction rolled back releases its key.
- Keys expire after 24 hours and are purged by the `retention` job.

Endpoints that read the header (every POST that creates a row): appointments, consultations, prescriptions, lab and
imaging results, medical records, documents (upload), referrals, care plans, health metrics, symptom logs, reminders,
emergency contacts (`POST /api/profile/emergency-contacts`), care-team assignments
(`POST /api/patients/{patientSystemId}/care-team`), caregiver grants (`POST /api/patients/me/caregiver-access`),
dependents (`POST /api/caregiver/dependents`), doctor time off, correction requests, invoices, payments
(`POST /api/billing/invoices/{invoiceId}/payments`), insurance claims, payment methods, administrator manual payments
and refunds, facilities, administrator accounts and legal document versions. The web app sends one key per form open,
reuses it on retry and renews it after success.

Messaging keeps its own keys: `clientThreadId` on `POST /api/communications/threads` and `clientMessageId` on
`POST /api/communications/threads/{id}/messages` (a retry returns the stored message with 200).
`POST /api/communications/support-thread` is idempotent by construction (one open support thread per user).
Appointments also keep the slot uniqueness indexes.

## 6. Sessions

- `POST /api/auth/login` answers `AuthResponse { message, tokens?, user?, flags?, challenge? }`. With tokens:
  `tokens { accessToken, refreshToken, expiresIn, sessionId }`. When a second factor is needed there are no tokens
  and `challenge { type: "MFA" | "MFA_SETUP", token, expiresInSeconds }`; the client finishes on the public
  `/api/auth/mfa/**` endpoints with the challenge token. No restricted tokens exist.
- `flags` (also on `GET /api/users/me`): `mustChangePassword`, `emailVerified`, `legalActionRequired`, `mfaEnabled`.
  The web dashboard sends `mustChangePassword` to `/auth/change-password` and `legalActionRequired` to
  `/auth/accept-terms`; the server itself never refuses other calls because of `legalActionRequired`.
- `POST /api/auth/register` answers 201 without tokens (section "Documented decisions" of the README).
- Refresh rotates the refresh token; `sessionId` stays the same for the life of the session. Presenting a refresh
  token that was already rotated or revoked counts as reuse and ends every session of the account.
- The web app keeps tokens and flags in `sessionStorage`; native apps in the secure store.

## 7. Rate limits

Fixed windows per key (`systemId`, `email|ip` or `ip`, where ip is the request's remote address after the trusted
proxy rewrite). A refused request answers 429 with `Retry-After: <seconds>`, code `RATE_LIMITED` and the message
`Too many requests. Please try again in N seconds.` (`minutes` from one minute, `hours` from two hours). The web app
shows "Too many attempts. Try again in N minutes." Buckets (`carenexus.rate-limit.buckets.<name>`, limit per window
in seconds; an unknown bucket uses `default` 120 / 60):

| Bucket | Limit / window | Key |
|---|---|---|
| `auth-login-email-ip` | 5 / 900 | email and ip |
| `auth-login-ip` | 30 / 900 | ip |
| `auth-login-account` | 20 / 3600 | account |
| `auth-register-ip` | 10 / 3600 | ip |
| `auth-refresh-ip` | 120 / 900 | ip |
| `auth-forgot-email` | 3 / 3600 | email |
| `auth-forgot-ip` | 10 / 3600 | ip |
| `auth-verify-ip` | 20 / 3600 | ip |
| `auth-mfa` | 5 / 300 | challenge / account |
| `password-change` | 5 / 900 | system id |
| `appointment-book` | 20 / 3600 | system id |
| `caregiver-request` | 10 / 86400 | system id |
| `reminder-manual` | 10 / 3600 | system id |
| `document-upload` | 60 / 3600 | system id |
| `payment-initiate` | 30 / 3600 | system id |
| `data-export` | 3 / 86400 | system id |
| `correction-request` | 10 / 86400 | system id |
| `message-send` | 60 / 60 | sender |
| `thread-create` | 20 / 3600 | creator (find-or-create included) |

`carenexus.rate-limit.enabled=false` (`RATE_LIMIT_ENABLED`) switches limiting off (test profile, browser end-to-end
runs). The store is in memory per API instance (see the README provider table).

## 8. Audit

Every successful read or write of patient data writes an `audit_log` row (`READ`, `CREATE`, `UPDATE`, `DELETE`) with
the resource type, the row id and the patient; list reads write one row per patient on the page. Account and security
events record the actor and the target account (`audit_log.target_system_id`). Denials are audited as
`ACCESS_DENIED`. Resource types added by the plan: `CARE_TEAM, REFERRAL, CARE_PLAN, DOCUMENT, MEDICATION_SCHEDULE,
HEALTH_METRIC, SYMPTOM_LOG, AI_ALERT, DISEASE_DETECTION, INVOICE, PAYMENT, PAYMENT_METHOD, INSURANCE_CLAIM, REMINDER,
CONSENT, ACCESS_REQUEST, CORRECTION_REQUEST, FACILITY, SYSTEM_SETTING, USER_SETTINGS, DOCTOR_PROFILE, AVAILABILITY,
AUDIT_LOG, ACCOUNT, DATA_EXPORT, NOTIFICATION, SUPPORT_THREAD, LICENCE` (plus the earlier clinical and messaging
types). `AuditCoverageTest` drives a read and a write for each and lists the deliberate exemptions (reads of
facilities, settings, doctor profiles, availability, licences and the administrator's account view).

## 9. Domain events

Published inside the changing transaction after the row is saved (`com.carenexus.api.common.event`). Work that must
commit with the change (notifications, linked records, the consent ledger, the automatic invoice and medical record,
appointment and consultation consistency) is a plain `@EventListener` and rolls the change back when it fails. Work
that may fail on its own (AI screening and alert generators, provider calls, account clean-up, login alerts) runs
after commit in a new transaction and only logs failures. Events carry ids and flags, never clinical values.
Listeners are idempotent (dedupe keys and unique indexes with `ON CONFLICT DO NOTHING`).

| Event (fields) | Published by |
|---|---|
| `AppointmentBookedEvent(appointmentId, patientSystemId, doctorSystemId, actorSystemId, actorRole, type, scheduledAt, durationMinutes)` | appointments |
| `AppointmentRescheduledEvent(appointmentId, patientSystemId, doctorSystemId, actorSystemId, actorRole, previousScheduledAt, scheduledAt)` | appointments |
| `AppointmentCancelledEvent(appointmentId, patientSystemId, doctorSystemId, actorSystemId, actorRole, scheduledAt, reason, bySystem)` | appointments, account lifecycle (`bySystem = true`) |
| `AppointmentStatusChangedEvent(appointmentId, patientSystemId, doctorSystemId, actorSystemId, previousStatus, status)` | appointments (COMPLETED, NO_SHOW) |
| `ConsultationCompletedEvent(consultationId, appointmentId, patientSystemId, doctorSystemId, followUpRequired, followUpDate)` | consultations |
| `PrescriptionIssuedEvent(prescriptionId, consultationId, patientSystemId, doctorSystemId, medicationName, frequency, startDate, expiryDate, scheduleTimes)` | prescriptions |
| `PrescriptionStatusChangedEvent(prescriptionId, patientSystemId, doctorSystemId, previousStatus, status)` | prescriptions, expiry job |
| `ClinicalResultRecordedEvent(kind LAB/IMAGING, resultId, patientSystemId, doctorSystemId, actorSystemId, status, critical, newlyCritical, created)` | lab and imaging results |
| `MedicationDoseMissedEvent(scheduleId, prescriptionId, patientSystemId, scheduleDate, scheduledTime, consecutiveMisses)` | medication schedule |
| `HealthMetricRecordedEvent(metricId, patientSystemId, recordedBySystemId, type, abnormal, severity, source)` | health tracking (one per stored row; a blood-pressure reading is two rows) |
| `SymptomLoggedEvent(symptomId, patientSystemId, loggedBySystemId, severity)` | health tracking |
| `CaregiverAccessChangedEvent`, `CaregiverApprovalPendingEvent`, `CaregiverMessagingRegainedEvent` | caregiver access (messaging and call consent) |
| `CaregiverGrantRecordedEvent(accessId, patientSystemId, caregiverSystemId, actorSystemId, kind, guardianAttested)` | caregiver access and its expiry sweeper (kinds GRANTED, PERMISSIONS_CHANGED, COMMUNICATION_APPROVED, EXTENDED, NARROWED, REVOKED, EXPIRED) |
| `ReferralCreatedEvent(referralId, patientSystemId, referringDoctorSystemId, specialistSystemId, priority)` | referrals |
| `ReferralStatusChangedEvent(referralId, patientSystemId, referringDoctorSystemId, specialistSystemId, previousStatus, status)` | referrals |
| `CarePlanChangedEvent(planId, patientSystemId, doctorSystemId, actorSystemId, kind, status, reviewDate)` | care plans (CREATED, UPDATED, REVIEWED, STATUS_CHANGED) |
| `DocumentUploadedEvent(documentId, patientSystemId, uploadedBySystemId, documentType)` | documents |
| `AiAlertRaisedEvent(alertId, patientSystemId, doctorSystemId, alertType, priority)` | AI alerts |
| `InvoiceIssuedEvent(invoiceId, invoiceNo, patientSystemId, doctorSystemId, total, currency, dueDate)` | invoices (on SENT) |
| `InvoiceStatusChangedEvent(invoiceId, invoiceNo, patientSystemId, previousStatus, status)` | invoices |
| `PaymentRecordedEvent(paymentId, invoiceId, patientSystemId, initiatedBySystemId, amount, currency, method, status)` | payments, manual payments, claim settlements |
| `InsuranceClaimStatusChangedEvent(claimId, invoiceId, patientSystemId, previousStatus, status)` | insurance claims |
| `UserRegisteredEvent(systemId, role, email, acceptedTermsVersion, acceptedPrivacyVersion, ip, userAgent, createdBySystemId)` | registration, account creation |
| `UserLoggedInEvent(systemId, role, ip, deviceType, deviceInfo)` | sign-in |
| `UserLoggedOutEvent(systemId)` | sign-out, session revocation |
| `PasswordChangedEvent(systemId, kind RESET/CHANGE/ADMIN_RESET)` | account password |
| `AdminPasswordResetRequestedEvent(targetSystemId, adminSystemId)` | administrator reset, invitation of a managed dependent |
| `UserDeactivatedEvent(systemId, role, actorSystemId, cause ADMIN/CLOSURE/SUSPENSION)` | administration, account closure |
| `UserReactivatedEvent(systemId, role, actorSystemId)` | administration |
| `DoctorVerificationDecidedEvent(doctorSystemId, adminSystemId, status, reason)` | clinician verification |

`NotificationStoredEvent(notificationId, recipientSystemId, category, priority, dedupeKey)`
(`communications.notify.event`) follows every stored in-app notification and drives the email, SMS and push fan-out.
Modules keep private events in their own `event` package (for example the calls module's
`AppointmentCallRelevantChangeEvent`).

## 10. Notifications

All in-app notifications go through `NotificationPublisher` (`publish`, `publishToPatientCircle`,
`publishToCareTeam`); message notifications keep their messaging path. The publisher:

- checks the recipient's preference for the category (`user_settings`; a missing row means the defaults). `CRITICAL`
  priority and category `SYSTEM` always pass;
- skips the actor (a user is never notified of their own action) unless the category is `SYSTEM`;
- dedupes per recipient on `dedupeKey` (`"<EVENT>:<resourceId>[:<discriminator>]"`, for example
  `APPT_REMINDER_24H:412:2026-11-12T09:00`) with `ON CONFLICT DO NOTHING`;
- writes generic text only (who and what kind of thing, never a result value, diagnosis, dose or message body; the
  one exception is a dose reminder, which names the medication but never the dose);
- computes `actionUrl` from the target (10.2);
- publishes `NotificationStoredEvent`; the fan-out queues one outbound message per channel the recipient has switched
  on (email, SMS, push), delayed to the end of the recipient's quiet hours (in their time zone) unless the priority is
  CRITICAL.

A notification whose `expires_at` has passed is hidden from lists and counts. Reads of the list and mark-read are
audited (`NOTIFICATION`); the unread-count poll is not.

### 10.1 Types, priorities, categories

- `type` (database CHECK, icon on clients): `APPOINTMENT, MEDICATION, LAB_RESULT, MESSAGE, AI_ALERT, SYSTEM, PAYMENT,
  REFERRAL`.
- `priority`: `LOW, NORMAL, HIGH, CRITICAL`.
- `category` and the `user_settings` toggle that can switch it off:

| Category | Toggle |
|---|---|
| `APPOINTMENT_REMINDER` | `appointment_reminders` |
| `APPOINTMENT_REMINDER_1H` | `appointment_reminder_1h` |
| `APPOINTMENT_CHANGE` | `appointment_change_alerts` |
| `MEDICATION_REMINDER` | `medication_reminders` |
| `PRESCRIPTION` | `prescription_reminders` |
| `LAB_RESULT` | `lab_result_alerts` |
| `IMAGING_RESULT` | `imaging_result_alerts` |
| `CONSULTATION` | `consultation_notifications` |
| `VITALS` | `vitals_alerts` |
| `REFERRAL` | `referral_alerts` |
| `CARE_PLAN` | `care_plan_update_alerts` |
| `ACCESS_CHANGE` | `access_change_alerts` |
| `LOGIN_ALERT` | `login_alerts` |
| `BILLING` | `billing_notifications` |
| `VIDEO_CALL` | `video_call_alerts` |
| `MESSAGE` | `message_notifications` |
| `CRITICAL` | `critical_alerts` (only for priorities LOW to HIGH; CRITICAL priority is always delivered) |
| `SYSTEM` | none: account security, verification decisions, access requests are always delivered |

### 10.2 Targets and action URLs

`actionUrl` is relative:

```
/dashboard/<role>?section=<sectionKey>[&patient=<PAT id>][&id=<resource id>][&tab=<tab>][&<other>=<value>...]
```

- `role` is the recipient's: `patient`, `doctor`, `proxy` (CAREGIVER) or `admin`.
- `patient` is added for caregiver recipients on every target and for doctors on `HEALTH_TRACKING` and `CARE_TEAM`
  (targets that open one patient), when the notification is about a patient. It is never added for patient or
  administrator recipients, nor for the other doctor targets, where it would switch on the list filter.
- `id` is `params.id`, else the notification's resource id.
- `tab` is the tab fixed by the table below, else `params.tab`.
- Any other parameter is passed through, sorted by name. Values are percent-encoded.
- A target with no section for the recipient's role gives `actionUrl = null`: the notification is informational.

| Target | patient | doctor | proxy | admin |
|---|---|---|---|---|
| `APPOINTMENTS` | `my-appointments` | `appointments` | `appointments` | - |
| `CONSULTATIONS` | `consultation-history` | `consultations` | `documents-results&tab=consultations` | - |
| `PRESCRIPTIONS` | `prescriptions` | `prescriptions` | `medications` | - |
| `MEDICATION_SCHEDULE` | `prescriptions&tab=schedule` | `prescriptions` | `medications&tab=schedule` | - |
| `LAB_RESULTS` | `lab-results` | `labs` | `documents-results&tab=labs` | - |
| `IMAGING_RESULTS` | `imaging-results` | `imaging` | `documents-results&tab=imaging` | - |
| `MEDICAL_RECORDS` | `medical-records` | `medical-records` | `documents-results&tab=records` | - |
| `DOCUMENTS` | `documents` | `documents` | `documents-results&tab=documents` | - |
| `REFERRALS` | `referrals` | `referrals` | `documents-results&tab=referrals` | - |
| `CARE_PLANS` | `care-plans` | `care-plans` | `care-plans` | - |
| `HEALTH_TRACKING` | `health-tracking` | `patients&tab=vitals` | `health-summary` | - |
| `AI_ALERTS` | `health-alerts` | `ai-alerts` | `health-alerts` | - |
| `BILLING` | `billing` | `billing` | `billing` | `billing` |
| `CAREGIVER_ACCESS` | `my-proxies` | - | `profile-settings` (`access-requests` when the resource type is `ACCESS_REQUEST`) | - |
| `CARE_TEAM` | `care-team` | `patients` | `patient-profile` | - |
| `MESSAGES` | `messages` | `messages` | `messages` | `messages` (support inbox) |
| `ACCOUNT_SECURITY` | `profile-settings&tab=security` | `profile&tab=security` | `profile-settings&tab=security` | `account` |
| `CORRECTIONS` | `medical-records&tab=corrections` | `medical-records&tab=corrections` | `documents-results&tab=corrections` | `corrections` |
| `ADMIN_CLINICIAN_QUEUE` | - | - | - | `clinician-queue` |
| `ADMIN_ACCOUNT_REQUESTS` | - | - | - | `account-requests` |
| `ADMIN_BILLING` | - | - | - | `billing` |

Message notifications of clinical and support conversations carry `threadId` and `messageId` and no `actionUrl`
(clients open the conversation by id). The administrators' support notification links to
`/dashboard/admin?section=messages&id=<threadId>`. Notifications created before the plan keep their stored target
(`CAREGIVER_ACCESS` for old caregiver rows).

## 11. Web section keys

The web dashboard is URL-addressable: `/dashboard/<role>?section=<key>&<param>=<value>`. The keys are stable because
action URLs, emails and the end-to-end suites depend on them. Every query parameter other than `section` is passed to
the section; the reserved ones are `patient` (PAT id context for doctor and caregiver screens, a server-side filter on
doctor lists), `id` (primary resource), `tab`, `appointmentId`, `consultationId`, `date`, `thread` and `mode`. An unknown
section opens the role's home (`dashboard`). Sections marked hidden are routable but not in the sidebar.

| Role | Section keys |
|---|---|
| patient | `dashboard`, `book-appointment`, `my-appointments` (alias `join-consultation`), `consultation-history`, `prescriptions` (tabs `schedule`, `reminders`), `health-tracking`, `care-plans`, `health-alerts`, `medical-records` (tab `corrections`), `lab-results`, `imaging-results`, `documents`, `referrals`, `care-team`, `my-proxies` (tab `requests`), `messages`, `notifications`, `billing`, `profile-settings` (tab `security`), `settings`, `help-support` |
| doctor | `dashboard`, `schedule`, `availability`, `consultations`, `appointments`, `patients` (tab `vitals`), `medical-records` (tab `corrections`), `documents`, `prescriptions`, `labs`, `imaging`, `care-plans`, `referrals`, `ai-alerts`, `billing`, `messages`, `notifications`, `profile` (tab `security`), `settings`, `help-support` |
| proxy | `dashboard`, `appointments`, `medications` (tabs `schedule`, `reminders`), `care-plans`, `messages`, `notifications`, `documents-results` (tabs `consultations`, `labs`, `imaging`, `records`, `documents`, `referrals`, `corrections`), `health-summary`, `health-alerts`, `billing`, `profile-settings` (tab `security`), `notification-settings`, `help-support`; hidden: `add-dependent`, `patient-profile`, `access-requests` |
| admin | `dashboard`, `users`, `clinician-queue`, `account-requests`, `corrections`, `billing`, `messages` (support inbox), `notifications`, `help-support`, `audit-log`, `legal`, `facilities`, `system-settings`, `account`; hidden: `user-detail`; `outbox` is listed only where the development outbox endpoint answers |

Doctor list sections (`consultations`, `appointments`, `prescriptions`, `labs`, `imaging`, `medical-records`,
`documents`, `referrals`, `care-plans`, `billing`) honour `patient=` as a filter and show a removable chip.

Public web routes used in emails: `/auth/forgot-password`, `/auth/reset-password?token=`, `/auth/verify-email?token=`,
`/auth/confirm-email-change?token=`, `/auth/change-password`, `/auth/accept-terms`, `/legal/terms`, `/legal/privacy`.
Video calls open at `/dashboard/call/<appointmentId>`.

## 12. Uploads, downloads and webhooks

- Uploads (documents, claim attachments): one file up to 10 MB; the allowed types (PDF, JPEG, PNG, DICOM) are decided
  by the scanner's detected type, never the client file name, which is also never used as the storage key. Refused
  files answer 400 `FILE_REJECTED`; too large 413.
- Downloads stream through the API with `Content-Disposition: attachment` and nosniff; every download is authorised
  and audited.
- Webhooks (`/api/integrations/livekit/webhook`, `/api/integrations/payments/{provider}/webhook`) verify a signature
  over the raw body, cap the body at 64 KB, store an idempotent event row without the payload (duplicates answer 200
  and change nothing) and answer 401 for a bad signature and 404 when the provider is not active.
