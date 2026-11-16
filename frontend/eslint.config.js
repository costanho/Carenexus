// ESLint flat config for the Expo app (web, native screens and the Playwright suite).
//
// `npm run lint` runs ESLint with --max-warnings 0, so a warning fails CI exactly like an error.
// Rules: the Expo SDK preset, eslint-config-expo/flat (core, TypeScript, React, React Hooks with the
// React Compiler checks, import). See https://docs.expo.dev/guides/using-eslint/.
//
// Baseline
// --------
// Lint was switched on after most of the app was written. Each finding that existed then is baselined
// below: the rule is turned off for exactly the files that violated it (131 files across 16 rules),
// and for no other rule. Every other file, and every new file, is held to the whole preset.
//  - Paying a finding off: fix the file, delete its path from that rule's list, run `npm run lint`.
//  - Never add a path to get new code past the linter. A finding that is intentional gets a scoped
//    `// eslint-disable-next-line <rule> -- <reason>` comment on the line instead.
//  - Unused eslint-disable comments are reported (ESLint default) and fail with --max-warnings 0.

const { defineConfig } = require('eslint/config');
const expoConfig = require('eslint-config-expo/flat');

/** rule -> files (relative to frontend/) where the rule is off; each entry says why. */
const BASELINE = {
  // React Compiler preset (eslint-plugin-react-hooks 7). setState called synchronously inside
  // useEffect, mostly to reset loading or form state when an id or prop changes. Moving each one
  // to derived state or a keyed remount changes when the screen re-renders, so it is a per-screen
  // refactor with a browser check, not a lint fix.
  'react-hooks/set-state-in-effect': [
    'src/app/auth/accept-terms/web/accept-terms-web.tsx',
    'src/app/auth/confirm-email-change/web/confirm-email-change-web.tsx',
    'src/app/auth/forgot-password/web/forgot-password-web.tsx',
    'src/app/auth/login/web/login-web.tsx',
    'src/app/auth/verify-email/web/verify-email-web.tsx',
    'src/components/mobile/direct/patient/patient-mobile-appointment-status.tsx',
    'src/components/mobile/messages/mobile-messages-screen.tsx',
    'src/components/mobile/messages/mobile-thread-view.tsx',
    'src/components/mobile/proxy/patient-proxy-dashboard-mobile.tsx',
    'src/components/mobile/proxy/patient/patient-proxy-mobile-health-summary.tsx',
    'src/components/mobile/proxy/patient/patient-proxy-mobile-profile.tsx',
    'src/components/mobile/proxy/patient/patient-proxy-mobile-shared.tsx',
    'src/components/web/admin/admin-audit-log.tsx',
    'src/components/web/admin/admin-billing.tsx',
    'src/components/web/admin/admin-corrections.tsx',
    'src/components/web/admin/admin-system-settings.tsx',
    'src/components/web/ai/detection-detail.tsx',
    'src/components/web/ai/run-assessment-dialog.tsx',
    'src/components/web/appointments/calendar-picker.tsx',
    'src/components/web/billing-actions/pay-dialog.tsx',
    'src/components/web/billing/invoice-detail.tsx',
    'src/components/web/call/call-device-check.web.tsx',
    'src/components/web/call/call-room.web.tsx',
    'src/components/web/call/call-screen.web.tsx',
    'src/components/web/careteam/add-care-team-member-dialog.tsx',
    'src/components/web/careteam/care-team-panel.tsx',
    'src/components/web/direct/doctor/consultation/consultation-editor.tsx',
    'src/components/web/direct/doctor/doctor-booking-dialog.tsx',
    'src/components/web/direct/doctor/doctor-reschedule-panel.tsx',
    'src/components/web/direct/doctor/doctor-web-ai-alerts.tsx',
    'src/components/web/direct/doctor/doctor-web-availability.tsx',
    'src/components/web/direct/doctor/doctor-web-billing.tsx',
    'src/components/web/direct/doctor/doctor-web-referrals.tsx',
    'src/components/web/direct/doctor/results/imaging-result-form.tsx',
    'src/components/web/direct/doctor/results/lab-result-form.tsx',
    'src/components/web/direct/doctor/results/shared.tsx',
    'src/components/web/direct/patient/patient-web-appointment-status.tsx',
    'src/components/web/documents/attachment-button.tsx',
    'src/components/web/layout/shell-support.ts',
    'src/components/web/messages/composer.tsx',
    'src/components/web/messages/messages-screen.tsx',
    'src/components/web/messages/new-conversation.tsx',
    'src/components/web/messages/thread-view.tsx',
    'src/components/web/proxy/patient/patient-proxy-health-summary.tsx',
    'src/components/web/proxy/patient/patient-proxy-web-profile.tsx',
    'src/components/web/records/records-screen.tsx',
    'src/hooks/use-admin-ops-outbox.ts',
    'src/hooks/use-billing-config.ts',
    'src/hooks/use-call-status.ts',
    'src/hooks/use-caregiver-dependents.ts',
    'src/hooks/use-doctor-dashboard.ts',
    'src/hooks/use-doctor-patients.ts',
    'src/hooks/use-draft.ts',
    'src/hooks/use-facilities.ts',
    'src/hooks/use-medication-schedule.ts',
    'src/hooks/use-message-recipients.ts',
    'src/hooks/use-notifications.ts',
    'src/hooks/use-paged.ts',
    'src/hooks/use-payments.ts',
    'src/hooks/use-public-settings.ts',
    'src/hooks/use-resource.ts',
    'src/hooks/use-support-threads.ts',
    'src/hooks/use-terminology.ts',
    'src/hooks/use-thread-messages.ts',
    'src/hooks/use-threads.ts',
  ],

  // React Compiler preset. ref.current read or written during render (latest-value refs read by
  // callbacks, focus-return and one-shot guards). The compiler does not optimise these functions;
  // rewriting them changes render behaviour and needs a per-screen check.
  'react-hooks/refs': [
    'src/app/auth/login/web/login-web.tsx',
    'src/components/mobile/proxy/patient/patient-proxy-mobile-shared.tsx',
    'src/components/web/ai/detection-detail.tsx',
    'src/components/web/appointments/slot-picker.tsx',
    'src/components/web/billing-actions/claim-form.tsx',
    'src/components/web/billing-actions/claim-tracker.tsx',
    'src/components/web/billing-actions/pay-dialog.tsx',
    'src/components/web/billing-actions/payment-history.tsx',
    'src/components/web/billing-actions/payment-methods-manager.tsx',
    'src/components/web/billing/amount-due-card.tsx',
    'src/components/web/billing/invoice-detail.tsx',
    'src/components/web/billing/invoice-list.tsx',
    'src/components/web/call/call-screen.web.tsx',
    'src/components/web/caregiver-access/activity-panel.tsx',
    'src/components/web/caregiver-access/grant-card.tsx',
    'src/components/web/careteam/care-team-panel.tsx',
    'src/components/web/direct/doctor/results/shared.tsx',
    'src/components/web/documents/upload-document-button.tsx',
    'src/components/web/layout/nav-drawer.tsx',
    'src/components/web/messages/thread-view.tsx',
    'src/components/web/proxy/patient/booking/dependent-chooser.tsx',
    'src/components/web/proxy/patient/booking/use-scheduling-refusal.ts',
    'src/components/web/proxy/patient/patient-proxy-care-plans.tsx',
    'src/components/web/proxy/patient/patient-proxy-topnavbar.tsx',
    'src/components/web/records/records-screen.tsx',
    'src/hooks/use-action.ts',
    'src/hooks/use-caregiver-dependents.ts',
    'src/hooks/use-dependent-records.ts',
    'src/hooks/use-medication-schedule.ts',
    'src/hooks/use-notifications.ts',
    'src/hooks/use-page-active.ts',
    'src/hooks/use-paged.ts',
    'src/hooks/use-payment-methods.ts',
    'src/hooks/use-payments.ts',
    'src/hooks/use-resource.ts',
    'src/hooks/use-section-nav.ts',
    'src/hooks/use-thread-messages.ts',
    'src/hooks/use-user-settings.ts',
    'src/lib/health/events.ts',
  ],

  // Apostrophes and quotes typed directly in JSX text (for example "don't"). React renders them
  // correctly; the rule guards against typos in markup. Fixing is a copy edit in app source.
  'react/no-unescaped-entities': [
    'src/components/mobile/direct/doctor/doctor-main-dashboard-area.tsx',
    'src/components/mobile/direct/patient/patient-topnavbar.tsx',
    'src/components/mobile/proxy/patient/patient-proxy-topnav.tsx',
    'src/components/web/ai/detection-detail.tsx',
    'src/components/web/ai/run-assessment-dialog.tsx',
    'src/components/web/billing-actions/claim-form.tsx',
    'src/components/web/call/call-device-check.web.tsx',
    'src/components/web/direct/doctor/doctor-main-dashboard-area.tsx',
    'src/components/web/direct/doctor/results/shared.tsx',
    'src/components/web/direct/patient/patient-top-navbar.tsx',
    'src/components/web/help/help-support.tsx',
    'src/components/web/messages/new-conversation.tsx',
    'src/components/web/messages/patient-filter.tsx',
    'src/components/web/proxy/patient/booking/cancel-appointment-dialog.tsx',
    'src/components/web/proxy/patient/patient-proxy-topnavbar.tsx',
    'src/components/web/records/consultation-card.tsx',
  ],

  // React Compiler preset, reported as a warning. react-hook-form's watch() returns values the
  // compiler cannot memoise, so it leaves those components unoptimised. Informational: the
  // components behave as plain React.
  'react-hooks/incompatible-library': [
    'src/app/auth/register/mobile/register-mobile.tsx',
    'src/app/auth/register/web/register-web.tsx',
    'src/components/mobile/direct/patient/patient-mobile-appointment.tsx',
    'src/components/web/admin/admin-billing.tsx',
    'src/components/web/appointments/booking-form.tsx',
    'src/components/web/careplans/care-plan-form.tsx',
    'src/components/web/careteam/add-care-team-member-form.tsx',
    'src/components/web/direct/doctor/doctor-web-availability.tsx',
    'src/components/web/direct/doctor/patient-detail/health-status-control.tsx',
    'src/components/web/direct/doctor/prescription/edit-prescription-dialog.tsx',
    'src/components/web/direct/doctor/prescription/prescription-form.tsx',
    'src/components/web/direct/doctor/records/record-form.tsx',
    'src/components/web/health/record-vitals-form.tsx',
    'src/components/web/health/symptom-form.tsx',
    'src/components/web/proxy/patient/patient-proxy-web-add-dependent.tsx',
  ],

  // Unused imports, colour constants and StyleSheet objects, almost all in native (mobile) screens,
  // plus one unused import in each of two call files. Removal is a source edit in files this slice
  // does not own.
  '@typescript-eslint/no-unused-vars': [
    'src/components/mobile/direct/doctor/doctor-mobile-lab-results.tsx',
    'src/components/mobile/direct/doctor/doctor-mobile-medical-records.tsx',
    'src/components/mobile/direct/doctor/doctor-mobile-patients.tsx',
    'src/components/mobile/direct/doctor/doctor-mobile-prescription.tsx',
    'src/components/mobile/direct/doctor/doctor-mobile-profile.tsx',
    'src/components/mobile/direct/patient/patient-mobile-consultation-history.tsx',
    'src/components/mobile/direct/patient/patient-mobile-medical-records.tsx',
    'src/components/mobile/direct/patient/patient-mobile-profile.tsx',
    'src/components/mobile/proxy/patient/patient-proxy-mobile-add-dependent.tsx',
    'src/components/web/call/call-room.web.tsx',
    'src/components/web/call/call-screen.web.tsx',
  ],

  // Hooks whose dependency arrays intentionally omit or over-list a value (first-mount navigation,
  // user snapshots, a memo keyed on the list identity). Changing an array changes when the effect
  // fires, so each needs a behaviour test. Hooks that omit a value on purpose elsewhere carry an
  // inline disable comment on the line instead.
  'react-hooks/exhaustive-deps': [
    'src/app/_layout.tsx',
    'src/components/mobile/proxy/patient/patient-proxy-mobile-appointments.tsx',
    'src/components/shared/phone-input.tsx',
    'src/components/web/call/call-admission-panel.tsx',
    'src/components/web/call/call-screen.web.tsx',
    'src/components/web/direct/doctor/doctor-web-billing.tsx',
    'src/hooks/use-caregiver-dependents.ts',
    'src/hooks/use-doctor-prescriptions.ts',
  ],

  // React Compiler preset. Date.now() read during render for 'upcoming / past' comparisons in four
  // screens; moving the clock into state or a ticking hook is a behaviour change per screen.
  'react-hooks/purity': [
    'src/app/auth/login/web/login-web.tsx',
    'src/components/mobile/direct/doctor/doctor-main-dashboard-area.tsx',
    'src/components/mobile/direct/patient/patient-mobile-appointment-status.tsx',
    'src/components/mobile/proxy/patient/patient-proxy-mobile-appointments.tsx',
  ],

  // Three native doctor screens use `condition && action()` as a statement.
  'no-unused-expressions': [
    'src/components/mobile/direct/doctor/doctor-mobile-lab-results.tsx',
    'src/components/mobile/direct/doctor/doctor-mobile-medical-records.tsx',
    'src/components/mobile/direct/doctor/doctor-mobile-prescription.tsx',
  ],

  // Two hooks spell an inline element type as Array<{...}>; style only.
  '@typescript-eslint/array-type': [
    'src/hooks/use-doctor-availability.ts',
    'src/hooks/use-medical-records.ts',
  ],

  // @axe-core/playwright and qrcode-generator export their default under the same name as a named
  // export; the default import is the documented usage of both packages.
  'import/no-named-as-default': [
    'e2e/support/ui.ts',
    'src/lib/account/qr.ts',
  ],

  // React Compiler preset. Module-level caches assigned from a component (the admin sidebar outbox
  // flag and the shell's last-user cache). Moving them into a store is a refactor of shared shell code.
  'react-hooks/globals': [
    'src/components/web/admin/admin-sidebar.tsx',
    'src/components/web/layout/shell-support.ts',
  ],

  // A response type and the component that renders it share the name AdminUserDetail; renaming
  // either touches its importers.
  '@typescript-eslint/no-redeclare': [
    'src/components/web/admin/admin-user-detail.tsx',
  ],

  // One file imports the same module in two statements.
  'import/no-duplicates': [
    'src/components/web/proxy/patient/patient-proxy-topnavbar.tsx',
  ],

  // axios.create and axios.isAxiosError are used through the default export, which is the documented
  // axios API.
  'import/no-named-as-default-member': [
    'src/lib/auth/auth.interceptor.ts',
  ],

  // React Compiler preset. A handler reads a function declared later in the same component.
  'react-hooks/immutability': [
    'src/components/web/proxy/patient/patient-proxy-topnavbar.tsx',
  ],

  // React Compiler preset. One useMemo whose dependency list the compiler cannot preserve, so it
  // skips optimising that component.
  'react-hooks/preserve-manual-memoization': [
    'src/components/web/direct/doctor/doctor-web-prescription.tsx',
  ],
};

// Three files in the exhaustive-deps baseline also carry inline disable comments for that rule; with the
// rule off for the file those comments suppress nothing, so unused-directive reporting is off for these
// three files only. Remove a file here when it leaves the exhaustive-deps list above.
const UNUSED_DIRECTIVES_ALLOWED = [
  'src/components/shared/phone-input.tsx',
  'src/components/web/direct/doctor/doctor-web-billing.tsx',
  'src/hooks/use-caregiver-dependents.ts',
];

module.exports = defineConfig([
  {
    // Build output, caches and test reports; expo-env.d.ts is generated by Expo.
    ignores: [
      'dist/**',
      'web-build/**',
      '.expo/**',
      'e2e-report/**',
      'e2e-results/**',
      'playwright/.cache/**',
      'coverage/**',
      'expo-env.d.ts',
    ],
  },
  expoConfig,
  ...Object.entries(BASELINE).map(([rule, files]) => ({
    name: `baseline/${rule}`,
    files,
    rules: { [rule]: 'off' },
  })),
  {
    name: 'baseline/unused-directives',
    files: UNUSED_DIRECTIVES_ALLOWED,
    linterOptions: { reportUnusedDisableDirectives: 'off' },
  },
]);
