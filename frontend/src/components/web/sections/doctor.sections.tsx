import { lazy } from 'react';
import type { SectionDef, SectionRegistry } from '@/lib/nav/sections';
import { DoctorMainDashboardArea } from '../direct/doctor/doctor-main-dashboard-area';
import { HelpSupportSection, MessagesSection, NotificationsSection } from './shared-sections';

// Doctor dashboard sections. Adding a section is one entry here: the sidebar, the URL
// (`/dashboard/doctor?section=<key>`) and notification links all read this list.
// Keys are stable: backend notification links depend on them (CONTRACTS 7.1 and 12.1).
//
// Every screen receives the section props (`role`, `params`, `navigate`, `openThread`, `theme`), so URL params
// such as `patient`, `id`, `tab`, `mode` and `consultationId` reach it on a link, a refresh and Back.

const DoctorWebSchedule       = lazy(() => import('../direct/doctor/doctor-web-schedule'));
const DoctorWebAvailability   = lazy(() => import('../direct/doctor/doctor-web-availability'));
const DoctorWebConsultation   = lazy(() => import('../direct/doctor/doctor-web-consultation'));
const DoctorWebAppointment    = lazy(() => import('../direct/doctor/doctor-web-appointment'));
const DoctorWebPatients       = lazy(() => import('../direct/doctor/doctor-web-patients'));
const DoctorWebMedicalRecords = lazy(() => import('../direct/doctor/doctor-web-medical-records'));
const DoctorWebDocuments      = lazy(() => import('../direct/doctor/doctor-web-documents'));
const DoctorWebPrescription   = lazy(() => import('../direct/doctor/doctor-web-prescription'));
const DoctorWebLabResults     = lazy(() => import('../direct/doctor/doctor-web-lab-results'));
const DoctorWebImaging        = lazy(() => import('../direct/doctor/doctor-web-imaging'));
const DoctorWebCarePlans      = lazy(() => import('../direct/doctor/doctor-web-care-plans'));
const DoctorWebReferrals      = lazy(() => import('../direct/doctor/doctor-web-referrals'));
const DoctorWebAiAlerts       = lazy(() => import('../direct/doctor/doctor-web-ai-alerts'));
const DoctorWebBilling        = lazy(() => import('../direct/doctor/doctor-web-billing'));
const DoctorWebProfile        = lazy(() => import('../direct/doctor/doctor-web-profile-section'));
const DoctorWebSettings       = lazy(() => import('../direct/doctor/doctor-web-settings'));

export const homeKey = 'dashboard';

export const sections: SectionDef[] = [
  {
    key: 'dashboard', label: 'Dashboard', icon: 'home-outline',
    render: p => <DoctorMainDashboardArea {...p} />,
  },

  {
    key: 'schedule', label: 'Schedule', icon: 'calendar-outline', group: 'CONSULTATIONS',
    render: p => <DoctorWebSchedule {...p} />,
  },
  {
    key: 'availability', label: 'Availability', icon: 'time-outline', group: 'CONSULTATIONS',
    render: p => <DoctorWebAvailability {...p} />,
  },
  {
    key: 'consultations', label: 'Consultations', icon: 'videocam-outline', group: 'CONSULTATIONS',
    render: p => <DoctorWebConsultation {...p} />,
  },
  {
    key: 'appointments', label: 'Appointments', icon: 'calendar-number-outline', group: 'CONSULTATIONS',
    render: p => <DoctorWebAppointment {...p} />,
  },

  {
    key: 'patients', label: 'Patients', icon: 'people-outline', group: 'PATIENTS & RECORDS',
    render: p => <DoctorWebPatients {...p} />,
  },
  {
    key: 'medical-records', label: 'Medical Records', icon: 'folder-open-outline', group: 'PATIENTS & RECORDS',
    render: p => <DoctorWebMedicalRecords {...p} />,
  },
  {
    key: 'documents', label: 'Documents', icon: 'document-outline', group: 'PATIENTS & RECORDS',
    render: p => <DoctorWebDocuments {...p} />,
  },

  {
    key: 'prescriptions', label: 'Prescriptions', icon: 'medkit-outline', group: 'CLINICAL',
    render: p => <DoctorWebPrescription {...p} />,
  },
  {
    key: 'labs', label: 'Labs & Results', icon: 'flask-outline', group: 'CLINICAL',
    render: p => <DoctorWebLabResults {...p} />,
  },
  {
    key: 'imaging', label: 'Imaging', icon: 'scan-outline', group: 'CLINICAL',
    render: p => <DoctorWebImaging {...p} />,
  },
  {
    key: 'care-plans', label: 'Care Plans', icon: 'clipboard-outline', group: 'CLINICAL',
    render: p => <DoctorWebCarePlans {...p} />,
  },
  {
    key: 'referrals', label: 'Referrals', icon: 'swap-horizontal-outline', group: 'CLINICAL',
    render: p => <DoctorWebReferrals {...p} />,
  },
  {
    key: 'ai-alerts', label: 'Clinical Alerts', icon: 'warning-outline', group: 'CLINICAL', badge: 'alerts',
    render: p => <DoctorWebAiAlerts {...p} />,
  },
  {
    key: 'billing', label: 'Billing', icon: 'card-outline', group: 'CLINICAL',
    render: p => <DoctorWebBilling {...p} />,
  },

  {
    key: 'messages', label: 'Messages', icon: 'chatbubble-outline', group: 'COMMUNICATION', badge: 'messages',
    render: p => <MessagesSection {...p} mode="DOCTOR" />,
  },
  {
    key: 'notifications', label: 'Notifications', icon: 'notifications-outline', group: 'COMMUNICATION', badge: 'notifications',
    render: p => <NotificationsSection {...p} />,
  },

  {
    key: 'profile', label: 'Profile', icon: 'person-outline', group: 'SETTINGS',
    render: p => <DoctorWebProfile {...p} />,
  },
  {
    key: 'settings', label: 'Settings', icon: 'options-outline', group: 'SETTINGS',
    render: p => <DoctorWebSettings {...p} />,
  },
  {
    key: 'help-support', label: 'Help & Support', icon: 'help-circle-outline', group: 'SETTINGS',
    render: p => <HelpSupportSection {...p} />,
  },
];

export const registry: SectionRegistry = { role: 'doctor', sections, homeKey };
