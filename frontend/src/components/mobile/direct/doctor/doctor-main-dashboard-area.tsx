import { Ionicons } from '@expo/vector-icons';
import { Image, ScrollView, StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const BLUE   = '#4F46E5';
const GREEN  = '#10B981';
const ORANGE = '#F59E0B';
const RED    = '#EF4444';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

function useScale() {
  const { width, height } = useWindowDimensions();
  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));
  return { s, sh, width };
}

// ─── Stat Card ────────────────────────────────────────────────────────────────

type StatCardProps = {
  icon: IoniconsName; iconBg: string; iconColor: string;
  label: string; value: string; sub: string; subColor: string;
};

function StatCard({ icon, iconBg, iconColor, label, value, sub, subColor }: StatCardProps) {
  const { s } = useScale();
  return (
    <View style={[styles.statCard, { width: s(148), borderRadius: s(12), padding: s(14) }]}>
      <View style={[styles.statIconBg, { backgroundColor: iconBg, width: s(40), height: s(40), borderRadius: s(10), marginBottom: s(8) }]}>
        <Ionicons name={icon} size={s(20)} color={iconColor} />
      </View>
      <Text style={[styles.statLabel, { fontSize: s(11) }]}>{label}</Text>
      <Text style={[styles.statValue, { fontSize: s(24) }]}>{value}</Text>
      <Text style={[styles.statSub,   { fontSize: s(11), color: subColor }]}>{sub}</Text>
    </View>
  );
}

// ─── Appointment Row ──────────────────────────────────────────────────────────

type ApptProps = {
  time: string; name: string; type: string;
  status: 'Confirmed' | 'Pending'; action: 'Join Call' | 'Start'; avatarId: number;
};

function AppointmentRow({ time, name, type, status, action, avatarId }: ApptProps) {
  const { s } = useScale();
  const isConfirmed = status === 'Confirmed';
  const isJoin = action === 'Join Call';
  return (
    <View style={[styles.apptRow, { gap: s(8) }]}>
      <View style={{ width: s(62) }}>
        <Text style={[styles.apptTime, { fontSize: s(11) }]}>{time}</Text>
        <Text style={[styles.apptDur,  { fontSize: s(10) }]}>30 mins</Text>
      </View>
      <Image source={{ uri: `https://i.pravatar.cc/150?img=${avatarId}` }}
        style={{ width: s(36), height: s(36), borderRadius: s(18), backgroundColor: BORDER }} />
      <View style={{ flex: 1 }}>
        <Text style={[styles.apptName, { fontSize: s(13) }]} numberOfLines={1}>{name}</Text>
        <Text style={[styles.apptType, { fontSize: s(11) }]} numberOfLines={1}>{type}</Text>
      </View>
      <View style={[styles.badge, { backgroundColor: isConfirmed ? '#D1FAE5' : '#FEF3C7', borderRadius: s(20), paddingHorizontal: s(7), paddingVertical: s(2) }]}>
        <Text style={[styles.badgeText, { fontSize: s(10), color: isConfirmed ? GREEN : ORANGE }]}>{status}</Text>
      </View>
      <TouchableOpacity style={[styles.actionBtn, { borderColor: isJoin ? BLUE : BORDER, borderRadius: s(8), paddingHorizontal: s(10), paddingVertical: s(5) }]} activeOpacity={0.8}>
        {isJoin && <Ionicons name="videocam-outline" size={s(12)} color={BLUE} style={{ marginRight: s(3) }} />}
        <Text style={[styles.actionBtnText, { fontSize: s(11), color: isJoin ? BLUE : TEXT }]}>{action}</Text>
      </TouchableOpacity>
      <TouchableOpacity activeOpacity={0.7}>
        <Ionicons name="ellipsis-vertical" size={s(16)} color={GRAY} />
      </TouchableOpacity>
    </View>
  );
}

// ─── Activity Row ─────────────────────────────────────────────────────────────

