import type { PageResponse } from '@/lib/api/types';

// Caregiver (proxy) API shapes: grants, the activity feed, managed dependents, access requests and a dependent's
// profile. Field names are the backend's JSON names. `@/hooks/use-caregiver-dependents` re-exports the grant and
// activity types under the names older screens (web and native) already import.

export type AccessLevel  = 'FULL_ACCESS' | 'VIEW_ONLY' | 'EDIT_ONLY' | 'CUSTOM';
export type AccessStatus = 'ACTIVE' | 'EXPIRED' | 'REVOKED';
export type HealthStatus = 'STABLE' | 'MONITOR' | 'CRITICAL' | string;
export type Gender       = 'MALE' | 'FEMALE' | 'OTHER';
export type BloodType    = 'A+' | 'A-' | 'B+' | 'B-' | 'O+' | 'O-' | 'AB+' | 'AB-';

/** Video call session status (one session per VIDEO appointment). */
export type CallStatus =
  | 'PREPARING'
  | 'READY'
  | 'FAILED'
  | 'LIVE'
  | 'ENDED'
  | 'CANCELLED'
  | 'NEEDS_TIME_CONFIRMATION';

/** The ten data permissions of a grant. Read flags first, then the three write flags. */
export type DataFlag =
  | 'canViewRecords'
  | 'canViewAppointments'
  | 'canViewMedications'
  | 'canViewLabResults'
  | 'canViewImaging'
  | 'canViewConsultations'
  | 'canViewBilling'
  | 'canScheduleAppointments'
  | 'canManageCarePlan'
  | 'canManageBilling';

/** Messaging and joining calls: requests that only the patient can approve. */
export type CommFlag = 'canMessageCareTeam' | 'canJoinCalls';

export type DataFlags = Record<DataFlag, boolean>;
export type CommFlags = Record<CommFlag, boolean>;

/** One grant as the caregiver sees it (GET /api/caregiver/dependents and every write that returns the grant). */
export interface CaregiverDependent {
  accessId:                 number;
  caregiverSystemId:        string;
  patientSystemId:          string;
  relationship:             string;
  accessLevel:              AccessLevel;
  // Stored data flags. Decide what the caregiver may do with effectivePermission (@/lib/caregiver/permissions),
  // never with these directly: FULL_ACCESS grants all of them and VIEW_ONLY blocks the write flags.
  canViewRecords:           boolean;
  canViewAppointments:      boolean;
  canViewMedications:       boolean;
  canViewLabResults:        boolean;
  canViewImaging:           boolean;
  canMessageCareTeam:       boolean;
  canViewConsultations:     boolean;
  canScheduleAppointments:  boolean;
  canManageCarePlan:        boolean;
  canViewBilling:           boolean;
  canManageBilling:         boolean;
  authorizedBySystemId:     string | null;
  authorizedDate:           string;
  expiresAt:                string | null;
  isActive:                 boolean;
  revokedAt:                string | null;
  // communication permissions: the flags are requests, effective only after the patient approves
  canJoinCalls:        boolean;
  messagingApprovedAt: string | null;
  callsApprovedAt:     string | null;
  messagingAllowed:    boolean;
  joinCallsAllowed:    boolean;
  pendingApproval:     boolean;
  accessStatus:        AccessStatus;
  /** When the caregiver attested to being the parent or legal guardian; null when not attested. */
  guardianAttestedAt:  string | null;
  /** True while the patient's account has no password, so the patient cannot sign in. */
  managed:             boolean;
  /** The caregiver created this managed dependent and may send (or re-send) the sign-in invitation. */
  canInvite:           boolean;
  /** Patient view only; null in the caregiver's own list. */
  caregiverName:       string | null;
  caregiverEmail:      string | null;
  // server-enriched patient fields
  patientName:          string | null;
  /** Null for a managed dependent that was never invited. */
  patientEmail:         string | null;
  /** The account phone, else the contact phone recorded for a managed dependent. */
  patientPhone:         string | null;
  /** Null unless the grant gives access to records. */
  patientHealthStatus:  HealthStatus | null;
  patientDateOfBirth:   string | null;
  patientGender:        string | null;
  patientBloodType:     string | null;
}

// ─── Activity feed ────────────────────────────────────────────────────────────

export interface CaregiverAppointment {
  appointmentId:         number;
  patientSystemId:       string | null;
  patientName:           string | null;
  doctorSystemId:        string | null;
  /** The doctor's display name, resolved by the hook; never the raw system id. */
  doctorName?:           string | null;
  scheduledAt:           string;
  durationMinutes:       number | null;
  type:                  string | null;
  status:                string;
  reasonForVisit:        string | null;
  /** Legacy field, always null. Use callJoinRoute. */
  videoConsultationLink: string | null;
  notes:                 string | null;
  callStatus:            CallStatus | null;
  callJoinRoute:         string | null;
  joinWindowOpensAt:     string | null;
  joinWindowClosesAt:    string | null;
}

