import { OPEN_INVOICE_STATUSES } from './format';
import type { BillingViewer, InvoiceStatus } from './types';

// The status filters of the invoice list. The key is what goes into the URL (`?section=billing&status=unpaid`),
// so a refresh and the Back button keep the filter.

export type InvoiceFilterKey = 'all' | 'draft' | 'unpaid' | 'overdue' | 'paid' | 'cancelled' | 'refunded';

export interface InvoiceFilter {
  key:      InvoiceFilterKey;
  label:    string;
  /** The statuses sent to the API; empty lists every status the caller may see. */
  statuses: readonly InvoiceStatus[];
  /** Only for people who can see drafts (the issuing doctor, an administrator). */
  staffOnly?: boolean;
}

export const INVOICE_FILTERS: readonly InvoiceFilter[] = [
  { key: 'all',       label: 'All',       statuses: [] },
  { key: 'draft',     label: 'Draft',     statuses: ['DRAFT'], staffOnly: true },
  // Sent, partially paid and overdue invoices are all still open: they differ only in how late they are.
  { key: 'unpaid',    label: 'Unpaid',    statuses: OPEN_INVOICE_STATUSES },
  { key: 'overdue',   label: 'Overdue',   statuses: ['OVERDUE'] },
  { key: 'paid',      label: 'Paid',      statuses: ['PAID'] },
  { key: 'cancelled', label: 'Cancelled', statuses: ['CANCELLED'] },
  { key: 'refunded',  label: 'Refunded',  statuses: ['REFUNDED'] },
];

export const DEFAULT_INVOICE_FILTER: InvoiceFilterKey = 'all';

/** True for viewers who can see draft invoices. Patients and caregivers never receive one. */
export function seesDraftInvoices(viewer: BillingViewer): boolean {
  return viewer === 'doctor' || viewer === 'admin';
}

/** The filters offered to a viewer, in display order. */
export function invoiceFiltersFor(viewer: BillingViewer): InvoiceFilter[] {
  const staff = seesDraftInvoices(viewer);
  return INVOICE_FILTERS.filter(f => staff || !f.staffOnly);
}

/** The filter a URL value stands for; anything unknown (or not offered to this viewer) is "all". */
export function parseInvoiceFilter(value: string | null | undefined, viewer: BillingViewer = 'patient'): InvoiceFilterKey {
  const key = (value ?? '').trim().toLowerCase();
  return invoiceFiltersFor(viewer).find(f => f.key === key)?.key ?? DEFAULT_INVOICE_FILTER;
}

/** The statuses a filter key sends to the API. */
export function invoiceFilterStatuses(key: InvoiceFilterKey): readonly InvoiceStatus[] {
  return INVOICE_FILTERS.find(f => f.key === key)?.statuses ?? [];
}
