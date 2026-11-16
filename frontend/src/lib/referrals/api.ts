import { withIdempotencyKey } from '@/lib/api/idempotency';
import { fetchPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import { api } from '@/lib/auth/auth.interceptor';
import type {
  AssignSpecialistBody, CreateReferralBody, DeclineReferralBody, DoctorReferralFilters, Referral, ReferralOutcomeBody,
} from './types';

// Typed client for /api/referrals (contract `api/be-referrals.md`): one function per endpoint.
// Every function rejects with the axios error; show it with `apiError` / `apiErrorMessage`.

const BASE = '/api/referrals';

function path(referralId: number, action?: string): string {
  return `${BASE}/${encodeURIComponent(String(referralId))}${action ? `/${action}` : ''}`;
}

/**
 * POST /api/referrals. `idempotencyKey` is the key of the open form: a retried submit repeats it and the server
 * answers with the referral it stored the first time instead of creating a second one.
 */
export async function createReferral(body: CreateReferralBody, idempotencyKey: string): Promise<Referral> {
  const { data } = await api.post<Referral>(BASE, body, withIdempotencyKey(idempotencyKey));
  return data;
}

/** GET /api/referrals/{id}. 403 when the caller may not see it, 404 when it does not exist. */
export async function getReferral(referralId: number): Promise<Referral> {
  const { data } = await api.get<Referral>(path(referralId));
  return data;
}

function doctorFilters(filters: DoctorReferralFilters | undefined) {
  return { status: filters?.status || undefined, patientSystemId: filters?.patientSystemId || undefined };
}

/** GET /api/referrals/doctor/outgoing: referrals the signed-in doctor issued, newest first. */
export function listOutgoingReferrals(
  filters: DoctorReferralFilters | undefined, page: number, size?: number,
): Promise<PageResponse<Referral>> {
  return fetchPage<Referral>(`${BASE}/doctor/outgoing`, page, size, doctorFilters(filters));
}

/** GET /api/referrals/doctor/incoming: referrals addressed to the signed-in doctor as specialist, every status. */
export function listIncomingReferrals(
  filters: DoctorReferralFilters | undefined, page: number, size?: number,
): Promise<PageResponse<Referral>> {
  return fetchPage<Referral>(`${BASE}/doctor/incoming`, page, size, doctorFilters(filters));
}

/** GET /api/referrals/patient/{patientSystemId}: a patient's referrals, every status, newest first. */
export function listPatientReferrals(patientSystemId: string, page: number, size?: number): Promise<PageResponse<Referral>> {
  return fetchPage<Referral>(`${BASE}/patient/${encodeURIComponent(patientSystemId)}`, page, size);
}

/** PATCH /api/referrals/{id}/specialist: assign or replace the specialist while the referral is pending. */
export async function assignReferralSpecialist(referralId: number, body: AssignSpecialistBody): Promise<Referral> {
  const { data } = await api.patch<Referral>(path(referralId, 'specialist'), body);
  return data;
}

/** PATCH /api/referrals/{id}/accept. `note` is required when the caller acts on the specialist's behalf. */
export async function acceptReferral(referralId: number, body: ReferralOutcomeBody = {}): Promise<Referral> {
  const { data } = await api.patch<Referral>(path(referralId, 'accept'), body);
  return data;
}

/** PATCH /api/referrals/{id}/decline. The reason is required for every caller. */
export async function declineReferral(referralId: number, body: DeclineReferralBody): Promise<Referral> {
  const { data } = await api.patch<Referral>(path(referralId, 'decline'), body);
  return data;
}

/** PATCH /api/referrals/{id}/complete. `note` is required when the caller acts on the specialist's behalf. */
export async function completeReferral(referralId: number, body: ReferralOutcomeBody = {}): Promise<Referral> {
  const { data } = await api.patch<Referral>(path(referralId, 'complete'), body);
  return data;
}

/** PATCH /api/referrals/{id}/cancel: the referring doctor withdraws a pending referral. No body. */
export async function cancelReferral(referralId: number): Promise<Referral> {
  const { data } = await api.patch<Referral>(path(referralId, 'cancel'));
  return data;
}

/**
 * The display name of one of the signed-in doctor's patients, from GET /api/doctors/patients/options
 * (`query` matches the system id). Used for the patient filter chip when no loaded referral carries the name.
 * Null when the patient is not in the doctor's own list or the lookup fails: the chip then shows a neutral label.
 */
export async function lookupDoctorPatientName(patientSystemId: string): Promise<string | null> {
  try {
    const { data } = await api.get<unknown>('/api/doctors/patients/options', { params: { query: patientSystemId } });
    if (!Array.isArray(data)) return null;
    const row = (data as { patientSystemId?: unknown; fullName?: unknown }[]).find(p => p?.patientSystemId === patientSystemId);
    const name = typeof row?.fullName === 'string' ? row.fullName.trim() : '';
    return name || null;
  } catch {
    return null;
  }
}
