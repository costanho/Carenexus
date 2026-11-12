import { apiErrorStatus } from '@/lib/api/errors';
import { withIdempotencyKey } from '@/lib/api/idempotency';
import { fetchPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import { api } from '@/lib/auth/auth.interceptor';
import type {
  AccountRequestStatus, AccountRequestType, AdminAccountRequest, Facility, FacilityInput, LegalCurrent,
  LegalDocType, LegalDocument, OutboundMessage, OutboundMessageFilter, PublishLegalDocumentInput, SystemSetting,
} from './ops-types';

// API client of the admin operations screens. One function per endpoint; every call goes through the shared
// axios instance, so the base URL and the access token come from one place.

// ─── Facilities (be-facilities) ───────────────────────────────────────────────

export interface FacilityListFilter {
  /** true = only active, false = only inactive, undefined = both. */
  active?: boolean;
  /** Case-insensitive match on name or address, at most 100 characters. */
  query?: string;
}

/** GET /api/admin/facilities: facilities in any state, sorted by name. */
export function listAdminFacilities(filter: FacilityListFilter, page: number, size?: number): Promise<PageResponse<Facility>> {
  return fetchPage<Facility>('/api/admin/facilities', page, size, {
    active: filter.active,
    query:  filter.query?.trim() || undefined,
  });
}

/** POST /api/admin/facilities: 201, or 200 with the stored facility when the key is replayed. */
export async function createFacility(input: FacilityInput, idempotencyKey: string): Promise<Facility> {
  const { data } = await api.post<Facility>('/api/admin/facilities', input, withIdempotencyKey(idempotencyKey));
  return data;
}

/** PUT /api/admin/facilities/{id}: full replacement of the five detail fields. */
export async function updateFacility(facilityId: number, input: FacilityInput): Promise<Facility> {
  const { data } = await api.put<Facility>(`/api/admin/facilities/${facilityId}`, input);
  return data;
}

/** PATCH /api/admin/facilities/{id}/activate|deactivate: answers the facility after the change. */
export async function setFacilityActive(facilityId: number, active: boolean): Promise<Facility> {
  const { data } = await api.patch<Facility>(`/api/admin/facilities/${facilityId}/${active ? 'activate' : 'deactivate'}`);
  return data;
}

// ─── System settings (be-system-settings) ─────────────────────────────────────

/** GET /api/admin/settings: every setting with its effective value, default and limits. */
export async function listSystemSettings(): Promise<SystemSetting[]> {
  const { data } = await api.get<SystemSetting[]>('/api/admin/settings');
  return Array.isArray(data) ? data : [];
}

/** PUT /api/admin/settings/{key}: the value always travels as a string, whatever the setting's type. */
export async function updateSystemSetting(key: string, value: string): Promise<SystemSetting> {
  const { data } = await api.put<SystemSetting>(`/api/admin/settings/${encodeURIComponent(key)}`, { value });
  return data;
}

/** DELETE /api/admin/settings/{key}: removes the override (204, no body). */
export async function restoreSystemSettingDefault(key: string): Promise<void> {
  await api.delete(`/api/admin/settings/${encodeURIComponent(key)}`);
}

// ─── Account requests (be-account-data-rights) ────────────────────────────────

export interface AccountRequestFilter {
  type?: AccountRequestType;
  status?: AccountRequestStatus;
}

/** GET /api/admin/account-requests: exports, closures and deletion requests of all users, newest first. */
export function listAccountRequests(filter: AccountRequestFilter, page: number, size?: number): Promise<PageResponse<AdminAccountRequest>> {
  return fetchPage<AdminAccountRequest>('/api/admin/account-requests', page, size, {
    type:   filter.type,
    status: filter.status,
  });
}

/** GET /api/admin/account-requests/{id}: one request (notification deep link). */
export async function getAccountRequest(requestId: number): Promise<AdminAccountRequest> {
  const { data } = await api.get<AdminAccountRequest>(`/api/admin/account-requests/${requestId}`);
  return data;
}

/** POST /api/admin/account-requests/{id}/approve: anonymises the account; answers the request as COMPLETED. */
export async function approveAccountRequest(requestId: number): Promise<AdminAccountRequest> {
  const { data } = await api.post<AdminAccountRequest>(`/api/admin/account-requests/${requestId}/approve`);
  return data;
}

/** POST /api/admin/account-requests/{id}/reject: the reason is shown to the user. */
export async function rejectAccountRequest(requestId: number, reason: string): Promise<AdminAccountRequest> {
  const { data } = await api.post<AdminAccountRequest>(`/api/admin/account-requests/${requestId}/reject`, { reason });
  return data;
}

/** Number of deletion requests waiting for a decision (for the admin home overview). */
export async function countPendingDeletionRequests(): Promise<number> {
  const page = await listAccountRequests({ type: 'DELETION', status: 'REQUESTED' }, 0, 1);
  return page.totalElements;
}

// ─── Legal documents (be-account-legal) ───────────────────────────────────────

/** GET /api/legal/current: the version in force of each document, without the text. */
export async function getCurrentLegal(): Promise<LegalCurrent> {
  const { data } = await api.get<LegalCurrent>('/api/legal/current');
  return data;
}

/** GET /api/legal/documents/{docType}: the text in force of one document. */
export async function getLegalDocument(docType: LegalDocType): Promise<LegalDocument> {
  const { data } = await api.get<LegalDocument>(`/api/legal/documents/${docType.toLowerCase()}`);
  return data;
}

/** POST /api/admin/legal/documents: the new version is in force at once (201, or 200 on a replayed key). */
export async function publishLegalDocument(input: PublishLegalDocumentInput, idempotencyKey: string): Promise<LegalDocument> {
  const { data } = await api.post<LegalDocument>('/api/admin/legal/documents', input, withIdempotencyKey(idempotencyKey));
  return data;
}

// ─── Development outbox (be-foundation-delivery-storage) ──────────────────────

const OUTBOX_URL = '/api/admin/dev/outbound-messages';

/** GET /api/admin/dev/outbound-messages: queued messages, newest first. 404 outside development profiles. */
export function listOutboundMessages(filter: OutboundMessageFilter, page: number, size?: number): Promise<PageResponse<OutboundMessage>> {
  return fetchPage<OutboundMessage>(OUTBOX_URL, page, size, {
    channel:  filter.channel,
    to:       filter.to?.trim() || undefined,
    template: filter.template?.trim() || undefined,
  });
}

let outboxProbe: Promise<boolean> | null = null;

/**
 * Whether this installation has the development outbox: true when the endpoint answers, false when it answers
 * 404 (not a development installation). Any other failure rejects and is not remembered, so it can be retried.
 * The answer cannot change while the server runs, so it is asked once per page load.
 */
export function probeOutboxAvailable(): Promise<boolean> {
  if (!outboxProbe) {
    const attempt = api.get(OUTBOX_URL, { params: { page: 0, size: 1 } })
      .then(() => true)
      .catch(e => {
        if (apiErrorStatus(e) === 404) return false;
        throw e;
      });
    outboxProbe = attempt;
    attempt.catch(() => { if (outboxProbe === attempt) outboxProbe = null; });
  }
  return outboxProbe;
}
