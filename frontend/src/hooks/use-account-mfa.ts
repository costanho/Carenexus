import { useAction, type ActionState } from '@/hooks/use-action';
import { useResource } from '@/hooks/use-resource';
import { disableMfa, enableMfa, getMfaStatus, regenerateRecoveryCodes, startMfaSetup } from '@/lib/account/api';
import type { MfaDisableRequest, MfaSetup, MfaStatus } from '@/lib/account/types';
import { updateSessionFlags } from '@/lib/auth/auth.storage';

export interface MfaState {
  /** Two-factor status of the signed-in account; null until loaded. */
  status:  MfaStatus | null;
  loading: boolean;
  error:   string | null;
  refetch: () => void;
  /** Starts (or continues) enrolment: returns the secret and its otpauth URI. Safe to repeat. */
  setup:      ActionState<[], MfaSetup>;
  /** Confirms enrolment with a code from the app. Resolves with the ten recovery codes, which exist only here. */
  enable:     ActionState<[string], string[]>;
  /** Turns two-factor authentication off (password plus an app code or a recovery code). */
  disable:    ActionState<[MfaDisableRequest], MfaStatus>;
  /** Replaces every recovery code. Resolves with the ten new codes. */
  regenerate: ActionState<[string], string[]>;
}

/**
 * Two-factor authentication of the signed-in account (/api/users/me/mfa/**). GET /api/users/me/mfa is the
 * source of truth; the stored session flag `mfaEnabled` is kept in step after enabling and disabling.
 */
export function useMfa(): MfaState {
  const status = useResource(getMfaStatus, [], { errorMessage: 'Two-factor authentication status could not be loaded.' });
  const { refetch, setData } = status;

  const setup = useAction(startMfaSetup, { errorMessage: 'Setup could not be started. Please try again.' });

  const enable = useAction(async (code: string) => {
    const { recoveryCodes } = await enableMfa(code);
    await updateSessionFlags({ mfaEnabled: true });
    refetch();
    return recoveryCodes;
  }, { errorMessage: 'Two-factor authentication could not be turned on. Please try again.' });

  const disable = useAction(async (body: MfaDisableRequest) => {
    const next = await disableMfa(body);
    await updateSessionFlags({ mfaEnabled: next.enabled });
    setData(next);
    return next;
  }, { errorMessage: 'Two-factor authentication could not be turned off. Please try again.' });

  const regenerate = useAction(async (code: string) => {
    const { recoveryCodes } = await regenerateRecoveryCodes(code);
    refetch();
    return recoveryCodes;
  }, { errorMessage: 'New recovery codes could not be generated. Please try again.' });

  return { status: status.data, loading: status.loading, error: status.error, refetch, setup, enable, disable, regenerate };
}
