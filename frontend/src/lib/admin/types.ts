import { z } from 'zod';

// Administrator area: accounts, clinician verification and the audit log.
// DTO types are camelCase straight from the API (contracts api/be-admin-users.md section 8,
// api/be-account-mfa.md 1.9 and 1.10, api/be-account-legal.md 1.5). The form schemas at the end mirror the
// backend constraints and reuse the backend's validation texts.

// ─── Enums ────────────────────────────────────────────────────────────────────

/** Stored role values. Unlike the session role they are upper case and say CAREGIVER, not proxy. */
export type AdminRole = 'PATIENT' | 'DOCTOR' | 'CAREGIVER' | 'ADMIN';
export type VerificationStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUSPENDED';
export type RegistryStatus = 'VERIFIED' | 'NOT_FOUND' | 'UNVERIFIED_MANUAL_REVIEW';
export type AuditStatus = 'SUCCESS' | 'FAILURE';
export type LegalDocType = 'TERMS' | 'PRIVACY';

export const ADMIN_ROLES: readonly AdminRole[] = ['PATIENT', 'DOCTOR', 'CAREGIVER', 'ADMIN'];
/** In the order the verification queue offers them: the work to do first. */
export const VERIFICATION_STATUSES: readonly VerificationStatus[] = ['PENDING', 'REJECTED', 'SUSPENDED', 'APPROVED'];
export const AUDIT_STATUSES: readonly AuditStatus[] = ['SUCCESS', 'FAILURE'];

/** Error codes of the admin endpoints that the screens react to. */
export const ADMIN_ERROR = {
  LICENCE_REQUIRED:    'LICENCE_REQUIRED',
  DOCTOR_NOT_VERIFIED: 'DOCTOR_NOT_VERIFIED',
  ACCOUNT_CLOSED:      'ACCOUNT_CLOSED',
} as const;

// ─── Accounts ─────────────────────────────────────────────────────────────────

/** One row of GET /api/admin/users. Timestamps are clinic wall-clock strings (no offset). */
export interface AdminUser {
  userId:    number;
  systemId:  string;
  firstName: string;
  lastName:  string;
  /** Null for a managed dependent without sign-in and for an anonymised account. */
  email:     string | null;
  phone:     string | null;
  role:      AdminRole;
  /** False = cannot sign in. */
  isActive:  boolean;
  createdAt: string;
  updatedAt: string;
  emailVerified:      boolean;
  /** A temporary password is in force. */
  mustChangePassword: boolean;
  /** Set when the owner closed the account, or it was anonymised. */
  closedAt:   string | null;
  /** Personal data was removed on request; the account can never be reactivated. */
  anonymised: boolean;
  /** Only for role DOCTOR; null for other roles and for a doctor account without a clinician row. */
  verificationStatus: VerificationStatus | null;
}

/** The clinician record: a row of the verification queue and the `doctor` of a user detail. */
export interface AdminDoctor {
  doctorSystemId: string;
  userId:         number;
  firstName:      string;
  lastName:       string;
  email:          string | null;
  phone:          string | null;
  emailVerified:  boolean;
  specialization: string | null;
  /** Null = no licence number on file; approval is refused until one is entered. */
  licenseNo:      string | null;
  licensingBody:  string | null;
  /** ISO 3166-1 alpha-2, upper case. */
  licenseCountry: string | null;
  verificationStatus: VerificationStatus;
  /** Answer of the licence registry. Information only: it never blocks or grants approval. */
  registryStatus:     RegistryStatus;
  /** Reason of the last rejection or suspension. */
  rejectionReason:    string | null;
  verifiedBySystemId: string | null;
  verifiedAt:         string | null;
  isActive:        boolean;
  doctorCreatedAt: string;
}

export interface AdminUserDetail extends AdminUser {
  emailVerifiedAt:   string | null;
  passwordChangedAt: string | null;
  /** False for a managed dependent that cannot sign in yet (a password reset then sends an invitation). */
  hasPassword:       boolean;
  closureReason:      string | null;
  deactivationReason: string | null;
  /** Sessions that can still be refreshed. */
  activeSessions:           number;
  /** Scheduled appointments after now: as doctor for a DOCTOR, as patient for a PATIENT, otherwise 0. */
  futureActiveAppointments: number;
  doctor: AdminDoctor | null;
}

/** The account after an action, plus one sentence that says what happened. */
export interface AdminUserActionResult extends AdminUserDetail {
  message: string;
}

