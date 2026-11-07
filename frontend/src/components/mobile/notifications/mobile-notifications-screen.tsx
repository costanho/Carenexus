import { StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { BG, type MessagesTheme } from '@/components/web/messages/ui';
import { NotificationList } from '@/components/web/notifications/notification-list';

/**
 * Notifications on a phone: the shared list (same copy and read rules as web), full screen.
 * A message notification opens its conversation; a caregiver approval request opens caregiver access.
 */
export function MobileNotificationsScreen({ theme, onOpenThread, onOpenCaregiverAccess }: {
  theme?:                 MessagesTheme;
  onOpenThread:           (threadId: number) => void;
  onOpenCaregiverAccess?: () => void;
}) {
  const insets = useSafeAreaInsets();
  return (
    <View style={[styles.wrap, { paddingTop: insets.top }]}>
      <NotificationList theme={theme} onOpenThread={onOpenThread} onOpenCaregiverAccess={onOpenCaregiverAccess} />
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { flex: 1, backgroundColor: BG },
});
