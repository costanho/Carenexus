import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Image, StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useLogout } from '@/hooks/use-logout';

const TEXT  = '#111827';
const GRAY  = '#6B7280';
const WHITE = '#FFFFFF';
const RED   = '#EF4444';
const TEAL  = '#0D9488';

export function PatientTopNavbar() {
  const insets             = useSafeAreaInsets();
  const { width, height }  = useWindowDimensions();
  const { user, loading: userLoading } = useCurrentUser();
  const { handleLogout, loading } = useLogout();

  const s = (n: number) => Math.round(n * (width / 390));
  const h = (n: number) => Math.round(n * (height / 844));

  const iconSize   = s(22);
  const avatarSize = s(36);
  const badgeSize  = s(14);

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

      {/* Right: bell + avatar + sign out */}
      <View style={[styles.right, { gap: s(10) }]}>
        <BellIcon count={3} iconSize={iconSize} badgeSize={badgeSize} />

        <View style={[styles.divider, { height: s(26) }]} />

        {/* Avatar */}
        <TouchableOpacity style={[styles.userRow, { gap: s(4) }]} activeOpacity={0.7}>
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
          <Ionicons name="chevron-down" size={s(12)} color={GRAY} />
        </TouchableOpacity>

        <View style={[styles.divider, { height: s(26) }]} />

        {/* Sign Out */}
        <TouchableOpacity
          style={[styles.logoutBtn, { borderRadius: s(8), paddingHorizontal: s(10), paddingVertical: h(7), gap: s(4) }]}
          onPress={handleLogout}
          activeOpacity={0.7}
          disabled={loading}
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

function BellIcon({ count, iconSize, badgeSize }: { count: number; iconSize: number; badgeSize: number }) {
  return (
    <View style={styles.bellWrapper}>
      <Ionicons name="notifications-outline" size={iconSize} color={GRAY} />
      <View style={[styles.badge, { minWidth: badgeSize, height: badgeSize, borderRadius: badgeSize / 2 }]}>
        <Text style={[styles.badgeText, { fontSize: badgeSize * 0.65 }]}>{count}</Text>
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

  bellWrapper: { position: 'relative', alignItems: 'center', justifyContent: 'center' },
  badge: {
    position: 'absolute', top: 0, right: 0,
    backgroundColor: RED,
    alignItems: 'center', justifyContent: 'center',
    paddingHorizontal: 2,
  },
  badgeText: { color: WHITE, fontWeight: '700' },

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
