import { Ionicons } from '@expo/vector-icons';
import { useEffect, useState } from 'react';
import { Platform, StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';
import { useUnreadCounts } from '@/hooks/use-notifications';
import { DEFAULT_THEME, type MessagesTheme } from '../messages/ui';
import { NotificationList } from './notification-list';

interface Props {
  theme?:       MessagesTheme;
  /** Open the messages section on this thread. */
  onOpenThread: (threadId: number) => void;
  /** Optional dark style (e.g. on a dark navbar). */
  iconColor?:   string;
}

/** Top-navbar bell: unread badge (polled every 30 s while active) and a dropdown list. */
export function NotificationBell({ theme = DEFAULT_THEME, onOpenThread, iconColor = '#374151' }: Props) {
  const { width } = useWindowDimensions();
  const { unreadCount } = useUnreadCounts();
  const [open, setOpen] = useState(false);
  const narrow = width < 520;

  // Escape closes the panel on web.
  useEffect(() => {
    if (!open || Platform.OS !== 'web' || typeof document === 'undefined') return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open]);

  const label = unreadCount > 0 ? `Notifications, ${unreadCount} unread` : 'Notifications';

  return (
    <View style={styles.anchor}>
      <TouchableOpacity
        style={styles.bell}
        onPress={() => setOpen(o => !o)}
        activeOpacity={0.7}
        accessibilityRole="button"
        accessibilityLabel={label}
        accessibilityState={{ expanded: open }}
        aria-expanded={open}
      >
        <Ionicons name={unreadCount > 0 ? 'notifications' : 'notifications-outline'} size={21} color={iconColor} importantForAccessibility="no" />
        {unreadCount > 0 && (
          <View style={styles.badge} importantForAccessibility="no-hide-descendants">
            <Text style={styles.badgeText}>{unreadCount > 99 ? '99+' : unreadCount}</Text>
          </View>
        )}
      </TouchableOpacity>

      {open && (
        <>
          {/* Click-away layer */}
          <TouchableOpacity
            style={styles.backdrop}
            onPress={() => setOpen(false)}
            accessibilityRole="button"
            accessibilityLabel="Close notifications"
            activeOpacity={1}
          />
          <View
            style={[styles.panel, narrow ? styles.panelNarrow : styles.panelWide]}
            accessibilityViewIsModal
          >
            <NotificationList
              compact
              theme={theme}
              onActivated={() => setOpen(false)}
              onOpenThread={onOpenThread}
            />
          </View>
        </>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  anchor: { position: 'relative', zIndex: 50 },
  bell:   { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center' },
  badge: {
    position: 'absolute', top: 3, right: 2,
    minWidth: 17, height: 17, borderRadius: 9, paddingHorizontal: 4,
    backgroundColor: '#DC2626', alignItems: 'center', justifyContent: 'center',
    borderWidth: 1.5, borderColor: '#FFFFFF',
  },
  badgeText: { color: '#FFFFFF', fontSize: 9, fontWeight: '700' },
  backdrop: {
    position: 'fixed' as any, top: 0, left: 0, right: 0, bottom: 0,
    backgroundColor: 'transparent', cursor: 'default' as any,
  },
  panel: {
    position: 'absolute', top: 46,
    backgroundColor: '#FFFFFF', borderRadius: 12, borderWidth: 1, borderColor: '#E5E7EB',
    shadowColor: '#000', shadowOffset: { width: 0, height: 6 }, shadowOpacity: 0.12, shadowRadius: 18, elevation: 12,
    overflow: 'hidden',
  },
  panelWide:   { right: 0, width: 380 },
  panelNarrow: { position: 'fixed' as any, top: 70, left: 12, right: 12 },
});
