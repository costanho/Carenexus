import { useResource, type ResourceState } from '@/hooks/use-resource';
import { listSystemSettings } from '@/lib/admin/ops-api';
import type { SystemSetting } from '@/lib/admin/ops-types';

/** Every system setting with its effective value, default and limits, for the admin `system-settings` section. */
export function useAdminSystemSettings(): ResourceState<SystemSetting[]> {
  return useResource<SystemSetting[]>(() => listSystemSettings(), [], {
    errorMessage: 'The system settings could not be loaded.',
  });
}
