import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Image, StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useLogout } from '@/hooks/use-logout';
import { MobileNotificationBell } from '../../notifications/mobile-notification-bell';

const TEXT  = '#111827';
const GRAY  = '#6B7280';
const WHITE = '#FFFFFF';
const RED   = '#EF4444';
const TEAL  = '#0D9488';

export function PatientTopNavbar({ onProfilePress, onOpenNotifications }: {
  onProfilePress?: () => void;
  onOpenNotifications?: () => void;
} = {}) {
  const insets             = useSafeAreaInsets();
  const { width, height }  = useWindowDimensions();
  const { user, loading: userLoading } = useCurrentUser();
  const { handleLogout, loading } = useLogout();

  const s = (n: number) => Math.round(n * (width / 390));
  const h = (n: number) => Math.round(n * (height / 844));

  const avatarSize = s(36);

  const firstName = user?.first_name ?? (userLoading ? '...' : 'there');
  const avatarUri = user?.avatar_url ?? null;

  return (
    <View style={[styles.navbar, {
      paddingTop:    insets.top + h(14),
      paddingBottom: h(14),
      paddingLeft:   insets.left  + s(16),
      paddingRight:  insets.right + s(16),
      gap:           s(8),
    }]}>
      {/* Left: greeting */}
      <View style={styles.left}>
        <Text style={[styles.title, { fontSize: s(15) }]} numberOfLines={1}>
          Welcome back, {firstName}!
        </Text>
        <Text style={[styles.subtitle, { fontSize: s(11), marginTop: s(2) }]} numberOfLines={1}>
          Here's your health overview.
        </Text>
      </View>

      {/* Right: avatar + sign out */}
      <View style={[styles.right, { gap: s(10) }]}>
        {onOpenNotifications && <MobileNotificationBell onPress={onOpenNotifications} size={s(22)} />}
        {/* Avatar */}
        <TouchableOpacity
          style={[styles.userRow, { gap: s(4) }]}
          activeOpacity={0.7}
          onPress={onProfilePress}
          disabled={!onProfilePress}
          accessibilityRole="button"
          accessibilityLabel="Open profile"
        >
          {avatarUri ? (
            <Image
              source={{ uri: avatarUri }}
              style={{ width: avatarSize, height: avatarSize, borderRadius: avatarSize / 2, backgroundColor: '#E5E7EB' }}
            />
          ) : (
            <View style={[styles.avatarFallback, { width: avatarSize, height: avatarSize, borderRadius: avatarSize / 2 }]}>
              <Text style={[styles.avatarInitial, { fontSize: s(14) }]}>
                {user?.first_name?.[0] ?? '?'}
              </Text>
            </View>
          )}
        </TouchableOpacity>

        <View style={[styles.divider, { height: s(26) }]} />

        {/* Sign Out */}
        <TouchableOpacity
          style={[styles.logoutBtn, { borderRadius: s(8), paddingHorizontal: s(10), paddingVertical: h(7), gap: s(4) }]}
          onPress={handleLogout}
          activeOpacity={0.7}
          disabled={loading}
          accessibilityRole="button"
          accessibilityLabel="Sign out"
          accessibilityState={{ disabled: loading }}
        >
          <Ionicons
            name={loading ? 'hourglass-outline' : 'log-out-outline'}
            size={s(17)}
            color={RED}
          />
          <Text style={[styles.logoutText, { fontSize: s(11) }]}>
            {loading ? '…' : 'Out'}
          </Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  navbar: {
    backgroundColor: WHITE,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#E5E7EB',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  left:     { flex: 1 },
  title:    { fontWeight: '700', color: TEXT },
  subtitle: { color: GRAY },
  right:    { flexDirection: 'row', alignItems: 'center' },

  divider:        { width: StyleSheet.hairlineWidth, backgroundColor: '#E5E7EB' },
  userRow:        { flexDirection: 'row', alignItems: 'center' },
  avatarFallback: { backgroundColor: TEAL, alignItems: 'center', justifyContent: 'center' },
  avatarInitial:  { color: WHITE, fontWeight: '700' },

  logoutBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#FECACA',
    backgroundColor: '#FFF5F5',
  },
  logoutText: {
    fontWeight: '600',
    color: RED,
  },
});
