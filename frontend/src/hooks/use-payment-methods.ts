import { useRef } from 'react';
import { useAction, type ActionState } from '@/hooks/use-action';
import { useResource, type ResourceState } from '@/hooks/use-resource';
import { isNotFoundError } from '@/lib/api/errors';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import {
  addPaymentMethod, deletePaymentMethod, listPatientPaymentMethods, setDefaultPaymentMethod,
} from '@/lib/billing/api';
import type { CreatePaymentMethodRequest, PaymentMethod } from '@/lib/billing/types';

// Saved payment methods (api/be-billing-payment-methods.md). Only the owning patient can add, change or remove
// one; a caregiver who may manage billing reads the list to choose a method when paying.

/**
 * The saved payment methods of one patient, default first
 * (`GET /api/billing/payment-methods/patient/{patientSystemId}`). The patient passes their own id, a caregiver the
 * loved one's. Responses are masked for everyone: only `lastFour` and `displayLabel` describe the instrument.
 *
 * A 403 for a caregiver (`errorStatus === 403`) means the grant does not allow managing billing: hide Pay.
 * A doctor always gets 403 and must not call it (pass `enabled: false`).
 */
export function usePaymentMethods(
  patientSystemId: string | null | undefined,
  options: { enabled?: boolean } = {},
): ResourceState<PaymentMethod[]> {
  const id = patientSystemId?.trim() || null;
  return useResource<PaymentMethod[]>(
    () => listPatientPaymentMethods(id as string),
    [id],
    { enabled: (options.enabled ?? true) && id !== null, errorMessage: 'We could not load the saved payment methods. Please try again.' },
  );
}

export interface PaymentMethodActions {
  /** Saves a new method for the signed-in patient. One Idempotency-Key is kept until a save succeeds. */
  add:        ActionState<[request: CreatePaymentMethodRequest], PaymentMethod>;
  /** Makes one of the signed-in patient's methods the default. */
  setDefault: ActionState<[methodId: number], PaymentMethod>;
  /** Removes one of the signed-in patient's methods. A method that is already gone counts as removed. */
  remove:     ActionState<[methodId: number], void>;
}

/**
 * The changes a patient can make to their own saved methods. `onChanged` runs after each successful change:
 * refetch the list there, because adding, removing or changing the default also changes the flags of the other
 * methods.
 *
 * ```ts
 * const methods = usePaymentMethods(patientSystemId);
 * const actions = usePaymentMethodActions(methods.refetch);
 * const result = await actions.add.run(toCreatePaymentMethodRequest(values));
 * ```
 */
export function usePaymentMethodActions(onChanged?: () => void): PaymentMethodActions {
  const onChangedRef = useRef(onChanged);
  onChangedRef.current = onChanged;
  const key = useIdempotencyKey();

  const add = useAction(async (request: CreatePaymentMethodRequest) => {
    const method = await addPaymentMethod(request, key.current());
    key.renew();
    onChangedRef.current?.();
    return method;
  }, { errorMessage: 'We could not save the payment method. Please try again.' });

  const setDefault = useAction(async (methodId: number) => {
    const method = await setDefaultPaymentMethod(methodId);
    onChangedRef.current?.();
    return method;
  }, { errorMessage: 'We could not change the default payment method. Please try again.' });

  const remove = useAction(async (methodId: number) => {
    try {
      await deletePaymentMethod(methodId);
    } catch (e) {
      // 404 = already removed (a second click, another tab): the outcome the person asked for.
      if (!isNotFoundError(e)) throw e;
    }
    onChangedRef.current?.();
  }, { errorMessage: 'We could not remove the payment method. Please try again.' });

  return { add, setDefault, remove };
}
