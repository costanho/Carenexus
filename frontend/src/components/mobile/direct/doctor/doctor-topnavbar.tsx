import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Image, StyleSheet, Text, TextInput, TouchableOpacity, useWindowDimensions, View } from 'react-native';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useLogout } from '@/hooks/use-logout';

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
  const { user } = useCurrentUser();
  const { handleLogout, loading } = useLogout();

  const avatarUri = user?.avatar_url ?? null;
  const initials  = user ? `${user.first_name[0]}${user.last_name[0]}`.toUpperCase() : 'DR';

  const s = (n: number) => Math.round(n * (width / 390));
  const h = (n: number) => Math.round(n * (height / 844));

  const iconSize   = s(20);
  const badgeSize  = s(14);
  const avatarSize = s(32);

  return (
    <View style={[styles.navbar, {
      paddingTop:    insets.top + h(10),
      paddingBottom: h(10),
      paddingLeft:   insets.left  + s(14),
      paddingRight:  insets.right + s(14),
      gap:           s(8),
    }]}>
      {/* Hamburger */}
      <TouchableOpacity onPress={onMenuPress} activeOpacity={0.7} style={{ padding: s(4) }}>
        <Ionicons name="menu-outline" size={s(24)} color={TEXT} />
      </TouchableOpacity>

      {/* Search bar */}
      <View style={[styles.searchBar, {
        borderRadius: s(10),
        paddingHorizontal: s(10),
        paddingVertical: h(7),
        gap: s(6),
      }]}>
        <Ionicons name="search-outline" size={s(15)} color={GRAY} />
        <TextInput
          style={[styles.searchInput, { fontSize: s(13) }]}
          placeholder="Search..."
          placeholderTextColor={GRAY}
        />
      </View>

      {/* Right icons */}
      <View style={[styles.rightSection, { gap: s(6) }]}>
        <TouchableOpacity activeOpacity={0.7}>
          <IconBadge name="notifications-outline" badge="12" iconSize={iconSize} badgeSize={badgeSize} />
        </TouchableOpacity>

        <View style={[styles.divider, { height: s(22) }]} />

        {/* Avatar */}
        <TouchableOpacity style={[styles.profileRow, { gap: s(4) }]} activeOpacity={0.8}>
          {avatarUri
            ? <Image source={{ uri: avatarUri }} style={{ width: avatarSize, height: avatarSize, borderRadius: avatarSize / 2, backgroundColor: BORDER }} />
            : <View style={[{ width: avatarSize, height: avatarSize, borderRadius: avatarSize / 2 }, styles.avatarFallback]}>
                <Text style={[styles.avatarInitials, { fontSize: s(10) }]}>{initials}</Text>
              </View>
          }
          <Ionicons name="chevron-down" size={s(12)} color={GRAY} />
        </TouchableOpacity>

        <View style={[styles.divider, { height: s(22) }]} />

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
  avatarFallback: {
    backgroundColor: '#0D9488',
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarInitials: {
    color: WHITE,
    fontWeight: '700',
  },
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
