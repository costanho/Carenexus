import { useRouter } from 'expo-router';
import { Platform } from 'react-native';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Image, StyleSheet, Text, TouchableOpacity, View, useWindowDimensions } from 'react-native';
import { decorative, webOnly } from '@/components/web/ui/a11y';
import { Icon } from '@/components/web/ui/icon';
import { logout } from '@/lib/auth/auth.service';
import { MENU_FOCUS_KEY, bellProps, useNavFocusRestore, useShellUser } from '../../layout/shell-support';
import { NotificationBell } from '../../notifications/notification-bell';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const RED    = '#B91C1C';
// Below this width the subtitle and the name block are hidden so the greeting keeps its room.
const NARROW_MAX = 640;
const BELL_THEME = { accent: '#7C3AED', accentBg: '#F5F3FF' };
// The top bar is the page's banner landmark (the section beside it is the main landmark).
const BANNER = { role: 'banner' } as object;
const MENU_ID = 'proxy-account-menu';

type Focusable = { focus?: () => void; contains?: (node: unknown) => boolean } | null;

/**
 * Keyboard and pointer behaviour of the account menu on the web (WAI-ARIA menu button): opening moves focus to
 * the first item, Arrow Up/Down, Home and End move between items, Escape closes the menu and returns focus to
 * the button, Tab closes it and moves on, and a click outside closes it.
 */
function useAccountMenuKeys(open: boolean, close: (refocus: boolean) => void) {
  const triggerRef = useRef<unknown>(null);
  const blockRef   = useRef<unknown>(null);
  const itemRefs   = useRef<unknown[]>([]);
  const closeRef   = useRef(close);
  closeRef.current = close;

  useEffect(() => {
    if (!open || Platform.OS !== 'web' || typeof document === 'undefined') return;
    const items = () => itemRefs.current.filter(Boolean) as HTMLElement[];
    items()[0]?.focus?.();

    const onKey = (e: KeyboardEvent) => {
      const list = items();
      const at = list.indexOf(document.activeElement as HTMLElement);
      switch (e.key) {
        case 'Escape':
          e.preventDefault();
          closeRef.current(true);
          break;
        case 'ArrowDown':
          e.preventDefault();
          list[(at + 1 + list.length) % list.length]?.focus();
          break;
        case 'ArrowUp':
          e.preventDefault();
          list[(at - 1 + list.length) % list.length]?.focus();
          break;
        case 'Home':
          e.preventDefault();
          list[0]?.focus();
          break;
        case 'End':
          e.preventDefault();
          list[list.length - 1]?.focus();
          break;
        case ' ':
        case 'Spacebar':
          // react-native-web activates only buttons with Space; a menu item takes it too.
          if (at >= 0) {
            e.preventDefault();
            list[at].click();
          }
          break;
        case 'Tab':
          closeRef.current(false);
          break;
      }
    };
    const onPointer = (e: MouseEvent) => {
      const block = blockRef.current as Focusable;
      if (block?.contains && !block.contains(e.target)) closeRef.current(false);
    };
    document.addEventListener('keydown', onKey);
    document.addEventListener('mousedown', onPointer);
    return () => {
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('mousedown', onPointer);
    };
  }, [open]);

  const focusTrigger = useCallback(() => {
    if (Platform.OS !== 'web') return;
    setTimeout(() => (triggerRef.current as Focusable)?.focus?.(), 0);
  }, []);

  const itemRef = (index: number) => (el: unknown) => { itemRefs.current[index] = el; };
  return { triggerRef, blockRef, itemRef, focusTrigger };
}

const LOGIN_ROUTE = Platform.OS === 'web'
  ? '/auth/login/web/login-web'
  : '/auth/login/mobile/login-mobile';

const AVATAR_COLORS = ['#4F46E5', '#0F766E', '#B45309', '#B91C1C', '#6D28D9', '#047857', '#0369A1'];
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

