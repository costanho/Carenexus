import { formatClinicDateTime } from '@/lib/time/clinic';
import { formatDateOnly } from '@/lib/ui/date-only';
import type { Referral, ReferralAction, ReferralPriority, ReferralStatus } from './types';

// Words for referral values and the status timeline. Pure functions, shared by the doctor, patient and
// caregiver views so all three describe a referral the same way.

export const REFERRAL_STATUS_LABEL: Record<ReferralStatus, string> = {
  PENDING:   'Pending',
  ACCEPTED:  'Accepted',
  DECLINED:  'Declined',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
};

export const REFERRAL_PRIORITY_LABEL: Record<ReferralPriority, string> = {
  ROUTINE:   'Routine',
  URGENT:    'Urgent',
  EMERGENCY: 'Emergency',
};

export function referralStatusLabel(status: string | null | undefined): string {
  return REFERRAL_STATUS_LABEL[status as ReferralStatus] ?? 'Unknown';
}

export function referralPriorityLabel(priority: string | null | undefined): string {
  return REFERRAL_PRIORITY_LABEL[priority as ReferralPriority] ?? 'Routine';
}

/** Options of a status filter, in the order a referral moves through them. */
export const REFERRAL_STATUS_OPTIONS: readonly { value: ReferralStatus; label: string }[] =
  (['PENDING', 'ACCEPTED', 'COMPLETED', 'DECLINED', 'CANCELLED'] as const).map(value => ({ value, label: REFERRAL_STATUS_LABEL[value] }));

export const REFERRAL_PRIORITY_OPTIONS: readonly { value: ReferralPriority; label: string }[] =
  (['ROUTINE', 'URGENT', 'EMERGENCY'] as const).map(value => ({ value, label: REFERRAL_PRIORITY_LABEL[value] }));

/** The specialist's name, or null while nobody is assigned. */
export function specialistName(referral: Referral): string | null {
  if (referral.specialistType === 'PLATFORM') return referral.specialistName?.trim() || 'A CareNexus doctor';
  if (referral.specialistType === 'EXTERNAL') return referral.externalSpecialistName?.trim() || 'An external specialist';
  return null;
}

/**
 * What stands next to the specialist's name: the specialization of a platform doctor, or the organisation of
 * an external specialist. Null when there is nothing to add.
 */
export function specialistDetail(referral: Referral): string | null {
  if (referral.specialistType === 'PLATFORM') return referral.specialistSpecialization?.trim() || null;
  if (referral.specialistType === 'EXTERNAL') return referral.externalSpecialistOrganisation?.trim() || null;
  return null;
}

/** "Dr. Amina Okafor, Cardiology", "Dr. Peter Moyo, Harare Heart Centre" or "Not assigned yet". */
export function specialistSummary(referral: Referral): string {
  const name = specialistName(referral);
  if (!name) return 'Not assigned yet';
  const detail = specialistDetail(referral);
  return detail ? `${name}, ${detail}` : name;
}

/** "10 Nov 2026" for the clinic calendar day a referral was issued. */
export function referralDateLabel(referral: Referral): string {
  return formatDateOnly(referral.referralDate, undefined, 'Date not recorded');
}

export function hasAction(referral: Referral, action: ReferralAction): boolean {
  return Array.isArray(referral.allowedActions) && referral.allowedActions.includes(action);
}

/** True when the caller may record an accept, decline or complete for this referral now. */
export function canRespond(referral: Referral): boolean {
  return hasAction(referral, 'ACCEPT') || hasAction(referral, 'DECLINE') || hasAction(referral, 'COMPLETE');
}

// ─── Status timeline ──────────────────────────────────────────────────────────

export type TimelineStepState = 'done' | 'current' | 'stopped';

