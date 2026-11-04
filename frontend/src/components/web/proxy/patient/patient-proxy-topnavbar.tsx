import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { Platform } from 'react-native';
import { useState } from 'react';
import { Image, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useCurrentUser } from '@/hooks/use-current-user';
import { logout } from '@/lib/auth/auth.service';
import { NotificationBell } from '../../notifications/notification-bell';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const RED    = '#EF4444';

const LOGIN_ROUTE = Platform.OS === 'web'
  ? '/auth/login/web/login-web'
  : '/auth/login/mobile/login-mobile';

const AVATAR_COLORS = ['#4F46E5', '#0D9488', '#F59E0B', '#EF4444', '#8B5CF6', '#10B981', '#0EA5E9'];
function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}
function initials(first: string, last: string) {
  return `${first[0] ?? ''}${last[0] ?? ''}`.toUpperCase();
}
function greeting() {
  const h = new Date().getHours();
  if (h < 12) return 'Good morning';
  if (h < 17) return 'Good afternoon';
  return 'Good evening';
}

export function PatientProxyTopNavbar({ onNavigate, onOpenThread }: {
  onNavigate?:   (key: string) => void;
  /** Open the messages section on a thread (from a notification). */
  onOpenThread?: (threadId: number) => void;
} = {}) {
  const { user }              = useCurrentUser();
  const router                = useRouter();
  const [menuOpen, setMenuOpen] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);

  const firstName = user?.first_name ?? '';
  const lastName  = user?.last_name  ?? '';
  const fullName  = user ? `${firstName} ${lastName}`.trim() : '—';
  const avatarUrl = user?.avatar_url ?? null;
  const ac        = avatarColor(fullName);

  async function handleLogout() {
    if (loggingOut) return;
    setLoggingOut(true);
    try {
      await logout();
    } finally {
      setMenuOpen(false);
      setLoggingOut(false);
      router.replace(LOGIN_ROUTE as any);
    }
  }

  return (
    <View style={styles.navbar}>
      {/* Left: greeting */}
      <View style={styles.left}>
        <Text style={styles.greeting}>{greeting()}, {firstName || '…'}!</Text>
        <Text style={styles.subtitle}>Here's how your loved ones are doing today.</Text>
      </View>

      {/* Right: profile menu */}
      <View style={styles.right}>
        {onOpenThread && (
          <NotificationBell theme={{ accent: '#7C3AED', accentBg: '#F5F3FF' }} onOpenThread={onOpenThread} />
        )}

        {/* Profile + dropdown */}
        <View style={styles.profileBlock}>
          <TouchableOpacity
            style={styles.profileRow}
            activeOpacity={0.8}
            onPress={() => setMenuOpen(o => !o)}
            accessibilityRole="button"
            // Named by the visible name and role (WCAG 2.5.3); the initials are decorative.
            accessibilityState={{ expanded: menuOpen }}
            aria-expanded={menuOpen}
          >
            {avatarUrl ? (
              <Image source={{ uri: avatarUrl }} style={styles.avatar} />
            ) : (
              <View style={[styles.avatar, styles.avatarFallback, { backgroundColor: ac }]} importantForAccessibility="no-hide-descendants">
                <Text style={styles.avatarInitials}>{initials(firstName, lastName)}</Text>
              </View>
            )}
            <View>
              <Text style={styles.profileName}>{fullName || '—'}{' '}</Text>
              <Text style={styles.profileRole}>Proxy / Caregiver</Text>
            </View>
            <Ionicons name={menuOpen ? 'chevron-up' : 'chevron-down'} size={16} color={GRAY} />
          </TouchableOpacity>

          {/* Dropdown menu */}
          {menuOpen && (
            <View style={styles.dropdown}>
              {onNavigate && (
                <TouchableOpacity
                  style={styles.dropdownItem}
                  activeOpacity={0.7}
                  onPress={() => { setMenuOpen(false); onNavigate('profile-settings'); }}
                  accessibilityRole="menuitem"
                >
                  <Ionicons name="settings-outline" size={16} color={TEXT} importantForAccessibility="no" />
                  <Text style={styles.dropdownItemText}>Profile & Settings</Text>
                </TouchableOpacity>
              )}
              <TouchableOpacity
                style={styles.dropdownItem}
                activeOpacity={0.7}
                onPress={handleLogout}
                disabled={loggingOut}
                accessibilityRole="menuitem"
                accessibilityState={{ disabled: loggingOut }}
              >
                <Ionicons name="log-out-outline" size={16} color={RED} importantForAccessibility="no" />
                <Text style={styles.dropdownLogoutText}>
                  {loggingOut ? 'Signing out…' : 'Log Out'}
                </Text>
              </TouchableOpacity>
            </View>
          )}
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  navbar: {
    zIndex: 20,
    height: 92,
    backgroundColor: WHITE,
    borderBottomWidth: 1,
    borderBottomColor: BORDER,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 28,
    gap: 20,
  },

  // Left
  left: {
    flex: 1,
  },
  greeting: {
    fontSize: 20,
    fontWeight: '700',
    color: TEXT,
    marginBottom: 4,
  },
  subtitle: {
    fontSize: 13,
    color: GRAY,
  },

  // Right
  right: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 16,
  },

  // Profile block
  profileBlock: {
    alignItems: 'flex-end',
    gap: 6,
  },
  profileRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  avatar: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: BORDER,
  },
  avatarFallback: {
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarInitials: {
    color: WHITE,
    fontSize: 14,
    fontWeight: '700',
  },
  profileName: {
    fontSize: 14,
    fontWeight: '700',
    color: TEXT,
  },
  profileRole: {
    fontSize: 12,
    color: GRAY,
    marginTop: 1,
  },

  dropdown: {
    position: 'absolute',
    top: 48,
    right: 0,
    backgroundColor: WHITE,
    borderWidth: 1,
    borderColor: BORDER,
    borderRadius: 10,
    shadowColor: '#000',
    shadowOpacity: 0.08,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: 4 },
    minWidth: 160,
    zIndex: 999,
  } as any,
  dropdownItem: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 16,
    paddingVertical: 13,
  },
  dropdownItemText: {
    fontSize: 14,
    color: TEXT,
    fontWeight: '500',
  },
  dropdownLogoutText: {
    fontSize: 14,
    color: RED,
    fontWeight: '600',
  },
});
