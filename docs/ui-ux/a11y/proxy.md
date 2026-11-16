# Accessibility pass: caregiver (proxy) web area

Wave 6, slice `web-a11y-proxy`. Scope: the caregiver shell (sidebar, top bar, phone drawer contents) and every screen under `frontend/src/components/web/proxy`, plus the registry in `components/web/sections/proxy.sections.tsx`. This pass closes the deferred findings of `docs/ui-ux/AUDIT.md` that fall in these files. It changes only accessibility: no behaviour, data or visible wording changed.

## Root cause of the deferred "visible text not in accessible name" finding

The older caregiver files hid their Ionicons with `importantForAccessibility="no"`. **react-native-web 0.21 does not map that prop to anything on the web.** It also ignores `accessibilityState`. So on the web the icon-font glyphs stayed in the accessibility tree, and axe counted them as visible text that was missing from the button names. The same applied to the expanded state of the "+N more" toggles and the busy state of "Message Care Team", which were never announced.

The fix uses the shared `Icon` primitive, which sets `aria-hidden`, or the `decorative` helper. It also uses the matching `aria-*` attributes: `aria-expanded`, `aria-busy`, `aria-checked` and `aria-current`.

## What was checked

Every file under `components/web/proxy/**` and the registry was read against these rules:

| Rule | How it was checked |
|---|---|
| Decorative icons and initials hidden | Every `Ionicons`, avatar-initials and status-dot element was looked at. Each one must be hidden with `aria-hidden` on the web, not only with `importantForAccessibility`. |
| Accessible names start with the visible text | Every `accessibilityLabel` was compared with the visible words of its control. |
| One page heading per section, sub-headings at level 2 | Every `accessibilityRole="header"` was checked for an explicit `aria-level`. Each section was checked for one level-1 heading. |
| Switches expose `role="switch"` and `aria-checked` | None of the caregiver files draws a switch of its own. The switches come from shared components (notification settings, permission fields), which are not owned here. |
| Status in text as well as colour | Health dots, access badges, appointment phases, critical results and highlighted rows were checked. |
| Dialogs and side panels trap focus and return it | Every dialog in scope uses the shared `Modal` or `SidePanel`, which use React Native's Modal. The phone drawer is `NavDrawer`. The account dropdown was the only custom popup. |
| Tables that become cards keep their labels | No caregiver screen uses a table. The stat and overview tiles keep a visible label beside each value. |
| No horizontal overflow at 375 px | Checked by reading the layout code: rows with fixed-width parts next to text were found and given `flexWrap` and `minWidth: 0`. |
| Contrast: 4.5:1 for text, 3:1 for non-text | Each text and background pair in the legacy files was measured with the WCAG formula (see the table below). Screens built from `@/components/web/ui` use the measured tokens. |

## What was changed

### Shell

- **Sidebar** (`patient-proxy-sidebar.tsx`):
  - The sidebar is now a `navigation` landmark named "Caregiver navigation".
  - All icons use `Icon`. The logo, avatar initials, health dots and rail health icons are `decorative`.
  - The loved ones are a list named "My loved ones", in both the full sidebar and the icon rail.
  - "+N more" announces `aria-expanded`. The redundant `accessibilityState` was removed.
  - The error "Retry" is named "Retry loading loved ones".
  - The "unknown health" dot changed from `#9CA3AF` (2.3:1) to `#6B7280` (4.4:1 on the sidebar).
- **Top bar** (`patient-proxy-topnavbar.tsx`):
  - The bar is the `banner` landmark.
  - The account dropdown's `menuitem`s now sit in a `role="menu"` named "Account". Before, axe reported them under `aria-required-parent`, which is critical.
  - The trigger has `aria-haspopup="menu"`, and `aria-controls` while the menu is open.
  - Keyboard behaviour follows the WAI-ARIA menu button pattern:
    - Opening the menu moves focus to the first item.
    - Arrow Up and Arrow Down move between items, and so do Home and End.
    - Space or Enter activates an item.
    - Escape closes the menu and returns focus to the button.
    - Tab closes the menu.
    - A click outside closes it.
  - The icons, avatar and chevron are hidden. `aria-disabled` replaces the ignored `accessibilityState` on "Log Out".

### Screens

