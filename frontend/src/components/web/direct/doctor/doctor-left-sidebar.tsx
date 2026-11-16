import { useMemo } from 'react';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useUnacknowledgedAlertCount } from '@/hooks/use-ai-alerts';
import { useLogout } from '@/hooks/use-logout';
import { navGroups, navItemLabel, type SectionDef } from '@/lib/nav/sections';
import { decorative, Icon } from '../../ui';
import {
  rememberNavFocus, useNavBadges, useNavFocusRestore, useSidebarScrollMemory,
} from '../../layout/shell-support';
import { registry } from '../../sections/doctor.sections';

const BG        = '#0D1B2E';
const ACTIVE_BG = '#3B5BDB';
const WHITE     = '#FFFFFF';
const GRAY      = '#8196AE';
const TEAL      = '#2DD4BF';
const DIVIDER   = '#1E2D42';
const LOGOUT_RED = '#F87171';

// The sidebar is the page's navigation landmark (one per page: on phones it lives inside the menu drawer).
const NAV_LANDMARK = { role: 'navigation', 'aria-label': 'Main' } as object;

// react-native-web ignores accessibilityState.selected, so the active item is announced through aria-current.
const CURRENT_PAGE = { 'aria-current': 'page' } as object;

function SidebarNavItem({ item, active, collapsed, onPress, badge = 0 }: {
  item: SectionDef; active: boolean; collapsed: boolean; onPress: () => void; badge?: number;
}) {
  const ref = useNavFocusRestore(item.key);
  return (
    <TouchableOpacity
      ref={ref as never}
      style={[styles.navItem, active && styles.navItemActive]}
      activeOpacity={0.7}
      onPress={() => { rememberNavFocus(item.key); onPress(); }}
      accessibilityRole="button"
      accessibilityLabel={navItemLabel(item, badge)}
      accessibilityState={{ selected: active }}
      {...(active ? CURRENT_PAGE : null)}
    >
      <View>
        <Icon name={item.icon} size={18} color={active ? WHITE : '#8BA3BE'} />
        {collapsed && badge > 0 && <View style={styles.badgeDot} {...decorative} />}
      </View>
      {!collapsed && (
        <Text style={[styles.navLabel, active && styles.navLabelActive]}>{item.label}{' '}</Text>
      )}
      {!collapsed && badge > 0 && (
        <View style={styles.badge} {...decorative}>
          <Text style={styles.badgeText}>{badge > 99 ? '99+' : badge}</Text>
        </View>
      )}
    </TouchableOpacity>
  );
}

/**
 * A titled group of navigation items. The group is named after its visible title (read once, as the group's
 * name, rather than as a stray line of text between two buttons), so the uppercase title text itself is hidden.
 */
function groupProps(title: string): object {
  const name = title.charAt(0) + title.slice(1).toLowerCase();
  return { role: 'group', 'aria-label': name };
}

export interface DoctorLeftSidebarProps {
  collapsed?:  boolean;
  /** Key of the section being shown. */
  activeKey?:  string;
  onNavigate?: (key: string) => void;
}

