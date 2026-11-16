import { useMemo } from 'react';
import { Image, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { decorative, Icon } from '@/components/web/ui';
import { useLogout } from '@/hooks/use-logout';
import { navGroups, navItemLabel, type SectionDef } from '@/lib/nav/sections';
import {
  rememberNavFocus, useNavBadges, useNavFocusRestore, useShellUser, useSidebarScrollMemory,
} from '../../layout/shell-support';
import { registry } from '../../sections/patient.sections';

const TEAL       = '#0F766E';
const TEAL_BG    = '#E8F5F4';
const GRAY_LABEL = '#636A75';
const ICON_GRAY  = '#6B7280';
const TEXT       = '#111827';
const WHITE      = '#FFFFFF';
const LOGOUT_RED = '#B91C1C';

// react-native-web ignores accessibilityState.selected, so the active item is announced through aria-current.
const CURRENT_PAGE = { 'aria-current': 'page' } as object;

/**
 * Accessible name of a sidebar item. It starts with the visible label and contains the visible count, so a
 * count shown as "99+" is named "99+ unread" rather than the exact number (WCAG 2.5.3 Label in Name).
 */
function sidebarItemName(item: SectionDef, badge: number): string {
  return badge > 99 ? `${item.label}, 99+ unread` : navItemLabel(item, badge);
}

/** "RECORDS & RESULTS" -> "Records & results", so a screen reader reads the group name as words. */
function groupName(title: string): string {
  const lower = title.toLowerCase();
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

function SidebarItem({
  item, collapsed, isActive, onNavigate, badge = 0,
}: {
  item: SectionDef; collapsed: boolean; isActive: boolean; onNavigate: (key: string) => void; badge?: number;
}) {
  const ref = useNavFocusRestore(item.key);
  return (
    <TouchableOpacity
      ref={ref as never}
      style={[styles.navItem, isActive && styles.navItemActive, collapsed && styles.navItemCollapsed]}
      activeOpacity={0.7}
      onPress={() => { rememberNavFocus(item.key); onNavigate(item.key); }}
      accessibilityRole="button"
      accessibilityLabel={sidebarItemName(item, badge)}
      accessibilityState={{ selected: isActive }}
      {...(isActive ? CURRENT_PAGE : null)}
    >
      <View>
        <Icon name={item.icon} size={18} color={isActive ? TEAL : ICON_GRAY} />
        {collapsed && badge > 0 && <View style={styles.badgeDot} {...decorative} />}
      </View>
      {!collapsed && (
        <Text style={[styles.navLabel, isActive && styles.navLabelActive]}>{item.label}{' '}</Text>
      )}
      {!collapsed && badge > 0 && (
        <View style={styles.badge}><Text style={styles.badgeText}>{badge > 99 ? '99+' : badge}</Text></View>
      )}
    </TouchableOpacity>
  );
}

export interface PatientSidebarProps {
  collapsed?:   boolean;
  /** Key of the section being shown. */
  activeKey?:   string;
  onNavigate?:  (key: string) => void;
}

/** Patient navigation. Every item comes from the section registry (components/web/sections/patient.sections.tsx). */
export function PatientSidebar({ collapsed = false, activeKey = registry.homeKey, onNavigate }: PatientSidebarProps) {
  const { user } = useShellUser();
  const { handleLogout, loading } = useLogout();
  const badges = useNavBadges();
  const scroll = useSidebarScrollMemory('patient');
  const { home, groups } = useMemo(() => navGroups(registry), []);
  const navigate  = onNavigate ?? (() => {});
  const fullName  = user ? `${user.first_name} ${user.last_name}` : '...';
  const avatarUri = user?.avatar_url ?? null;

  return (
    <View style={[styles.sidebar, collapsed && styles.sidebarCollapsed]}>
      {/* Logo */}
      <View style={[styles.logoSection, collapsed && styles.logoCollapsed]}>
        <View style={styles.logoIconWrapper} {...decorative}>
          <Icon name="heart-outline" size={18} color={TEAL} />
        </View>
        {!collapsed && (
          <View style={styles.flex1}>
            <Text style={styles.logoText}>
              <Text style={styles.logoCare}>Care</Text>
              <Text style={styles.logoNexus}>Nexus</Text>
            </Text>
            <Text style={styles.logoDirect}>DIRECT</Text>
          </View>
        )}
      </View>

      <View style={styles.divider} />

      {/* The navigation landmark; each group of items is a named group. */}
      <ScrollView
        style={styles.scroll}
        showsVerticalScrollIndicator={false}
        role="navigation"
        aria-label="Patient navigation"
        {...scroll}
      >
        {/* Profile */}
        {!collapsed ? (
          <View style={styles.profileSection}>
            {avatarUri
              ? <Image source={{ uri: avatarUri }} style={styles.avatar} {...decorative} />
              : <View style={[styles.avatar, styles.avatarFallback]} {...decorative}><Text style={styles.avatarInitial}>{user?.first_name?.[0] ?? '?'}</Text></View>
            }
            <View style={styles.flex1}>
              <Text style={styles.helloText}>Hello,</Text>
              {/* The wave is decoration; screen readers would otherwise read "waving hand". */}
              <Text style={styles.nameText}>{fullName} <Text {...decorative}>👋</Text></Text>
              <Text style={styles.roleText}>Patient</Text>
            </View>
          </View>
        ) : (
          <View style={styles.avatarCollapsed}>
            {avatarUri
              ? <Image source={{ uri: avatarUri }} style={styles.avatarSmall} {...decorative} />
              : <View style={[styles.avatarSmall, styles.avatarFallback]} {...decorative}><Text style={styles.avatarInitial}>{user?.first_name?.[0] ?? '?'}</Text></View>
            }
          </View>
        )}

        {/* Home */}
        <SidebarItem
          collapsed={collapsed}
          item={home}
          isActive={activeKey === home.key}
          onNavigate={navigate}
        />

        {/* Groups */}
        {groups.map(group => (
          // The group name is spoken once, from the group; the capitalised heading text is its visual form.
          <View key={group.title} role="group" aria-label={groupName(group.title)}>
            {!collapsed
              ? <Text style={styles.sectionTitle} {...decorative}>{group.title}</Text>
              : <View style={styles.sectionDivider} />
            }
            {group.items.map(item => (
              <SidebarItem
                key={item.key}
                collapsed={collapsed}
                item={item}
                isActive={activeKey === item.key}
                onNavigate={navigate}
                badge={item.badge ? badges[item.badge] : 0}
              />
            ))}
          </View>
        ))}

        <View style={{ height: 16 }} />
      </ScrollView>

      {/* Logout footer */}
      <View style={styles.logoutFooter}>
        <View style={styles.divider} />
        <TouchableOpacity
          style={[styles.logoutBtn, collapsed && styles.logoutBtnCollapsed]}
          onPress={handleLogout}
          activeOpacity={0.7}
          disabled={loading}
          accessibilityRole="button"
          // Starts with the visible words in both states ("Sign Out", "Signing out…").
          accessibilityLabel={loading ? 'Signing out' : 'Sign out'}
          accessibilityState={{ disabled: loading }}
          aria-busy={loading}
        >
          <View style={styles.logoutIconWrap}>
            <Icon
              name={loading ? 'hourglass-outline' : 'log-out-outline'}
              size={17}
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
  sidebar: {
    width: 260, backgroundColor: WHITE,
    height: '100%', borderRightWidth: 1, borderRightColor: '#E5E7EB',
    flexDirection: 'column',
  },
  sidebarCollapsed: { width: 72 },
  flex1: { flex: 1 },

  logoSection: {
    paddingHorizontal: 20, paddingTop: 24, paddingBottom: 16,
    flexDirection: 'row', alignItems: 'center', gap: 10,
  },
  logoCollapsed: { paddingHorizontal: 0, justifyContent: 'center' },
  logoIconWrapper: {
    width: 36, height: 36, borderRadius: 18,
    borderWidth: 2, borderColor: TEAL,
    alignItems: 'center', justifyContent: 'center',
  },
  logoText:   { fontSize: 18, fontWeight: '700', lineHeight: 22 },
  logoCare:   { color: TEXT },
  logoNexus:  { color: TEAL },
  logoDirect: { fontSize: 11, fontWeight: '600', color: TEAL, letterSpacing: 2 },

  divider: { height: 1, backgroundColor: '#E5E7EB', marginHorizontal: 16 },
  scroll:  { flex: 1, paddingHorizontal: 8 },

  profileSection: {
    flexDirection: 'row', alignItems: 'center', gap: 12,
    paddingVertical: 20, paddingHorizontal: 4,
  },
  avatar:         { width: 52, height: 52, borderRadius: 26, backgroundColor: '#E5E7EB' },
  avatarCollapsed:{ alignItems: 'center', paddingVertical: 16 },
  avatarSmall:    { width: 36, height: 36, borderRadius: 18, backgroundColor: '#E5E7EB' },
  avatarFallback: { backgroundColor: TEAL, alignItems: 'center', justifyContent: 'center' },
  avatarInitial:  { color: WHITE, fontWeight: '700', fontSize: 16 },
  helloText:      { fontSize: 14, color: TEXT },
  nameText:       { fontSize: 16, fontWeight: '700', color: TEXT },
  roleText:       { fontSize: 13, color: TEAL, fontWeight: '500' },

  navItem: {
    flexDirection: 'row', alignItems: 'center', gap: 12,
    paddingVertical: 10, paddingHorizontal: 12,
    borderRadius: 8, marginBottom: 2,
  },
  navItemActive:    { backgroundColor: TEAL_BG },
  navItemCollapsed: { justifyContent: 'center', alignItems: 'center', paddingVertical: 12, marginBottom: 2, borderRadius: 8 },
  navLabel:         { fontSize: 14, color: TEXT, fontWeight: '400', flex: 1 },
  navLabelActive:   { color: TEAL, fontWeight: '600' },

  sectionTitle: {
    fontSize: 11, fontWeight: '600', color: GRAY_LABEL,
    letterSpacing: 1, paddingHorizontal: 12, paddingTop: 16, paddingBottom: 6,
  },
  sectionDivider: { height: 1, backgroundColor: '#F3F4F6', marginVertical: 8, marginHorizontal: 8 },

  badge:        { minWidth: 20, height: 20, borderRadius: 10, paddingHorizontal: 6, backgroundColor: TEAL, alignItems: 'center', justifyContent: 'center' },
  badgeText:    { color: WHITE, fontSize: 11, fontWeight: '700' },
  badgeDot:     { position: 'absolute', top: -2, right: -3, width: 8, height: 8, borderRadius: 4, backgroundColor: '#DC2626', borderWidth: 1, borderColor: WHITE },

  logoutFooter: { paddingBottom: 16 },
  logoutBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    marginHorizontal: 10,
    marginTop: 12,
    paddingHorizontal: 12,
    paddingVertical: 11,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#FECACA',
    backgroundColor: '#FFF5F5',
  },
  logoutBtnCollapsed: {
    justifyContent: 'center',
    marginHorizontal: 10,
    paddingHorizontal: 0,
  },
  logoutIconWrap: {
    width: 28,
    height: 28,
    borderRadius: 14,
    backgroundColor: '#FEE2E2',
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoutText: {
    fontSize: 14,
    fontWeight: '600',
    color: LOGOUT_RED,
  },
});
