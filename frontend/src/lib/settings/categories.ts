import type { AppRole } from '@/lib/auth/auth.types';
import type { CategoryField, ChannelField } from './types';

// What each role can switch on and off. A switch is listed for a role only when the server sends that role
// notifications of the category, so no switch on the screen is without effect:
//   - a doctor gets no dose reminders, second appointment reminders, prescription, consultation, imaging, vitals
//     or access-change notifications, so those switches are not offered to doctors;
//   - out-of-range health readings notify caregivers only;
//   - no notification is sent with the video-call category, so `videoCallAlerts` is not offered to anyone.
// The two appointment reminders are named "first" and "second": how long before the appointment each one is sent
// is set by the clinic and is not known to this screen.

export interface CategoryToggle {
  field:       CategoryField;
  label:       string;
  /** What the switch covers for this role. */
  description: string;
}

export interface CategoryGroup {
  key:       string;
  title:     string;
  subtitle?: string;
  items:     CategoryToggle[];
}

/** Shown with the critical-alerts switch. */
export const CRITICAL_ALWAYS_DELIVERED = 'Alerts sent with critical priority are always delivered, even when this is off.';

/** Conversation notifications have no email, SMS or push copy. */
const MESSAGES: CategoryToggle = {
  field: 'messageNotifications', label: 'Messages',
  description: 'When someone writes to you in Messages. These notifications are shown in CareNexus only.',
};

const LOGIN: CategoryToggle = {
  field: 'loginAlerts', label: 'New sign-in alerts',
  description: 'When your account is signed in to on a new device.',
};

const PATIENT_GROUPS: CategoryGroup[] = [
  {
    key: 'appointments', title: 'Appointments',
    items: [
      {
        field: 'appointmentReminders', label: 'First appointment reminder',
        description: 'The earlier of the two reminders sent before an appointment, and appointment reminders that you or your care team set up.',
      },
      {
        field: 'appointmentReminder1h', label: 'Second appointment reminder',
        description: 'The later reminder, sent shortly before the appointment starts.',
      },
      {
        field: 'appointmentChangeAlerts', label: 'Appointment changes',
        description: 'When an appointment is booked, moved, cancelled or marked as missed.',
      },
      {
        field: 'consultationNotifications', label: 'Consultation summaries',
        description: 'When a doctor completes a consultation and its summary is available.',
      },
    ],
  },
  {
    key: 'medication-results', title: 'Medication and results',
    items: [
      {
        field: 'prescriptionReminders', label: 'Prescriptions',
        description: 'When a prescription is issued, cancelled or expires.',
      },
      {
        field: 'medicationReminders', label: 'Medication reminders',
        description: 'When a dose is due, and medication reminders that you or your care team set up.',
      },
      {
        field: 'labResultAlerts', label: 'Lab results',
        description: 'When a lab result is available, and lab follow-up reminders.',
      },
      {
        field: 'imagingResultAlerts', label: 'Imaging results',
        description: 'When an imaging result is available.',
      },
    ],
  },
  {
    key: 'care', title: 'Your care',
    items: [
      {
        field: 'criticalAlerts', label: 'Critical health alerts',
        description: 'When your care team marks your health status as critical.',
      },
      {
        field: 'referralAlerts', label: 'Referrals',
        description: 'When you are referred to a specialist, and when the referral is accepted, declined, completed or cancelled.',
      },
      {
        field: 'carePlanUpdateAlerts', label: 'Care plan updates',
        description: 'When a doctor creates or changes a care plan for you, and care plan review reminders.',
      },
      {
        field: 'accessChangeAlerts', label: 'Care team changes',
        description: 'When a doctor is added to your care team.',
      },
    ],
  },
  {
    key: 'account', title: 'Billing, messages and account',
    items: [
      {
        field: 'billingNotifications', label: 'Billing',
        description: 'Invoices, payments, refunds, overdue notices and insurance claim updates.',
      },
      MESSAGES,
      LOGIN,
    ],
  },
];

