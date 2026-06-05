import { Ionicons } from '@expo/vector-icons';
import { Image, ScrollView, StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';

const TEAL       = '#0D9488';
const NAVY       = '#1E3A5F';
const GRAY       = '#6B7280';
const GRAY_LIGHT = '#F9FAFB';
const BORDER     = '#E5E7EB';
const WHITE      = '#FFFFFF';
const TEXT       = '#111827';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

function useScale() {
  const { width, height } = useWindowDimensions();
  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));
  return { s, sh, width, height };
}

// ── Quick Actions ────────────────────────────────────────────────────

type QuickAction = { label: string; icon: IoniconsName; iconBg: string; iconColor: string };

const quickActions: QuickAction[] = [
  { label: 'Book Appointment',   icon: 'calendar-outline',      iconBg: '#EEE8FF', iconColor: '#7C5CFC' },
  { label: 'Join Consultation',  icon: 'videocam-outline',      iconBg: '#E6F4F1', iconColor: TEAL },
  { label: 'Upload Documents',   icon: 'cloud-upload-outline',  iconBg: '#E8F0FE', iconColor: '#4285F4' },
  { label: 'View Prescriptions', icon: 'medkit-outline',        iconBg: '#FFF3E6', iconColor: '#F4A124' },
  { label: 'Message Doctor',     icon: 'chatbubble-outline',    iconBg: '#EEE8FF', iconColor: '#7C5CFC' },
];

function QuickActions() {
  const { s, width } = useScale();
  const cardWidth   = width * 0.42;
  const iconBox     = s(40);
  const gap         = s(10);

  return (
    <ScrollView
      horizontal nestedScrollEnabled
      showsHorizontalScrollIndicator={false}
      contentContainerStyle={{ gap, paddingVertical: s(2) }}
    >
      {quickActions.map((a) => (
        <TouchableOpacity
          key={a.label}
          style={[styles.quickCard, { width: cardWidth, padding: s(12), borderRadius: s(12), gap: s(6) }]}
          activeOpacity={0.8}
        >
          <View style={[styles.iconBox, { width: iconBox, height: iconBox, borderRadius: iconBox / 2, backgroundColor: a.iconBg }]}>
            <Ionicons name={a.icon} size={s(20)} color={a.iconColor} />
          </View>
          <Text style={[styles.quickLabel, { fontSize: s(12) }]}>{a.label}</Text>
          <Ionicons name="arrow-forward" size={s(13)} color={GRAY} />
        </TouchableOpacity>
      ))}
    </ScrollView>
  );
}

// ── Welcome Banner ───────────────────────────────────────────────────

function WelcomeBanner() {
  const { s } = useScale();
  const avatarSize = s(60);

  return (
    <View style={[styles.banner, { padding: s(16), borderRadius: s(14), gap: s(12) }]}>
      <Image
        source={{ uri: 'https://i.pravatar.cc/150?img=68' }}
        style={{ width: avatarSize, height: avatarSize, borderRadius: avatarSize / 2, backgroundColor: BORDER }}
      />
      <View style={styles.flex1}>
        <Text style={[styles.bannerTitle, { fontSize: s(14), marginBottom: s(4) }]}>
          Welcome to CareNexus Direct
        </Text>
        <Text style={[styles.bannerDesc, { fontSize: s(11), lineHeight: s(17) }]}>
          Book appointments, consult online, access your records and stay connected with your care team.
        </Text>
      </View>
      <View style={[styles.bannerDecor, { right: -s(16), bottom: -s(16) }]}>
        <View style={{ width: s(90), height: s(90), borderRadius: s(45), borderWidth: s(18), borderColor: TEAL }} />
      </View>
    </View>
  );
}

// ── Health Overview ──────────────────────────────────────────────────

type HealthMetric = { label: string; value: string; sub?: string; icon: IoniconsName; iconBg: string; iconColor: string };

const metrics: HealthMetric[] = [
  { label: 'Conditions', value: '2',      icon: 'heart-outline',        iconBg: '#E6F4F1', iconColor: TEAL },
  { label: 'Allergies',  value: '1',      icon: 'warning-outline',      iconBg: '#FEE2E2', iconColor: '#EF4444' },
  { label: 'Medications',value: '3',      icon: 'medkit-outline',       iconBg: '#E8F0FE', iconColor: '#4285F4' },
  { label: 'Latest BP',  value: '128/82', sub: 'mmHg • 2 days ago', icon: 'heart-circle-outline', iconBg: '#FEE2E2', iconColor: '#EF4444' },
  { label: 'Weight',     value: '72 kg',  sub: '1 week ago',        icon: 'barbell-outline',      iconBg: '#EEE8FF', iconColor: '#7C5CFC' },
];

