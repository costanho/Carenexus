import { lazy } from 'react';
import type { CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import type { SectionDef, SectionProps, SectionRegistry } from '@/lib/nav/sections';
import { PatientProxyMainDashboard } from '../proxy/patient/patient-proxy-main-dashboard';
import { markDependentsStale } from './proxy-dependents';
import { HelpSupportSection, NotificationsSection, useThreadParam } from './shared-sections';

// Caregiver (proxy) dashboard sections. Adding a section is one entry here: the sidebar, the URL
// (`/dashboard/proxy?section=<key>`) and notification links all read this list.
// Keys are stable: backend notification links depend on them (CONTRACTS 7.1 and 12.1).
// The loved one a screen is about travels in the `patient` param (a PAT system id); every section that shows a
// loved one's data reads it, so a link from a profile or a notification opens that person only.
//
// Notification targets (CONTRACTS 7.1) and where they land:
//   APPOINTMENTS        appointments&patient
//   CONSULTATIONS       documents-results&tab=consultations&patient&id
//   PRESCRIPTIONS       medications&patient&id
//   MEDICATION_SCHEDULE medications&tab=schedule&patient&date[&id]
//   LAB_RESULTS         documents-results&tab=labs&patient&id
//   IMAGING_RESULTS     documents-results&tab=imaging&patient&id
//   MEDICAL_RECORDS     documents-results&tab=records&patient&id
//   DOCUMENTS           documents-results&tab=documents&patient&id
//   REFERRALS           documents-results&tab=referrals&patient&id
//   CORRECTIONS         documents-results&tab=corrections&patient&id
//   CARE_PLANS          care-plans&patient&id
//   HEALTH_TRACKING     health-summary&patient
//   AI_ALERTS           health-alerts&patient&id
//   BILLING             billing&patient&id
//   CAREGIVER_ACCESS    access-requests&id (a request decision), profile-settings&patient (a grant change)
//   CARE_TEAM           patient-profile&patient
//   MESSAGES            messages&thread
//   ACCOUNT_SECURITY    profile-settings&tab=security

const ProxyAppointments         = lazy(() => import('../proxy/patient/patient-proxy-appointments'));
const ProxyMedications          = lazy(() => import('../proxy/patient/patient-proxy-medications'));
const ProxyCarePlans            = lazy(() => import('../proxy/patient/patient-proxy-care-plans'));
const ProxyMessages             = lazy(() => import('../proxy/patient/patient-proxy-messages'));
const ProxyDocuments            = lazy(() => import('../proxy/patient/sections/documents-section'));
const ProxyHealthSummary        = lazy(() => import('../proxy/patient/sections/health-summary-section'));
const ProxyHealthAlerts         = lazy(() => import('../proxy/patient/patient-proxy-health-alerts'));
const ProxyBilling              = lazy(() => import('../proxy/patient/patient-proxy-billing'));
const ProxyProfileSettings      = lazy(() => import('../proxy/patient/sections/profile-settings-section'));
const ProxyNotificationSettings = lazy(() => import('../proxy/patient/patient-proxy-notification-settings'));
const ProxyAddDependent         = lazy(() => import('../proxy/patient/patient-proxy-web-add-dependent'));
const ProxyPatientProfile       = lazy(() => import('../proxy/patient/sections/patient-profile-section'));
const ProxyAccessRequests       = lazy(() => import('../proxy/patient/patient-proxy-access-requests'));

export const homeKey = 'dashboard';

/** Opens the profile of a linked patient (the hidden `patient-profile` section). */
export function openPatientProfile(navigate: SectionProps['navigate'], dependent: CaregiverDependent): void {
  navigate('patient-profile', { patient: dependent.patientSystemId });
}

function MessagesSection({ params }: SectionProps) {
  const thread = useThreadParam(params);
  return <ProxyMessages openThreadId={thread.openThreadId} onOpenThreadHandled={thread.onOpenThreadHandled} />;
}

export const sections: SectionDef[] = [
  {
    key: 'dashboard', label: 'Dashboard', icon: 'home-outline',
    render: ({ navigate, openThread }) => (
      <PatientProxyMainDashboard
        onNavigate={(key, params) => navigate(key, params)}
        onSelectPatient={dep => openPatientProfile(navigate, dep)}
        onOpenThread={openThread}
      />
    ),
  },

  {
    key: 'appointments', label: 'Appointments', icon: 'calendar-outline', group: 'CARE COORDINATION',
    // The screen reads `patient` and `mode=book` from the dashboard's navigation context (useSectionNav).
    render: () => <ProxyAppointments />,
  },
  {
    key: 'medications', label: 'Medications', icon: 'medkit-outline', group: 'CARE COORDINATION',
    render: p => <ProxyMedications {...p} />,
  },
  {
    key: 'care-plans', label: 'Care Plans', icon: 'clipboard-outline', group: 'CARE COORDINATION',
    render: p => <ProxyCarePlans {...p} />,
  },
  {
    key: 'messages', label: 'Messages', icon: 'chatbox-outline', group: 'CARE COORDINATION', badge: 'messages',
    render: p => <MessagesSection {...p} />,
  },
  {
    key: 'notifications', label: 'Notifications', icon: 'notifications-outline', group: 'CARE COORDINATION', badge: 'notifications',
    render: p => <NotificationsSection {...p} />,
  },
  {
    key: 'documents-results', label: 'Documents & Results', icon: 'document-outline', group: 'CARE COORDINATION',
    render: p => <ProxyDocuments {...p} />,
  },
  {
    key: 'health-summary', label: 'Health Summary', icon: 'download-outline', group: 'CARE COORDINATION',
    render: p => <ProxyHealthSummary {...p} />,
  },
  {
    key: 'health-alerts', label: 'Health Alerts', icon: 'shield-checkmark-outline', group: 'CARE COORDINATION',
    render: p => <ProxyHealthAlerts {...p} />,
  },
  {
    key: 'billing', label: 'Billing & Payments', icon: 'card-outline', group: 'CARE COORDINATION',
    render: p => <ProxyBilling {...p} />,
  },

  {
    key: 'profile-settings', label: 'Profile & Settings', icon: 'settings-outline', group: 'ACCOUNT',
    render: p => <ProxyProfileSettings {...p} />,
  },
  {
    key: 'notification-settings', label: 'Notification Settings', icon: 'options-outline', group: 'ACCOUNT',
    render: p => <ProxyNotificationSettings {...p} />,
  },
  {
    key: 'help-support', label: 'Help & Support', icon: 'help-circle-outline', group: 'ACCOUNT',
    render: p => <HelpSupportSection {...p} />,
  },

  // Reachable from in-app links and notifications only.
  {
    key: 'add-dependent', label: 'Add a Loved One', icon: 'person-add-outline', nav: false,
    render: ({ navigate, theme }) => (
      <ProxyAddDependent
        theme={theme}
        // The sidebar's list is reloaded by the navigation, so the new loved one appears in it.
        onDone={() => { markDependentsStale(); navigate(homeKey); }}
        onCancel={() => navigate(homeKey)}
        onViewRequests={() => navigate('access-requests')}
      />
    ),
  },
  {
    key: 'patient-profile', label: 'Patient Profile', icon: 'person-outline', nav: false,
    render: p => <ProxyPatientProfile {...p} />,
  },
  {
    key: 'access-requests', label: 'Access Requests', icon: 'mail-open-outline', nav: false,
    render: p => <ProxyAccessRequests {...p} />,
  },
];

export const registry: SectionRegistry = { role: 'proxy', sections, homeKey };
