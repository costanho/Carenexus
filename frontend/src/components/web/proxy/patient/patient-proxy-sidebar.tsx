import { useMemo, useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { decorative } from '@/components/web/ui/a11y';
import { Icon } from '@/components/web/ui/icon';
import type { CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import { onSignedOut } from '@/lib/auth/auth.events';
import { navGroups, navItemLabel, type IoniconsName, type SectionDef } from '@/lib/nav/sections';
import {
  rememberNavFocus, useNavBadges, useNavFocusRestore, useSidebarScrollMemory,
} from '../../layout/shell-support';
import { useDependents } from '../../sections/proxy-dependents';
import { registry } from '../../sections/proxy.sections';

const PURPLE      = '#7C3AED';
const PURPLE_BG   = '#F5F3FF';
const TEXT        = '#111827';
const GRAY        = '#5B6472';
const GRAY_LABEL  = '#636A75';
const WHITE       = '#FFFFFF';
const BORDER      = '#E5E7EB';
const RED         = '#B91C1C';

// react-native-web ignores accessibilityState, so the active item is announced through aria-current and the
// "+N more" toggle through aria-expanded. Decorative parts (icons, initials, status dots) use `decorative`,
// which hides them on web (aria-hidden) and on native alike.
const CURRENT_PAGE = { 'aria-current': 'page' } as object;
// The whole sidebar is the caregiver's navigation landmark (on phones it sits inside the menu drawer).
const NAV_LANDMARK = { role: 'navigation', 'aria-label': 'Caregiver navigation' } as object;

// White initials need 4.5:1 against every avatar colour.
const AVATAR_COLORS = ['#4F46E5', '#0F766E', '#B45309', '#B91C1C', '#6D28D9', '#047857', '#0369A1'];
function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < (name?.length ?? 0); i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}
function initials(name: string) {
  return (name ?? '?').split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
}

/**
 * Health status indicator. The decorative dot on the avatar uses a status palette of its own (bright, well
 * separated in lightness; the darker text shades made MONITOR and CRITICAL look alike), and colour is never
 * the only cue: the full sidebar shows the status word, the icon rail a different icon shape per level, and
 * both are in the accessible name.
 *
 * `text` is the shade for anything that has to be read on a light surface: the status word, and the rail
 * icon on its white disc (7:1 or better; the bright amber dot colour is only 2.15:1 on white, below the 3:1
 * a graphic needs).
 */
interface HealthLook { label: string; dot: string; text: string; icon: IoniconsName }
const HEALTH: Record<string, HealthLook> = {
  STABLE:   { label: 'Stable',   dot: '#16A34A', text: '#166534', icon: 'checkmark-circle' },
  MONITOR:  { label: 'Monitor',  dot: '#F59E0B', text: '#92400E', icon: 'alert-circle' },
  CRITICAL: { label: 'Critical', dot: '#DC2626', text: '#991B1B', icon: 'warning' },
};
// 4.4:1 on the sidebar background, above the 3:1 a graphic needs (the lighter gray was 2.3:1).
const HEALTH_UNKNOWN_DOT = '#6B7280';
function healthLook(status: string | null): HealthLook | null {
  return status ? HEALTH[status] ?? null : null;
}

function accessLabel(level: string) {
  switch (level) {
    case 'FULL_ACCESS': return 'Full Access';
    case 'VIEW_ONLY':   return 'View Only';
    case 'EDIT_ONLY':   return 'Edit Only';
    case 'CUSTOM':      return 'Custom';
    default:            return level;
  }
}

// A navigation mounts a fresh sidebar; whether "+N more" was opened carries over. Cleared on sign-out.
let lovedOnesExpanded = false;
onSignedOut(() => { lovedOnesExpanded = false; });

function SidebarNavItem({ item, active, collapsed, onPress, badge = 0 }: { item: SectionDef; active?: boolean; collapsed?: boolean; onPress?: () => void; badge?: number }) {
  const ref = useNavFocusRestore(item.key);
  return (
    <TouchableOpacity
      ref={ref as never}
      style={[styles.navItem, active && styles.navItemActive]}
      activeOpacity={0.7}
      onPress={() => { rememberNavFocus(item.key); onPress?.(); }}
      accessibilityRole="button"
      accessibilityLabel={navItemLabel(item, badge)}
      accessibilityState={{ selected: !!active }}
      {...(active ? CURRENT_PAGE : null)}
    >
      <View {...decorative}>
        <Icon name={item.icon} size={18} color={active ? WHITE : GRAY} />
        {collapsed && badge > 0 && <View style={styles.badgeDot} />}
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

export interface PatientProxySidebarProps {
  collapsed?:        boolean;
  /** Key of the section being shown. */
  activeKey?:        string;
  onNavigate?:       (key: string) => void;
  onSelectPatient?:  (dep: CaregiverDependent) => void;
}

const SHOW_LIMIT = 3;

/**
 * Caregiver navigation. Every item comes from the section registry (components/web/sections/proxy.sections.tsx);
 * the loved-ones list above them opens a linked patient's profile. The list is the shared one loaded by the
 * caregiver shell (components/web/sections/proxy-dependents.tsx), so a navigation does not request it again.
 */
export function PatientProxySidebar({ collapsed = false, activeKey = registry.homeKey, onNavigate, onSelectPatient }: PatientProxySidebarProps) {
  const { dependents, loading, error, refetch } = useDependents();
  const [expanded, setExpandedState] = useState(lovedOnesExpanded);
  const setExpanded = (next: boolean) => { lovedOnesExpanded = next; setExpandedState(next); };
  const badges = useNavBadges();
  const scroll = useSidebarScrollMemory('proxy');
  const { home, groups } = useMemo(() => navGroups(registry), []);

  return (
    <View style={[styles.sidebar, collapsed && styles.sidebarCollapsed]} {...NAV_LANDMARK}>
      {/* Logo */}
      <View style={styles.logoSection}>
        <View style={styles.logoIconWrapper} {...decorative}>
          <Icon name="heart-outline" size={20} color={PURPLE} />
        </View>
        {!collapsed && (
          <View>
            <Text style={styles.logoText}>
              <Text style={styles.logoCare}>Care</Text>
              <Text style={styles.logoNexus}>Nexus</Text>
            </Text>
            <Text style={styles.logoProxy}>PROXY</Text>
          </View>
        )}
      </View>

      <ScrollView style={styles.scroll} showsVerticalScrollIndicator={false} {...scroll}>
        {/* Home */}
        <SidebarNavItem
          item={home}
          active={activeKey === home.key}
          collapsed={collapsed}
          onPress={() => onNavigate?.(home.key)}
        />

        {/* My Loved Ones */}
        {!collapsed && (
          <>
            <Text style={styles.sectionLabel}>MY LOVED ONES</Text>

            {loading ? (
              <ActivityIndicator size="small" color={PURPLE} style={{ marginVertical: 12 }} accessibilityLabel="Loading loved ones" />
            ) : error ? (
              <View style={styles.errorRow} accessibilityRole="alert">
                <Text style={[styles.emptyText, styles.errorText]}>{error}</Text>
                <TouchableOpacity
                  onPress={refetch}
                  activeOpacity={0.7}
                  accessibilityRole="button"
                  // Starts with the visible word; says what is retried.
                  accessibilityLabel="Retry loading loved ones"
                  style={styles.retryBtn}
                >
                  <Text style={styles.retryText}>Retry</Text>
                </TouchableOpacity>
              </View>
            ) : dependents.length === 0 ? (
              <Text style={styles.emptyText}>No linked patients yet.</Text>
            ) : (
              <>
                <View role="list" aria-label="My loved ones">
                  {(expanded ? dependents : dependents.slice(0, SHOW_LIMIT)).map((dep) => {
                    const name   = dep.patientName ?? 'Unknown';
                    const health = healthLook(dep.patientHealthStatus);
                    const label  = accessLabel(dep.accessLevel);
                    const rel    = dep.relationship
                      ? dep.relationship.charAt(0).toUpperCase() + dep.relationship.slice(1)
                      : '';
                    return (
                      <View key={dep.accessId} role="listitem">
                        <TouchableOpacity
                          style={styles.lovedOneRow}
                          activeOpacity={0.7}
                          onPress={() => onSelectPatient?.(dep)}
                          accessibilityRole="button"
                          // Named by its visible text (name, relationship, access, health status); the initials and dot are decorative.
                        >
                          <View style={styles.avatarWrapper} {...decorative}>
                            <View style={[styles.avatar, styles.avatarFallback, { backgroundColor: avatarColor(name) }]}>
                              <Text style={styles.avatarInitials}>{initials(name)}</Text>
                            </View>
                            <View style={[styles.dot, { backgroundColor: health?.dot ?? HEALTH_UNKNOWN_DOT }]} />
                          </View>
                          <View style={{ flex: 1, minWidth: 0 }}>
                            <Text style={styles.lovedOneName} numberOfLines={1}>{name}{' '}</Text>
                            <Text style={styles.lovedOneRel} numberOfLines={1}>{rel}{rel ? ' ' : ''}</Text>
                            <View style={styles.lovedOneMeta}>
                              <Text style={styles.lovedOneAccess}>{label}{health ? ' ' : ''}</Text>
                              {health && (
                                <View style={styles.healthTag}>
                                  <Icon name={health.icon} size={12} color={health.text} />
                                  <Text style={[styles.healthText, { color: health.text }]}>{health.label}</Text>
                                </View>
                              )}
                            </View>
                          </View>
                        </TouchableOpacity>
                      </View>
                    );
                  })}
                </View>
                {dependents.length > SHOW_LIMIT && (
                  <TouchableOpacity
                    style={styles.expandBtn}
                    activeOpacity={0.7}
                    onPress={() => setExpanded(!expanded)}
                    accessibilityRole="button"
                    aria-expanded={expanded}
                  >
                    <Icon name={expanded ? 'chevron-up' : 'chevron-down'} size={13} color={PURPLE} />
                    <Text style={styles.expandText}>
                      {expanded ? 'Show less' : `+${dependents.length - SHOW_LIMIT} more`}
                    </Text>
                  </TouchableOpacity>
                )}
              </>
            )}

            <TouchableOpacity
              style={styles.addBtn}
              activeOpacity={0.8}
              onPress={() => onNavigate?.('add-dependent')}
              accessibilityRole="button"
            >
              <Icon name="add" size={16} color={PURPLE} />
              <Text style={styles.addBtnText}>Add Another Loved One</Text>
            </TouchableOpacity>
          </>
        )}

        {/* Collapsed: avatars with a health icon */}
        {collapsed && (
          <View style={{ alignItems: 'center', gap: 8, paddingVertical: 8 }} role="list" aria-label="My loved ones">
            {dependents.map((dep) => {
              const name   = dep.patientName ?? '?';
              const health = healthLook(dep.patientHealthStatus);
              return (
                <View key={dep.accessId} role="listitem">
                  <TouchableOpacity
                    activeOpacity={0.7}
                    style={{ position: 'relative' }}
                    onPress={() => onSelectPatient?.(dep)}
                    accessibilityRole="button"
                    // Starts with the visible initials (WCAG 2.5.3 Label in Name); the health status is spoken, not colour only.
                    accessibilityLabel={`${initials(name)}: open profile for ${name}${health ? `, health status ${health.label}` : ''}`}
                  >
                    <View style={[styles.collapsedAvatar, { backgroundColor: avatarColor(name), alignItems: 'center', justifyContent: 'center' }]}>
                      <Text style={{ color: WHITE, fontSize: 11, fontWeight: '700' }}>{initials(name)}</Text>
                    </View>
                    {health ? (
                      <View style={styles.collapsedHealth} {...decorative}>
                        <Icon name={health.icon} size={13} color={health.text} />
                      </View>
                    ) : (
                      <View style={[styles.collapsedDot, { backgroundColor: HEALTH_UNKNOWN_DOT }]} {...decorative} />
                    )}
                  </TouchableOpacity>
                </View>
              );
            })}
          </View>
        )}

        {/* Groups */}
        {groups.map((group, gi) => (
          <View key={group.title}>
            {!collapsed
              ? <Text style={styles.sectionLabel}>{group.title}</Text>
              : gi === 0 ? <View style={styles.sectionSpacer} /> : <View style={styles.sectionDivider} />
            }
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

        <View style={{ height: 24 }} />
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  sidebar: {
    width: 260,
    backgroundColor: PURPLE_BG,
    height: '100%',
    borderRightWidth: 1,
    borderRightColor: BORDER,
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
    width: 38,
    height: 38,
    borderRadius: 19,
    borderWidth: 2,
    borderColor: PURPLE,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: WHITE,
  },
  logoText:  { fontSize: 18, fontWeight: '700', lineHeight: 22 },
  logoCare:  { color: '#1E3A8A' },
  logoNexus: { color: PURPLE },
  logoProxy: { fontSize: 11, fontWeight: '700', color: PURPLE, letterSpacing: 2.5 },

  scroll: { flex: 1, paddingHorizontal: 10 },

  sectionLabel: {
    fontSize: 11,
    fontWeight: '600',
    color: GRAY_LABEL,
    letterSpacing: 1,
    paddingHorizontal: 10,
    paddingTop: 20,
    paddingBottom: 8,
    textTransform: 'uppercase',
  },
  sectionSpacer: { height: 28 },
  sectionDivider: {
    height: 1,
    backgroundColor: BORDER,
    marginVertical: 12,
    marginHorizontal: 4,
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
  navItemActive: { backgroundColor: PURPLE },
  badge:           { marginLeft: 'auto', minWidth: 20, height: 20, borderRadius: 10, paddingHorizontal: 6, backgroundColor: PURPLE, alignItems: 'center', justifyContent: 'center' },
  badgeActive:     { backgroundColor: WHITE },
  badgeText:       { color: WHITE, fontSize: 11, fontWeight: '700' },
  badgeTextActive: { color: PURPLE },
  badgeDot:        { position: 'absolute', top: -2, right: -3, width: 8, height: 8, borderRadius: 4, backgroundColor: '#DC2626' },
  navLabel:      { fontSize: 14, color: TEXT, fontWeight: '400' },
  navLabelActive:{ color: WHITE, fontWeight: '600' },

  lovedOneRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingHorizontal: 10,
    paddingVertical: 10,
    borderRadius: 10,
  },
  avatarWrapper: { position: 'relative' },
  avatar: {
    width: 46,
    height: 46,
    borderRadius: 23,
    backgroundColor: BORDER,
  },
  dot: {
    width: 11,
    height: 11,
    borderRadius: 6,
    borderWidth: 2,
    borderColor: PURPLE_BG,
    position: 'absolute',
    bottom: 1,
    right: 1,
  },
  lovedOneName:   { fontSize: 14, fontWeight: '600', color: TEXT },
  lovedOneRel:    { fontSize: 11, color: PURPLE, fontWeight: '500', marginTop: 1 },
  lovedOneMeta:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', columnGap: 8, rowGap: 2, marginTop: 1 },
  lovedOneAccess: { fontSize: 11, color: GRAY },
  healthTag:      { flexDirection: 'row', alignItems: 'center', gap: 3 },
  healthText:     { fontSize: 11, fontWeight: '700' },
  avatarFallback: { alignItems: 'center', justifyContent: 'center' },
  avatarInitials: { color: WHITE, fontSize: 14, fontWeight: '700' },
  emptyText:      { fontSize: 12, color: GRAY_LABEL, paddingHorizontal: 10, paddingVertical: 8 },
  errorRow:       { paddingBottom: 4 },
  errorText:      { color: RED, paddingBottom: 2 },
  retryBtn:       { alignSelf: 'flex-start', paddingHorizontal: 10, paddingVertical: 4 },
  retryText:      { fontSize: 12, color: PURPLE, fontWeight: '600' },
  collapsedAvatar: { width: 36, height: 36, borderRadius: 18, backgroundColor: BORDER },
  collapsedDot:    { width: 9, height: 9, borderRadius: 5, borderWidth: 2, borderColor: PURPLE_BG, position: 'absolute', bottom: 0, right: 0 },
  collapsedHealth: {
    position: 'absolute', bottom: -3, right: -4, width: 16, height: 16, borderRadius: 8,
    backgroundColor: WHITE, alignItems: 'center', justifyContent: 'center',
  },

  expandBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    paddingHorizontal: 10,
    paddingVertical: 6,
  },
  expandText: { fontSize: 12, color: PURPLE, fontWeight: '600' },

  addBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    marginHorizontal: 10,
    marginTop: 8,
    borderWidth: 1,
    borderColor: PURPLE,
    borderRadius: 8,
    paddingVertical: 9,
    paddingHorizontal: 12,
    backgroundColor: WHITE,
  },
  addBtnText: { fontSize: 13, color: PURPLE, fontWeight: '500' },

});