type ActivityProps = {
  icon: IoniconsName; iconBg: string; iconColor: string;
  title: string; sub: string; time: string;
};

function ActivityRow({ icon, iconBg, iconColor, title, sub, time }: ActivityProps) {
  const { s } = useScale();
  return (
    <View style={[styles.activityRow, { gap: s(10) }]}>
      <View style={[styles.iconCircle, { backgroundColor: iconBg, width: s(34), height: s(34), borderRadius: s(17) }]}>
        <Ionicons name={icon} size={s(15)} color={iconColor} />
      </View>
      <View style={{ flex: 1 }}>
        <Text style={[styles.activityTitle, { fontSize: s(12) }]} numberOfLines={1}>{title}</Text>
        <Text style={[styles.activitySub,   { fontSize: s(11) }]} numberOfLines={1}>{sub}</Text>
      </View>
      <Text style={[styles.activityTime, { fontSize: s(10) }]}>{time}</Text>
    </View>
  );
}

// ─── Task Row ─────────────────────────────────────────────────────────────────

type TaskProps = { icon: IoniconsName; iconBg: string; iconColor: string; title: string; sub: string };

function TaskRow({ icon, iconBg, iconColor, title, sub }: TaskProps) {
  const { s } = useScale();
  return (
    <TouchableOpacity style={[styles.activityRow, { gap: s(10) }]} activeOpacity={0.7}>
      <View style={[styles.iconCircle, { backgroundColor: iconBg, width: s(34), height: s(34), borderRadius: s(17) }]}>
        <Ionicons name={icon} size={s(15)} color={iconColor} />
      </View>
      <View style={{ flex: 1 }}>
        <Text style={[styles.activityTitle, { fontSize: s(13) }]}>{title}</Text>
        <Text style={[styles.activitySub,   { fontSize: s(11) }]}>{sub}</Text>
      </View>
      <Ionicons name="chevron-forward" size={s(16)} color={GRAY} />
    </TouchableOpacity>
  );
}

// ─── Patient Mini Stat ────────────────────────────────────────────────────────

type PatientStatProps = { label: string; value: string; growth: string; icon: IoniconsName; iconColor: string; bg: string };

function PatientStatCard({ label, value, growth, icon, iconColor, bg }: PatientStatProps) {
  const { s } = useScale();
  return (
    <View style={[styles.patientCard, { backgroundColor: bg, borderRadius: s(12), padding: s(12) }]}>
      <Text style={[styles.activitySub,   { fontSize: s(11), marginBottom: s(6) }]}>{label}</Text>
      <View style={styles.patientRow}>
        <Text style={[styles.statValue, { fontSize: s(22) }]}>{value}</Text>
        <Ionicons name={icon} size={s(22)} color={iconColor} />
      </View>
      <View style={[styles.activityRow, { gap: s(3), marginTop: s(4) }]}>
        <Ionicons name="arrow-up-outline" size={s(11)} color={GREEN} />
        <Text style={[styles.activitySub, { fontSize: s(10), color: GREEN }]}>{growth}</Text>
      </View>
    </View>
  );
}

// ─── Card Wrapper ─────────────────────────────────────────────────────────────

