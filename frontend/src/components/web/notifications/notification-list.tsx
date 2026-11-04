import { Ionicons } from '@expo/vector-icons';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useNotifications } from '@/hooks/use-notifications';
import { usePageActive } from '@/hooks/use-page-active';
import { shortTime } from '@/lib/communications/format';
import type { AppNotification } from '@/lib/communications/types';
import { showMessage } from '@/lib/ui/dialogs';
import { apiErrorMessage } from '@/hooks/use-appointments';
import {
  BG, BORDER, Banner, DEFAULT_THEME, Empty, ErrorBlock, GRAY, LIGHT, Loading, type MessagesTheme, RED, TEXT,
  ThemeContext, WHITE,
} from '../messages/ui';

interface Props {
  theme?:       MessagesTheme;
  /** Called for a MESSAGE notification with a thread. */
  onOpenThread: (threadId: number) => void;
  /** Called after any notification is activated (e.g. close a dropdown). */
  onActivated?: () => void;
  /** Compact = dropdown panel; otherwise a full page section with a title. */
  compact?:     boolean;
}

export function NotificationList({ theme = DEFAULT_THEME, onOpenThread, onActivated, compact }: Props) {
  const { online } = usePageActive();
  const n = useNotifications();
  const unread = n.notifications.filter(x => !x.read).length;

  async function activate(item: AppNotification) {
    if (!item.read) n.markRead(item.notificationId).catch(() => {});
    onActivated?.();
    if (item.type === 'MESSAGE' && item.threadId != null) onOpenThread(item.threadId);
  }

  async function markAll() {
    try {
      await n.markAllRead();
    } catch (e) {
      showMessage('Could not update notifications', apiErrorMessage(e, 'Please try again.'));
    }
  }

  return (
    <ThemeContext.Provider value={theme}>
      <View style={[styles.wrap, !compact && styles.page]}>
        <View style={styles.header}>
          <Text style={compact ? styles.panelTitle : styles.pageTitle} accessibilityRole="header">Notifications</Text>
          {unread > 0 && (
            <TouchableOpacity onPress={markAll} accessibilityRole="button" accessibilityLabel="Mark all notifications as read" style={styles.linkBtn}>
              <Text style={[styles.link, { color: theme.accent }]}>Mark all as read</Text>
            </TouchableOpacity>
          )}
        </View>
        {!online && <Banner tone="warning" icon="cloud-offline-outline" text="You're offline. Notifications will refresh when you reconnect." />}

        {n.loading && n.notifications.length === 0 ? <Loading compact={compact} label="Loading notifications" /> : n.error && n.notifications.length === 0 ? (
          <ErrorBlock compact={compact} message={n.error} onRetry={n.refetch} />
        ) : n.notifications.length === 0 ? (
          <Empty icon="notifications-off-outline" title="No notifications" sub="You'll see new messages and updates here." />
        ) : (
          <ScrollView style={compact ? styles.panelScroll : styles.flex} contentContainerStyle={!compact && styles.pageList}>
            <View accessibilityRole="list" accessibilityLabel="Notifications">
              {n.notifications.map(item => {
                const urgent = item.priority === 'HIGH';
                return (
                  <View key={item.notificationId} role="listitem">
                  <TouchableOpacity
                    style={[styles.row, !item.read && { backgroundColor: theme.accentBg }]}
                    onPress={() => activate(item)}
                    activeOpacity={0.75}
                    accessibilityRole="button"
                    accessibilityLabel={`${item.title}, ${shortTime(item.createdAt)}, ${item.message}${item.read ? '' : ', unread'}`}
                    accessibilityHint={item.type === 'MESSAGE' && item.threadId != null ? 'Opens the conversation' : undefined}
                  >
                    <View style={[styles.iconWrap, urgent && styles.iconUrgent]}>
                      <Ionicons
                        name={item.type === 'MESSAGE' ? 'chatbubble-outline' : 'notifications-outline'}
                        size={15}
                        color={urgent ? RED : theme.accent}
                        importantForAccessibility="no"
                      />
                    </View>
                    <View style={styles.body}>
                      <View style={styles.top}>
                        <Text style={[styles.title, !item.read && styles.titleUnread, urgent && { color: RED }]} numberOfLines={1}>{item.title}</Text>
                        <Text style={styles.time}>{shortTime(item.createdAt)}</Text>
                      </View>
                      <Text style={styles.message} numberOfLines={2}>{item.message}</Text>
                    </View>
                    {!item.read && <View style={[styles.dot, { backgroundColor: theme.accent }]} />}
                  </TouchableOpacity>
                  </View>
                );
              })}
            </View>
            {n.hasMore && (
              <TouchableOpacity style={styles.moreBtn} onPress={n.loadMore} disabled={n.loadingMore} accessibilityRole="button" accessibilityLabel="Load more notifications">
                <Text style={[styles.link, { color: theme.accent }]}>{n.loadingMore ? 'Loading…' : 'Load more'}</Text>
              </TouchableOpacity>
            )}
          </ScrollView>
        )}
      </View>
    </ThemeContext.Provider>
  );
}

const styles = StyleSheet.create({
  wrap:       { backgroundColor: WHITE, minWidth: 0 },
  page:       { flex: 1, backgroundColor: BG },
  flex:       { flex: 1 },
  header:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 16, paddingVertical: 12, borderBottomWidth: 1, borderBottomColor: BORDER, backgroundColor: WHITE },
  panelTitle: { fontSize: 15, fontWeight: '700', color: TEXT },
  pageTitle:  { fontSize: 20, fontWeight: '700', color: TEXT },
  linkBtn:    { paddingVertical: 4, paddingHorizontal: 4 },
  link:       { fontSize: 12, fontWeight: '600' },
  panelScroll:{ maxHeight: 420 },
  pageList:   { paddingBottom: 24 },
  row:        { flexDirection: 'row', alignItems: 'flex-start', gap: 10, paddingHorizontal: 14, paddingVertical: 11, borderBottomWidth: 1, borderBottomColor: BORDER, backgroundColor: WHITE },
  iconWrap:   { width: 30, height: 30, borderRadius: 15, backgroundColor: '#F3F4F6', alignItems: 'center', justifyContent: 'center' },
  iconUrgent: { backgroundColor: '#FEE2E2' },
  body:       { flex: 1, minWidth: 0, gap: 2 },
  top:        { flexDirection: 'row', alignItems: 'center', gap: 8 },
  title:      { flex: 1, fontSize: 13, color: TEXT, fontWeight: '500' },
  titleUnread:{ fontWeight: '700' },
  time:       { fontSize: 11, color: LIGHT },
  message:    { fontSize: 12, color: GRAY, lineHeight: 17 },
  dot:        { width: 8, height: 8, borderRadius: 4, marginTop: 6 },
  moreBtn:    { alignItems: 'center', paddingVertical: 12 },
});
