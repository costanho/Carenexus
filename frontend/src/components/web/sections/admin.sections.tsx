import { lazy } from 'react';
import { Screen } from '@/components/web/ui';
import type { SectionDef, SectionProps, SectionRegistry } from '@/lib/nav/sections';
import { HelpSupportSection, NotificationsSection } from './shared-sections';

// Administrator area sections. Adding a section is one entry here: the sidebar, the URL
// (`/dashboard/admin?section=<key>`), the home overview and notification links all read this list.
// Keys are stable: backend notification links depend on them (CONTRACTS 7.1 and 12.1):
//   ADMIN_CLINICIAN_QUEUE -> clinician-queue, ADMIN_ACCOUNT_REQUESTS -> account-requests,
//   ADMIN_BILLING and BILLING -> billing, CORRECTIONS -> corrections, MESSAGES -> messages (support inbox),
//   ACCOUNT_SECURITY -> account.
// Admin screens live in components/web/admin/admin-<section>.tsx and take SectionProps.

const AdminHome            = lazy(() => import('../admin/admin-home'));
const AdminUsers           = lazy(() => import('../admin/admin-users'));
const AdminUserDetail      = lazy(() => import('../admin/admin-user-detail'));
const AdminClinicianQueue  = lazy(() => import('../admin/admin-clinician-queue'));
const AdminAccountRequests = lazy(() => import('../admin/admin-account-requests'));
const AdminCorrections     = lazy(() => import('../admin/admin-corrections'));
const AdminBilling         = lazy(() => import('../admin/admin-billing'));
const AdminSupportInbox    = lazy(() => import('../admin/admin-support-inbox'));
const AdminAuditLog        = lazy(() => import('../admin/admin-audit-log'));
const AdminLegal           = lazy(() => import('../admin/admin-legal'));
const AdminFacilities      = lazy(() => import('../admin/admin-facilities'));
const AdminSystemSettings  = lazy(() => import('../admin/admin-system-settings'));
const AdminOutbox          = lazy(() => import('../admin/admin-outbox'));
const AccountSecurityPanel = lazy(() => import('../account/account-security-panel'));

/** Key of the development outbox. It is listed in the sidebar and on the home only where the installation has it. */
export const OUTBOX_KEY = 'outbox';

/**
 * Section `account`: the signed-in administrator's own password, sign-in email, two-factor authentication,
 * signed-in devices, data and legal acceptances (the ACCOUNT_SECURITY notification target for administrators).
 */
function AdminAccountSection({ theme }: SectionProps) {
  return (
    <Screen
      title="Account security"
      subtitle="Your password, sign-in email, two-factor authentication and the devices signed in to your account."
      theme={theme}
    >
      <AccountSecurityPanel role="admin" theme={theme} />
    </Screen>
  );
}

export const homeKey = 'dashboard';

export const sections: SectionDef[] = [
  {
    key: 'dashboard', label: 'Dashboard', icon: 'home-outline',
    render: p => <AdminHome {...p} />,
  },

  // People and accounts
  {
    key: 'users', label: 'Users', icon: 'people-outline', group: 'PEOPLE',
    render: p => <AdminUsers {...p} />,
  },
  {
    // Reached from Users, the audit log and the clinician queue; reads params.id (the account's system id).
    key: 'user-detail', label: 'User details', icon: 'person-outline', nav: false,
    render: p => <AdminUserDetail {...p} />,
  },
  {
    key: 'clinician-queue', label: 'Clinician verification', icon: 'shield-checkmark-outline', group: 'PEOPLE',
    render: p => <AdminClinicianQueue {...p} />,
  },
  {
    key: 'account-requests', label: 'Account requests', icon: 'person-remove-outline', group: 'PEOPLE',
    render: p => <AdminAccountRequests {...p} />,
  },

  // Records and money
  {
    key: 'corrections', label: 'Correction requests', icon: 'create-outline', group: 'RECORDS & BILLING',
    render: p => <AdminCorrections {...p} />,
  },
  {
    key: 'billing', label: 'Billing', icon: 'card-outline', group: 'RECORDS & BILLING',
    render: p => <AdminBilling {...p} />,
  },

  // Support and notifications
  {
    key: 'messages', label: 'Messages', icon: 'chatbubbles-outline', group: 'SUPPORT', badge: 'messages',
    render: p => <AdminSupportInbox {...p} />,
  },
  {
    key: 'notifications', label: 'Notifications', icon: 'notifications-outline', group: 'SUPPORT', badge: 'notifications',
    render: p => <NotificationsSection {...p} />,
  },
  {
    key: 'help-support', label: 'Help & Support', icon: 'help-circle-outline', group: 'SUPPORT',
    render: p => <HelpSupportSection {...p} />,
  },

  // Compliance
  {
    key: 'audit-log', label: 'Audit log', icon: 'document-text-outline', group: 'COMPLIANCE',
    render: p => <AdminAuditLog {...p} />,
  },
  {
    key: 'legal', label: 'Legal documents', icon: 'reader-outline', group: 'COMPLIANCE',
    render: p => <AdminLegal {...p} />,
  },

  // Installation
  {
    key: 'facilities', label: 'Facilities', icon: 'business-outline', group: 'SYSTEM',
    render: p => <AdminFacilities {...p} />,
  },
  {
    key: 'system-settings', label: 'System settings', icon: 'settings-outline', group: 'SYSTEM',
    render: p => <AdminSystemSettings {...p} />,
  },
  {
    // Development installations only. Routable everywhere (the screen says when it is not available here), but
    // listed only where the endpoint answers: see adminNavRegistry.
    key: OUTBOX_KEY, label: 'Outbox', icon: 'mail-outline', group: 'SYSTEM', nav: false,
    render: p => <AdminOutbox {...p} />,
  },

  // The administrator's own account
  {
    key: 'account', label: 'Account security', icon: 'lock-closed-outline', group: 'YOUR ACCOUNT',
    render: p => <AdminAccountSection {...p} />,
  },
];

export const registry: SectionRegistry = { role: 'admin', sections, homeKey };

const registryWithOutbox: SectionRegistry = {
  ...registry,
  sections: sections.map(s => (s.key === OUTBOX_KEY ? { ...s, nav: true } : s)),
};

/**
 * The registry as the sidebar and the home list it. The development outbox appears only when the installation
 * has it (`useAdminOutboxAvailable().available === true`); the routing registry is the same either way.
 */
export function adminNavRegistry(outboxAvailable: boolean): SectionRegistry {
  return outboxAvailable ? registryWithOutbox : registry;
}

/** The sidebar item that stands for a section without one of its own (user details belong to Users). */
export function adminNavKey(sectionKey: string): string {
  return sectionKey === 'user-detail' ? 'users' : sectionKey;
}