export interface AdminUserFilters {
  /** Free text: name, email or account ID. */
  q?:      string;
  role?:   AdminRole | '';
  /** true = active accounts, false = inactive, undefined = both. */
  active?: boolean;
}

export interface CreateAdminRequest {
  firstName: string;
  lastName:  string;
  email:     string;
  /** The temporary password; the new administrator must change it at the first sign-in. */
  password:  string;
  phone?:    string | null;
}

/** Body of deactivate and reject. */
export interface ReasonRequest { reason: string }

/** PUT .../licence replaces all four fields. */
export interface LicenceRequest {
  licenseNo:      string;
  licensingBody:  string | null;
  licenseCountry: string | null;
  specialization: string | null;
}

// ─── Two-factor authentication and legal acceptances (other slices' admin endpoints) ───

/** GET /api/admin/users/{systemId}/mfa. `enabledAt` is an instant (UTC with Z). */
export interface MfaStatus {
  enabled:  boolean;
  /** The account's role must keep two-factor authentication. */
  required: boolean;
  recoveryCodesRemaining: number;
  enabledAt: string | null;
}

/** One row of GET /api/admin/users/{systemId}/legal-acceptances. `acceptedAt` is an instant (UTC with Z). */
export interface LegalAcceptance {
  acceptanceId: number;
  systemId:     string;
  docType:      LegalDocType;
  version:      string;
  acceptedAt:   string;
  ipAddress:    string | null;
  userAgent:    string | null;
  /** True when `version` is the current version of the document. */
  current:      boolean;
}

// ─── Audit log ────────────────────────────────────────────────────────────────

export interface AuditEntry {
  auditId: number;
  /** The actor; null when nobody was signed in (for example a failed login). */
  systemId:  string | null;
  actorName: string | null;
  actorRole: string | null;
  /** Upper snake case, open list: READ, CREATE, LOGIN, USER_DEACTIVATE, ... */
  action:       string;
  resourceType: string | null;
  resourceId:   number | null;
  patientSystemId: string | null;
  /** The account an account or security event is about. */
  targetSystemId:  string | null;
  httpMethod:  string | null;
  requestPath: string | null;
  status:    AuditStatus;
  ipAddress: string | null;
  userAgent: string | null;
  errorMessage: string | null;
  /** Clinic wall-clock. */
  timestamp: string;
}

export interface AuditFilters {
  actor?:        string;
  patient?:      string;
  action?:       string;
  resourceType?: string;
  status?:       AuditStatus | '';
  /** YYYY-MM-DD, clinic date, inclusive. */
  from?:         string;
  to?:           string;
}

// ─── Labels ───────────────────────────────────────────────────────────────────

/** The tone names of the `StatusBadge` primitive (kept as a plain union so this file needs no UI import). */
export type StatusTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger' | 'critical';

const ROLE_LABEL: Record<AdminRole, string> = {
  PATIENT: 'Patient', DOCTOR: 'Doctor', CAREGIVER: 'Caregiver', ADMIN: 'Administrator',
};

/** "Administrator" for ADMIN; an unknown value is shown as sent. */
export function roleLabel(role: string | null | undefined): string {
  const key = (role ?? '').trim().toUpperCase();
  if (key === 'PROXY') return ROLE_LABEL.CAREGIVER;
  return ROLE_LABEL[key as AdminRole] ?? (role?.trim() || 'Unknown');
}

/** "First Last", or the account ID when the name is empty. */
export function fullName(person: { firstName?: string | null; lastName?: string | null }, fallback = ''): string {
  return `${person.firstName ?? ''} ${person.lastName ?? ''}`.trim() || fallback;
}

const VERIFICATION_LABEL: Record<VerificationStatus, string> = {
  PENDING: 'Awaiting verification', APPROVED: 'Approved', REJECTED: 'Rejected', SUSPENDED: 'Suspended',
};
const VERIFICATION_TONE: Record<VerificationStatus, StatusTone> = {
  PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', SUSPENDED: 'danger',
};

export function verificationLabel(status: VerificationStatus): string {
  return VERIFICATION_LABEL[status] ?? status;
}
export function verificationTone(status: VerificationStatus): StatusTone {
  return VERIFICATION_TONE[status] ?? 'neutral';
}

