import { usePaged, type PagedState } from '@/hooks/use-paged';
import { useResource, type ResourceState } from '@/hooks/use-resource';
import { listDoctorInvoices, listPatientInvoices, listUninvoicedConsultations } from '@/lib/billing/api';
import { type AmountDueSummary, loadAmountDue } from '@/lib/billing/summary';
import type { Invoice, InvoiceStatus, UninvoicedConsultation } from '@/lib/billing/types';

// Invoice lists (api/be-billing-invoices.md). Lists are paged ("Load more"); render the state with `PagedList`.

export interface UseInvoicesOptions {
  /**
   * `patient` (default): the invoices of one patient, `GET /api/billing/invoices?patientSystemId=`. Used by the
   * patient (own id) and by a caregiver (the loved one's id); nothing is requested until the id is known.
   * `doctor`: the invoices the calling doctor issued, drafts included, `GET /api/doctors/billing/invoices`;
   * `patientSystemId` is then an optional filter.
   */
  scope?:           'patient' | 'doctor';
  patientSystemId?: string | null;
  /** One status or several; empty or absent lists every status the caller may see. */
  status?:          InvoiceStatus | readonly InvoiceStatus[] | null;
  /** Exact match on the invoice's consultation. */
  consultationId?:  number | null;
  /** Exact match on the invoice's appointment. */
  appointmentId?:   number | null;
  /** Rows per request, 1 to 100. Default 20. */
  pageSize?:        number;
  enabled?:         boolean;
}

function statusList(status: UseInvoicesOptions['status']): InvoiceStatus[] {
  if (status == null) return [];
  return typeof status === 'string' ? [status] : [...status];
}

/**
 * ```tsx
 * const invoices = useInvoices({ patientSystemId, status: ['SENT', 'PARTIALLY_PAID', 'OVERDUE'] });
 * <PagedList state={invoices} keyOf={i => i.invoiceId} renderItem={...} empty={{ icon: 'receipt-outline', title: 'No invoices' }} />
 * ```
 * A 403 (`errorStatus === 403`) on a caregiver screen means the grant no longer allows billing: refetch the
 * dependents and show the no-access state.
 */
export function useInvoices(options: UseInvoicesOptions = {}): PagedState<Invoice> {
  const scope = options.scope ?? 'patient';
  const patientSystemId = options.patientSystemId?.trim() || null;
  const statuses = statusList(options.status);
  const statusKey = statuses.join(',');
  const consultationId = options.consultationId ?? null;
  const appointmentId = options.appointmentId ?? null;
  const enabled = (options.enabled ?? true) && (scope === 'doctor' || patientSystemId !== null);

  return usePaged<Invoice>(
    (page, size) => {
      const filters = { status: statuses, consultationId, appointmentId };
      return scope === 'doctor'
        ? listDoctorInvoices({ ...filters, patientSystemId }, page, size)
        : listPatientInvoices(patientSystemId as string, filters, page, size);
    },
    [scope, patientSystemId, statusKey, consultationId, appointmentId],
    {
      enabled,
      pageSize:     options.pageSize,
      keyOf:        invoice => invoice.invoiceId,
      errorMessage: 'We could not load the invoices. Please try again.',
    },
  );
}

/** The calling doctor's completed consultations that have no invoice yet (`GET /api/doctors/billing/uninvoiced`). */
export function useUninvoicedConsultations(options: { pageSize?: number; enabled?: boolean } = {}): PagedState<UninvoicedConsultation> {
  return usePaged<UninvoicedConsultation>(
    (page, size) => listUninvoicedConsultations(page, size),
    [],
    {
      enabled:      options.enabled,
      pageSize:     options.pageSize,
      keyOf:        row => row.consultationId,
      errorMessage: 'We could not load the consultations waiting for an invoice. Please try again.',
    },
  );
}

/**
 * What a patient still owes: outstanding and overdue totals computed from their open invoices (there is no
 * summary endpoint). Nothing is requested until the id is known.
 */
export function useAmountDue(
  patientSystemId: string | null | undefined,
  options: { enabled?: boolean } = {},
): ResourceState<AmountDueSummary> {
  const id = patientSystemId?.trim() || null;
  return useResource<AmountDueSummary>(
    () => loadAmountDue(id as string),
    [id],
    { enabled: (options.enabled ?? true) && id !== null, errorMessage: 'We could not load the amount due. Please try again.' },
  );
}
