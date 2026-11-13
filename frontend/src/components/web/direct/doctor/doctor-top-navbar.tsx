import { Image, StyleSheet, Text, TouchableOpacity, View, useWindowDimensions } from 'react-native';
import { useLogout } from '@/hooks/use-logout';
import { MENU_FOCUS_KEY, bellProps, useNavFocusRestore, useShellUser } from '../../layout/shell-support';
import { NotificationBell } from '../../notifications/notification-bell';
import { decorative, Icon } from '../../ui';
import { doctorDisplayName } from '@/hooks/use-doctors';

const BG       = '#FFFFFF';
const BORDER   = '#E5E7EB';
const TEXT     = '#111827';
const GRAY     = '#6B7280';
const RED      = '#B91C1C';

/** Below this window width the bar drops the name block and the Sign Out text (phones). */
const NARROW_MAX = 640;

export interface DoctorTopNavbarProps {
  onMenuPress?:    () => void;
  onProfilePress?: () => void;
  /** Open the messages section on a thread (from a notification). */
  onOpenThread?:   (threadId: number) => void;
  /** Follows the in-app link of any other notification (`followActionUrl`); false when there is nothing to open. */
  onNotificationLink?: (actionUrl: string | null | undefined) => boolean;
}

// The top bar is the page's banner landmark.
const BANNER = { role: 'banner' } as object;

const BELL_THEME = { accent: '#4F46E5', accentBg: '#EEF2FF' };

export function DoctorTopNavbar({ onMenuPress, onProfilePress, onOpenThread, onNotificationLink }: DoctorTopNavbarProps) {
  const { user } = useShellUser();
  const { handleLogout, loading } = useLogout();
  const { width } = useWindowDimensions();
  const narrow = width < NARROW_MAX;
  // After a navigation started in the phone drawer, the menu button of the new copy takes focus back.
  const menuRef = useNavFocusRestore(MENU_FOCUS_KEY);
  const fullName  = user ? doctorDisplayName(user.first_name, user.last_name) || 'Doctor' : 'Doctor';
  const avatarUri = user?.avatar_url ?? null;
  const initials  = user ? `${user.first_name[0] ?? ''}${user.last_name[0] ?? ''}`.toUpperCase() || 'DR' : 'DR';

  return (
    <View style={[styles.navbar, narrow && styles.navbarNarrow]} {...BANNER}>
      {/* Left: hamburger */}
      <View style={styles.left}>
        {!!onMenuPress && (
          <TouchableOpacity
            ref={menuRef as never}
            onPress={onMenuPress}
            style={styles.hamburger}
            activeOpacity={0.7}
            accessibilityRole="button"
            accessibilityLabel="Open menu"
          >
            <Icon name="menu-outline" size={26} color={TEXT} />
          </TouchableOpacity>
        )}
      </View>

      {/* Right: profile + logout */}
      <View style={styles.right}>
        {onOpenThread && (
          <>
            <NotificationBell {...bellProps({ theme: BELL_THEME, onOpenThread, onNavigate: onNotificationLink })} />
            {!narrow && <View style={styles.divider} />}
          </>
        )}

        {/* Profile */}
        <TouchableOpacity
          style={styles.profile}
          activeOpacity={0.8}
          onPress={onProfilePress}
          disabled={!onProfilePress}
          accessibilityRole="button"
          // Named by its visible text (name and role, WCAG 2.5.3); the initials are decorative.
          // On phones the text is hidden, so the same words become the label.
          accessibilityLabel={narrow ? `${fullName} Doctor` : undefined}
        >
          {avatarUri
            ? <Image source={{ uri: avatarUri }} style={styles.avatar} {...decorative} />
            : <View style={[styles.avatar, styles.avatarFallback]} {...decorative}>
                {/* On phones the button is labelled by name, so it shows no other visible words (WCAG 2.5.3). */}
                {narrow ? <Icon name="person" size={18} color="#FFFFFF" /> : <Text style={styles.avatarInitials}>{initials}</Text>}
              </View>
          }
          {!narrow && (
            <View style={styles.nameBlock}>
              <Text style={styles.doctorName} numberOfLines={1}>{fullName}{' '}</Text>
              <Text style={styles.doctorRole}>Doctor</Text>
            </View>
          )}
          {!!onProfilePress && !narrow && <Icon name="chevron-forward" size={16} color={GRAY} />}
        </TouchableOpacity>

        {!narrow && <View style={styles.divider} />}

        {/* Sign Out */}
        <TouchableOpacity
          style={[styles.logoutBtn, narrow && styles.logoutBtnNarrow]}
          onPress={handleLogout}
          activeOpacity={0.7}
          disabled={loading}
          accessibilityRole="button"
          // Starts with the visible text; on phones only the icon shows, so the same words are the name.
          accessibilityLabel={loading ? 'Signing out' : 'Sign Out'}
          accessibilityState={{ disabled: loading, busy: loading }}
          aria-busy={loading}
        >
          <Icon
            name={loading ? 'hourglass-outline' : 'log-out-outline'}
            size={18}
            color={RED}
          />
          {!narrow && <Text style={styles.logoutText}>{loading ? '…' : 'Sign Out'}</Text>}
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  navbar: {
    zIndex: 20,
    height: 88,
    backgroundColor: BG,
    borderBottomWidth: 1,
    borderBottomColor: BORDER,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 24,
    gap: 16,
  },
  navbarNarrow: {
    height: 64,
    paddingHorizontal: 12,
    gap: 8,
  },
  left: {
    flex: 1,
    minWidth: 0,
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
    minWidth: 0,
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
    flexShrink: 1,
    minWidth: 0,
  },
  nameBlock: {
    flexShrink: 1,
    minWidth: 0,
  },
  avatar: {
    width: 38,
    height: 38,
    borderRadius: 19,
    backgroundColor: '#E5E7EB',
  },
  avatarFallback: {
    backgroundColor: '#0F766E',
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
  logoutBtnNarrow: {
    paddingHorizontal: 8,
    marginLeft: 4,
  },
  logoutText: {
    fontSize: 13,
    fontWeight: '600',
    color: RED,
  },
});
