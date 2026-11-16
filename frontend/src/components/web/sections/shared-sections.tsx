import { lazy, useCallback } from 'react';
import { useSectionNav } from '@/hooks/use-section-nav';
import type { MessagingMode } from '@/lib/communications/types';
import type { SectionParams, SectionProps } from '@/lib/nav/sections';
import { useNotificationActions } from '../layout/shell-support';

// Section adapters shared by more than one role registry.

const MessagesScreen = lazy(() =>
  import('../messages/messages-screen').then(m => ({ default: m.MessagesScreen }))
);
const NotificationList = lazy(() =>
  import('../notifications/notification-list').then(m => ({ default: m.NotificationList }))
);
const HelpSupport = lazy(() => import('../help/help-support'));

/**
 * The conversation a link asked for (`?section=messages&thread=<id>`). The messages screen opens it and then
 * reports it handled; the param is removed at that point (in place, no history entry), so picking another
 * conversation and later following a link to the same one opens it again.
 */
export function useThreadParam(params: SectionParams): { openThreadId: number | null; onOpenThreadHandled: () => void } {
  const { setParams } = useSectionNav();
  const id = params.thread !== undefined ? Number(params.thread) : NaN;
  const onOpenThreadHandled = useCallback(() => setParams({ thread: undefined }), [setParams]);
  return { openThreadId: Number.isInteger(id) && id > 0 ? id : null, onOpenThreadHandled };
}

/** Messages for the patient and doctor dashboards (the caregiver dashboard has its own wrapper). */
export function MessagesSection({ mode, params, theme }: SectionProps & { mode: MessagingMode }) {
  const thread = useThreadParam(params);
  return (
    <MessagesScreen
      mode={mode}
      theme={theme}
      openThreadId={thread.openThreadId}
      onOpenThreadHandled={thread.onOpenThreadHandled}
    />
  );
}

/** Full-page notification list. Message notifications open the conversation; the rest follow their link. */
export function NotificationsSection({ theme }: SectionProps) {
  const actions = useNotificationActions(useSectionNav());
  return (
    <NotificationList
      theme={theme}
      onOpenThread={actions.onOpenThread}
      onOpenCaregiverAccess={actions.onOpenCaregiverAccess}
      onActivated={actions.onActivated}
    />
  );
}

export function HelpSupportSection(props: SectionProps) {
  return <HelpSupport {...props} />;
}
