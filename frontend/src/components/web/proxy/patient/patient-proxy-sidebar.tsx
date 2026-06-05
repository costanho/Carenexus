import { Ionicons } from '@expo/vector-icons';
import { Image, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';

const PURPLE      = '#7C3AED';
const PURPLE_LIGHT= '#EDE9FE';
const PURPLE_BG   = '#F5F3FF';
const TEXT        = '#111827';
const GRAY        = '#6B7280';
const GRAY_LABEL  = '#9CA3AF';
const WHITE       = '#FFFFFF';
const BORDER      = '#E5E7EB';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type NavItem      = { key: string; label: string; icon: IoniconsName };

const careItems: NavItem[] = [
  { key: 'appointments',       label: 'Appointments',        icon: 'calendar-outline' },
  { key: 'medications',        label: 'Medications',         icon: 'pencil-outline' },
  { key: 'messages',           label: 'Messages',            icon: 'chatbox-outline' },
  { key: 'care-plans',         label: 'Care Plans',          icon: 'document-text-outline' },
  { key: 'documents-results',  label: 'Documents & Results', icon: 'document-outline' },
  { key: 'health-summary',     label: 'Health Summary',      icon: 'download-outline' },
];

const accountItems: NavItem[] = [
  { key: 'profile-settings',      label: 'Profile & Settings',    icon: 'settings-outline' },
  { key: 'notification-settings', label: 'Notification Settings', icon: 'notifications-outline' },
];

type LovedOne = { name: string; relation: string; access: string; avatarId: number; dotColor: string };

const lovedOnes: LovedOne[] = [
  { name: 'Mom (Mary Davis)',   relation: 'Mom',  access: 'Full Access', avatarId: 47, dotColor: '#22C55E' },
  { name: 'Dad (Robert Davis)', relation: 'Dad',  access: 'View Only',   avatarId: 15, dotColor: '#F59E0B' },
];

function SidebarNavItem({ item, active }: { item: NavItem; active?: boolean }) {
  return (
    <TouchableOpacity
      style={[styles.navItem, active && styles.navItemActive]}
      activeOpacity={0.7}
    >
      <Ionicons name={item.icon} size={18} color={active ? WHITE : GRAY} />
      <Text style={[styles.navLabel, active && styles.navLabelActive]}>{item.label}</Text>
    </TouchableOpacity>
  );
}

export interface PatientProxySidebarProps {
  collapsed?: boolean;
}

export function PatientProxySidebar({ collapsed = false }: PatientProxySidebarProps) {
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
        {/* Dashboard active */}
        <SidebarNavItem item={{ key: 'dashboard', label: 'Dashboard', icon: 'home-outline' }} active />

        {/* My Loved Ones */}
        {!collapsed && (
          <>
            <Text style={styles.sectionLabel}>MY LOVED ONES</Text>
            {lovedOnes.map((person) => (
              <TouchableOpacity key={person.name} style={styles.lovedOneRow} activeOpacity={0.7}>
                <View style={styles.avatarWrapper}>
                  <Image
                    source={{ uri: `https://i.pravatar.cc/150?img=${person.avatarId}` }}
                    style={styles.avatar}
                  />
                  <View style={[styles.dot, { backgroundColor: person.dotColor }]} />
                </View>
                <View>
                  <Text style={styles.lovedOneName}>{person.name}</Text>
                  <Text style={styles.lovedOneAccess}>{person.access}</Text>
                </View>
              </TouchableOpacity>
            ))}
            <TouchableOpacity style={styles.addBtn} activeOpacity={0.8}>
              <Ionicons name="add" size={16} color={PURPLE} />
              <Text style={styles.addBtnText}>Add Another Loved One</Text>
            </TouchableOpacity>
          </>
        )}

        {/* Care Coordination */}
        <Text style={styles.sectionLabel}>{collapsed ? '' : 'CARE COORDINATION'}</Text>
        {careItems.map((item) => (
          <SidebarNavItem key={item.key} item={item} />
        ))}

        {/* Account */}
        {!collapsed && <Text style={styles.sectionLabel}>ACCOUNT</Text>}
        {collapsed && <View style={styles.sectionDivider} />}
        {accountItems.map((item) => (
          <SidebarNavItem key={item.key} item={item} />
        ))}

        {/* Help card */}
        {!collapsed && (
          <View style={styles.helpCard}>
            <View style={styles.helpIconWrapper}>
              <Ionicons name="headset-outline" size={26} color={PURPLE} />
            </View>
            <View>
              <Text style={styles.helpTitle}>Need Help?</Text>
              <Text style={styles.helpSub}>We're here for you.</Text>
              <TouchableOpacity activeOpacity={0.7}>
                <Text style={styles.helpLink}>Contact Support</Text>
              </TouchableOpacity>
            </View>
          </View>
        )}

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
  lovedOneAccess: { fontSize: 12, color: GRAY, marginTop: 1 },

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

  helpCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    backgroundColor: PURPLE_LIGHT,
    borderRadius: 12,
    padding: 16,
    marginTop: 24,
    marginHorizontal: 4,
  },
  helpIconWrapper: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: WHITE,
    alignItems: 'center',
    justifyContent: 'center',
  },
  helpTitle: { fontSize: 14, fontWeight: '700', color: PURPLE },
  helpSub:   { fontSize: 12, color: GRAY, marginTop: 2 },
  helpLink:  { fontSize: 13, color: PURPLE, fontWeight: '600', marginTop: 4 },
});
