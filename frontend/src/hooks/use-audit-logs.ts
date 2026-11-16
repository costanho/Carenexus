import { usePaged, type PagedState } from '@/hooks/use-paged';
import { useResource, type ResourceState } from '@/hooks/use-resource';
import { AUDIT_PAGE_SIZE, getAuditEntry, listAuditLogs } from '@/lib/admin/api';
import type { AuditEntry, AuditFilters } from '@/lib/admin/types';

const keyOfAudit = (row: AuditEntry) => row.auditId;

/**
 * The audit search (GET /api/admin/audit-logs), newest first, 50 rows per page. Filters are combined with AND;
 * a change of a filter drops the rows and loads the first page again. Every request is itself written to the
 * audit log, so callers apply filters on an explicit action rather than on every keystroke.
 */
export function useAuditLogs(filters: AuditFilters, options: { pageSize?: number; enabled?: boolean } = {}): PagedState<AuditEntry> {
  const actor = filters.actor?.trim() ?? '';
  const patient = filters.patient?.trim() ?? '';
  const action = filters.action?.trim() ?? '';
  const resourceType = filters.resourceType?.trim() ?? '';
  const status = filters.status ?? '';
  const from = filters.from ?? '';
  const to = filters.to ?? '';
  return usePaged<AuditEntry>(
    (page, size) => listAuditLogs({ actor, patient, action, resourceType, status, from, to }, page, size),
    [actor, patient, action, resourceType, status, from, to],
    {
      pageSize: options.pageSize ?? AUDIT_PAGE_SIZE, enabled: options.enabled, keyOf: keyOfAudit,
      errorMessage: 'The audit log could not be loaded.',
    },
  );
}

/**
 * One audit row by id (GET /api/admin/audit-logs/{auditId}), for a link to a row that is not among the loaded
 * pages. Disabled, or without a usable id, nothing is requested.
 */
export function useAuditEntry(auditId: number | null | undefined, options: { enabled?: boolean } = {}): ResourceState<AuditEntry> {
  const id = typeof auditId === 'number' && Number.isSafeInteger(auditId) && auditId > 0 ? auditId : 0;
  return useResource<AuditEntry>(() => getAuditEntry(id), [id], {
    enabled: id > 0 && (options.enabled ?? true),
    errorMessage: 'The audit entry could not be loaded.',
  });
}
