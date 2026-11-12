# Mobile communication (iOS / Android)

Messaging, notifications and caregiver consent in the Expo app, matching the web app (PR costanho/Carenexus#2).
The backend and the API contract (`docs/communications/CONTRACT.md`) are unchanged.

## What was built

| Area | Patient | Doctor | Caregiver |
|---|---|---|---|
| Messages tab (bottom navbar, unread badge) | yes | yes | yes (the tab was "Soon") |
| Inbox / Archived tabs, unread counts, previews, pull to refresh | yes | yes | yes |
| Patient filter | n/a | yes | yes |
| Thread view: urgent toggle + required helper text, "Not up to date" banner, failed send with Retry / Discard, blocked (403) state | yes | yes | yes |
| Drafts kept across thread switches and app restarts | yes | yes | yes |
| New conversation (Private / Care team, pick members) | yes | yes | yes |
| Member manager (sheet), archive / unarchive | yes | yes | yes |
| Notification bell (top nav) and notifications screen, tap-through to the thread | yes | yes | yes |
| `CAREGIVER_ACCESS` notification opens caregiver access | yes | n/a | n/a |
| Caregiver access: approve / deny / revoke messaging and calls, pending state | yes (Profile → "Caregivers and access") | n/a | n/a |
| Messaging status per loved one (same wording as web) | n/a | n/a | dashboard, loved-one profile, empty inbox, new conversation |
| Contextual Message button (get-or-create thread POST) | appointment cards → doctor | patient cards, appointment cards → patient | "Message Care Team" per loved one |
| Sign-out clears drafts, outbox and unread counts | yes | yes | yes |

### Code

New files (no existing screen was forked):

- `frontend/src/components/mobile/messages/`
  - `mobile-messages-screen.tsx`: inbox → thread → new conversation, one screen at a time; Android back returns to the list.
  - `mobile-thread-list.tsx`, `mobile-thread-view.tsx`, `mobile-composer.tsx`: phone layouts (FlatList, keyboard avoiding, members in a page sheet).
  - `messaging-actions.tsx`: a provider each dashboard mounts once. Cards deep in a screen render `MessageDoctorButton`, `MessagePatientButton` or `MobileMessageCareTeamButton` without props threaded through. It also provides `CaregiverAccessLink`.
  - `messages-tab-icon.tsx`: the Messages tab badge and its accessible name ("Messages, 3 unread").
- `frontend/src/components/mobile/notifications/`: `mobile-notification-bell.tsx`, `mobile-notifications-screen.tsx`.
- `frontend/src/components/mobile/caregiver-access/patient-mobile-caregiver-access.tsx`: safe-area and keyboard wrapper.
- `frontend/src/hooks/use-draft.ts`: draft-bound text with the web composer's rules. It also saves when the app goes to the background.
- `frontend/src/lib/communications/storage.ts` and `storage.native.ts`: see "Storage" below.

Reused unchanged:

- All hooks: `use-threads`, `use-thread-messages`, `use-notifications`, `use-caregiver-messaging`, `use-doctor-message-patient`, `use-message-recipients`, `use-caregiver-access`.
- All of `lib/communications`.
- These web components, which are plain React Native views: `NewConversation`, `MemberManager`, `PatientFilter`, `CaregiverMessagingStatus`, `NotificationList`, `MessageCareTeamButton`, the `ui.tsx` primitives and the patient caregiver-access screen. Copy and rules therefore cannot drift between web and mobile.

Shared files changed (web behaviour unchanged):

- `lib/communications/drafts.ts`: `storage()` now delegates to `./storage`. On web that is still `sessionStorage`.
- `hooks/use-page-active.ts`: on native, polling pauses while the app is in the background (`AppState`). The web path is untouched.

Existing mobile screens got small additive edits only: new `activeKey` branches in the three dashboards, one tab entry per bottom navbar, one bell per top nav, and one button element per card.
The doctor bottom navbar now has 7 tabs, and tab labels shrink to fit on one line.
`components/mobile/layout/bar-insets.tsx` wraps each dashboard's content area so screens do not repeat a safe-area inset that a navbar already handles. Before this, doctor and caregiver screens left an empty status-bar band under the top bar, and every screen padded above the bottom tab bar for the home indicator.

### Storage: why `expo-secure-store`

On web, drafts and the outbox use `sessionStorage`. On native, `storage.native.ts` implements the same small `Storage` interface on top of `expo-secure-store`:

- Drafts and unsent messages can contain health details. SecureStore encrypts them at rest (iOS Keychain, Android Keystore), using `WHEN_UNLOCKED_THIS_DEVICE_ONLY` so they are never copied to another device by a keychain backup.
- It is already installed (auth tokens use it) and runs in Expo Go. AsyncStorage would add a dependency and store plain text.
- SecureStore has no key listing and warns above about 2 KB per value. The adapter therefore:
  - keeps every value in memory, so the existing drafts and outbox code stays synchronous;
  - keeps an index of keys and splits values into 600-code-unit chunks, never cutting a surrogate pair;
  - writes in the background, coalesced and in order;
  - loads once, synchronously, at startup.
- Sign-out clears the in-memory copy straight away, and the matching keychain deletes are queued.

## Video calls

Decision (7 Nov 2026): video calls stay web-only for now.
Video appointments on mobile keep the existing note "Join video consultations from the CareNexus web app." on the patient, doctor and caregiver appointment cards, with no dead buttons.
Native calling would need `@livekit/react-native` + WebRTC and a custom Expo dev build, since Expo Go cannot load it.

## Verified

On the iOS Simulator (iPhone 16 Pro, iOS 18.0, Expo Go SDK 56) against the local API. Screenshots are in `docs/mobile/screenshots/communication/`.

- **Patient ↔ doctor private thread.**
  - The patient pressed Message on an appointment card, which opened or created the private thread.
  - The patient sent normal and urgent messages; the helper text shows while urgent is on.
  - The doctor got a notification and a Messages badge; tapping it opened the thread and marked it read; the doctor replied.
  - The doctor's Message on a patient card reopened the same thread (no duplicate).
  - The doctor's patient filter works.
- **Care team.**
  - The patient created a care-team conversation with a doctor and Sarah through the New form.
  - Sarah's "Message Care Team" created a care-team thread with Thomas's doctors, and she sent a message in it.
- **Caregiver revoked mid-session.**
  - Thomas turned off Sarah's messaging through the API while Sarah had the thread open.
  - Her next poll got a 403, and the thread showed "You no longer have access to this conversation." with the composer locked.
- **Caregiver status.** The dashboard, empty inbox and new-conversation picker show "Waiting for {name} to approve messaging" and "Messaging is not allowed…", with the same wording as web.
- **Outage.** The API was cut off with a local proxy (the Docker stack was not touched).
  - The thread showed "Not up to date. Could not reach CareNexus, retrying…".
  - A send failed with Retry / Discard, and the failed message was still there after killing and relaunching the app.
  - Retry delivered it, and the server stored exactly one copy (same `clientMessageId`).
- **Drafts.** Drafts survived switching threads and a full app restart, and were gone after sign-out and signing back in.
- **Archive and members.** Archive and unarchive worked from the thread header and from the list row. The members sheet opened.
- **Caregiver approval notification.**
  - The test notification for Thomas was a dev-only database row, inserted by hand and deleted afterwards: caregivers cannot widen their own grant.
  - It opened the caregiver-access screen, where Thomas approved messaging; the toggle went back to "Allowed".
- **Keyboard.** With the on-screen keyboard, the composer stays above it.
- `npx tsc --noEmit -p .` is clean.
- Web is unchanged. `npm run e2e:build` and then `E2E_CHANNEL=chrome npx playwright test --project=chrome e2e/messaging.spec.ts e2e/fix-messaging.spec.ts e2e/smoke-comms.spec.ts` passed 18/18. The worktree's `dist` was served on :19006, which the API's CORS list already allows, with `E2E_WEB_URL` pointed at it.

Not verified:

- Android: there was no emulator run. `KeyboardAvoidingView` uses `height` there, and BackHandler is wired but untested.
- VoiceOver was not run. Accessible labels mirror web.

## Deferred

- Native video calls (needs a dev build and a decision).
- Push notifications. Polling is the same as on web: thread every 5 s, list every 15 s, counts every 30 s, all paused while the app is in the background.
- "F5": a caregiver-created dependent approving their own caregiver.
- There is no native connectivity detection (NetInfo is not installed). An unreachable server shows the "Not up to date" banner and failed sends instead of an offline banner.
- Swipe-to-archive. Archive is a button on each row.
