# Mobile easy fixes (iOS / Android)

Bugs and launch-readiness gaps in the Expo mobile screens, found by comparing each mobile screen with its web counterpart and the fixes made on web in costanho/Carenexus PR #1 and PR #2.

**Out of scope:** messaging, notifications, caregiver-consent UI and video-call UI. Those are handled in the separate "Mobile app: messaging and communication" work.

Paths are relative to `frontend/src/`. `M/` means `components/mobile/`. Line numbers are from before the fixes.

**Status key:**
- **Fixed** — done in this change.
- **OK** — checked; no change needed.
- **Deferred** — not fixed here; the reason is given.

## Shared

| # | Where | Impact | Fix | Status |
|---|---|---|---|---|
| S1 | many screens (see P9, D12, C13) | Date-only values (DOB, expiry, follow-up, test date) are parsed with `new Date('YYYY-MM-DD')` / `T00:00:00`. They shift a day depending on the device time zone, and ages are off around birthdays. | New `lib/ui/date-only.ts`: `isValidDateOnly`, `formatDateOnly`, `daysUntilDateOnly`, `ageFromDateOnly`. These use only the Y-M-D parts. | Fixed |
| S2 | `hooks/use-consultations.ts`, `use-prescriptions.ts`, `use-lab-results.ts`, `use-imaging-results.ts`, `use-medical-records.ts` | `loadActiveDoctors()` sits inside `Promise.all` without a catch. If the doctor list fails, the whole screen errors, although the records loaded. | `.catch(() => [])`, as `use-appointments.ts` does. Doctor names fall back to the system id. | Fixed |
| S3 | `hooks/use-consultations.ts` | The "patient-visible consultation" rule lives only in a web component. | Export `isPatientVisibleConsultation` from the hook. Web keeps its own copy, unchanged. | Fixed |

## Auth (login / register)

| # | Where | Impact | Fix | Status |
|---|---|---|---|---|
| A1 | `app/auth/register/mobile/register-mobile.tsx:85` | `Field` is declared inside the component. Every keystroke creates a new component type, which remounts the `TextInput`, so the keyboard closes after each character. | Move `Field` to module scope and pass the scale function in. | Fixed |
| A2 | `register-mobile.tsx:166,252,296,331` | Role cards, the Terms checkbox, Create Account and the Sign in link have no role. Selected and checked states are not announced. | Add `radio`/`checkbox`/`button`/`link` roles and selected/checked state. Mark the title as a header. | Fixed |
| A3 | `app/auth/login/mobile/login-mobile.tsx:61,255,295` | No double-submit guard. Return on the password field does not submit. The auth error is not announced. "Create a new account" has no role. | Add an `if (loading) return` guard, `returnKeyType="go"` with `onSubmitEditing`, `accessibilityRole="alert"` with a live region, and a link role. | Fixed |
| A4 | `login-mobile.tsx`, `register-mobile.tsx` | The dark status-bar icons sit on the teal/indigo/purple header and are hard to read. | `<StatusBar style="light" />` on both screens. | Fixed |
| A5 | `login-mobile.tsx:80` | A wrong password returns 401 `InvalidCredentialsException`. | Checked on the simulator: the server's "Invalid email or password" message shows, and the 401 does not trigger a token refresh (`/api/auth/` is excluded). | OK |
| A6 | `app/_layout.tsx`, `app/dashboard/_layout.tsx`, `lib/auth/*` | Session handling. | Checked: sign-out clears tokens and fires `emitSignedOut`; an expired token refreshes once with single-flight; a rejected refresh returns to login; `RoleGate` stops a wrong-role dashboard from mounting. | OK |

## Patient

