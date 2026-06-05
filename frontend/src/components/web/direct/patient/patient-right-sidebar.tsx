import { Ionicons } from '@expo/vector-icons';
import { Image, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';

const TEAL = '#0D9488';
const TEAL_BG = '#E8F5F4';
const NAVY = '#1E3A5F';
const GRAY = '#6B7280';
const GRAY_LIGHT = '#F3F4F6';
const BORDER = '#E5E7EB';
const WHITE = '#FFFFFF';
const PURPLE_BG = '#EEF0FB';
const PURPLE = '#5B6BB5';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

// ── Upcoming Appointment ────────────────────────────────────────────

function UpcomingAppointment() {
  return (
    <View style={styles.card}>
      <View style={styles.cardHeader}>
        <Text style={styles.cardTitle}>Upcoming Appointment</Text>
        <TouchableOpacity><Text style={styles.link}>View all</Text></TouchableOpacity>
      </View>

      <View style={styles.apptInner}>
        {/* Date & time */}
        <View style={styles.apptTopRow}>
          <View style={styles.apptIconBg}>
            <Ionicons name="calendar-outline" size={24} color={TEAL} />
          </View>
          <View>
            <Text style={styles.apptDate}>24 May 2024</Text>
            <Text style={styles.apptTime}>10:30 AM</Text>
          </View>
        </View>

        {/* Details */}
        <Text style={styles.apptTitle}>Follow-up Consultation</Text>
        <Text style={styles.apptSub}>with Dr. Matt (Physician)</Text>

        <View style={styles.apptMeta}>
          <Ionicons name="videocam-outline" size={15} color={GRAY} />
          <Text style={styles.apptMetaText}>Video Consultation</Text>
        </View>
        <View style={styles.apptMeta}>
          <Ionicons name="time-outline" size={15} color={GRAY} />
          <Text style={styles.apptMetaText}>30 minutes</Text>
        </View>

        {/* Actions */}
        <TouchableOpacity style={styles.joinBtn} activeOpacity={0.85}>
          <Ionicons name="videocam" size={16} color={WHITE} />
          <Text style={styles.joinBtnText}>Join Consultation</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.rescheduleBtn} activeOpacity={0.7}>
          <Text style={styles.rescheduleBtnText}>Reschedule</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

// ── My Proxies ──────────────────────────────────────────────────────

type Proxy = { name: string; relation: string; access: string; img: string };

const proxies: Proxy[] = [
  { name: 'Sarah Davis', relation: 'Daughter', access: 'Full Access', img: 'https://i.pravatar.cc/150?img=5' },
  { name: 'James Davis', relation: 'Son', access: 'View Only', img: 'https://i.pravatar.cc/150?img=12' },
];

function MyProxies() {
  return (
    <View style={styles.card}>
      <View style={styles.cardHeader}>
        <Text style={styles.cardTitle}>My Proxies (Caregivers)</Text>
        <TouchableOpacity><Text style={styles.link}>Manage</Text></TouchableOpacity>
      </View>

      {proxies.map((p, i) => (
        <View key={p.name}>
          <TouchableOpacity style={styles.proxyRow} activeOpacity={0.7}>
            <Image source={{ uri: p.img }} style={styles.proxyAvatar} />
            <View style={styles.proxyInfo}>
              <Text style={styles.proxyName}>{p.name} <Text style={styles.proxyRelation}>({p.relation})</Text></Text>
              <Text style={styles.proxyAccess}>{p.access}</Text>
            </View>
            <Ionicons name="chevron-forward" size={16} color={GRAY} />
          </TouchableOpacity>
          {i < proxies.length - 1 && <View style={styles.rowDivider} />}
        </View>
      ))}

      <TouchableOpacity style={styles.manageLink}>
        <Text style={styles.manageLinkText}>Add or Manage Proxies →</Text>
      </TouchableOpacity>
    </View>
  );
}

// ── Quick Access ────────────────────────────────────────────────────

type QuickItem = { label: string; icon: IoniconsName };

const quickItems: QuickItem[] = [
  { label: 'Medical Records', icon: 'folder-open-outline' },
  { label: 'Lab Results', icon: 'flask-outline' },
  { label: 'Imaging Results', icon: 'image-outline' },
  { label: 'Referrals', icon: 'git-compare-outline' },
];

function QuickAccess() {
  return (
    <View style={styles.card}>
      <Text style={[styles.cardTitle, { marginBottom: 8 }]}>Quick Access</Text>
      {quickItems.map((item, i) => (
        <View key={item.label}>
          <TouchableOpacity style={styles.quickRow} activeOpacity={0.7}>
            <Ionicons name={item.icon} size={18} color={GRAY} />
            <Text style={styles.quickLabel}>{item.label}</Text>
            <Ionicons name="chevron-forward" size={16} color={GRAY} />
          </TouchableOpacity>
          {i < quickItems.length - 1 && <View style={styles.rowDivider} />}
        </View>
      ))}
    </View>
  );
}

// ── Need Help ───────────────────────────────────────────────────────

function NeedHelp() {
  return (
    <View style={styles.helpCard}>
      <View style={styles.helpText}>
        <Text style={styles.helpTitle}>Need help?</Text>
        <Text style={styles.helpSub}>Our support team is here for you.</Text>
        <TouchableOpacity>
          <Text style={styles.helpLink}>Contact Support →</Text>
        </TouchableOpacity>
      </View>
      <View style={styles.helpIconBg}>
        <Ionicons name="headset-outline" size={28} color={PURPLE} />
      </View>
    </View>
  );
}

// ── Main export ─────────────────────────────────────────────────────

export function PatientRightSidebar() {
  return (
    <View style={styles.sidebar}>
      <ScrollView
        showsVerticalScrollIndicator={false}
        contentContainerStyle={styles.scroll}
      >
        <UpcomingAppointment />
        <MyProxies />
        <QuickAccess />
        <NeedHelp />
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  sidebar: {
    width: 300,
    backgroundColor: GRAY_LIGHT,
    borderLeftWidth: 1,
    borderLeftColor: BORDER,
    height: '100%',
  },
  scroll: {
    padding: 16,
    gap: 16,
  },

  // Card shell
  card: {
    backgroundColor: WHITE,
    borderRadius: 14,
    padding: 16,
    borderWidth: 1,
    borderColor: BORDER,
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  cardTitle: {
    fontSize: 15,
    fontWeight: '700',
    color: '#111827',
  },
  link: {
    fontSize: 13,
    color: TEAL,
    fontWeight: '500',
  },

  // Appointment
  apptInner: {
    backgroundColor: TEAL_BG,
    borderRadius: 10,
    padding: 14,
    gap: 6,
  },
  apptTopRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    marginBottom: 4,
  },
  apptIconBg: {
    width: 44,
    height: 44,
    borderRadius: 10,
    backgroundColor: WHITE,
    alignItems: 'center',
    justifyContent: 'center',
  },
  apptDate: {
    fontSize: 12,
    color: GRAY,
  },
  apptTime: {
    fontSize: 22,
    fontWeight: '700',
    color: '#111827',
  },
  apptTitle: {
    fontSize: 14,
    fontWeight: '700',
    color: '#111827',
  },
  apptSub: {
    fontSize: 13,
    color: GRAY,
    marginBottom: 4,
  },
  apptMeta: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  apptMetaText: {
    fontSize: 13,
    color: GRAY,
  },
  joinBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    backgroundColor: TEAL,
    borderRadius: 8,
    paddingVertical: 12,
    marginTop: 8,
  },
  joinBtnText: {
    color: WHITE,
    fontWeight: '600',
    fontSize: 14,
  },
  rescheduleBtn: {
    borderRadius: 8,
    borderWidth: 1,
    borderColor: TEAL,
    paddingVertical: 11,
    alignItems: 'center',
    marginTop: 8,
  },
  rescheduleBtnText: {
    color: TEAL,
    fontWeight: '500',
    fontSize: 14,
  },

  // Proxies
  proxyRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 10,
    gap: 10,
  },
  proxyAvatar: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: BORDER,
  },
  proxyInfo: {
    flex: 1,
  },
  proxyName: {
    fontSize: 14,
    fontWeight: '600',
    color: '#111827',
  },
  proxyRelation: {
    fontWeight: '400',
    color: GRAY,
  },
  proxyAccess: {
    fontSize: 12,
    color: TEAL,
    marginTop: 2,
  },
  rowDivider: {
    height: 1,
    backgroundColor: BORDER,
  },
  manageLink: {
    marginTop: 12,
    alignItems: 'center',
  },
  manageLinkText: {
    fontSize: 13,
    color: TEAL,
    fontWeight: '500',
  },

  // Quick access
  quickRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 12,
    gap: 12,
  },
  quickLabel: {
    flex: 1,
    fontSize: 14,
    color: '#111827',
  },

  // Need help
  helpCard: {
    backgroundColor: PURPLE_BG,
    borderRadius: 14,
    padding: 18,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  helpText: {
    flex: 1,
    gap: 4,
  },
  helpTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: NAVY,
  },
  helpSub: {
    fontSize: 12,
    color: GRAY,
  },
  helpLink: {
    fontSize: 13,
    color: TEAL,
    fontWeight: '500',
    marginTop: 6,
  },
  helpIconBg: {
    width: 52,
    height: 52,
    borderRadius: 26,
    backgroundColor: '#D8DCF5',
    alignItems: 'center',
    justifyContent: 'center',
    marginLeft: 12,
  },
});
