import { useEffect, useState } from 'react';
import { apiErrorMessage } from '@/hooks/use-appointments';
import { fetchRecipients, httpStatus } from '@/lib/communications/api';
import type { RecipientsResponse } from '@/lib/communications/types';

interface State {
  data:      RecipientsResponse | null;
  loading:   boolean;
  error:     string | null;
  forbidden: boolean;
  refetch:   () => void;
}

/**
 * People the current user may message about a patient.
 * Patients may omit `patientSystemId` (defaults to self); pass `enabled=false` until a patient is chosen.
 */
export function useMessageRecipients(patientSystemId: string | null | undefined, enabled = true): State {
  const [data,      setData]      = useState<RecipientsResponse | null>(null);
  const [loading,   setLoading]   = useState(false);
  const [error,     setError]     = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [tick,      setTick]      = useState(0);

  useEffect(() => {
    if (!enabled) {
      setData(null);
      setError(null);
      setForbidden(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    setForbidden(false);
    fetchRecipients(patientSystemId)
      .then(res => { if (!cancelled) setData(res); })
      .catch(e => {
        if (cancelled) return;
        setData(null);
        setForbidden(httpStatus(e) === 403);
        setError(apiErrorMessage(e, 'Failed to load people you can message.'));
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [patientSystemId, enabled, tick]);

  return { data, loading, error, forbidden, refetch: () => setTick(t => t + 1) };
}
