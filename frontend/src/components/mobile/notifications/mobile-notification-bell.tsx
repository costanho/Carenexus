import { Ionicons } from '@expo/vector-icons';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useUnreadCounts } from '@/hooks/use-notifications';

/** Top-nav bell for phones: unread badge (shared 30 s poll); opens the notifications screen. */
export function MobileNotificationBell({ onPress, size = 22, color = '#374151' }: {
  onPress: () => void;
  size?:   number;
  color?:  string;
}) {
  const { unreadCount } = useUnreadCounts();
  const label = unreadCount > 0 ? `Notifications, ${unreadCount} unread` : 'Notifications';
  return (
    <TouchableOpacity
      style={styles.bell}
      onPress={onPress}
      activeOpacity={0.7}
      hitSlop={6}
      accessibilityRole="button"
      accessibilityLabel={label}
    >
      <Ionicons name={unreadCount > 0 ? 'notifications' : 'notifications-outline'} size={size} color={color} importantForAccessibility="no" />
      <UnreadDot count={unreadCount} />
    </TouchableOpacity>
  );
}

/** Small red count for an icon (bell, Messages tab). Hidden from screen readers: the owner's label says it. */
export function UnreadDot({ count, style }: { count: number; style?: object }) {
  if (count <= 0) return null;
  return (
    <View style={[styles.badge, style]} importantForAccessibility="no-hide-descendants" accessibilityElementsHidden>
      <Text style={styles.badgeText}>{count > 99 ? '99+' : count}</Text>
    </View>
  );
}

/** Unread message count for a "Messages" tab (shares the bell's poll). */
export function useUnreadMessageCount(): number {
  return useUnreadCounts().unreadMessageCount;
}

const styles = StyleSheet.create({
  bell:  { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center' },
  badge: {
    position: 'absolute', top: 2, right: 0,
    minWidth: 18, height: 18, borderRadius: 9, paddingHorizontal: 4,
    backgroundColor: '#DC2626', alignItems: 'center', justifyContent: 'center',
    borderWidth: 1.5, borderColor: '#FFFFFF',
  },
  badgeText: { color: '#FFFFFF', fontSize: 10, fontWeight: '700' },
});
