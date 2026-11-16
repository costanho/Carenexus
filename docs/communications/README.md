# CareNexus communications: saved messaging and embedded video calls

This folder documents the communications release for Care Direct (patients and doctors) and Care Proxy (caregivers acting for a patient).

- [CONTRACT.md](CONTRACT.md) is the authoritative specification: migrations V15 to V20, permissions, every endpoint with fields, status codes and error messages, call session and participant statuses, LiveKit integration, configuration and frontend behaviour. Where it disagrees with the older PRD, ERD or Technical Architecture documents, the contract and the code/migrations win.
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

Still out of scope: native mobile calling (mobile apps show "Join video consultations from the CareNexus web app."), recording, transcription, screen sharing, in-call chat (LiveKit's data channel is disabled), attachments, and email/SMS notifications.

Saved messaging is new in this release too: private and care-team conversations, a history boundary for members added later, read positions, retry-safe sending, an urgent highlight, and in-app notifications. Updates arrive by polling, not websockets.

## 2. Interfaces summary

Full request/response shapes and error messages are in [CONTRACT.md](CONTRACT.md) (section numbers in brackets).

| Area | Interface |
|---|---|
| Caregiver permissions [5] | `GET/POST /api/patients/me/caregiver-access`, `PATCH/DELETE /api/patients/me/caregiver-access/{accessId}`. Messaging and joining calls are separate permissions. Each takes effect only after the patient approves it, and `FULL_ACCESS` never implies them. V15 resets existing grants to "needs patient approval". |
| Messaging [7] | `GET /api/communications/recipients`, `GET/POST /api/communications/threads`, `GET /api/communications/threads/{threadId}`, `POST/DELETE .../members`, `PUT .../archive`, `GET/POST .../messages` (`before`/`after` message-id cursors, `clientMessageId` for duplicate-safe retries), `POST .../read`. Admins cannot read or send messages. Membership and the current caregiver permission are checked on every request. |
| Notifications [7.4] | `GET /api/notifications`, `GET /api/notifications/unread-count`, `POST /api/notifications/{notificationId}/read`, `POST /api/notifications/read-all`. |
| Calls [8] | Base `/api/appointments/{appointmentId}/call`: `GET` status, `POST /start`, `/join-request`, `/admissions/{participantId}/admit`, `/deny`, `/remove`, `/token`, `/leave`, `/end`, `/confirm-time`. |
| Webhook [8.6] | `POST /api/integrations/livekit/webhook`, public, verified by LiveKit signature over the raw body; duplicate events are recorded once. |
| Appointment responses [9] | New `callStatus`, `callJoinRoute` (`/dashboard/call/{appointmentId}`), `joinWindowOpensAt`, `joinWindowClosesAt`. |
| Database [4] | V15 consent columns, V16 thread members and message ids, V17 notification references, V18 call sessions/participants, V19 call jobs and provider events, V20 dev-only demo seed. The next schema migration is V21. |
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
- **No guardian verification.** Patients approve caregiver permissions themselves. Verifying a caregiver's legal authority is outside this version.
