import { useEffect, useState } from 'react';
import { usePaged, type PagedState } from '@/hooks/use-paged';
import { listOutboundMessages, probeOutboxAvailable } from '@/lib/admin/ops-api';
import type { OutboundMessage, OutboundMessageFilter } from '@/lib/admin/ops-types';

/**
 * Queued outbound messages (development installations only), newest first, page by page. On an installation
 * without the endpoint the first request fails with `errorStatus` 404.
 */
export function useAdminOutbox({ channel, to = '', template = '' }: OutboundMessageFilter = {}): PagedState<OutboundMessage> {
  const recipient = to.trim();
  const templateId = template.trim();
  return usePaged<OutboundMessage>(
    (page, size) => listOutboundMessages({ channel, to: recipient, template: templateId }, page, size),
    [channel, recipient, templateId],
    { keyOf: m => m.messageId, errorMessage: 'The outbox could not be loaded.' },
  );
}

export interface OutboxAvailability {
  /** true = the development outbox exists here; false = it does not (404) or could not be checked; null = still checking. */
  available: boolean | null;
}

/**
 * Whether the Outbox section should be offered. The admin registry lists the section only when `available` is
 * true, so it is absent on production installations. A failed check counts as "not offered" rather than
 * showing a section that may not exist.
 */
export function useAdminOutboxAvailable(enabled = true): OutboxAvailability {
  const [available, setAvailable] = useState<boolean | null>(enabled ? null : false);
  useEffect(() => {
    if (!enabled) { setAvailable(false); return; }
    let cancelled = false;
    setAvailable(null);
    probeOutboxAvailable()
      .then(result => { if (!cancelled) setAvailable(result); })
      .catch(() => { if (!cancelled) setAvailable(false); });
    return () => { cancelled = true; };
  }, [enabled]);
  return { available };
}
