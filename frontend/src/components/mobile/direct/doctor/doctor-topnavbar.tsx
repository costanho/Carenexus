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
const RED    = '#EF4444';

export interface DoctorTopNavbarMobileProps {
  onMenuPress?:    () => void;
  onProfilePress?: () => void;
  onOpenNotifications?: () => void;
}

export function DoctorTopNavbarMobile({ onMenuPress, onProfilePress, onOpenNotifications }: DoctorTopNavbarMobileProps) {
  const insets = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  const { user } = useCurrentUser();
  const { handleLogout, loading } = useLogout();

  const avatarUri = user?.avatar_url ?? null;
  const initials  = user ? `${user.first_name[0] ?? ''}${user.last_name[0] ?? ''}`.toUpperCase() || 'DR' : 'DR';

  const s = (n: number) => Math.round(n * (width / 390));
  const h = (n: number) => Math.round(n * (height / 844));

  const avatarSize = s(32);
  const fullName   = user ? `Dr. ${user.first_name} ${user.last_name}`.trim() : 'Doctor';

  return (
    <View style={[styles.navbar, {
      paddingTop:    insets.top + h(10),
      paddingBottom: h(10),
      paddingLeft:   insets.left  + s(14),
      paddingRight:  insets.right + s(14),
      gap:           s(8),
    }]}>
      {/* Hamburger */}
      {!!onMenuPress && (
        <TouchableOpacity
          onPress={onMenuPress}
          activeOpacity={0.7}
          style={{ padding: s(4) }}
          accessibilityRole="button"
          accessibilityLabel="Open menu"
        >
          <Ionicons name="menu-outline" size={s(24)} color={TEXT} />
        </TouchableOpacity>
      )}

      {/* Brand */}
      <Text style={[styles.brand, { fontSize: s(17) }]} accessibilityRole="header">
        Care<Text style={styles.brandAccent}>Nexus</Text>
      </Text>

      {/* Right icons */}
      <View style={[styles.rightSection, { gap: s(6) }]}>
        {onOpenNotifications && <MobileNotificationBell onPress={onOpenNotifications} size={s(21)} />}
        {/* Avatar */}
        <TouchableOpacity
          style={[styles.profileRow, { gap: s(4) }]}
          activeOpacity={0.8}
          onPress={onProfilePress}
          disabled={!onProfilePress}
          accessibilityRole="button"
          accessibilityLabel={`${fullName}, open profile`}
        >
          {avatarUri
            ? <Image source={{ uri: avatarUri }} style={{ width: avatarSize, height: avatarSize, borderRadius: avatarSize / 2, backgroundColor: BORDER }} />
            : <View style={[{ width: avatarSize, height: avatarSize, borderRadius: avatarSize / 2 }, styles.avatarFallback]}>
                <Text style={[styles.avatarInitials, { fontSize: s(10) }]}>{initials}</Text>
              </View>
          }
        </TouchableOpacity>

        <View style={[styles.divider, { height: s(22) }]} />

        {/* Sign Out */}
        <TouchableOpacity
          style={[styles.logoutBtn, { borderRadius: s(8), paddingHorizontal: s(10), paddingVertical: h(7), gap: s(4) }]}
          onPress={handleLogout}
          activeOpacity={0.7}
          disabled={loading}
          accessibilityRole="button"
          accessibilityLabel="Sign out"
          accessibilityState={{ disabled: loading, busy: loading }}
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
  brand: {
    flex: 1,
    fontWeight: '800',
    color: TEXT,
  },
  brandAccent: {
    color: '#4F46E5',
  },
  rightSection: {
    flexDirection: 'row',
    alignItems: 'center',
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