| # | Where | Impact | Fix | Status |
|---|---|---|---|---|
| P1 | `M/direct/patient/patient-mobile-consultation-history.tsx:25,40-46,202-206` | The "In Progress" and "Drafts" stats and filters are always 0, because the backend hides those statuses from patients. Cancelled consultations are listed; web hides them. | Use `isPatientVisibleConsultation`. Use web's stats (Total / With diagnosis / Follow-ups) and its All / Follow-up filters. | Fixed |
| P2 | `M/direct/patient/patient-main-dashboard-area.tsx:242-294` | "Recent Consultations" is built from COMPLETED *appointments*. A visit without a finalised consultation appears there, but is missing from the history screen it links to. | Use `useConsultations()` filtered by `isPatientVisibleConsultation`. Show `chief_complaint ?? diagnosis` and `tidyDoctorName`, as web does. | Fixed |
| P3 | `M/direct/patient/patient-mobile-profile.tsx:267` | "Patient ID" always shows "—". | Show `profile.patient_system_id`. | Fixed |
| P4 | `M/direct/patient/patient-mobile-appointment-status.tsx:128,153-157` | The reschedule notice is card-local state. The rescheduled card leaves the "Upcoming" filter, or the first page, and unmounts, so the notice never shows. | Keep `rescheduledId` in the list. Show the notice on the card when it is visible, otherwise as a list-level banner with a live region, as web does. | Fixed |
| P5 | `patient-mobile-appointment-status.tsx:47,322,336` | The "Upcoming" filter is `status === 'SCHEDULED'`. It drops RESCHEDULED appointments and keeps past ones, and its count disagrees with the Upcoming stat. | Filter with `isUpcomingAppointment`. Label a past SCHEDULED appointment "Awaiting update", not "Upcoming". | Fixed |
| P6 | `patient-mobile-appointment-status.tsx:186`, `patient-mobile-appointment.tsx:580`, `patient-mobile-consultation-history.tsx:54` | Doctor avatar initials: `doctor_name[4]` assumes a "Dr. " prefix; `first_name[0]` gives "D" for "Dr. Michael"; `getInitials` gives "DB". | `doctorInitial()` from `hooks/use-doctors.ts`. Pass displayed names through `tidyDoctorName()`. | Fixed |
| P7 | `patient-mobile-appointment.tsx:389-417` | After booking, the shared appointment list is not refreshed, so the dashboard and My Appointments show the old list. | Call `invalidateAppointments()` on success, as web does. | Fixed |
| P8 | `M/direct/patient-dashboard-mobile.tsx`; `patient-mobile-appointment*.tsx` back buttons | Android hardware back leaves the dashboard instead of returning to Dashboard. On the standalone `/appointments/*` routes, `router.back()` has nothing to go back to when opened directly. | `BackHandler` in the dashboard shell. `canGoBack() ? back() : replace('/dashboard/patient')`. | Fixed |
| P9 | `patient-mobile-profile.tsx:50,58`; `patient-mobile-prescription.tsx:58`; `patient-main-dashboard-area.tsx:235`; `patient-mobile-consultation-history.tsx:48`; `patient-mobile-lab-results.tsx:64`; `patient-mobile-imaging-results.tsx:60` | Date-only values show a day early west of UTC. Age is wrong on the birthday. | `formatDateOnly` / `ageFromDateOnly` (S1). | Fixed |
| P10 | `patient-mobile-appointment.tsx:221-320` (TimePicker) | "9", "AM" and ":00" look selected before anything is picked. Accepting them gives "Please select a time". | Highlight only when a value is set. Add radio roles and checked state. | Fixed |
| P11 | all patient screens | Filter pills have no tab role or selected state. Expand toggles have no expanded state. Retry and "Show more" have no button role. Page and section titles are not headers. "View all" / "Manage" links are ambiguous. | Add roles, state, header roles and specific labels. | Fixed |
| P12 | `patient-mobile-appointment-status.tsx:435,484` | The New-appointment FAB covers the last card's Cancel and Details controls. | Pad the list by the FAB footprint. | Fixed |
| P13 | `patient-mobile-lab-results.tsx:87,163`; `patient-mobile-profile.tsx:97` | A non-string `result_data.comment` throws "Objects are not valid as a React child". Empty names render "UNDEFINEDUNDEFINED" initials. | Render the comment only when it is a string. Guard the initials. | Fixed |
| P14 | `hooks/use-appointments.ts` `isFutureSlot` / `isUpcomingAppointment` | These compare clinic wall-clock times with the device clock, so they are off on devices that are not on CAT. | New `clinicNowLocal()` in `hooks/use-appointments.ts`. `isFutureSlot` and `isUpcomingAppointment` compare the wall-clock value with the current time in the clinic. Shared with web: identical in CAT, and now also correct on devices or browsers outside CAT. | Fixed (follow-up) |
| P15 | `patient-mobile-appointment-status.tsx:56` | The appointment date has no year, so an appointment a year out is ambiguous. | Include the year. | Fixed |

