import { useEffect, useState } from 'react';

/**
 * Whether the browser reports a network connection. Changes cannot be saved while offline, so the screen says so
 * and disables its submit controls. Always true where the browser events do not exist (native, tests).
 */
export function useOnline(): boolean {
  const [online, setOnline] = useState(() =>
    typeof navigator === 'undefined' || navigator.onLine !== false);

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.addEventListener !== 'function') return;
    const on  = () => setOnline(true);
    const off = () => setOnline(false);
    window.addEventListener('online', on);
    window.addEventListener('offline', off);
    return () => {
      window.removeEventListener('online', on);
      window.removeEventListener('offline', off);
    };
  }, []);

  return online;
}
