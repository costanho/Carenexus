import type { IoniconsName } from '@/components/web/ui';
import type {
  HealthStatus, PatientAppointmentType, PatientGender, PatientSort,
} from '@/hooks/use-doctor-patients';
import { formatClinicDate, formatClinicDateTime } from '@/lib/time/clinic';
import { ageFromDateOnly, formatDateOnly } from '@/lib/ui/date-only';

// Wording shared by the patient list and the patient detail panel.

export const HEALTH_STATUS_LABEL: Record<HealthStatus, string> = {
  STABLE:   'Stable',
  MONITOR:  'Monitor',
  CRITICAL: 'Critical',
};

/** "Stable", "Monitor" or "Critical"; "Unknown" for a value the app does not know. */
export function healthStatusLabel(status: string | null | undefined): string {
  return HEALTH_STATUS_LABEL[status as HealthStatus] ?? 'Unknown';
}

export const GENDER_LABEL: Record<PatientGender, string> = {
  MALE:   'Male',
  FEMALE: 'Female',
  OTHER:  'Other',
};

export function genderLabel(gender: string | null | undefined): string | null {
  return GENDER_LABEL[gender as PatientGender] ?? null;
}

export const PATIENT_SORT_LABEL: Record<PatientSort, string> = {
  name:            'Name',
  nextAppointment: 'Next appointment',
  healthStatus:    'Health status',
  lastVisit:       'Last visit',
};

const APPOINTMENT_TYPE: Record<PatientAppointmentType, { label: string; icon: IoniconsName }> = {
  IN_PERSON: { label: 'In person', icon: 'business-outline' },
  VIDEO:     { label: 'Video',     icon: 'videocam-outline' },
  PHONE:     { label: 'Phone',     icon: 'call-outline' },
};

export function appointmentTypeLabel(type: string | null | undefined): string | null {
  return APPOINTMENT_TYPE[type as PatientAppointmentType]?.label ?? null;
}

export function appointmentTypeIcon(type: string | null | undefined): IoniconsName {
  return APPOINTMENT_TYPE[type as PatientAppointmentType]?.icon ?? 'calendar-outline';
}

/** "Fri 20 Nov 2026, 10:30 CAT · In person" for a next appointment, or null when there is none. */
export function nextAppointmentText(at: string | null | undefined, type: string | null | undefined): string | null {
  const when = formatClinicDateTime(at);
  if (!when) return null;
  const how = appointmentTypeLabel(type);
  return how ? `${when} · ${how}` : when;
}

/** "Wed 28 Oct 2026" for the last completed visit, or null when there has been none. */
export function lastVisitText(at: string | null | undefined): string | null {
  return formatClinicDate(at) || null;
}

/** "42 yrs", or null when the date of birth is missing. */
export function ageText(dateOfBirth: string | null | undefined): string | null {
  const age = ageFromDateOnly(dateOfBirth);
  return age === null ? null : `${age} ${age === 1 ? 'yr' : 'yrs'}`;
}

/** "17 Jun 1984 (42 yrs)", or null when the date of birth is missing. */
export function dateOfBirthText(dateOfBirth: string | null | undefined): string | null {
  const date = formatDateOnly(dateOfBirth, undefined, '');
  if (!date) return null;
  const age = ageText(dateOfBirth);
  return age ? `${date} (${age})` : date;
}

/** Up to two initials for an avatar. */
export function initialsOf(name: string): string {
  return name.split(/\s+/).filter(Boolean).map(word => word[0]).join('').slice(0, 2).toUpperCase() || '?';
}

/** "1 visit", "4 visits". */
export function plural(count: number, one: string, many: string = `${one}s`): string {
  return `${count} ${count === 1 ? one : many}`;
}
