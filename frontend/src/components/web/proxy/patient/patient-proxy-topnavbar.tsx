import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { Platform } from 'react-native';
import { useState } from 'react';
import { Image, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useCurrentUser } from '@/hooks/use-current-user';
import { logout } from '@/lib/auth/auth.service';

const PURPLE = '#7C3AED';
const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
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

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

function IconBadge({ name, badge }: { name: IoniconsName; badge: string }) {
  return (
    <View style={styles.iconWrapper}>
      <Ionicons name={name} size={24} color={TEXT} />
      <View style={styles.badge}>
        <Text style={styles.badgeText}>{badge}</Text>
      </View>
    </View>
  );
}

export function PatientProxyTopNavbar() {
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

      {/* Right: icons + profile + switch */}
      <View style={styles.right}>
        <View style={styles.iconsRow}>
          <TouchableOpacity activeOpacity={0.7}>
            <IconBadge name="notifications-outline" badge="5" />
          </TouchableOpacity>
          <TouchableOpacity activeOpacity={0.7}>
            <IconBadge name="mail-outline" badge="3" />
          </TouchableOpacity>
        </View>

        <View style={styles.divider} />

        {/* Profile + dropdown */}
        <View style={styles.profileBlock}>
          <TouchableOpacity style={styles.profileRow} activeOpacity={0.8} onPress={() => setMenuOpen(o => !o)}>
            {avatarUrl ? (
              <Image source={{ uri: avatarUrl }} style={styles.avatar} />
            ) : (
              <View style={[styles.avatar, styles.avatarFallback, { backgroundColor: ac }]}>
                <Text style={styles.avatarInitials}>{initials(firstName, lastName)}</Text>
              </View>
            )}
            <View>
              <Text style={styles.profileName}>{fullName || '—'}</Text>
              <Text style={styles.profileRole}>Proxy / Caregiver</Text>
            </View>
            <Ionicons name={menuOpen ? 'chevron-up' : 'chevron-down'} size={16} color={GRAY} />
          </TouchableOpacity>

          {/* Dropdown menu */}
          {menuOpen && (
            <View style={styles.dropdown}>
              <TouchableOpacity
                style={styles.dropdownItem}
                activeOpacity={0.7}
                onPress={handleLogout}
                disabled={loggingOut}
              >
                <Ionicons name="log-out-outline" size={16} color={RED} />
                <Text style={styles.dropdownLogoutText}>
                  {loggingOut ? 'Signing out…' : 'Log Out'}
                </Text>
              </TouchableOpacity>
            </View>
          )}

          <TouchableOpacity style={styles.switchBtn} activeOpacity={0.8}>
            <Ionicons name="people-outline" size={15} color={PURPLE} />
            <Text style={styles.switchText}>Switch to another</Text>
            <Ionicons name="chevron-down" size={14} color={PURPLE} />
          </TouchableOpacity>
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  navbar: {
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
  iconsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 20,
  },
  iconWrapper: {
    width: 40,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
  },
  badge: {
    position: 'absolute',
    top: 2,
    right: 2,
    backgroundColor: PURPLE,
    borderRadius: 8,
    minWidth: 18,
    height: 18,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 4,
  },
  badgeText: {
    fontSize: 10,
    fontWeight: '700',
    color: WHITE,
  },
  divider: {
    width: 1,
    height: 36,
    backgroundColor: BORDER,
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
  switchBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    backgroundColor: BG,
    borderWidth: 1,
    borderColor: BORDER,
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 5,
  },
  switchText: {
    fontSize: 12,
    color: PURPLE,
    fontWeight: '600',
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
  dropdownLogoutText: {
    fontSize: 14,
    color: RED,
    fontWeight: '600',
  },
});
