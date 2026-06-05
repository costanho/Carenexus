import { Ionicons } from '@expo/vector-icons';
import { Image, ScrollView, StyleSheet, Text, TouchableOpacity, View, useWindowDimensions } from 'react-native';

const WHITE   = '#FFFFFF';
const TEXT    = '#111827';
const GRAY    = '#6B7280';
const BORDER  = '#E5E7EB';
const BG      = '#F9FAFB';
const BLUE    = '#4F46E5';
const GREEN   = '#10B981';
const ORANGE  = '#F59E0B';
const RED     = '#EF4444';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

// ─── Stat Card ───────────────────────────────────────────────────────────────

type StatCardProps = {
  icon: IoniconsName;
  iconBg: string;
  iconColor: string;
  label: string;
  value: string;
  sub: string;
  subColor: string;
};

function StatCard({ icon, iconBg, iconColor, label, value, sub, subColor }: StatCardProps) {
  return (
    <View style={styles.statCard}>
      <View style={[styles.statIconBg, { backgroundColor: iconBg }]}>
        <Ionicons name={icon} size={22} color={iconColor} />
      </View>
      <Text style={styles.statLabel}>{label}</Text>
      <Text style={styles.statValue}>{value}</Text>
      <Text style={[styles.statSub, { color: subColor }]}>{sub}</Text>
    </View>
  );
}

// ─── Schedule Row ─────────────────────────────────────────────────────────────

type AppointmentProps = {
  time: string;
  name: string;
  type: string;
  status: 'Confirmed' | 'Pending';
  action: 'Join Call' | 'Start';
  avatarId: number;
};

function AppointmentRow({ time, name, type, status, action, avatarId }: AppointmentProps) {
  const isConfirmed = status === 'Confirmed';
  const isJoin = action === 'Join Call';
  return (
    <View style={styles.apptRow}>
      <View style={styles.apptTime}>
        <Text style={styles.apptTimeText}>{time}</Text>
        <Text style={styles.apptDuration}>30 mins</Text>
      </View>
      <Image source={{ uri: `https://i.pravatar.cc/150?img=${avatarId}` }} style={styles.apptAvatar} />
      <View style={styles.apptInfo}>
        <Text style={styles.apptName}>{name}</Text>
        <Text style={styles.apptType}>{type}</Text>
      </View>
      <View style={[styles.badge, { backgroundColor: isConfirmed ? '#D1FAE5' : '#FEF3C7' }]}>
        <Text style={[styles.badgeText, { color: isConfirmed ? GREEN : ORANGE }]}>{status}</Text>
      </View>
      <TouchableOpacity style={[styles.actionBtn, isJoin && styles.actionBtnJoin]} activeOpacity={0.8}>
        {isJoin && <Ionicons name="videocam-outline" size={14} color={BLUE} style={{ marginRight: 4 }} />}
        <Text style={[styles.actionBtnText, isJoin && { color: BLUE }]}>{action}</Text>
      </TouchableOpacity>
      <TouchableOpacity style={styles.moreBtn} activeOpacity={0.7}>
        <Ionicons name="ellipsis-vertical" size={18} color={GRAY} />
      </TouchableOpacity>
    </View>
  );
}

// ─── Activity Row ─────────────────────────────────────────────────────────────

type ActivityProps = {
  icon: IoniconsName;
  iconBg: string;
  iconColor: string;
  title: string;
  sub: string;
  time: string;
};

function ActivityRow({ icon, iconBg, iconColor, title, sub, time }: ActivityProps) {
  return (
    <View style={styles.activityRow}>
      <View style={[styles.activityIcon, { backgroundColor: iconBg }]}>
        <Ionicons name={icon} size={16} color={iconColor} />
      </View>
      <View style={styles.activityText}>
        <Text style={styles.activityTitle}>{title}</Text>
        <Text style={styles.activitySub}>{sub}</Text>
      </View>
      <Text style={styles.activityTime}>{time}</Text>
    </View>
  );
}

// ─── Task Row ─────────────────────────────────────────────────────────────────

type TaskProps = {
  icon: IoniconsName;
  iconBg: string;
  iconColor: string;
  title: string;
  sub: string;
};