function Card({ title, action, s, children }: { title: string; action: string; s: (n: number) => number; children: React.ReactNode }) {
  return (
    <View style={[styles.card, { borderRadius: s(16), padding: s(16), marginBottom: s(16) }]}>
      <View style={[styles.cardHeader, { marginBottom: s(14) }]}>
        <Text style={[styles.cardTitle, { fontSize: s(15) }]}>{title}</Text>
        <TouchableOpacity activeOpacity={0.7}>
          <Text style={[styles.cardAction, { fontSize: s(12) }]}>{action}</Text>
        </TouchableOpacity>
      </View>
      {children}
    </View>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────

export function DoctorMainDashboardAreaMobile() {
  const { s, sh } = useScale();

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={{ padding: s(16), paddingBottom: sh(80) }} showsVerticalScrollIndicator={false}>

      {/* Header */}
      <View style={[styles.header, { marginBottom: s(20) }]}>
        <View style={{ flex: 1 }}>
          <Text style={[styles.greeting, { fontSize: s(18) }]}>Good morning, Dr. Matt 👋</Text>
          <Text style={[styles.greetingSub, { fontSize: s(12), marginTop: s(2) }]}>Here's what's happening today.</Text>
        </View>
        <TouchableOpacity style={[styles.dateBtn, { gap: s(5), borderRadius: s(8), paddingHorizontal: s(10), paddingVertical: s(6) }]} activeOpacity={0.8}>
          <Ionicons name="calendar-outline" size={s(13)} color={GRAY} />
          <Text style={[styles.dateText, { fontSize: s(11) }]}>22 May 2024</Text>
        </TouchableOpacity>
      </View>

      {/* Stat Cards — horizontal scroll */}
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ marginBottom: s(20) }} contentContainerStyle={{ gap: s(10), paddingRight: s(4) }}>
        <StatCard icon="calendar-outline"  iconBg="#EEF2FF" iconColor={BLUE}    label="Today's Appts"       value="8" sub="3 upcoming"         subColor={BLUE}   />
        <StatCard icon="videocam-outline"  iconBg="#D1FAE5" iconColor={GREEN}   label="Consultations"       value="5" sub="2 completed"         subColor={GREEN}  />
        <StatCard icon="medkit-outline"    iconBg="#FEF3C7" iconColor={ORANGE}  label="Prescriptions"       value="6" sub="Today"               subColor={GRAY}   />
        <StatCard icon="people-outline"    iconBg="#EFF6FF" iconColor="#3B82F6" label="New Patients"        value="3" sub="This week"            subColor={GRAY}   />
        <StatCard icon="clipboard-outline" iconBg="#FEE2E2" iconColor={RED}     label="Pending Tasks"       value="7" sub="Requires attention"   subColor={RED}    />
      </ScrollView>

      {/* Today's Schedule */}
      <Card title="Today's Schedule" action="View full schedule" s={s}>
        <AppointmentRow time="09:00 AM" name="Sarah Johnson"   type="Follow-up Consultation"  status="Confirmed" action="Join Call" avatarId={47} />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <AppointmentRow time="10:00 AM" name="Michael Brown"   type="Initial Consultation"    status="Confirmed" action="Join Call" avatarId={12} />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <AppointmentRow time="11:30 AM" name="Esther Williams" type="Review Results"          status="Confirmed" action="Join Call" avatarId={32} />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <AppointmentRow time="02:00 PM" name="David Thompson"  type="Follow-up Consultation"  status="Pending"   action="Start"    avatarId={15} />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <AppointmentRow time="03:00 PM" name="Linda Davis"     type="Chronic Care Management" status="Confirmed" action="Start"    avatarId={56} />
        <TouchableOpacity style={[styles.viewAll, { marginTop: s(14) }]} activeOpacity={0.7}>
          <Text style={[styles.cardAction, { fontSize: s(13) }]}>View all appointments →</Text>
        </TouchableOpacity>
      </Card>

      {/* Recent Activity */}
      <Card title="Recent Activity" action="View all" s={s}>
        <ActivityRow icon="chatbubble-outline"    iconBg="#EEF2FF" iconColor={BLUE}    title="New message from Sarah Johnson"         sub="Regarding lab results"            time="10 min ago" />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <ActivityRow icon="flask-outline"         iconBg="#D1FAE5" iconColor={GREEN}   title="Lab results for Michael Brown"          sub="Blood Test Results"               time="45 min ago" />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <ActivityRow icon="medkit-outline"        iconBg="#FEF3C7" iconColor={ORANGE}  title="Prescription issued to Linda Davis"     sub="Atorvastatin 20mg"               time="1 hr ago"   />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <ActivityRow icon="calendar-outline"      iconBg="#EFF6FF" iconColor="#3B82F6" title="New appointment booked"                 sub="James Wilson — 24 May 2024"       time="2 hrs ago"  />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <ActivityRow icon="document-text-outline" iconBg="#FEE2E2" iconColor={RED}     title="Referral from Esther Williams"          sub="Cardiology Consultation"          time="3 hrs ago"  />
      </Card>

      {/* Tasks & Reminders */}
      <Card title="Tasks & Reminders" action="View all" s={s}>
        <TaskRow icon="flask-outline"  iconBg="#D1FAE5" iconColor={GREEN}  title="Review lab results"      sub="3 pending" />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <TaskRow icon="people-outline" iconBg="#EEF2FF" iconColor={BLUE}   title="Follow up with patients" sub="5 pending" />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <TaskRow icon="medkit-outline" iconBg="#FEF3C7" iconColor={ORANGE} title="Pending prescriptions"   sub="2 pending" />
      </Card>

      {/* Patients Overview */}
      <Card title="Patients Overview" action="View all patients" s={s}>
        <View style={[styles.patientGrid, { gap: s(10) }]}>
          <PatientStatCard label="Total Patients"  value="248" growth="12% this month" icon="people-outline"     iconColor={BLUE}    bg="#F5F3FF" />
          <PatientStatCard label="Active Patients" value="186" growth="8% this month"  icon="people-outline"     iconColor={GREEN}   bg="#F0FDF4" />
          <PatientStatCard label="Chronic Care"    value="72"  growth="15% this month" icon="heart-outline"      iconColor={ORANGE}  bg="#FFFBEB" />
          <PatientStatCard label="New This Month"  value="24"  growth="20% this month" icon="person-add-outline" iconColor="#3B82F6" bg="#EFF6FF" />
        </View>
      </Card>

    </ScrollView>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  scroll: { flex: 1, backgroundColor: BG },

  header:      { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start' },
  greeting:    { fontWeight: '700', color: TEXT },
  greetingSub: { color: GRAY },
  dateBtn:     { flexDirection: 'row', alignItems: 'center', borderWidth: 1, borderColor: BORDER, backgroundColor: WHITE },
  dateText:    { color: TEXT, fontWeight: '500' },

  statCard:   { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  statIconBg: { alignItems: 'center', justifyContent: 'center' },
  statLabel:  { color: GRAY, marginBottom: 2 },
  statValue:  { fontWeight: '700', color: TEXT, marginBottom: 2 },
  statSub:    { fontWeight: '500' },

  card:       { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  cardHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  cardTitle:  { fontWeight: '700', color: TEXT },
  cardAction: { color: BLUE, fontWeight: '500' },

  sep: { height: StyleSheet.hairlineWidth, backgroundColor: BORDER },

  apptRow:     { flexDirection: 'row', alignItems: 'center' },
  apptTime:    { fontWeight: '600', color: BLUE },
  apptDur:     { color: GRAY, marginTop: 1 },
  apptName:    { fontWeight: '600', color: TEXT },
  apptType:    { color: GRAY, marginTop: 1 },
  badge:       {},
  badgeText:   { fontWeight: '600' },
  actionBtn:   { flexDirection: 'row', alignItems: 'center', borderWidth: 1 },
  actionBtnText: { fontWeight: '500' },
  viewAll:     { alignItems: 'center' },

  activityRow:  { flexDirection: 'row', alignItems: 'center' },
  iconCircle:   { alignItems: 'center', justifyContent: 'center' },
  activityTitle:{ fontWeight: '600', color: TEXT },
  activitySub:  { color: GRAY, marginTop: 1 },
  activityTime: { color: GRAY },

  patientGrid: { flexDirection: 'row', flexWrap: 'wrap' },
  patientCard: {},
  patientRow:  { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
});
