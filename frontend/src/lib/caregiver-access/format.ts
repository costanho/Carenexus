import { formatClinicDate, formatClinicDateTime } from '@/lib/time/clinic';
import { describeDataAccess, levelLabel } from './permissions';
import type { CaregiverActivityEntry, ConsentEventKind, ConsentRecord, ConsentScope } from './types';

// Wording for the caregiver access screen: names, dates, the activity feed and the consent ledger.
// Every date-time of this domain is a clinic wall-clock value and is formatted in clinic time.

/** The name to show for a caregiver: their name, else their email, else a neutral word. */
export function caregiverDisplayName(source: { caregiverName?: string | null; caregiverEmail?: string | null }): string {
  return source.caregiverName?.trim() || source.caregiverEmail?.trim() || 'This caregiver';
}

/** "Fri 20 Nov 2026" for a clinic date-time; '' when the value is missing or unreadable. */
export function formatAccessDate(value: string | null | undefined): string {
  return formatClinicDate(value);
}

/** "Fri 20 Nov 2026, 10:15 CAT" for a clinic date-time; '' when the value is missing or unreadable. */
export function formatAccessDateTime(value: string | null | undefined): string {
  return formatClinicDateTime(value);
}

/** "LAB_RESULT" becomes "lab result"; used for values the screen has no wording for. */
export function humanise(value: string | null | undefined): string {
  return (value ?? '').trim().toLowerCase().split(/[\s_-]+/).filter(Boolean).join(' ');
}

function capitalise(text: string): string {
  return text ? text.charAt(0).toUpperCase() + text.slice(1) : text;
}

// ─── Activity feed ────────────────────────────────────────────────────────────

const ACTION_LABELS: Record<string, string> = {
  READ:   'Viewed',
  CREATE: 'Added',
  UPDATE: 'Changed',
  DELETE: 'Removed',
};

/** What the caregiver touched, in the words the patient knows the area by. */
const RESOURCE_LABELS: Record<string, string> = {
  PROFILE:             'profile',
  APPOINTMENT:         'appointments',
  CONSULTATION:        'consultations',
  PRESCRIPTION:        'prescriptions',
  LAB_RESULT:          'lab results',
  IMAGING_RESULT:      'imaging results',
  MEDICAL_RECORD:      'medical records',
  DOCUMENT:            'documents',
  CARE_PLAN:           'care plans',
  REFERRAL:            'referrals',
  HEALTH_METRIC:       'health readings',
  SYMPTOM_LOG:         'symptom logs',
  MEDICATION_SCHEDULE: 'medication schedule',
  AI_ALERT:            'health alerts',
  INVOICE:             'invoices',
  PAYMENT:             'payments',
  PAYMENT_METHOD:      'payment methods',
  INSURANCE_CLAIM:     'insurance claims',
  REMINDER:            'reminders',
  CORRECTION_REQUEST:  'correction requests',
  CARE_TEAM:           'care team',
  EMERGENCY_CONTACT:   'emergency contacts',
  DEPENDENT_ACCESS:    'their own access',
  ACCESS_REQUEST:      'access request',
  ACCOUNT:             'account',
};

function key(value: string | null | undefined): string {
  return (value ?? '').trim().toUpperCase();
}

/** "Viewed", "Added", "Changed", "Removed"; any other action is shown in plain words ("Dependent invite"). */
export function activityActionLabel(action: string | null | undefined): string {
  return ACTION_LABELS[key(action)] ?? (capitalise(humanise(action)) || 'Activity');
}

/** "Lab results", "Appointments"; any other type is shown in plain words; '' when there is none. */
export function activityResourceLabel(resourceType: string | null | undefined): string {
  return capitalise(RESOURCE_LABELS[key(resourceType)] ?? humanise(resourceType));
}

/** One sentence for a row of the feed: "Viewed lab results", "Added appointments", "Dependent invite". */
export function describeActivity(entry: Pick<CaregiverActivityEntry, 'action' | 'resourceType'>): string {
  const action = activityActionLabel(entry.action);
  const resource = activityResourceLabel(entry.resourceType);
  return resource ? `${action} ${resource.charAt(0).toLowerCase()}${resource.slice(1)}` : action;
}

// ─── Consent ledger ───────────────────────────────────────────────────────────

const CONSENT_LABELS: Record<ConsentEventKind, string> = {
  GRANTED:                'Access granted',
  PERMISSIONS_CHANGED:    'Permissions changed',
  COMMUNICATION_APPROVED: 'Messaging or video calls approved',
  EXTENDED:               'Access extended',
  NARROWED:               'Access reduced by the caregiver',
  REVOKED:                'Access removed',
  EXPIRED:                'Access expired',
};

export function consentEventLabel(kind: string | null | undefined): string {
  return CONSENT_LABELS[key(kind) as ConsentEventKind] ?? (capitalise(humanise(kind)) || 'Access changed');
}

/** True for the steps after which the caregiver holds nothing. */
export function consentEndsAccess(kind: string | null | undefined): boolean {
  const k = key(kind);
  return k === 'REVOKED' || k === 'EXPIRED';
}

/** Who performed a step: "You", the caregiver's name, "An administrator" or "CareNexus (automatic)". */
export function consentActorLabel(record: ConsentRecord): string {
  const actor = record.grantedBySystemId?.trim();
  if (!actor) return 'CareNexus (automatic)';
  if (actor === record.patientSystemId) return 'You';
  if (actor === record.caregiverSystemId) return caregiverDisplayName(record);
  if (actor.toUpperCase().startsWith('ADM-')) return 'An administrator';
  return 'Someone else';
}

/**
 * The lines that say what a consent snapshot covered: the level, what could be seen and done (with the
 * Full access / View only rule applied), messaging and calls, the end date and the guardian attestation.
 */
export function describeConsentScope(scope: ConsentScope): string[] {
  const lines: string[] = [`${levelLabel(scope.accessLevel)}. ${describeDataAccess(scope.accessLevel, scope)}`];

  const approved: string[] = [];
  const waiting: string[] = [];
  if (scope.canMessageCareTeam) (scope.messagingApproved ? approved : waiting).push('messaging your care team');
  if (scope.canJoinCalls) (scope.callsApproved ? approved : waiting).push('joining your video calls');
  if (approved.length > 0) lines.push(`Approved: ${approved.join(' and ')}.`);
  if (waiting.length > 0) lines.push(`Asked for, not approved: ${waiting.join(' and ')}.`);
  if (approved.length === 0 && waiting.length === 0) lines.push('No messaging or video calls.');

  const ends = formatAccessDate(scope.expiresAt);
  lines.push(scope.expiresAt ? (ends ? `End date: ${ends}.` : 'Has an end date.') : 'No end date.');
  if (scope.guardianAttested) lines.push('The caregiver confirmed they are the parent or legal guardian.');
  return lines;
}
