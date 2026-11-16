import type { IoniconsName, StatusTone } from '@/components/web/ui';
import { statusLabel, statusTone } from '@/components/web/ui';
import type {
  ConsultationAppointmentType, ConsultationStatus,
} from '@/hooks/use-doctor-consultations';
import { threadDraftKey } from '@/lib/communications/drafts';
import { formatClinicDate, formatClinicDateTime } from '@/lib/time/clinic';
import { formatDateOnly } from '@/lib/ui/date-only';

// Labels and small formatters shared by the consultation list, the editor and the read-only view.

export const CONSULTATION_STATUS_LABELS: Record<ConsultationStatus, string> = {
  DRAFT:       'Draft',
  IN_PROGRESS: 'In progress',
  COMPLETED:   'Completed',
  CANCELLED:   'Cancelled',
};

const STATUS_ICONS: Record<ConsultationStatus, IoniconsName> = {
  DRAFT:       'document-outline',
  IN_PROGRESS: 'sync-outline',
  COMPLETED:   'checkmark-circle-outline',
  CANCELLED:   'close-circle-outline',
};

export function consultationStatusBadge(status: ConsultationStatus): { label: string; tone: StatusTone; icon: IoniconsName } {
  return { label: CONSULTATION_STATUS_LABELS[status], tone: statusTone('consultation', status), icon: STATUS_ICONS[status] };
}

const TYPE_LABELS: Record<ConsultationAppointmentType, string> = {
  IN_PERSON: 'In Person',
  VIDEO:     'Video',
  PHONE:     'Phone',
};

const TYPE_ICONS: Record<ConsultationAppointmentType, IoniconsName> = {
  IN_PERSON: 'location-outline',
  VIDEO:     'videocam-outline',
  PHONE:     'call-outline',
};

/** Label and icon of an appointment type; null when the type is not known (nothing is shown then). */
export function appointmentTypeBadge(type: ConsultationAppointmentType | null | undefined): { label: string; icon: IoniconsName } | null {
  return type ? { label: TYPE_LABELS[type], icon: TYPE_ICONS[type] } : null;
}

/** "Scheduled", "No show", ... for an appointment status; null when it is not known. */
export function appointmentStatusText(status: string | null | undefined): string | null {
  return status ? statusLabel(status) : null;
}

/** A clinic wall-clock date-time as "Fri 20 Nov 2026, 10:15 CAT". */
export function clinicDateTime(value: string | null | undefined, fallback = 'Not recorded'): string {
  return formatClinicDateTime(value, fallback);
}

/** A clinic wall-clock date-time as "Fri 20 Nov 2026". */
export function clinicDate(value: string | null | undefined, fallback = 'Not recorded'): string {
  return formatClinicDate(value, fallback);
}

/** A date-only value ("YYYY-MM-DD") as "24 Nov 2026". */
export function followUpDateText(value: string | null | undefined, fallback = 'Not set'): string {
  return formatDateOnly(value, undefined, fallback);
}

/** The day a consultation is listed under: when it was completed, else started, else created. */
export function consultationDateLabel(c: { completedAt: string | null; startedAt: string | null; createdAt: string }): string {
  if (c.completedAt) return `Completed ${clinicDate(c.completedAt)}`;
  if (c.startedAt) return `Started ${clinicDate(c.startedAt)}`;
  return `Created ${clinicDate(c.createdAt)}`;
}

/**
 * Storage key of the unsaved notes of one consultation. It is built from the message-draft key of the same
 * user, so it shares that prefix and is removed with the other drafts when the user signs out or another
 * account signs in (lib/communications/session.ts).
 */
export function consultationDraftKey(userSystemId: string, consultationId: number): string {
  const userPrefix = threadDraftKey(userSystemId, 0).replace(/\.0$/, '');
  return `${userPrefix}.consultation.${consultationId}`;
}

/** A positive whole number from a URL param, or null. */
export function idParam(value: string | undefined): number | null {
  if (!value || !/^\d{1,15}$/.test(value)) return null;
  const n = Number(value);
  return n > 0 ? n : null;
}
