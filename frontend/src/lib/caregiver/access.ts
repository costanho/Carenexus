import type { IoniconsName } from '@/lib/nav/sections';
import { formatDateOnly } from '@/lib/ui/date-only';
import { type CaregiverGrant, effectivePermissions } from './permissions';
import type { AccessLevel, CommFlag, CommFlags, DataFlag, DataFlags, NarrowAccessBody } from './types';

// The permission model behind the caregiver forms (add a loved one, ask for access, reduce your own access).
// It mirrors what the server stores on save: FULL_ACCESS stores every data permission as on, VIEW_ONLY stores the
// three write permissions as off, EDIT_ONLY and CUSTOM store the flags as sent. Messaging and joining calls are
// never part of a level: they are requests that only the patient can approve.

export const READ_FLAGS: readonly DataFlag[] = [
  'canViewRecords', 'canViewAppointments', 'canViewMedications', 'canViewLabResults', 'canViewImaging',
  'canViewConsultations', 'canViewBilling',
];
export const WRITE_FLAGS: readonly DataFlag[] = ['canScheduleAppointments', 'canManageCarePlan', 'canManageBilling'];
export const DATA_FLAGS: readonly DataFlag[] = [...READ_FLAGS, ...WRITE_FLAGS];
export const COMM_FLAGS: readonly CommFlag[] = ['canMessageCareTeam', 'canJoinCalls'];

export interface PermissionOption {
  flag:  DataFlag;
  /** Full label for a switch ("View lab results"). */
  label: string;
  /** Short label for a summary tile ("Lab results"). */
  short: string;
  icon:  IoniconsName;
  /** A permission to change data; View only never includes it. */
  write: boolean;
}

export const PERMISSION_OPTIONS: readonly PermissionOption[] = [
  { flag: 'canViewRecords',          label: 'View medical records',         short: 'Medical records',   icon: 'document-text-outline', write: false },
  { flag: 'canViewAppointments',     label: 'View appointments',            short: 'Appointments',      icon: 'calendar-outline',      write: false },
  { flag: 'canViewMedications',      label: 'View medications',             short: 'Medications',       icon: 'medical-outline',       write: false },
  { flag: 'canViewLabResults',       label: 'View lab results',             short: 'Lab results',       icon: 'flask-outline',         write: false },
  { flag: 'canViewImaging',          label: 'View imaging results',         short: 'Imaging',           icon: 'scan-outline',          write: false },
  { flag: 'canViewConsultations',    label: 'View consultations',           short: 'Consultations',     icon: 'people-outline',        write: false },
  { flag: 'canViewBilling',          label: 'View bills and payments',      short: 'View billing',      icon: 'receipt-outline',       write: false },
  { flag: 'canScheduleAppointments', label: 'Book and change appointments', short: 'Book appointments', icon: 'add-circle-outline',    write: true  },
  { flag: 'canManageCarePlan',       label: 'Manage profile and care plan', short: 'Manage care plan',  icon: 'clipboard-outline',     write: true  },
  { flag: 'canManageBilling',        label: 'Pay bills and manage billing', short: 'Manage billing',    icon: 'card-outline',          write: true  },
];

export const COMM_LABELS: Record<CommFlag, string> = {
  canMessageCareTeam: 'Message the care team',
  canJoinCalls:       'Join video calls',
};

export interface AccessLevelOption {
  value:       AccessLevel;
  label:       string;
  description: string;
}

export const ACCESS_LEVEL_OPTIONS: readonly AccessLevelOption[] = [
  { value: 'FULL_ACCESS', label: 'Full access', description: 'Every permission below, including booking and billing.' },
  { value: 'VIEW_ONLY',   label: 'View only',   description: 'See what you choose below. No booking, editing or paying.' },
  { value: 'EDIT_ONLY',   label: 'Edit',        description: 'See and act on what you choose below.' },
  { value: 'CUSTOM',      label: 'Custom',      description: 'Choose each permission yourself.' },
];

