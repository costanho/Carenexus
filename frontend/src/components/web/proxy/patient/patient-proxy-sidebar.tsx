import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { type CaregiverDependent, useCaregiverDependents } from '@/hooks/use-caregiver-dependents';
import { useUnreadCounts } from '@/hooks/use-notifications';
import { SoonPill } from './proxy-states';


const PURPLE      = '#7C3AED';
const PURPLE_BG   = '#F5F3FF';
const TEXT        = '#111827';
const GRAY        = '#5B6472';
const GRAY_LABEL  = '#636A75';
const WHITE       = '#FFFFFF';
const BORDER      = '#E5E7EB';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type NavItem      = { key: string; label: string; icon: IoniconsName; comingSoon?: boolean };

const careItems: NavItem[] = [
  { key: 'appointments',       label: 'Appointments',        icon: 'calendar-outline' },
  { key: 'medications',        label: 'Medications',         icon: 'pencil-outline' },
  { key: 'messages',           label: 'Messages',            icon: 'chatbox-outline' },
  { key: 'notifications',      label: 'Notifications',       icon: 'notifications-outline' },
  { key: 'care-plans',         label: 'Care Plans',          icon: 'document-text-outline', comingSoon: true },
  { key: 'documents-results',  label: 'Documents & Results', icon: 'document-outline' },
  { key: 'health-summary',     label: 'Health Summary',      icon: 'download-outline' },
];