function TaskRow({ icon, iconBg, iconColor, title, sub }: TaskProps) {
  return (
    <TouchableOpacity style={styles.taskRow} activeOpacity={0.7}>
      <View style={[styles.activityIcon, { backgroundColor: iconBg }]}>
        <Ionicons name={icon} size={16} color={iconColor} />
      </View>
      <View style={styles.activityText}>
        <Text style={styles.activityTitle}>{title}</Text>
        <Text style={styles.activitySub}>{sub}</Text>
      </View>
      <Ionicons name="chevron-forward" size={16} color={GRAY} />
    </TouchableOpacity>
  );
}

// ─── Patient Stat Mini Card ───────────────────────────────────────────────────

type PatientStatProps = {
  label: string;
  value: string;
  growth: string;
  icon: IoniconsName;
  iconColor: string;
  bg: string;
};

function PatientStatCard({ label, value, growth, icon, iconColor, bg }: PatientStatProps) {
  return (
    <View style={[styles.patientStatCard, { backgroundColor: bg }]}>
      <Text style={styles.patientStatLabel}>{label}</Text>
      <View style={styles.patientStatRow}>
        <Text style={styles.patientStatValue}>{value}</Text>
        <Ionicons name={icon} size={26} color={iconColor} />
      </View>
      <View style={styles.patientGrowthRow}>
        <Ionicons name="arrow-up-outline" size={12} color={GREEN} />
        <Text style={styles.patientGrowth}>{growth}</Text>
      </View>
    </View>
  );
}

// ─── Section Card Wrapper ─────────────────────────────────────────────────────

function Card({ title, action, children }: { title: string; action: string; children: React.ReactNode }) {
  return (
    <View style={styles.card}>
      <View style={styles.cardHeader}>
        <Text style={styles.cardTitle}>{title}</Text>
        <TouchableOpacity activeOpacity={0.7}>
          <Text style={styles.cardAction}>{action}</Text>
        </TouchableOpacity>
      </View>
      {children}
    </View>
  );
}

// ─── Main Component ──────────────────────────────────────────────────────────