/** Doctor navigation. Every item comes from the section registry (components/web/sections/doctor.sections.tsx). */
export function DoctorLeftSidebar({ collapsed = false, activeKey = registry.homeKey, onNavigate }: DoctorLeftSidebarProps) {
  const { handleLogout, loading } = useLogout();
  const shared = useNavBadges();
  // Clinical Alerts carries the number of unacknowledged AI alerts (GET /api/ai-alerts/unacknowledged-count,
  // polled by a shared store). The shell's badge table has no alert source yet, so the doctor sidebar adds it.
  const alerts = useUnacknowledgedAlertCount(true);
  const badges = useMemo(() => ({ ...shared, alerts: alerts.count }), [shared, alerts.count]);
  const scroll = useSidebarScrollMemory('doctor');
  const { home, groups } = useMemo(() => navGroups(registry), []);

  return (
    <View style={[styles.sidebar, collapsed && styles.sidebarCollapsed]} {...NAV_LANDMARK}>
      {/* Logo */}
      <View style={styles.logoSection}>
        <View style={styles.logoIconWrapper} {...decorative}>
          <Icon name="heart" size={16} color={TEAL} />
        </View>
        {!collapsed && (
          <View>
            <Text style={styles.logoText}>
              <Text style={styles.logoCare}>Care</Text>
              <Text style={styles.logoNexus}>Nexus</Text>
            </Text>
            <Text style={styles.logoDirect}>DIRECT</Text>
          </View>
        )}
      </View>

      <View style={styles.divider} />

      <ScrollView style={styles.scroll} showsVerticalScrollIndicator={false} {...scroll}>
        {/* Home */}
        <SidebarNavItem
          item={home}
          active={activeKey === home.key}
          collapsed={collapsed}
          onPress={() => onNavigate?.(home.key)}
        />

        {/* Groups */}
        {groups.map((group, gi) => (
          <View key={group.title} {...groupProps(group.title)}>
            <View style={[styles.sectionDivider, gi === 0 && { marginTop: 8 }]} {...decorative} />
            {!collapsed && <Text style={styles.sectionTitle} {...decorative}>{group.title}</Text>}
            {group.items.map(item => (
              <SidebarNavItem
                key={item.key}
                item={item}
                active={activeKey === item.key}
                collapsed={collapsed}
                onPress={() => onNavigate?.(item.key)}
                badge={item.badge ? badges[item.badge] : 0}
              />
            ))}
          </View>
        ))}

        <View style={styles.spacer} />
      </ScrollView>

      {/* Footer */}
      <View style={styles.footer}>
        <View style={styles.divider} />

        {/* Brand row */}
        <View style={[styles.brandRow, collapsed && styles.brandRowCollapsed]}>
          <View style={styles.footerIconBg} {...decorative}>
            <Icon name="shield-outline" size={16} color={TEAL} />
          </View>
          {!collapsed && (
            <View style={styles.footerText}>
              <Text style={styles.footerTitle}>CareNexus Direct</Text>
              <Text style={styles.footerVersion}>v1.0.0</Text>
            </View>
          )}
        </View>

        {/* Logout button */}
        <TouchableOpacity
          style={[styles.logoutBtn, collapsed && styles.logoutBtnCollapsed]}
          onPress={handleLogout}
          activeOpacity={0.7}
          disabled={loading}
          accessibilityRole="button"
          // The name is the visible text (also when the rail is collapsed and shows only the icon).
          accessibilityLabel={loading ? 'Signing out…' : 'Sign Out'}
          accessibilityState={{ disabled: loading, busy: loading }}
          aria-busy={loading}
        >
          <View style={styles.logoutIconBg} {...decorative}>
            <Icon
              name={loading ? 'hourglass-outline' : 'log-out-outline'}
              size={16}
              color={LOGOUT_RED}
            />
          </View>
          {!collapsed && (
            <Text style={styles.logoutText}>{loading ? 'Signing out…' : 'Sign Out'}</Text>
          )}
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  badge:     { marginLeft: 'auto', minWidth: 20, height: 20, borderRadius: 10, paddingHorizontal: 6, backgroundColor: TEAL, alignItems: 'center', justifyContent: 'center' },
  badgeText: { color: BG, fontSize: 11, fontWeight: '700' },
  badgeDot:  { position: 'absolute', top: -2, right: -3, width: 8, height: 8, borderRadius: 4, backgroundColor: LOGOUT_RED }, // bright red: the only unread cue on the navy rail

  sidebar: {
    width: 240,
    backgroundColor: BG,
    height: '100%',
    flexDirection: 'column',
  },
  sidebarCollapsed: { width: 68 },

  logoSection: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 20,
    paddingTop: 24,
    paddingBottom: 16,
  },
  logoIconWrapper: {
    width: 34,
    height: 34,
    borderRadius: 17,
    borderWidth: 2,
    borderColor: TEAL,
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoText:   { fontSize: 17, fontWeight: '700', lineHeight: 21 },
  logoCare:   { color: WHITE },
  logoNexus:  { color: TEAL },
  logoDirect: { fontSize: 10, fontWeight: '700', color: TEAL, letterSpacing: 2.5 },

  divider: { height: 1, backgroundColor: DIVIDER, marginHorizontal: 16 },
  scroll:  { flex: 1, paddingHorizontal: 10, paddingTop: 8 },

  sectionDivider: { height: 1, backgroundColor: DIVIDER, marginVertical: 12, marginHorizontal: 4 },
  sectionTitle: {
    fontSize: 10, fontWeight: '600', color: GRAY,
    letterSpacing: 1.2, paddingHorizontal: 10,
    paddingBottom: 6, textTransform: 'uppercase',
  },

  navItem: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingVertical: 11,
    paddingHorizontal: 12,
    borderRadius: 10,
    marginBottom: 2,
  },
  navItemActive: { backgroundColor: ACTIVE_BG },
  navLabel:      { fontSize: 14, color: '#8BA3BE', fontWeight: '400' },
  navLabelActive:{ color: WHITE, fontWeight: '600' },

  spacer: { height: 24 },

  footer: { paddingBottom: 16 },

  brandRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 16,
    paddingTop: 14,
    paddingBottom: 8,
  },
  brandRowCollapsed: { justifyContent: 'center', paddingHorizontal: 0 },

  footerIconBg: {
    width: 30,
    height: 30,
    borderRadius: 15,
    backgroundColor: '#131F30',
    borderWidth: 1,
    borderColor: DIVIDER,
    alignItems: 'center',
    justifyContent: 'center',
  },
  footerText:    { flex: 1 },
  footerTitle:   { fontSize: 12, fontWeight: '600', color: WHITE },
  footerVersion: { fontSize: 10, color: GRAY, marginTop: 1 },

  logoutBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    marginHorizontal: 10,
    paddingHorizontal: 10,
    paddingVertical: 10,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#2A1A1A',
    backgroundColor: 'rgba(239,68,68,0.08)',
  },
  logoutBtnCollapsed: {
    justifyContent: 'center',
    marginHorizontal: 10,
    paddingHorizontal: 0,
  },
  logoutIconBg: {
    width: 28,
    height: 28,
    borderRadius: 14,
    backgroundColor: 'rgba(239,68,68,0.15)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoutText: {
    fontSize: 13,
    fontWeight: '600',
    color: LOGOUT_RED,
  },
});
