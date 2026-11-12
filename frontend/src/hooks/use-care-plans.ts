import { useCallback, useMemo } from 'react';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import { effectivePermission, hasCaregiverPermission, type CaregiverGrant } from '@/lib/caregiver/permissions';
import {
  createCarePlan, getCarePlan, listCarePlanPatientOptions, listDoctorCarePlans, listPatientCarePlans,
  reviewCarePlan, setCarePlanStatus, updateCarePlan, updateGoalProgress,
} from '@/lib/careplans/api';
import type {
  CarePlan, CarePlanPatientOption, CarePlanStatus, CreateCarePlanRequest, GoalProgressRequest,
  ReviewCarePlanRequest, UpdateCarePlanRequest,
} from '@/lib/careplans/types';
import { type ActionState, useAction } from './use-action';
import { type PagedState, usePaged } from './use-paged';
import { type ResourceState, useResource } from './use-resource';

// Care plans (/api/care-plans): the paged lists of the three dashboards, one plan by id, and the mutations.
// Lists are newest first and each row is a whole plan with its goals and interventions.

const LIST_ERROR = 'Care plans could not be loaded. Please try again.';
const planKey = (plan: CarePlan) => plan.planId;

export interface CarePlanListOptions {
  /** Only plans with this status; null or undefined lists every status. */
  status?:  CarePlanStatus | null;
  /** When false nothing is requested. Default true. */
  enabled?: boolean;
}

/**
 * All care plans of one patient, whoever wrote them (GET /api/care-plans/patient/{id}). Used by the patient
 * for their own plans, by a caregiver for a loved one and by a doctor for a patient in their care. A 403 is
 * reported as `errorStatus === 403` (PagedList shows the no-access state).
 */
export function usePatientCarePlans(
  patientSystemId: string | null | undefined,
  options: CarePlanListOptions = {},
): PagedState<CarePlan> {
  const id = (patientSystemId ?? '').trim();
  const status = options.status ?? null;
  return usePaged<CarePlan>(
    (page, size) => listPatientCarePlans(id, page, size, { status }),
    [id, status],
    { enabled: (options.enabled ?? true) && id !== '', errorMessage: LIST_ERROR, keyOf: planKey },
  );
}

/**
 * The care plans the signed-in doctor wrote (GET /api/care-plans/doctor), optionally only those for one
 * patient (the doctor screen's `patient` URL parameter, filtered by the server).
 */
export function useDoctorCarePlans(
  options: CarePlanListOptions & { patientSystemId?: string | null } = {},
): PagedState<CarePlan> {
  const status = options.status ?? null;
  const patient = (options.patientSystemId ?? '').trim() || null;
  return usePaged<CarePlan>(
    (page, size) => listDoctorCarePlans(page, size, { status, patientSystemId: patient }),
    [status, patient],
    { enabled: options.enabled ?? true, errorMessage: LIST_ERROR, keyOf: planKey },
  );
}

export interface DependentCarePlans {
  plans:     PagedState<CarePlan>;
  /** The grant is active and lets the caregiver read care plans. When false nothing is requested. */
  canView:   boolean;
  /** The grant lets the caregiver update goal progress (never with view-only access). */
  canManage: boolean;
}

/**
 * The care plans of one of the caregiver's loved ones. The permissions come from the shared caregiver rule
 * (`@/lib/caregiver/permissions`), so full access, view-only access and an ended grant are handled the way the
 * server decides them; the plans are requested only when the grant allows reading them, and a 403 from the
 * server still shows as no access for this loved one alone.
 */
export function useDependentCarePlans(
  dependent: (CaregiverGrant & { patientSystemId: string }) | null | undefined,
  options: CarePlanListOptions = {},
): DependentCarePlans {
  const canView = hasCaregiverPermission(dependent, 'CARE_PLANS');
  const canManage = effectivePermission(dependent, 'canManageCarePlan');
  const plans = usePatientCarePlans(dependent?.patientSystemId, {
    status:  options.status,
    enabled: (options.enabled ?? true) && canView,
  });
  return { plans, canView, canManage };
}

/** One care plan by id (GET /api/care-plans/{id}). 403 and 404 are reported in `errorStatus`. */
export function useCarePlan(planId: number | null | undefined, options: { enabled?: boolean } = {}): ResourceState<CarePlan> {
  const id = planId ?? null;
  return useResource<CarePlan>(
    () => getCarePlan(id as number),
    [id],
    { enabled: (options.enabled ?? true) && id !== null, errorMessage: 'This care plan could not be loaded. Please try again.' },
  );
}

// ─── A list plus the plan a link points at ────────────────────────────────────

export interface LinkedCarePlan {
  /** The plan named by the `id` URL parameter when it is not among the loaded rows; null otherwise. */
  plan:        CarePlan | null;
  loading:     boolean;
  error:       string | null;
  /** 403 = no access, 404 = not found (also a plan that belongs to someone else on this screen). */
  errorStatus: number | null;
  retry:       () => void;
}

export interface CarePlanBoard {
  list:    PagedState<CarePlan>;
  /** The plan to show opened (from the `id` URL parameter), or null. */
  openId:  number | null;
  linked:  LinkedCarePlan;
  /** Puts the plan returned by a mutation in place of the copy on screen (list row and linked plan). */
  apply:   (plan: CarePlan) => void;
  /**
   * Takes a plan off the list because it no longer matches the list's filter (a status change while the list
   * is filtered by status). It stays on screen as the opened plan through the `id` URL parameter, and the list
   * is loaded again so its count is right.
   */
  drop:    (plan: CarePlan) => void;
  /** Loads the list and the linked plan again (after a save was refused because the plan changed elsewhere). */
  refresh: () => void;
}