export function DoctorMainDashboardArea() {
  const { width } = useWindowDimensions();
  const isLarge = width >= 1024;

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>
      {/* Header */}
      <View style={styles.header}>
        <View>
          <Text style={styles.greeting}>Good morning, Dr. Matt 👋</Text>
          <Text style={styles.greetingSub}>Here's what's happening in your practice today.</Text>
        </View>
        <TouchableOpacity style={styles.dateBtn} activeOpacity={0.8}>
          <Ionicons name="calendar-outline" size={16} color={GRAY} />
          <Text style={styles.dateText}>Wednesday, 22 May 2024</Text>
          <Ionicons name="chevron-down" size={14} color={GRAY} />
        </TouchableOpacity>
      </View>

      {/* Stat Cards */}
      <View style={styles.statsRow}>
        <StatCard icon="calendar-outline"   iconBg="#EEF2FF" iconColor={BLUE}    label="Today's Appointments" value="8" sub="3 upcoming"       subColor={BLUE}   />
        <StatCard icon="videocam-outline"   iconBg="#D1FAE5" iconColor={GREEN}   label="Consultations Today"  value="5" sub="2 completed"      subColor={GREEN}  />
        <StatCard icon="medkit-outline"     iconBg="#FEF3C7" iconColor={ORANGE}  label="Prescriptions Issued" value="6" sub="Today"            subColor={GRAY}   />
        <StatCard icon="people-outline"     iconBg="#EFF6FF" iconColor="#3B82F6" label="New Patients"         value="3" sub="This week"        subColor={GRAY}   />
        <StatCard icon="clipboard-outline"  iconBg="#FEE2E2" iconColor={RED}     label="Pending Tasks"        value="7" sub="Requires attention" subColor={RED}  />
      </View>

      {/* Two-column body */}
      <View style={[styles.body, isLarge && styles.bodyRow]}>
        {/* Left column */}
        <View style={[styles.leftCol, isLarge && styles.leftColWide]}>
          {/* Today's Schedule */}
          <Card title="Today's Schedule" action="View full schedule">
            <AppointmentRow time="09:00 AM" name="Sarah Johnson"   type="Follow-up Consultation"   status="Confirmed" action="Join Call" avatarId={47} />
            <View style={styles.separator} />
            <AppointmentRow time="10:00 AM" name="Michael Brown"   type="Initial Consultation"     status="Confirmed" action="Join Call" avatarId={12} />
            <View style={styles.separator} />
            <AppointmentRow time="11:30 AM" name="Esther Williams" type="Review Results"           status="Confirmed" action="Join Call" avatarId={32} />
            <View style={styles.separator} />
            <AppointmentRow time="02:00 PM" name="David Thompson"  type="Follow-up Consultation"   status="Pending"   action="Start"    avatarId={15} />
            <View style={styles.separator} />
            <AppointmentRow time="03:00 PM" name="Linda Davis"     type="Chronic Care Management"  status="Confirmed" action="Start"    avatarId={56} />
            <TouchableOpacity style={styles.viewAll} activeOpacity={0.7}>
              <Text style={styles.viewAllText}>View all appointments →</Text>
            </TouchableOpacity>
          </Card>

          {/* Patients Overview */}
          <Card title="Patients Overview" action="View all patients">
            <View style={styles.patientStatsGrid}>
              <PatientStatCard label="Total Patients"   value="248" growth="12% this month" icon="people-outline"      iconColor={BLUE}    bg="#F5F3FF" />
              <PatientStatCard label="Active Patients"  value="186" growth="8% this month"  icon="people-outline"      iconColor={GREEN}   bg="#F0FDF4" />
              <PatientStatCard label="Chronic Care"     value="72"  growth="15% this month" icon="heart-outline"       iconColor={ORANGE}  bg="#FFFBEB" />
              <PatientStatCard label="New This Month"   value="24"  growth="20% this month" icon="person-add-outline"  iconColor="#3B82F6" bg="#EFF6FF" />
            </View>
          </Card>
        </View>

        {/* Right column */}
        <View style={[styles.rightCol, isLarge && styles.rightColNarrow]}>
          {/* Recent Activity */}
          <Card title="Recent Activity" action="View all">
            <ActivityRow icon="chatbubble-outline"   iconBg="#EEF2FF" iconColor={BLUE}    title="New message from Sarah Johnson"        sub="Regarding lab results"           time="10 min ago" />
            <View style={styles.separator} />
            <ActivityRow icon="flask-outline"        iconBg="#D1FAE5" iconColor={GREEN}   title="Lab results uploaded for Michael Brown" sub="Blood Test Results"              time="45 min ago" />
            <View style={styles.separator} />
            <ActivityRow icon="medkit-outline"       iconBg="#FEF3C7" iconColor={ORANGE}  title="Prescription issued to Linda Davis"     sub="Atorvastatin 20mg"              time="1 hr ago"   />
            <View style={styles.separator} />
            <ActivityRow icon="calendar-outline"     iconBg="#EFF6FF" iconColor="#3B82F6" title="New appointment booked"                 sub="With James Wilson on 24 May 2024" time="2 hrs ago"  />
            <View style={styles.separator} />
            <ActivityRow icon="document-text-outline" iconBg="#FEE2E2" iconColor={RED}    title="Referral request from Esther Williams"  sub="Cardiology Consultation"         time="3 hrs ago"  />
          </Card>

          {/* Tasks & Reminders */}
          <Card title="Tasks & Reminders" action="View all">
            <TaskRow icon="flask-outline"    iconBg="#D1FAE5" iconColor={GREEN}   title="Review lab results"      sub="3 pending" />
            <View style={styles.separator} />
            <TaskRow icon="people-outline"   iconBg="#EEF2FF" iconColor={BLUE}    title="Follow up with patients" sub="5 pending" />
            <View style={styles.separator} />
            <TaskRow icon="medkit-outline"   iconBg="#FEF3C7" iconColor={ORANGE}  title="Pending prescriptions"   sub="2 pending" />
          </Card>
        </View>
      </View>
    </ScrollView>
  );
}

