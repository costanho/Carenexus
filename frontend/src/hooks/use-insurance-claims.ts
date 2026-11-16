import { usePaged, type PagedState } from '@/hooks/use-paged';
import { useResource, type ResourceState } from '@/hooks/use-resource';
import { getClaim, listClaims } from '@/lib/billing/api';
import type { ClaimStatus, InsuranceClaim } from '@/lib/billing/types';

// Insurance claim reads (api/be-billing-claims.md). Doctors have no access to claims: every claim endpoint
// answers 403 to that role, also for invoices they issued, so doctor screens pass `enabled: false`.

export interface UseInsuranceClaimsOptions {
  /** One status or several; empty or absent lists every status. */
  status?:    ClaimStatus | readonly ClaimStatus[] | null;
  /** Only the claims of this invoice (there is no nested /invoices/{id}/claims path). */
  invoiceId?: number | null;
  /** Rows per request, 1 to 100. Default 20. */
  pageSize?:  number;
  enabled?:   boolean;
}

/**
 * The claims of one patient, newest first (`GET /api/billing/claims?patientSystemId=`). The patient passes their
 * own id, a caregiver the loved one's (needs the permission to view billing; 403 otherwise). Nothing is requested
 * until the id is known.
 *
 * Submit claim is offered only when the invoice has no open claim:
 * `useInsuranceClaims(patientSystemId, { invoiceId, status: OPEN_CLAIM_STATUSES, pageSize: 1 }).total === 0`
 * once that list has loaded.
 */
export function useInsuranceClaims(
  patientSystemId: string | null | undefined,
  options: UseInsuranceClaimsOptions = {},
): PagedState<InsuranceClaim> {
  const id = patientSystemId?.trim() || null;
  const statuses: ClaimStatus[] = options.status == null
    ? []
    : typeof options.status === 'string' ? [options.status] : [...options.status];
  const statusKey = statuses.join(',');
  const invoiceId = options.invoiceId ?? null;

  return usePaged<InsuranceClaim>(
    (page, size) => listClaims(id as string, { status: statuses, invoiceId }, page, size),
    [id, statusKey, invoiceId],
    {
      enabled:      (options.enabled ?? true) && id !== null,
      pageSize:     options.pageSize,
      keyOf:        claim => claim.claimId,
      errorMessage: 'We could not load the insurance claims. Please try again.',
    },
  );
}

/** One claim (`GET /api/billing/claims/{claimId}`). Nothing is requested while the id is missing. */
export function useInsuranceClaim(
  claimId: number | null | undefined,
  options: { enabled?: boolean } = {},
): ResourceState<InsuranceClaim> {
  const id = typeof claimId === 'number' && Number.isInteger(claimId) && claimId > 0 ? claimId : null;
  return useResource<InsuranceClaim>(
    () => getClaim(id as number),
    [id],
    { enabled: (options.enabled ?? true) && id !== null, errorMessage: 'We could not load this claim. Please try again.' },
  );
}