export interface TimelineStep {
  key:   string;
  /** What happened, or what is being waited for. */
  title: string;
  /** "Tue 10 Nov 2026, 14:05 CAT"; null for a step that has not happened yet. */
  when:  string | null;
  /** Who did it, or a sentence of context. */
  detail: string | null;
  /** `done` = happened, `current` = waiting for this, `stopped` = the referral ended here without care. */
  state: TimelineStepState;
}

function when(value: string | null | undefined): string | null {
  return formatClinicDateTime(value, '') || null;
}

/** "recorded by Dr. James Wilson on the specialist's behalf", or null when the named specialist acted. */
function onBehalf(referral: Referral): string | null {
  const who = referral.outcomeRecordedByName?.trim();
  return referral.outcomeRecordedBySystemId && who ? `Recorded by ${who} on the specialist's behalf` : null;
}

/**
 * The steps a referral has been through, oldest first, ending with what it is waiting for when it is still open.
 * Built only from the fields the server sends: no step is shown for something that has not been recorded.
 */
export function referralTimeline(referral: Referral): TimelineStep[] {
  const steps: TimelineStep[] = [{
    key: 'created',
    title: 'Referral issued',
    when: when(referral.createdAt),
    detail: referral.referringDoctorName?.trim() ? `By ${referral.referringDoctorName.trim()}` : null,
    state: 'done',
  }];
  const specialist = specialistName(referral);

  switch (referral.status) {
    case 'PENDING':
      steps.push({
        key: 'waiting-response',
        title: specialist ? 'Waiting for the specialist to respond' : 'Waiting for a specialist to be assigned',
        when: null,
        detail: specialist ? specialistSummary(referral) : null,
        state: 'current',
      });
      break;
    case 'ACCEPTED':
      steps.push({
        key: 'accepted',
        title: 'Accepted',
        when: when(referral.respondedAt),
        detail: onBehalf(referral) ?? (specialist ? `By ${specialist}` : null),
        state: 'done',
      });
      steps.push({
        key: 'waiting-completion',
        title: 'Specialist care in progress',
        when: null,
        detail: null,
        state: 'current',
      });
      break;
    case 'COMPLETED':
      // The recorder fields describe the latest outcome (the completion), so the acceptance carries no name.
      steps.push({ key: 'accepted', title: 'Accepted', when: when(referral.respondedAt), detail: null, state: 'done' });
      steps.push({
        key: 'completed',
        title: 'Completed',
        when: when(referral.completedAt),
        detail: onBehalf(referral) ?? (specialist ? `By ${specialist}` : null),
        state: 'done',
      });
      break;
    case 'DECLINED':
      steps.push({
        key: 'declined',
        title: 'Declined',
        when: when(referral.respondedAt),
        detail: onBehalf(referral) ?? (specialist ? `By ${specialist}` : null),
        state: 'stopped',
      });
      break;
    case 'CANCELLED':
      steps.push({
        key: 'cancelled',
        title: 'Cancelled',
        // A cancelled referral never changes again, so its last update is the cancellation.
        when: when(referral.updatedAt),
        detail: referral.referringDoctorName?.trim() ? `Withdrawn by ${referral.referringDoctorName.trim()}` : null,
        state: 'stopped',
      });
      break;
    default:
      break;
  }
  return steps;
}

/** One sentence on where a referral stands, for readers who did not issue it (patients and caregivers). */
export function referralStatusSentence(referral: Referral): string {
  const specialist = specialistName(referral);
  switch (referral.status) {
    case 'PENDING':
      return specialist
        ? `Waiting for ${specialist} to respond.`
        : 'A specialist has not been chosen yet. The referring doctor will assign one.';
    case 'ACCEPTED':
      return specialist ? `${specialist} has accepted this referral.` : 'This referral has been accepted.';
    case 'COMPLETED':
      return 'The specialist care for this referral is complete.';
    case 'DECLINED':
      return 'This referral was declined. The referring doctor can issue a new one.';
    case 'CANCELLED':
      return 'This referral was withdrawn by the referring doctor.';
    default:
      return '';
  }
}