export function PatientProxyTopNavbar({ onNavigate, onOpenThread, onMenuPress, onNotificationLink }: {
  onNavigate?:   (key: string) => void;
  /** Phones: opens the navigation drawer. */
  onMenuPress?:  () => void;
  /** Open the messages section on a thread (from a notification). */
  onOpenThread?: (threadId: number) => void;
  /** Follows the in-app link of any other notification (`followActionUrl`); false when there is nothing to open. */
  onNotificationLink?: (actionUrl: string | null | undefined) => boolean;
} = {}) {
  const { user }              = useShellUser();
  const router                = useRouter();
  const { width }             = useWindowDimensions();
  const narrow                = width < NARROW_MAX;
  const [menuOpen, setMenuOpen] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  // After a navigation started in the phone drawer, the menu button of the new copy takes focus back.
  const menuRef = useNavFocusRestore(MENU_FOCUS_KEY);
  const accountMenu = useAccountMenuKeys(menuOpen, refocus => {
    setMenuOpen(false);
    if (refocus) accountMenu.focusTrigger();
  });

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
    <View style={[styles.navbar, narrow && styles.navbarNarrow]} {...BANNER}>
      {!!onMenuPress && (
        <TouchableOpacity
          ref={menuRef as never}
          onPress={onMenuPress}
          style={styles.menuBtn}
          activeOpacity={0.7}
          accessibilityRole="button"
          accessibilityLabel="Open menu"
        >
          <Icon name="menu-outline" size={26} color={TEXT} />
        </TouchableOpacity>
      )}
      {/* Left: greeting */}
      <View style={styles.left}>
        <Text style={[styles.greeting, narrow && styles.greetingNarrow]} numberOfLines={narrow ? 2 : undefined}>{greeting()}, {firstName || '…'}!</Text>
        {!narrow && <Text style={styles.subtitle}>Here's how your loved ones are doing today.</Text>}
      </View>

      {/* Right: profile menu */}
      <View style={[styles.right, narrow && styles.rightNarrow]}>
        {onOpenThread && (
          <NotificationBell {...bellProps({ theme: BELL_THEME, onOpenThread, onNavigate: onNotificationLink })} />
        )}

        {/* Profile + dropdown */}
        <View style={styles.profileBlock} ref={accountMenu.blockRef as never}>
          <TouchableOpacity
            ref={accountMenu.triggerRef as never}
            style={styles.profileRow}
            activeOpacity={0.8}
            onPress={() => setMenuOpen(o => !o)}
            accessibilityRole="button"
            // Named by the visible name and role (WCAG 2.5.3); the initials are decorative.
            // On phones the text is hidden, so the same words become the label.
            accessibilityLabel={narrow ? `${fullName || '—'} Proxy / Caregiver` : undefined}
            aria-expanded={menuOpen}
            {...webOnly({ 'aria-haspopup': 'menu', 'aria-controls': menuOpen ? MENU_ID : undefined })}
          >
            {avatarUrl ? (
              <Image source={{ uri: avatarUrl }} style={styles.avatar} {...decorative} />
            ) : (
              <View style={[styles.avatar, styles.avatarFallback, { backgroundColor: ac }]} {...decorative}>
                {/* On phones the button is labelled by name, so it shows no other visible words (WCAG 2.5.3). */}
                {narrow ? <Icon name="person" size={18} color={WHITE} /> : <Text style={styles.avatarInitials}>{initials(firstName, lastName)}</Text>}
              </View>
            )}
            {!narrow && (
              <View>
                <Text style={styles.profileName}>{fullName || '—'}{' '}</Text>
                <Text style={styles.profileRole}>Proxy / Caregiver</Text>
              </View>
            )}
            <Icon name={menuOpen ? 'chevron-up' : 'chevron-down'} size={16} color={GRAY} />
          </TouchableOpacity>

          {/* Dropdown menu: the menuitems sit in a named menu (axe: aria-required-parent). */}
          {menuOpen && (
            <View style={styles.dropdown} role="menu" aria-label="Account" {...webOnly({ id: MENU_ID })}>
              {onNavigate && (
                <TouchableOpacity
                  ref={accountMenu.itemRef(0) as never}
                  style={styles.dropdownItem}
                  activeOpacity={0.7}
                  onPress={() => { setMenuOpen(false); onNavigate('profile-settings'); }}
                  accessibilityRole="menuitem"
                >
                  <Icon name="settings-outline" size={16} color={TEXT} />
                  <Text style={styles.dropdownItemText}>Profile & Settings</Text>
                </TouchableOpacity>
              )}
              <TouchableOpacity
                ref={accountMenu.itemRef(1) as never}
                style={styles.dropdownItem}
                activeOpacity={0.7}
                onPress={handleLogout}
                disabled={loggingOut}
                accessibilityRole="menuitem"
                aria-disabled={loggingOut}
              >
                <Icon name="log-out-outline" size={16} color={RED} />
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
  navbarNarrow: {
    height: 64,
    paddingHorizontal: 12,
    gap: 8,
  },

  menuBtn: { padding: 4, marginRight: 4 },

  // Left
  left: {
    flex: 1,
    minWidth: 0,
  },
  greetingNarrow: {
    fontSize: 16,
    marginBottom: 0,
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
  rightNarrow: {
    gap: 8,
    flexShrink: 0,
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
