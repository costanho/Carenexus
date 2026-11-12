import { effectivePermission, isWriteFlag } from '@/lib/caregiver/permissions';
import type { AccessLevel, CommunicationFlag, CommunicationFlags, DataFlag, DataFlags } from './types';

// The permission model behind the patient's permission editor: the access levels (presets), the ten data
// permissions and the two communication permissions. The level rule itself (FULL_ACCESS grants every data
// permission, VIEW_ONLY blocks the three write permissions) has one implementation on web,
// `effectivePermission` in '@/lib/caregiver/permissions'; everything here goes through it.

export interface LevelOption {
  value: AccessLevel;
  label: string;
  /** One line under the label saying what the level does. */
  sub:   string;
}

export const ACCESS_LEVELS: readonly LevelOption[] = [
  { value: 'VIEW_ONLY',   label: 'View only',       sub: 'Sees what you choose. Cannot book, change or pay anything.' },
  { value: 'EDIT_ONLY',   label: 'View and manage', sub: 'Sees and acts on what you choose.' },
  { value: 'FULL_ACCESS', label: 'Full access',     sub: 'Everything: all records, booking, care plan and billing.' },
  { value: 'CUSTOM',      label: 'Custom',          sub: 'Choose each permission yourself.' },
];

export function levelLabel(level: string | null | undefined): string {
  return ACCESS_LEVELS.find(l => l.value === level)?.label ?? 'Custom';
}

export interface DataFlagOption {
  key:   DataFlag;
  /** Label of the switch, for example "View billing". */
  label: string;
  /** What the permission covers, shown under the label. */
  sub:   string;
  /** The words used in a summary sentence: a noun for what can be seen ("billing"), a verb for what can be done ("pay bills"). */
  noun:  string;
  /** True for the permissions that let the caregiver change something (blocked by View only). */
  write: boolean;
}

export const DATA_FLAG_OPTIONS: readonly DataFlagOption[] = [
  {
    key: 'canViewRecords', label: 'View medical records', noun: 'medical records', write: false,
    sub: 'Records, documents, referrals, care plans, health tracking and health alerts.',
  },
  { key: 'canViewAppointments',  label: 'View appointments',    noun: 'appointments',    write: false, sub: 'Upcoming and past appointments.' },
  { key: 'canViewMedications',   label: 'View medications',     noun: 'medications',     write: false, sub: 'Prescriptions and medication schedules.' },
  { key: 'canViewLabResults',    label: 'View lab results',     noun: 'lab results',     write: false, sub: 'Laboratory test results.' },
  { key: 'canViewImaging',       label: 'View imaging results', noun: 'imaging results', write: false, sub: 'Scans and imaging reports.' },
  { key: 'canViewConsultations', label: 'View consultations',   noun: 'consultations',   write: false, sub: 'Summaries of your visits.' },
  { key: 'canViewBilling',       label: 'View billing',         noun: 'billing',         write: false, sub: 'Invoices, payments and insurance claims.' },
  {
    key: 'canScheduleAppointments', label: 'Book appointments', noun: 'book appointments', write: true,
    sub: 'Book, reschedule and cancel appointments for you.',
  },
  {
    key: 'canManageCarePlan', label: 'Manage care plan', noun: 'manage your care plan', write: true,
    sub: 'Update your profile, care plan goals, health readings, reminders and documents.',
  },
  {
    key: 'canManageBilling', label: 'Manage billing', noun: 'pay bills', write: true,
    sub: 'Pay invoices and manage payment methods. Includes viewing billing.',
  },
];

export const DATA_FLAGS: readonly DataFlag[] = DATA_FLAG_OPTIONS.map(o => o.key);

export interface CommunicationFlagOption {
  key:   CommunicationFlag;
  label: string;
  /** The words used in a sentence, for example "message your care team". */
  verb:  string;
}

export const COMMUNICATION_FLAG_OPTIONS: readonly CommunicationFlagOption[] = [
  { key: 'canMessageCareTeam', label: 'Message my care team', verb: 'message your care team' },
  { key: 'canJoinCalls',       label: 'Join my video calls',  verb: 'join your video calls' },
];

export const NO_DATA_FLAGS: DataFlags = {
  canViewRecords: false, canViewAppointments: false, canViewMedications: false, canViewLabResults: false,
  canViewImaging: false, canViewConsultations: false, canViewBilling: false,
  canScheduleAppointments: false, canManageCarePlan: false, canManageBilling: false,
};