function HealthOverview() {
  const { s, width } = useScale();
  const cardWidth = width * 0.4;
  const iconBox   = s(32);

  return (
    <View style={[styles.section, { padding: s(14), borderRadius: s(14) }]}>
      <View style={[styles.row, { marginBottom: s(10) }]}>
        <Text style={[styles.sectionTitle, { fontSize: s(14) }]}>Health Overview</Text>
        <TouchableOpacity><Text style={[styles.link, { fontSize: s(12) }]}>View summary →</Text></TouchableOpacity>
      </View>
      <ScrollView horizontal nestedScrollEnabled showsHorizontalScrollIndicator={false}
        contentContainerStyle={{ gap: s(10), paddingVertical: s(2) }}>
        {metrics.map((m) => (
          <View key={m.label} style={[styles.metricCard, { width: cardWidth, padding: s(12), borderRadius: s(10), gap: s(3) }]}>
            <View style={[styles.iconBox, { width: iconBox, height: iconBox, borderRadius: iconBox / 2, backgroundColor: m.iconBg, marginBottom: s(4) }]}>
              <Ionicons name={m.icon} size={s(16)} color={m.iconColor} />
            </View>
            <Text style={[styles.metricLabel, { fontSize: s(10) }]}>{m.label}</Text>
            <Text style={[styles.metricValue, { fontSize: s(20) }]}>{m.value}</Text>
            {m.sub
              ? <Text style={[styles.metricSub, { fontSize: s(10), lineHeight: s(15) }]}>{m.sub}</Text>
              : <Text style={[styles.link, { fontSize: s(11) }]}>View details</Text>
            }
          </View>
        ))}
      </ScrollView>
    </View>
  );
}

// ── Recent Consultations ─────────────────────────────────────────────

type Consultation = { date: string; title: string; doctor: string };

const consultations: Consultation[] = [
  { date: '24 May 2024 • 10:30 AM', title: 'Follow-up Consultation', doctor: 'Dr. Matt (Physician)' },
  { date: '10 May 2024 • 11:00 AM', title: 'Review Lab Results',     doctor: 'Dr. Matt (Physician)' },
  { date: '26 Apr 2024 • 09:15 AM', title: 'Initial Consultation',   doctor: 'Dr. Matt (Physician)' },
];

function RecentConsultations() {
  const { s } = useScale();
  const iconBox = s(34);

  return (
    <View style={[styles.section, { padding: s(14), borderRadius: s(14) }]}>
      <View style={[styles.row, { marginBottom: s(10) }]}>
        <Text style={[styles.sectionTitle, { fontSize: s(14) }]}>Recent Consultations</Text>
        <TouchableOpacity><Text style={[styles.link, { fontSize: s(12) }]}>View all</Text></TouchableOpacity>
      </View>
      {consultations.map((c) => (
        <TouchableOpacity key={c.date} style={[styles.listRow, { gap: s(10), paddingVertical: s(10) }]} activeOpacity={0.7}>
          <View style={[styles.iconBox, { width: iconBox, height: iconBox, borderRadius: s(8), backgroundColor: '#E6F4F1' }]}>
            <Ionicons name="videocam-outline" size={s(16)} color={TEAL} />
          </View>
          <View style={styles.flex1}>
            <Text style={[styles.listDate,  { fontSize: s(10) }]}>{c.date}</Text>
            <Text style={[styles.listTitle, { fontSize: s(13) }]}>{c.title}</Text>
            <Text style={[styles.listSub,   { fontSize: s(11) }]}>{c.doctor}</Text>
          </View>
          <View style={[styles.badge, { paddingHorizontal: s(7), paddingVertical: s(3), borderRadius: s(6) }]}>
            <Text style={[styles.badgeText, { fontSize: s(10) }]}>Completed</Text>
          </View>
          <Ionicons name="chevron-forward" size={s(15)} color={GRAY} />
        </TouchableOpacity>
      ))}
      <TouchableOpacity style={[styles.viewAllRow, { paddingTop: s(10) }]}>
        <Text style={[styles.link, { fontSize: s(12) }]}>View all consultations →</Text>
      </TouchableOpacity>
    </View>
  );
}

// ── Latest Prescriptions ─────────────────────────────────────────────

type Prescription = { name: string; dosage: string; date: string };

const prescriptions: Prescription[] = [
  { name: 'Atorvastatin 20mg',  dosage: '1 tablet at night',       date: '24 May 2024' },
  { name: 'Amlodipine 5mg',     dosage: '1 tablet in the morning', date: '24 May 2024' },
  { name: 'Vitamin D3 1000 IU', dosage: '1 tablet daily',          date: '24 May 2024' },
];

function LatestPrescriptions() {
  const { s } = useScale();

  return (
    <View style={[styles.section, { padding: s(14), borderRadius: s(14) }]}>
      <View style={[styles.row, { marginBottom: s(10) }]}>
        <Text style={[styles.sectionTitle, { fontSize: s(14) }]}>Latest Prescriptions</Text>
        <TouchableOpacity><Text style={[styles.link, { fontSize: s(12) }]}>View all</Text></TouchableOpacity>
      </View>
      {prescriptions.map((p) => (
        <TouchableOpacity key={p.name} style={[styles.listRow, { gap: s(10), paddingVertical: s(10) }]} activeOpacity={0.7}>
          <Ionicons name="document-outline" size={s(20)} color={GRAY} />
          <View style={styles.flex1}>
            <Text style={[styles.listTitle, { fontSize: s(13) }]}>{p.name}</Text>
            <Text style={[styles.listSub,   { fontSize: s(11) }]}>{p.dosage}</Text>
            <Text style={[styles.listDate,  { fontSize: s(10) }]}>{p.date}</Text>
          </View>
          <View style={[styles.badge, { backgroundColor: '#E6F4F1', paddingHorizontal: s(7), paddingVertical: s(3), borderRadius: s(6) }]}>
            <Text style={[styles.badgeText, { fontSize: s(10), color: TEAL }]}>Active</Text>
          </View>
        </TouchableOpacity>
      ))}
      <TouchableOpacity style={[styles.viewAllRow, { paddingTop: s(10) }]}>
        <Text style={[styles.link, { fontSize: s(12) }]}>View all prescriptions →</Text>
      </TouchableOpacity>
    </View>
  );
}

