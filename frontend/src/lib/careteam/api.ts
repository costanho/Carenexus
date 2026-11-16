import { fetchPage, MAX_PAGE_SIZE } from '@/lib/api/paged';
import { withIdempotencyKey } from '@/lib/api/idempotency';
import type { PageResponse } from '@/lib/api/types';
import { api } from '@/lib/auth/auth.interceptor';
import {
  OWN_CARE_TEAM,
  type AssignCareTeamMemberBody, type CareTeamFacilityOption, type CareTeamMember, type UpdateCareTeamMemberBody,
} from './types';

// One function per care-team endpoint (contract be-care-team, section 6). Each rejects with the axios error;
// show it with `apiError` / `apiErrorMessage`.

function base(patientSystemId: string): string {
  return `/api/patients/${encodeURIComponent(patientSystemId)}/care-team`;
}

function rows(data: unknown): CareTeamMember[] {
  return Array.isArray(data) ? (data as CareTeamMember[]) : [];
}

/**
 * A patient's care team: assigned members first (primary on top), then doctors known only from appointments.
 * Open to the patient, a related doctor, a caregiver with an active grant and an administrator; 403 otherwise.
 */
export async function getPatientCareTeam(patientSystemId: string): Promise<CareTeamMember[]> {
  const { data } = await api.get<CareTeamMember[]>(base(patientSystemId));
  return rows(data);
}

/** The signed-in patient's own care team. PATIENT only. */
export async function getMyCareTeam(): Promise<CareTeamMember[]> {
  const { data } = await api.get<CareTeamMember[]>(base(OWN_CARE_TEAM));
  return rows(data);
}

/**
 * Adds a member (related doctor or administrator). Pass the form's idempotency key so a retried submit returns
 * the member that was already created instead of answering 409. 409 = the provider is already on the team.
 */
export async function assignCareTeamMember(
  patientSystemId: string,
  body: AssignCareTeamMemberBody,
  idempotencyKey?: string,
): Promise<CareTeamMember> {
  const { data } = await api.post<CareTeamMember>(
    base(patientSystemId), body, idempotencyKey ? withIdempotencyKey(idempotencyKey) : undefined,
  );
  return data;
}

/**
 * Changes a member's role and/or primary flag. A patient may send `isPrimary` only. Making a member primary
 * clears the flag on the previous primary, so reload the list afterwards.
 */
export async function updateCareTeamMember(
  patientSystemId: string,
  careTeamId: number,
  body: UpdateCareTeamMemberBody,
): Promise<CareTeamMember> {
  const { data } = await api.patch<CareTeamMember>(`${base(patientSystemId)}/${careTeamId}`, body);
  return data;
}

/**
 * Removes a member: the patient, the doctor on the row, or an administrator. Repeating it answers 204 again.
 * A removed doctor who still has an appointment with the patient comes back in the list as a DERIVED row.
 */
export async function removeCareTeamMember(patientSystemId: string, careTeamId: number): Promise<void> {
  await api.delete(`${base(patientSystemId)}/${careTeamId}`);
}

/** One page of the signed-in doctor's own assignments, newest first (GET /api/doctors/care-team). DOCTOR only. */
export function getDoctorCareTeamAssignments(page: number, size?: number): Promise<PageResponse<CareTeamMember>> {
  return fetchPage<CareTeamMember>('/api/doctors/care-team', page, size);
}

/**
 * The active facilities a membership can be linked to (GET /api/facilities, first 100 by name). An empty list is
 * a normal state: the form then has no facility field.
 */
export async function getCareTeamFacilityOptions(): Promise<CareTeamFacilityOption[]> {
  const page = await fetchPage<{ facilityId?: unknown; name?: unknown }>('/api/facilities', 0, MAX_PAGE_SIZE);
  const options: CareTeamFacilityOption[] = [];
  for (const row of page.content) {
    const facilityId = typeof row?.facilityId === 'number' ? row.facilityId : NaN;
    const name = typeof row?.name === 'string' ? row.name.trim() : '';
    if (Number.isInteger(facilityId) && facilityId > 0 && name) options.push({ facilityId, name });
  }
  return options;
}
