# Accessibility pass: web (November 2026)

This pass follows the UI/UX audit in [AUDIT.md](AUDIT.md). It closes the deferred findings and applies the same rules to every web screen added since then. The edits change accessibility only: behaviour and data are unchanged, and no visible text was renamed.

The pass is split four ways. Each part has its own report:

| Part | Report |
|---|---|
| Shared components, admin area, sign-in and legal pages | this file |
| Patient area: shell and every screen under `components/web/direct/patient` | [a11y/patient.md](a11y/patient.md) |
| Doctor area: shell and every screen under `components/web/direct/doctor` | [a11y/doctor.md](a11y/doctor.md) |
| Caregiver area: shell and every screen under `components/web/proxy` | [a11y/proxy.md](a11y/proxy.md) |

## Scope of this part

- **Shared components:** everything under `frontend/src/components/web` except the role folders `direct/` and `proxy/` and the three role section registries. That covers:
  - the primitives (`ui`), `pickers` and `layout`;
  - `messages`, `notifications` and `help`;
  - the domain components: `account`, `ai`, `appointments`, `billing`, `billing-actions`, `call`, `caregiver-access`, `careplans`, `careteam`, `corrections`, `documents`, `health`, `medication`, `profile`, `records`, `referrals`, `reminders` and `settings`.
- **Admin area:** `components/web/admin` and its section registry `components/web/sections/admin.sections.tsx`.
- **Sign-in, account and legal pages:** the web screens under `frontend/src/app/auth` (sign-in, register, forgot and reset password, change password, verify email, confirm email change, accept terms) and `frontend/src/app/legal` (terms, privacy). The native sign-in and register screens (`app/auth/*/mobile`) were not changed.

## Rules checked

1. Decorative Ionicons glyphs and avatar initials are hidden from the accessibility tree.
2. Every `accessibilityLabel` starts with the control's visible text (WCAG 2.5.3, "label in name").
3. Headings:
   - one page heading (level 1) per section;
   - sub-headings at level 2 and below, with no skipped levels.
4. Switches have role `switch` and expose `aria-checked`.
5. Status is given in text as well as colour.
6. Dialogs and side panels:
   - trap focus;
   - close on Escape;
   - return focus to the control that opened them.
7. Tables that turn into cards on a phone keep their column labels.
8. No horizontal overflow at 375 px.
9. Contrast:
   - text at least 4.5:1;
   - control outlines and other non-text parts at least 3:1.

## How it was checked

The checks were done on the source. The web app was not run for this pass, so axe and the 375 px overflow script did not run against these changes.

- **Scripted scans of every file in scope** found:
  - `Ionicons` glyphs without `aria-hidden`;
  - `accessibilityRole="header"` without a level;
  - pressables without a role or an accessible name;
  - views with an `aria-label` but no role (axe flags `aria-label` on a generic `div`);
  - `accessibilityState` values that react-native-web 0.21 drops: `selected`, `checked` and `expanded` without a matching `aria-*` prop. react-native-web keeps only `disabled`.
  - text colours below 4.5:1 on white;
  - fixed widths that cannot fit 375 px.
- **Visible text against labels.** For every control with an explicit label, the visible text was compared with the label. axe's `label-content-name-mismatch` rule compares the same two strings:
  - case and punctuation are ignored;
  - `aria-hidden` text does not count;
  - the visible text must appear inside the name.
- **react-native-web 0.21 source**, to confirm two things the shared dialogs rely on:
  - `Modal` renders `role="dialog"` with `aria-modal`, traps Tab and puts focus back on the control that opened it when it closes;
  - `Switch` renders a checkbox input with `role="switch"`.
- **Reading by hand:** every file the scans pointed at, plus the primitives, the admin shell, the messages and notifications screens, the call screens and the auth screens.

## Results

