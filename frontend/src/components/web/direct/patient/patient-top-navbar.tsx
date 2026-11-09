import { Ionicons } from '@expo/vector-icons';
import { Image, StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useLogout } from '@/hooks/use-logout';
import { NotificationBell } from '../../notifications/notification-bell';

const TEXT  = '#111827';
const GRAY  = '#6B7280';
const WHITE = '#FFFFFF';
const RED   = '#B91C1C';

interface Props {
  showMenuButton?: boolean;
  onMenuPress?: () => void;
  onProfilePress?: () => void;
  /** Open the messages section on a thread (from a notification). */
  onOpenThread?: (threadId: number) => void;
  /** Opens the caregiver access screen (from a caregiver approval notification). */
  onOpenCaregiverAccess?: () => void;
}

export function PatientTopNavbar({ showMenuButton, onMenuPress, onProfilePress, onOpenThread, onOpenCaregiverAccess }: Props) {
  const { width } = useWindowDimensions();
  const { user, loading: userLoading } = useCurrentUser();
  const { handleLogout, loading } = useLogout();
  const isSmall = width < 768;

  const firstName = user?.first_name ?? (userLoading ? '...' : 'there');
  const fullName  = user ? `${user.first_name} ${user.last_name}` : '';
  const showName  = !isSmall && !!fullName;
  const avatarUri = user?.avatar_url ?? null;

  return (
    <View style={styles.navbar}>
      <View style={styles.left}>
        {showMenuButton && (
          <TouchableOpacity
            onPress={onMenuPress}
            style={styles.menuBtn}
            activeOpacity={0.7}
            accessibilityRole="button"
            accessibilityLabel="Open menu"
          >
            <Ionicons name="menu-outline" size={26} color={TEXT} />
          </TouchableOpacity>
        )}
        {/* Shrinks (and truncates) instead of running under the bell on a phone. */}
        <View style={styles.titleBlock}>
          <Text style={styles.title} numberOfLines={1}>Welcome back, {firstName}!</Text>
          {!isSmall && (
            <Text style={styles.subtitle}>Here's your health overview and latest updates.</Text>
          )}
        </View>
      </View>

      <View style={styles.right}>
        {onOpenThread && (
          <NotificationBell theme={{ accent: '#0F766E', accentBg: '#E8F5F4' }} onOpenThread={onOpenThread} onOpenCaregiverAccess={onOpenCaregiverAccess} />
        )}

        {/* Profile */}
        <TouchableOpacity
          style={styles.userRow}
          activeOpacity={0.7}
          onPress={onProfilePress}
          disabled={!onProfilePress}
          accessibilityRole="button"
          // The visible name must be part of the accessible name (WCAG 2.5.3 Label in Name).
          accessibilityLabel={showName ? `${fullName}, open profile and settings` : 'Open profile and settings'}
        >
          {avatarUri ? (
            <Image source={{ uri: avatarUri }} style={styles.avatar} />
          ) : (
            <View style={[styles.avatar, styles.avatarFallback]}>
              {showName
                ? <Ionicons name="person" size={16} color={WHITE} importantForAccessibility="no" />
                : <Text style={styles.avatarInitial}>{user?.first_name?.[0] ?? '?'}</Text>}
            </View>
          )}
          {showName && <Text style={styles.userName}>{fullName}</Text>}
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
          accessibilityState={{ disabled: loading }}
        >
          <Ionicons
            name={loading ? 'hourglass-outline' : 'log-out-outline'}
            size={18}
            color={RED}
          />
          {!isSmall && (
            <Text style={styles.logoutText}>{loading ? '…' : 'Sign Out'}</Text>
          )}
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  navbar: {
    zIndex: 20,
    height: 96,
    backgroundColor: WHITE,
    borderBottomWidth: 1,
    borderBottomColor: '#E5E7EB',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 24,
  },
  left: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    minWidth: 0,
  },
  menuBtn: {
    padding: 4,
  },
  titleBlock: {
    flex: 1,
    minWidth: 0,
  },
  title: {
    fontSize: 16,
    fontWeight: '700',
    color: TEXT,
  },
  subtitle: {
    fontSize: 13,
    color: GRAY,
    marginTop: 2,
  },
  right: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  divider: {
    width: 1,
    height: 28,
    backgroundColor: '#E5E7EB',
  },
  userRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  avatar: {
    width: 34,
    height: 34,
    borderRadius: 17,
    backgroundColor: '#E5E7EB',
  },
  userName: {
    fontSize: 14,
    fontWeight: '500',
    color: TEXT,
  },
  avatarFallback: {
    backgroundColor: '#0F766E',
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarInitial: {
    color: WHITE,
    fontSize: 14,
    fontWeight: '700',
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
