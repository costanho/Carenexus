import { Ionicons } from '@expo/vector-icons';
import { Image, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useLogout } from '@/hooks/use-logout';

const BG       = '#FFFFFF';
const BORDER   = '#E5E7EB';
const TEXT     = '#111827';
const GRAY     = '#6B7280';
const INPUT_BG = '#F3F4F6';
const RED      = '#EF4444';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

function IconBtn({ name, badge }: { name: IoniconsName; badge?: string }) {
  return (
    <View style={styles.iconBtn}>
      <Ionicons name={name} size={22} color={GRAY} />
      {badge && (
        <View style={styles.badge}>
          <Text style={styles.badgeText}>{badge}</Text>
        </View>
      )}
    </View>
  );
}

export interface DoctorTopNavbarProps {
  onMenuPress?: () => void;
}

export function DoctorTopNavbar({ onMenuPress }: DoctorTopNavbarProps) {
  const { user } = useCurrentUser();
  const { handleLogout, loading } = useLogout();
  const fullName  = user ? `Dr. ${user.first_name} ${user.last_name}` : 'Doctor';
  const avatarUri = user?.avatar_url ?? null;
  const initials  = user ? `${user.first_name[0]}${user.last_name[0]}`.toUpperCase() : 'DR';

  return (
    <View style={styles.navbar}>
      {/* Left: hamburger */}
      <View style={styles.left}>
        <TouchableOpacity onPress={onMenuPress} style={styles.hamburger} activeOpacity={0.7}>
          <Ionicons name="menu-outline" size={26} color={TEXT} />
        </TouchableOpacity>
      </View>

      {/* Center: search — absolutely centered */}
      <View style={styles.center}>
        <View style={styles.searchWrapper}>
          <Ionicons name="search-outline" size={18} color={GRAY} />
          <TextInput
            style={styles.searchInput}
            placeholder="Search patients, appointments..."
            placeholderTextColor={GRAY}
          />
        </View>
      </View>

      {/* Right: icons + profile + logout */}
      <View style={styles.right}>
        <TouchableOpacity activeOpacity={0.7}>
          <IconBtn name="chatbubble-outline" badge="6" />
        </TouchableOpacity>
        <TouchableOpacity activeOpacity={0.7}>
          <IconBtn name="notifications-outline" badge="12" />
        </TouchableOpacity>

        <View style={styles.divider} />

        {/* Profile */}
        <TouchableOpacity style={styles.profile} activeOpacity={0.8}>
          {avatarUri
            ? <Image source={{ uri: avatarUri }} style={styles.avatar} />
            : <View style={[styles.avatar, styles.avatarFallback]}><Text style={styles.avatarInitials}>{initials}</Text></View>
          }
          <View>
            <Text style={styles.doctorName}>{fullName}</Text>
            <Text style={styles.doctorRole}>Doctor</Text>
          </View>
          <Ionicons name="chevron-down" size={16} color={GRAY} />
        </TouchableOpacity>

        <View style={styles.divider} />

        {/* Sign Out */}
        <TouchableOpacity
          style={styles.logoutBtn}
          onPress={handleLogout}
          activeOpacity={0.7}
          disabled={loading}
        >
          <Ionicons
            name={loading ? 'hourglass-outline' : 'log-out-outline'}
            size={18}
            color={RED}
          />
          <Text style={styles.logoutText}>{loading ? '…' : 'Sign Out'}</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  navbar: {
    height: 88,
    backgroundColor: BG,
    borderBottomWidth: 1,
    borderBottomColor: BORDER,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 24,
    gap: 16,
  },
  left: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
  },
  center: {
    flex: 2,
    alignItems: 'center',
  },
  hamburger: {
    padding: 6,
  },
  searchWrapper: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: INPUT_BG,
    borderRadius: 10,
    paddingHorizontal: 14,
    paddingVertical: 10,
    gap: 8,
    width: '100%',
    maxWidth: 480,
  },
  searchInput: {
    flex: 1,
    fontSize: 14,
    color: TEXT,
    outlineStyle: 'none',
  } as any,
  right: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-end',
    gap: 4,
  },
  iconBtn: {
    width: 40,
    height: 40,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
  },
  badge: {
    position: 'absolute',
    top: 4,
    right: 4,
    backgroundColor: RED,
    borderRadius: 8,
    minWidth: 16,
    height: 16,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 3,
  },
  badgeText: {
    fontSize: 9,
    fontWeight: '700',
    color: '#FFFFFF',
  },
  divider: {
    width: 1,
    height: 32,
    backgroundColor: BORDER,
    marginHorizontal: 8,
  },
  profile: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  avatar: {
    width: 38,
    height: 38,
    borderRadius: 19,
    backgroundColor: '#E5E7EB',
  },
  avatarFallback: {
    backgroundColor: '#0D9488',
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarInitials: {
    color: '#FFFFFF',
    fontSize: 13,
    fontWeight: '700',
  },
  doctorName: {
    fontSize: 14,
    fontWeight: '700',
    color: TEXT,
  },
  doctorRole: {
    fontSize: 12,
    color: GRAY,
  },
  logoutBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#FECACA',
    backgroundColor: '#FFF5F5',
  },
  logoutText: {
    fontSize: 13,
    fontWeight: '600',
    color: RED,
  },
});
