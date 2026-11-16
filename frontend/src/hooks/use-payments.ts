import { useCallback, useEffect, useRef, useState } from 'react';
import { usePolling } from '@/hooks/use-page-active';
import { usePaged, type PagedState } from '@/hooks/use-paged';
import { useResource, type ResourceState } from '@/hooks/use-resource';
import { apiError } from '@/lib/api/errors';
import { getPayment, listInvoicePayments, listPatientPayments } from '@/lib/billing/api';
import type { Payment } from '@/lib/billing/types';

// Payment reads (api/be-billing-payments.md). Starting a payment is a single call (`initiatePayment` in
// lib/billing/api) made by the Pay dialog; `usePaymentWatch` then follows the payment until it settles.

function positiveId(value: number | null | undefined): number | null {
  return typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : null;
}

/**
 * The payment history of one invoice, failed attempts included, newest first
 * (`GET /api/billing/invoices/{id}/payments`). Readable by everyone who may read the invoice, the issuing doctor
 * included.
 */
export function useInvoicePayments(
  invoiceId: number | null | undefined,
  options: { pageSize?: number; enabled?: boolean } = {},
): PagedState<Payment> {
  const id = positiveId(invoiceId);
  return usePaged<Payment>(
    (page, size) => listInvoicePayments(id as number, page, size),
    [id],
    {
      enabled:      (options.enabled ?? true) && id !== null,
      pageSize:     options.pageSize,
      keyOf:        payment => payment.paymentId,
      errorMessage: 'We could not load the payments. Please try again.',
    },
  );
}

/**
 * Every payment of one patient across invoices, newest first (`GET /api/billing/payments?patientSystemId=`).
 * For the patient and for a caregiver who may view billing; a doctor gets 403 and must not call it.
 */
export function usePatientPayments(
  patientSystemId: string | null | undefined,
  options: { pageSize?: number; enabled?: boolean } = {},
): PagedState<Payment> {
  const id = patientSystemId?.trim() || null;
  return usePaged<Payment>(
    (page, size) => listPatientPayments(id as string, page, size),
    [id],
    {
      enabled:      (options.enabled ?? true) && id !== null,
      pageSize:     options.pageSize,
      keyOf:        payment => payment.paymentId,
      errorMessage: 'We could not load the payments. Please try again.',
    },
  );
}

/** One payment (`GET /api/billing/payments/{id}`). Each successful read is audited, so do not poll with it. */
export function usePayment(
  paymentId: number | null | undefined,
  options: { enabled?: boolean } = {},
): ResourceState<Payment> {
  const id = positiveId(paymentId);
  return useResource<Payment>(
    () => getPayment(id as number),
    [id],
    { enabled: (options.enabled ?? true) && id !== null, errorMessage: 'We could not load this payment. Please try again.' },
  );
}

/** A pending payment is read every 5 seconds, at most 24 times (two minutes); each read writes an audit row. */
export const PAYMENT_POLL_INTERVAL_MS = 5000;
export const PAYMENT_POLL_MAX_ATTEMPTS = 24;

export interface PaymentWatch {
  /** The latest known state of the payment being watched, or null when none is. */
  payment:     Payment | null;
  /** True while the payment is pending and is still being checked automatically. */
  watching:    boolean;
  /** True when the payment is still pending after the last automatic check: say "still processing" and stop waiting. */
  timedOut:    boolean;
  /** The sentence of the last failed check, or null. Checks continue after a transient failure. */
  error:       string | null;
  /** HTTP status of the last failed check. 403 or 404 ends the watch: the caller may no longer read the payment. */
  errorStatus: number | null;
  /** Reads the payment once more now, for a "Check again" button after `timedOut`. */
  checkNow:    () => Promise<void>;
}

interface WatchState {
  paymentId:   number | null;
  payment:     Payment | null;
  attempts:    number;
  error:       string | null;
  errorStatus: number | null;
}

