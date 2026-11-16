import { useCallback } from 'react';
import {
  acceptReferral, assignReferralSpecialist, cancelReferral, completeReferral, createReferral, declineReferral,
  getReferral, listIncomingReferrals, listOutgoingReferrals, listPatientReferrals, lookupDoctorPatientName,
} from '@/lib/referrals/api';
import type {
  AssignSpecialistBody, CreateReferralBody, DoctorReferralFilters, Referral, ReferralStatus,
} from '@/lib/referrals/types';
import { type ActionState, useAction } from './use-action';
import { type PagedState, usePaged } from './use-paged';
import { type ResourceState, useResource } from './use-resource';

// Referral data hooks (contract `api/be-referrals.md`). Lists are paged ("Load more"); every write goes through
// `useAction`, so a double click cannot send a request twice and the server's sentence is available to show
// inline (a 409 such as "This referral is declined and cannot be accepted" included).

export interface ReferralListOptions {
  /** When false nothing is requested. Default true. */
  enabled?: boolean;
  pageSize?: number;
}

const keyOf = (referral: Referral) => referral.referralId;

function filterDeps(filters: DoctorReferralFilters | undefined): [string, string] {
  return [filters?.status || '', filters?.patientSystemId || ''];
}

/** Referrals the signed-in doctor issued (Sent), newest first, with an optional status and patient filter. */
export function useOutgoingReferrals(filters?: DoctorReferralFilters, options: ReferralListOptions = {}): PagedState<Referral> {
  const [status, patientSystemId] = filterDeps(filters);
  return usePaged<Referral>(
    (page, size) => listOutgoingReferrals({ status: status as ReferralStatus | '', patientSystemId }, page, size),
    [status, patientSystemId],
    { enabled: options.enabled, pageSize: options.pageSize, keyOf, errorMessage: 'Could not load the referrals you sent.' },
  );
}

/** Referrals addressed to the signed-in doctor as specialist (Received), every status unless filtered. */
export function useIncomingReferrals(filters?: DoctorReferralFilters, options: ReferralListOptions = {}): PagedState<Referral> {
  const [status, patientSystemId] = filterDeps(filters);
  return usePaged<Referral>(
    (page, size) => listIncomingReferrals({ status: status as ReferralStatus | '', patientSystemId }, page, size),
    [status, patientSystemId],
    { enabled: options.enabled, pageSize: options.pageSize, keyOf, errorMessage: 'Could not load the referrals you received.' },
  );
}

/**
 * A patient's referrals, every status, newest first: the patient's own list, or a loved one's for a caregiver
 * whose access allows it. The endpoint has no status filter. Nothing is requested without a system id.
 */
export function usePatientReferrals(patientSystemId: string | null | undefined, options: ReferralListOptions = {}): PagedState<Referral> {
  const id = (patientSystemId ?? '').trim();
  return usePaged<Referral>(
    (page, size) => listPatientReferrals(id, page, size),
    [id],
    { enabled: (options.enabled ?? true) && !!id, pageSize: options.pageSize, keyOf, errorMessage: 'Could not load referrals.' },
  );
}

/** One referral by id. `errorStatus` 403 or 404 means it is not (or no longer) available to the caller. */
export function useReferral(referralId: number | null | undefined, options: { enabled?: boolean } = {}): ResourceState<Referral> {
  const valid = typeof referralId === 'number' && Number.isInteger(referralId) && referralId > 0;
  return useResource<Referral>(
    () => getReferral(referralId as number),
    [valid ? referralId : null],
    { enabled: (options.enabled ?? true) && valid, errorMessage: 'Could not load this referral.' },
  );
}

/**
 * How many referrals addressed to the signed-in doctor still wait for a response (the Received tab's badge).
 * A failed count is simply not shown; `refetch` after a response was recorded.
 */
export function usePendingIncomingCount(options: { enabled?: boolean } = {}): { count: number; refetch: () => void } {
  const { data, refetch } = useResource<number>(
    () => listIncomingReferrals({ status: 'PENDING' }, 0, 1).then(page => page.totalElements),
    [],
    { enabled: options.enabled ?? true },
  );
  return { count: data ?? 0, refetch };
}

/**
 * The name of one of the signed-in doctor's patients, for the patient filter chip. Null while loading, when
 * there is no id, and when the patient is not in the doctor's own list.
 */
export function useDoctorPatientName(patientSystemId: string | null | undefined, options: { enabled?: boolean } = {}): string | null {
  const id = (patientSystemId ?? '').trim();
  const { data } = useResource<string | null>(
    () => lookupDoctorPatientName(id),
    [id],
    { enabled: (options.enabled ?? true) && !!id },
  );
  return data ?? null;
}

// ─── Writes ───────────────────────────────────────────────────────────────────

/** Create a referral. Pass the open form's idempotency key; reuse it on retry and renew it after success. */
export function useCreateReferral(): ActionState<[body: CreateReferralBody, idempotencyKey: string], Referral> {
  return useAction(
    (body: CreateReferralBody, idempotencyKey: string) => createReferral(body, idempotencyKey),
    { errorMessage: 'Could not create the referral. Please try again.' },
  );
}

/** Assign or replace the specialist of a pending referral. */
export function useAssignReferralSpecialist(): ActionState<[referralId: number, body: AssignSpecialistBody], Referral> {
  return useAction(
    (referralId: number, body: AssignSpecialistBody) => assignReferralSpecialist(referralId, body),
    { errorMessage: 'Could not assign the specialist. Please try again.' },
  );
}

/** Accept a pending referral. The note is required when recorded on the specialist's behalf. */
export function useAcceptReferral(): ActionState<[referralId: number, note?: string | null], Referral> {
  return useAction(
    (referralId: number, note?: string | null) => acceptReferral(referralId, note?.trim() ? { note: note.trim() } : {}),
    { errorMessage: 'Could not accept the referral. Please try again.' },
  );
}

/** Decline a pending referral with a reason. */
export function useDeclineReferral(): ActionState<[referralId: number, reason: string], Referral> {
  return useAction(
    (referralId: number, reason: string) => declineReferral(referralId, { reason: reason.trim() }),
    { errorMessage: 'Could not decline the referral. Please try again.' },
  );
}

/** Complete an accepted referral. The note is required when recorded on the specialist's behalf. */
export function useCompleteReferral(): ActionState<[referralId: number, note?: string | null], Referral> {
  return useAction(
    (referralId: number, note?: string | null) => completeReferral(referralId, note?.trim() ? { note: note.trim() } : {}),
    { errorMessage: 'Could not complete the referral. Please try again.' },
  );
}

/** Withdraw a pending referral (the referring doctor only). */
export function useCancelReferral(): ActionState<[referralId: number], Referral> {
  return useAction(
    (referralId: number) => cancelReferral(referralId),
    { errorMessage: 'Could not cancel the referral. Please try again.' },
  );
}

/**
 * Applies a referral returned by a write to a loaded list without another request: the row is replaced in place.
 * Use it for instant feedback, then `refetch` so a row that no longer matches the status filter leaves the list.
 */
export function useReferralRowUpdater(setItems: PagedState<Referral>['setItems']): (updated: Referral) => void {
  return useCallback((updated: Referral) => {
    setItems(rows => rows.map(row => (row.referralId === updated.referralId ? updated : row)));
  }, [setItems]);
}
