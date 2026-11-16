# UI/UX audit: web and mobile (November 2026)

Scope: every sidebar section of the web app for patient, doctor and caregiver, at 1440 px (desktop) and 390 px (phone width), plus the iOS app for all three roles. The audit ran on the combined code of PRs #2, #3 and #4.

- **Web:**
  - Each screen was captured with Playwright.
  - The screens were checked with axe for the WCAG 2.1 A/AA rules marked serious or critical.
  - A script measured elements running past the right edge.
  - Each screen was reviewed by eye.
- **Mobile:** the iOS Simulator (iPhone 16 Pro, Expo Go), using the screens and flows from the mobile work.

## Results

Same 33 web screens, before and after this change:

| Check | Before | After |
|---|---|---|
| Colour contrast (serious) | 24 screens | 2 screens, fixed after the re-run |
| Missing ARIA parent: tabs without a tablist (critical) | 3 | 0 |
| Scroll region not reachable by keyboard (serious) | 1 | 0 |
| Elements cut off at 390 px (excluding horizontal chip scrollers) | 46 | 16 |
| Visible text not in accessible name (serious) | 7 | 6, deferred (see below) |

The 16 remaining overflow hits are almost all filter-chip rows inside horizontal scrollers, which scroll on purpose. One is a prescription detail cell on the doctor screen.

## Findings and what was done

Ranked by how many people they affect and how badly.

### Fixed

1. **Low-contrast colours everywhere (web and mobile).**
   - **Problem:** mid-tone palette colours were used for small text, and white text sat on mid-tone fills. Examples:
     - teal `#0D9488` on its tint (3.3:1);
     - gray `#9CA3AF` for secondary text and inactive tab labels (2.5:1);
     - emerald, amber and red 500 for status text and badges (1.9 to 3.1:1);
     - white on emerald, amber, sky or teal buttons and avatars.
   - **Fix:** the same hues, one or two steps darker, in 76 files (74 components plus the two register-form colour files). The pairs found in the audit now measure 4.5:1 or more.

   | Was | Now |
   |---|---|
   | `#0D9488` | `#0F766E` |
   | `#9CA3AF` | `#6B7280` |
   | `#10B981` | `#047857` |
   | `#EF4444` | `#B91C1C` |
   | `#F59E0B`, `#D97706` | `#B45309` |
   | `#0EA5E9` | `#0369A1` |
   | `#8B5CF6` | `#6D28D9` |
   | `#3B82F6` | `#1D4ED8` |
   | `#4F6EF7` | `#4338CA` |

2. **Doctor and caregiver web at phone width kept a 68 px icon rail.** It took a sixth of the screen and pushed headers and Refresh buttons off the right edge, while the patient app already used a menu drawer.
   - Below 768 px all roles now use the same pattern: a menu button in the top bar opens the full sidebar as a drawer, with a backdrop, Close menu and Escape. Since the section-registry shell work the patient sidebar is also rendered through `NavDrawer` (it previously had its own overlay without a focus trap), and the administrator shell uses it too.
   - Code: `components/web/layout/nav-drawer.tsx`.
3. **Web page headers and stat cards broke at phone width.**
   - **Headers:** the title block didn't shrink, so the subtitle and the Refresh button ran past the edge. Header rows now wrap, and the title block shrinks.
   - **Stat cards:** on the patient and caregiver screens, four stat cards were squeezed into one row, with overlapping cards and cut labels ("Tot", "Upc"). They now wrap into a 2×2 grid. This covers 23 screens.
4. **Patient web top bar at phone width.** The bell drew over "Welcome back, Thomas!". The title now truncates.
5. **Accessibility bugs.**
   - Filter tabs on My Appointments and Consultation History had no tablist parent (axe critical). They now sit in a named tablist with `aria-selected`.
   - The caregiver Health summary scroll region couldn't be reached by keyboard. It is now focusable.
6. **Wrong icon.** Caregiver "Medications" used a pencil; it now uses the medkit icon that the patient Prescriptions screen uses.
7. **Mobile message box stayed tall after sending a long message** (iOS keeps a multiline input at its tallest size). It now grows from 1 to about 5 lines with the text and returns to one line after sending.
8. **Mobile safe-area gaps** (shipped in #4): doctor and caregiver screens no longer leave an empty status-bar band under the top bar, and screens no longer pad above the bottom tab bar.

### Recommended next (not changed here)

These need a product decision, or touch layout more broadly than a safe fix.

- **Patient web dashboard repeats its navigation three times:** the sidebar, six quick-action tiles and a "Quick Access" card list the same destinations. Keep the tiles, drop Quick Access, and show the "Welcome to CareNexus Direct" banner only on a first visit.
- **Two Sign Out buttons on web** (top bar and sidebar footer) for patient and doctor. Keep the top-bar one.
- **"Coming soon" items in the main navigation:** web Referrals, Documents, Settings, Help & Support and Care Plans; mobile caregiver tabs were the same before #3 and #4. Hide them until they exist; disabled entries add noise and look broken.
- **Link colour drifts from the role accent:** blue links ("View profile", "Manage", "View all") appear in the teal patient theme. Use the role accent for links.
- **Mobile doctor bottom bar has 7 tabs at 390 pt.** Labels shrink to fit, but it is crowded. Group Schedule / Appointments / Consults under one "Schedule" tab, or move Profile into the top bar.
- **Visible text missing from accessible names (axe, 6 screens):** icon-font glyphs (Ionicons) count as visible text on web, and some buttons have descriptive labels that don't start with their visible words. Hide decorative icons from the accessibility tree and start each label with the visible text.
- **Doctor web prescription detail cells** overflow by up to 60 px at 390 px; they should wrap.
- **Dark doctor headers on mobile sub-screens** (navy band under the white top bar) feel heavy next to the light patient and caregiver screens; consider the light header style.

## Verification

- **Web Chrome e2e:** `smoke-navigation`, `accessibility`, `messaging`, `fix-messaging`, `fix-doctor`, `fix-caregiver`, `fix-patient` and `smoke-comms` passed 69/69 against this branch's build.
  - The first run had 3 failures caused by the local Docker stack stopping mid-run. They passed once it was restarted.
  - One test changed: the caregiver 375 px test now opens the menu drawer before choosing Dashboard, as the other 375 px helpers already did.
- **Typecheck:** `npx tsc --noEmit -p .` is clean.
- **iOS Simulator:**
  - The new palette on the caregiver dashboard and tabs.
  - The message box grows with long text and shrinks back after sending (`screenshots/mobile-composer-*.png`).
- **Before and after at 390 px:** `screenshots/before-*` and `screenshots/after-*`.

Not verified: Android, and a screen-reader pass.
