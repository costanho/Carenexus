import { Ionicons } from '@expo/vector-icons';
import { useIsFocused } from 'expo-router';
import { useCallback } from 'react';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useLogout } from '@/hooks/use-logout';
import { SectionNavContext, useSectionNav } from '@/hooks/use-section-nav';
import { ROLE_THEME } from '@/lib/nav/sections';
import { NavDrawer } from '../layout/nav-drawer';
import { SectionHost } from '../layout/section-host';
import {
  MENU_FOCUS_KEY, bellProps, rememberNavFocus, useNavFocusRestore, useNotificationActions, useShellLayout, useShellUser,
} from '../layout/shell-support';
import { NotificationBell } from '../notifications/notification-bell';
import { registry } from '../sections/admin.sections';
import { AdminSidebar } from './admin-sidebar';

const THEME = ROLE_THEME.admin;

const TEXT   = '#0F172A';
const GRAY   = '#475569';
const WHITE  = '#FFFFFF';
const BORDER = '#E2E8F0';
const RED    = '#B91C1C';

/** Below this window width the bar drops the name block and the Sign Out text (phones). */
const NARROW_MAX = 640;
const HIDDEN = { 'aria-hidden': true } as object;

/**
 * Administrator area shell (web only). Which section is shown comes from the URL
 * (`/dashboard/admin?section=<key>`) and what a section is comes from the registry in
 * components/web/sections/admin.sections.tsx; this file only lays out the sidebar, top bar and content.
 */
export function AdminDashboardWeb() {
  // Opening a section adds a history entry, and the router keeps the previous screen mounted underneath for
  // Back. Only the screen in front renders the dashboard, so hidden copies hold no data and do not poll.
  const focused = useIsFocused();
  if (!focused) return <View style={styles.container} />;
  return <AdminShell />;
}

interface AdminTopBarProps {
  narrow: boolean;
  onMenuPress?: () => void;
  /** Support messages with a conversation open the support inbox on it. */
  onOpenThread: (threadId: number) => void;
  /** Every other notification follows its in-app link (`followActionUrl`); false when there is nothing to open. */
  onNotificationLink: (actionUrl: string | null | undefined) => boolean;
}

const BELL_THEME = { accent: THEME.accent, accentBg: THEME.accentBg };

function AdminTopBar({ narrow, onMenuPress, onOpenThread, onNotificationLink }: AdminTopBarProps) {
  const { user } = useShellUser();
  const { handleLogout, loading } = useLogout();
  const fullName = user ? `${user.first_name} ${user.last_name}`.trim() : '';
  // After a navigation started in the phone drawer, the menu button of the new copy takes focus back.
  const menuRef = useNavFocusRestore(MENU_FOCUS_KEY);

  return (
    <View style={[styles.navbar, narrow && styles.navbarNarrow]}>
      <View style={styles.left}>
        {!!onMenuPress && (
          <TouchableOpacity
            ref={menuRef as never}
            onPress={onMenuPress}
            style={styles.menuBtn}
            activeOpacity={0.7}
            accessibilityRole="button"
            accessibilityLabel="Open menu"
          >
            <Ionicons name="menu-outline" size={26} color={TEXT} importantForAccessibility="no" {...HIDDEN} />
          </TouchableOpacity>
        )}
        <Text style={styles.barTitle} numberOfLines={1}>Administration</Text>
      </View>

      <View style={[styles.right, narrow && styles.rightNarrow]}>
        {/* The bell also keeps the unread counts polled, which the Messages and Notifications badges read. */}
        <NotificationBell {...bellProps({ theme: BELL_THEME, onOpenThread, onNavigate: onNotificationLink })} />
        {!narrow && <View style={styles.divider} />}
        {!narrow && !!fullName && (
          <View style={styles.nameBlock}>
            <Text style={styles.userName} numberOfLines={1}>{fullName}{' '}</Text>
            <Text style={styles.userRole}>Administrator</Text>
          </View>
        )}
        <TouchableOpacity
          style={[styles.logoutBtn, narrow && styles.logoutBtnNarrow]}
          onPress={handleLogout}
          activeOpacity={0.7}
          disabled={loading}
          accessibilityRole="button"
          accessibilityLabel="Sign out"
          accessibilityState={{ disabled: loading, busy: loading }}
          aria-busy={loading}
        >
          <Ionicons name={loading ? 'hourglass-outline' : 'log-out-outline'} size={18} color={RED} importantForAccessibility="no" {...HIDDEN} />
          {!narrow && <Text style={styles.logoutText}>{loading ? '…' : 'Sign Out'}</Text>}
        </TouchableOpacity>
      </View>
    </View>
  );
}