export interface CaregiverConsultation {
  consultationId:  number;
  appointmentId:   number | null;
  patientSystemId: string | null;
  patientName:     string | null;
  doctorSystemId:  string | null;
  /** The doctor's display name, resolved by the hook; never the raw system id. */
  doctorName?:     string | null;
  chiefComplaint:  string | null;
  diagnosis:       string | null;
  status:          string | null;
  startedAt:       string | null;
  completedAt:     string | null;
  createdAt:       string | null;
}

/** GET /api/caregiver/dependents/activity with paging: both lists share `page` and `size`. */
export interface CaregiverActivityPage {
  /** The window start that was applied (clinic date). */
  from:          string | null;
  /** The window end that was applied; null = open (all future rows). */
  to:            string | null;
  appointments:  PageResponse<CaregiverAppointment>;
  consultations: PageResponse<CaregiverConsultation>;
}

export interface ActivityQuery {
  /** Clinic date "YYYY-MM-DD", inclusive. Server default: 90 days before today. */
  from?: string;
  /** Clinic date "YYYY-MM-DD", inclusive. Server default: open. */
  to?:   string;
}

// ─── Creating and inviting a dependent ────────────────────────────────────────

/** Body of POST /api/caregiver/dependents. Email and password are sent together or not at all. */
export interface CreateDependentBody extends Partial<DataFlags>, Partial<CommFlags> {
  firstName:          string;
  lastName:           string;
  email?:             string;
  password?:          string;
  phone?:             string;
  guardianAttested:   boolean;
  dateOfBirth?:       string;
  gender?:            Gender;
  bloodType?:         BloodType;
  allergies?:         string[];
  chronicConditions?: string[];
  relationship:       string;
  accessLevel:        AccessLevel;
  /** "YYYY-MM-DD": access ends at the end of that clinic day. Omit for no end date. */
  expiresAt?:         string;
}

/** Body of PATCH /api/caregiver/dependents/{accessId}. Only what is being reduced is sent. */
export interface NarrowAccessBody extends Partial<DataFlags>, Partial<CommFlags> {
  accessLevel?: AccessLevel;
  expiresAt?:   string;
}

// ─── Access requests (asking an existing patient for access) ──────────────────

export type AccessRequestStatus = 'PENDING' | 'APPROVED' | 'DECLINED' | 'CANCELLED' | 'EXPIRED';

/** One request as the caregiver sees it (GET /api/caregiver/access-requests). */
export interface CaregiverAccessRequest extends DataFlags, CommFlags {
  requestId:         number;
  status:            AccessRequestStatus;
  caregiverSystemId: string;
  /** The email the caregiver typed. */
  patientEmail:      string;
  /** Null until the request is approved. */
  patientSystemId:   string | null;
  /** Null until the request is approved. */
  patientName:       string | null;
  relationship:      string;
  /** The level that was asked for; what the patient granted is on the grant itself. */
  accessLevel:       AccessLevel;
  message:           string | null;
  declineReason:     string | null;
  /** The grant created by the approval; set only when approved. */
  accessId:          number | null;
  createdAt:         string;
  requestExpiresAt:  string;
  respondedAt:       string | null;
}

/** Body of POST /api/caregiver/access-requests. */
export interface CreateAccessRequestBody extends Partial<DataFlags>, Partial<CommFlags> {
  patientEmail: string;
  relationship: string;
  accessLevel:  AccessLevel;
  message?:     string;
}

// ─── A dependent's profile ────────────────────────────────────────────────────

export interface DependentEmergencyContact {
  contactId:       number;
  patientSystemId: string;
  name:            string;
  relationship:    string | null;
  phone:           string;
  email:           string | null;
  isPrimary:       boolean;
  isCaregiver:     boolean;
  createdAt:       string;
}

/** GET / PUT /api/profile/user/{systemId} for a linked patient. Health fields are null without records access. */
export interface DependentProfile {
  userId:            number;
  systemId:          string;
  firstName:         string;
  lastName:          string;
  phone:             string | null;
  email:             string | null;
  role:              string;
  isActive:          boolean;
  patientSystemId:   string | null;
  dateOfBirth:       string | null;
  gender:            Gender | null;
  bloodType:         BloodType | null;
  /** A string holding a JSON array; read it with parseList. */
  allergies:         string | null;
  /** A string holding a JSON array; read it with parseList. */
  chronicConditions: string | null;
  healthStatus:      HealthStatus | null;
  emergencyContacts: DependentEmergencyContact[] | null;
  createdAt:         string;
  updatedAt:         string;
}

/** Body of PUT /api/profile/user/{systemId}: only the fields the caregiver changed. Never healthStatus or email. */
export interface UpdateDependentProfileBody {
  firstName?:         string;
  lastName?:          string;
  phone?:             string;
  dateOfBirth?:       string;
  /** '' clears the value ("Not known"). */
  gender?:            Gender | '';
  /** '' clears the value ("Not known"). */
  bloodType?:         BloodType | '';
  /** JSON.stringify(string[]). */
  allergies?:         string;
  /** JSON.stringify(string[]). */
  chronicConditions?: string;
}
