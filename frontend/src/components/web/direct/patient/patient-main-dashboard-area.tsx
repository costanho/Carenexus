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

// ── Quick Actions ────────────────────────────────────────────────────

type QuickAction = { label: string; desc: string; icon: IoniconsName; iconBg: string; iconColor: string };

const quickActions: QuickAction[] = [
  { label: 'Book\nAppointment',   desc: 'Schedule a new appointment',        icon: 'calendar-outline',     iconBg: '#EEE8FF', iconColor: '#7C5CFC' },
  { label: 'Join\nConsultation',  desc: 'Join your upcoming consultation',   icon: 'videocam-outline',     iconBg: '#E6F4F1', iconColor: TEAL },
  { label: 'Upload\nDocuments',   desc: 'Share reports or other documents',  icon: 'cloud-upload-outline', iconBg: '#E8F0FE', iconColor: '#4285F4' },
  { label: 'View\nPrescriptions', desc: 'See your prescriptions and refills', icon: 'medkit-outline',      iconBg: '#FFF3E6', iconColor: '#F4A124' },
  { label: 'Message\nDoctor',     desc: 'Send a message to your doctor',     icon: 'chatbubble-outline',   iconBg: '#EEE8FF', iconColor: '#7C5CFC' },
];

function QuickActions() {
  const { width } = useWindowDimensions();
  const isSmall = width < 768;

  if (isSmall) {
    return (
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 10, paddingVertical: 2 }}>
        {quickActions.map((a) => (
          <TouchableOpacity key={a.label} style={[styles.quickCard, { width: 140 }]} activeOpacity={0.8}>
            <View style={[styles.quickIconBg, { backgroundColor: a.iconBg }]}>
              <Ionicons name={a.icon} size={20} color={a.iconColor} />
            </View>
            <Text style={styles.quickLabel}>{a.label}</Text>
            <Text style={styles.quickArrow}>→</Text>
          </TouchableOpacity>
        ))}
      </ScrollView>
    );
  }

  return (
    <View style={styles.quickRow}>
      {quickActions.map((a) => (
        <TouchableOpacity key={a.label} style={styles.quickCard} activeOpacity={0.8}>
          <View style={[styles.quickIconBg, { backgroundColor: a.iconBg }]}>
            <Ionicons name={a.icon} size={22} color={a.iconColor} />
          </View>
          <Text style={styles.quickLabel}>{a.label}</Text>
          <Text style={styles.quickDesc}>{a.desc}</Text>
          <Text style={styles.quickArrow}>→</Text>
        </TouchableOpacity>
      ))}
    </View>
  );
}

// ── Welcome Banner ───────────────────────────────────────────────────

function WelcomeBanner() {
  const { width } = useWindowDimensions();
  const isSmall = width < 768;

  return (
    <View style={styles.banner}>
      <Image
        source={{ uri: 'https://i.pravatar.cc/150?img=68' }}
        style={[styles.bannerAvatar, isSmall && { width: 60, height: 60, borderRadius: 30 }]}
      />
      <View style={styles.bannerText}>
        <Text style={[styles.bannerTitle, isSmall && { fontSize: 15 }]}>Welcome to CareNexus Direct</Text>
        <Text style={[styles.bannerDesc, isSmall && { fontSize: 12 }]}>
          {isSmall
            ? "Book appointments, consult online and stay connected with your care team."
            : "We're here to make your healthcare simple, accessible and personal.\nBook appointments, consult online, access your records and stay connected\nwith your care team – all in one place."}
        </Text>
      </View>
      {!isSmall && (
        <View style={styles.bannerDecor}>
          <View style={styles.decorCircleOuter} />
          <View style={styles.decorCircleInner} />
        </View>
      )}
    </View>
  );
}

// ── Health Overview ──────────────────────────────────────────────────

type HealthMetric = { label: string; value: string; sub?: string; icon: IoniconsName; iconBg: string; iconColor: string };

