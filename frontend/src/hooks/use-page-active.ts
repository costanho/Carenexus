import { useEffect, useRef, useState } from 'react';
import { AppState, Platform } from 'react-native';

const NATIVE = Platform.OS !== 'web';

function isVisible(): boolean {
  // iOS / Android: "visible" while the app is in the foreground.
  if (NATIVE) return AppState.currentState == null || AppState.currentState === 'active';
  return typeof document === 'undefined' || document.visibilityState !== 'hidden';
}

function isOnline(): boolean {
  return typeof navigator === 'undefined' || navigator.onLine !== false;
}

export interface PageActivity {
  /** Tab is visible AND the browser reports a connection. */
  active:  boolean;
  visible: boolean;
  online:  boolean;
}

/** Tracks document visibility and navigator.onLine (web), or the app being in the foreground (native). */
export function usePageActive(): PageActivity {
  const [visible, setVisible] = useState(isVisible);
  const [online,  setOnline]  = useState(isOnline);

  useEffect(() => {
    if (NATIVE) {
      // No navigator.onLine on native: "online" stays true and failed polls show as "not up to date".
      const sub = AppState.addEventListener('change', state => setVisible(state === 'active'));
      return () => sub.remove();
    }
    if (typeof document === 'undefined' || typeof window === 'undefined') return;
    const onVisibility = () => setVisible(isVisible());
    const onOnline     = () => setOnline(true);
    const onOffline    = () => setOnline(false);
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);
    return () => {
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('online', onOnline);
      window.removeEventListener('offline', onOffline);
    };
  }, []);

  return { active: visible && online, visible, online };
}

/**
 * Runs `fn` every `intervalMs` while the page is visible and online (and `enabled`).
 * Pauses when hidden/offline; on return it runs immediately and resumes.
 * The first run on mount is left to the caller (pass `runOnStart` to include it).
 */
export function usePolling(
  fn: () => void | Promise<unknown>,
  intervalMs: number,
  enabled = true,
  runOnStart = false,
): void {
  const { active } = usePageActive();
  const fnRef = useRef(fn);
  fnRef.current = fn;
  const firstRef  = useRef(true);
  const pausedRef = useRef(false);

  useEffect(() => {
    if (!enabled) return;
    if (!active) {
      pausedRef.current = true;
      return;
    }
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;

    const tick = async () => {
      if (cancelled) return;
      try {
        await fnRef.current();
      } catch {
        // the callback owns its error state
      }
      if (!cancelled) timer = setTimeout(tick, intervalMs);
    };

    // Coming back from hidden/offline (or re-enabled): refresh straight away.
    const immediate = pausedRef.current || (firstRef.current && runOnStart);
    firstRef.current  = false;
    pausedRef.current = false;
    if (immediate) tick();
    else timer = setTimeout(tick, intervalMs);

    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [active, enabled, intervalMs, runOnStart]);
}
