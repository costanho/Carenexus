import type { CallSessionStatus } from './types';
import { formatClinicTime } from './format';

// Shared logic for the "Join video call" buttons in appointment lists (CONTRACT.md 11.3).

export interface CallJoinInput {
  type:               string | null | undefined;
  status:             string | null | undefined;
  callStatus:         CallSessionStatus | string | null | undefined;
  callJoinRoute:      string | null | undefined;
  joinWindowOpensAt:  string | null | undefined;
  joinWindowClosesAt: string | null | undefined;
}

export interface CallJoinInfo {
  /** Route to push; null means no button. */
  route:   string | null;
  /** Button label, or the status text when `informational`. */
  label:   string;
  /**
   * True for a status the user cannot act on yet (e.g. the doctor still has to confirm the time). Render it as
   * plain text, never as a Join button; `route` is null then.
   */
  informational?: boolean;
}

const ACTIVE = new Set(['SCHEDULED', 'RESCHEDULED']);

/** Positive integer appointment id from a route param, else null ("undefined", "abc", "0", arrays of those). */
export function parseAppointmentId(raw: string | string[] | null | undefined): number | null {
  const value = Array.isArray(raw) ? raw[0] : raw;
  if (!value || !/^\d+$/.test(value)) return null;
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

/** Only the internal call screen with a real id is ever pushed ("/dashboard/call/undefined" never is). */
export function isCallRoute(route: string | null | undefined): route is string {
  const match = typeof route === 'string' ? /^\/dashboard\/call\/([^/?#]+)$/.exec(route) : null;
  return !!match && parseAppointmentId(match[1]) !== null;
}

/**
 * Describes the Join control for an appointment, or returns null when no control should show.
 * Uses only the call fields from the appointment response; the call screen re-checks everything.
 */
export function describeCallJoin(a: CallJoinInput, isDoctor: boolean, now = Date.now()): CallJoinInfo | null {
  if (a.type !== 'VIDEO' || !isCallRoute(a.callJoinRoute) || !a.status || !ACTIVE.has(a.status)) return null;
  const route = a.callJoinRoute;

  switch (a.callStatus) {
    case 'ENDED':
    case 'CANCELLED':
      return null;
    case 'NEEDS_TIME_CONFIRMATION':
      // Only the doctor can act (confirm the time). Patients and caregivers see a status, not a Join button.
      return isDoctor
        ? { route, label: 'Confirm time' }
        : { route: null, label: 'Time to be confirmed', informational: true };
    default:
      break;
  }

  const closes = a.joinWindowClosesAt ? new Date(a.joinWindowClosesAt).getTime() : NaN;
  if (!Number.isNaN(closes) && closes < now) return null;

  const opens = a.joinWindowOpensAt ? new Date(a.joinWindowOpensAt).getTime() : NaN;
  if (!Number.isNaN(opens) && opens > now) {
    return { route, label: `Opens at ${formatClinicTime(a.joinWindowOpensAt)}` };
  }
  return { route, label: 'Join video call' };
}

/** Plain-language call state for appointment detail rows. */
export function callStatusLabel(callStatus: CallSessionStatus | string | null | undefined): string {
  switch (callStatus) {
    case 'PREPARING':               return 'Preparing';
    case 'READY':                   return 'Ready to start';
    case 'LIVE':                    return 'In progress';
    case 'FAILED':                  return 'Room could not be prepared';
    case 'ENDED':                   return 'Ended';
    case 'CANCELLED':               return 'Closed';
    case 'NEEDS_TIME_CONFIRMATION': return 'Time to be confirmed';
    default:                        return 'Not set up';
  }
}