const metrics: HealthMetric[] = [
  { label: 'Conditions',         value: '2',      icon: 'heart-outline',        iconBg: '#E6F4F1', iconColor: TEAL },
  { label: 'Allergies',          value: '1',      icon: 'warning-outline',      iconBg: '#FEE2E2', iconColor: '#EF4444' },
  { label: 'Current Medications',value: '3',      icon: 'medkit-outline',       iconBg: '#E8F0FE', iconColor: '#4285F4' },
  { label: 'Latest BP',          value: '128/82', sub: 'mmHg\n2 days ago',  icon: 'heart-circle-outline', iconBg: '#FEE2E2', iconColor: '#EF4444' },
  { label: 'Latest Weight',      value: '72 kg',  sub: '1 week ago',        icon: 'barbell-outline',      iconBg: '#EEE8FF', iconColor: '#7C5CFC' },
];

function HealthOverview() {
  const { width } = useWindowDimensions();
  const isSmall = width < 768;

  return (
    <View style={styles.section}>
      <View style={styles.sectionHeader}>
        <Text style={styles.sectionTitle}>Health Overview</Text>
        <TouchableOpacity><Text style={styles.link}>View full summary →</Text></TouchableOpacity>
      </View>
      {isSmall ? (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 10, paddingVertical: 2 }}>
          {metrics.map((m) => (
            <View key={m.label} style={[styles.metricCard, { width: 130 }]}>
              <View style={[styles.metricIconBg, { backgroundColor: m.iconBg }]}>
                <Ionicons name={m.icon} size={18} color={m.iconColor} />
              </View>
              <Text style={styles.metricLabel}>{m.label}</Text>
              <Text style={[styles.metricValue, { fontSize: 18 }]}>{m.value}</Text>
              {m.sub ? <Text style={styles.metricSub}>{m.sub}</Text> : <Text style={styles.link}>Details</Text>}
            </View>
          ))}
        </ScrollView>
      ) : (
        <View style={styles.metricsRow}>
          {metrics.map((m) => (
            <View key={m.label} style={styles.metricCard}>
              <View style={[styles.metricIconBg, { backgroundColor: m.iconBg }]}>
                <Ionicons name={m.icon} size={20} color={m.iconColor} />
              </View>
              <Text style={styles.metricLabel}>{m.label}</Text>
              <Text style={styles.metricValue}>{m.value}</Text>
              {m.sub
                ? <Text style={styles.metricSub}>{m.sub}</Text>
                : <TouchableOpacity><Text style={styles.link}>View details</Text></TouchableOpacity>
              }
            </View>
          ))}
        </View>
      )}
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
  return (
    <View style={[styles.section, { flex: 1 }]}>
      <View style={styles.sectionHeader}>
        <Text style={styles.sectionTitle}>Recent Consultations</Text>
        <TouchableOpacity><Text style={styles.link}>View all</Text></TouchableOpacity>
      </View>
      {consultations.map((c) => (
        <View key={c.date} style={styles.consultRow}>
          <View style={styles.consultIconBg}>
            <Ionicons name="videocam-outline" size={18} color={TEAL} />
          </View>
          <View style={styles.consultInfo}>
            <Text style={styles.consultDate}>{c.date}</Text>
            <Text style={styles.consultTitle}>{c.title}</Text>
            <Text style={styles.consultDoctor}>{c.doctor}</Text>
          </View>
          <View style={styles.badge}><Text style={styles.badgeText}>Completed</Text></View>
          <Ionicons name="chevron-forward" size={16} color={GRAY} />
        </View>
      ))}
      <TouchableOpacity style={styles.viewAllRow}>
        <Text style={styles.link}>View all consultations →</Text>
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
  return (
    <View style={[styles.section, { flex: 1 }]}>
      <View style={styles.sectionHeader}>
        <Text style={styles.sectionTitle}>Latest Prescriptions</Text>
        <TouchableOpacity><Text style={styles.link}>View all</Text></TouchableOpacity>
      </View>
      {prescriptions.map((p) => (
        <View key={p.name} style={styles.consultRow}>
          <Ionicons name="document-outline" size={22} color={GRAY} />
          <View style={styles.consultInfo}>
            <Text style={styles.consultTitle}>{p.name}</Text>
            <Text style={styles.consultDoctor}>{p.dosage}</Text>
            <Text style={styles.consultDate}>{p.date}</Text>
          </View>
          <View style={[styles.badge, styles.badgeActive]}>
            <Text style={[styles.badgeText, styles.badgeActiveText]}>Active</Text>
          </View>
        </View>
      ))}
      <TouchableOpacity style={styles.viewAllRow}>
        <Text style={styles.link}>View all prescriptions →</Text>
      </TouchableOpacity>
    </View>
  );
}

