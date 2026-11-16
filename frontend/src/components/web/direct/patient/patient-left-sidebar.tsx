import { Ionicons } from '@expo/vector-icons';
import { Image, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useLogout } from '@/hooks/use-logout';
import { useUnreadCounts } from '@/hooks/use-notifications';

const TEAL       = '#0F766E';
const TEAL_BG    = '#E8F5F4';
const GRAY_LABEL = '#636A75';
const ICON_GRAY  = '#6B7280';
const TEXT       = '#111827';
const WHITE      = '#FFFFFF';
const LOGOUT_RED = '#B91C1C';
const DISABLED_GRAY = '#D1D5DB';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
// `disabled` marks features that have no screen/backend yet: shown greyed out with a "Soon" pill, not navigable.
type NavItem      = { key: string; label: string; icon: IoniconsName; active?: boolean; disabled?: boolean };
type NavSection   = { title: string; items: NavItem[] };

const sections: NavSection[] = [
  {
    title: 'APPOINTMENTS',
    items: [
      { key: 'book-appointment', label: 'Book Appointment',    icon: 'calendar-outline' },
      { key: 'my-appointments',  label: 'My Appointments',     icon: 'calendar-number-outline' },
    ],
  },
  {
    title: 'CONSULTATIONS',
    items: [
      { key: 'join-consultation',    label: 'Join Consultation',    icon: 'videocam-outline' },
      { key: 'consultation-history', label: 'Consultation History', icon: 'document-text-outline' },
    ],
  },
  {
    title: 'MY HEALTH',
    items: [
      { key: 'medical-records', label: 'Medical Records', icon: 'folder-open-outline' },
      { key: 'prescriptions',   label: 'Prescriptions',   icon: 'medkit-outline' },
      { key: 'lab-results',     label: 'Lab Results',     icon: 'flask-outline' },
      { key: 'imaging-results', label: 'Imaging Results', icon: 'image-outline' },
      { key: 'referrals',       label: 'Referrals',       icon: 'git-compare-outline', disabled: true },
    ],
  },
  {
    title: 'CARE & SUPPORT',
    items: [
      { key: 'my-proxies',    label: 'My Proxies (Caregivers)', icon: 'people-outline' },
      { key: 'messages',      label: 'Messages',                icon: 'chatbubble-outline' },
      { key: 'notifications', label: 'Notifications',           icon: 'notifications-outline' },
    ],
  },
  {
    title: 'ACCOUNT',
    items: [
      { key: 'profile-settings', label: 'Profile & Settings', icon: 'settings-outline' },
    ],
  },
];

function SidebarItem({
  item, collapsed, isActive, onNavigate, badge = 0,
}: {
  item: NavItem; collapsed: boolean; isActive: boolean; onNavigate: (key: string) => void; badge?: number;
}) {
  const disabled = !!item.disabled;
  const color = disabled ? DISABLED_GRAY : isActive ? TEAL : ICON_GRAY;
  return (
    <TouchableOpacity
      style={[styles.navItem, isActive && styles.navItemActive, collapsed && styles.navItemCollapsed]}
      activeOpacity={0.7}
      onPress={disabled ? undefined : () => onNavigate(item.key)}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityLabel={disabled ? `${item.label}, coming soon` : badge > 0 ? `${item.label}, ${badge} unread` : item.label}
      accessibilityState={{ selected: isActive, disabled }}
    >
      <View>
        <Ionicons name={item.icon} size={18} color={color} />
        {collapsed && badge > 0 && <View style={styles.badgeDot} />}
      </View>
      {!collapsed && (
        <Text style={[styles.navLabel, isActive && styles.navLabelActive, disabled && styles.navLabelDisabled]}>{item.label}{' '}</Text>
      )}
      {!collapsed && disabled && (
        <View style={styles.soonPill}><Text style={styles.soonPillText}>Coming soon</Text></View>
      )}
      {!collapsed && !disabled && badge > 0 && (
        <View style={styles.badge}><Text style={styles.badgeText}>{badge > 99 ? '99+' : badge}</Text></View>
      )}
    </TouchableOpacity>
  );
}

