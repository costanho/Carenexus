import { useEffect, useState } from 'react';

/**
 * The value, once it has stopped changing for `delayMs`. Used for search boxes of the admin lists, so a request
 * is sent when the person pauses, not on every key.
 */
export function useDebouncedValue<T>(value: T, delayMs = 350): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    if (Object.is(settled, value)) return;
    const timer = setTimeout(() => setSettled(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs, settled]);
  return settled;
}