// ── Important Reminders ──────────────────────────────────────────────

type Reminder = { label: string; sub: string; time: string; icon: IoniconsName; iconBg: string; iconColor: string; due?: boolean };

const reminders: Reminder[] = [
  { label: 'Take Amlodipine 5mg',     sub: 'Every morning • 08:00 AM', time: '08:00 AM', icon: 'calendar-outline',  iconBg: '#E8F0FE', iconColor: '#4285F4' },
  { label: 'Follow up with Dr. Matt', sub: '24 May 2024 • 10:30 AM',   time: '10:30 AM', icon: 'clipboard-outline', iconBg: '#E8F0FE', iconColor: '#4285F4' },
  { label: 'Upload lab results',       sub: 'Blood test results due',   time: 'Due',      icon: 'clipboard-outline', iconBg: '#FEE2E2', iconColor: '#EF4444', due: true },
];

function ImportantReminders() {
  const { width } = useWindowDimensions();
  const isSmall = width < 768;

  return (
    <View style={styles.section}>
      <View style={styles.remindersHeader}>
        <Ionicons name="list-outline" size={20} color={TEXT} />
        <Text style={styles.sectionTitle}>Important Reminders</Text>
      </View>
      <View style={[styles.remindersRow, isSmall && { flexDirection: 'column' }]}>
        {reminders.map((r) => (
          <TouchableOpacity key={r.label} style={[styles.reminderCard, isSmall && { flex: undefined }]} activeOpacity={0.8}>
            <View style={[styles.reminderIconBg, { backgroundColor: r.iconBg }]}>
              <Ionicons name={r.icon} size={18} color={r.iconColor} />
            </View>
            <View style={styles.reminderInfo}>
              <Text style={styles.reminderLabel}>{r.label}</Text>
              <Text style={styles.reminderSub}>{r.sub}</Text>
            </View>
            <Text style={[styles.reminderTime, r.due && styles.reminderDue]}>{r.time}</Text>
            <Ionicons name="chevron-forward" size={16} color={r.due ? '#EF4444' : GRAY} />
          </TouchableOpacity>
        ))}
      </View>
    </View>
  );
}

// ── Main export ──────────────────────────────────────────────────────

