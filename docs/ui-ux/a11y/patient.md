# Accessibility pass: patient area (web)

Wave 6, slice `web-a11y-patient`. This pass covers the patient shell and every screen under
`frontend/src/components/web/direct/patient`:

- Shell: `direct/patient-dashboard-web.tsx`, `direct/patient/patient-left-sidebar.tsx`,
  `patient-top-navbar.tsx`, `patient-right-sidebar.tsx`, and the registry in
  `sections/patient.sections.tsx`.
- Screens: the dashboard home (`patient-main-dashboard-area.tsx`) and every `patient-web-*.tsx` file.
- Shared pieces: `patient-record-sections.tsx` and `call-time-pending.tsx`.

The changes are accessibility-only. No data, request, navigation or visible wording changed. The one layout
change is reflow on phones: two dashboard rows that scrolled sideways now wrap (see "Changed").

It closes the deferred findings of [AUDIT.md](../AUDIT.md) that fall in these files (gap-critic G12). The shared
primitives, the role-neutral feature components and the e2e specs belong to other slices. Findings in them are
listed under "Outside this slice".

## How it was checked

- **Code review of each file against the wave 6 rules:**
  - decorative icons and initials hidden;
  - accessible names start with, and contain, the visible text;
  - one page heading with sub-headings at level 2;
  - switches;
  - status shown in text as well as colour;
  - dialogs and panels;
  - table-to-card labels;
  - reflow at 375 px;
  - contrast.
- **react-native-web 0.21.2 source, to see what reaches the DOM:**
  - `importantForAccessibility="no"` and `accessibilityState` are **not** rendered on web. Only `aria-*` props
    and `role` are.
  - So `importantForAccessibility="no"` on an Ionicons glyph did not hide it. That is the cause the audit
    recorded for "visible text not in accessible name".
- **axe-core's `label-content-name-mismatch` logic (the version installed in `frontend/node_modules`):**
  - An `aria-label` on a widget must contain the visible text as a contiguous run of words. Punctuation, emoji
    and text in parentheses are ignored.
  - Visible text is measured visually, so `aria-hidden` text inside the control still counts.
  - Labels were rewritten to repeat the visible text in reading order, then add context.
- **Contrast** was measured with the WCAG relative-luminance formula for every text and background pair in the
  owned files. The ratios are below.
- **Not run here:** a browser, axe, the 375 px overflow script or a screen reader. This slice is built in an
  isolated workspace where starting the dev server or a browser is not allowed, so every check above is
  static. The results are therefore unverified in a browser. The e2e-support slice must add the patient screens
  to `e2e/accessibility.spec.ts` and the phone-width overflow check (see "Requests to other slices"), and the
  manual pass at the end of this file is still owed.

## Results by screen

| Screen | File | Result |
|---|---|---|
| Shell: sidebar | patient-left-sidebar.tsx | Fixed (icons, initials, emoji, landmark, groups, Sign out name) |
| Shell: top bar | patient-top-navbar.tsx, patient-dashboard-web.tsx | Fixed (icons, avatar, menu expanded state, Sign out name) |
| Shell: right rail | patient-right-sidebar.tsx | Fixed (icons, arrow glyph, contrast, landmark, status text) |
| Dashboard | patient-main-dashboard-area.tsx | Fixed (page heading, card names, icons, arrow glyphs, metrics as plain text, reflow at 375 px) |
| Book Appointment | patient-web-appointment.tsx | Page file reviewed, shared content not reviewed; booking form is shared (`appointments/booking-form`) |
| My Appointments (and Join Consultation) | patient-web-appointment-status.tsx | Fixed (opened-from-link card says so in words after its heading; stat figures read as plain text) |
| Consultation History, Medical Records (+ Corrections tab), Lab Results, Imaging Results | patient-web-consultation-history.tsx, -medical-records.tsx, -lab-results.tsx, -imaging-results.tsx, patient-record-sections.tsx | Page file reviewed, shared content not reviewed; cards, chips, stats and tabs are shared (`components/web/records`) |
| Prescriptions (Medications, Reminders) | patient-web-prescription.tsx | Fixed (tab content is a named tabpanel) |
| Vitals & Symptoms | patient-web-health-tracking.tsx | Page file reviewed (named tabpanels); tab row scrolls at 375 px (shared `Tabs`) |
| Care Plans | patient-web-care-plans.tsx | Page file reviewed, shared content not reviewed; content shared (`careplans`) |
| Health Alerts | patient-web-health-alerts.tsx | Page file reviewed, shared content not reviewed; content shared (`ai`) |
| Documents | patient-web-documents.tsx | Page file reviewed, shared content not reviewed; content shared (`documents`) |
| Referrals | patient-web-referrals.tsx | Page file reviewed, shared content not reviewed; content shared (`referrals`) |
| My Care Team | patient-web-care-team.tsx | Page file reviewed, shared content not reviewed; content shared (`careteam`) |
| My Proxies (Caregivers) | patient-web-caregiver-access.tsx | Page file reviewed (h1 and two h2, named list); content shared (`caregiver-access`) |
| Messages, Help & Support | sections/shared-sections.tsx | Shared, not reviewed here |
| Notifications | patient-web-notifications.tsx | Page file reviewed, shared content not reviewed; list shared (`notifications`) |
| Billing & Payments | patient-web-billing.tsx | Page file reviewed, shared content not reviewed; content shared (`billing-actions`) |
| Profile & Settings (+ Security tab) | patient-web-profile.tsx | Page file reviewed (named tag lists, status badges with text); tabs and cards shared (`profile`, `account`) |
| Settings | patient-web-settings.tsx | Shared (`settings/user-settings`), not reviewed here |

