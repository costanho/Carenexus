import { api } from '@/lib/auth/auth.interceptor';
import { withIdempotencyKey } from '@/lib/api/idempotency';
import { pageParams, toPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import type {
  AccessRequestStatus, ActivityQuery, CaregiverAccessRequest, CaregiverActivityPage, CaregiverAppointment,
  CaregiverConsultation, CaregiverDependent, CreateAccessRequestBody, CreateDependentBody, DependentProfile,
  NarrowAccessBody, UpdateDependentProfileBody,
} from './types';

// The caregiver's API client: one function per endpoint. The caregiver is always the signed-in user (taken from
// the token by the server), so no function takes a caregiver id. Every function rejects with the axios error;
// use apiError / apiErrorMessage (@/lib/api/errors) to show it.

const DEPENDENTS      = '/api/caregiver/dependents';
const ACCESS_REQUESTS = '/api/caregiver/access-requests';

function text(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value : null;
}

/**
 * One grant row with every field present. Fields added to the API over time (billing flags, managed, canInvite,
 * patientPhone, guardianAttestedAt) default to "off" or null when a response does not carry them, so screens
 * never read undefined.
 */
export function toDependent(row: unknown): CaregiverDependent {
  const r = (row && typeof row === 'object' ? row : {}) as Record<string, unknown>;
  return {
    ...(r as unknown as CaregiverDependent),
    canViewRecords:          r.canViewRecords === true,
    canViewAppointments:     r.canViewAppointments === true,
    canViewMedications:      r.canViewMedications === true,
    canViewLabResults:       r.canViewLabResults === true,
    canViewImaging:          r.canViewImaging === true,
    canViewConsultations:    r.canViewConsultations === true,
    canScheduleAppointments: r.canScheduleAppointments === true,
    canManageCarePlan:       r.canManageCarePlan === true,
    canViewBilling:          r.canViewBilling === true,
    canManageBilling:        r.canManageBilling === true,
    canMessageCareTeam:      r.canMessageCareTeam === true,
    canJoinCalls:            r.canJoinCalls === true,
    messagingAllowed:        r.messagingAllowed === true,
    joinCallsAllowed:        r.joinCallsAllowed === true,
    pendingApproval:         r.pendingApproval === true,
    managed:                 r.managed === true,
    canInvite:               r.canInvite === true,
    guardianAttestedAt:      text(r.guardianAttestedAt),
    caregiverName:           text(r.caregiverName),
    caregiverEmail:          text(r.caregiverEmail),
    patientPhone:            text(r.patientPhone),
    patientEmail:            text(r.patientEmail),
  };
}

/** GET /api/caregiver/dependents: the caller's active grants, oldest first. */
export async function fetchDependents(): Promise<CaregiverDependent[]> {
  const { data } = await api.get<unknown>(DEPENDENTS);
  return Array.isArray(data) ? data.map(toDependent) : [];
}

/**
 * GET /api/caregiver/dependents/activity?page=&size=[&from=&to=]: one page of the appointments and consultations
 * of every loved one whose grant shares them. Both lists use the same page number and size.
 */
export async function fetchActivityPage(page: number, size: number, query: ActivityQuery = {}): Promise<CaregiverActivityPage> {
  const paging = pageParams(page, size);
  const params: Record<string, string | number> = { ...paging };
  if (query.from) params.from = query.from;
  if (query.to)   params.to   = query.to;
  const { data } = await api.get<unknown>(`${DEPENDENTS}/activity`, { params });
  const body = (data && typeof data === 'object' ? data : {}) as Record<string, unknown>;
  return {
    from:          text(body.from),
    to:            text(body.to),
    appointments:  toPage<CaregiverAppointment>(body.appointments, paging.page, paging.size),
    consultations: toPage<CaregiverConsultation>(body.consultations, paging.page, paging.size),
  };
}

/**
 * POST /api/caregiver/dependents: creates the patient account, the health profile and this caregiver's grant in
 * one transaction. `idempotencyKey` is the key of the open form: a retry with the same key returns the stored
 * result instead of creating a second account.
 */
export async function createDependent(body: CreateDependentBody, idempotencyKey: string): Promise<CaregiverDependent> {
  const { data } = await api.post<unknown>(DEPENDENTS, body, withIdempotencyKey(idempotencyKey));
  return toDependent(data);
}

/** PATCH /api/caregiver/dependents/{accessId}: the caregiver reduces their own access. Widening is refused (403). */
export async function narrowDependentAccess(accessId: number, body: NarrowAccessBody): Promise<CaregiverDependent> {
  const { data } = await api.patch<unknown>(`${DEPENDENTS}/${accessId}`, body);
  return toDependent(data);
}

/**
 * POST /api/caregiver/dependents/{accessId}/invite: stores the email on a managed dependent's account and sends
 * them a link to set a password. Calling it again re-sends the link and may correct the email.
 */
export async function inviteDependent(accessId: number, email: string): Promise<CaregiverDependent> {
  const { data } = await api.post<unknown>(`${DEPENDENTS}/${accessId}/invite`, { email });
  return toDependent(data);
}

/**
 * POST /api/caregiver/access-requests: asks an existing patient for access. The server answers 202 with the same
 * sentence whether or not the email belongs to a patient; that sentence is returned.
 */
export async function createAccessRequest(body: CreateAccessRequestBody): Promise<string> {
  const { data } = await api.post<{ message?: unknown }>(ACCESS_REQUESTS, body);
  return text(data?.message) ?? '';
}

/** GET /api/caregiver/access-requests: the caller's own requests, newest first. */
export async function fetchAccessRequests(
  page: number,
  size: number,
  status?: AccessRequestStatus,
): Promise<PageResponse<CaregiverAccessRequest>> {
  const paging = pageParams(page, size);
  const { data } = await api.get<unknown>(ACCESS_REQUESTS, { params: status ? { ...paging, status } : paging });
  return toPage<CaregiverAccessRequest>(data, paging.page, paging.size);
}

/** DELETE /api/caregiver/access-requests/{requestId}: cancels the caller's own pending request. */
export async function cancelAccessRequest(requestId: number): Promise<void> {
  await api.delete(`${ACCESS_REQUESTS}/${requestId}`);
}

/** GET /api/profile/user/{systemId} for a linked patient. Health fields come back null without records access. */
export async function fetchDependentProfile(patientSystemId: string): Promise<DependentProfile> {
  const { data } = await api.get<DependentProfile>(`/api/profile/user/${encodeURIComponent(patientSystemId)}`);
  return data;
}

/** PUT /api/profile/user/{systemId}: partial update; returns the profile after the change. */
export async function updateDependentProfile(
  patientSystemId: string,
  body: UpdateDependentProfileBody,
): Promise<DependentProfile> {
  const { data } = await api.put<DependentProfile>(`/api/profile/user/${encodeURIComponent(patientSystemId)}`, body);
  return data;
}
