import { type IoniconsName, statusLabel, statusTone, type StatusTone } from '@/components/web/ui';
import type { PrescriptionRoute, PrescriptionStatus, SettablePrescriptionStatus } from '@/hooks/use-doctor-prescriptions';
import { clinicToday } from '@/lib/time/clinic';
import { dateOnlyParts, formatDateOnly } from '@/lib/ui/date-only';

// Words, tones and small date helpers shared by the prescription card, the dialogs and the screen.

export const STATUS_LABEL: Record<PrescriptionStatus, string> = {
  ACTIVE:    'Active',
  ON_HOLD:   'On hold',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
  EXPIRED:   'Expired',
};

const STATUS_ICON: Record<PrescriptionStatus, IoniconsName> = {
  ACTIVE:    'checkmark-circle-outline',
  ON_HOLD:   'pause-circle-outline',
  COMPLETED: 'checkbox-outline',
  CANCELLED: 'ban-outline',
  EXPIRED:   'hourglass-outline',
};

/**
 * Label, tone and icon of a status badge. The label is always shown, so the tone never carries the meaning alone.
 * A status this screen does not know is shown in its own words rather than as one it does know.
 */
export function statusBadge(status: PrescriptionStatus): { label: string; tone: StatusTone; icon: IoniconsName } {
  return {
    label: STATUS_LABEL[status] ?? statusLabel(status),
    tone:  statusTone('prescription', status),
    icon:  STATUS_ICON[status] ?? 'help-circle-outline',
  };
}

export const ROUTE_LABEL: Record<PrescriptionRoute, string> = {
  ORAL:       'Oral',
  TOPICAL:    'Topical',
  INJECTION:  'Injection',
  INHALED:    'Inhaled',
  SUBLINGUAL: 'Sublingual',
  RECTAL:     'Rectal',
  IV:         'Intravenous (IV)',
};

/** The route in words; a value this screen does not know is shown as sent ("Nasal"), never as another route. */
export function routeLabel(route: PrescriptionRoute | string | null | undefined): string {
  return ROUTE_LABEL[route as PrescriptionRoute] ?? (route ? statusLabel(route) : 'Route not recorded');
}

export const ROUTE_OPTIONS: { value: PrescriptionRoute; label: string }[] =
  (Object.keys(ROUTE_LABEL) as PrescriptionRoute[]).map(value => ({ value, label: ROUTE_LABEL[value] }));

/** "14 May 2026" for a date-only value; `fallback` when missing. */
export function formatDay(value: string | null | undefined, fallback = 'Not set'): string {
  return formatDateOnly(value, undefined, fallback);
}

/** Whole days from the clinic's today to a date-only value: 0 = today, negative = past. Null when not a date. */
export function daysFromClinicToday(value: string | null | undefined): number | null {
  const date = dateOnlyParts(value);
  const today = dateOnlyParts(clinicToday());
  if (!date || !today) return null;
  return Math.round((Date.UTC(date.y, date.m - 1, date.d) - Date.UTC(today.y, today.m - 1, today.d)) / 86_400_000);
}

/** Days within which an active prescription is flagged as expiring soon. */
export const EXPIRING_SOON_DAYS = 14;

export function refillsText(count: number): string {
  if (count <= 0) return 'No refills';
  return `${count} ${count === 1 ? 'refill' : 'refills'}`;
}

export interface StatusAction {
  /** The status the action sets. */
  target:       SettablePrescriptionStatus;
  /** Button text on the card. */
  label:        string;
  icon:         IoniconsName;
  /** Heading of the confirmation dialog. */
  title:        string;
  /** What happens, in the confirmation dialog. */
  consequence:  string;
  confirmLabel: string;
  destructive:  boolean;
  /** Completes "<medication> …" in the message shown after the change. */
  done:         string;
}

export const STATUS_ACTIONS: Record<'hold' | 'resume' | 'complete' | 'cancel', StatusAction> = {
  hold: {
    target: 'ON_HOLD', label: 'Hold', icon: 'pause-outline',
    title: 'Put this prescription on hold?',
    consequence: 'Doses that are still to come are removed from the patient\'s schedule until you resume the prescription. '
      + 'Doses already taken or missed stay on record.',
    confirmLabel: 'Put on hold', destructive: false,
    done: 'is now on hold',
  },
  resume: {
    target: 'ACTIVE', label: 'Resume', icon: 'play-outline',
    title: 'Resume this prescription?',
    consequence: 'The prescription becomes active again and doses are scheduled from its saved times.',
    confirmLabel: 'Resume', destructive: false,
    done: 'is active again',
  },
  complete: {
    target: 'COMPLETED', label: 'Complete', icon: 'checkmark-done-outline',
    title: 'Mark this prescription as completed?',
    consequence: 'Doses that are still to come are removed from the patient\'s schedule. '
      + 'A completed prescription cannot be edited or reopened.',
    confirmLabel: 'Mark completed', destructive: false,
    done: 'is marked as completed',
  },
  cancel: {
    target: 'CANCELLED', label: 'Cancel prescription', icon: 'close-circle-outline',
    title: 'Cancel this prescription?',
    consequence: 'The patient is told that the prescription was cancelled and doses that are still to come are removed '
      + 'from their schedule. A cancelled prescription cannot be edited or reopened; issue a new one if it is still needed.',
    confirmLabel: 'Cancel prescription', destructive: true,
    done: 'was cancelled',
  },
};
