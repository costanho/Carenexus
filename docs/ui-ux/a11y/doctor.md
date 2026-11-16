# Accessibility pass: doctor web area (wave 6)

Scope: the doctor dashboard shell (`components/web/direct/doctor-dashboard-web.tsx`, the section registry
`components/web/sections/doctor.sections.tsx`, the sidebar and the top bar) and every screen and part under
`components/web/direct/doctor/**` (64 files): Dashboard, Schedule, Availability, Consultations (list, editor, view),
Appointments, Patients (with the patient detail panel and its tabs), Medical Records, Documents, Prescriptions, Labs
& Results, Imaging, Care Plans, Referrals, Clinical Alerts, Billing, Profile and Settings. Messages, Notifications and
Help & Support are shared sections and belong to the shared pass.

Only accessibility changed. No visible text was renamed, no data or behaviour changed.

## How it was checked

- **Code review** of every file in scope against the rules below.
- **Contrast** of every hard-coded colour pair, computed with the WCAG 2.1 formula.
- **Type check** (`tsc --noEmit`) passes.
- **Not run:** axe in a browser, the 375 px overflow script and a screen reader. These need the web build served
  and a running API with seeded doctor data; this slice may not start a server, the API or a browser. See "Still to
  check by hand".

## Rules and results

| Rule | Result |
|---|---|
| Decorative icons and initials are hidden from the accessibility tree | Done. The last raw `Ionicons` (home dashboard, reschedule panel) now use the shared `Icon`, which is always hidden. Avatar initials on the home dashboard are hidden too. Every other doctor file already used `Icon` and `decorative`/`ariaHidden`. |
| Each accessible name starts with the visible text | Done. `Button`/`IconButton` already put the visible label first. The remaining cases were raw `Pressable`s and labels that read badly once the label was put first (list below). |
| One page heading per section, sub-headings at level 2 | Done. Every section renders `Screen` (h1) apart from the home dashboard, whose greeting is now the h1. Cards listed directly under the page heading now use level 2. Headings inside a card, panel or dialog use level 3, or level 4 below a level-3 card. The patient side panel (title at level 2) now draws its cards with level-3 titles and level-4 headings inside them. |
| Switches expose role switch with aria-checked | Already met. Every switch is the shared `SwitchField` (role `switch`, `aria-checked`): weekly-hours Bookable, Whole days, Show past time off, follow-up required, lab Critical. The doctor area has no other toggles. |
| Disclosure buttons expose their state | Done. Every button that shows or hides more of a card sets `aria-expanded`: the appointment Details button (already), and now "View full record"/"Show less" on record cards and "Show details"/"Hide details" on consultation and prescription cards. |
| Status in text as well as colour | Already met. Every status is a `StatusBadge` with its label, or a coloured pill with text. The coloured left border of appointment and record cards repeats a type that is also written in a badge. |
| Dialogs and side panels trap focus and return it | Already met. Every dialog and panel is the shared `Modal`/`SidePanel` (focus trap, Escape, focus return), and the phone drawer is `NavDrawer`. The reschedule panel opens inline, not as a dialog. |
| Tables that become cards keep their labels | Already met. The prescription detail grid, appointment details, invoice lines, patient facts and result summaries all show a label beside or above each value at every width. |
| No horizontal overflow at 375 px | Checked by reading the layout only (no browser). The prescription detail cells flagged in AUDIT.md (up to 60 px over at 390 px) now shrink (`minWidth: 0`, `maxWidth: '100%'`, basis 130 px) and wrap, so two fit across a 375 px card. Rows on other screens wrap or stack; no fixed width is wider than 343 px. |
| Text contrast 4.5:1, non-text 3:1 | Met for every pair measured (table below). One fix: the ICD-10 option chosen with the arrow keys was marked only by a pale tint (1.1:1). It now also has a 4 px accent bar (6.3:1). |

## What changed

