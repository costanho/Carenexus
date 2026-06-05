import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Image, StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';

const TEXT  = '#111827';
const GRAY  = '#6B7280';
const WHITE = '#FFFFFF';
const RED   = '#EF4444';

export function PatientTopNavbar() {
  const insets       = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();

  // All sizes derived from screen dimensions — no fixed pixels
  const s = (n: number) => Math.round(n * (width / 390));   // scale to screen width
  const h = (n: number) => Math.round(n * (height / 844));  // scale to screen height

  const iconSize   = s(24);
  const avatarSize = s(40);
  const badgeSize  = s(14);

  return (
    <View style={[styles.navbar, {
      paddingTop:    insets.top + h(16),
      paddingBottom: h(16),
      paddingLeft:   insets.left  + s(16),
      paddingRight:  insets.right + s(16),
      gap:           s(8),
    }]}>
      {/* Left: greeting */}
      <View style={styles.left}>
        <Text style={[styles.title, { fontSize: s(16) }]} numberOfLines={1}>
          Welcome back, Linda!
        </Text>
        <Text style={[styles.subtitle, { fontSize: s(12), marginTop: s(2) }]} numberOfLines={1}>
          Here's your health overview and latest updates.
        </Text>
      </View>

      {/* Right: bells + avatar */}
      <View style={[styles.right, { gap: s(12) }]}>
        <BellIcon count={3} iconSize={iconSize} badgeSize={badgeSize} />
        <BellIcon count={2} iconSize={iconSize} badgeSize={badgeSize} />

        <View style={[styles.divider, { height: s(28) }]} />

        <TouchableOpacity style={[styles.userRow, { gap: s(4) }]} activeOpacity={0.7}>
          <Image
            source={{ uri: 'https://i.pravatar.cc/150?img=47' }}
            style={{ width: avatarSize, height: avatarSize, borderRadius: avatarSize / 2, backgroundColor: '#E5E7EB' }}
          />
          <Ionicons name="chevron-down" size={s(14)} color={GRAY} />
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
  left:  { flex: 1 },
  title: { fontWeight: '700', color: TEXT },
  subtitle: { color: GRAY },
  right: { flexDirection: 'row', alignItems: 'center' },
  bellWrapper: { position: 'relative', alignItems: 'center', justifyContent: 'center' },
  badge: {
    position: 'absolute', top: 0, right: 0,
    backgroundColor: RED,
    alignItems: 'center', justifyContent: 'center',
    paddingHorizontal: 2,
  },
  badgeText: { color: WHITE, fontWeight: '700' },
  divider:  { width: StyleSheet.hairlineWidth, backgroundColor: '#E5E7EB' },
  userRow:  { flexDirection: 'row', alignItems: 'center' },
});
