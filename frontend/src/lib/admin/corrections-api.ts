import { z } from 'zod';
import { fetchPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import { api } from '@/lib/auth/auth.interceptor';

// Correction requests as an administrator sees them (be-corrections): every request, assignment to one of the
// patient's related doctors, and resolution. Types repeat the contract's `CorrectionRequestResponse`.

export type CorrectionResourceType = 'MEDICAL_RECORD' | 'LAB_RESULT' | 'IMAGING_RESULT' | 'PRESCRIPTION' | 'CONSULTATION';
export type CorrectionStatus = 'OPEN' | 'ACCEPTED' | 'REJECTED';
export type CorrectionDecision = 'ACCEPTED' | 'REJECTED';

export interface CorrectionRequest {
  requestId: number; patientSystemId: string; patientName: string;
  requestedBySystemId: string; requestedByName: string; requestedByRole: 'PATIENT' | 'CAREGIVER';
  resourceType: CorrectionResourceType; resourceId: number; requestedText: string; status: CorrectionStatus;
  assignedDoctorSystemId: string | null; assignedDoctorName: string | null;
  resolverSystemId: string | null; resolverName: string | null; resolutionNote: string | null;
  createdAt: string; updatedAt: string; resolvedAt: string | null;
}

export interface AssignableDoctor {
  doctorSystemId: string; doctorName: string; specialization: string | null; currentAssignee: boolean;
}

export interface AdminCorrectionFilter {
  status?: CorrectionStatus;
  /** true = only requests with no assigned doctor. */
  unassigned?: boolean;
}

export const CORRECTION_STATUSES: readonly CorrectionStatus[] = ['OPEN', 'ACCEPTED', 'REJECTED'];

export const CORRECTION_STATUS_LABEL: Record<CorrectionStatus, string> = {
  OPEN:     'Open',
  ACCEPTED: 'Accepted',
  REJECTED: 'Rejected',
};

export const CORRECTION_RESOURCE_LABEL: Record<CorrectionResourceType, string> = {
  MEDICAL_RECORD: 'Medical record',
  LAB_RESULT:     'Lab result',
  IMAGING_RESULT: 'Imaging result',
  PRESCRIPTION:   'Prescription',
  CONSULTATION:   'Consultation',
};

export function isCorrectionStatus(value: string | undefined): value is CorrectionStatus {
  return !!value && (CORRECTION_STATUSES as readonly string[]).includes(value);
}

/**
 * "Lab result #318". The request carries no clinical content of the record (no title or value), so a row is
 * named from the kind of record and its id.
 */
export function correctionRecordLabel(request: Pick<CorrectionRequest, 'resourceType' | 'resourceId'>): string {
  const kind = CORRECTION_RESOURCE_LABEL[request.resourceType] ?? 'Record';
  return `${kind} #${request.resourceId}`;
}

/** Only an open request can be assigned or resolved; ACCEPTED and REJECTED are final. */
export function isCorrectionOpen(request: Pick<CorrectionRequest, 'status'>): boolean {
  return request.status === 'OPEN';
}

export function isCorrectionDecision(value: string | null | undefined): value is CorrectionDecision {
  return value === 'ACCEPTED' || value === 'REJECTED';
}

/**
 * Resolve body: the note is required for both decisions, 1 to 1000 characters. `decision` is a plain string in
 * the form (empty until a choice is made) and is narrowed with `isCorrectionDecision` before it is sent.
 */
export const resolveCorrectionSchema = z.object({
  decision: z.string().refine((value): boolean => isCorrectionDecision(value), 'Choose a decision'),
  note: z.string().trim()
    .min(1, 'Enter a note. The person who asked sees it.')
    .max(1000, 'The note must be at most 1000 characters'),
});

export interface ResolveCorrectionValues { decision: string; note: string }

// ─── Calls ────────────────────────────────────────────────────────────────────

/** GET /api/admin/correction-requests: every correction request, newest first. */
export function listAdminCorrections(filter: AdminCorrectionFilter, page: number, size?: number): Promise<PageResponse<CorrectionRequest>> {
  return fetchPage<CorrectionRequest>('/api/admin/correction-requests', page, size, {
    status:     filter.status,
    unassigned: filter.unassigned ? true : undefined,
  });
}

/** GET /api/correction-requests/{id}: one request (notification deep link; an administrator may read any). */
export async function getCorrection(requestId: number): Promise<CorrectionRequest> {
  const { data } = await api.get<CorrectionRequest>(`/api/correction-requests/${requestId}`);
  return data;
}

/** GET /api/admin/correction-requests/{id}/assignable-doctors: the patient's active related doctors. */
export async function listAssignableDoctors(requestId: number): Promise<AssignableDoctor[]> {
  const { data } = await api.get<AssignableDoctor[]>(`/api/admin/correction-requests/${requestId}/assignable-doctors`);
  return Array.isArray(data) ? data : [];
}

/** PATCH /api/admin/correction-requests/{id}/assign: assigns or reassigns an open request. */
export async function assignCorrection(requestId: number, doctorSystemId: string): Promise<CorrectionRequest> {
  const { data } = await api.patch<CorrectionRequest>(`/api/admin/correction-requests/${requestId}/assign`, { doctorSystemId });
  return data;
}

/** POST /api/correction-requests/{id}/resolve: records the decision; it does not change the record itself. */
export async function resolveCorrection(requestId: number, decision: CorrectionDecision, note: string): Promise<CorrectionRequest> {
  const { data } = await api.post<CorrectionRequest>(`/api/correction-requests/${requestId}/resolve`, { decision, note });
  return data;
}

/** Number of open requests with no assigned doctor (for the admin home overview). */
export async function countUnassignedCorrections(): Promise<number> {
  const page = await listAdminCorrections({ status: 'OPEN', unassigned: true }, 0, 1);
  return page.totalElements;
}
