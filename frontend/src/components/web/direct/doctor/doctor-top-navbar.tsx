import { Ionicons } from '@expo/vector-icons';
import { Image, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useLogout } from '@/hooks/use-logout';

const BG       = '#FFFFFF';
const BORDER   = '#E5E7EB';
const TEXT     = '#111827';
const GRAY     = '#6B7280';
const RED      = '#EF4444';

export interface DoctorTopNavbarProps {
  onMenuPress?:    () => void;
  onProfilePress?: () => void;
}

export function DoctorTopNavbar({ onMenuPress, onProfilePress }: DoctorTopNavbarProps) {
  const { user } = useCurrentUser();
  const { handleLogout, loading } = useLogout();
  const fullName  = user ? `Dr. ${user.first_name} ${user.last_name}`.trim() : 'Doctor';
  const avatarUri = user?.avatar_url ?? null;
  const initials  = user ? `${user.first_name[0] ?? ''}${user.last_name[0] ?? ''}`.toUpperCase() || 'DR' : 'DR';

  return (
    <View style={styles.navbar}>
      {/* Left: hamburger */}
      <View style={styles.left}>
        {!!onMenuPress && (
          <TouchableOpacity
            onPress={onMenuPress}
            style={styles.hamburger}
            activeOpacity={0.7}
            accessibilityRole="button"
            accessibilityLabel="Open menu"
          >
            <Ionicons name="menu-outline" size={26} color={TEXT} />
          </TouchableOpacity>
        )}
      </View>

      {/* Right: profile + logout */}
      <View style={styles.right}>
        {/* Profile */}
        <TouchableOpacity
          style={styles.profile}
          activeOpacity={0.8}
          onPress={onProfilePress}
          disabled={!onProfilePress}
          accessibilityRole="button"
          accessibilityLabel={`${fullName}, open profile`}
        >
          {avatarUri
            ? <Image source={{ uri: avatarUri }} style={styles.avatar} />
            : <View style={[styles.avatar, styles.avatarFallback]}><Text style={styles.avatarInitials}>{initials}</Text></View>
          }
          <View>
            <Text style={styles.doctorName}>{fullName}</Text>
            <Text style={styles.doctorRole}>Doctor</Text>
          </View>
          {!!onProfilePress && <Ionicons name="chevron-forward" size={16} color={GRAY} />}
        </TouchableOpacity>

        <View style={styles.divider} />

        {/* Sign Out */}
        <TouchableOpacity
          style={styles.logoutBtn}
          onPress={handleLogout}
          activeOpacity={0.7}
          disabled={loading}
          accessibilityRole="button"
          accessibilityLabel="Sign out"
          accessibilityState={{ disabled: loading, busy: loading }}
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
  hamburger: {
    padding: 6,
  },
  right: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-end',
    gap: 4,
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
