import { lazy, Suspense, useCallback, useRef } from 'react';
import { LoadingView } from '@/components/web/ui';
import { apiErrorMessage } from '@/hooks/use-appointments';
import { useCurrentUser } from '@/hooks/use-current-user';
import { createThread } from '@/lib/communications/api';
import type { SectionDef, SectionProps, SectionRegistry } from '@/lib/nav/sections';
import { showMessage } from '@/lib/ui/dialogs';
import { PatientMainDashboardArea } from '../direct/patient/patient-main-dashboard-area';
import { HelpSupportSection, MessagesSection } from './shared-sections';

// Patient dashboard sections. Adding a section is one entry here: the sidebar, the URL
// (`/dashboard/patient?section=<key>`) and notification links all read this list.
// Keys are stable: backend notification links depend on them (CONTRACTS 7.1 and 12.1). Every patient target of
// 7.1 resolves to an entry below, and each screen reads its own `id` / `tab` params:
//   my-appointments&id                 consultation-history&id         prescriptions&tab=schedule&date
//   prescriptions&tab=reminders        prescriptions&id                lab-results&id, imaging-results&id
//   medical-records&id                 medical-records&tab=corrections&id
//   documents&id, referrals&id, care-plans&id, health-tracking&id, health-alerts, care-team&id
//   my-proxies&tab=requests&id         messages (thread)               billing&id
//   profile-settings&tab=security

const PatientWebAppointment       = lazy(() => import('../direct/patient/patient-web-appointment'));
const PatientWebAppointmentStatus = lazy(() => import('../direct/patient/patient-web-appointment-status'));
const PatientWebPrescription      = lazy(() => import('../direct/patient/patient-web-prescription'));
const PatientWebCaregiverAccess   = lazy(() => import('../direct/patient/patient-web-caregiver-access'));
const PatientWebProfile           = lazy(() => import('../direct/patient/patient-web-profile'));
const PatientWebSettings          = lazy(() => import('../direct/patient/patient-web-settings'));
const PatientWebDocuments         = lazy(() => import('../direct/patient/patient-web-documents'));
const PatientWebReferrals         = lazy(() => import('../direct/patient/patient-web-referrals'));
const PatientWebCarePlans         = lazy(() => import('../direct/patient/patient-web-care-plans'));
const PatientWebHealthTracking    = lazy(() => import('../direct/patient/patient-web-health-tracking'));
const PatientWebHealthAlerts      = lazy(() => import('../direct/patient/patient-web-health-alerts'));
const PatientWebCareTeam          = lazy(() => import('../direct/patient/patient-web-care-team'));
const PatientWebBilling           = lazy(() => import('../direct/patient/patient-web-billing'));
const PatientWebNotifications     = lazy(() => import('../direct/patient/patient-web-notifications'));
const AccountSecurityPanel        = lazy(() => import('../account/account-security-panel'));

// The record sections carry the dashboard's card controls (attachments, Request a correction) and, on Medical
// Records, the Corrections tab; they share one chunk.
const recordSections = () => import('../direct/patient/patient-record-sections');
const PatientConsultationHistory = lazy(() => recordSections().then(m => ({ default: m.PatientConsultationHistorySection })));
const PatientMedicalRecords      = lazy(() => recordSections().then(m => ({ default: m.PatientMedicalRecordsSection })));
const PatientLabResults          = lazy(() => recordSections().then(m => ({ default: m.PatientLabResultsSection })));
const PatientImagingResults      = lazy(() => recordSections().then(m => ({ default: m.PatientImagingResultsSection })));

export const homeKey = 'dashboard';

/**
 * "Message Dr X" from an appointment: opens the private conversation with that doctor. The API returns the
 * existing private thread for this pair (200) or creates it (201), so repeated presses never make duplicates.
 */
export function useMessageDoctor({ navigate, openThread }: Pick<SectionProps, 'navigate' | 'openThread'>) {
  const { user } = useCurrentUser();
  const busy = useRef(false);
  const patientSystemId = user?.system_id;

  return useCallback(async (doctorSystemId: string, doctorName: string) => {
    if (busy.current) return;
    if (!patientSystemId) { navigate('messages'); return; }
    busy.current = true;
    try {
      const thread = await createThread({
        patientSystemId,
        type:            'PRIVATE',
        memberSystemIds: [doctorSystemId],
      });
      openThread(thread.threadId);
    } catch (e) {
      navigate('messages');
      showMessage(
        'Could not open the conversation',
        apiErrorMessage(e, `We could not open a conversation with ${doctorName}. You can start one from Messages.`),
      );
    } finally {
      busy.current = false;
    }
  }, [patientSystemId, navigate, openThread]);
}

/** My Appointments: `id` pins the linked appointment, `tab` opens a filter; Message opens the doctor thread. */
function MyAppointmentsSection(props: SectionProps) {
  const messageDoctor = useMessageDoctor(props);
  return (
    <PatientWebAppointmentStatus
      onMessageDoctor={messageDoctor}
      navigate={props.navigate}
      params={props.params}
      theme={props.theme}
    />
  );
}

/**
 * Profile & Settings: the profile, plus the Security tab (`tab=security`, the ACCOUNT_SECURITY notification
 * link) with password, email, two-factor, sessions, privacy and legal cards. The panel's code loads only when
 * the tab is opened.
 */
