import { useCallback } from 'react';
import { useResource } from '@/hooks/use-resource';
import { getNotificationChannels } from '@/lib/settings/api';
import type { NotificationChannels } from '@/lib/settings/types';

// Which external channels (email, SMS, push) this installation can send on. The answer describes the
// installation, not the user, and changes only when the API is restarted, so it is kept for the session.

let cached: NotificationChannels | null = null;

function load(): Promise<NotificationChannels> {
  if (cached) return Promise.resolve(cached);
  return getNotificationChannels().then(channels => {
    cached = channels;
    return channels;
  });
}

export interface NotificationChannelsState {
  /** Null until loaded. */
  channels: NotificationChannels | null;
  loading:  boolean;
  /** A sentence to show when the channels could not be loaded. */
  error:    string | null;
  /** Asks the server again (the kept answer is dropped first). */
  refetch:  () => void;
}

export function useNotificationChannels(): NotificationChannelsState {
  const resource = useResource(load, [], { errorMessage: 'Could not check which delivery channels are available.' });
  const { refetch: reload } = resource;
  const refetch = useCallback(() => {
    cached = null;
    reload();
  }, [reload]);
  return { channels: resource.data, loading: resource.loading, error: resource.error, refetch };
}