export function accessLevelLabel(level: string | null | undefined): string {
  return ACCESS_LEVEL_OPTIONS.find(o => o.value === level)?.label ?? 'Custom';
}

function isLevel(value: unknown): value is AccessLevel {
  return value === 'FULL_ACCESS' || value === 'VIEW_ONLY' || value === 'EDIT_ONLY' || value === 'CUSTOM';
}

/** An access level the forms understand; anything unexpected is treated as Custom (the flags decide). */
export function toAccessLevel(value: unknown): AccessLevel {
  return isLevel(value) ? value : 'CUSTOM';
}

export function allDataFlags(value: boolean): DataFlags {
  const out = {} as DataFlags;
  for (const flag of DATA_FLAGS) out[flag] = value;
  return out;
}

/** The ten data flags of any object that carries them; a missing flag is off. */
export function dataFlagsOf(source: Partial<Record<DataFlag, boolean | null>> | null | undefined): DataFlags {
  const out = {} as DataFlags;
  for (const flag of DATA_FLAGS) out[flag] = source?.[flag] === true;
  return out;
}

/** Whether a level fixes a flag: 'on' under Full access, 'off' for a write flag under View only, else null. */
export function lockedBy(level: AccessLevel, flag: DataFlag): 'on' | 'off' | null {
  if (level === 'FULL_ACCESS') return 'on';
  if (level === 'VIEW_ONLY' && WRITE_FLAGS.includes(flag)) return 'off';
  return null;
}

/** The flags as the server will store them for this level. */
export function normaliseDataFlags(level: AccessLevel, flags: DataFlags): DataFlags {
  const out = { ...flags };
  for (const flag of DATA_FLAGS) {
    const lock = lockedBy(level, flag);
    if (lock) out[flag] = lock === 'on';
  }
  return out;
}

/**
 * Starting flags when a level is picked in a form that grants access: Full access switches everything on, View
 * only switches every read permission on, Edit adds booking, Custom keeps what is already chosen.
 */
export function presetDataFlags(level: AccessLevel, current: DataFlags): DataFlags {
  if (level === 'FULL_ACCESS') return allDataFlags(true);
  if (level === 'CUSTOM') return { ...current };
  const out = allDataFlags(false);
  for (const flag of READ_FLAGS) out[flag] = true;
  if (level === 'EDIT_ONLY') out.canScheduleAppointments = true;
  return out;
}

// ─── A caregiver reducing their own access ────────────────────────────────────

/** Levels a caregiver may move to from their current one: the current level, Custom (from Full access) and View only. */
export function narrowingLevels(current: AccessLevel): AccessLevel[] {
  if (current === 'FULL_ACCESS') return ['FULL_ACCESS', 'CUSTOM', 'VIEW_ONLY'];
  if (current === 'VIEW_ONLY') return ['VIEW_ONLY'];
  return [current, 'VIEW_ONLY'];
}

export interface NarrowingDraft {
  level:     AccessLevel;
  flags:     DataFlags;
  comm:      CommFlags;
  /** "YYYY-MM-DD", or '' for no end date. */
  expiresOn: string;
}

type NarrowableGrant = CaregiverGrant & { accessLevel?: string | null };

/**
 * The data permissions the caregiver may keep switched on when they choose the flags themselves (Custom, Edit,
 * View only): what they effectively hold now, except that a billing permission counts only when it is stored on
 * the grant. The server refuses a billing flag sent as on while the stored one is off, which is the case for a
 * Full access grant saved before the billing flags existed: there billing comes from the level alone.
 */
export function keepableDataFlags(grant: NarrowableGrant): DataFlags {
  const flags = dataFlagsOf(effectivePermissions(grant));
  flags.canViewBilling   = flags.canViewBilling   && grant.canViewBilling   === true;
  flags.canManageBilling = flags.canManageBilling && grant.canManageBilling === true;
  return flags;
}

