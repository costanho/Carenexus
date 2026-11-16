import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useResource } from '@/hooks/use-resource';
import { apiError, type ApiError } from '@/lib/api/errors';
import { applySettingsPatch, getUserSettings, patchUserSettings } from '@/lib/settings/api';
import type { UpdateUserSettings, UserSettings } from '@/lib/settings/types';

// The signed-in account's notification settings: loaded once, then changed one PATCH at a time. A change is
// shown at once and rolled back if the server refuses it.

export type SettingsField = keyof UpdateUserSettings;

/** A change the server refused (or that never reached it). The change has been rolled back. */
export interface SettingsSaveFailure {
  /** The fields the refused change carried. */
  fields: SettingsField[];
  error:  ApiError;
}

export type SettingsUpdateResult =
  | { ok: true;  data: UserSettings }
  /** `error` is null when nothing was sent because the settings are not loaded yet. */
  | { ok: false; error: ApiError | null };

export interface UserSettingsState {
  /** The settings with every change still being saved already applied; null until loaded. */
  settings:    UserSettings | null;
  loading:     boolean;
  /** Load error. After a failed reload the settings already on screen are kept alongside it. */
  error:       string | null;
  errorStatus: number | null;
  refetch:     () => void;
  /**
   * Applies the change on screen, sends it, and takes it back if the server refuses it. Changes are sent one
   * after another in the order they were made, so quick successive changes cannot overwrite each other.
   * Never rejects: check `result.ok`.
   */
  update:      (patch: UpdateUserSettings) => Promise<SettingsUpdateResult>;
  /** True while at least one change is waiting for the server. */
  saving:      boolean;
  /** The most recent refused change; cleared when the next change is made or by `clearFailure`. */
  failure:     SettingsSaveFailure | null;
  clearFailure: () => void;
  /** Number of changes saved since the screen opened (0 = nothing saved yet), for a "Saved" status. */
  savedCount:  number;
}

interface PendingChange {
  id:    number;
  patch: UpdateUserSettings;
}

const LOAD_ERROR = 'Could not load your settings. Please try again.';
const SAVE_ERROR = 'Could not save this change. Please try again.';

export function useUserSettings(): UserSettingsState {
  const resource = useResource(getUserSettings, [], { errorMessage: LOAD_ERROR });
  const { data, setData } = resource;

  const [pending, setPending] = useState<PendingChange[]>([]);
  const [failure, setFailure] = useState<SettingsSaveFailure | null>(null);
  const [savedCount, setSavedCount] = useState(0);

  const loaded = useRef(false);
  loaded.current = data !== null;
  const nextId = useRef(0);
  // The tail of the queue: each change waits for the one before it.
  const queue = useRef<Promise<unknown>>(Promise.resolve());
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  const update = useCallback((patch: UpdateUserSettings): Promise<SettingsUpdateResult> => {
    if (!loaded.current) return Promise.resolve({ ok: false, error: null });
    const id = ++nextId.current;
    setPending(list => [...list, { id, patch }]);
    setFailure(null);

    const run = queue.current.then(async (): Promise<SettingsUpdateResult> => {
      try {
        const saved = await patchUserSettings(patch);
        if (mounted.current) {
          // The answer already contains this change, so it stops being "pending" in the same render.
          setData(saved);
          setPending(list => list.filter(change => change.id !== id));
          setSavedCount(count => count + 1);
        }
        return { ok: true, data: saved };
      } catch (e) {
        const error = apiError(e, SAVE_ERROR);
        if (mounted.current) {
          // Dropping the change from the list is the rollback: the screen shows the last saved value again.
          setPending(list => list.filter(change => change.id !== id));
          setFailure({ fields: Object.keys(patch) as SettingsField[], error });
        }
        return { ok: false, error };
      }
    });
    queue.current = run;
    return run;
  }, [setData]);

  const clearFailure = useCallback(() => setFailure(null), []);

  const settings = useMemo(
    () => (data ? pending.reduce((current, change) => applySettingsPatch(current, change.patch), data) : null),
    [data, pending],
  );

  return {
    settings,
    loading:     resource.loading,
    error:       resource.error,
    errorStatus: resource.errorStatus,
    refetch:     resource.refetch,
    update,
    saving:      pending.length > 0,
    failure,
    clearFailure,
    savedCount,
  };
}
