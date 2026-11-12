import { useAction, type ActionState } from '@/hooks/use-action';
import { changePassword } from '@/lib/account/api';
import { currentSessionId } from '@/lib/account/session';
import { updateSessionFlags } from '@/lib/auth/auth.storage';

export interface ChangePasswordInput {
  currentPassword: string;
  newPassword:     string;
}

export interface ChangePasswordResult {
  /** The server's confirmation sentence. */
  message: string;
  /**
   * True when this device's session id was sent as `keepSessionId`, so this device stays signed in and only
   * the others are ended. False when no session id is stored: then every session ends, this one included.
   */
  keptThisSession: boolean;
}

/**
 * Changes the signed-in user's password (POST /api/users/me/password). The current session id is always sent
 * as `keepSessionId` when one is stored, and the stored `mustChangePassword` flag is cleared after a success.
 */
export function useChangePassword(): ActionState<[ChangePasswordInput], ChangePasswordResult> {
  return useAction(async (input: ChangePasswordInput): Promise<ChangePasswordResult> => {
    const keepSessionId = await currentSessionId();
    const { message } = await changePassword({
      currentPassword: input.currentPassword,
      newPassword:     input.newPassword,
      ...(keepSessionId ? { keepSessionId } : {}),
    });
    await updateSessionFlags({ mustChangePassword: false });
    return { message, keptThisSession: keepSessionId !== null };
  }, { errorMessage: 'Your password could not be changed. Please try again.' });
}
