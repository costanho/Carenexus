import { useCallback } from 'react';
import { useAction, type ActionState } from '@/hooks/use-action';
import { usePaged, type PagedState } from '@/hooks/use-paged';
import { createAdminUser, listAdminUsers } from '@/lib/admin/api';
import type { AdminUser, AdminUserDetail, AdminUserFilters, CreateAdminRequest } from '@/lib/admin/types';
import { useIdempotencyKey } from '@/lib/api/idempotency';

const keyOfUser = (user: AdminUser) => user.userId;

/**
 * The account directory (GET /api/admin/users), newest account first, page by page.
 * A change of a filter drops the rows and loads the first page again.
 */
export function useAdminUsers(filters: AdminUserFilters, options: { pageSize?: number; enabled?: boolean } = {}): PagedState<AdminUser> {
  const q = filters.q?.trim() ?? '';
  const role = filters.role ?? '';
  const active = filters.active;
  return usePaged<AdminUser>(
    (page, size) => listAdminUsers({ q, role, active }, page, size),
    [q, role, active],
    { pageSize: options.pageSize, enabled: options.enabled, keyOf: keyOfUser, errorMessage: 'The accounts could not be loaded.' },
  );
}

export interface CreateAdminState extends ActionState<[CreateAdminRequest], AdminUserDetail> {
  /** Call after a success: the next submit is a new request with a new key. */
  renewKey: () => void;
}

/**
 * Creates an administrator account (POST /api/admin/users). One Idempotency-Key is used for as long as the
 * component that calls this hook stays mounted (one opening of the dialog), so a retried submit repeats the key
 * and the server answers with the first result instead of creating a second account.
 */
export function useCreateAdmin(): CreateAdminState {
  const key = useIdempotencyKey();
  const current = key.current;
  const create = useCallback((body: CreateAdminRequest) => createAdminUser(body, current()), [current]);
  const action = useAction(create, { errorMessage: 'The administrator account could not be created.' });
  return { ...action, renewKey: key.renew };
}
