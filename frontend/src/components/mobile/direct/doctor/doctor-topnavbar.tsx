import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Image, StyleSheet, Text, TextInput, TouchableOpacity, useWindowDimensions, View } from 'react-native';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BG     = '#F3F4F6';
const BORDER = '#E5E7EB';
const RED    = '#EF4444';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

function IconBadge({ name, badge, iconSize, badgeSize }: {
  name: IoniconsName;
  badge: string;
  iconSize: number;
  badgeSize: number;
}) {
  return (
    <View style={styles.iconWrapper}>
      <Ionicons name={name} size={iconSize} color={GRAY} />
      <View style={[styles.badge, { minWidth: badgeSize, height: badgeSize, borderRadius: badgeSize / 2 }]}>
        <Text style={[styles.badgeText, { fontSize: badgeSize * 0.62 }]}>{badge}</Text>
      </View>
    </View>
  );
}

export interface DoctorTopNavbarMobileProps {
  onMenuPress?: () => void;
}

export function DoctorTopNavbarMobile({ onMenuPress }: DoctorTopNavbarMobileProps) {
  const insets = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();

  const s = (n: number) => Math.round(n * (width / 390));
  const h = (n: number) => Math.round(n * (height / 844));

  const iconSize   = s(22);
  const badgeSize  = s(15);
  const avatarSize = s(34);

  return (
    <View style={[styles.navbar, {
      paddingTop:    insets.top + h(10),
      paddingBottom: h(10),
      paddingLeft:   insets.left  + s(14),
      paddingRight:  insets.right + s(14),
      gap:           s(10),
    }]}>
      {/* Hamburger */}
      <TouchableOpacity onPress={onMenuPress} activeOpacity={0.7} style={{ padding: s(4) }}>
        <Ionicons name="menu-outline" size={s(26)} color={TEXT} />
      </TouchableOpacity>

      {/* Search bar */}
      <View style={[styles.searchBar, {
        borderRadius: s(10),
        paddingHorizontal: s(10),
        paddingVertical: h(7),
        gap: s(6),
      }]}>
        <Ionicons name="search-outline" size={s(16)} color={GRAY} />
        <TextInput
          style={[styles.searchInput, { fontSize: s(13) }]}
          placeholder="Search..."
          placeholderTextColor={GRAY}
        />
      </View>

      {/* Right icons */}
      <View style={[styles.rightSection, { gap: s(6) }]}>
        <TouchableOpacity activeOpacity={0.7}>
          <IconBadge name="chatbubble-outline"    badge="6"  iconSize={iconSize} badgeSize={badgeSize} />
        </TouchableOpacity>
        <TouchableOpacity activeOpacity={0.7}>
          <IconBadge name="notifications-outline" badge="12" iconSize={iconSize} badgeSize={badgeSize} />
        </TouchableOpacity>

        <View style={[styles.divider, { height: s(24) }]} />

        <TouchableOpacity style={[styles.profileRow, { gap: s(6) }]} activeOpacity={0.8}>
          <Image
            source={{ uri: 'https://i.pravatar.cc/150?img=12' }}
            style={{ width: avatarSize, height: avatarSize, borderRadius: avatarSize / 2, backgroundColor: BORDER }}
          />
          <View>
            <Text style={[styles.doctorName, { fontSize: s(12) }]} numberOfLines={1}>Dr. Matt</Text>
            <Text style={[styles.doctorRole, { fontSize: s(10) }]} numberOfLines={1}>Physician</Text>
          </View>
          <Ionicons name="chevron-down" size={s(12)} color={GRAY} />
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
  },
  searchBar: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: BG,
  },
  searchInput: {
    flex: 1,
    color: TEXT,
  },
  rightSection: {
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
    backgroundColor: RED,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 2,
  },
  badgeText: {
    color: WHITE,
    fontWeight: '700',
  },
  divider: {
    width: StyleSheet.hairlineWidth,
    backgroundColor: BORDER,
  },
  profileRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  doctorName: {
    fontWeight: '700',
    color: TEXT,
  },
  doctorRole: {
    color: GRAY,
  },
});
