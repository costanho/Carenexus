import { useCallback, useEffect, useRef, useState } from 'react';
import { apiErrorMessage } from './use-appointments';
import { getCallStatus } from '@/lib/calls/api';
import type { CallStatusResponse } from '@/lib/calls/types';

interface State {
  status:    CallStatusResponse | null;
  loading:   boolean;
  error:     string | null;
  /** HTTP status of the last failed load (403/404 are final for the screen). */
  errorCode: number | null;
  offline:   boolean;
  refetch:   () => Promise<CallStatusResponse | null>;
  /** Replace the cached status with a fresher one (e.g. a POST response). */
  setStatus: (s: CallStatusResponse) => void;
}

function isOnline(): boolean {
  return typeof navigator === 'undefined' || navigator.onLine !== false;
}

function isVisible(): boolean {
  return typeof document === 'undefined' || document.visibilityState !== 'hidden';
}

/**
 * Loads GET /api/appointments/{id}/call and polls it every `intervalMs` while the page is
 * visible and online. Pass `intervalMs = null` to stop polling (the initial load still runs).
 */
export function useCallStatus(appointmentId: number, intervalMs: number | null): State {
  const [status, setStatusState] = useState<CallStatusResponse | null>(null);
  const [loading, setLoading]     = useState(true);
  const [error, setError]         = useState<string | null>(null);
  const [errorCode, setErrorCode] = useState<number | null>(null);
  const [offline, setOffline]     = useState(!isOnline());
  const mounted  = useRef(true);
  const inFlight = useRef<Promise<CallStatusResponse | null> | null>(null);

  const load = useCallback((): Promise<CallStatusResponse | null> => {
    if (inFlight.current) return inFlight.current;
    const p = getCallStatus(appointmentId)
      .then(data => {
        if (mounted.current) {
          setStatusState(data);
          setError(null);
          setErrorCode(null);
        }
        return data;
      })
      .catch(e => {
        if (mounted.current) {
          setError(apiErrorMessage(e, 'Could not load the call. Check your connection and try again.'));
          setErrorCode(e?.response?.status ?? null);
        }
        return null;
      })
      .finally(() => {
        inFlight.current = null;
        if (mounted.current) setLoading(false);
      });
    inFlight.current = p;
    return p;
  }, [appointmentId]);

  useEffect(() => {
    mounted.current = true;
    setLoading(true);
    load();
    return () => { mounted.current = false; };
  }, [load]);

  useEffect(() => {
    if (intervalMs == null) return;
    let timer: ReturnType<typeof setInterval> | null = null;

    const stop  = () => { if (timer) { clearInterval(timer); timer = null; } };
    const start = () => {
      stop();
      if (!isVisible() || !isOnline()) return;
      timer = setInterval(() => { load(); }, intervalMs);
    };
    const resume = () => {
      setOffline(!isOnline());
      if (isVisible() && isOnline()) load();
      start();
    };
    const pause = () => { setOffline(!isOnline()); stop(); };

    start();
    if (typeof document !== 'undefined' && typeof window !== 'undefined') {
      const onVisibility = () => (isVisible() ? resume() : pause());
      document.addEventListener('visibilitychange', onVisibility);
      window.addEventListener('online', resume);
      window.addEventListener('offline', pause);
      return () => {
        stop();
        document.removeEventListener('visibilitychange', onVisibility);
        window.removeEventListener('online', resume);
        window.removeEventListener('offline', pause);
      };
    }
    return stop;
  }, [intervalMs, load]);

  const setStatus = useCallback((s: CallStatusResponse) => setStatusState(s), []);

  return { status, loading, error, errorCode, offline, refetch: load, setStatus };
}
