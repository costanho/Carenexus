import { useCallback, useEffect, useRef, useState } from 'react';
import { apiError } from '@/lib/api/errors';
import { getBillingConfig } from '@/lib/billing/api';
import type { BillingConfig } from '@/lib/billing/types';

// GET /api/billing/config answers the same for every signed-in user and only changes with a server
// configuration change, so it is read once and kept for the session. Only a successful answer is kept.

let cached: BillingConfig | null = null;
let inFlight: Promise<BillingConfig> | null = null;

function load(force: boolean): Promise<BillingConfig> {
  if (!force && cached) return Promise.resolve(cached);
  if (!force && inFlight) return inFlight;
  const request = getBillingConfig()
    .then(config => { cached = config; return config; })
    .finally(() => { if (inFlight === request) inFlight = null; });
  inFlight = request;
  return request;
}

/** Forgets the kept configuration, so the next `useBillingConfig` asks the server again. */
export function clearBillingConfigCache(): void {
  cached = null;
  inFlight = null;
}

export interface BillingConfigState {
  /** Null until the first answer arrives. */
  config:  BillingConfig | null;
  loading: boolean;
  error:   string | null;
  /** Asks the server again (also the Retry of the error state). */
  refetch: () => void;
}

/**
 * What the active payment gateway accepts. Screens derive three rules from it: offer only `onlineMethods` to
 * patients and caregivers (an empty list hides Pay); when `liveGateway` is false say plainly that no live payment
 * gateway is connected and no money moves; show a Simulate control only when `simulationEnabled` is true.
 */
export function useBillingConfig(options: { enabled?: boolean } = {}): BillingConfigState {
  const enabled = options.enabled ?? true;
  const [config, setConfig] = useState<BillingConfig | null>(cached);
  const [loading, setLoading] = useState(enabled && cached === null);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const lastTick = useRef(0);

  useEffect(() => {
    if (!enabled) { setLoading(false); return; }
    const force = tick !== lastTick.current;
    lastTick.current = tick;
    if (!force && cached) { setConfig(cached); setLoading(false); setError(null); return; }

    let cancelled = false;
    setLoading(true);
    setError(null);
    load(force)
      .then(next => {
        if (cancelled) return;
        setConfig(next);
        setLoading(false);
      })
      .catch(e => {
        if (cancelled) return;
        setError(apiError(e, 'We could not load the payment options. Please try again.').message);
        setLoading(false);
      });
    return () => { cancelled = true; };
  }, [enabled, tick]);

  const refetch = useCallback(() => setTick(t => t + 1), []);
  return { config, loading, error, refetch };
}
