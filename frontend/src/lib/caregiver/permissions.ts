import { clinicNowLocal, toClinicLocal } from '@/lib/time/clinic';

// The caregiver effective-permission rule, mirroring the backend (PatientPermission.isGrantedBy and the expiry-aware
// grant queries, CONTRACTS section 4). Screens use it to decide which panels and actions to show for a loved one;
// the server still enforces every request.
//
//   - A grant counts only while it is active: not revoked, not switched off, not past its end date.
//   - Data permissions: access level FULL_ACCESS grants everything; otherwise the flag must be on, and a write
//     permission is also refused when the level is VIEW_ONLY.
//   - Communication permissions (messaging, joining calls) are requests that take effect only once the patient
//     approves them; the access level neither grants nor blocks them.

/** The can* flags on a caregiver grant, including the billing flags added with V23. */
export type CaregiverFlag =
  | 'canViewRecords'
  | 'canViewAppointments'
  | 'canViewMedications'
  | 'canViewLabResults'
  | 'canViewImaging'
  | 'canViewConsultations'
  | 'canScheduleAppointments'
  | 'canManageCarePlan'
  | 'canViewBilling'
  | 'canManageBilling'
  | 'canMessageCareTeam'
  | 'canJoinCalls';

/** Backend PatientPermission names a caregiver screen may need to check. */
export type CaregiverPermission =
  | 'PROFILE' | 'MANAGE_PROFILE'
  | 'RECORDS' | 'MEDICATIONS' | 'LAB_RESULTS' | 'IMAGING' | 'APPOINTMENTS' | 'CONSULTATIONS'
  | 'MANAGE_APPOINTMENTS' | 'MANAGE_CARE_PLAN'
  | 'BILLING' | 'MANAGE_BILLING'
  | 'CARE_PLANS' | 'HEALTH_METRICS' | 'MANAGE_HEALTH_METRICS'
  | 'REFERRALS' | 'DOCUMENTS' | 'MANAGE_DOCUMENTS' | 'AI_ALERTS' | 'CARE_TEAM'
  | 'MESSAGING' | 'JOIN_CALLS';

/**
 * The fields of a caregiver grant the rule reads. `CaregiverDependent` (use-caregiver-dependents) and the patient's
 * own view of a grant both satisfy it; every field is optional so partial rows are handled (a missing flag is off).
 */
export interface CaregiverGrant {
  accessLevel?:             string | null;
  accessStatus?:            string | null;
  isActive?:                boolean | null;
  revokedAt?:               string | null;
  expiresAt?:               string | null;
  canViewRecords?:          boolean | null;
  canViewAppointments?:     boolean | null;
  canViewMedications?:      boolean | null;
  canViewLabResults?:       boolean | null;
  canViewImaging?:          boolean | null;
  canViewConsultations?:    boolean | null;
  canScheduleAppointments?: boolean | null;
  canManageCarePlan?:       boolean | null;
  canViewBilling?:          boolean | null;
  canManageBilling?:        boolean | null;
  canMessageCareTeam?:      boolean | null;
  canJoinCalls?:            boolean | null;
  /** Server-computed: messaging requested AND approved by the patient. */
  messagingAllowed?:        boolean | null;
  /** Server-computed: joining calls requested AND approved by the patient. */
  joinCallsAllowed?:        boolean | null;
}

const WRITE_FLAGS: ReadonlySet<CaregiverFlag> = new Set<CaregiverFlag>([
  'canScheduleAppointments', 'canManageCarePlan', 'canManageBilling',
]);

/** True for flags that let the caregiver change data (blocked by VIEW_ONLY). */
export function isWriteFlag(flag: CaregiverFlag): boolean {
  return WRITE_FLAGS.has(flag);
}

/**
 * True while the grant authorises anything at all: active, not revoked and not past its end date.
 * The end date is a clinic wall-clock value and is compared with the clinic's current time.
 */
