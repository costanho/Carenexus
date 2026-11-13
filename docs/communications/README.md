# CareNexus communications: saved messaging and embedded video calls

This folder documents the communications release for Care Direct (patients and doctors) and Care Proxy (caregivers acting for a patient).

- [CONTRACT.md](CONTRACT.md) is the original specification of this release: migrations V15 to V20, permissions, every endpoint with fields, status codes and error messages, call session and participant statuses, LiveKit integration, configuration and frontend behaviour. Where it disagrees with the older PRD, ERD or Technical Architecture documents, the contract and the code/migrations win. Later changes (support conversations, validation messages, rate limits, the shared error envelope) are summarised in sections 5 and 6 below and override it.
- API-wide conventions (error envelope and codes, pagination, notifications and their links): [docs/api/CONTRACT.md](../api/CONTRACT.md).
- Local startup, environments, secrets, HTTPS and webhook setup are in the [root README](../../README.md).

---

## 1. Expanded video scope compared with the PRD

The PRD treated video as out of scope apart from "video consultation link generation": a VIDEO appointment stored an external meeting link, and the calling stack itself was not built. This release replaces that with calls embedded in CareNexus.

| | PRD (before) | This release |
|---|---|---|
| Where the call happens | External meeting URL (`meet.jit.si` placeholder) | Inside the CareNexus web app at `/dashboard/call/{appointmentId}`, using LiveKit (local LiveKit server in development, LiveKit Cloud for staging and launch) |
| Access control | Anyone with the link | Authenticated route; the server checks the appointment's doctor, patient, or a caregiver with patient-approved call permission on every status, admission and token request |
| Meeting links | Supplied by clients or generated, returned in responses | `videoConsultationLink` is always `null` in responses and ignored in requests; the old column is kept only as legacy data; `VIDEO_BASE_URL` is removed |
| Session model | None | One managed call session per VIDEO appointment with statuses `NEEDS_TIME_CONFIRMATION`, `PREPARING`, `READY`, `FAILED`, `LIVE`, `ENDED`, `CANCELLED` |
| Joining | Open the link | Device check, waiting area, the doctor admits each person, short-lived (5 minute) room tokens |
| Caregivers | Not covered | Approved caregivers join as "attending with" or "speaking for" the named patient, are labelled as acting for that patient, and still need the doctor's admission |
| Scheduling | Clinic wall-clock time | Clinic time (Africa/Harare) for booking; call windows stored as UTC instants. Entry opens 15 minutes before the start and closes 60 minutes after the scheduled end. Appointments booked before this release need the doctor to confirm the time first |
| Lifecycle | None | Cancel, reschedule, type change, expiry, logout and permission removal invalidate admissions and trigger provider cleanup through durable, retried jobs |
| Clinical records | n/a | Leaving or ending a call never changes appointment status or consultation notes |

