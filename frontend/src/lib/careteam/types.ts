// Care-team DTOs, straight from the API (contract be-care-team, sections 3 and 4). camelCase, no view models.

/** The six care-team roles. Stored and returned in upper case. */
export type CareTeamRole = 'DOCTOR' | 'NURSE' | 'LAB' | 'PHARMACY' | 'SPECIALIST' | 'THERAPIST';

/**
 * ASSIGNED: a stored membership with a `careTeamId`; it can be changed and removed.
 * DERIVED: a doctor who is related to the patient only through an appointment or a consultation; read-only.
 */
export type CareTeamSource = 'ASSIGNED' | 'DERIVED';

/** `CareTeamMemberResponse`: the only response object of the care-team endpoints. Every returned row is active. */
export interface CareTeamMember {
  /** Row id; null for DERIVED rows. */
  careTeamId:           number | null;
  patientSystemId:      string;
  patientName:          string;
  source:               CareTeamSource;
  /** The doctor's DOC-... id; null for an external provider (someone without a CareNexus account). */
  providerSystemId:     string | null;
  /** First and last name without a title for a doctor account; the stored name for an external provider. */
  providerName:         string;
  /** External providers only. */
  providerPhone:        string | null;
  /** External providers only. */
  providerOrganisation: string | null;
  role:                 CareTeamRole;
  /** With legacy rows more than one member can read as primary: show a badge on each. */
  isPrimary:            boolean;
  specialization:       string | null;
  facilityId:           number | null;
  facilityName:         string | null;
  /** Clinic wall-clock date-time without an offset ("2026-11-10T09:15:30"); null for DERIVED rows. */
  assignedAt:           string | null;
}

/**
 * Body of POST /api/patients/{patientSystemId}/care-team. Exactly one of `providerSystemId` (a doctor account)
 * and `providerName` (an external provider) is sent. Optional fields are null or left out, never ''.
 */
export interface AssignCareTeamMemberBody {
  providerSystemId?:     string | null;
  providerName?:         string | null;
  providerPhone?:        string | null;
  providerOrganisation?: string | null;
  role:                  CareTeamRole;
  isPrimary?:            boolean;
  facilityId?:           number | null;
}

/** Body of PATCH .../care-team/{careTeamId}. At least one field is sent; null or absent means unchanged. */
export interface UpdateCareTeamMemberBody {
  role?:      CareTeamRole | null;
  isPrimary?: boolean | null;
}

/** An active facility offered when assigning a member (id and name of GET /api/facilities rows). */
export interface CareTeamFacilityOption {
  facilityId: number;
  name:       string;
}

/** All roles, in the order the pickers list them. */
export const CARE_TEAM_ROLES: readonly CareTeamRole[] = ['DOCTOR', 'SPECIALIST', 'NURSE', 'THERAPIST', 'LAB', 'PHARMACY'];

/** The only roles a doctor with a CareNexus account can hold (contract D4); the server refuses the others. */
export const DOCTOR_ACCOUNT_ROLES: readonly CareTeamRole[] = ['DOCTOR', 'SPECIALIST'];

/**
 * Pass as the patient id to read the signed-in patient's own care team (GET /api/patients/me/care-team).
 * Writes always use the real PAT-... id carried by the rows.
 */
export const OWN_CARE_TEAM = 'me';

/** Column lengths of V42, mirrored by the add-member form. */
export const CARE_TEAM_LIMITS = {
  providerName:         150,
  providerPhone:        20,
  providerOrganisation: 200,
} as const;