// ─── Styles ──────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  scroll:     { flex: 1, backgroundColor: BG },
  container:  { padding: 24, paddingBottom: 40 },

  // Header
  header: {
    flexDirection: 'row', justifyContent: 'space-between',
    alignItems: 'flex-start', marginBottom: 24, flexWrap: 'wrap', gap: 12,
  },
  greeting:    { fontSize: 24, fontWeight: '700', color: TEXT, marginBottom: 4 },
  greetingSub: { fontSize: 14, color: GRAY },
  dateBtn: {
    flexDirection: 'row', alignItems: 'center', gap: 6,
    borderWidth: 1, borderColor: BORDER, borderRadius: 8,
    paddingHorizontal: 12, paddingVertical: 8, backgroundColor: WHITE,
  },
  dateText: { fontSize: 13, color: TEXT, fontWeight: '500' },

  // Stats
  statsRow: {
    flexDirection: 'row', gap: 12, marginBottom: 24,
  },
  statCard: {
    flex: 1,
    backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER,
    borderRadius: 12, padding: 16,
  },
  statIconBg: {
    width: 44, height: 44, borderRadius: 12,
    alignItems: 'center', justifyContent: 'center', marginBottom: 10,
  },
  statLabel: { fontSize: 12, color: GRAY, marginBottom: 4 },
  statValue: { fontSize: 28, fontWeight: '700', color: TEXT, marginBottom: 2 },
  statSub:   { fontSize: 12, fontWeight: '500' },

  // Body layout
  body:         { gap: 20 },
  bodyRow:      { flexDirection: 'row', alignItems: 'flex-start' },
  leftCol:      { gap: 20 },
  leftColWide:  { flex: 1.5 },
  rightCol:     { gap: 20 },
  rightColNarrow: { flex: 1 },

  // Card
  card: {
    backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER,
    borderRadius: 16, padding: 20,
  },
  cardHeader: {
    flexDirection: 'row', justifyContent: 'space-between',
    alignItems: 'center', marginBottom: 16,
  },
  cardTitle:  { fontSize: 16, fontWeight: '700', color: TEXT },
  cardAction: { fontSize: 13, color: BLUE, fontWeight: '500' },

  separator: { height: 1, backgroundColor: BORDER, marginVertical: 10 },

  // Appointment row
  apptRow: {
    flexDirection: 'row', alignItems: 'center', gap: 10, flexWrap: 'wrap',
  },
  apptTime:     { width: 74 },
  apptTimeText: { fontSize: 13, fontWeight: '600', color: BLUE },
  apptDuration: { fontSize: 11, color: GRAY, marginTop: 2 },
  apptAvatar:   { width: 40, height: 40, borderRadius: 20, backgroundColor: BORDER },
  apptInfo:     { flex: 1, minWidth: 100 },
  apptName:     { fontSize: 14, fontWeight: '600', color: TEXT },
  apptType:     { fontSize: 12, color: GRAY, marginTop: 1 },
  badge: {
    paddingHorizontal: 8, paddingVertical: 3,
    borderRadius: 20,
  },
  badgeText:    { fontSize: 11, fontWeight: '600' },
  actionBtn: {
    flexDirection: 'row', alignItems: 'center',
    borderWidth: 1, borderColor: BORDER, borderRadius: 8,
    paddingHorizontal: 12, paddingVertical: 6,
  },
  actionBtnJoin: { borderColor: BLUE },
  actionBtnText: { fontSize: 13, fontWeight: '500', color: TEXT },
  moreBtn:       { padding: 4 },
  viewAll: { alignItems: 'center', marginTop: 14 },
  viewAllText:   { fontSize: 13, color: BLUE, fontWeight: '500' },

  // Activity row
  activityRow: {
    flexDirection: 'row', alignItems: 'center', gap: 12,
  },
  activityIcon: {
    width: 36, height: 36, borderRadius: 18,
    alignItems: 'center', justifyContent: 'center',
  },
  activityText:  { flex: 1 },
  activityTitle: { fontSize: 13, fontWeight: '600', color: TEXT },
  activitySub:   { fontSize: 12, color: GRAY, marginTop: 1 },
  activityTime:  { fontSize: 11, color: GRAY, whiteSpace: 'nowrap' } as any,

  // Task row
  taskRow: {
    flexDirection: 'row', alignItems: 'center', gap: 12,
  },

  // Patient stats
  patientStatsGrid: {
    flexDirection: 'row', flexWrap: 'wrap', gap: 12,
  },
  patientStatCard: {
    flex: 1, minWidth: 120, borderRadius: 12, padding: 14,
  },
  patientStatLabel: { fontSize: 12, color: GRAY, marginBottom: 8 },
  patientStatRow:   { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  patientStatValue: { fontSize: 26, fontWeight: '700', color: TEXT },
  patientGrowthRow: { flexDirection: 'row', alignItems: 'center', gap: 2, marginTop: 6 },
  patientGrowth:    { fontSize: 11, color: GREEN, fontWeight: '500' },
});
