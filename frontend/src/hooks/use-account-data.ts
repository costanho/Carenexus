import { useState } from 'react';
import { useAction, type ActionState } from '@/hooks/use-action';
import { useResource } from '@/hooks/use-resource';
import {
  cancelAccountDeletion, closeAccount, downloadDataExport, listDataExports, listDeletionRequests,
  requestAccountDeletion, requestDataExport,
} from '@/lib/account/api';
import { saveBlob } from '@/lib/account/browser';
import { endLocalSession } from '@/lib/account/session';
import type { AccountClosure, AccountRequest, PasswordReasonRequest } from '@/lib/account/types';

// Data rights of the signed-in account: export, deletion request, closure (be-account-data-rights).
// The three password-confirming requests answer a wrong password with 400 `PASSWORD_INCORRECT`.

export interface DataExportsState {
  /** The account's exports, newest first; at most the first one is downloadable. Empty until loaded. */
  exports: AccountRequest[];
  loading: boolean;
  error:   string | null;
  refetch: () => void;
  /** Builds a new export (the password is confirmed again). The previous export stops being downloadable. */
  request:  ActionState<[string], AccountRequest>;
  /** Fetches the file with the bearer token and saves it under the export's file name. */
  download: ActionState<[AccountRequest], void>;
  /** The export `download` is working on, for the row's spinner. */
  downloadingId: number | null;
}

export function useDataExports(): DataExportsState {
  const list = useResource(listDataExports, [], { errorMessage: 'Your data exports could not be loaded.' });
  const { refetch } = list;
  const [downloadingId, setDownloadingId] = useState<number | null>(null);

  const request = useAction(async (password: string) => {
    const created = await requestDataExport(password);
    refetch();
    return created;
  }, { errorMessage: 'The export could not be prepared. Please try again.' });

  const download = useAction(async (row: AccountRequest) => {
    setDownloadingId(row.requestId);
    try {
      const blob = await downloadDataExport(row.requestId);
      saveBlob(blob, row.fileName?.trim() || `carenexus-data-export-${row.requestId}.json`);
    } catch (e) {
      // An export that expired or was replaced since the list was loaded: show its real state.
      refetch();
      throw e;
    } finally {
      setDownloadingId(null);
    }
  }, { errorMessage: 'The export could not be downloaded. Please try again.' });

  return {
    exports: list.data ?? [],
    loading: list.loading,
    error:   list.error,
    refetch,
    request,
    download,
    downloadingId,
  };
}

export interface DeletionRequestsState {
  /** The newest deletion request (the current state), or null when the account never asked. */
  latest:  AccountRequest | null;
  loading: boolean;
  error:   string | null;
  refetch: () => void;
  /** Files a request for an administrator to review. Nothing is deleted or deactivated by it. */
  request: ActionState<[PasswordReasonRequest], AccountRequest>;
  /** Withdraws the request that is awaiting review. */
  cancel:  ActionState<[number], AccountRequest>;
}

export function useDeletionRequests(): DeletionRequestsState {
  const list = useResource(listDeletionRequests, [], { errorMessage: 'Your deletion request could not be loaded.' });
  const { refetch, setData } = list;

  const request = useAction(async (body: PasswordReasonRequest) => {
    try {
      const created = await requestAccountDeletion(body);
      setData(prev => [created, ...(prev ?? [])]);
      return created;
    } catch (e) {
      refetch(); // a 409 means a request is already awaiting review: show it
      throw e;
    }
  }, { errorMessage: 'The deletion request could not be sent. Please try again.' });

  const cancel = useAction(async (requestId: number) => {
    try {
      const updated = await cancelAccountDeletion(requestId);
      setData(prev => (prev ?? []).map(r => (r.requestId === updated.requestId ? updated : r)));
      return updated;
    } catch (e) {
      refetch(); // already decided or gone: show the real state
      throw e;
    }
  }, { errorMessage: 'The request could not be withdrawn. Please try again.' });

  return {
    latest:  list.data?.[0] ?? null,
    loading: list.loading,
    error:   list.error,
    refetch,
    request,
    cancel,
  };
}

/**
 * Closes the signed-in account (POST /api/users/me/close). After a success the access token is refused at
 * once and every refresh token is revoked, so the stored session is dropped here without another request;
 * the caller shows the returned message and sends the person to the sign-in screen.
 */
export function useCloseAccount(): ActionState<[PasswordReasonRequest], AccountClosure> {
  return useAction(async (body: PasswordReasonRequest) => {
    const closure = await closeAccount(body);
    await endLocalSession();
    return closure;
  }, { errorMessage: 'Your account could not be closed. Please try again.' });
}
