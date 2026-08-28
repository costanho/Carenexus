import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { Platform } from 'react-native';
import { useState } from 'react';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Image, StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';
import { useCurrentUser } from '@/hooks/use-current-user';
import { logout } from '@/lib/auth/auth.service';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const PURPLE = '#7C3AED';
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
function greeting() {
  const hr = new Date().getHours();
  if (hr < 12) return 'Good morning';
  if (hr < 17) return 'Good afternoon';
  return 'Good evening';
}

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
  const { user }   = useCurrentUser();
  const router     = useRouter();
  const insets     = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();

  const [menuOpen,   setMenuOpen]   = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);

  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));

  const iconSize   = s(22);
  const badgeSize  = s(15);
  const avatarSize = s(34);

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
    <View style={[styles.navbar, {
      paddingTop:    insets.top + sh(10),
      paddingBottom: sh(14),
      paddingLeft:   insets.left  + s(16),
      paddingRight:  insets.right + s(16),
    }]}>
      {/* Left: greeting */}
      <View style={styles.left}>
        <Text style={[styles.greeting, { fontSize: s(15) }]} numberOfLines={1}>
          {greeting()}, {firstName || '…'}!
        </Text>
        <Text style={[styles.subtitle, { fontSize: s(11), marginTop: s(2) }]} numberOfLines={1}>
          Here's how your loved ones are doing today.
        </Text>
      </View>

      {/* Right: badges + avatar + caret */}
      <View style={[styles.right, { gap: s(10) }]}>
        <TouchableOpacity activeOpacity={0.7}>
          <IconBadge name="notifications-outline" badge="5" iconSize={iconSize} badgeSize={badgeSize} />
        </TouchableOpacity>
        <TouchableOpacity activeOpacity={0.7}>
          <IconBadge name="mail-outline" badge="3" iconSize={iconSize} badgeSize={badgeSize} />
        </TouchableOpacity>

        <View style={[styles.divider, { height: s(24) }]} />

        {/* Profile row with dropdown */}
        <View>
          <TouchableOpacity
            style={[styles.profileRow, { gap: s(6) }]}
            activeOpacity={0.8}
            onPress={() => setMenuOpen(o => !o)}
          >
            {avatarUrl ? (
              <Image
                source={{ uri: avatarUrl }}
                style={{ width: avatarSize, height: avatarSize, borderRadius: avatarSize / 2, backgroundColor: BORDER }}
              />
            ) : (
              <View style={{
                width: avatarSize, height: avatarSize, borderRadius: avatarSize / 2,
                backgroundColor: ac, alignItems: 'center', justifyContent: 'center',
              }}>
                <Text style={{ color: WHITE, fontSize: s(12), fontWeight: '700' }}>
                  {`${firstName[0] ?? ''}${lastName[0] ?? ''}`.toUpperCase()}
                </Text>
              </View>
            )}
            <View>
              <Text style={[styles.profileName, { fontSize: s(11) }]} numberOfLines={1}>{fullName}</Text>
              <Text style={[styles.profileRole, { fontSize: s(10) }]} numberOfLines={1}>Proxy / Caregiver</Text>
            </View>
            <Ionicons name={menuOpen ? 'chevron-up' : 'chevron-down'} size={s(12)} color={GRAY} />
          </TouchableOpacity>

          {/* Dropdown */}
          {menuOpen && (
            <View style={[styles.dropdown, {
              borderRadius: s(10),
              minWidth: s(140),
              top: avatarSize + s(8),
            }]}>
              <TouchableOpacity
                style={[styles.dropdownItem, { paddingHorizontal: s(14), paddingVertical: s(12), gap: s(8) }]}
                onPress={handleLogout}
                disabled={loggingOut}
                activeOpacity={0.7}
              >
                <Ionicons name="log-out-outline" size={s(15)} color={RED} />
                <Text style={[styles.dropdownLogoutText, { fontSize: s(13) }]}>
                  {loggingOut ? 'Signing out…' : 'Log Out'}
                </Text>
              </TouchableOpacity>
            </View>
          )}
        </View>
      </View>

      {/* Switch to another — below, full width */}
      <TouchableOpacity style={[styles.switchBtn, {
        marginTop: sh(8),
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

  dropdown: {
    position: 'absolute',
    right: 0,
    backgroundColor: WHITE,
    borderWidth: 1,
    borderColor: BORDER,
    shadowColor: '#000',
    shadowOpacity: 0.1,
    shadowRadius: 10,
    shadowOffset: { width: 0, height: 4 },
    elevation: 8,
    zIndex: 999,
  } as any,
  dropdownItem: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  dropdownLogoutText: {
    color: RED,
    fontWeight: '600',
  },

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
