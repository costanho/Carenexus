import { Ionicons } from '@expo/vector-icons';
import { useRef, useState } from 'react';
import { Platform, ScrollView, StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';
import { type NotificationFilters, useNotifications, useUnreadCounts } from '@/hooks/use-notifications';
import { usePageActive } from '@/hooks/use-page-active';
import { ariaLevel, decorative } from '@/components/web/ui/a11y';
import { apiErrorMessage } from '@/lib/api/errors';
import { shortTime } from '@/lib/communications/format';
import { type AppNotification, NOTIFICATION_TYPES, type NotificationType } from '@/lib/communications/types';
import {
  BG, BORDER, Banner, DEFAULT_THEME, Empty, ErrorBlock, GRAY, LIGHT, Loading, type MessagesTheme, RED, RED_BG, TEXT,
  ThemeContext, UrgentTag, useTheme, WHITE,
} from '../messages/ui';
import {
  destinationHint, emphasisLabel, notificationDestination, notificationEmphasis, NOTIFICATION_TYPE_META,
  notificationTypeMeta, parseNotificationType, type NotificationDestination, type NotificationHandlers,
} from './notification-meta';

/** The filters of the list, always complete. */
export interface NotificationListFilters {
  unreadOnly: boolean;
  type:       NotificationType | null;
}

interface Props {
  theme?:       MessagesTheme;
  /** Called for a MESSAGE notification with a thread. */
  onOpenThread: (threadId: number) => void;
  /**
   * Legacy, optional: called for a caregiver access notification (patient dashboard, native screens). Where it
   * is not passed, such a notification follows its link like every other one.
   */
  onOpenCaregiverAccess?: () => void;
  /**
   * Follows a notification's in-app link (the dashboards pass `followActionUrl`). Called for every
   * notification that is not a conversation message and not handled by `onOpenCaregiverAccess`; one without
   * a link is informational and only gets marked read.
   */
  onNavigate?:  (actionUrl: string | null | undefined) => boolean | void;
  /**
   * Called when any notification is activated, before it is opened. A host that passes this without
   * `onNavigate` is expected to follow `item.actionUrl` itself (the dashboards' notifications section does);
   * do not follow the link in both.
   */
  onActivated?: (item: AppNotification) => void;
  /** Dropdown only: shows a Close button in the header. */
  onClose?:     () => void;
  /** Compact = dropdown panel; otherwise a full page section with a title and the type filter. */
  compact?:     boolean;
  /**
   * Filters kept by the host (for example in the URL, so a refresh keeps them). With `onFiltersChange` the
   * list is controlled; without it the value is only the starting point.
   */
  filters?:     NotificationFilters;
  onFiltersChange?: (filters: NotificationListFilters) => void;
}

const IS_WEB = Platform.OS === 'web';
/** Below this width the type filter is one row that scrolls sideways instead of wrapping over several lines. */
const NARROW_MAX = 768;

function completeFilters(filters: NotificationFilters | undefined): NotificationListFilters {
  return { unreadOnly: !!filters?.unreadOnly, type: parseNotificationType(filters?.type) };
}

export function NotificationList({
  theme = DEFAULT_THEME, onOpenThread, onOpenCaregiverAccess, onNavigate, onActivated, onClose, compact,
  filters, onFiltersChange,
}: Props) {
  const { online } = usePageActive();
  const { width } = useWindowDimensions();
  const [ownFilters, setOwnFilters] = useState<NotificationListFilters>(() => completeFilters(filters));
  const controlled = filters !== undefined && onFiltersChange !== undefined;
  const { unreadOnly, type } = controlled ? completeFilters(filters) : ownFilters;
  const filtered = unreadOnly || type !== null;

  const n = useNotifications({ unreadOnly, type });
  const counts = useUnreadCounts(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [markingAll, setMarkingAll] = useState(false);
  const titleRef = useRef<any>(null);

  // Unread rows of other types or further pages are not loaded here, so the shared count decides as well.
  const hasUnread = counts.unreadCount > 0 || n.notifications.some(x => !x.read);

  const handlers: NotificationHandlers = {
    caregiverAccess: !!onOpenCaregiverAccess,
    link:            !!onNavigate || !!onActivated,
  };

  function setFilters(next: NotificationListFilters) {
    setActionError(null);
    if (!controlled) setOwnFilters(next);
    onFiltersChange?.(next);
  }

  function clearFilters() {
    setFilters({ unreadOnly: false, type: null });
    // The button that was pressed belongs to the empty state and goes away with it.
    if (IS_WEB) titleRef.current?.focus?.();
  }

  function activate(item: AppNotification, destination: NotificationDestination) {
    // Not saved (offline, server error): the row shows as unread again and the badge keeps counting it.
    if (!item.read) n.markRead(item.notificationId).catch(() => {});
    onActivated?.(item);
    if (destination === 'thread' && item.threadId != null) onOpenThread(item.threadId);
    else if (destination === 'caregiver-access') onOpenCaregiverAccess?.();
    else if (destination === 'link') onNavigate?.(item.actionUrl);
  }

  async function markAll() {
    if (markingAll) return;
    setMarkingAll(true);
    setActionError(null);
    try {
      await n.markAllRead();
      // The button goes away with the last unread notification; keep keyboard focus in the list.
      if (IS_WEB) titleRef.current?.focus?.();
    } catch (e) {
      setActionError(apiErrorMessage(e, 'Could not mark notifications as read. Please try again.'));
    } finally {
      setMarkingAll(false);
    }
  }

  const emptyTitle = type !== null
    ? `No ${unreadOnly ? 'unread ' : ''}${NOTIFICATION_TYPE_META[type].kind} notifications`
    : unreadOnly ? 'No unread notifications' : 'No notifications';
  const emptySub = filtered
    ? (unreadOnly && type === null ? "You're all caught up." : 'Nothing matches these filters.')
    : "You'll see new messages and updates here.";

  const typeFilter = (scrolls: boolean) => (
    <View style={[styles.filterGroup, scrolls && styles.filterGroupRow]} role="group" aria-label="Filter by type">
      <Text style={styles.filterCaption}>Type</Text>
      <FilterChip label="All types" selected={type === null} onPress={() => setFilters({ unreadOnly, type: null })} />
      {NOTIFICATION_TYPES.map(t => (
        <FilterChip
          key={t}
          label={NOTIFICATION_TYPE_META[t].label}
          // Not the bare label: several match a sidebar item ("Messages", "Billing", "Referrals").
          name={`${NOTIFICATION_TYPE_META[t].label} only`}
          selected={type === t}
          onPress={() => setFilters({ unreadOnly, type: t })}
        />
      ))}
    </View>
  );

  return (
    <ThemeContext.Provider value={theme}>
      <View style={[styles.wrap, !compact && styles.page]}>
        <View style={styles.header}>
          <Text
            ref={titleRef}
            style={[compact ? styles.panelTitle : styles.pageTitle, styles.titleFocus]}
            accessibilityRole="header"
            // The page heading on the Notifications page; in the bell's dialog it sits under the page's own heading.
            {...ariaLevel(compact ? 2 : 1)}
            {...(IS_WEB ? ({ tabIndex: -1 } as object) : null)}
          >
            Notifications
          </Text>
          <View style={styles.headerActions}>
            {hasUnread && (
              <TouchableOpacity
                onPress={markAll}
                disabled={markingAll}
                accessibilityRole="button"
                // The visible words (WCAG 2.5.3); the panel's heading says what "all" is.
                accessibilityLabel="Mark all as read"
                style={styles.linkBtn}
              >
                <Text style={[styles.link, { color: theme.accent }]}>Mark all as read</Text>
              </TouchableOpacity>
            )}
            {onClose && (
              <TouchableOpacity onPress={onClose} accessibilityRole="button" accessibilityLabel="Close notifications" style={styles.closeBtn}>
                <Ionicons aria-hidden name="close" size={18} color={GRAY} importantForAccessibility="no" />
              </TouchableOpacity>
            )}
          </View>
        </View>

        <View style={[styles.filters, compact && styles.filtersCompact]}>
          <View style={styles.filterGroup} role="group" aria-label="Show">
            {!compact && <Text style={styles.filterCaption}>Show</Text>}
            <FilterChip label="All" selected={!unreadOnly} onPress={() => setFilters({ unreadOnly: false, type })} />
            <FilterChip label="Unread" selected={unreadOnly} onPress={() => setFilters({ unreadOnly: true, type })} />
          </View>
          {!compact && (width < NARROW_MAX ? (
            <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.filterScroll}>
              {typeFilter(true)}
            </ScrollView>
          ) : typeFilter(false))}
        </View>

        {!online && <Banner tone="warning" icon="cloud-offline-outline" text="You're offline. Notifications will refresh when you reconnect." />}
        {actionError && <Banner tone="danger" icon="alert-circle-outline" text={actionError} />}

        {n.loading && n.notifications.length === 0 ? <Loading compact={compact} label="Loading notifications" /> : n.error && n.notifications.length === 0 ? (
          <ErrorBlock compact={compact} message={n.error} onRetry={n.refetch} />
        ) : n.notifications.length === 0 ? (
          <Empty icon="notifications-off-outline" title={emptyTitle} sub={emptySub}>
            {filtered && (
              <TouchableOpacity
                style={[styles.clearBtn, { borderColor: theme.accent }]}
                onPress={clearFilters}
                activeOpacity={0.8}
                accessibilityRole="button"
                accessibilityLabel="Show all notifications"
              >
                <Text style={[styles.link, { color: theme.accent }]}>Show all notifications</Text>
              </TouchableOpacity>
            )}
          </Empty>
        ) : (
          <ScrollView style={compact ? styles.panelScroll : styles.flex} contentContainerStyle={!compact && styles.pageList}>
            <View accessibilityRole="list" accessibilityLabel="Notifications">
              {n.notifications.map(item => (
                <NotificationRow
                  key={item.notificationId}
                  item={item}
                  compact={compact}
                  handlers={handlers}
                  onActivate={activate}
                />
              ))}
            </View>
            {n.loadMoreError && (
              <Text style={styles.moreError} accessibilityRole="alert">{n.loadMoreError}</Text>
            )}
            {n.hasMore && (
              <TouchableOpacity
                style={styles.moreBtn}
                onPress={n.loadMore}
                disabled={n.loadingMore}
                accessibilityRole="button"
                accessibilityLabel={n.loadMoreError ? 'Try again to load more notifications' : 'Load more notifications'}
              >
                <Text style={[styles.link, { color: theme.accent }]}>
                  {n.loadingMore ? 'Loading…' : n.loadMoreError ? 'Try again' : 'Load more'}
                </Text>
              </TouchableOpacity>
            )}
          </ScrollView>
        )}
      </View>
    </ThemeContext.Provider>
  );
}

/** A toggle in the filter bar. The pressed state is exposed as well as drawn (border, tint and weight). */
function FilterChip({ label, name, selected, onPress }: {
  label: string; name?: string; selected: boolean; onPress: () => void;
}) {
  const theme = useTheme();
  return (
    <TouchableOpacity
      style={[styles.chip, selected && { borderColor: theme.accent, backgroundColor: theme.accentBg }]}
      onPress={onPress}
      activeOpacity={0.8}
      accessibilityRole="button"
      accessibilityLabel={name ?? label}
      // aria-selected is not valid on a button, so web gets aria-pressed and native the selected state.
      {...(IS_WEB ? ({ 'aria-pressed': selected } as object) : { accessibilityState: { selected } })}
    >
      {selected && <Ionicons aria-hidden name="checkmark" size={13} color={TEXT} importantForAccessibility="no" />}
      <Text style={[styles.chipText, selected && styles.chipTextOn]}>{label}</Text>
    </TouchableOpacity>
  );
}

function NotificationRow({ item, compact, handlers, onActivate }: {
  item:       AppNotification;
  compact?:   boolean;
  handlers:   NotificationHandlers;
  onActivate: (item: AppNotification, destination: NotificationDestination) => void;
}) {
  const theme       = useTheme();
  const meta        = notificationTypeMeta(item.type);
  const emphasis    = notificationEmphasis(item.priority);
  const tag         = emphasisLabel(emphasis);
  const destination = notificationDestination(item, handlers);
  const time        = shortTime(item.createdAt);
  // A read notification with nowhere to go has nothing left to do. It stays the same element (a disabled
  // button) rather than turning into plain text, so focus is not lost at the moment it is marked read.
  const inert       = destination === null && item.read;
  const critical    = emphasis === 'critical';

  // The visible text in visible order (title, time, priority tag, message), so the name contains what is shown
  // (WCAG 2.5.3); "unread", shown only as a dot and a tint, is added last. The priority word is always part of the
  // name because colour alone must not carry it.
  const name = [item.title, time, tag, item.message].filter(Boolean).join(', ') + (item.read ? '' : ', unread');

  return (
    <View role="listitem">
      <TouchableOpacity
        style={[
          styles.row,
          !item.read && { backgroundColor: critical ? RED_BG : theme.accentBg },
          critical && styles.rowCritical,
        ]}
        onPress={() => onActivate(item, destination)}
        disabled={inert}
        activeOpacity={0.75}
        accessibilityRole="button"
        accessibilityLabel={name}
        accessibilityHint={destinationHint(destination, item.read)}
        accessibilityState={{ disabled: inert }}
      >
        <View style={[
          styles.iconWrap,
          { backgroundColor: critical ? RED : emphasis === 'urgent' ? '#FEE2E2' : meta.bg },
        ]}>
          <Ionicons aria-hidden
            name={meta.icon}
            size={15}
            color={critical ? WHITE : emphasis === 'urgent' ? RED : meta.color}
            importantForAccessibility="no"
          />
        </View>
        <View style={styles.body}>
          <View style={styles.top}>
            <Text
              style={[styles.title, !item.read && styles.titleUnread, emphasis !== null && { color: RED }]}
              numberOfLines={compact ? 1 : 2}
            >
              {item.title}
            </Text>
            <Text style={styles.time}>{time}</Text>
          </View>
          {critical ? (
            <View style={styles.criticalTag}>
              <Ionicons aria-hidden name="warning" size={11} color={WHITE} importantForAccessibility="no" />
              <Text style={styles.criticalText}>{tag}</Text>
            </View>
          ) : emphasis === 'urgent' ? <UrgentTag /> : null}
          <Text style={styles.message} numberOfLines={compact ? 2 : 4}>{item.message}</Text>
        </View>
        <View style={styles.trail}>
          {!item.read && <View style={[styles.dot, { backgroundColor: critical ? RED : theme.accent }]} {...decorative} />}
          {destination !== null && (
            <Ionicons aria-hidden name="chevron-forward" size={14} color={LIGHT} importantForAccessibility="no" />
          )}
        </View>
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:       { backgroundColor: WHITE, minWidth: 0 },
  page:       { flex: 1, backgroundColor: BG },
  flex:       { flex: 1 },
  header:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 16, paddingVertical: 12, borderBottomWidth: 1, borderBottomColor: BORDER, backgroundColor: WHITE },
  panelTitle: { fontSize: 15, fontWeight: '700', color: TEXT },
  pageTitle:  { fontSize: 20, fontWeight: '700', color: TEXT },
  titleFocus: { outlineStyle: 'none' as any }, // focused from script only, after "Mark all as read"
  headerActions: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  linkBtn:    { paddingVertical: 4, paddingHorizontal: 4 },
  closeBtn:   { padding: 4, borderRadius: 6 },
  link:       { fontSize: 12, fontWeight: '600' },
  filters:    { paddingHorizontal: 16, paddingVertical: 10, gap: 8, borderBottomWidth: 1, borderBottomColor: BORDER, backgroundColor: WHITE },
  filtersCompact: { paddingHorizontal: 14, paddingVertical: 8 },
  filterGroup:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 6 },
  filterGroupRow:{ flexWrap: 'nowrap' },
  filterScroll:  { flexGrow: 0 },
  filterCaption: { fontSize: 12, fontWeight: '600', color: GRAY, minWidth: 40 },
  chip:       { flexDirection: 'row', alignItems: 'center', gap: 4, minHeight: 30, paddingHorizontal: 11, paddingVertical: 5, borderRadius: 15, borderWidth: 1, borderColor: '#9CA3AF', backgroundColor: WHITE },
  chipText:   { fontSize: 12, fontWeight: '500', color: TEXT },
  chipTextOn: { fontWeight: '700' },
  clearBtn:   { borderWidth: 1, borderRadius: 8, paddingHorizontal: 14, paddingVertical: 7, marginTop: 4 },
  panelScroll:{ maxHeight: 420 },
  pageList:   { paddingBottom: 24 },
  row:        { flexDirection: 'row', alignItems: 'flex-start', gap: 10, paddingHorizontal: 14, paddingVertical: 11, borderBottomWidth: 1, borderBottomColor: BORDER, backgroundColor: WHITE, borderLeftWidth: 3, borderLeftColor: 'transparent' },
  rowCritical:{ borderLeftColor: RED },
  iconWrap:   { width: 30, height: 30, borderRadius: 15, alignItems: 'center', justifyContent: 'center' },
  body:       { flex: 1, minWidth: 0, gap: 3 },
  top:        { flexDirection: 'row', alignItems: 'flex-start', gap: 8 },
  title:      { flex: 1, fontSize: 13, color: TEXT, fontWeight: '500' },
  titleUnread:{ fontWeight: '700' },
  time:       { fontSize: 11, color: LIGHT },
  criticalTag:  { flexDirection: 'row', alignItems: 'center', gap: 3, backgroundColor: RED, borderRadius: 10, paddingHorizontal: 7, paddingVertical: 2, alignSelf: 'flex-start' },
  criticalText: { fontSize: 10, fontWeight: '700', color: WHITE, letterSpacing: 0.3 },
  message:    { fontSize: 12, color: GRAY, lineHeight: 17 },
  trail:      { alignItems: 'center', gap: 6, paddingTop: 4, minWidth: 14 },
  dot:        { width: 8, height: 8, borderRadius: 4 },
  moreError:  { fontSize: 12, color: RED, textAlign: 'center', paddingTop: 12, paddingHorizontal: 16 },
  moreBtn:    { alignItems: 'center', paddingVertical: 12 },
});