/**
 * Pairs a care-plan list with the plan a notification or link points at. When that plan is not in the loaded
 * rows (another page, a different status filter) it is fetched on its own so the screen can still open it.
 * `patientSystemId` limits the linked plan to one patient: a plan of anyone else is reported as not found.
 */
export function useCarePlanBoard(
  list: PagedState<CarePlan>,
  openId: number | null,
  options: { patientSystemId?: string | null; enabled?: boolean } = {},
): CarePlanBoard {
  const inList = openId !== null && list.items.some(p => p.planId === openId);
  const listFailed = !!list.error && list.items.length === 0;
  const wanted = (options.enabled ?? true) && openId !== null && !list.loading && !listFailed && !inList;
  const single = useCarePlan(openId, { enabled: wanted });

  const scope = (options.patientSystemId ?? '').trim();
  const foreign = !!single.data && scope !== '' && single.data.patientSystemId !== scope;

  const { setItems, refetch: refetchList } = list;
  const { setData, refetch: refetchSingle } = single;

  const apply = useCallback((plan: CarePlan) => {
    setItems(prev => prev.map(p => (p.planId === plan.planId ? plan : p)));
    setData(prev => (prev && prev.planId === plan.planId ? plan : prev));
  }, [setItems, setData]);

  const drop = useCallback((plan: CarePlan) => {
    setItems(prev => prev.filter(p => p.planId !== plan.planId));
    refetchList();
  }, [setItems, refetchList]);

  const refresh = useCallback(() => {
    refetchList();
    refetchSingle();
  }, [refetchList, refetchSingle]);

  const linked = useMemo<LinkedCarePlan>(() => {
    if (!wanted) return { plan: null, loading: false, error: null, errorStatus: null, retry: refetchSingle };
    if (foreign) {
      return { plan: null, loading: false, error: 'This care plan was not found.', errorStatus: 404, retry: refetchSingle };
    }
    return {
      plan:        single.data,
      loading:     single.loading,
      error:       single.data ? null : single.error,
      errorStatus: single.data ? null : single.errorStatus,
      retry:       refetchSingle,
    };
  }, [wanted, foreign, single.data, single.loading, single.error, single.errorStatus, refetchSingle]);

  return { list, openId, linked, apply, drop, refresh };
}

// ─── Mutations ────────────────────────────────────────────────────────────────

/**
 * Creates a care plan (treating doctor). Call the hook once per open form: every submit of that form carries
 * the same Idempotency-Key, so a retry after a lost response returns the plan that was already created, and a
 * new key is drawn after a success.
 */
export function useCreateCarePlan(): ActionState<[CreateCarePlanRequest], CarePlan> {
  const key = useIdempotencyKey();
  return useAction(async (body: CreateCarePlanRequest) => {
    const plan = await createCarePlan(body, key.current());
    key.renew();
    return plan;
  }, { errorMessage: 'The care plan could not be saved. Please try again.' });
}

/** Replaces the editable content of a plan (author doctor). */
export function useUpdateCarePlan(): ActionState<[number, UpdateCarePlanRequest], CarePlan> {
  return useAction(
    (planId: number, body: UpdateCarePlanRequest) => updateCarePlan(planId, body),
    { errorMessage: 'The care plan could not be saved. Please try again.' },
  );
}

/** Changes the status of a plan (author doctor). */
export function useSetCarePlanStatus(): ActionState<[number, CarePlanStatus], CarePlan> {
  return useAction(
    (planId: number, status: CarePlanStatus) => setCarePlanStatus(planId, status),
    { errorMessage: 'The status could not be changed. Please try again.' },
  );
}

/** Records a review of a plan and sets its next review date (author doctor). */
export function useReviewCarePlan(): ActionState<[number, ReviewCarePlanRequest], CarePlan> {
  return useAction(
    (planId: number, body: ReviewCarePlanRequest) => reviewCarePlan(planId, body),
    { errorMessage: 'The review could not be saved. Please try again.' },
  );
}

/** Updates the status and/or progress note of one goal. Resolves to the whole plan. */
export function useGoalProgress(): ActionState<[number, number, GoalProgressRequest], CarePlan> {
  return useAction(
    (planId: number, goalId: number, body: GoalProgressRequest) => updateGoalProgress(planId, goalId, body),
    { errorMessage: 'The goal could not be updated. Please try again.' },
  );
}

// ─── Patient names for the doctor screen ──────────────────────────────────────

export interface CarePlanPatientOptions {
  options: CarePlanPatientOption[];
  loading: boolean;
  error:   string | null;
  refetch: () => void;
  /** The patient's name, or null while the list is loading or when the patient is not in it. */
  nameOf:  (patientSystemId: string | null | undefined) => string | null;
}

/** Id and name of the signed-in doctor's patients (GET /api/doctors/patients/options), for the picker and for names. */
export function useCarePlanPatientOptions(options: { enabled?: boolean } = {}): CarePlanPatientOptions {
  const { data, loading, error, refetch } = useResource<CarePlanPatientOption[]>(
    listCarePlanPatientOptions,
    [],
    { enabled: options.enabled ?? true, errorMessage: 'Your patient list could not be loaded.' },
  );
  const list = useMemo(() => data ?? [], [data]);
  const names = useMemo(() => new Map(list.map(o => [o.patientSystemId, o.fullName] as const)), [list]);
  const nameOf = useCallback(
    (patientSystemId: string | null | undefined) => names.get((patientSystemId ?? '').trim()) || null,
    [names],
  );
  return { options: list, loading, error, refetch, nameOf };
}
