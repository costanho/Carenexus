import { Ionicons } from '@expo/vector-icons';
import { useMemo } from 'react';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useAdminOutboxAvailable } from '@/hooks/use-admin-ops-outbox';
import { useLogout } from '@/hooks/use-logout';
import { onSignedOut } from '@/lib/auth/auth.events';
import { navGroups, navItemLabel, type SectionDef } from '@/lib/nav/sections';
import {
  rememberNavFocus, useNavBadges, useNavFocusRestore, useSidebarScrollMemory,
} from '../layout/shell-support';
import { adminNavKey, adminNavRegistry, registry } from '../sections/admin.sections';

const SLATE      = '#334155';
const SLATE_BG   = '#F1F5F9';
const TEXT       = '#0F172A';
const GRAY       = '#475569';
const GRAY_LABEL = '#526077';
const WHITE      = '#FFFFFF';
const BORDER     = '#CBD5E1';
const LOGOUT_RED = '#B91C1C';

// react-native-web ignores accessibilityState.selected, so the active item is announced through aria-current.
const CURRENT_PAGE = { 'aria-current': 'page' } as object;
const HIDDEN = { 'aria-hidden': true } as object;

// The answer of the outbox check, kept across the fresh copy of the shell each navigation mounts, so the item
// does not disappear and reappear while the new copy asks again (the check itself is made once per page load).
let outboxKnown = false;
onSignedOut(() => { outboxKnown = false; });

/**
 * Whether the development outbox is listed in the sidebar and on the home: only where the endpoint exists. A
 * failed or pending check lists nothing; the section stays reachable by URL and then says it is not available.
 */
export function useOutboxListed(): boolean {
  const { available } = useAdminOutboxAvailable();
  if (available !== null) outboxKnown = available;
  return available ?? outboxKnown;
}

function SidebarNavItem({ item, active, collapsed, onPress, badge = 0 }: {
  item: SectionDef; active: boolean; collapsed: boolean; onPress: () => void; badge?: number;
}) {
  const ref = useNavFocusRestore(item.key);
  return (
    <TouchableOpacity
      ref={ref as never}
      style={[styles.navItem, active && styles.navItemActive, collapsed && styles.navItemCollapsed]}
      activeOpacity={0.7}
      onPress={() => { rememberNavFocus(item.key); onPress(); }}
      accessibilityRole="button"
      accessibilityLabel={navItemLabel(item, badge)}
      accessibilityState={{ selected: active }}
      {...(active ? CURRENT_PAGE : null)}
    >
      <View>
        <Ionicons name={item.icon} size={18} color={active ? WHITE : GRAY} importantForAccessibility="no" {...HIDDEN} />
        {/* The count is in the accessible name ("Messages, 3 unread"); the dot only repeats it. */}
        {collapsed && badge > 0 && <View style={styles.badgeDot} {...HIDDEN} />}
      </View>
      {/* The collapsed rail shows icons only; the label stays the accessible name. */}
      {!collapsed && <Text style={[styles.navLabel, active && styles.navLabelActive]}>{item.label}{' '}</Text>}
      {!collapsed && badge > 0 && (
        <View style={[styles.badge, active && styles.badgeActive]}>
          <Text style={[styles.badgeText, active && styles.badgeTextActive]}>{badge > 99 ? '99+' : badge}</Text>
        </View>
      )}
    </TouchableOpacity>
  );
}

export interface AdminSidebarProps {
  collapsed?:  boolean;
  /** Key of the section being shown. */
  activeKey?:  string;
  onNavigate?: (key: string) => void;
}

