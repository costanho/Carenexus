import { Ionicons } from '@expo/vector-icons';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useLogout } from '@/hooks/use-logout';

const BG        = '#0D1B2E';
const ACTIVE_BG = '#4F6EF7';
const WHITE     = '#FFFFFF';
const GRAY      = '#6B8099';
const TEAL      = '#2DD4BF';
const DIVIDER   = '#1E2D42';
const LOGOUT_RED = '#EF4444';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type NavItem      = { key: string; label: string; icon: IoniconsName };
type NavSection   = { title: string; items: NavItem[] };

const sections: NavSection[] = [
  {
    title: 'CONSULTATIONS',
    items: [
      { key: 'schedule',      label: 'Schedule',      icon: 'calendar-outline' },
      { key: 'consultations', label: 'Consultations', icon: 'videocam-outline' },
      { key: 'appointments',  label: 'Appointments',  icon: 'calendar-number-outline' },
    ],
  },
  {
    title: 'PATIENTS & RECORDS',
    items: [
      { key: 'patients',        label: 'Patients',        icon: 'people-outline' },
      { key: 'medical-records', label: 'Medical Records', icon: 'folder-open-outline' },
      { key: 'documents',       label: 'Documents',       icon: 'document-outline' },
    ],
  },
  {
    title: 'CLINICAL',
    items: [
      { key: 'prescriptions', label: 'Prescriptions',  icon: 'medkit-outline' },
      { key: 'referrals',     label: 'Referrals',      icon: 'git-compare-outline' },
      { key: 'labs',          label: 'Labs & Results', icon: 'flask-outline' },
    ],
  },
  {
    title: 'COMMUNICATION',
    items: [
      { key: 'messages',      label: 'Messages',       icon: 'chatbubble-outline' },
      { key: 'notifications', label: 'Notifications',  icon: 'notifications-outline' },
    ],
  },
  {
    title: 'SETTINGS',
    items: [
      { key: 'profile',      label: 'Profile',        icon: 'person-outline' },
      { key: 'settings',     label: 'Settings',       icon: 'settings-outline' },
      { key: 'help-support', label: 'Help & Support', icon: 'help-circle-outline' },
    ],
  },
];

function SidebarNavItem({ item, active, collapsed, onPress }: {
  item: NavItem; active: boolean; collapsed: boolean; onPress: () => void;
}) {
  return (
    <TouchableOpacity
      style={[styles.navItem, active && styles.navItemActive]}
      activeOpacity={0.7}
      onPress={onPress}
    >
      <Ionicons name={item.icon} size={18} color={active ? WHITE : '#8BA3BE'} />
      {!collapsed && (
        <Text style={[styles.navLabel, active && styles.navLabelActive]}>{item.label}</Text>
      )}
    </TouchableOpacity>
  );
}

export interface DoctorLeftSidebarProps {
  collapsed?:  boolean;
  activeKey?:  string;
  onNavigate?: (key: string) => void;
}

export function DoctorLeftSidebar({ collapsed = false, activeKey = 'dashboard', onNavigate }: DoctorLeftSidebarProps) {
  const { handleLogout, loading } = useLogout();

  return (
    <View style={[styles.sidebar, collapsed && styles.sidebarCollapsed]}>
      {/* Logo */}
      <View style={styles.logoSection}>
        <View style={styles.logoIconWrapper}>
          <Ionicons name="heart" size={16} color={TEAL} />
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

      <ScrollView style={styles.scroll} showsVerticalScrollIndicator={false}>
        {/* Dashboard */}
        <TouchableOpacity
          style={[styles.navItem, activeKey === 'dashboard' && styles.navItemActive]}
          activeOpacity={0.8}
          onPress={() => onNavigate?.('dashboard')}
        >
          <Ionicons name="home-outline" size={18} color={activeKey === 'dashboard' ? WHITE : '#8BA3BE'} />
          {!collapsed && (
            <Text style={[styles.navLabel, activeKey === 'dashboard' && styles.navLabelActive]}>Dashboard</Text>
          )}
        </TouchableOpacity>

        {/* Sections */}
        {sections.map((section, si) => (
          <View key={section.title}>
            <View style={[styles.sectionDivider, si === 0 && { marginTop: 8 }]} />
            {!collapsed && <Text style={styles.sectionTitle}>{section.title}</Text>}
            {section.items.map((item) => (
              <SidebarNavItem
                key={item.key}
                item={item}
                active={activeKey === item.key}
                collapsed={collapsed}
                onPress={() => onNavigate?.(item.key)}
              />
            ))}
          </View>
        ))}

        <View style={styles.spacer} />
      </ScrollView>

      {/* Footer */}
      <View style={styles.footer}>
        <View style={styles.divider} />

        {/* Brand / version row */}
        <View style={[styles.brandRow, collapsed && styles.brandRowCollapsed]}>
          <View style={styles.footerIconBg}>
            <Ionicons name="shield-outline" size={16} color={TEAL} />
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
        >
          <View style={styles.logoutIconBg}>
            <Ionicons
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