export const NO_COMMUNICATION_FLAGS: CommunicationFlags = { canMessageCareTeam: false, canJoinCalls: false };

type FlagSource = { [K in DataFlag]?: boolean | null };

/**
 * The data permissions a level and a set of stored flags amount to, whether or not the grant is active right
 * now: FULL_ACCESS gives all ten, VIEW_ONLY drops the three write permissions, the other levels keep the flags.
 * Use it to show what a grant, a request or a consent snapshot covers and to seed the editor, so a FULL_ACCESS
 * row that still carries stale `false` flags is never shown or saved as narrower than it is.
 */
export function dataFlagsUnderLevel(level: string | null | undefined, flags: FlagSource): DataFlags {
  // An always-active stand-in: only the level rule of `effectivePermission` is wanted here, not the expiry check.
  const standIn = { ...flags, accessLevel: level, accessStatus: 'ACTIVE', isActive: true, revokedAt: null, expiresAt: null };
  const out = { ...NO_DATA_FLAGS };
  for (const key of DATA_FLAGS) out[key] = effectivePermission(standIn, key);
  return out;
}

/** True when the level decides this permission, so its switch cannot be changed. */
export function isFlagFixedByLevel(level: AccessLevel, flag: DataFlag): boolean {
  return level === 'FULL_ACCESS' || (level === 'VIEW_ONLY' && isWriteFlag(flag));
}

/**
 * The flags after the patient picks a level in the editor:
 *   - Full access switches everything on.
 *   - View only keeps what can be seen and switches the three write permissions off.
 *   - View and manage keeps what can be seen and lets the caregiver act on each area they can see.
 *   - Custom keeps the flags as they are.
 * Every switch that the level does not fix can still be changed afterwards.
 */
export function applyLevelPreset(level: AccessLevel, current: DataFlags): DataFlags {
  switch (level) {
    case 'FULL_ACCESS':
    case 'VIEW_ONLY':
      return dataFlagsUnderLevel(level, current);
    case 'EDIT_ONLY':
      return {
        ...current,
        canScheduleAppointments: current.canScheduleAppointments || current.canViewAppointments,
        canManageCarePlan:       current.canManageCarePlan || current.canViewRecords,
        canManageBilling:        current.canManageBilling || current.canViewBilling,
      };
    default:
      return { ...current };
  }
}

/**
 * The flags after one switch is changed. Paying bills needs the bills to be visible, so switching "Manage
 * billing" on also switches "View billing" on, and switching "View billing" off also switches "Manage billing" off.
 */
export function withDataFlag(flags: DataFlags, flag: DataFlag, value: boolean): DataFlags {
  const next = { ...flags, [flag]: value };
  if (flag === 'canManageBilling' && value) next.canViewBilling = true;
  if (flag === 'canViewBilling' && !value) next.canManageBilling = false;
  return next;
}

/** What is sent to the server for a level: the flags with the level rule applied, all ten of them. */
export function dataFlagsToSend(level: AccessLevel, flags: DataFlags): DataFlags {
  return dataFlagsUnderLevel(level, flags);
}

function sentenceList(items: string[]): string {
  if (items.length <= 1) return items.join('');
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;
}

/**
 * One or two sentences saying what a level and flags give, for example
 * "Can see medical records and appointments. Can also book appointments."
 */
export function describeDataAccess(level: string | null | undefined, flags: FlagSource): string {
  if (level === 'FULL_ACCESS') return 'Can see everything and can book appointments, manage your care plan and pay bills.';
  const effective = dataFlagsUnderLevel(level, flags);
  const reads  = DATA_FLAG_OPTIONS.filter(o => !o.write && effective[o.key]).map(o => o.noun);
  const writes = DATA_FLAG_OPTIONS.filter(o => o.write && effective[o.key]).map(o => o.noun);
  if (reads.length === 0 && writes.length === 0) return 'Cannot see or change any of your health information.';
  const parts: string[] = [];
  parts.push(reads.length > 0 ? `Can see ${sentenceList(reads)}.` : 'Cannot see your health information.');
  if (writes.length > 0) parts.push(`Can ${reads.length > 0 ? 'also ' : ''}${sentenceList(writes)}.`);
  return parts.join(' ');
}
