import { MAX_PAGE_SIZE } from '@/lib/api/paged';
import { listPatientInvoices } from './api';
import { OPEN_INVOICE_STATUSES } from './format';
import type { Invoice } from './types';

// The amount a patient still owes. There is no summary endpoint: the totals are computed from the patient's open
// invoices (GET /api/billing/invoices?patientSystemId=&status=SENT,PARTIALLY_PAID,OVERDUE&size=100) by summing
// `outstanding`; overdue is the part whose status is OVERDUE (api/be-billing-invoices.md, section 6.3).

/** Totals of the open invoices in one currency. */
export interface AmountDueTotals {
  currency:     string;
  /** Sum of `outstanding` over every open invoice. */
  outstanding:  number;
  /** The part of `outstanding` that belongs to OVERDUE invoices. */
  overdue:      number;
  /** Open invoices with a balance. */
  invoiceCount: number;
  overdueCount: number;
}

export interface AmountDueSummary {
  /** One entry per currency, largest balance first. Empty when nothing is owed. */
  totals:       AmountDueTotals[];
  /** Open invoices with a balance, across currencies. */
  invoiceCount: number;
  overdueCount: number;
  /** The earliest due date ("YYYY-MM-DD") among open invoices that are not overdue yet, or null. */
  nextDueDate:  string | null;
  /** True when the patient has more open invoices than were read, so the totals are a lower bound. */
  truncated:    boolean;
}

/** Whole cents of an amount, so sums do not pick up binary rounding noise. */
function cents(amount: number | null | undefined): number {
  return typeof amount === 'number' && Number.isFinite(amount) ? Math.round(amount * 100) : 0;
}

/** Computes the summary from a list of invoices; invoices that are not open or have no balance are ignored. */
export function summariseOpenInvoices(invoices: readonly Invoice[], truncated = false): AmountDueSummary {
  const byCurrency = new Map<string, { outstanding: number; overdue: number; invoiceCount: number; overdueCount: number }>();
  let invoiceCount = 0;
  let overdueCount = 0;
  let nextDueDate: string | null = null;

  for (const invoice of invoices) {
    if (!OPEN_INVOICE_STATUSES.includes(invoice.status)) continue;
    const owed = cents(invoice.outstanding);
    if (owed <= 0) continue;

    const currency = (invoice.currency ?? '').trim().toUpperCase();
    const row = byCurrency.get(currency) ?? { outstanding: 0, overdue: 0, invoiceCount: 0, overdueCount: 0 };
    row.outstanding += owed;
    row.invoiceCount += 1;
    invoiceCount += 1;
    if (invoice.status === 'OVERDUE') {
      row.overdue += owed;
      row.overdueCount += 1;
      overdueCount += 1;
    } else if (invoice.dueDate && (nextDueDate === null || invoice.dueDate < nextDueDate)) {
      // "YYYY-MM-DD" strings order the same as the dates they stand for.
      nextDueDate = invoice.dueDate;
    }
    byCurrency.set(currency, row);
  }

  const totals: AmountDueTotals[] = Array.from(byCurrency.entries())
    .map(([currency, row]) => ({
      currency,
      outstanding:  row.outstanding / 100,
      overdue:      row.overdue / 100,
      invoiceCount: row.invoiceCount,
      overdueCount: row.overdueCount,
    }))
    .sort((a, b) => b.outstanding - a.outstanding);

  return { totals, invoiceCount, overdueCount, nextDueDate, truncated };
}

/** Most pages read for one summary (1000 open invoices); beyond that the summary is marked `truncated`. */
const MAX_SUMMARY_PAGES = 10;

/**
 * Reads the patient's open invoices and totals what is still owed. A patient passes their own id, a caregiver
 * the loved one's (403 without the billing permission). Rejects with the request's error.
 */
export async function loadAmountDue(patientSystemId: string): Promise<AmountDueSummary> {
  const invoices: Invoice[] = [];
  let truncated = false;
  for (let page = 0; ; page++) {
    const result = await listPatientInvoices(patientSystemId, { status: OPEN_INVOICE_STATUSES }, page, MAX_PAGE_SIZE);
    invoices.push(...result.content);
    if (page + 1 >= result.totalPages) break;
    if (page + 1 >= MAX_SUMMARY_PAGES) { truncated = true; break; }
  }
  return summariseOpenInvoices(invoices, truncated);
}