const PROXY_GROUPS: CategoryGroup[] = [
  {
    key: 'appointments', title: 'Appointments',
    subtitle: 'You are notified about a loved one only for the information they share with you.',
    items: [
      {
        field: 'appointmentReminders', label: 'First appointment reminder',
        description: "The earlier of the two reminders sent before a loved one's appointment.",
      },
      {
        field: 'appointmentReminder1h', label: 'Second appointment reminder',
        description: 'The later reminder, sent shortly before the appointment starts.',
      },
      {
        field: 'appointmentChangeAlerts', label: 'Appointment changes',
        description: "When a loved one's appointment is booked, moved, cancelled or marked as missed.",
      },
      {
        field: 'consultationNotifications', label: 'Consultation summaries',
        description: 'When a doctor completes a consultation with a loved one and its summary is available.',
      },
    ],
  },
  {
    key: 'medication-results', title: 'Medication and results',
    items: [
      {
        field: 'prescriptionReminders', label: 'Prescriptions',
        description: 'When a doctor issues a new prescription for a loved one.',
      },
      {
        field: 'medicationReminders', label: 'Medication reminders',
        description: "When a loved one's dose is due, and when doses are missed in a row.",
      },
      {
        field: 'labResultAlerts', label: 'Lab results',
        description: 'When a lab result for a loved one is available.',
      },
      {
        field: 'imagingResultAlerts', label: 'Imaging results',
        description: 'When an imaging result for a loved one is available.',
      },
    ],
  },
  {
    key: 'health', title: 'Health alerts',
    items: [
      {
        field: 'criticalAlerts', label: 'Critical health alerts',
        description: "When the care team marks a loved one's health status as critical.",
      },
      {
        field: 'vitalsAlerts', label: 'Health readings outside range',
        description: 'When a health reading recorded for a loved one is outside its expected range.',
      },
      {
        field: 'referralAlerts', label: 'Referrals',
        description: 'When a loved one is referred to a specialist.',
      },
      {
        field: 'carePlanUpdateAlerts', label: 'Care plan updates',
        description: "When a doctor creates or changes a loved one's care plan.",
      },
    ],
  },
  {
    key: 'account', title: 'Access, billing and account',
    items: [
      {
        field: 'accessChangeAlerts', label: 'Access changes',
        description: "When your access to a loved one's information is granted, changed, extended or removed, or is about to expire.",
      },
      {
        field: 'billingNotifications', label: 'Billing',
        description: 'Invoices, payments, refunds, overdue notices and insurance claim updates for a loved one.',
      },
      MESSAGES,
      LOGIN,
    ],
  },
];

const DOCTOR_GROUPS: CategoryGroup[] = [
  {
    key: 'appointments', title: 'Appointments',
    items: [
      {
        field: 'appointmentChangeAlerts', label: 'Appointment changes',
        description: 'When an appointment with you is booked, moved, cancelled or marked as missed.',
      },
      {
        field: 'appointmentReminders', label: 'Appointments needing an outcome',
        description: 'A reminder when a past appointment still has no recorded outcome.',
      },
    ],
  },
  {
    key: 'clinical', title: 'Clinical',
    items: [
      {
        field: 'criticalAlerts', label: 'Clinical alerts',
        description: 'Clinical alerts raised for your patients, and reports of severe symptoms.',
      },
      {
        field: 'labResultAlerts', label: 'Result review reminders',
        description: 'Reminders to review lab and imaging results that you ordered.',
      },
      {
        field: 'referralAlerts', label: 'Referrals',
        description: 'When a referral is sent to you, and when a referral you made is accepted, declined or completed.',
      },
      {
        field: 'carePlanUpdateAlerts', label: 'Care plan reviews',
        description: 'A reminder when a care plan you wrote is due for review.',
      },
    ],
  },
  {
    key: 'account', title: 'Billing, messages and account',
    items: [
      {
        field: 'billingNotifications', label: 'Billing',
        description: 'When an invoice you issued is paid.',
      },
      MESSAGES,
      LOGIN,
    ],
  },
];

const ADMIN_GROUPS: CategoryGroup[] = [
  { key: 'account', title: 'Messages and account', items: [MESSAGES, LOGIN] },
];

/** The category switches of each role's settings screen, in display order. */
export const SETTINGS_CATEGORIES: Readonly<Record<AppRole, CategoryGroup[]>> = {
  patient: PATIENT_GROUPS,
  proxy:   PROXY_GROUPS,
  doctor:  DOCTOR_GROUPS,
  admin:   ADMIN_GROUPS,
};

export type ChannelKey = 'email' | 'sms' | 'push';

export interface ChannelDef {
  key:         ChannelKey;
  field:       ChannelField;
  label:       string;
  description: string;
  /** Shown when the installation's provider for the channel is the local one, which records instead of sending. */
  localNote:   string;
}

/** The external channels, in display order. The in-app channel is always on and has no switch. */
export const SETTINGS_CHANNELS: readonly ChannelDef[] = [
  {
    key: 'email', field: 'emailNotifications', label: 'Email',
    description: 'Send notifications to the email address of your account as well.',
    localNote: 'This installation records email messages locally instead of sending them.',
  },
  {
    key: 'sms', field: 'smsNotifications', label: 'SMS',
    description: 'Send notifications as text messages to the phone number of your account as well.',
    localNote: 'This installation records SMS messages locally instead of sending them.',
  },
  {
    key: 'push', field: 'pushNotifications', label: 'Push notifications',
    description: 'Send notifications as push messages as well. They reach only devices where you have allowed CareNexus notifications.',
    localNote: 'This installation records push messages locally instead of sending them.',
  },
];
