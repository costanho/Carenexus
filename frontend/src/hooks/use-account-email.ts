import { useAction, type ActionState } from '@/hooks/use-action';
import { useResource } from '@/hooks/use-resource';
import { cancelEmailChange, getPendingEmailChange, requestEmailChange } from '@/lib/account/api';
import type { EmailChangeRequest, EmailChangeResponse } from '@/lib/account/types';

export interface EmailChangeState {
  /** The request waiting for confirmation, or null when there is none (also null while loading). */
  pending: EmailChangeResponse | null;
  loading: boolean;
  /** Why the pending request could not be loaded, or null. */
  error:   string | null;
  refetch: () => void;
  /** Starts a change; a new request replaces the pending one. */
  request: ActionState<[EmailChangeRequest], EmailChangeResponse>;
  /** Cancels the pending request; its emailed link stops working at once. */
  cancel:  ActionState<[], void>;
}

/** The signed-in user's pending email change (GET, POST and DELETE /api/users/me/email-change). */
export function useEmailChange(): EmailChangeState {
  const pending = useResource(getPendingEmailChange, [], { errorMessage: 'Your email settings could not be loaded.' });
  const { setData } = pending;

  const request = useAction(async (body: EmailChangeRequest) => {
    const created = await requestEmailChange(body);
    setData(created);
    return created;
  }, { errorMessage: 'The email change could not be requested. Please try again.' });

  const cancel = useAction(async () => {
    await cancelEmailChange();
    setData(null);
  }, { errorMessage: 'The request could not be cancelled. Please try again.' });

  return {
    pending: pending.data,
    loading: pending.loading,
    error:   pending.error,
    refetch: pending.refetch,
    request,
    cancel,
  };
}