## Doctor

| # | Where | Impact | Fix | Status |
|---|---|---|---|---|
| D1 | `M/direct/doctor/doctor-mobile-consultation.tsx:38-44,132,142`; `hooks/use-doctor-consultations.ts:21` | **Crash.** `statusConfig` has no CANCELLED or default case. A cancelled consultation red-screens the Consultations tab. | Add CANCELLED to the type, a Cancelled config and filter, and a default. | Fixed |
| D2 | `M/direct/doctor-dashboard-mobile.tsx:60-80`, `doctor-bottom-navbar.tsx:13-20` | Prescriptions, Medical Records and Labs are rendered by the shell, but nothing navigates to them. Doctors on a phone cannot open them. | "Clinical records" shortcut cards on the dashboard. The bottom navbar is left alone because the messaging session edits it. | Fixed |
| D3 | `M/direct/doctor/doctor-mobile-appointment.tsx:249-271` | No reschedule on mobile, and no "moved to …" notice. | Reuse the RN-only `DoctorReschedulePanel` (gated by `canReschedule`), plus a notice banner with `alert` role. | Fixed |
| D4 | `doctor-mobile-appointment.tsx:359`, `doctor-mobile-schedule.tsx:251`, `doctor-mobile-consultation.tsx:297`, and the patients / prescriptions / records / labs early returns | Every refetch (for example after Complete or Cancel) swaps the list for a spinner, jumps to the top and collapses the cards. | Show the full-screen loading or error state only when the list is empty, as web does. | Fixed |
| D5 | `doctor-mobile-schedule.tsx:184,192,217` | The "Today" stat counts CANCELLED appointments. The default filter shows cancelled rows. | Exclude cancelled from Today. Default to an "Active" filter, and add "All incl. cancelled", as web does. | Fixed |
| D6 | `doctor-dashboard-mobile.tsx:94` and every doctor sub-screen header | The top navbar already pads for `insets.top`. Each screen header adds it again, leaving a 47-59 pt blank band on notched iPhones. | Remove `insets.top` from the sub-screen headers. | Fixed |
| D7 | `doctor-main-dashboard-area.tsx:158` | "Good morning, Dr. Dr. Michael". | `doctorDisplayName(first, last)`. | Fixed |
| D8 | `doctor-topnavbar.tsx:25,31` | "Dr. Dr." in the avatar label. The initials are "DK", and it throws when `first_name` is null. | `doctorDisplayName` plus null-safe initials that skip the title. | Fixed |
| D9 | `doctor-mobile-profile.tsx:156`, `doctor-mobile-prescription.tsx:264`, `doctor-mobile-medical-records.tsx:189`, `doctor-mobile-lab-results.tsx` | Avatar initials are taken from "Dr. …", so every doctor shows "D…". | Strip the title before taking initials. | Fixed |
| D10 | `doctor-mobile-consultation.tsx:51` | The type chip shows "Unknown" when the appointment is not in the schedule response. | Neutral "Appointment" label, as web does. | Fixed |
| D11 | `doctor-mobile-patients.tsx:119,138`, `doctor-mobile-prescription.tsx:145,164`, `doctor-mobile-medical-records.tsx:131,150`, `doctor-mobile-profile.tsx:75` | Card components declared inside render remount every card on each search keystroke, which makes long lists janky. | Move them to module scope. | Fixed |
| D12 | `doctor-mobile-prescription.tsx:52-61`; `doctor-mobile-lab-results.tsx:58`; `doctor-mobile-consultation.tsx:55`; `doctor-mobile-patients.tsx:36-40` | "Expiring soon" disappears from 02:00 on the expiry day. Date-only values shift west of UTC. Age is off around birthdays. | S1 helpers. | Fixed |
| D13 | `doctor-main-dashboard-area.tsx:113-115` | At 375 pt, a video row's hint squeezes the patient-name column to zero width. | Move the hint under the name, and stop the badge shrinking. | Fixed |
| D14 | all doctor screens | Filter tabs have no role or selected state. Bottom tabs have no tab role, selected state or full label ("Appts", "Consults"). Titles are not headers. Expand toggles have no expanded state. Retry has no role. Search inputs have no label. | Add roles, state, labels and headers. The bottom-tab change is kept to the `TouchableOpacity` props, to keep the merge with the messaging session small. | Fixed |
| D15 | `doctor-mobile-appointment.tsx:294,328`, `doctor-mobile-schedule.tsx:194,220` | The "In Progress" appointment stat and filter can never be non-zero, because appointments have no IN_PROGRESS status. | Remove them. | Fixed |
| D16 | `doctor-mobile-prescription.tsx:32-50,73-78`; `doctor-mobile-lab-results.tsx:42-48,84-90` | CANCELLED, ON_HOLD, PENDING and CRITICAL statuses, and some routes, render as raw enum text. | Add labels and a readable fallback. | Fixed |
| D17 | `doctor-mobile-lab-results.tsx:233` | The search box relies on iOS-only `clearButtonMode`, so Android has no clear button. | Add a clear button. | Fixed |
| D18 | `doctor-mobile-schedule.tsx:110-175` | Schedule cards have no details (Patient ID, appointment #, notes) the way web does. | View details panel on each Schedule card: Patient ID, appointment #, reason and notes, as on web. The video-call status line is left out because call UI is out of scope. | Fixed (follow-up) |
| D19 | `doctor-dashboard-mobile.tsx` | Android back from a sub-screen leaves the dashboard. | `BackHandler` that returns to Dashboard. | Fixed |

## Caregiver (proxy)

| # | Where | Impact | Fix | Status |
|---|---|---|---|---|
| C1 | `M/proxy/patient/patient-proxy-mobile-add-dependent.tsx:80-84` | **Blocker.** `isValidDate` compares `toISOString()` of local midnight, so in Harare (UTC+2) every valid date of birth and expiry is rejected, and nobody can add a loved one. | `isValidDateOnly` (S1). | Fixed |
| C2 | `M/proxy/patient/patient-proxy-mobile-profile.tsx:271-390` | `Card`, `MInput`, `MTagInput` and the other field components are declared inside render. The keyboard closes after every character, and half-typed tags are lost. | Move them to module scope. | Fixed |
| C3 | `patient-proxy-mobile-profile.tsx:104-108,231,264,592` | Access presets and the CUSTOM toggle send `canMessageCareTeam: true`. The backend answers 403 "You can only reduce your own access", so tapping the already-selected "Full Access" makes every save fail. | Drop messaging from the presets and switches, and only ever send it as off-or-unchanged, as web does. | Fixed |
| C4 | `patient-proxy-mobile-profile.tsx:165-168,207-213` | When health fields are redacted, or the load failed, the editor pre-fills MALE / A+ / STABLE and Save writes them. Edit shows for caregivers who will get a 403. | Show Edit only with permission and a loaded patient. Show the health editor only when the record fields are present. No made-up defaults. | Fixed |
| C5 | `M/proxy/patient/patient-proxy-main-dashboard.tsx:148` | Upcoming keeps only SCHEDULED, so rescheduled visits drop out of the count and list. | SCHEDULED or RESCHEDULED, and in the future. | Fixed |
| C6 | `M/proxy/patient/patient-proxy-topnav.tsx:117-136` | The Log Out dropdown is positioned outside the navbar's bounds. On Android those touches are not delivered, so sign-out can be untappable, and the menu never closes on an outside tap. | Inline Sign out button using `useLogout()`, as the patient top bar does. | Fixed |
| C7 | `patient-proxy-main-dashboard.tsx:139` | The dashboard ignores the consultations that `useCaregiverActivity` already returns. | Add a "Recent consultations" card, using `doctorDisplayName` / `loadActiveDoctors` for doctor names. | Fixed |
| C8 | `M/proxy/patient/patient-bottom-navbar.tsx:17,19`; the dependent's Schedule / Medications / Documents / Health summary screens | Mobile has no per-dependent appointments, medications, documents or health-summary screens; web has them. | New native screens: Schedule tab (appointments, with Upcoming/Past/Cancelled/All and a loved-one picker), Medications, Documents & results (lab results), Health summary, Care plans and the Profile tab (account and linked loved ones). Reached from the bottom tabs, a new Records card on the dashboard, and Quick Actions on a loved one's profile. Each record is gated by the caregiver's permission (FULL_ACCESS or the flag, as the backend checks) and shows "not shared" instead of data. The Messages tab is left to the messaging work. | Fixed (follow-up) |
| C9 | `M/proxy/patient-proxy-dashboard-mobile.tsx:20-62` | Android back from Profile or Add loved one leaves the dashboard and loses the half-filled form. | `BackHandler`. | Partly fixed: Back from a sub-screen returns to the dashboard, but Back on step 2 or 3 of Add loved one or inside an open profile editor still discards what was typed (review PR 3 item 13; listed in TODO.md, "Native app phase") |
| C10 | `patient-proxy-main-dashboard.tsx:43-46,238-246` | Health status shows "Unknown" in warning colours when records are not shared with the caregiver. | Grey "Not shared" pill. | Fixed |
| C11 | `patient-proxy-mobile-profile.tsx:149,176,599` | The expiry field is pre-filled with a full ISO date-time under a YYYY-MM-DD placeholder and is never validated. The "leave blank for no expiry" hint is wrong. | Pre-fill the date, validate it, check it is not later than the current expiry, and correct the hint. | Fixed |
| C12 | `patient-proxy-mobile-profile.tsx:611` | "Authorized By" shows a raw system id. | "You" or the patient's name when they match. | Fixed |
| C13 | `patient-proxy-main-dashboard.tsx:56-60`, `patient-proxy-mobile-profile.tsx:65-68` | Age is a year off around birthdays. | `ageFromDateOnly`. | Fixed |
| C14 | `patient-proxy-mobile-add-dependent.tsx:89,99,286,631` | New dependents default to, and presets set, `can_message_care_team: true`, a messaging request the caregiver never asked for. | Default it off and remove it from the presets, as web does. | Fixed |
| C15 | `patient-proxy-mobile-add-dependent.tsx:457-459`, `patient-proxy-mobile-profile.tsx:393` | Double `insets.top` gap. The keyboard covers the lower fields. | Remove the extra inset. `KeyboardAvoidingView` plus `automaticallyAdjustKeyboardInsets`. | Fixed |
| C16 | add-dependent, profile, dashboard | Icon buttons have no labels. Switches have no switch role or checked state. Chips and level cards have no radio role or selected state. Card and page titles are not headers. Error banners have no alert role. | Add roles, state and labels. | Fixed |
| C17 | `patient-proxy-topnav.tsx:109`, `patient-proxy-main-dashboard.tsx:118,221`, `patient-proxy-mobile-profile.tsx:444` | At 375 pt a long name wraps the navbar, and rows overflow. | `numberOfLines` and `flexShrink`. | Fixed |
| C18 | `hooks/use-caregiver-dependents.ts:111,178` | If loading the current user fails, the hooks stay `loading` forever, and the dashboard spins with no retry. | Pass the user error through. | Fixed |
| C19 | `patient-proxy-mobile-add-dependent.tsx:428-436` | The success screen has no way to copy the generated password; web has one. | Copy button using `expo-clipboard` (~56.0.4, bundled in Expo Go). The password and email are also selectable text. | Fixed (follow-up) |
| C20 | `patient-proxy-main-dashboard.tsx:270` | "Add Another Loved One" shows even when there are none. | "Add a Loved One" when the list is empty. | Fixed |

## Found while verifying on the simulator

| # | Where | Impact | Fix | Status |
|---|---|---|---|---|
| V1 | `patient-mobile-profile.tsx` | The header and avatar card stayed pinned while only the details scrolled, leaving little room on small phones. | The whole page scrolls. | Fixed |
| V2 | `patient-main-dashboard-area.tsx` (Current Prescriptions) | The dose showed twice ("Amlodipine 5mg Tablets 5mg") when the medication name already contained it. | Append the dosage only when the name does not already include it. | Fixed |
| V3 | `doctor-mobile-appointment.tsx`, `patient-mobile-appointment-status.tsx` | After a reschedule the moved card re-sorts, and the "moved to …" notice sat above the list, out of view. | Scroll the list to the notice. | Fixed |
| V4 | `doctor-mobile-patients.tsx`, `doctor-mobile-prescription.tsx`, `doctor-mobile-medical-records.tsx` | With the search keyboard open, the first tap on a card only closed the keyboard. | `keyboardShouldPersistTaps="handled"` and dismiss on drag. | Fixed |
| V5 | patient sub-screens with a teal header | Dark status-bar icons sat on the teal header. | `<StatusBar style="light" />` on those screens. | Fixed |
| V6 | `doctor-main-dashboard-area.tsx` (new Clinical records card) | The Lab Results shortcut said "Lab and imaging results", but the doctor Labs screen shows lab results only. | "Lab test results". | Fixed |

## Verification

**Type check:** `npx tsc --noEmit -p .` in `frontend` is clean.

**Simulator.** Expo Go (SDK 56), on two simulators:
- iPhone 16, iOS 18.0, 393 pt
- iPhone SE (3rd gen), 375 pt

Both ran against the local API on `http://localhost:9000`, with the device time zone set to Africa/Harare (CAT, UTC+2). Screenshots are in `docs/mobile/screenshots/`.

**Auth:**
- A wrong password shows "Invalid email or password".
- Return on the password field signs in.
- Sign-out returns to login for all three roles.
- The session survives an app restart.
- The register form keeps focus while typing.

**Patient:**
- The dashboard shows Recent Consultations from finalised consultations.
- Consultation History shows Total / With diagnosis / Follow-ups, with no Draft or In-Progress filters, and "JB" initials.
- My Appointments: the time picker starts with nothing highlighted. A reschedule shows the "moved" notice, and the appointment stays counted as Upcoming.
- Profile shows Patient ID `PAT-00021`, the date of birth and age 68.
- Prescriptions shows the expiry date.

**Doctor:**
- The greeting reads "Dr. James Banda", with no double title.
- The new Clinical records shortcuts open Prescriptions. There is no blank band under the top bar.
- Appointments: a reschedule through the panel shows the "moved to Tue, 24 Nov 2026, 9:00 AM CAT" notice.
- Schedule defaults to Active, and "All incl. cancelled" is available.
- Consultations, including the Cancelled filter, opens without a crash.

**Caregiver:**
- The dashboard shows Recent consultations with "Dr. James Banda", and the Rescheduled label on the moved visit.
- The inline Sign out button works.
- In the profile's Personal Details editor, focus holds while typing. The edit was cancelled, so nothing was saved.
- Add Loved One accepts date of birth 1950-03-01 in CAT, which the old check rejected. A past expiry date and an empty relationship are both rejected, and no request reaches the API. The form was not submitted, so no test account exists.

**375 pt (iPhone SE):**
- The login screen fits.
- The doctor dashboard fits: the greeting wraps, the appointment rows keep the name and truncate the detail, and all six bottom tabs fit.

**Web regression:** the web app was rebuilt with `npm run e2e:build` and served from this worktree on :8081. The API's CORS only allows :8081, so another port fails sign-in with "Unable to reach the server".

`E2E_CHANNEL=chrome npx playwright test --project=chrome` was run with these specs:
- `fix-patient`
- `fix-doctor`
- `fix-caregiver`
- `smoke-records`
- `smoke-appointments`
- `smoke-caregiver`

Result: **40 passed**.

**Follow-up (C8, D18, C19, P14):** the type check is clean.
- **Caregiver, on the iPhone 16:**
  - The Schedule and Profile tabs open, and Schedule shows real appointments with the Rescheduled label and single "Dr." names.
  - From Agnes Zimba's profile, Quick Actions lists only the records she shares.
  - View Medications opens focused on her, and Back returns to her profile.
- **Web, after the clinic-time change in `use-appointments.ts`:** rebuilt and ran `fix-patient`, `smoke-appointments`, `fix-caregiver`, `smoke-caregiver` and `fix-doctor` in Chrome. **36 passed.**
- **Not clicked through on the simulator:** Documents, Health summary, Care plans, the doctor Schedule details panel and the copy-password button.

**Not verified here:**
- Android, including the hardware-back `BackHandler`. Only iOS simulators were run.
- VoiceOver. Roles and states were added in code but not listened to.
- Every screen at 375 pt. Only login and the doctor dashboard were checked on the SE.