/** The editor's starting values: the current level and the permissions the caregiver can keep. */
export function narrowingDraftOf(grant: NarrowableGrant): NarrowingDraft {
  return {
    level:     toAccessLevel(grant.accessLevel),
    flags:     keepableDataFlags(grant),
    comm:      { canMessageCareTeam: grant.canMessageCareTeam === true, canJoinCalls: grant.canJoinCalls === true },
    expiresOn: (grant.expiresAt ?? '').slice(0, 10),
  };
}

export interface NarrowingPlan {
  /** The PATCH body: only what is being reduced. Empty when nothing changes. */
  body:    NarrowAccessBody;
  /** One sentence per thing the caregiver gives up, for the confirmation. */
  removed: string[];
  changed: boolean;
}

/**
 * Turns the editor's values into the PATCH body and the list of what is given up. A permission can only go from
 * on to off: a value the caregiver does not hold now is never sent as on, whatever the draft says.
 */
export function planNarrowing(grant: NarrowableGrant, draft: NarrowingDraft): NarrowingPlan {
  const before = dataFlagsOf(effectivePermissions(grant));
  const keepable = keepableDataFlags(grant);
  const currentLevel = toAccessLevel(grant.accessLevel);
  const wanted = normaliseDataFlags(draft.level, draft.flags);
  const after = {} as DataFlags;
  for (const flag of DATA_FLAGS) {
    // A permission the level switches on stays as it is; any other one survives only when it can be sent as on.
    const fromLevel = lockedBy(draft.level, flag) === 'on';
    after[flag] = before[flag] && wanted[flag] && (fromLevel || keepable[flag]);
  }

  const body: NarrowAccessBody = {};
  const removed: string[] = [];

  const lost = PERMISSION_OPTIONS.filter(o => before[o.flag] && !after[o.flag]);
  const levelChanged = draft.level !== currentLevel;
  if (levelChanged) {
    body.accessLevel = draft.level;
    removed.push(`Your access level changes from ${accessLevelLabel(currentLevel)} to ${accessLevelLabel(draft.level)}.`);
  }
  if (levelChanged || lost.length > 0) {
    // Every flag the new level does not fix is sent, so the stored values match what the caregiver saw on screen
    // (a grant saved before flags were normalised can hold values that differ from the effective ones).
    for (const flag of DATA_FLAGS) {
      if (lockedBy(draft.level, flag) === null) body[flag] = after[flag];
    }
  }
  for (const option of lost) removed.push(`${option.label}.`);

  for (const flag of COMM_FLAGS) {
    if (grant[flag] === true && !draft.comm[flag]) {
      body[flag] = false;
      removed.push(`${COMM_LABELS[flag]}.`);
    }
  }

  const currentDay = (grant.expiresAt ?? '').slice(0, 10);
  if (draft.expiresOn && draft.expiresOn !== currentDay) {
    body.expiresAt = draft.expiresOn;
    removed.push(`Your access will end on ${formatDateOnly(draft.expiresOn)}.`);
  }

  return { body, removed, changed: Object.keys(body).length > 0 };
}

/**
 * Checks the end date typed by a caregiver who is reducing their own access: it may be set or moved earlier,
 * never removed or moved later. Returns the sentence to show, or null when the value is acceptable.
 */
export function narrowingExpiryError(
  currentExpiresAt: string | null | undefined,
  expiresOn: string,
  today: string,
  patientFirstName: string,
): string | null {
  const currentDay = (currentExpiresAt ?? '').slice(0, 10);
  if (!expiresOn) {
    return currentDay ? `You cannot remove the end date. Ask ${patientFirstName} to extend your access.` : null;
  }
  if (expiresOn === currentDay) return null;
  if (expiresOn < today) return 'Choose today or a later date.';
  if (currentDay && expiresOn > currentDay) {
    return `You cannot extend your own access. Ask ${patientFirstName} to extend it.`;
  }
  return null;
}