export function isGrantActive(dependent: CaregiverGrant | null | undefined, now: Date = new Date()): boolean {
  if (!dependent) return false;
  if (dependent.accessStatus && dependent.accessStatus !== 'ACTIVE') return false;
  if (dependent.isActive === false || dependent.revokedAt) return false;
  if (dependent.expiresAt) {
    const expires = toClinicLocal(dependent.expiresAt);
    // An unreadable end date is treated as expired: never show more than the server would allow.
    if (!expires || expires <= clinicNowLocal(now)) return false;
  }
  return true;
}

/**
 * Whether the caregiver effectively holds a flag on this grant right now, the way the backend decides it.
 * Use this instead of reading `dependent.canX` directly: a FULL_ACCESS grant may carry stale false flags.
 */
export function effectivePermission(
  dependent: CaregiverGrant | null | undefined,
  flag: CaregiverFlag,
  now: Date = new Date(),
): boolean {
  if (!dependent || !isGrantActive(dependent, now)) return false;

  if (flag === 'canMessageCareTeam') return dependent.messagingAllowed === true;
  if (flag === 'canJoinCalls')       return dependent.joinCallsAllowed === true;

  if (dependent.accessLevel === 'FULL_ACCESS') return true;
  if (isWriteFlag(flag) && dependent.accessLevel === 'VIEW_ONLY') return false;
  return dependent[flag] === true;
}

/** All data flags as the caregiver effectively holds them (for an access summary or to seed an editor). */
export function effectivePermissions(
  dependent: CaregiverGrant | null | undefined,
  now: Date = new Date(),
): Record<CaregiverFlag, boolean> {
  const flags: CaregiverFlag[] = [
    'canViewRecords', 'canViewAppointments', 'canViewMedications', 'canViewLabResults', 'canViewImaging',
    'canViewConsultations', 'canScheduleAppointments', 'canManageCarePlan', 'canViewBilling', 'canManageBilling',
    'canMessageCareTeam', 'canJoinCalls',
  ];
  const out = {} as Record<CaregiverFlag, boolean>;
  for (const flag of flags) out[flag] = effectivePermission(dependent, flag, now);
  return out;
}

/**
 * Whether the caregiver passes the backend permission of that name for this loved one (CONTRACTS section 4 table).
 * Example: `hasCaregiverPermission(dependent, 'MANAGE_DOCUMENTS')` decides whether to show the upload button.
 */
export function hasCaregiverPermission(
  dependent: CaregiverGrant | null | undefined,
  permission: CaregiverPermission,
  now: Date = new Date(),
): boolean {
  const has = (flag: CaregiverFlag) => effectivePermission(dependent, flag, now);
  switch (permission) {
    case 'PROFILE':
    case 'CARE_TEAM':             return isGrantActive(dependent, now);
    case 'MANAGE_PROFILE':
    case 'MANAGE_CARE_PLAN':
    case 'MANAGE_HEALTH_METRICS':
    case 'MANAGE_DOCUMENTS':      return has('canManageCarePlan');
    case 'RECORDS':
    case 'HEALTH_METRICS':
    case 'REFERRALS':
    case 'DOCUMENTS':
    case 'AI_ALERTS':             return has('canViewRecords');
    // A read permission: either flag is enough, and VIEW_ONLY does not block it even when the flag is the manage one.
    case 'CARE_PLANS':
      return has('canViewRecords')
        || (isGrantActive(dependent, now) && dependent?.canManageCarePlan === true);
    case 'MEDICATIONS':           return has('canViewMedications');
    case 'LAB_RESULTS':           return has('canViewLabResults');
    case 'IMAGING':               return has('canViewImaging');
    case 'APPOINTMENTS':          return has('canViewAppointments');
    case 'CONSULTATIONS':         return has('canViewConsultations');
    case 'MANAGE_APPOINTMENTS':   return has('canScheduleAppointments');
    case 'BILLING':               return has('canViewBilling');
    case 'MANAGE_BILLING':        return has('canManageBilling');
    case 'MESSAGING':             return has('canMessageCareTeam');
    case 'JOIN_CALLS':            return has('canJoinCalls');
    default:                      return false;
  }
}
