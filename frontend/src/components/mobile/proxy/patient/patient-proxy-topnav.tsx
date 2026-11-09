import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Image, StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useLogout } from '@/hooks/use-logout';
import { MobileNotificationBell } from '../../notifications/mobile-notification-bell';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const RED    = '#B91C1C';

const AVATAR_COLORS = ['#4F46E5', '#0F766E', '#B45309', '#B91C1C', '#6D28D9', '#047857', '#0369A1'];
function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}
function greeting() {
  const hr = new Date().getHours();
  if (hr < 12) return 'Good morning';
  if (hr < 17) return 'Good afternoon';
  return 'Good evening';
}

export function PatientProxyTopNavMobile({ onOpenNotifications }: { onOpenNotifications?: () => void } = {}) {
  const { user }   = useCurrentUser();
  const insets     = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  // Inline button (no dropdown): a menu drawn outside the navbar's bounds does not receive touches on Android.
  const { handleLogout, loading: loggingOut } = useLogout();

  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));

  const avatarSize = s(34);

  const firstName = user?.first_name ?? '';
  const lastName  = user?.last_name  ?? '';
  const fullName  = user ? `${firstName} ${lastName}`.trim() : '—';
  const avatarUrl = user?.avatar_url ?? null;
  const ac        = avatarColor(fullName);

  return (
    <View style={[styles.navbar, {
      paddingTop:    insets.top + sh(10),
      paddingBottom: sh(14),
      paddingLeft:   insets.left  + s(16),
      paddingRight:  insets.right + s(16),
      gap:           s(8),
    }]}>
      {/* Left: greeting */}
      <View style={styles.left}>
        <Text style={[styles.greeting, { fontSize: s(15) }]} numberOfLines={1} accessibilityRole="header">
          {greeting()}, {firstName || '…'}!
        </Text>
        <Text style={[styles.subtitle, { fontSize: s(11), marginTop: s(2) }]} numberOfLines={1}>
          Here's how your loved ones are doing today.
        </Text>
      </View>

      {/* Right: avatar + sign out */}
      <View style={[styles.right, { gap: s(10) }]}>
        {onOpenNotifications && <MobileNotificationBell onPress={onOpenNotifications} size={s(22)} />}
        <View
          style={[styles.profileRow, { gap: s(6) }]}
          accessible
          accessibilityLabel={`${fullName}, proxy or caregiver`}
        >
          {avatarUrl ? (
            <Image
              source={{ uri: avatarUrl }}
              style={{ width: avatarSize, height: avatarSize, borderRadius: avatarSize / 2, backgroundColor: BORDER }}
            />
          ) : (
            <View style={{
              width: avatarSize, height: avatarSize, borderRadius: avatarSize / 2,
              backgroundColor: ac, alignItems: 'center', justifyContent: 'center',
            }}>
              <Text style={{ color: WHITE, fontSize: s(12), fontWeight: '700' }}>
                {`${firstName[0] ?? ''}${lastName[0] ?? ''}`.toUpperCase()}
              </Text>
            </View>
          )}
        </View>

        <View style={[styles.divider, { height: s(26) }]} />

        <TouchableOpacity
          style={[styles.logoutBtn, { borderRadius: s(8), paddingHorizontal: s(10), paddingVertical: sh(7), gap: s(4) }]}
          onPress={handleLogout}
          activeOpacity={0.7}
          disabled={loggingOut}
          accessibilityRole="button"
          accessibilityLabel="Sign out"
          accessibilityState={{ disabled: loggingOut, busy: loggingOut }}
        >
          <Ionicons
            name={loggingOut ? 'hourglass-outline' : 'log-out-outline'}
            size={s(17)}
            color={RED}
            importantForAccessibility="no"
          />
          <Text style={[styles.logoutText, { fontSize: s(11) }]} numberOfLines={1}>
            {loggingOut ? '…' : 'Out'}
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
    borderBottomColor: BORDER,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  left:     { flex: 1, minWidth: 0 },
  greeting: { fontWeight: '700', color: TEXT },
  subtitle: { color: GRAY },
  right: {
    flexDirection: 'row',
    alignItems: 'center',
    flexShrink: 0,
  },
  profileRow:  { flexDirection: 'row', alignItems: 'center' },
  divider:     { width: StyleSheet.hairlineWidth, backgroundColor: BORDER },

  logoutBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#FECACA',
    backgroundColor: '#FFF5F5',
  },
  logoutText: {
    color: RED,
    fontWeight: '600',
  },
});
