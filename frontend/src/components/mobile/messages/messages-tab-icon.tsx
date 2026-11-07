import type { ReactNode } from 'react';
import { StyleSheet, View } from 'react-native';
import { UnreadDot, useUnreadMessageCount } from '../notifications/mobile-notification-bell';

/** Accessible name for a bottom tab: "Messages, 3 unread" for the Messages tab, the label otherwise. */
export function useTabLabel(): (key: string, label: string) => string {
  const count = useUnreadMessageCount();
  return (key, label) => (key === 'messages' && count > 0 ? `${label}, ${count} unread` : label);
}

/** Wraps a bottom-tab icon; for the Messages tab (`show`) it adds the unread message count. */
export function MessagesTabIcon({ show, children }: { show: boolean; children: ReactNode }) {
  if (!show) return <>{children}</>;
  return <WithBadge>{children}</WithBadge>;
}

function WithBadge({ children }: { children: ReactNode }) {
  const count = useUnreadMessageCount();
  return (
    <View style={styles.wrap}>
      {children}
      <UnreadDot count={count} style={styles.dot} />
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { position: 'relative' },
  dot:  { top: -6, right: -12 },
});