/** The registry answer in words. An unknown value is treated as manual review, as the contract says. */
export function registryLabel(status: string | null | undefined): string {
  if (status === 'VERIFIED') return 'Found in the licence registry';
  if (status === 'NOT_FOUND') return 'Not found in the licence registry';
  return 'Not checked automatically: review manually';
}
export function registryTone(status: string | null | undefined): StatusTone {
  if (status === 'VERIFIED') return 'success';
  if (status === 'NOT_FOUND') return 'danger';
  return 'warning';
}

/** Why an account can or cannot sign in, as one state. */
export type AccountState = 'ACTIVE' | 'DEACTIVATED' | 'CLOSED' | 'DELETED' | 'PENDING' | 'REJECTED' | 'SUSPENDED';

export function accountState(user: Pick<AdminUser, 'isActive' | 'anonymised' | 'closedAt' | 'role' | 'verificationStatus'>): AccountState {
  if (user.anonymised) return 'DELETED';
  if (user.closedAt) return 'CLOSED';
  if (user.isActive) return 'ACTIVE';
  if (user.role === 'DOCTOR') {
    if (user.verificationStatus === 'PENDING') return 'PENDING';
    if (user.verificationStatus === 'REJECTED') return 'REJECTED';
    if (user.verificationStatus === 'SUSPENDED') return 'SUSPENDED';
  }
  return 'DEACTIVATED';
}

const ACCOUNT_STATE: Record<AccountState, { label: string; tone: StatusTone }> = {
  ACTIVE:      { label: 'Active',                tone: 'success' },
  DEACTIVATED: { label: 'Deactivated',           tone: 'neutral' },
  CLOSED:      { label: 'Closed by owner',       tone: 'neutral' },
  DELETED:     { label: 'Deleted on request',    tone: 'neutral' },
  PENDING:     { label: 'Awaiting verification', tone: 'warning' },
  REJECTED:    { label: 'Rejected',              tone: 'danger' },
  SUSPENDED:   { label: 'Suspended',             tone: 'danger' },
};

export function accountStateLabel(state: AccountState): string { return ACCOUNT_STATE[state].label; }
export function accountStateTone(state: AccountState): StatusTone { return ACCOUNT_STATE[state].tone; }

/**
 * What an administrator may do with an account (client rules of api/be-admin-users.md section 8). The server
 * still decides: these only keep controls that cannot work off the screen.
 */
export interface AccountActions {
  /** Reactivate a deactivated account, or re-open one its owner closed. */
  canActivate:   boolean;
  /** The activation re-opens an account that its owner closed. */
  isReopen:      boolean;
  canDeactivate: boolean;
  /** An unverified clinician is activated from the verification queue, not here. */
  needsVerification: boolean;
  /** A link to set a new password can be sent (needs an email address and an account that is not closed). */
  canSendPasswordLink: boolean;
  /** Sign-out everywhere and the other security actions; false only for an anonymised account. */
  canManageSecurity:   boolean;
}

export function accountActions(user: AdminUser, selfSystemId: string | null | undefined): AccountActions {
  if (user.anonymised) {
    return {
      canActivate: false, isReopen: false, canDeactivate: false, needsVerification: false,
      canSendPasswordLink: false, canManageSecurity: false,
    };
  }
  const isDoctor = user.role === 'DOCTOR';
  const verified = !isDoctor || user.verificationStatus === 'APPROVED' || user.verificationStatus === 'SUSPENDED';
  const needsVerification = isDoctor && !user.isActive
    && (user.verificationStatus === 'PENDING' || user.verificationStatus === 'REJECTED');
  return {
    canActivate:   !user.isActive && verified,
    isReopen:      !user.isActive && user.closedAt !== null,
    canDeactivate: user.isActive && user.systemId !== selfSystemId,
    needsVerification,
    canSendPasswordLink: user.email !== null && user.closedAt === null,
    canManageSecurity:   true,
  };
}

/**
 * What a deactivation means for the account's upcoming appointments, from the count returned with it.
 * Null when there is nothing to say (no appointments, or a role without appointments).
 */
export function appointmentImpact(user: Pick<AdminUserDetail, 'role' | 'futureActiveAppointments'>): string | null {
  const n = user.futureActiveAppointments;
  if (!n || n <= 0) return null;
  if (user.role === 'DOCTOR') {
    return n === 1
      ? '1 upcoming appointment with this doctor will be cancelled and the patient informed.'
      : `${n} upcoming appointments with this doctor will be cancelled and the patients informed.`;
  }
  if (user.role === 'PATIENT') {
    return n === 1 ? 'This patient has 1 upcoming appointment.' : `This patient has ${n} upcoming appointments.`;
  }
  return null;
}

