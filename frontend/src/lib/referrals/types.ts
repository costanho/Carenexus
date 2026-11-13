// Referral DTOs, straight from the API (camelCase): /api/referrals, contract `api/be-referrals.md`.

export type ReferralStatus = 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'COMPLETED' | 'CANCELLED';
export type ReferralPriority = 'ROUTINE' | 'URGENT' | 'EMERGENCY';
/** Derived by the server: a doctor on the platform, a specialist outside it, or nobody yet. */
export type ReferralSpecialistType = 'PLATFORM' | 'EXTERNAL' | 'UNASSIGNED';
/** What the signed-in caller may do with a referral now; computed by the server (`allowedActions`). */
export type ReferralAction = 'ACCEPT' | 'DECLINE' | 'COMPLETE' | 'CANCEL' | 'ASSIGN_SPECIALIST';

export const REFERRAL_STATUSES: readonly ReferralStatus[] = ['PENDING', 'ACCEPTED', 'DECLINED', 'COMPLETED', 'CANCELLED'];
export const REFERRAL_PRIORITIES: readonly ReferralPriority[] = ['ROUTINE', 'URGENT', 'EMERGENCY'];

/** `ReferralResponse`. Every field is always present; a nullable field is null, never missing. */
export interface Referral {
  referralId: number;
  patientSystemId: string;
  patientName: string;
  referringDoctorSystemId: string;
  referringDoctorName: string;
  specialistType: ReferralSpecialistType;
  specialistSystemId: string | null;
  specialistName: string | null;
  specialistSpecialization: string | null;
  externalSpecialistName: string | null;
  externalSpecialistOrganisation: string | null;
  externalSpecialistContact: string | null;
  reason: string;
  clinicalSummary: string | null;
  priority: ReferralPriority;
  status: ReferralStatus;
  consultationId: number | null;
  /** Clinic calendar day the referral was issued, YYYY-MM-DD. */
  referralDate: string;
  declineReason: string | null;
  outcomeNote: string | null;
  /** Who recorded the latest outcome on the specialist's behalf; null when the named specialist acted. */
  outcomeRecordedBySystemId: string | null;
  outcomeRecordedByName: string | null;
  /** Clinic wall clock (no offset). */
  respondedAt: string | null;
  completedAt: string | null;
  createdAt: string;
  updatedAt: string;
  allowedActions: ReferralAction[];
  /** True when the caller would act on the specialist's behalf: a note is then mandatory on accept and complete. */
  outcomeNoteRequired: boolean;
}

export interface CreateReferralBody {
  patientSystemId: string;
  reason: string;
  clinicalSummary?: string | null;
  priority?: ReferralPriority;
  specialistSystemId?: string | null;
  externalSpecialistName?: string | null;
  externalSpecialistOrganisation?: string | null;
  externalSpecialistContact?: string | null;
  consultationId?: number | null;
}

export type AssignSpecialistBody =
  | { specialistSystemId: string }
  | { externalSpecialistName: string; externalSpecialistOrganisation?: string | null; externalSpecialistContact?: string | null };

export interface ReferralOutcomeBody { note?: string | null }
export interface DeclineReferralBody { reason: string }

/** Filters of the two doctor lists. An empty value means "no filter". */
export interface DoctorReferralFilters {
  status?: ReferralStatus | '' | null;
  patientSystemId?: string | null;
}

/** Field limits of the request bodies (the backend DTO constraints). */
export const REFERRAL_LIMITS = {
  reason: 2000,
  clinicalSummary: 5000,
  externalSpecialist: 150,
  declineReason: 500,
  outcomeNote: 1000,
} as const;

export function isReferralStatus(value: unknown): value is ReferralStatus {
  return typeof value === 'string' && (REFERRAL_STATUSES as readonly string[]).includes(value);
}

export function isReferralPriority(value: unknown): value is ReferralPriority {
  return typeof value === 'string' && (REFERRAL_PRIORITIES as readonly string[]).includes(value);
}