"Page file reviewed" means the owned page file (its heading, panels, names and layout) was checked line by
line and needed no change. The content inside those screens is rendered by the shared components named in the
same row, which belong to `web-a11y-shared` and were **not** reviewed line by line here (see "Outside this
slice"). A screen marked that way has not passed an accessibility review as a whole.

Rule by rule, for the owned files:

- **Page headings.**
  - Every section has one h1, from `Screen` or `RecordsScreen`.
  - The dashboard home had none. It now has a visually hidden h1 "Dashboard".
  - Sub-headings in owned files are level 2: dashboard cards, right-rail cards, "Appointment Booked!", the
    doctor name on appointment cards, "Caregivers with access" and "Consent history".
- **Switches:** there are none in owned files. Notification settings are shared.
- **Dialogs and side panels:**
  - The phone navigation drawer is the shared `NavDrawer`. It is a modal dialog with Escape and a focus trap,
    and focus returns to the menu button.
  - The menu button now reports `aria-expanded` and `aria-haspopup="dialog"`.
  - The patient files open no other dialogs. The health dialogs, the reschedule panel and the billing dialogs
    are shared.
- **Tables that become cards:** none in owned files.
- **Status in text:** every status uses a text badge or label:
  - appointment status and type, the "Due" / "Due now" dose badge;
  - right-rail urgency ("Starting soon!", "Tomorrow", ...);
  - prescription "Active", consultation "Completed".
  - The unread dot on the collapsed sidebar is backed by "N unread" in the item's name.
  - The accent border of a linked appointment now has visually hidden text "Opened from your link". It sits
    right after the card's h2 (the doctor name), so a user who reaches the card by heading navigation hears it.

## Changed

### Hidden from the accessibility tree

- **Icons:** every Ionicons glyph in the owned files now uses the shared `Icon`, which sets `aria-hidden`. That
  covers sidebar items, logo, Sign out, menu, avatar person, right-rail icons, dashboard tiles, metrics, rows,
  chevrons and the call-time note.
- **Avatar initials and pictures:** in the sidebar (full and collapsed) and the top bar.
- **Other decoration:** the 👋 after the patient's name, the welcome-banner circles, the unread dot on the
  collapsed rail, and every "→" arrow in owned files: the dashboard tiles, "View profile →", "View all
  consultations →", "View all prescriptions →", "Book one →" and, in the right rail, "Book an appointment →".
  Each arrow is a nested `Text` marked decorative, so the link or button is named by its words alone and the
  visible text (which e2e selects by) is unchanged.

### Accessible names (WCAG 2.5.3, Label in Name)

The visible text is unchanged.

| Control | Was | Now |
|---|---|---|
| Dashboard, Upcoming Appointments card | `Appointment with <doctor> on <when>` | `<doctor>, <when>, open appointment` |
| Dashboard, Recent Consultations row | `<title>, <doctor>, <day>. Open consultation` | `<day>, <title>, <doctor> (<specialty>), Completed. Open consultation` |
| Dashboard, Current Prescriptions row | `<name>, <frequency>, active. Open prescription` | `<name>, <frequency>, Prescribed <day>, <doctor>, Active. Open prescription` |
| Dashboard, Current Medications metric | `Current Medications: <n>. View details` | `Current Medications: <n>. Active prescriptions. View details` |
| Dashboard, other metrics (Conditions, Allergies, Blood Type) | `aria-label` on a generic element (dropped by react-native-web) | No name: the label, value and sub-line are plain text read in order. A "—" value is hidden; when no sub-line explains it, visually hidden "Not recorded" is read instead |
| My Appointments stat figures | value and label as text | Unchanged plain text; an unknown count's "–" is hidden and visually hidden "Not available" is read instead |
| Next medication dose, Open schedule | `Open the medication schedule` (the shared Button already prefixed the visible label) | `Open schedule of your medication doses` |
| Sidebar item with more than 99 unread | `<label>, <n> unread` | `<label>, 99+ unread` (matches the visible "99+") |
| Sign out (sidebar and top bar) while signing out | `Sign out` | `Signing out` |

These names were already correct and are unchanged:

- sidebar items;
- `Open menu`;
- `<Full name>, open profile and settings`;
- `Sign out`;
- the quick-action tiles (`Book Appointment. Schedule a new appointment`);
- the "Your Care" links;
- right-rail `Message <doctor>`, `Retry loading appointments` and Quick Access rows;
- My Appointments `Reschedule appointment with <doctor>`, `Cancel appointment with <doctor>`,
  `Message <doctor>`, `Refresh appointments`;
- `Grant access to a caregiver`.

### Landmarks, groups and states

- **Sidebar:**
  - It is a `navigation` landmark named "Patient navigation".
  - Each group (Appointments, Consultations, My health, Records & results, Care & support, Account) is a named
    `group`. The capitalised group text is its visual form.
- **Right rail:** it is a `complementary` landmark named "Upcoming appointment and quick access". Its loading
  text is a `status` and its error text an `alert`.
- **Dashboard card states:**
  - Loading text is a `status`, and error text an `alert`.
  - Retry stays named "Retry loading".
- **Prescriptions tabs:** the tab content is a `tabpanel` named after the selected tab, with the same spacing as
  before.
- **Busy controls:** Sign out reports `aria-busy` while signing out.

### Contrast

All pairs are text unless marked.

| Where | Was | Now |
|---|---|---|
| Right rail urgency badge "Tomorrow" (white on orange) | 2.80:1 (`#F97316`) | 5.18:1 (`#C2410C`) |
| Right rail time, "View all" and button text in the "Tomorrow" colour | 2.64:1 on `#FFF7ED` | 4.88:1 |
| Right rail date, "with Dr …", type, duration and facility (`#6B7280`) on the teal and red tints | 4.33:1, 4.42:1 | 6.77:1, 6.91:1 (`#4B5563`) |
| Right rail "No upcoming appointments" and "Loading…" on `#F3F4F6` | 4.39:1 | 6.87:1 (`#4B5563`) |

These pairs were checked and already pass, so they are unchanged:

- teal `#0F766E` on white (5.47) and on its tint (4.90);
- `#6B7280` on white (4.83);
- `#636A75` group labels (5.46);
- link `#4338CA` (7.90);
- amber note `#B45309` on `#FFFBEB` (4.84);
- red `#B91C1C` on `#FEF2F2` (5.91) and on `#FFF5F5` (6.05);
- white on teal (5.47);
- the banner text (9.37, 10.46);
- the indigo video button `#4F46E5` on `#EEF2FF` (5.62);
- the status tones from `ui/tokens.ts` (5.21 or more).

The card and divider borders are decorative. The shared inputs use `borderInput` (4.83:1).

### Reflow at 375 px

On a phone the dashboard's quick-action tiles and the four Health Overview figures sat in sideways scrollers,
which the overflow check reports. They now wrap two to a row, and their content is unchanged. The upcoming
appointment cards, the two-column rows and the care links already stacked or wrapped.

## Outside this slice (for web-a11y-shared)

These were seen while reviewing the patient screens. They are in files this slice does not own.

- **`ui/tabs.tsx`:**
  - The row is a horizontal ScrollView.
  - At 375 px the seven My Appointments filters and the three Vitals & Symptoms tabs overflow it, and the
    overflow check reports scrollable elements.
  - Tabs expose no ids, so a screen cannot point its tabpanel at the tab with `aria-labelledby`. The patient
    screens name each panel with `aria-label` instead.
- **`call/call-join-button.tsx`:**
  - Its Ionicons glyphs are not hidden.
  - The join button is named `<label>: video call with <doctor>`, which starts with the visible label and is
    fine.
  - The informational state puts an `aria-label` on a `text` role.
- **`billing/amount-due-card.tsx`, `health/latest-vitals-card.tsx`, `records/*`, `medication/*`,
  `reminders/*`, `careplans/*`, `careteam/*`, `documents/*`, `referrals/*`, `ai/*`, `notifications/*`,
  `caregiver-access/*`, `profile/*`, `account/*`, `settings/*` and `appointments/*`:** these carry most of the
  patient screens' content. They were not reviewed line by line here.

## Requests to other slices

- **e2e-existing-specs (W7), `frontend/e2e/smoke-navigation.spec.ts` line 37:**
  - The dashboard Upcoming Appointments card is now named `<doctor>, <when>, open appointment`.
  - Change the regex to:
    ```
    /^Dr\. .+, \d{1,2} \w{3} \d{4} • \d{2}:\d{2}, open appointment$/
    ```
- **e2e-support / e2e-existing-specs, `frontend/e2e/accessibility.spec.ts`:**
  - Enable `label-content-name-mismatch` for the patient screens.
  - Run axe and the 375 px overflow check on the dashboard home and on each section below:
    - my-appointments, book-appointment, consultation-history;
    - prescriptions, and prescriptions&tab=reminders;
    - health-tracking (all three tabs), care-plans, health-alerts;
    - medical-records, and medical-records&tab=corrections;
    - lab-results, imaging-results, documents, referrals;
    - care-team, my-proxies, notifications, billing;
    - profile-settings, and profile-settings&tab=security;
    - settings, help-support.
  - Also run them with the phone drawer open.
  - New stable locators:
    - `getByRole('navigation', { name: 'Patient navigation' })`;
    - `getByRole('complementary', { name: 'Upcoming appointment and quick access' })`;
    - the dashboard `getByRole('heading', { level: 1, name: 'Dashboard' })`.

## Still needs a manual screen-reader pass

Not done in this slice: there was no browser or assistive technology. Check with VoiceOver (Safari) and NVDA
(Firefox or Chrome):

1. **Sidebar:**
   - The navigation landmark and the group names are announced once each, with no extra reading of the
     capitalised group text.
   - The active item reads as "current page".
   - Unread counts are read once.
2. **Phone drawer:**
   - Opening it announces a dialog, and the menu button reports expanded.
   - Focus stays inside the drawer.
   - Escape and Close return focus to the menu button.
   - Choosing an item lands focus on the new section.
3. **Dashboard:**
   - The hidden h1 is found by heading navigation.
   - The Conditions, Allergies and Blood Type figures are read in browse mode (label, value, sub-line), and a
     missing blood type is read as "Not recorded" once, not as "dash".
   - Card buttons read their visible text, then "open …".
   - Loading and error text are announced when they appear, without noise on every refresh.
4. **Right rail:** the urgency label, the date and time, and the Join / View Details / Reschedule or Cancel /
   Message buttons read in order.
5. **My Appointments:**
   - The tablist arrow keys work.
   - "Show details" reports expanded or collapsed.
   - The reschedule panel and cancel confirmation move and return focus.
   - "Opened from your link" is read right after the doctor-name heading on a linked card.
   - The stat figures (Upcoming, Completed, ...) are read in browse mode.
6. **Prescriptions and Vitals & Symptoms:** tab changes are followed by the named tab panel.
7. **Visible focus:** check the focus ring on every TouchableOpacity in the shell and dashboard, at 100% and 200%
   zoom.
