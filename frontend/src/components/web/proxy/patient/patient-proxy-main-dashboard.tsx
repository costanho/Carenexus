import { Ionicons } from '@expo/vector-icons';
import { Image, ScrollView, StyleSheet, Text, TouchableOpacity, View, useWindowDimensions } from 'react-native';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const PURPLE = '#7C3AED';
const PURPLE_LIGHT = '#EDE9FE';
const PURPLE_BG    = '#F5F3FF';
const GREEN  = '#10B981';
const ORANGE = '#F59E0B';
const RED    = '#EF4444';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

// ─── Stat Card ────────────────────────────────────────────────────────────────

function StatCard({ icon, iconBg, iconColor, label, value, sub, subColor = GRAY }: {
  icon: IoniconsName; iconBg: string; iconColor: string;
  label: string; value: string; sub: string; subColor?: string;
}) {
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

// ─── Section Card ─────────────────────────────────────────────────────────────

function Card({ title, action, children, style }: {
  title: string; action?: string; children: React.ReactNode; style?: object;
}) {
  return (
    <View style={[styles.card, style]}>
      <View style={styles.cardHeader}>
        <Text style={styles.cardTitle}>{title}</Text>
        {action && (
          <TouchableOpacity activeOpacity={0.7}>
            <Text style={styles.cardAction}>{action}</Text>
          </TouchableOpacity>
        )}
      </View>
      {children}
    </View>
  );
}

// ─── Loved One Row ────────────────────────────────────────────────────────────

function LovedOneRow({ name, access, age, gender, lastUpdated, health, carePlan, lastVisit, lastVisitDoc, avatarId, dotColor }: {
  name: string; access: string; age: string; gender: string; lastUpdated: string;
  health: string; carePlan: string; lastVisit: string; lastVisitDoc: string;
  avatarId: number; dotColor: string;
}) {
  const isFullAccess = access === 'Full Access';
  return (
    <TouchableOpacity style={styles.lovedOneRow} activeOpacity={0.8}>
      <View style={styles.lovedOneLeft}>
        <View style={styles.lovedOneAvatarWrapper}>
          <Image source={{ uri: `https://i.pravatar.cc/150?img=${avatarId}` }} style={styles.lovedOneAvatar} />
          <View style={[styles.lovedOneDot, { backgroundColor: dotColor }]} />
        </View>
        <View>
          <Text style={styles.lovedOneName}>{name}</Text>
          <View style={[styles.accessBadge, { backgroundColor: isFullAccess ? '#D1FAE5' : '#FEF3C7' }]}>
            <Text style={[styles.accessText, { color: isFullAccess ? GREEN : ORANGE }]}>{access}</Text>
          </View>
          <Text style={styles.lovedOneMeta}>{age} • {gender}</Text>
          <Text style={styles.lovedOneUpdated}>Last updated: {lastUpdated}</Text>
        </View>
      </View>
      <View style={styles.lovedOneStats}>
        <View style={styles.lovedOneStat}>
          <Text style={styles.lovedOneStatLabel}>Health Status</Text>
          <View style={styles.lovedOneStatVal}>
            <Ionicons name="heart-outline" size={14} color={health === 'Stable' ? GREEN : ORANGE} />
            <Text style={[styles.lovedOneStatText, { color: health === 'Stable' ? GREEN : ORANGE }]}>{health}</Text>
          </View>
        </View>
        <View style={styles.statDivider} />
        <View style={styles.lovedOneStat}>
          <Text style={styles.lovedOneStatLabel}>Care Plan</Text>
          <View style={styles.lovedOneStatVal}>
            <Ionicons name="clipboard-outline" size={14} color={GRAY} />
            <Text style={styles.lovedOneStatText}>{carePlan}</Text>
          </View>
        </View>
        <View style={styles.statDivider} />
        <View style={styles.lovedOneStat}>
          <Text style={styles.lovedOneStatLabel}>Last Visit</Text>
          <View style={styles.lovedOneStatVal}>
            <Ionicons name="calendar-outline" size={14} color={GRAY} />
            <View>
              <Text style={styles.lovedOneStatText}>{lastVisit}</Text>
              <Text style={styles.lovedOneStatSub}>{lastVisitDoc}</Text>
            </View>
          </View>
        </View>
      </View>
      <Ionicons name="chevron-forward" size={18} color={GRAY} style={{ marginLeft: 8 }} />
    </TouchableOpacity>
  );
}

// ─── Schedule Row ─────────────────────────────────────────────────────────────

function ScheduleRow({ time, timeColor = TEXT, title, person, doctor, avatarId }: {
  time: string; timeColor?: string; title: string; person: string; doctor: string; avatarId: number;
}) {
  return (
    <View style={styles.scheduleRow}>
      <Image source={{ uri: `https://i.pravatar.cc/150?img=${avatarId}` }} style={styles.scheduleAvatar} />
      <View style={[styles.scheduleTimePill, { borderColor: timeColor === ORANGE ? ORANGE : BORDER }]}>
        <Text style={[styles.scheduleTime, { color: timeColor }]}>{time}</Text>
      </View>
      <View style={{ flex: 1 }}>
        <Text style={styles.scheduleTitle}>{title}</Text>
        <Text style={styles.schedulePerson}>{person}</Text>
        <Text style={styles.scheduleDoctor}>{doctor}</Text>
      </View>
      <TouchableOpacity style={styles.videoBtn} activeOpacity={0.8}>
        <Ionicons name="videocam-outline" size={18} color={GREEN} />
      </TouchableOpacity>
    </View>
  );
}

// ─── Update Row ───────────────────────────────────────────────────────────────

function UpdateRow({ icon, iconBg, iconColor, title, sub, time }: {
  icon: IoniconsName; iconBg: string; iconColor: string; title: string; sub: string; time: string;
}) {
  return (
    <View style={styles.updateRow}>
      <View style={[styles.updateIcon, { backgroundColor: iconBg }]}>
        <Ionicons name={icon} size={15} color={iconColor} />
      </View>
      <View style={{ flex: 1 }}>
        <Text style={styles.updateTitle}>{title}</Text>
        <Text style={styles.updateSub}>{sub}</Text>
      </View>
      <Text style={styles.updateTime}>{time}</Text>
    </View>
  );
}

// ─── Medication Row ───────────────────────────────────────────────────────────

function MedRow({ name, dose, person, dueTime, dueLabel, dueColor }: {
  name: string; dose: string; person: string; dueTime: string; dueLabel: string; dueColor: string;
}) {
  return (
    <View style={styles.medRow}>
      <View style={[styles.updateIcon, { backgroundColor: '#F5F3FF' }]}>
        <Ionicons name="medical-outline" size={15} color={PURPLE} />
      </View>
      <View style={{ flex: 1 }}>
        <Text style={styles.updateTitle}>{name}</Text>
        <Text style={styles.updateSub}>{dose}</Text>
        <Text style={styles.updateSub}>{person}</Text>
      </View>
      <View style={[styles.duePill, { backgroundColor: dueColor + '20', borderColor: dueColor + '40' }]}>
        <Text style={[styles.dueTime, { color: dueColor }]}>Due {dueTime}</Text>
        <Text style={[styles.dueLabel, { color: dueColor }]}>{dueLabel}</Text>
      </View>
    </View>
  );
}

// ─── Care Team Row ────────────────────────────────────────────────────────────

function CareTeamRow({ name, role, avatarId }: { name: string; role: string; avatarId: number }) {
  return (
    <View style={styles.careRow}>
      <Image source={{ uri: `https://i.pravatar.cc/150?img=${avatarId}` }} style={styles.careAvatar} />
      <View style={{ flex: 1 }}>
        <Text style={styles.careName}>{name}</Text>
        <Text style={styles.careRole}>{role}</Text>
      </View>
      <TouchableOpacity style={styles.messageBtn} activeOpacity={0.8}>
        <Ionicons name="chatbox-outline" size={13} color={WHITE} />
        <Text style={styles.messageBtnText}>Message</Text>
      </TouchableOpacity>
    </View>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────

export function PatientProxyMainDashboard() {
  const { width } = useWindowDimensions();
  const isLarge = width >= 1024;
  const isMedium = width >= 768;

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>

      {/* Stat Cards */}
      <View style={styles.statsRow}>
        <StatCard icon="calendar-outline"      iconBg="#EDE9FE" iconColor={PURPLE} label="Upcoming Appointments" value="2" sub="Next: Tomorrow, 10:30 AM" />
        <StatCard icon="medical-outline"       iconBg="#D1FAE5" iconColor={GREEN}  label="Medications Due"       value="3" sub="Today" />
        <StatCard icon="flask-outline"         iconBg="#FEF3C7" iconColor={ORANGE} label="Pending Results"       value="1" sub="Awaiting Review" />
        <StatCard icon="chatbox-outline"       iconBg="#EDE9FE" iconColor={PURPLE} label="Unread Messages"       value="2" sub="From Care Team" />
        <StatCard icon="warning-outline"       iconBg="#FEE2E2" iconColor={RED}    label="Alerts"                value="1" sub="Requires Attention" subColor={RED} />
      </View>

      {/* Top row: Loved One Overview + Today's Schedule */}
      <View style={[styles.row, isLarge && styles.rowHorizontal]}>
        {/* Loved One Overview */}
        <Card title="Loved One Overview" style={[styles.colWide, !isLarge && styles.colFull]}>
          <LovedOneRow
            name="Mary Davis (Mom)" access="Full Access" age="78 years" gender="Female"
            lastUpdated="2 hrs ago" health="Stable" carePlan="Active"
            lastVisit="24 May 2024" lastVisitDoc="with Dr. Matt" avatarId={47} dotColor={GREEN}
          />
          <View style={styles.sep} />
          <LovedOneRow
            name="Robert Davis (Dad)" access="View Only" age="82 years" gender="Male"
            lastUpdated="1 day ago" health="Monitor" carePlan="Active"
            lastVisit="18 May 2024" lastVisitDoc="with Dr. Matt" avatarId={15} dotColor={ORANGE}
          />
          <TouchableOpacity style={styles.viewAllBtn} activeOpacity={0.7}>
            <Text style={styles.viewAllText}>View all loved ones →</Text>
          </TouchableOpacity>
        </Card>

        {/* Today's Schedule */}
        <Card title="Today's Schedule" action="View Calendar" style={[styles.colNarrow, !isLarge && styles.colFull]}>
          <ScheduleRow time="10:30 AM" title="Follow-up Consultation" person="Mary Davis (Mom)"  doctor="Dr. Matt (Physician)" avatarId={47} />
          <View style={styles.sep} />
          <ScheduleRow time="02:00 PM" timeColor={ORANGE} title="Lab Review"             person="Mary Davis (Mom)"  doctor="Dr. Matt (Physician)" avatarId={47} />
          <View style={styles.sep} />
          <ScheduleRow time="04:30 PM" title="Blood Pressure Check"   person="Robert Davis (Dad)" doctor="Care Nurse"          avatarId={15} />
          <TouchableOpacity style={styles.viewAllBtn} activeOpacity={0.7}>
            <Text style={styles.viewAllText}>View all appointments →</Text>
          </TouchableOpacity>
        </Card>
      </View>

      {/* Bottom row: Recent Updates + Medications + Care Team */}
      <View style={[styles.row, isMedium && styles.rowHorizontal]}>
        {/* Recent Updates */}
        <Card title="Recent Updates" action="View all" style={[styles.colThird, !isMedium && styles.colFull]}>
          <UpdateRow icon="document-text-outline" iconBg="#EDE9FE" iconColor={PURPLE} title="Dr. Matt sent consultation summary" sub="Mary Davis • 24 May 2024"    time="2 hrs ago"  />
          <View style={styles.sep} />
          <UpdateRow icon="medkit-outline"         iconBg="#FEF3C7" iconColor={ORANGE} title="New prescription issued"            sub="Mary Davis • Amlodipine 5mg"  time="3 hrs ago"  />
          <View style={styles.sep} />
          <UpdateRow icon="flask-outline"          iconBg="#D1FAE5" iconColor={GREEN}  title="Lab results uploaded"               sub="Mary Davis • Blood Test"       time="1 day ago"  />
          <View style={styles.sep} />
          <UpdateRow icon="chatbox-outline"        iconBg="#EDE9FE" iconColor={PURPLE} title="Message from Dr. Matt"              sub='"Please ensure medication is taken daily."' time="2 days ago" />
          <TouchableOpacity style={styles.viewAllBtn} activeOpacity={0.7}>
            <Text style={styles.viewAllText}>View all updates →</Text>
          </TouchableOpacity>
        </Card>

        {/* Medications */}
        <Card title="Medications" action="View all" style={[styles.colThird, !isMedium && styles.colFull]}>
          <MedRow name="Amlodipine 5mg"   dose="1 tablet • Once daily"   person="Mary Davis (Mom)" dueTime="08:00 AM" dueLabel="Today" dueColor={GREEN}  />
          <View style={styles.sep} />
          <MedRow name="Metformin 500mg"  dose="1 tablet • Twice daily"  person="Mary Davis (Mom)" dueTime="08:00 AM" dueLabel="Today" dueColor={GREEN}  />
          <View style={styles.sep} />
          <MedRow name="Atorvastatin 20mg" dose="1 tablet • At night"    person="Mary Davis (Mom)" dueTime="08:00 PM" dueLabel="Today" dueColor={PURPLE} />
          <TouchableOpacity style={styles.viewAllBtn} activeOpacity={0.7}>
            <Text style={styles.viewAllText}>View all medications →</Text>
          </TouchableOpacity>
        </Card>

        {/* Care Team */}
        <Card title="Care Team" action="View all" style={[styles.colThird, !isMedium && styles.colFull]}>
          <CareTeamRow name="Dr. Matt"          role="Physician"   avatarId={12} />
          <View style={styles.sep} />
          <CareTeamRow name="Nurse Clara"        role="Care Nurse"  avatarId={32} />
          <View style={styles.sep} />
          <CareTeamRow name="Lab Support"        role="Laboratory"  avatarId={22} />
          <View style={styles.sep} />
          <CareTeamRow name="Pharmacy Support"   role="Pharmacist"  avatarId={55} />
          <TouchableOpacity style={styles.viewAllBtn} activeOpacity={0.7}>
            <Text style={styles.viewAllText}>View full care team →</Text>
          </TouchableOpacity>
        </Card>
      </View>

      {/* Proxy Access Banner */}
      <View style={styles.banner}>
        <View style={styles.bannerIconWrapper}>
          <Ionicons name="shield-outline" size={28} color={PURPLE} />
        </View>
        <View style={{ flex: 1 }}>
          <Text style={styles.bannerTitle}>You are a Full Access Proxy for Mary Davis</Text>
          <Text style={styles.bannerSub}>
            You can view health info, appointments, medications, results and communicate with the care team. You can also join consultations when permitted.
          </Text>
        </View>
        <TouchableOpacity style={styles.manageBtn} activeOpacity={0.8}>
          <Ionicons name="people-outline" size={15} color={PURPLE} />
          <Text style={styles.manageBtnText}>Manage Permissions</Text>
        </TouchableOpacity>
      </View>

    </ScrollView>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  scroll:    { flex: 1, backgroundColor: BG },
  container: { padding: 20, paddingBottom: 40, gap: 20 },

  // Stat cards
  statsRow: { flexDirection: 'row', gap: 12 },
  statCard: {
    flex: 1, backgroundColor: WHITE,
    borderWidth: 1, borderColor: BORDER, borderRadius: 12, padding: 16,
  },
  statIconBg: { width: 42, height: 42, borderRadius: 10, alignItems: 'center', justifyContent: 'center', marginBottom: 10 },
  statLabel:  { fontSize: 12, color: GRAY, marginBottom: 4 },
  statValue:  { fontSize: 26, fontWeight: '700', color: TEXT, marginBottom: 2 },
  statSub:    { fontSize: 11, color: GRAY },

  // Layout rows
  row:           { gap: 16 },
  rowHorizontal: { flexDirection: 'row', alignItems: 'flex-start' },
  colWide:       { flex: 1.4 },
  colNarrow:     { flex: 1 },
  colThird:      { flex: 1 },
  colFull:       { flex: undefined },

  // Card
  card: {
    backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER,
    borderRadius: 16, padding: 20,
  },
  cardHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 },
  cardTitle:  { fontSize: 16, fontWeight: '700', color: TEXT },
  cardAction: { fontSize: 13, color: PURPLE, fontWeight: '500' },

  sep: { height: 1, backgroundColor: BORDER, marginVertical: 12 },

  viewAllBtn:  { alignItems: 'center', marginTop: 12 },
  viewAllText: { fontSize: 13, color: PURPLE, fontWeight: '500' },

  // Loved one
  lovedOneRow: {
    flexDirection: 'row', alignItems: 'center', gap: 12,
    backgroundColor: BG, borderRadius: 12, padding: 14,
  },
  lovedOneLeft:         { width: 180 },
  lovedOneAvatarWrapper:{ position: 'relative', marginBottom: 6 },
  lovedOneAvatar:       { width: 52, height: 52, borderRadius: 26, backgroundColor: BORDER },
  lovedOneDot: {
    width: 12, height: 12, borderRadius: 6, borderWidth: 2, borderColor: BG,
    position: 'absolute', bottom: 1, right: 1,
  },
  lovedOneName:    { fontSize: 15, fontWeight: '700', color: TEXT, marginBottom: 4 },
  accessBadge:     { alignSelf: 'flex-start', borderRadius: 20, paddingHorizontal: 8, paddingVertical: 2, marginBottom: 4 },
  accessText:      { fontSize: 11, fontWeight: '600' },
  lovedOneMeta:    { fontSize: 12, color: GRAY },
  lovedOneUpdated: { fontSize: 11, color: GRAY, marginTop: 2 },
  lovedOneStats:   { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 12 },
  lovedOneStat:    { flex: 1 },
  lovedOneStatLabel: { fontSize: 11, color: GRAY, marginBottom: 4 },
  lovedOneStatVal: { flexDirection: 'row', alignItems: 'center', gap: 5 },
  lovedOneStatText:{ fontSize: 13, fontWeight: '600', color: TEXT },
  lovedOneStatSub: { fontSize: 11, color: GRAY },
  statDivider:     { width: 1, height: 40, backgroundColor: BORDER },

  // Schedule
  scheduleRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  scheduleAvatar: { width: 44, height: 44, borderRadius: 22, backgroundColor: BORDER },
  scheduleTimePill: {
    borderWidth: 1, borderRadius: 8,
    paddingHorizontal: 8, paddingVertical: 4, alignItems: 'center',
  },
  scheduleTime:   { fontSize: 12, fontWeight: '700' },
  scheduleTitle:  { fontSize: 13, fontWeight: '700', color: TEXT },
  schedulePerson: { fontSize: 12, color: GRAY, marginTop: 1 },
  scheduleDoctor: { fontSize: 12, color: GRAY },
  videoBtn: {
    width: 36, height: 36, borderRadius: 8, borderWidth: 1,
    borderColor: GREEN + '50', backgroundColor: GREEN + '15',
    alignItems: 'center', justifyContent: 'center',
  },

  // Updates & Meds
  updateRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 10 },
  medRow:    { flexDirection: 'row', alignItems: 'flex-start', gap: 10 },
  updateIcon:{ width: 32, height: 32, borderRadius: 16, alignItems: 'center', justifyContent: 'center', marginTop: 1 },
  updateTitle:{ fontSize: 13, fontWeight: '600', color: TEXT },
  updateSub:  { fontSize: 11, color: GRAY, marginTop: 1 },
  updateTime: { fontSize: 11, color: GRAY },
  duePill: {
    borderWidth: 1, borderRadius: 8, paddingHorizontal: 8, paddingVertical: 4, alignItems: 'center',
  },
  dueTime:  { fontSize: 11, fontWeight: '700' },
  dueLabel: { fontSize: 10, fontWeight: '500' },

  // Care team
  careRow:    { flexDirection: 'row', alignItems: 'center', gap: 10 },
  careAvatar: { width: 40, height: 40, borderRadius: 20, backgroundColor: BORDER },
  careName:   { fontSize: 14, fontWeight: '600', color: TEXT },
  careRole:   { fontSize: 12, color: GRAY, marginTop: 1 },
  messageBtn: {
    flexDirection: 'row', alignItems: 'center', gap: 5,
    backgroundColor: PURPLE, borderRadius: 8, paddingHorizontal: 10, paddingVertical: 6,
  },
  messageBtnText: { fontSize: 12, color: WHITE, fontWeight: '600' },

  // Banner
  banner: {
    flexDirection: 'row', alignItems: 'center', gap: 16,
    backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER,
    borderRadius: 16, padding: 20,
  },
  bannerIconWrapper: {
    width: 56, height: 56, borderRadius: 28,
    backgroundColor: PURPLE_BG, borderWidth: 1, borderColor: PURPLE_LIGHT,
    alignItems: 'center', justifyContent: 'center',
  },
  bannerTitle: { fontSize: 15, fontWeight: '700', color: TEXT, marginBottom: 4 },
  bannerSub:   { fontSize: 12, color: GRAY, lineHeight: 18 },
  manageBtn: {
    flexDirection: 'row', alignItems: 'center', gap: 6,
    borderWidth: 1, borderColor: BORDER, borderRadius: 10,
    paddingHorizontal: 14, paddingVertical: 10, backgroundColor: WHITE,
  },
  manageBtnText: { fontSize: 13, color: PURPLE, fontWeight: '600' },
});
