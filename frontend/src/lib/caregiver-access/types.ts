// Types for the patient's caregiver access screen (My Proxies), camelCase straight from the API
// (api/be-caregiver-core.md and api/be-caregiver-requests-consent.md).
//
// Date and time kinds:
//   - "clinic local date-time": clinic wall clock, no offset ("2026-11-10T09:14:22.418"). Format and compare with
//     '@/lib/time/clinic', never with the device clock.
//   - "instant": UTC with an offset ("2026-11-10T07:14:22.418Z").

export type AccessLevel = 'FULL_ACCESS' | 'VIEW_ONLY' | 'EDIT_ONLY' | 'CUSTOM';
export type AccessStatus = 'ACTIVE' | 'EXPIRED' | 'REVOKED';
export type AccessRequestStatus = 'PENDING' | 'APPROVED' | 'DECLINED' | 'CANCELLED' | 'EXPIRED';

/** The ten data permissions a level and the individual switches decide. */
export interface DataFlags {
  canViewRecords:          boolean;
  canViewAppointments:     boolean;
  canViewMedications:      boolean;
  canViewLabResults:       boolean;
  canViewImaging:          boolean;
  canViewConsultations:    boolean;
  canViewBilling:          boolean;
  canScheduleAppointments: boolean;
  canManageCarePlan:       boolean;
  canManageBilling:        boolean;
}

/** The two communication permissions. A level never grants them; the patient approves each one. */
export interface CommunicationFlags {
  canMessageCareTeam: boolean;
  canJoinCalls:       boolean;
}

/** All twelve permission flags, with the same names as `CaregiverFlag` in '@/lib/caregiver/permissions'. */
export interface AccessFlags extends DataFlags, CommunicationFlags {}

export type DataFlag = keyof DataFlags;
export type CommunicationFlag = keyof CommunicationFlags;

/**
 * A caregiver grant as the patient sees it (GET /api/patients/me/caregiver-access).
 *
 * The data flags are the STORED values: decide what the caregiver may do with `effectivePermission` from
 * '@/lib/caregiver/permissions' (FULL_ACCESS grants everything, VIEW_ONLY blocks the three write flags).
 * canMessageCareTeam / canJoinCalls are requests; messagingAllowed / joinCallsAllowed are what is in effect now
 * (the patient approved and the grant is active). pendingApproval: a request is waiting for the patient.
 */
export interface CaregiverAccess extends AccessFlags {
  accessId:             number;
  caregiverSystemId:    string;
  patientSystemId:      string;
  relationship:         string;
  accessLevel:          AccessLevel;
  /** Instant; when the patient approved messaging. */
  messagingApprovedAt:  string | null;
  /** Instant; when the patient approved joining calls. */
  callsApprovedAt:      string | null;
  messagingAllowed:     boolean;
  joinCallsAllowed:     boolean;
  pendingApproval:      boolean;
  authorizedBySystemId: string | null;
  /** Clinic local date-time. */
  authorizedDate:       string;
  /** Clinic local date-time; null = no end date. */
  expiresAt:            string | null;
  isActive:             boolean;
  /** Clinic local date-time. */
  revokedAt:            string | null;
  accessStatus:         AccessStatus;
  /** Instant; set when the caregiver attested to being the parent or legal guardian. */
  guardianAttestedAt?:  string | null;
  /** True while the patient's account has no password. */
  managed?:             boolean;
  /** Always false in the patient view. */
  canInvite?:           boolean;
  caregiverName:        string | null;
  caregiverEmail:       string | null;
  patientName:          string | null;
  patientEmail:         string | null;
  patientPhone?:        string | null;
  patientGender:        string | null;
  patientHealthStatus:  string | null;
  patientDateOfBirth:   string | null;
  patientBloodType:     string | null;
}

/** Body of POST /api/patients/me/caregiver-access. Absent flags are false; absent level is VIEW_ONLY. */
export interface GrantCaregiverAccessBody extends Partial<AccessFlags> {
  caregiverEmail: string;
  relationship:   string;
  accessLevel?:   AccessLevel;
  /** "YYYY-MM-DD" (the end of that clinic day); null or absent = no end date. */
  expiresAt?:     string | null;
}

/** Body of PATCH /api/patients/me/caregiver-access/{accessId}. Absent fields stay as they are. */
export interface UpdateCaregiverAccessBody extends Partial<AccessFlags> {
  accessLevel?: AccessLevel;
  /** "YYYY-MM-DD"; "" removes the end date; omit to leave it unchanged. */
  expiresAt?:   string;
}

/** One access request addressed to the patient (GET /api/patients/me/caregiver-access/requests). */
export interface CaregiverAccessRequest extends AccessFlags {
  requestId:         number;
  status:            AccessRequestStatus;
  caregiverSystemId: string;
  caregiverName:     string | null;
  caregiverEmail:    string | null;
  patientEmail:      string;
  patientSystemId:   string | null;
  patientName:       string | null;
  /** As stated by the caregiver; becomes the grant's relationship on approval. */
  relationship:      string;
  /** The REQUESTED level; the flags on this row are the requested permissions and never change. */
  accessLevel:       AccessLevel;
  /** The caregiver's note, at most 500 characters. */
  message:           string | null;
  declineReason:     string | null;
  /** The grant created or reused by the approval; set only when APPROVED. */
  accessId:          number | null;
  /** Clinic local date-times. */
  createdAt:         string;
  requestExpiresAt:  string;
  respondedAt:       string | null;
}

/**
 * Body of POST .../requests/{requestId}/approve. It is authoritative: `accessLevel` is required, an absent flag is
 * false and an absent `expiresAt` means no end date, so the screen sends the level and all twelve flags.
 */
export interface ApproveAccessRequestBody extends Partial<AccessFlags> {
  accessLevel: AccessLevel;
  /** "YYYY-MM-DD" (the end of that clinic day); null or absent = no end date. */
  expiresAt?:  string | null;
}

/** Body of POST .../requests/{requestId}/decline. The reason is shown to the caregiver. */
export interface DeclineAccessRequestBody {
  reason?: string;
}

/** Snapshot of a grant right after one lifecycle step. The data flags are the stored values. */
export interface ConsentScope extends AccessFlags {
  accessLevel:       AccessLevel;
  messagingApproved: boolean;
  callsApproved:     boolean;
  /** Clinic local date-time; null = no end date. */
  expiresAt:         string | null;
  guardianAttested:  boolean;
}

export type ConsentEventKind =
  | 'GRANTED' | 'PERMISSIONS_CHANGED' | 'COMMUNICATION_APPROVED' | 'EXTENDED' | 'NARROWED' | 'REVOKED' | 'EXPIRED';

/** One immutable row of the patient's consent ledger (GET /api/patients/me/consents). */
export interface ConsentRecord {
  consentId:         number;
  eventKind:         ConsentEventKind;
  accessId:          number;
  patientSystemId:   string;
  caregiverSystemId: string;
  caregiverName:     string | null;
  /** Who performed the step: the patient, the caregiver, an administrator (ADM-), or null for the system. */
  grantedBySystemId: string | null;
  scope:             ConsentScope;
  /** Clinic local date-time. */
  recordedAt:        string;
}

/** One thing a caregiver viewed or did with the patient's data (GET .../{accessId}/activity). */
export interface CaregiverActivityEntry {
  auditId:      number;
  /** READ, CREATE, UPDATE, DELETE or an account event in upper snake case (open set). */
  action:       string;
  /** Audit resource type in upper snake case (open set); may be null. */
  resourceType: string | null;
  /** Clinic local date-time. */
  timestamp:    string;
}