Still out of scope: native mobile calling (mobile apps show "Join video consultations from the CareNexus web app."), recording, transcription, screen sharing, in-call chat (LiveKit's data channel is disabled) and attachments. Email, SMS and push copies of notifications now go through the delivery outbox (local providers in development; see the README provider table); message notifications never contain the message text.

Saved messaging is new in this release too: private and care-team conversations, a history boundary for members added later, read positions, retry-safe sending, an urgent highlight, and in-app notifications. Updates arrive by polling, not websockets.

## 2. Interfaces summary

Full request/response shapes and error messages are in [CONTRACT.md](CONTRACT.md) (section numbers in brackets).

| Area | Interface |
|---|---|
| Caregiver permissions [5] | `GET/POST /api/patients/me/caregiver-access`, `PATCH/DELETE /api/patients/me/caregiver-access/{accessId}`. Messaging and joining calls are separate permissions. Each takes effect only after the patient approves it, and `FULL_ACCESS` never implies them. V15 resets existing grants to "needs patient approval". |
| Messaging [7] | `GET /api/communications/recipients`, `GET/POST /api/communications/threads`, `GET /api/communications/threads/{threadId}`, `POST/DELETE .../members`, `PUT .../archive`, `GET/POST .../messages` (`before`/`after` message-id cursors, `clientMessageId` for duplicate-safe retries), `POST .../read`. Administrators cannot read or send messages in patient-doctor or care-team conversations; they answer support conversations only (section 5). Membership and the current caregiver permission are checked on every request. |
| Support [5] | `POST /api/communications/support-thread` (open or get your own), `GET /api/admin/support/threads`, `POST /api/admin/support/threads/{threadId}/close`. |
| Notifications [7.4] | `GET /api/notifications` (now paged, with `category`, `target`, `resourceType`, `resourceId` and a computed `actionUrl`), `GET /api/notifications/unread-count`, `POST /api/notifications/{notificationId}/read`, `POST /api/notifications/read-all`. |
| Calls [8] | Base `/api/appointments/{appointmentId}/call`: `GET` status, `POST /start`, `/join-request`, `/admissions/{participantId}/admit`, `/deny`, `/remove`, `/token`, `/leave`, `/end`, `/confirm-time`. |
| Webhook [8.6] | `POST /api/integrations/livekit/webhook`, public, verified by LiveKit signature over the raw body; duplicate events are recorded once. |
| Appointment responses [9] | New `callStatus`, `callJoinRoute` (`/dashboard/call/{appointmentId}`), `joinWindowOpensAt`, `joinWindowClosesAt`. |
| Database [4] | V15 consent columns, V16 thread members and message ids, V17 notification references, V18 call sessions/participants, V19 call jobs and provider events, V20 dev-only demo seed, V65 support threads, V68 call end reason `APPOINTMENT_ENDED`. Migration numbering: [DATA-MODEL-NOTES.md](../architecture/DATA-MODEL-NOTES.md). |
| Configuration [10] | `LIVEKIT_ENABLED`, `LIVEKIT_URL`, `LIVEKIT_CLIENT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, `LIVEKIT_WEBHOOK_ENABLED`, `CALLS_WORKER_ENABLED`; `carenexus.calls.*` and `carenexus.messaging.max-body-length` in `application.yml`. |

## 3. Release gates

Each gate needs evidence (test name, CI run, or a recorded manual check) before launch. "Local" means provable with the docker compose stack; "Cloud staging" means it must be shown against a LiveKit Cloud staging project.

| # | Gate | Where it can be proven |
|---|---|---|
| 1 | A clean, migration-created database passes schema validation (`ddl-auto: validate`), with and without seed data | CI (`./mvnw verify`, Testcontainers) |
| 2 | Patient, assigned doctor and approved caregiver complete the intended messaging and call journeys | Local, then Cloud staging |
| 3 | Unrelated users, guessed ids, self-granted permissions and expired grants are denied | CI tests + local |
| 4 | Newly added members cannot see earlier messages, previews or notifications | CI tests |
| 5 | Revoked caregivers lose thread access immediately and are removed from calls | Thread access: CI/local. Live call removal: **Cloud staging** |
| 6 | Duplicate sends, failed requests and reconnects produce no duplicate messages or rooms | CI tests + local |
| 7 | VIDEO booking, cancellation, rescheduling and type changes produce the documented call states | CI tests + local |
| 8 | Camera denial, missing devices, audio-only use, network loss, session expiry and provider failure each have a clear recovery path | Local browser checks |
| 9 | Keyboard, screen-reader and 375 px narrow-screen checks pass in supported web browsers | Local browser checks |
| 10 | Ending or leaving a call never completes clinical documentation | CI tests + local |
| 11 | CI is green: backend verify, frontend type check, no critical npm advisories, no leaked secrets | CI |

Demonstrate messaging and calls separately before demonstrating the combined journey.

### What still needs LiveKit Cloud staging proof

Local LiveKit (dev mode) proves basic calling only. The following must be shown separately on Cloud staging, with the evidence recorded, before launch:

1. **Removal during a live call.** Revoke a caregiver's call permission (and separately, have the doctor press Remove) while that caregiver is connected. The caregiver must be disconnected by the provider, not only refused a new token. A connected participant is not disconnected when their token expires, because LiveKit refreshes tokens for connected clients, so removal has to be an explicit provider call.
2. **Revocation cutoff.** After removal, the caregiver's next token request is refused, and the rejoin attempt fails.
3. **Removal failure handling.** With the provider call failing (for example, a wrong key on staging), new access is blocked at once, the removal job keeps retrying, and the doctor sees "Removal pending". Removal must never be reported as done before the provider confirms it.
4. **Signed webhooks.** Cloud deliveries are accepted, unsigned or tampered requests get `401`, and redelivered events are not processed twice.
5. **Cleanup jobs.** Ended, cancelled and expired sessions have their Cloud rooms deleted.
6. **Usage alerts** are configured on the Cloud project and fire on a test threshold.

## 4. What this release does not claim

Docs, UI copy and API messages must stay within these limits:

- **No end-to-end encryption claim.** Calls use LiveKit's standard transport; the provider's media servers handle the media. This implementation does not add end-to-end encryption and must not be described as encrypted end to end.
- **No regulatory certification.** Nothing here has been certified or audited against HIPAA, GDPR, POPIA or any other regulation. Do not describe the product as compliant or certified.
- **Urgent is a highlight, not monitoring.** The urgent flag only changes how a message is shown. Nobody is alerted, nothing is monitored, and replies come no faster. The composer shows exactly: "Urgent highlights your message for the care team. It does not make a reply faster. For emergencies, call your local emergency number."
- **No guaranteed response times** for messages or calls.
- **No guardian verification.** Patients approve caregiver permissions themselves. A caregiver who creates a managed dependent (an account without sign-in details) must attest that they are the guardian, and the attestation is recorded, but nobody verifies a caregiver's legal authority in this version.
- **Support is not clinical advice or an emergency line.** Support conversations reach the clinic's administrators, who see no clinical conversations and no clinical records through them.

## 5. Support conversations

Support conversations (conversation type `SUPPORT`, stored with `thread_type = 'PATIENT_SUPPORT'`, V65) let a user write to the clinic's administrators from **Help & Support → Contact support** on the web. They reuse the messaging endpoints for reading and sending, so the same read positions, retry-safe sending and notifications apply.

**Who may open one.** A patient, a caregiver or a doctor, each for themselves: `POST /api/communications/support-thread` (optional body `{ "subject": "..." }`, at most 200 characters) returns the caller's open support conversation with 200, or creates one with 201. No patient context is involved, so a caregiver whose grants have ended can still contact support. A user has at most one open support conversation (partial unique index on the opener) and any number of closed ones; two concurrent first calls return the same conversation. Opening it notifies nobody. Administrators cannot open one (403 `Administrators cannot open a support conversation`).

**Who answers.**

- Every active administrator sees every support conversation that has at least one message in the admin **Messages** section (`GET /api/admin/support/threads?open=&page=&size=`, newest activity first, with an unread flag per administrator). Listing does not make the administrator a member.
- The first time an administrator opens, reads, sends to or marks read a support conversation, they join it as an `ADMIN` member with the full history (audited as `UPDATE SUPPORT_THREAD`).
- Their messages are labelled `<First Last> (Support)`. The opener gets one `MESSAGE` notification per administrator message.
- Every active administrator gets at most one `New support message` notification per conversation and clock hour for the opener's messages (target `MESSAGES`, link `/dashboard/admin?section=messages&id=<threadId>`). A message from one administrator does not notify the others.
- No calls, no member management (409), no archive for administrators (409) and no urgent flag (forced off) in a support conversation.

**Closing.** Any administrator closes a conversation with `POST /api/admin/support/threads/{threadId}/close` (idempotent; the first `closedAt` stays). A closed conversation stays readable for the opener and administrators, refuses new messages with 409 (a retry of a message stored before the close still answers 200) and is never reopened; the opener's next **Contact support** creates a new one.

**What an administrator can never read.** Administrators have messaging access to `SUPPORT` conversations only.

- On every patient-doctor (`PRIVATE`) or care-team (`CARE_TEAM`) conversation, and on its messages, members and read endpoints, they get 403 `Administrators cannot access conversations`, and the attempt is audited as `ACCESS_DENIED`.
- The support close route answers 404 `Conversation not found` for a clinical conversation id, so it never confirms that one exists.
- `GET /api/communications/recipients`, the thread list and `POST /api/communications/threads` refuse administrators.
- A support conversation has no patient (`patientSystemId` and `patientName` are null), so joining one gives no access to anybody's clinical data.
- Notification text never contains the message body or the subject.

**Web.** Help & Support shows the clinic name and the support email and phone from `GET /api/settings/public`, which administrators set in **System settings**. When that call fails or has no email, the build-time `EXPO_PUBLIC_SUPPORT_EMAIL` is the fallback. Native apps cannot start a support conversation; one started on the web appears in the native message list and opens there, but the native header prints `null` where the patient name would be (see [TODO.md](../../TODO.md)).

## 6. Changes since this release

These hardening changes override [CONTRACT.md](CONTRACT.md):

- **Error envelope.** Every error uses the shared envelope with `code`, `errors` and `requestId` ([docs/api/CONTRACT.md](../api/CONTRACT.md) section 3). Bean Validation runs before any service code. Validation messages have the form `<field>: <text>` (for example `body: must not be empty` instead of `Message cannot be empty`); statuses are unchanged.
- **Rate limits.** `POST /api/communications/threads` uses the `thread-create` bucket (20 per hour per creator, find-or-create calls included). `POST /api/communications/threads/{id}/messages` uses `message-send` (60 per minute per sender, every role, clinical and support conversations). Both answer 429 `RATE_LIMITED` with `Retry-After`. The support and call endpoints have no bucket.
- **Recipients.** `GET /api/communications/recipients` returns at most 200 people and is audited (`READ MESSAGE_RECIPIENT`). The thread list is audited per patient on the page.
- **Calls.** `POST /api/appointments/{id}/call/confirm-time` with a missing body or `scheduledAt` answers 400 (was 409). Marking an appointment COMPLETED or NO_SHOW ends a call session that never went live, with end reason `APPOINTMENT_ENDED` (V68).
- **Message length.** At most `MESSAGING_MAX_BODY_LENGTH` (4000) characters per message.