export function PatientMainDashboardArea() {
  const { width } = useWindowDimensions();
  const isSmall = width < 768;

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
      <QuickActions />
      <WelcomeBanner />
      <HealthOverview />
      <View style={[styles.twoCol, isSmall && { flexDirection: 'column' }]}>
        <RecentConsultations />
        <LatestPrescriptions />
      </View>
      <ImportantReminders />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: GRAY_LIGHT },
  scroll:    { padding: 20, gap: 16 },

  quickRow:    { flexDirection: 'row', gap: 12, flexWrap: 'wrap' },
  quickCard:   { flex: 1, minWidth: 100, backgroundColor: WHITE, borderRadius: 12, padding: 14, borderWidth: 1, borderColor: BORDER, gap: 4 },
  quickIconBg: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center', marginBottom: 4 },
  quickLabel:  { fontSize: 13, fontWeight: '700', color: TEXT, lineHeight: 18 },
  quickDesc:   { fontSize: 11, color: GRAY, lineHeight: 15 },
  quickArrow:  { fontSize: 14, color: GRAY, marginTop: 4 },

  banner:           { backgroundColor: '#F0F4FF', borderRadius: 14, padding: 20, flexDirection: 'row', alignItems: 'center', gap: 16, borderWidth: 1, borderColor: BORDER, overflow: 'hidden' },
  bannerAvatar:     { width: 80, height: 80, borderRadius: 40, backgroundColor: BORDER },
  bannerText:       { flex: 1 },
  bannerTitle:      { fontSize: 18, fontWeight: '700', color: NAVY, marginBottom: 6 },
  bannerDesc:       { fontSize: 13, color: GRAY, lineHeight: 20 },
  bannerDecor:      { position: 'absolute', right: -20, top: -20, opacity: 0.15 },
  decorCircleOuter: { width: 140, height: 140, borderRadius: 70, borderWidth: 24, borderColor: TEAL },
  decorCircleInner: { width: 80, height: 80, borderRadius: 40, borderWidth: 16, borderColor: TEAL, position: 'absolute', right: 10, bottom: -30 },

  section:       { backgroundColor: WHITE, borderRadius: 14, padding: 16, borderWidth: 1, borderColor: BORDER, gap: 2 },
  sectionHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 },
  sectionTitle:  { fontSize: 15, fontWeight: '700', color: TEXT },
  link:          { fontSize: 13, color: '#4F6EF7', fontWeight: '500' },

  metricsRow: { flexDirection: 'row', gap: 10, flexWrap: 'wrap' },
  metricCard: { flex: 1, minWidth: 100, borderRadius: 10, borderWidth: 1, borderColor: BORDER, padding: 12, gap: 4, alignItems: 'flex-start' },
  metricIconBg: { width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center', marginBottom: 4 },
  metricLabel:  { fontSize: 11, color: GRAY },
  metricValue:  { fontSize: 22, fontWeight: '700', color: TEXT },
  metricSub:    { fontSize: 11, color: GRAY, lineHeight: 16 },

  twoCol:        { flexDirection: 'row', gap: 16 },
  consultRow:    { flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 10, borderTopWidth: 1, borderTopColor: BORDER },
  consultIconBg: { width: 38, height: 38, borderRadius: 10, backgroundColor: '#E6F4F1', alignItems: 'center', justifyContent: 'center' },
  consultInfo:   { flex: 1 },
  consultDate:   { fontSize: 11, color: GRAY },
  consultTitle:  { fontSize: 13, fontWeight: '600', color: TEXT },
  consultDoctor: { fontSize: 12, color: GRAY },
  badge:         { backgroundColor: '#E6F4F1', borderRadius: 6, paddingHorizontal: 8, paddingVertical: 3 },
  badgeText:     { fontSize: 11, color: TEAL, fontWeight: '600' },
  badgeActive:   { backgroundColor: '#E6F4F1' },
  badgeActiveText: { color: TEAL },
  viewAllRow:    { alignItems: 'center', paddingTop: 12 },

  remindersHeader: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 12 },
  remindersRow:    { flexDirection: 'row', gap: 12 },
  reminderCard:    { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 10, backgroundColor: WHITE, borderRadius: 12, padding: 14, borderWidth: 1, borderColor: BORDER },
  reminderIconBg:  { width: 36, height: 36, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  reminderInfo:    { flex: 1 },
  reminderLabel:   { fontSize: 13, fontWeight: '600', color: TEXT },
  reminderSub:     { fontSize: 11, color: GRAY },
  reminderTime:    { fontSize: 13, fontWeight: '600', color: GRAY },
  reminderDue:     { color: '#EF4444' },
});
