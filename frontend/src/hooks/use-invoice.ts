import { useResource, type ResourceState } from '@/hooks/use-resource';
import { getInvoice } from '@/lib/billing/api';
import type { Invoice } from '@/lib/billing/types';

/**
 * One invoice (`GET /api/billing/invoices/{id}`). Nothing is requested while the id is missing.
 *
 * `errorStatus` tells the two refusals apart: 404 means the invoice does not exist or is not visible to the
 * caller (a draft, for a patient or caregiver): show "This invoice is not available". 403 on a caregiver screen
 * means the grant changed: refetch the dependents and show the no-access state.
 */
export function useInvoice(
  invoiceId: number | null | undefined,
  options: { enabled?: boolean } = {},
): ResourceState<Invoice> {
  const id = typeof invoiceId === 'number' && Number.isInteger(invoiceId) && invoiceId > 0 ? invoiceId : null;
  return useResource<Invoice>(
    () => getInvoice(id as number),
    [id],
    { enabled: (options.enabled ?? true) && id !== null, errorMessage: 'We could not load this invoice. Please try again.' },
  );
}
