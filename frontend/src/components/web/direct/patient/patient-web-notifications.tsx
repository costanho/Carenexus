import { useCallback, useMemo } from 'react';
import { parseNotificationType } from '@/components/web/notifications/notification-meta';
import { NotificationList, type NotificationListFilters } from '@/components/web/notifications/notification-list';
import { useSectionNav } from '@/hooks/use-section-nav';
import { followActionUrl } from '@/lib/nav/action-url';
import { ROLE_THEME, type SectionProps } from '@/lib/nav/sections';

/**
 * Patient dashboard section `notifications`: the full notification list. A conversation message opens Messages
 * on its thread; every other notification follows its `actionUrl` (CONTRACTS 7.1) through `followActionUrl`, so
 * appointment, result, referral, care-team and caregiver-access links all deep-link the same way. A notification
 * without a link for this dashboard is informational and is only marked read.
 *
 * The filters live in the URL (`tab=unread`, `type=<NotificationType>`), so a refresh and Back keep them.
 */
export default function PatientWebNotifications(props: Partial<SectionProps>) {
  const nav = useSectionNav();
  const params = props.params ?? nav.params;
  const theme = props.theme ?? ROLE_THEME.patient;
  const { role, navigate, openThread, hasSection, homeKey, setParams } = nav;

  const onNavigate = useCallback(
    (actionUrl: string | null | undefined) => followActionUrl(actionUrl, { role, navigate, hasSection, homeKey }),
    [role, navigate, hasSection, homeKey],
  );

  const unreadOnly = params.tab === 'unread';
  const type = parseNotificationType(params.type);
  const filters = useMemo(() => ({ unreadOnly, type }), [unreadOnly, type]);
  const onFiltersChange = useCallback((next: NotificationListFilters) => {
    setParams({ tab: next.unreadOnly ? 'unread' : undefined, type: next.type ?? undefined });
  }, [setParams]);

  return (
    <NotificationList
      theme={theme}
      onOpenThread={props.openThread ?? openThread}
      onNavigate={onNavigate}
      filters={filters}
      onFiltersChange={onFiltersChange}
    />
  );
}