| File | Change |
|---|---|
| `doctor-main-dashboard-area.tsx` | The greeting is the page heading (h1), and the "Today's Schedule" and "Coming Up" card titles are level 2. Raw Ionicons became the hidden `Icon`, and the stat-card icon box and patient initials are hidden. The loading spinner is a polite status named "Loading dashboard". The error block is an alert. |
| `doctor-reschedule-panel.tsx` | Raw Ionicon replaced. The heading level comes from the card it opens in (3, or 4 under a level-3 card). The time-slot loading spinner is a named status. |
| `doctor-web-appointment.tsx` | The patient name on each card is a heading: level 2 in the list, 3 under "Opened appointment" (new `headingLevel` prop). The Details button's name now starts with its visible text ("Details, show appointment details" / "Hide details, hide appointment details"). Before, it was "Show appointment details", which does not contain the visible word "Details" at the start. Stat tiles are named in the order shown ("3 Upcoming, View upcoming"); before, "Upcoming: 3. View upcoming" did not contain the visible text in order. |
| `doctor-web-schedule.tsx` | Appointment cards under a day's level-2 heading use level 3. |
| `doctor-left-sidebar.tsx`, `doctor-top-navbar.tsx` (earlier run of this slice) | Navigation and banner landmarks. The sidebar groups are named groups, and their uppercase titles are hidden so they are not read twice. Icons, unread dots and badge counts are hidden (the count is already in the item name, "Messages, 3 unread"). The Sign Out buttons are named by their visible text in both states. |
| `consultation/consultation-card.tsx`, `doctor-web-billing.tsx` (Uninvoiced rows) | The patient name of each list card is a level-2 heading. |
| `records/record-card.tsx`, `results/lab-result-views.tsx`, `results/imaging-result-views.tsx` | List card titles moved from level 3 to level 2 (they sit directly under the page heading). |
| `patient-detail/panel-card.tsx` (new) | `PanelCard`: the SectionCard look with a level-3 title, for cards inside the patient side panel, whose own title is level 2. The shared `SectionCard` always draws level 2. |
| `patient-detail/patient-overview.tsx` | Health status, Allergies and chronic conditions, Appointments with you, Demographics and Emergency contacts are `PanelCard`s (level 3). "Allergies", "Chronic conditions" and "Recent appointments" inside them are level 4. |
| `patient-detail/patient-history-tab.tsx` | The five History blocks are `PanelCard`s (level 3); the item titles inside them are level 4. |
| `disclosure-button.tsx` (new) | `DisclosureButton`: the ghost small button with a chevron, plus `aria-expanded` (and `accessibilityState.expanded`). The shared `Button` has no expanded state. The visible text and the accessible name are unchanged. |
| `records/record-card.tsx`, `consultation/consultation-card.tsx`, `prescription/prescription-card.tsx` | "View full record"/"Show less" and "Show details"/"Hide details" use `DisclosureButton`, so they announce expanded or collapsed. |
| `doctor-web-medical-records.tsx` | The search box name starts with the words shown in it: "Search by title or ICD-10 code, medical records". |
| `patient-detail/doctor-patient-panel.tsx`, `patient-detail/doctor-patient-tabs.tsx`, `patient-detail/patient-history-tab.tsx`, `consultation/consultation-invoice-card.tsx`, `results/attachments.tsx` | Extra context was reworded so the name reads naturally after the visible label. Before, the result was "Book appointment, Book an appointment for ...". Now: "Book appointment for Thomas Davis", "Open in Care Plans, the care plans of ...", "Edit in Care Plans, <plan>", "View yours: the consultations you wrote for ...", "Create invoice for the consultation with ...", "Remove file, <title>". |
| `records/icd-code-field.tsx` | Visible accent bar on the active suggestion. |

## Contrast measured

| Pair | Ratio |
|---|---|
| Sidebar text `#8BA3BE` and group titles `#8196AE` on navy `#0D1B2E` | 6.66 / 5.69 |
| Active item, white on `#3B5BDB` | 5.67 |
| Sign Out `#F87171` on its tinted navy | 5.89 |
| Home status badges: amber `#B45309` on `#FEF3C7` | 4.51 |
| Home status badges: green `#047857` on `#D1FAE5` | 4.84 |
| Home status badges: red `#B91C1C` on `#FEE2E2` | 5.30 |
| Home status badges: violet `#6D28D9` on `#F5F3FF` | 6.48 |
| Home status badges: indigo `#4F46E5` on `#EEF2FF` | 5.62 |
| Muted text `#6B7280` on white / on `#F9FAFB` | 4.83 / 4.63 |
| White on the avatar colours (teal, violet, red, amber, green, indigo) | 5.02 to 7.10 |
| Record-type badges (eight types and the fallback) on their tints | 5.21 to 10.57 |

## Still to check by hand

1. **axe and the 375 px overflow script on every doctor screen.** Include the states that only appear on demand
   (this also confirms that the record, consultation and prescription cards report `aria-expanded`, and that the
   patient panel's headings run 2, 3, 4):
   - Appointments: an expanded card and the reschedule panel.
   - Prescriptions: the New prescription panel and the Edit, Edit schedule times and status dialogs.
   - Patients: the patient panel on every tab.
   - The record, result and referral panels.
   - The billing invoice form.
   - The ICD-10 suggestions list.
2. **Screen-reader pass** with NVDA + Chrome and VoiceOver + Safari:
   - Moving by heading through Appointments, Schedule and Patients.
   - Choosing a time in the reschedule radio group.
   - The ICD-10 combobox: the active option is announced through `aria-activedescendant`, and the add/remove status messages are read.
   - That the tabpanels on Labs, Imaging and the patient panel are announced with their tab names.
3. **Patient ID line on patient cards.** It carries `aria-label="Patient ID PAT-…"` on plain text. VoiceOver reads it, but NVDA and JAWS ignore names on generic text and read only the id. If that matters, show "Patient ID" as visible text (a visible-text change, so it waits for wave 7).
4. **Stat tiles that are not buttons.** They carry a name on a generic element; check how each screen reader reads them.

## Needed outside this slice

- Optional clean-up, not needed for the result: if `SectionCard` gains a `headingLevel` prop and `Button` an
  `expanded` prop (requested by the shared pass), `PanelCard` and `DisclosureButton` can be replaced by them.
- **e2e:**
  - Add the doctor screens and states above to `e2e/accessibility.spec.ts`, and turn the `label-content-name-mismatch` check back on (gap G12).
  - Renamed accessible names, all of which still match the existing selectors:
    - The appointment Details button still ends in "appointment details" and still contains "Show appointment details".
    - Sign Out is matched case-insensitively.