// ── Important Reminders ──────────────────────────────────────────────

type Reminder = { label: string; sub: string; time: string; icon: IoniconsName; iconBg: string; iconColor: string; due?: boolean };

const reminders: Reminder[] = [
  { label: 'Take Amlodipine 5mg',    sub: 'Every morning • 08:00 AM', time: '08:00 AM', icon: 'calendar-outline',  iconBg: '#E8F0FE', iconColor: '#4285F4' },
  { label: 'Follow up with Dr. Matt',sub: '24 May 2024 • 10:30 AM',   time: '10:30 AM', icon: 'clipboard-outline', iconBg: '#E8F0FE', iconColor: '#4285F4' },
  { label: 'Upload lab results',      sub: 'Blood test results due',   time: 'Due',      icon: 'clipboard-outline', iconBg: '#FEE2E2', iconColor: '#EF4444', due: true },
];

function ImportantReminders() {
  const { s } = useScale();
  const iconBox = s(32);

  return (
    <View style={[styles.section, { padding: s(14), borderRadius: s(14) }]}>
      <View style={[styles.row, { marginBottom: s(10) }]}>
        <Ionicons name="list-outline" size={s(18)} color={TEXT} />
        <Text style={[styles.sectionTitle, { fontSize: s(14), marginLeft: s(6) }]}>Important Reminders</Text>
      </View>
      {reminders.map((r) => (
        <TouchableOpacity key={r.label} style={[styles.listRow, { gap: s(10), paddingVertical: s(10) }]} activeOpacity={0.8}>
          <View style={[styles.iconBox, { width: iconBox, height: iconBox, borderRadius: s(8), backgroundColor: r.iconBg }]}>
            <Ionicons name={r.icon} size={s(16)} color={r.iconColor} />
          </View>
          <View style={styles.flex1}>
            <Text style={[styles.listTitle, { fontSize: s(13) }]}>{r.label}</Text>
            <Text style={[styles.listSub,   { fontSize: s(11) }]}>{r.sub}</Text>
          </View>
          <Text style={[styles.reminderTime, { fontSize: s(12) }, r.due && styles.reminderDue]}>{r.time}</Text>
          <Ionicons name="chevron-forward" size={s(15)} color={r.due ? '#EF4444' : GRAY} />
        </TouchableOpacity>
      ))}
    </View>
  );
}

// ── Main export ──────────────────────────────────────────────────────

export function PatientMainDashboardAreaMobile() {
  const { s } = useScale();
  const pad = s(16);

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={{ padding: pad, gap: pad }}
      showsVerticalScrollIndicator={false}
      nestedScrollEnabled
    >
      <QuickActions />
      <WelcomeBanner />
      <HealthOverview />
      <RecentConsultations />
      <LatestPrescriptions />
      <ImportantReminders />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container:    { flex: 1, backgroundColor: GRAY_LIGHT },
  flex1:        { flex: 1 },
  row:          { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  iconBox:      { alignItems: 'center', justifyContent: 'center' },

  quickCard:    { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  quickLabel:   { fontWeight: '700', color: TEXT, lineHeight: 17 },

  banner:       { backgroundColor: '#F0F4FF', flexDirection: 'row', alignItems: 'center', borderWidth: 1, borderColor: BORDER, overflow: 'hidden' },
  bannerTitle:  { fontWeight: '700', color: NAVY },
  bannerDesc:   { color: GRAY },
  bannerDecor:  { position: 'absolute', opacity: 0.12 },

  section:      { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  sectionTitle: { fontWeight: '700', color: TEXT },
  link:         { color: '#4F6EF7', fontWeight: '500' },

  metricCard:   { borderWidth: 1, borderColor: BORDER },
  metricLabel:  { color: GRAY },
  metricValue:  { fontWeight: '700', color: TEXT },
  metricSub:    { color: GRAY },

  listRow:      { flexDirection: 'row', alignItems: 'center', borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: BORDER },
  listDate:     { color: GRAY },
  listTitle:    { fontWeight: '600', color: TEXT },
  listSub:      { color: GRAY },
  badge:        { backgroundColor: '#F3F4F6' },
  badgeText:    { color: GRAY, fontWeight: '600' },
  viewAllRow:   { alignItems: 'center' },

  reminderTime: { fontWeight: '600', color: GRAY },
  reminderDue:  { color: '#EF4444' },
});