const accountItems: NavItem[] = [
  { key: 'profile-settings',      label: 'Profile & Settings',    icon: 'settings-outline' },
  { key: 'notification-settings', label: 'Notification Settings', icon: 'options-outline' },
];

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
function healthDotColor(status: string | null) {
  switch (status) {
    case 'STABLE':   return '#22C55E';
    case 'MONITOR':  return '#F59E0B';
    case 'CRITICAL': return '#EF4444';
    default:         return '#9CA3AF';
  }
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

function SidebarNavItem({ item, active, collapsed, onPress, badge = 0 }: { item: NavItem; active?: boolean; collapsed?: boolean; onPress?: () => void; badge?: number }) {
  const disabled = !!item.comingSoon;
  return (
    <TouchableOpacity
      style={[styles.navItem, active && styles.navItemActive, disabled && styles.navItemDisabled]}
      activeOpacity={0.7}
      onPress={disabled ? undefined : onPress}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityLabel={disabled ? `${item.label}, coming soon` : badge > 0 ? `${item.label}, ${badge} unread` : item.label}
      accessibilityState={{ selected: !!active, disabled }}
    >
      <View>
        <Ionicons name={item.icon} size={18} color={active ? WHITE : GRAY} importantForAccessibility="no" />
        {collapsed && badge > 0 && <View style={styles.badgeDot} />}
      </View>
      {/* The collapsed rail shows icons only; the label stays the accessible name. */}
      {!collapsed && <Text style={[styles.navLabel, active && styles.navLabelActive]}>{item.label}{' '}</Text>}
      {!collapsed && disabled && <SoonPill />}
      {!collapsed && !disabled && badge > 0 && (
        <View style={[styles.badge, active && styles.badgeActive]}>
          <Text style={[styles.badgeText, active && styles.badgeTextActive]}>{badge > 99 ? '99+' : badge}</Text>
        </View>
      )}
    </TouchableOpacity>
  );
}

export interface PatientProxySidebarProps {
  collapsed?:        boolean;
  activeKey?:        string;
  onNavigate?:       (key: string) => void;
  onSelectPatient?:  (dep: CaregiverDependent) => void;
}

const SHOW_LIMIT = 3;

export function PatientProxySidebar({ collapsed = false, activeKey = 'dashboard', onNavigate, onSelectPatient }: PatientProxySidebarProps) {
  const { dependents, loading, error } = useCaregiverDependents();
  const [expanded, setExpanded] = useState(false);
  // The top navbar bell polls the counts; the sidebar only reads them.
  const { unreadCount, unreadMessageCount } = useUnreadCounts(false);

  return (
    <View style={[styles.sidebar, collapsed && styles.sidebarCollapsed]}>
      {/* Logo */}
      <View style={styles.logoSection}>
        <View style={styles.logoIconWrapper}>
          <Ionicons name="heart-outline" size={20} color={PURPLE} />
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

      <ScrollView style={styles.scroll} showsVerticalScrollIndicator={false}>
        {/* Dashboard */}
        <SidebarNavItem
          item={{ key: 'dashboard', label: 'Dashboard', icon: 'home-outline' }}
          active={activeKey === 'dashboard'}
          collapsed={collapsed}
          onPress={() => onNavigate?.('dashboard')}
        />

        {/* My Loved Ones */}
        {!collapsed && (
          <>
            <Text style={styles.sectionLabel}>MY LOVED ONES</Text>

            {loading ? (
              <ActivityIndicator size="small" color={PURPLE} style={{ marginVertical: 12 }} />
            ) : error ? (
              <Text style={[styles.emptyText, { color: '#EF4444' }]}>{error}</Text>
            ) : dependents.length === 0 ? (
              <Text style={styles.emptyText}>No linked patients yet.</Text>
            ) : (
              <>
                {(expanded ? dependents : dependents.slice(0, SHOW_LIMIT)).map((dep) => {
                  const name     = dep.patientName ?? 'Unknown';
                  const dotColor = healthDotColor(dep.patientHealthStatus);
                  const label    = accessLabel(dep.accessLevel);
                  const rel      = dep.relationship
                    ? dep.relationship.charAt(0).toUpperCase() + dep.relationship.slice(1)
                    : '';
                  return (
                    <TouchableOpacity
                      key={dep.accessId}
                      style={styles.lovedOneRow}
                      activeOpacity={0.7}
                      onPress={() => onSelectPatient?.(dep)}
                      accessibilityRole="button"
                      // Named by its visible text (name, relationship, access); the initials are decorative.
                    >
                      <View style={styles.avatarWrapper} importantForAccessibility="no-hide-descendants">
                        <View style={[styles.avatar, styles.avatarFallback, { backgroundColor: avatarColor(name) }]}>
                          <Text style={styles.avatarInitials}>{initials(name)}</Text>
                        </View>
                        <View style={[styles.dot, { backgroundColor: dotColor }]} />
                      </View>
                      <View style={{ flex: 1 }}>
                        <Text style={styles.lovedOneName} numberOfLines={1}>{name}{' '}</Text>
                        <Text style={styles.lovedOneRel} numberOfLines={1}>{rel}{rel ? ' ' : ''}</Text>
                        <Text style={styles.lovedOneAccess}>{label}</Text>
                      </View>
                    </TouchableOpacity>
                  );
                })}
                {dependents.length > SHOW_LIMIT && (
                  <TouchableOpacity
                    style={styles.expandBtn}
                    activeOpacity={0.7}
                    onPress={() => setExpanded(e => !e)}
                    accessibilityRole="button"
                    accessibilityState={{ expanded }}
                    aria-expanded={expanded}
                  >
                    <Ionicons name={expanded ? 'chevron-up' : 'chevron-down'} size={13} color={PURPLE} />
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
              <Ionicons name="add" size={16} color={PURPLE} importantForAccessibility="no" />
              <Text style={styles.addBtnText}>Add Another Loved One</Text>
            </TouchableOpacity>
          </>
        )}

        {/* Collapsed: dot indicators only */}
        {collapsed && (
          <View style={{ alignItems: 'center', gap: 8, paddingVertical: 8 }}>
            {dependents.map((dep) => {
              const name     = dep.patientName ?? '?';
              const dotColor = healthDotColor(dep.patientHealthStatus);
              return (
                <TouchableOpacity
                  key={dep.accessId}
                  activeOpacity={0.7}
                  style={{ position: 'relative' }}
                  onPress={() => onSelectPatient?.(dep)}
                  accessibilityRole="button"
                  // Starts with the visible initials (WCAG 2.5.3 Label in Name).
                  accessibilityLabel={`${initials(name)}: open profile for ${name}`}
                >
                  <View style={[styles.collapsedAvatar, { backgroundColor: avatarColor(name), alignItems: 'center', justifyContent: 'center' }]}>
                    <Text style={{ color: WHITE, fontSize: 11, fontWeight: '700' }}>{initials(name)}</Text>
                  </View>
                  <View style={[styles.collapsedDot, { backgroundColor: dotColor }]} />
                </TouchableOpacity>
              );
            })}
          </View>
        )}

        {/* Care Coordination */}
        <Text style={styles.sectionLabel}>{collapsed ? '' : 'CARE COORDINATION'}</Text>
        {careItems.map((item) => (
          <SidebarNavItem
            key={item.key}
            item={item}
            active={activeKey === item.key}
            collapsed={collapsed}
            onPress={() => onNavigate?.(item.key)}
            badge={item.key === 'messages' ? unreadMessageCount : item.key === 'notifications' ? unreadCount : 0}
          />
        ))}

        {/* Account */}
        {!collapsed && <Text style={styles.sectionLabel}>ACCOUNT</Text>}
        {collapsed && <View style={styles.sectionDivider} />}
        {accountItems.map((item) => (
          <SidebarNavItem
            key={item.key}
            item={item}
            active={activeKey === item.key}
            collapsed={collapsed}
            onPress={() => onNavigate?.(item.key)}
          />
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
  navItemDisabled: { opacity: 0.55 },
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
  lovedOneAccess: { fontSize: 11, color: GRAY, marginTop: 1 },
  avatarFallback: { alignItems: 'center', justifyContent: 'center' },
  avatarInitials: { color: WHITE, fontSize: 14, fontWeight: '700' },
  emptyText:      { fontSize: 12, color: GRAY_LABEL, paddingHorizontal: 10, paddingVertical: 8 },
  collapsedAvatar: { width: 36, height: 36, borderRadius: 18, backgroundColor: BORDER },
  collapsedDot:    { width: 9, height: 9, borderRadius: 5, borderWidth: 2, borderColor: PURPLE_BG, position: 'absolute', bottom: 0, right: 0 },

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