| Rule | Result |
|---|---|
| 1. Decorative icons and initials hidden | Every `Ionicons` glyph in scope now has `aria-hidden`. Two sets of initials were exposed and are now hidden: the call-room participant tile and the messages avatar. The unread count badge inside a conversation row is hidden too, because the row's name already says "N unread". |
| 2. Label starts with visible text | The controls listed under [Accessible names that changed](#accessible-names-that-changed) had a label that did not contain or did not start with their visible text. All are fixed. The shared `Button`, `IconButton`, `LinkText` and `CallButton` build their names with `accessibleName`, so the visible label always comes first. |
| 3. Headings | 16 headings had no level, and react-native-web renders those as `h1`. They now have explicit levels. Conversation, new-conversation and members headings follow the layout: level 2 or 3 beside the list on a wide screen, level 1 or 2 when the pane fills a phone. The notifications heading is level 1 on its page and level 2 inside the bell's dialog. |
| 4. Switches | Already correct: `SwitchField`, the device-check toggles and the message box's "Mark as urgent" switch. The new-conversation urgent switch was named "Mark first message as urgent", which does not start with its visible "Mark as urgent"; it is now "Mark as urgent, first message". The urgent switches' off-state track was almost invisible on white (#E5E7EB), so it now uses #6B7280. |
| 5. Status not by colour alone | Three places showed status by colour only. The call-room tile's connection dot now has a visible "Poor" or "Lost" when the connection is weak, and the tile's name says "speaking", "muted" and "camera off". The admin rail's badge dot and the notification unread dot are hidden because the count or "unread" is in the name. The notification "Critical" and "Urgent" words are now always in the row's name. `StatusBadge` always shows its label. |
| 6. Dialogs and side panels | `Modal` and `SidePanel` (react-native-web Modal) already trap focus, close on Escape and return focus. `NavDrawer` and the notification bell panel have their own traps, which close on Escape and return focus to the menu or bell button. No changes were needed. |
| 7. Tables to cards | Admin `DataTable` cards already showed their labels (users, audit log), and so did the admin billing `RecordRow`, which has visually hidden column labels on wide screens and a visible label per value on phones. On a narrow card, a lab result's value lost its "Result" column name; it now reads "Result: …". |
| 8. No overflow at 375 px | The scan found no fixed width that cannot fit 375 px. The wide panes (messages list 340 px, bell panel 380 px, call-room people panel 360 px) are only used above their breakpoints. Not measured in a browser this time (see below). |
| 9. Contrast | All text colours in scope measure 4.5:1 or more against their background, and the light text in the call room sits on the dark room background. Several text-field outlines measured 1.2:1 (#E5E7EB). They now use #6B7280, which is 4.83:1 on white: the sign-in and register fields, the message box, the new-conversation subject and message fields, and the messages patient search. |

## What changed

Files are under `frontend/src/`.

**Call** (`components/web/call`)
- `call-ui.tsx`:
  - `CallButton`'s name now starts with its label (`accessibleName`).
  - The spinner inside a busy button is hidden.
  - `CenteredMessage` is the page heading (level 1).
- `call-screen.web.tsx`:
  - "Video consultation" is level 1 and "Confirm appointment time" is level 2.
  - The caregiver's "How you are joining" radios include their description in their name.
- `call-room.web.tsx`:
  - The room title is level 1.
  - The "audio paused" banner is named by its visible sentence.
  - Each participant tile is a named group that includes speaking, muted, camera off and connection quality. Its initials are hidden, and a weak connection is also shown as text.
  - Devices and People are toggle buttons with `aria-pressed`. This replaces `accessibilityState.selected`, which react-native-web drops.
- `call-admission-panel.tsx`:
  - The panel is a named region.
  - "Waiting to join" and "In the call" are level 2.
  - The connection dot is hidden (the status is in the text).
- `call-device-check.web.tsx`:
  - "Camera and microphone" is level 2.
  - The microphone level meter has role `img`.

**Messages** (`components/web/messages`)
- `ui.tsx`: `CountBadge` is decorative and `Avatar` is hidden on web.
- `messages-screen.tsx`:
  - The page title is level 1.
  - The New button is named "New conversation".
  - It passes the heading level to the new-conversation pane.
- `thread-list.tsx`:
  - The empty-state button is named "New conversation".
  - The open conversation has `aria-current`.
- `support-thread-list.tsx`: the open conversation has `aria-current`.
- `thread-view.tsx`:
  - The conversation title is level 2 beside the list and level 1 alone; day headings sit one level below it.
  - Each message is a named group: sender, full time and urgent. Its text is read as shown, not repeated in the name.
- `new-conversation.tsx`:
  - The heading level follows the layout.
  - The radio groups are named "Conversation type", "Who is this about?", "Patient", "Send to" and "Add people".
  - The type and recipient options are named by their visible title and description or role.
  - The fields and the urgent switch have 3:1 outlines.
- `member-manager.tsx`:
  - The panel is a named group.
  - "Members" is one level below the conversation title.
  - The add list is named.
- `patient-filter.tsx`:
  - The toggle's name starts with its visible "Patient: <name>".
  - The search field outline is 3:1.
- `composer.tsx`: the message box outline and the switch track are 3:1.

**Notifications** (`components/web/notifications/notification-list.tsx`)
- The heading is level 1 on the page and level 2 in the bell's dialog.
- "Mark all as read" is named by its visible words.
- Each row's name follows the visible order (title, time, priority, message) and ends with "unread".
- The unread dot is hidden.

**Help** (`components/web/help/help-support.tsx`)
- The page title is level 1.
- The email and phone links are named by the visible address or number first.

**Layout** (`components/web/layout`)
- `error-boundary.tsx`: "Something went wrong" is level 1.
- `section-host.tsx`: the section loader is a status region.
- `nav-drawer.tsx`: the close icon is hidden.

**Admin** (`components/web/admin/admin-sidebar.tsx`)
- The sidebar is a navigation landmark named "Administration menu".
- The collapsed rail's badge dot is hidden.

**Other components**
- `components/web/billing/invoice-list.tsx`: a row's name follows its visible text: number, status, who, dates, total and balance.
- `components/web/records/lab-result-card.tsx`: a stacked result shows "Result: <value>".
- `components/web/ui/fields-base.tsx`: the native radio fallback of `SelectField` reports `checked` (was `selected`).

**Auth** (`app/auth/login/web/login-web.tsx`, `app/auth/register/web/register-web.tsx`)
- The text-field outlines are 3:1.
- The register "Check your email" icon is hidden.

## Accessible names that changed

The end-to-end specs select some controls by accessible name, so they need these updates. Specs that match part of a name without `exact` still pass for the names marked "still matches".

| Control | Was | Now | Specs that use the old name |
|---|---|---|---|
| Messages "New" button, empty-list button | Start a new conversation | New conversation | smoke-comms:116, smoke-caregiver:133 and :146, fix-messaging:326, :350, :370 and :399 |
| Notifications "Mark all as read" | Mark all notifications as read | Mark all as read | fix-caregiver:242, smoke-comms:84 |
| Messages patient filter toggle | Filter by patient: <name> | Patient: <name>, change patient filter | fix-messaging:424, :430, :437 and :441 (exact) |
| New conversation type radios | Private conversation, … / Care team conversation, … | Private, Between … / Care team, The patient, … | smoke-comms:121; fix-messaging:327 and :353 (exact regex), :371, :381 and :401 (regex on the start of the name) |
| New conversation "Mark as urgent" switch | Mark first message as urgent | Mark as urgent, first message | fix-messaging:331 and :333 |
| New conversation recipient radio or checkbox | <name> | <name>, <role> (for example "Dr. James Banda, Doctor") | fix-messaging:328, :372, :382 and :402; smoke-comms:122 (exact) |
| Message bubble (named group) | <sender>, <time>, urgent: <text> | <sender>, <time>, urgent (the text is read from the bubble) | messaging:82 |
| Call "audio paused" banner | Turn on call audio | Your browser paused the call audio. Select here to hear others. Turn on call audio. | none (still matches) |
| Call "How you are joining" radios | Attending with <name> | Attending with <name>, <name> is with you or joining too. | video-call:25 (still matches) |
| Help email and phone links | Email <address> / Call <number> | <address>, send an email / <number>, call | none |
| Billing invoice row | Invoice <no>, <status>, … | <no>, <status>, <who>. <dates>. <total>, <balance>, invoice | none |
| Notification row | <title>, <priority>, <time>, <message>[, unread] | <title>, <time>, <priority>, <message>[, unread] | fix-caregiver:225 (regex, still matches) |

## Not done here, or still to check by hand

- **Screen-reader pass (manual).** No screen reader was used. Each item below needs a pass with NVDA and Firefox, VoiceOver and Safari, and TalkBack and Chrome:
  - **Messages:**
    - the order in which a conversation row, a message group and a pending message are read;
    - whether the `role="log"` history announces new messages without repeating old ones;
    - whether the urgent switch announces its helper text.
  - **Notification bell:** is the dialog announced on open, and does focus return to the bell on close?
  - **Call room:**
    - are the participant tile names read when tabbing past the grid;
    - are the Devices and People toggle states announced;
    - is the reconnecting alert announced once?
  - **Admin:**
    - navigating by the "Administration menu" landmark and the users and audit-log tables;
    - the row and column headers being read in table mode.
  - **Sign-in:** role radio group, error messages tied to the fields (`aria-invalid`), and the multi-factor steps.
  - **Legal and accept-terms:** the heading outline of a long document, and the scrollable document region.
- **Browser runs.** axe (with `label-content-name-mismatch` switched on) and the 375 px overflow script should run on every screen in scope after this change. That is wave 7's `e2e-existing-specs` work, and the request is recorded in the build plan.
- **Per-role areas.** The role shells, top bars and sidebars, and the screens under `direct/` and `proxy/`, are covered in the three role reports. The doctor web prescription cells that overflowed at 390 px (AUDIT.md) are in the doctor area.
- **Native screens.** Shared files that the phone app also uses (`messages/ui.tsx`, `messages/member-manager.tsx`, `ui/fields-base.tsx`) were changed in ways that are safe on native:
  - hidden decorations;
  - the radio state reported as `checked`;
  - an optional heading level.

  The phone app itself was not part of this pass.