function AdminShell() {
  const nav = useSectionNav(registry);
  const layout = useShellLayout();
  const { navigate } = nav;
  const { closeDrawer } = layout;
  // Notification clicks: a support message with a conversation opens the inbox on it; the rest follow their
  // link, so ADMIN_CLINICIAN_QUEUE, ADMIN_ACCOUNT_REQUESTS, ADMIN_BILLING, CORRECTIONS, MESSAGES and
  // ACCOUNT_SECURITY targets open their section (keys in components/web/sections/admin.sections.tsx).
  const notifications = useNotificationActions(nav);

  const navigateFromDrawer = useCallback((key: string) => {
    closeDrawer();
    // The drawer is closed in the copy the navigation mounts, so focus goes back to its menu button.
    rememberNavFocus(MENU_FOCUS_KEY);
    navigate(key);
  }, [closeDrawer, navigate]);

  return (
    <SectionNavContext.Provider value={nav}>
      <View style={styles.container}>
        {!layout.isSmall && (
          <AdminSidebar collapsed={layout.isCollapsed} activeKey={nav.section} onNavigate={navigate} />
        )}
        <View style={styles.main}>
          <AdminTopBar
            narrow={layout.width < NARROW_MAX}
            onMenuPress={layout.isSmall ? layout.openDrawer : undefined}
            onOpenThread={notifications.onOpenThread}
            onNotificationLink={notifications.onNavigate}
          />
          <SectionHost nav={nav} theme={THEME} />
        </View>
        {/* Phones: the sidebar is a drawer behind the menu button, so pages get the full width. */}
        <NavDrawer open={layout.drawerOpen} onClose={closeDrawer}>
          <AdminSidebar collapsed={false} activeKey={nav.section} onNavigate={navigateFromDrawer} />
        </NavDrawer>
      </View>
    </SectionNavContext.Provider>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, flexDirection: 'row', backgroundColor: '#F8FAFC' },
  main:      { flex: 1, flexDirection: 'column', minWidth: 0 },

  navbar: {
    zIndex: 20, height: 72, backgroundColor: WHITE,
    borderBottomWidth: 1, borderBottomColor: BORDER,
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 24, gap: 16,
  },
  navbarNarrow: { height: 60, paddingHorizontal: 12, gap: 8 },
  left:     { flex: 1, minWidth: 0, flexDirection: 'row', alignItems: 'center', gap: 8 },
  menuBtn:  { padding: 6 },
  barTitle: { flexShrink: 1, fontSize: 16, fontWeight: '700', color: TEXT },

  right:     { flexDirection: 'row', alignItems: 'center', gap: 14, flexShrink: 0 },
  rightNarrow: { gap: 6 },
  divider:   { width: 1, height: 28, backgroundColor: BORDER },
  nameBlock: { alignItems: 'flex-end', maxWidth: 260 },
  userName:  { fontSize: 14, fontWeight: '700', color: TEXT },
  userRole:  { fontSize: 12, color: GRAY },

  logoutBtn: {
    flexDirection: 'row', alignItems: 'center', gap: 6,
    paddingHorizontal: 12, paddingVertical: 8, borderRadius: 8,
    borderWidth: 1, borderColor: '#FECACA', backgroundColor: '#FFF5F5',
  },
  logoutBtnNarrow: { paddingHorizontal: 8 },
  logoutText:      { fontSize: 13, fontWeight: '600', color: RED },
});
