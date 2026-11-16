import { api } from '@/lib/auth/auth.interceptor';
import { withIdempotencyKey } from '@/lib/api/idempotency';
import { fetchPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import type {
  CarePlan, CarePlanGoalStatus, CarePlanListFilter, CarePlanPatientOption, CarePlanStatus,
  CreateCarePlanRequest, GoalProgressRequest, ReviewCarePlanRequest, UpdateCarePlanRequest,
} from './types';

// Typed client for /api/care-plans: one function per endpoint. Every function rejects with the axios error;
// callers turn it into a message with `apiError` (the hooks in use-care-plans.ts do).

const BASE = '/api/care-plans';

/**
 * POST /api/care-plans (treating doctor). `idempotencyKey` is the key of the open form: a retried submit repeats
 * it and the server answers with the plan it already created instead of creating a second one.
 */
export async function createCarePlan(body: CreateCarePlanRequest, idempotencyKey: string): Promise<CarePlan> {
  const { data } = await api.post<CarePlan>(BASE, body, withIdempotencyKey(idempotencyKey));
  return data;
}

/** GET /api/care-plans/{id}: one plan with its goals and interventions. */
export async function getCarePlan(planId: number): Promise<CarePlan> {
  const { data } = await api.get<CarePlan>(`${BASE}/${planId}`);
  return data;
}

/** GET /api/care-plans/patient/{patientSystemId}: all plans of one patient, newest first. */
export function listPatientCarePlans(
  patientSystemId: string,
  page: number,
  size?: number,
  filter: Pick<CarePlanListFilter, 'status'> = {},
): Promise<PageResponse<CarePlan>> {
  return fetchPage<CarePlan>(`${BASE}/patient/${encodeURIComponent(patientSystemId)}`, page, size, {
    status: filter.status ?? undefined,
  });
}

/** GET /api/care-plans/doctor: plans written by the signed-in doctor, newest first. */
export function listDoctorCarePlans(
  page: number,
  size?: number,
  filter: CarePlanListFilter = {},
): Promise<PageResponse<CarePlan>> {
  return fetchPage<CarePlan>(`${BASE}/doctor`, page, size, {
    status:          filter.status ?? undefined,
    patientSystemId: filter.patientSystemId ?? undefined,
  });
}

/** PUT /api/care-plans/{id} (author doctor): replaces the editable content. */
export async function updateCarePlan(planId: number, body: UpdateCarePlanRequest): Promise<CarePlan> {
  const { data } = await api.put<CarePlan>(`${BASE}/${planId}`, body);
  return data;
}

/** PATCH /api/care-plans/{id}/status (author doctor). Sending the current status is a no-op. */
export async function setCarePlanStatus(planId: number, status: CarePlanStatus): Promise<CarePlan> {
  const { data } = await api.patch<CarePlan>(`${BASE}/${planId}/status`, { status });
  return data;
}

/** PATCH /api/care-plans/{id}/review (author doctor): records a review now and sets the next review date. */
export async function reviewCarePlan(planId: number, body: ReviewCarePlanRequest): Promise<CarePlan> {
  const { data } = await api.patch<CarePlan>(`${BASE}/${planId}/review`, body);
  return data;
}

/**
 * PATCH /api/care-plans/{id}/goals/{goalId} (the patient, a caregiver allowed to manage the care plan, or the
 * author doctor). Returns the whole plan with the goal changed.
 */
export async function updateGoalProgress(planId: number, goalId: number, body: GoalProgressRequest): Promise<CarePlan> {
  const payload: { status?: CarePlanGoalStatus; progressNote?: string } = {};
  if (body.status !== undefined) payload.status = body.status;
  if (body.progressNote !== undefined) payload.progressNote = body.progressNote;
  const { data } = await api.patch<CarePlan>(`${BASE}/${planId}/goals/${goalId}`, payload);
  return data;
}

/**
 * GET /api/doctors/patients/options: id and name of the signed-in doctor's patients (at most 500, ordered by
 * name). Care-plan rows carry only the patient's system id; the doctor screen reads names from here.
 */
export async function listCarePlanPatientOptions(): Promise<CarePlanPatientOption[]> {
  const { data } = await api.get<unknown>('/api/doctors/patients/options');
  if (!Array.isArray(data)) return [];
  const out: CarePlanPatientOption[] = [];
  for (const row of data as Partial<CarePlanPatientOption>[]) {
    const id = typeof row?.patientSystemId === 'string' ? row.patientSystemId.trim() : '';
    if (!id) continue;
    const firstName = typeof row.firstName === 'string' ? row.firstName.trim() : '';
    const lastName  = typeof row.lastName === 'string' ? row.lastName.trim() : '';
    const fullName  = (typeof row.fullName === 'string' ? row.fullName.trim() : '') || `${firstName} ${lastName}`.trim();
    out.push({ patientSystemId: id, firstName, lastName, fullName });
  }
  return out;
}
