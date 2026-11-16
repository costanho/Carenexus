import { useCallback, useEffect, useRef, useState } from 'react';
import { apiErrorMessage } from '@/lib/api/errors';
import { onSignedOut } from '@/lib/auth/auth.events';
import { fetchPublicSettings } from '@/lib/support/api';
import type { PublicSettings } from '@/lib/support/types';

export type { PublicSettings } from '@/lib/support/types';

// Clinic name, support contact and clinic hours (GET /api/settings/public). The answer is the same for every
// signed-in user and rarely changes, so it is kept in memory for the session: one request serves every screen
// that reads it, and a page reload asks again.

let cached: PublicSettings | null = null;
let inflight: Promise<PublicSettings> | null = null;
/** Bumped when the cache is dropped, so a response that was already on its way is not stored. */
let generation = 0;
const listeners = new Set<() => void>();

function text(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

function normalise(data: PublicSettings): PublicSettings {
  return {
    ...data,
    clinicName:   text(data?.clinicName) ?? '',
    supportEmail: text(data?.supportEmail),
    supportPhone: text(data?.supportPhone),
  };
}

/** The cached settings, or one shared request for them. `force` asks the server again. */
export function loadPublicSettings(force = false): Promise<PublicSettings> {
  if (!force && cached) return Promise.resolve(cached);
  if (!force && inflight) return inflight;
  const gen = generation;
  const request = fetchPublicSettings()
    .then(data => {
      const value = normalise(data);
      if (gen === generation) cached = value;
      return value;
    })
    .finally(() => { if (inflight === request) inflight = null; });
  inflight = request;
  return request;
}

/**
 * Drops the cached settings; every mounted `usePublicSettings` loads them again. Call it after an administrator
 * changes the clinic name or the support contact.
 */
export function invalidatePublicSettings(): void {
  cached = null;
  inflight = null;
  generation += 1;
  listeners.forEach(fn => {
    try { fn(); } catch { /* one consumer must not block the others */ }
  });
}

// The endpoint needs a signed-in user, so nothing is kept after sign-out.
onSignedOut(() => {
  cached = null;
  inflight = null;
  generation += 1;
});

/** Support email from the build configuration, used when the server has none or cannot be reached. */
export function fallbackSupportEmail(): string | null {
  return text(process.env.EXPO_PUBLIC_SUPPORT_EMAIL);
}

export interface PublicSettingsState {
  /** The clinic settings; null while loading and when they could not be loaded. */
  settings:     PublicSettings | null;
  loading:      boolean;
  /** A sentence to show when the settings could not be loaded, or null. */
  error:        string | null;
  /**
   * The support email to show: the configured one, otherwise EXPO_PUBLIC_SUPPORT_EMAIL (also while the request
   * is failing). Null while loading and when neither exists.
   */
  supportEmail: string | null;
  /** Asks the server again (retry after an error). */
  refetch:      () => void;
}

/** `const { settings, supportEmail, loading, error, refetch } = usePublicSettings();` */
export function usePublicSettings(options: { enabled?: boolean } = {}): PublicSettingsState {
  const enabled = options.enabled ?? true;
  const [settings, setSettings] = useState<PublicSettings | null>(() => (enabled ? cached : null));
  const [loading,  setLoading]  = useState(enabled && !cached);
  const [error,    setError]    = useState<string | null>(null);
  const requestRef = useRef(0);

  const load = useCallback((force: boolean) => {
    const id = ++requestRef.current;
    if (force || !cached) setLoading(true);
    setError(null);
    loadPublicSettings(force)
      .then(value => {
        if (id !== requestRef.current) return;
        setSettings(value);
        setLoading(false);
      })
      .catch(e => {
        if (id !== requestRef.current) return;
        // A failed retry keeps what an earlier request loaded, if anything.
        setSettings(cached);
        setError(apiErrorMessage(e, 'Clinic details could not be loaded.'));
        setLoading(false);
      });
  }, []);

  useEffect(() => {
    if (!enabled) {
      requestRef.current += 1;
      setSettings(null);
      setLoading(false);
      setError(null);
      return;
    }
    load(false);
    const onInvalidated = () => load(false);
    listeners.add(onInvalidated);
    return () => {
      listeners.delete(onInvalidated);
      requestRef.current += 1;
    };
  }, [enabled, load]);

  const refetch = useCallback(() => { if (enabled) load(true); }, [enabled, load]);

  const supportEmail = loading ? null : (settings?.supportEmail ?? fallbackSupportEmail());
  return { settings, loading, error, supportEmail, refetch };
}