/**
 * Follows a payment returned by `initiatePayment` (or by a replayed Idempotency-Key, which may be out of date)
 * until it leaves PENDING. Polling pauses while the tab is hidden or offline and stops after
 * `PAYMENT_POLL_MAX_ATTEMPTS` checks or when the server refuses the read.
 *
 * `onSettled` is called once when the payment turns COMPLETED, FAILED, REFUNDED or CANCELLED: refetch the invoice
 * there, its balance changed in the same commit. Keep `started` in state: the watch restarts when its `paymentId`
 * changes and adopts a new object for the same payment.
 *
 * ```ts
 * const watch = usePaymentWatch(started, { onSettled: () => invoice.refetch() });
 * ```
 */
export function usePaymentWatch(
  started: Payment | null | undefined,
  options: { onSettled?: (payment: Payment) => void } = {},
): PaymentWatch {
  const startedId = started?.paymentId ?? null;
  const [state, setState] = useState<WatchState>(() => ({
    paymentId: startedId, payment: started ?? null, attempts: 0, error: null, errorStatus: null,
  }));

  const onSettledRef = useRef(options.onSettled);
  onSettledRef.current = options.onSettled;
  const watchedId = useRef(startedId);
  watchedId.current = startedId;
  // The payment id `onSettled` was already called for, so a later manual check does not report it twice.
  const settledFor = useRef<number | null>(null);

  // A different payment (or none) starts a fresh watch.
  useEffect(() => {
    setState({ paymentId: startedId, payment: started ?? null, attempts: 0, error: null, errorStatus: null });
    // Only the id identifies the watch; a re-rendered `started` object for the same payment must not reset it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [startedId]);

  // A newer copy of the same payment handed in by the caller (the answer of a simulated settlement, for example)
  // replaces the one being watched. A copy the caller has held all along is never applied again, so it cannot
  // undo what a later check has read.
  useEffect(() => {
    if (!started) return;
    setState(prev => (prev.paymentId === started.paymentId && prev.payment !== started
      ? { ...prev, payment: started, error: null, errorStatus: null }
      : prev));
  }, [started]);

  // Until the effects above have run, state kept for another payment is stale: report the new one as it was handed in.
  const current: WatchState = state.paymentId === startedId
    ? state
    : { paymentId: startedId, payment: started ?? null, attempts: 0, error: null, errorStatus: null };

  const check = useCallback(async (counts: boolean) => {
    const id = watchedId.current;
    if (id === null) return;
    try {
      const next = await getPayment(id);
      if (watchedId.current !== id) return;
      setState(prev => (prev.paymentId === id
        ? { ...prev, payment: next, attempts: prev.attempts + (counts ? 1 : 0), error: null, errorStatus: null }
        : prev));
      if (next.status !== 'PENDING' && settledFor.current !== id) {
        settledFor.current = id;
        onSettledRef.current?.(next);
      }
    } catch (e) {
      if (watchedId.current !== id) return;
      const failure = apiError(e, 'We could not check the payment. Please try again.');
      setState(prev => (prev.paymentId === id
        ? { ...prev, attempts: prev.attempts + (counts ? 1 : 0), error: failure.message, errorStatus: failure.status }
        : prev));
    }
  }, []);

  const pending = current.payment?.status === 'PENDING';
  const refused = current.errorStatus === 403 || current.errorStatus === 404;
  const watching = pending && !refused && current.attempts < PAYMENT_POLL_MAX_ATTEMPTS;

  usePolling(() => check(true), PAYMENT_POLL_INTERVAL_MS, watching);

  // A payment that was already settled when it was handed in (the gateway answered at once) is reported too.
  useEffect(() => {
    const payment = current.payment;
    if (!payment || payment.status === 'PENDING' || settledFor.current === payment.paymentId) return;
    settledFor.current = payment.paymentId;
    onSettledRef.current?.(payment);
  }, [current.payment]);

  const checkNow = useCallback(() => check(false), [check]);

  return {
    payment:     current.payment,
    watching,
    timedOut:    pending && !refused && current.attempts >= PAYMENT_POLL_MAX_ATTEMPTS,
    error:       current.error,
    errorStatus: current.errorStatus,
    checkNow,
  };
}