- **Home** (`patient-proxy-main-dashboard.tsx`):
  - A visually hidden level-1 heading, "Caregiver dashboard", was added. Card titles are now level 2; they were all level 1 before. The "Medications and lab results" block already used levels 2 to 4.
  - All icons use `Icon`. The avatar initials and health dots are hidden.
  - Each loved-one row's name now starts with the visible name and then reads every fact the card shows, in order: "<name>, open profile, <access>, <age>, <gender>, health status <status>, blood type <type>, relationship <relationship>". A missing age or gender is left out; a missing blood type or relationship ("—" on screen) is read as "not recorded". It was "Open profile for <name>", which left out everything but the name.
  - "View all" is named "View all appointments". The arrow buttons are named without the "→" glyph.
  - "+N more" announces `aria-expanded`.
  - At 375 px, the "Today's Schedule" rows and the access banner wrap. The join button and "View Access" move under the text instead of squeezing it.
- **Health Summary** (`patient-proxy-health-summary.tsx`):
  - The page title is level 1 and each loved one's name is a level-2 heading. Their blocks ("Vitals & Symptoms", "Chronic Conditions", "Allergies & Contraindications", "Emergency Contacts") are level 3, nested under the name. Before, the blocks were level 2 like the name, so on a page with several loved ones a screen-reader user moving by heading could not tell whose "Chronic Conditions" they were in. `HealthSummarySection`, which the vitals slot (`sections/health-summary-section.tsx`) also uses, renders level 3.
  - The initials and status dot are hidden. The icons use `Icon`.
  - Two contrast fixes:
    - The unknown-status badge text went from 4.4:1 to 6.9:1.
    - The emergency phone number went from 4.4:1 to 6.9:1.
- **Shared states** (`proxy-states.tsx`): the icons use `Icon`, which also hides them on native.
- **Message Care Team** (`message-care-team-button.tsx`):
  - The button announces `aria-busy` while busy, and its spinner is hidden.
  - The reason shown beside it is hidden from assistive technology, because it is already part of the button's name.
- **Access requests** (`patient-proxy-access-requests.tsx`):
  - Request card titles are level 2; they were level 3 right under the page heading.
  - A highlighted request now also says "From your notification". Before, only the border colour showed it.
- **A loved one's profile** (`patient-proxy-web-profile.tsx`): the "Reduce access" button was named "Reduce your access to <name>", which does not contain its visible words. It is now "Reduce access to <name>".
- **Booking, Book For** (`booking/dependent-chooser.tsx`): the radio group follows the WAI-ARIA radio pattern.
  - The group is one Tab stop.
  - Arrow keys move to and choose the next person who can be chosen.
  - Space and Enter choose the focused person.

### Checked and already compliant (no change)

- Appointments and its booking panel, cancel dialog and appointment cards.
- Medications.
- Care Plans.
- Health Alerts.
- Billing.
- Documents & Results, with its record tabs, cards, side panel and the Documents, Referrals and Corrections tabs.
- Profile & Settings and its section wrapper.
- A loved one's profile: its editors and dialogs, and every button other than "Reduce access" (changed, see above).
- Add Loved One: the choice cards, both flows, the guardian checkbox and the step status.
- Messages and Notification Settings, both thin wrappers over shared screens.
- The section registry.

These files already use the shared primitives, explicit `aria-level`s, names that start with the visible label, and `aria-pressed` or `aria-checked`.

## Contrast measured (WCAG relative luminance)

| Pair | Ratio | Result |
|---|---|---|
| Purple `#7C3AED` on sidebar `#F5F3FF` | 5.20 | pass |
| Sidebar gray `#5B6472` on `#F5F3FF` | 5.45 | pass |
| Group label `#636A75` on `#F5F3FF` | 4.97 | pass |
| Health words `#166534`, `#92400E`, `#991B1B` on `#F5F3FF` | 6.50, 6.46, 7.58 | pass |
| Unknown dot `#6B7280` on `#F5F3FF` (non-text) | 4.41 | pass (was `#9CA3AF`, 2.31) |
| Access badge `#B45309` on `#FEF3C7` | 4.51 | pass |
| Access badge `#047857` on `#D1FAE5` | 4.84 | pass |
| Gray `#6B7280` on card `#F9FAFB` | 4.63 | pass |
| Video button `#047857` on its tint | 4.89 | pass |
| White initials on every avatar colour | 5.02 or more | pass |
| Unknown status `#4B5563` on `#F3F4F6` | 6.87 | pass (was `#6B7280`, 4.39) |
| Emergency phone `#4B5563` on `#FEF2F2` | 6.91 | pass (was `#6B7280`, 4.42) |
| "Message Care Team" disabled `#5B6472` on `#F3F4F6` | 5.44 | pass |

