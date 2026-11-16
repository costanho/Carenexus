import { useEffect, useRef } from 'react';
import { api } from '@/lib/auth/auth.interceptor';

// The shared BookingForm and ReschedulePanel show a refused request on the form itself and report only success to
// the screen. A caregiver's scheduling permission can be revoked while one of them is open; the screen then has
// to say so clearly and reload the loved ones, so the refusal must reach it too. This hook observes the answers
// of the appointment write calls (it never changes them: the form still gets the same error) and reports a 403.

/**
 * The writes the shared forms send: POST /api/appointments (BookingForm), PUT /api/appointments/{id} (ReschedulePanel)
 * and PATCH /api/appointments/{id}/reschedule. A cancel (DELETE) is sent by this screen itself, which handles its 403.
 */
const WRITE_PATHS: { method: string; path: RegExp }[] = [
  { method: 'post',   path: /^\/api\/appointments\/?$/ },
  { method: 'put',    path: /^\/api\/appointments\/\d+\/?$/ },
  { method: 'patch',  path: /^\/api\/appointments\/\d+\/reschedule\/?$/ },
];

interface RequestConfigLike { method?: string; url?: string; baseURL?: string }

/** The path of a request, without the API origin and the query string. */
function pathOf(config: RequestConfigLike): string {
  let url = config.url ?? '';
  const base = config.baseURL ?? '';
  if (base && url.startsWith(base)) url = url.slice(base.length);
  url = url.replace(/^[a-z]+:\/\/[^/]+/i, '');
  const query = url.search(/[?#]/);
  return query >= 0 ? url.slice(0, query) : url;
}

export function isAppointmentWrite(config: RequestConfigLike | null | undefined): boolean {
  if (!config) return false;
  const method = (config.method ?? 'get').toLowerCase();
  const path = pathOf(config);
  return WRITE_PATHS.some(w => w.method === method && w.path.test(path));
}

/**
 * Calls `onForbidden` whenever an appointment write made while the calling screen is mounted is answered 403
 * (the caregiver's scheduling permission was removed or the grant ended). The rejected request still reaches
 * the form that sent it unchanged.
 */
export function useSchedulingRefusal(onForbidden: () => void, enabled = true): void {
  const callback = useRef(onForbidden);
  callback.current = onForbidden;

  useEffect(() => {
    if (!enabled) return undefined;
    const id = api.interceptors.response.use(
      response => response,
      (error: unknown) => {
        const failure = error as { response?: { status?: number }; config?: RequestConfigLike } | null;
        if (failure?.response?.status === 403 && isAppointmentWrite(failure.config)) {
          // Reported after the form has handled its own error, so both updates land in a settled tree.
          setTimeout(() => callback.current(), 0);
        }
        return Promise.reject(error);
      },
    );
    return () => api.interceptors.response.eject(id);
  }, [enabled]);
}