function ProfileSection(props: SectionProps) {
  return (
    <PatientWebProfile
      {...props}
      securityPanel={(
        <Suspense fallback={<LoadingView label="Loading account security" />}>
          <AccountSecurityPanel role={props.role} theme={props.theme} />
        </Suspense>
      )}
    />
  );
}

export const sections: SectionDef[] = [
  {
    key: 'dashboard', label: 'Dashboard', icon: 'home', rightSidebar: true,
    render: ({ navigate, theme }) => <PatientMainDashboardArea navigate={navigate} theme={theme} />,
  },

  {
    key: 'book-appointment', label: 'Book Appointment', icon: 'calendar-outline', group: 'APPOINTMENTS', rightSidebar: true,
    render: ({ navigate, params, theme }) => (
      <PatientWebAppointment onBack={() => navigate(homeKey)} navigate={navigate} params={params} theme={theme} />
    ),
  },
  {
    key: 'my-appointments', label: 'My Appointments', icon: 'calendar-number-outline', group: 'APPOINTMENTS', rightSidebar: true,
    render: p => <MyAppointmentsSection {...p} />,
  },

  {
    // Video consultations are joined from the appointment list.
    key: 'join-consultation', label: 'Join Consultation', icon: 'videocam-outline', group: 'CONSULTATIONS',
    aliasOf: 'my-appointments',
    render: p => <MyAppointmentsSection {...p} />,
  },
  {
    key: 'consultation-history', label: 'Consultation History', icon: 'document-text-outline', group: 'CONSULTATIONS', rightSidebar: true,
    render: p => <PatientConsultationHistory {...p} />,
  },

  {
    key: 'prescriptions', label: 'Prescriptions', icon: 'medkit-outline', group: 'MY HEALTH', rightSidebar: true,
    render: p => <PatientWebPrescription {...p} />,
  },
  {
    key: 'health-tracking', label: 'Vitals & Symptoms', icon: 'pulse-outline', group: 'MY HEALTH',
    render: p => <PatientWebHealthTracking {...p} />,
  },
  {
    key: 'care-plans', label: 'Care Plans', icon: 'clipboard-outline', group: 'MY HEALTH', rightSidebar: true,
    render: p => <PatientWebCarePlans {...p} />,
  },
  {
    key: 'health-alerts', label: 'Health Alerts', icon: 'shield-checkmark-outline', group: 'MY HEALTH', rightSidebar: true,
    render: p => <PatientWebHealthAlerts {...p} />,
  },

  {
    key: 'medical-records', label: 'Medical Records', icon: 'folder-open-outline', group: 'RECORDS & RESULTS', rightSidebar: true,
    render: p => <PatientMedicalRecords {...p} />,
  },
  {
    key: 'lab-results', label: 'Lab Results', icon: 'flask-outline', group: 'RECORDS & RESULTS', rightSidebar: true,
    render: p => <PatientLabResults {...p} />,
  },
  {
    key: 'imaging-results', label: 'Imaging Results', icon: 'image-outline', group: 'RECORDS & RESULTS', rightSidebar: true,
    render: p => <PatientImagingResults {...p} />,
  },
  {
    key: 'documents', label: 'Documents', icon: 'document-outline', group: 'RECORDS & RESULTS', rightSidebar: true,
    render: p => <PatientWebDocuments {...p} />,
  },
  {
    key: 'referrals', label: 'Referrals', icon: 'swap-horizontal-outline', group: 'RECORDS & RESULTS', rightSidebar: true,
    render: p => <PatientWebReferrals {...p} />,
  },

  {
    key: 'care-team', label: 'My Care Team', icon: 'people-circle-outline', group: 'CARE & SUPPORT',
    render: p => <PatientWebCareTeam {...p} />,
  },
  {
    key: 'my-proxies', label: 'My Proxies (Caregivers)', icon: 'people-outline', group: 'CARE & SUPPORT',
    render: ({ navigate, params, theme }) => (
      <PatientWebCaregiverAccess onBack={() => navigate(homeKey)} params={params} theme={theme} />
    ),
  },
  {
    key: 'messages', label: 'Messages', icon: 'chatbubble-outline', group: 'CARE & SUPPORT', badge: 'messages',
    render: p => <MessagesSection {...p} mode="PATIENT" />,
  },
  {
    key: 'notifications', label: 'Notifications', icon: 'notifications-outline', group: 'CARE & SUPPORT', badge: 'notifications',
    render: p => <PatientWebNotifications {...p} />,
  },

  {
    key: 'billing', label: 'Billing & Payments', icon: 'card-outline', group: 'ACCOUNT',
    render: p => <PatientWebBilling {...p} />,
  },
  {
    key: 'profile-settings', label: 'Profile & Settings', icon: 'person-circle-outline', group: 'ACCOUNT',
    render: p => <ProfileSection {...p} />,
  },
  {
    key: 'settings', label: 'Settings', icon: 'options-outline', group: 'ACCOUNT',
    render: p => <PatientWebSettings {...p} />,
  },
  {
    key: 'help-support', label: 'Help & Support', icon: 'help-circle-outline', group: 'ACCOUNT',
    render: p => <HelpSupportSection {...p} />,
  },
];

export const registry: SectionRegistry = { role: 'patient', sections, homeKey };
