import { Ionicons } from '@expo/vector-icons';
import { Image, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';

const TEAL       = '#0D9488';
const TEAL_BG    = '#E8F5F4';
const GRAY_LABEL = '#9CA3AF';
const ICON_GRAY  = '#6B7280';
const TEXT       = '#111827';
const SUPPORT_BG = '#F3F4F6';
const WHITE      = '#FFFFFF';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type NavItem      = { key: string; label: string; icon: IoniconsName; active?: boolean };
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
      { key: 'referrals',       label: 'Referrals',       icon: 'git-compare-outline' },
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

function SidebarItem({ item, collapsed }: { item: NavItem; collapsed: boolean }) {
  const color = item.active ? TEAL : ICON_GRAY;
  return (
    <TouchableOpacity
      style={[styles.navItem, item.active && styles.navItemActive, collapsed && styles.navItemCollapsed]}
      activeOpacity={0.7}
    >
      <Ionicons name={item.icon} size={18} color={color} />
      {!collapsed && (
        <Text style={[styles.navLabel, item.active && styles.navLabelActive]}>{item.label}</Text>
      )}
    </TouchableOpacity>
  );
}

export interface PatientSidebarProps {
  collapsed?: boolean;
  onClose?: () => void;
}

export function PatientSidebar({ collapsed = false, onClose }: PatientSidebarProps) {
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
          <TouchableOpacity onPress={onClose} style={styles.closeBtn}>
            <Ionicons name="close" size={20} color={ICON_GRAY} />
          </TouchableOpacity>
        )}
      </View>

      <View style={styles.divider} />

      <ScrollView style={styles.scroll} showsVerticalScrollIndicator={false}>
        {/* Profile */}
        {!collapsed ? (
          <View style={styles.profileSection}>
            <Image source={{ uri: 'https://i.pravatar.cc/150?img=47' }} style={styles.avatar} />
            <View style={styles.flex1}>
              <Text style={styles.helloText}>Hello,</Text>
              <Text style={styles.nameText}>Linda Davis 👋</Text>
              <Text style={styles.roleText}>Patient</Text>
            </View>
          </View>
        ) : (
          <View style={styles.avatarCollapsed}>
            <Image source={{ uri: 'https://i.pravatar.cc/150?img=47' }} style={styles.avatarSmall} />
          </View>
        )}

        {/* Dashboard */}
        <SidebarItem collapsed={collapsed} item={{ key: 'dashboard', label: 'Dashboard', icon: 'home', active: true }} />

        {/* Sections */}
        {sections.map((section) => (
          <View key={section.title}>
            {!collapsed
              ? <Text style={styles.sectionTitle}>{section.title}</Text>
              : <View style={styles.sectionDivider} />
            }
            {section.items.map((item) => (
              <SidebarItem key={item.key} collapsed={collapsed} item={item} />
            ))}
          </View>
        ))}

        {/* Support */}
        {!collapsed ? (
          <View style={styles.supportCard}>
            <Ionicons name="headset-outline" size={28} color={TEAL} />
            <View>
              <Text style={styles.supportTitle}>Need Help?</Text>
              <Text style={styles.supportSub}>Contact Support</Text>
            </View>
          </View>
        ) : (
          <TouchableOpacity style={styles.navItemCollapsed}>
            <Ionicons name="headset-outline" size={20} color={TEAL} />
          </TouchableOpacity>
        )}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  sidebar: {
    width: 260, backgroundColor: WHITE,
    height: '100%', borderRightWidth: 1, borderRightColor: '#E5E7EB',
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

  supportCard: {
    flexDirection: 'row', alignItems: 'center', gap: 12,
    backgroundColor: SUPPORT_BG, borderRadius: 12, padding: 16,
    marginTop: 20, marginBottom: 24, marginHorizontal: 4,
  },
  supportTitle: { fontSize: 15, fontWeight: '700', color: TEAL },
  supportSub:   { fontSize: 12, color: TEAL },
});