The bright health dot colours (amber `#F59E0B`: 1.96 on the sidebar) are decorative: the status word is always shown next to them, and in the icon rail a dark icon with a distinct shape per level is shown, with the status in the button's name.

## Accessible names that changed (for the e2e specs)

| Control | Before | Now |
|---|---|---|
| Loved-one row on the caregiver home | `Open profile for <name>` | `<name>, open profile, <access level>, <age> yrs, <gender>, health status <Stable/Monitor/Critical/Unknown>, blood type <type or "not recorded">, relationship <relationship or "not recorded">` |
| "Reduce access" on a loved one's profile | `Reduce your access to <name>` | `Reduce access to <name>` |
| "View all" in Today's Schedule | `View all` | `View all appointments` |
| "View health summary →" | `View health summary →` | `View health summary` |
| "View all appointments →" | `View all appointments →` | `View all appointments` |
| Retry in the sidebar | `Retry` | `Retry loading loved ones` |

Specs that use the old loved-one row name:

- `e2e/smoke-navigation.spec.ts:81`
- `e2e/smoke-caregiver.spec.ts:183`
- `e2e/fix-caregiver.spec.ts:29`, `:31`, `:131`, `:191`, `:279` and `:345`

Select the row with `getByRole('button', { name: new RegExp(`^${escapeRe(name)}, open profile`) })`, and replace `/^Open profile for /` with `/, open profile, /`. The "Log Out" and "Profile & Settings" menu items keep their role and names. No spec selects the "Reduce access" button by name.

Heading levels that changed on Health Summary: the loved ones' block titles are now level 3. `e2e/fix-caregiver.spec.ts:187` asserts a level-2 heading "Latest Vitals" there. That heading no longer exists on this page in any case: the vitals slot renders "Vitals & Symptoms" (now level 3) around `HealthPanel`, whose own cards are "Latest readings", "Trends" and "Symptoms". The assertion should become `getByRole('heading', { name: 'Vitals & Symptoms', exact: true, level: 3 }).first()` (reported as an integration request for e2e-existing-specs).

## Not verified here, still needs a manual pass

- This slice could not start the app, so nothing was run through axe or a browser. That covers the 375 px overflow check, the keyboard walk-through and the focus order. The caregiver sections should be added to `e2e/accessibility.spec.ts`, along with the phone-width overflow check:
  - the home;
  - appointments, including `mode=book`;
  - medications, with each of its tabs;
  - care plans;
  - documents and results, with each tab and the record side panel;
  - health summary;
  - health alerts;
  - billing;
  - profile and settings, with `tab=security`;
  - add loved one, in each mode;
  - a loved one's profile, with each editor open;
  - access requests.
- **Screen-reader pass:** none was done, on NVDA, JAWS, VoiceOver or TalkBack. Listen for:
  - the hidden "Caregiver dashboard" heading;
  - the account menu's announcements and the focus returning to the button;
  - the "Book For" radio group's arrow-key choice, which changes the booking form under it;
  - the loved-one row names;
  - the health status badges written in capitals ("STABLE"), which some screen readers may spell out.
- **Native (iOS and Android):** the `decorative` and `Icon` changes also apply there. The menu and radio keyboard handling is web only.
- **Shared components used by these screens and owned elsewhere** (reported as integration requests):
  - `CallJoinButton`: its Ionicons are not hidden from the accessibility tree. The informational state's name, "Video call with <name>: <label>", does not start with the visible label.
  - The phone drawer, `NavDrawer` (`layout/nav-drawer.tsx`), which holds the caregiver navigation on phones: its "Close menu" button draws a raw `Ionicons` with `importantForAccessibility="no"`, which react-native-web 0.21 ignores, so the glyph stays in the accessibility tree. This is the same root cause as above. It should use `Icon` from `@/components/web/ui`.
  - `HealthPanel` and `SectionCard`: inside a loved one's "Vitals & Symptoms" block (level 3) on Health Summary, the panel's cards "Latest readings", "Trends" and "Symptoms" are level 2, because `SectionCard` always renders level 2. Until `SectionCard` takes a heading level and `HealthPanel` passes one through, this part of the outline goes back up a level. **Known gap.**
  - The notification bell, `HealthPanel`, the shared booking, reschedule, care-plan, billing and documents components, and the shared notification-settings switches all belong to their own a11y slices.