export function legalDocLabel(docType: string): string {
  if (docType === 'TERMS') return 'Terms of Service';
  if (docType === 'PRIVACY') return 'Privacy Policy';
  return docType;
}

// ─── Form schemas (mirror the backend Bean Validation, with the backend's texts) ──────────────

/** `ValidationPatterns.PHONE` of the backend. */
export const ADMIN_PHONE_PATTERN = /^\+?(?:[ .()-]*\d){7,15}[ .()-]*$/;

export const REASON_MAX = 500;
export const SEARCH_MAX = 100;

function utf8Length(value: string): number {
  if (typeof TextEncoder !== 'undefined') return new TextEncoder().encode(value).length;
  return unescape(encodeURIComponent(value)).length;
}

const PASSWORD_RULE = 'Password must be at least 8 characters and contain at least one letter and one number';
const PASSWORD_TOO_LONG = 'Password must be at most 72 characters';

/** POST /api/admin/users. */
export const createAdminSchema = z.object({
  firstName: z.string().trim().min(1, 'First name is required').max(100, 'First name must be at most 100 characters'),
  lastName:  z.string().trim().min(1, 'Last name is required').max(100, 'Last name must be at most 100 characters'),
  email:     z.string().trim().min(1, 'Email is required').max(150, 'Please enter a valid email address')
               .email('Please enter a valid email address'),
  phone:     z.string().trim().max(20, 'Phone number must be at most 20 characters')
               .refine(v => v === '' || ADMIN_PHONE_PATTERN.test(v), 'Please enter a valid phone number'),
  password:  z.string().min(1, 'Password is required')
               .refine(v => v.length >= 8 && /[A-Za-z]/.test(v) && /\d/.test(v), PASSWORD_RULE)
               // The server stores at most 72 bytes, so characters outside ASCII count more than once.
               .refine(v => v.length <= 72 && utf8Length(v) <= 72, PASSWORD_TOO_LONG),
});
export type CreateAdminForm = z.infer<typeof createAdminSchema>;
export const CREATE_ADMIN_FIELDS = ['firstName', 'lastName', 'email', 'phone', 'password'] as const;

/** Body of deactivate and reject. */
export const reasonSchema = z.object({
  reason: z.string().trim().min(1, 'Reason is required').max(REASON_MAX, 'Reason must be at most 500 characters'),
});
export type ReasonForm = z.infer<typeof reasonSchema>;

/** PUT /api/admin/doctors/{systemId}/licence. */
export const licenceSchema = z.object({
  licenseNo:      z.string().trim().min(1, 'Licence number is required').max(50, 'Licence number must be at most 50 characters'),
  licensingBody:  z.string().trim().max(150, 'Licensing body must be at most 150 characters'),
  licenseCountry: z.string().trim()
                    .refine(v => v === '' || /^[A-Za-z]{2}$/.test(v), 'Licence country must be a two-letter country code'),
  specialization: z.string().trim().max(150, 'Specialization must be at most 150 characters'),
});
export type LicenceForm = z.infer<typeof licenceSchema>;
export const LICENCE_FIELDS = ['licenseNo', 'licensingBody', 'licenseCountry', 'specialization'] as const;

/** The request for a validated licence form: blank optional fields are sent as null (they clear the value). */
export function toLicenceRequest(form: LicenceForm): LicenceRequest {
  const orNull = (v: string) => (v.trim() === '' ? null : v.trim());
  return {
    licenseNo:      form.licenseNo.trim(),
    licensingBody:  orNull(form.licensingBody),
    licenseCountry: orNull(form.licenseCountry)?.toUpperCase() ?? null,
    specialization: orNull(form.specialization),
  };
}

const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Checks the audit date range before a request is sent (every audit read is itself audited, so a request that
 * can only fail is not worth making). Returns the sentence to show, or null when the range is fine.
 */
export function auditRangeError(from: string | undefined, to: string | undefined): string | null {
  if (from && !DATE_ONLY.test(from)) return 'Enter the start date as YYYY-MM-DD.';
  if (to && !DATE_ONLY.test(to)) return 'Enter the end date as YYYY-MM-DD.';
  if (from && to && from > to) return 'The start date must not be after the end date.';
  return null;
}
