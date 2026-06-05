import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Image, StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const PURPLE = '#7C3AED';
const BG     = '#F9FAFB';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

function IconBadge({ name, badge, iconSize, badgeSize }: {
  name: IoniconsName; badge: string; iconSize: number; badgeSize: number;
}) {
  return (
    <View style={styles.iconWrapper}>
      <Ionicons name={name} size={iconSize} color={TEXT} />
      <View style={[styles.badge, { minWidth: badgeSize, height: badgeSize, borderRadius: badgeSize / 2 }]}>
        <Text style={[styles.badgeText, { fontSize: badgeSize * 0.62 }]}>{badge}</Text>
      </View>
    </View>
  );
}

export function PatientProxyTopNavMobile() {
  const insets = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();

  const s  = (n: number) => Math.round(n * (width  / 390));
  const h  = (n: number) => Math.round(n * (height / 844));

  const iconSize   = s(22);
  const badgeSize  = s(15);
  const avatarSize = s(34);

  return (
    <View style={[styles.navbar, {
      paddingTop:    insets.top + h(10),
      paddingBottom: h(14),
      paddingLeft:   insets.left  + s(16),
      paddingRight:  insets.right + s(16),
    }]}>
      {/* Left: greeting */}
      <View style={styles.left}>
        <Text style={[styles.greeting, { fontSize: s(15) }]} numberOfLines={1}>
          Good morning, Sarah! 👋
        </Text>
        <Text style={[styles.subtitle, { fontSize: s(11), marginTop: s(2) }]} numberOfLines={1}>
          Here's how your loved ones are doing today.
        </Text>
      </View>

      {/* Right: badges + avatar */}
      <View style={[styles.right, { gap: s(10) }]}>
        <TouchableOpacity activeOpacity={0.7}>
          <IconBadge name="notifications-outline" badge="5" iconSize={iconSize} badgeSize={badgeSize} />
        </TouchableOpacity>
        <TouchableOpacity activeOpacity={0.7}>
          <IconBadge name="mail-outline" badge="3" iconSize={iconSize} badgeSize={badgeSize} />
        </TouchableOpacity>

        <View style={[styles.divider, { height: s(24) }]} />

        <TouchableOpacity style={[styles.profileRow, { gap: s(6) }]} activeOpacity={0.8}>
          <Image
            source={{ uri: 'https://i.pravatar.cc/150?img=47' }}
            style={{ width: avatarSize, height: avatarSize, borderRadius: avatarSize / 2, backgroundColor: BORDER }}
          />
          <View>
            <Text style={[styles.profileName, { fontSize: s(11) }]} numberOfLines={1}>Sarah Davis</Text>
            <Text style={[styles.profileRole, { fontSize: s(10) }]} numberOfLines={1}>Proxy (Daughter)</Text>
          </View>
          <Ionicons name="chevron-down" size={s(12)} color={GRAY} />
        </TouchableOpacity>
      </View>

      {/* Switch to another — below, full width */}
      <TouchableOpacity style={[styles.switchBtn, {
        marginTop: h(8),
        borderRadius: s(8),
        paddingHorizontal: s(10),
        paddingVertical: s(5),
        gap: s(5),
      }]} activeOpacity={0.8}>
        <Ionicons name="people-outline" size={s(13)} color={PURPLE} />
        <Text style={[styles.switchText, { fontSize: s(11) }]}>Switch to another</Text>
        <Ionicons name="chevron-down" size={s(11)} color={PURPLE} />
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  navbar: {
    backgroundColor: WHITE,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: BORDER,
    flexWrap: 'wrap',
    flexDirection: 'row',
    alignItems: 'center',
  },
  left:     { flex: 1 },
  greeting: { fontWeight: '700', color: TEXT },
  subtitle: { color: GRAY },
  right: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  iconWrapper: {
    position: 'relative',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 2,
  },
  badge: {
    position: 'absolute',
    top: 0,
    right: 0,
    backgroundColor: PURPLE,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 2,
  },
  badgeText:   { color: WHITE, fontWeight: '700' },
  divider:     { width: StyleSheet.hairlineWidth, backgroundColor: BORDER },
  profileRow:  { flexDirection: 'row', alignItems: 'center' },
  profileName: { fontWeight: '700', color: '#111827' },
  profileRole: { color: GRAY },
  switchBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1,
    borderColor: BORDER,
    backgroundColor: '#F9FAFB',
    alignSelf: 'flex-end',
  },
  switchText: { color: PURPLE, fontWeight: '600' },
});