/** Administrator navigation. Every item comes from the section registry (components/web/sections/admin.sections.tsx). */
export function AdminSidebar({ collapsed = false, activeKey = registry.homeKey, onNavigate }: AdminSidebarProps) {
  const { handleLogout, loading } = useLogout();
  const badges = useNavBadges();
  const scroll = useSidebarScrollMemory('admin');
  const outboxListed = useOutboxListed();
  const { home, groups } = useMemo(() => navGroups(adminNavRegistry(outboxListed)), [outboxListed]);
  // A section without a sidebar item of its own (user details) highlights the item it belongs to.
  const current = adminNavKey(activeKey);

  return (
    // A navigation landmark, so screen-reader users can jump between the menu and the page.
    <View style={[styles.sidebar, collapsed && styles.sidebarCollapsed]} role="navigation" aria-label="Administration menu">
      {/* Logo */}
      <View style={[styles.logoSection, collapsed && styles.logoCollapsed]}>
        <View style={styles.logoIconWrapper}>
          <Ionicons name="shield-checkmark-outline" size={18} color={SLATE} importantForAccessibility="no" {...HIDDEN} />
        </View>
        {!collapsed && (
          <View>
            <Text style={styles.logoText}>
              <Text style={styles.logoCare}>Care</Text>
              <Text style={styles.logoNexus}>Nexus</Text>
            </Text>
            <Text style={styles.logoAdmin}>ADMIN</Text>
          </View>
        )}
      </View>

      <ScrollView style={styles.scroll} showsVerticalScrollIndicator={false} {...scroll}>
        <SidebarNavItem
          item={home}
          active={current === home.key}
          collapsed={collapsed}
          onPress={() => onNavigate?.(home.key)}
        />

        {groups.map(group => (
          <View key={group.title}>
            {!collapsed
              ? <Text style={styles.sectionLabel}>{group.title}</Text>
              : <View style={styles.sectionDivider} />
            }
            {group.items.map(item => (
              <SidebarNavItem
                key={item.key}
                item={item}
                active={current === item.key}
                collapsed={collapsed}
                onPress={() => onNavigate?.(item.key)}
                badge={item.badge ? badges[item.badge] : 0}
              />
            ))}
          </View>
        ))}

        <View style={{ height: 24 }} />
      </ScrollView>

      {/* Sign out */}
      <View style={styles.footer}>
        <TouchableOpacity
          style={[styles.logoutBtn, collapsed && styles.logoutBtnCollapsed]}
          onPress={handleLogout}
          activeOpacity={0.7}
          disabled={loading}
          accessibilityRole="button"
          accessibilityLabel="Sign out"
          accessibilityState={{ disabled: loading, busy: loading }}
          aria-busy={loading}
        >
          <Ionicons
            name={loading ? 'hourglass-outline' : 'log-out-outline'}
            size={17}
            color={LOGOUT_RED}
            importantForAccessibility="no"
            {...HIDDEN}
          />
          {!collapsed && <Text style={styles.logoutText}>{loading ? 'Signing out…' : 'Sign Out'}</Text>}
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  sidebar: {
    width: 250, backgroundColor: SLATE_BG, height: '100%',
    borderRightWidth: 1, borderRightColor: BORDER, flexDirection: 'column',
  },
  sidebarCollapsed: { width: 68 },

  logoSection: {
    flexDirection: 'row', alignItems: 'center', gap: 10,
    paddingHorizontal: 20, paddingTop: 24, paddingBottom: 16,
  },
  logoCollapsed: { paddingHorizontal: 0, justifyContent: 'center' },
  logoIconWrapper: {
    width: 38, height: 38, borderRadius: 19, borderWidth: 2, borderColor: SLATE,
    alignItems: 'center', justifyContent: 'center', backgroundColor: WHITE,
  },
  logoText:  { fontSize: 18, fontWeight: '700', lineHeight: 22 },
  logoCare:  { color: TEXT },
  logoNexus: { color: SLATE },
  logoAdmin: { fontSize: 11, fontWeight: '700', color: SLATE, letterSpacing: 2.5 },

  scroll: { flex: 1, paddingHorizontal: 10 },

  sectionLabel: {
    fontSize: 11, fontWeight: '600', color: GRAY_LABEL, letterSpacing: 1,
    paddingHorizontal: 10, paddingTop: 20, paddingBottom: 8, textTransform: 'uppercase',
  },
  sectionDivider: { height: 1, backgroundColor: BORDER, marginVertical: 12, marginHorizontal: 4 },

  navItem: {
    flexDirection: 'row', alignItems: 'center', gap: 12,
    paddingVertical: 11, paddingHorizontal: 12, borderRadius: 10, marginBottom: 2,
  },
  navItemActive:    { backgroundColor: SLATE },
  navItemCollapsed: { justifyContent: 'center' },
  navLabel:         { fontSize: 14, color: TEXT, fontWeight: '400', flexShrink: 1 },
  navLabelActive:   { color: WHITE, fontWeight: '600' },

  badge:           { marginLeft: 'auto', minWidth: 20, height: 20, borderRadius: 10, paddingHorizontal: 6, backgroundColor: SLATE, alignItems: 'center', justifyContent: 'center' },
  badgeActive:     { backgroundColor: WHITE },
  badgeText:       { color: WHITE, fontSize: 11, fontWeight: '700' },
  badgeTextActive: { color: SLATE },
  badgeDot:        { position: 'absolute', top: -2, right: -3, width: 8, height: 8, borderRadius: 4, backgroundColor: '#DC2626' },

  footer: { paddingHorizontal: 10, paddingBottom: 16, paddingTop: 8, borderTopWidth: 1, borderTopColor: BORDER },
  logoutBtn: {
    flexDirection: 'row', alignItems: 'center', gap: 10,
    paddingHorizontal: 12, paddingVertical: 11, borderRadius: 10,
    borderWidth: 1, borderColor: '#FECACA', backgroundColor: '#FFF5F5',
  },
  logoutBtnCollapsed: { justifyContent: 'center', paddingHorizontal: 0 },
  logoutText:         { fontSize: 14, fontWeight: '600', color: LOGOUT_RED },
});