export interface PatientSidebarProps {
  collapsed?:   boolean;
  onClose?:     () => void;
  activeKey?:   string;
  onNavigate?:  (key: string) => void;
}

export function PatientSidebar({ collapsed = false, onClose, activeKey = 'dashboard', onNavigate }: PatientSidebarProps) {
  const { user } = useCurrentUser();
  const { handleLogout, loading } = useLogout();
  // The top navbar bell polls the counts; the sidebar only reads them.
  const { unreadCount, unreadMessageCount } = useUnreadCounts(false);
  const fullName  = user ? `${user.first_name} ${user.last_name}` : '...';
  const avatarUri = user?.avatar_url ?? null;

  return (
    <View style={[styles.sidebar, collapsed && styles.sidebarCollapsed]}>
      {/* Logo */}
      <View style={[styles.logoSection, collapsed && styles.logoCollapsed]}>
        <View style={styles.logoIconWrapper}>
          <Ionicons name="heart-outline" size={18} color={TEAL} />
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
        {onClose && (
          <TouchableOpacity onPress={onClose} style={styles.closeBtn} accessibilityRole="button" accessibilityLabel="Close menu">
            <Ionicons name="close" size={20} color={ICON_GRAY} />
          </TouchableOpacity>
        )}
      </View>

      <View style={styles.divider} />

      <ScrollView style={styles.scroll} showsVerticalScrollIndicator={false}>
        {/* Profile */}
        {!collapsed ? (
          <View style={styles.profileSection}>
            {avatarUri
              ? <Image source={{ uri: avatarUri }} style={styles.avatar} />
              : <View style={[styles.avatar, styles.avatarFallback]}><Text style={styles.avatarInitial}>{user?.first_name?.[0] ?? '?'}</Text></View>
            }
            <View style={styles.flex1}>
              <Text style={styles.helloText}>Hello,</Text>
              <Text style={styles.nameText}>{fullName} 👋</Text>
              <Text style={styles.roleText}>Patient</Text>
            </View>
          </View>
        ) : (
          <View style={styles.avatarCollapsed}>
            {avatarUri
              ? <Image source={{ uri: avatarUri }} style={styles.avatarSmall} />
              : <View style={[styles.avatarSmall, styles.avatarFallback]}><Text style={styles.avatarInitial}>{user?.first_name?.[0] ?? '?'}</Text></View>
            }
          </View>
        )}

        {/* Dashboard */}
        <SidebarItem
          collapsed={collapsed}
          item={{ key: 'dashboard', label: 'Dashboard', icon: 'home' }}
          isActive={activeKey === 'dashboard'}
          onNavigate={onNavigate ?? (() => {})}
        />

        {/* Sections */}
        {sections.map((section) => (
          <View key={section.title}>
            {!collapsed
              ? <Text style={styles.sectionTitle}>{section.title}</Text>
              : <View style={styles.sectionDivider} />
            }
            {section.items.map((item) => (
              <SidebarItem
                key={item.key}
                collapsed={collapsed}
                item={item}
                isActive={activeKey === item.key}
                onNavigate={onNavigate ?? (() => {})}
                badge={item.key === 'messages' ? unreadMessageCount : item.key === 'notifications' ? unreadCount : 0}
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
          accessibilityLabel="Sign out"
          accessibilityState={{ disabled: loading }}
        >
          <View style={styles.logoutIconWrap}>
            <Ionicons
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
  closeBtn:   { padding: 4 },

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

  navLabelDisabled: { color: GRAY_LABEL },
  badge:        { minWidth: 20, height: 20, borderRadius: 10, paddingHorizontal: 6, backgroundColor: TEAL, alignItems: 'center', justifyContent: 'center' },
  badgeText:    { color: WHITE, fontSize: 11, fontWeight: '700' },
  badgeDot:     { position: 'absolute', top: -2, right: -3, width: 8, height: 8, borderRadius: 4, backgroundColor: '#DC2626', borderWidth: 1, borderColor: WHITE },
  soonPill:     { backgroundColor: '#F3F4F6', borderRadius: 10, paddingHorizontal: 8, paddingVertical: 2 },
  soonPillText: { fontSize: 10, fontWeight: '700', color: GRAY_LABEL, letterSpacing: 0.4 },

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
