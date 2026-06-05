import { Ionicons } from '@expo/vector-icons';
import { Image, ScrollView, StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const PURPLE = '#7C3AED';
const PURPLE_BG   = '#F5F3FF';
const PURPLE_LIGHT= '#EDE9FE';
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

function StatCard({ icon, iconBg, iconColor, label, value, sub, subColor = GRAY }: {
  icon: IoniconsName; iconBg: string; iconColor: string;
  label: string; value: string; sub: string; subColor?: string;
}) {
  const { s } = useScale();
  return (
    <View style={[styles.statCard, { width: s(150), borderRadius: s(12), padding: s(14) }]}>
      <View style={[styles.statIconBg, { backgroundColor: iconBg, width: s(38), height: s(38), borderRadius: s(10), marginBottom: s(8) }]}>
        <Ionicons name={icon} size={s(18)} color={iconColor} />
      </View>
      <Text style={[styles.statLabel, { fontSize: s(10) }]}>{label}</Text>
      <Text style={[styles.statValue, { fontSize: s(22) }]}>{value}</Text>
      <Text style={[styles.statSub,   { fontSize: s(10), color: subColor }]}>{sub}</Text>
    </View>
  );
}

// ─── Card Wrapper ─────────────────────────────────────────────────────────────

function Card({ title, action, s, children }: {
  title: string; action?: string; s: (n: number) => number; children: React.ReactNode;
}) {
  return (
    <View style={[styles.card, { borderRadius: s(14), padding: s(14), marginBottom: s(14) }]}>
      <View style={[styles.cardHeader, { marginBottom: s(12) }]}>
        <Text style={[styles.cardTitle, { fontSize: s(14) }]}>{title}</Text>
        {action && (
          <TouchableOpacity activeOpacity={0.7}>
            <Text style={[styles.cardAction, { fontSize: s(11) }]}>{action}</Text>
          </TouchableOpacity>
        )}
      </View>
      {children}
    </View>
  );
}

// ─── Loved One Row ────────────────────────────────────────────────────────────

function LovedOneRow({ name, access, age, gender, lastUpdated, health, lastVisit, avatarId, dotColor }: {
  name: string; access: string; age: string; gender: string;
  lastUpdated: string; health: string; lastVisit: string; avatarId: number; dotColor: string;
}) {
  const { s } = useScale();
  const isFull = access === 'Full Access';
  return (
    <TouchableOpacity style={[styles.lovedOneRow, { gap: s(10), borderRadius: s(10), padding: s(10) }]} activeOpacity={0.8}>
      <View>
        <Image source={{ uri: `https://i.pravatar.cc/150?img=${avatarId}` }}
          style={{ width: s(46), height: s(46), borderRadius: s(23), backgroundColor: BORDER }} />
        <View style={[styles.dot, { backgroundColor: dotColor, width: s(11), height: s(11), borderRadius: s(6) }]} />
      </View>
      <View style={{ flex: 1 }}>
        <Text style={[styles.lovedOneName, { fontSize: s(13) }]} numberOfLines={1}>{name}</Text>
        <View style={[styles.accessBadge, { backgroundColor: isFull ? '#D1FAE5' : '#FEF3C7', borderRadius: s(20), paddingHorizontal: s(7), paddingVertical: s(2), marginBottom: s(3) }]}>
          <Text style={[styles.accessText, { fontSize: s(10), color: isFull ? GREEN : ORANGE }]}>{access}</Text>
        </View>
        <Text style={[styles.lovedOneMeta, { fontSize: s(11) }]}>{age} • {gender}</Text>
        <Text style={[styles.lovedOneMeta, { fontSize: s(10) }]}>Updated: {lastUpdated}</Text>
      </View>
      <View style={{ alignItems: 'flex-end', gap: s(4) }}>
        <View style={[styles.healthPill, { backgroundColor: health === 'Stable' ? '#D1FAE5' : '#FEF3C7', borderRadius: s(8), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
          <Text style={[{ fontSize: s(11), fontWeight: '600', color: health === 'Stable' ? GREEN : ORANGE }]}>{health}</Text>
        </View>
        <Text style={[styles.lovedOneMeta, { fontSize: s(10) }]}>{lastVisit}</Text>
      </View>
      <Ionicons name="chevron-forward" size={s(14)} color={GRAY} />
    </TouchableOpacity>
  );
}

// ─── Schedule Row ─────────────────────────────────────────────────────────────

function ScheduleRow({ time, timeColor = TEXT, title, person, doctor, avatarId, s }: {
  time: string; timeColor?: string; title: string; person: string; doctor: string; avatarId: number; s: (n: number) => number;
}) {
  return (
    <View style={[styles.scheduleRow, { gap: s(8) }]}>
      <Image source={{ uri: `https://i.pravatar.cc/150?img=${avatarId}` }}
        style={{ width: s(38), height: s(38), borderRadius: s(19), backgroundColor: BORDER }} />
      <View style={{ flex: 1 }}>
        <Text style={[styles.cardTitle, { fontSize: s(12) }]} numberOfLines={1}>{title}</Text>
        <Text style={[styles.lovedOneMeta, { fontSize: s(11) }]}>{person}</Text>
        <Text style={[styles.lovedOneMeta, { fontSize: s(10) }]}>{doctor}</Text>
      </View>
      <View style={[styles.timePill, { borderRadius: s(6), paddingHorizontal: s(7), paddingVertical: s(3), borderColor: timeColor === ORANGE ? ORANGE : BORDER }]}>
        <Text style={[{ fontSize: s(11), fontWeight: '700', color: timeColor }]}>{time}</Text>
      </View>
      <TouchableOpacity style={[styles.videoBtn, { width: s(32), height: s(32), borderRadius: s(8) }]} activeOpacity={0.8}>
        <Ionicons name="videocam-outline" size={s(15)} color={GREEN} />
      </TouchableOpacity>
    </View>
  );
}

// ─── Update Row ───────────────────────────────────────────────────────────────

function UpdateRow({ icon, iconBg, iconColor, title, sub, time, s }: {
  icon: IoniconsName; iconBg: string; iconColor: string; title: string; sub: string; time: string; s: (n: number) => number;
}) {
  return (
    <View style={[styles.updateRow, { gap: s(10) }]}>
      <View style={[styles.iconCircle, { backgroundColor: iconBg, width: s(32), height: s(32), borderRadius: s(16) }]}>
        <Ionicons name={icon} size={s(14)} color={iconColor} />
      </View>
      <View style={{ flex: 1 }}>
        <Text style={[styles.updateTitle, { fontSize: s(12) }]} numberOfLines={1}>{title}</Text>
        <Text style={[styles.lovedOneMeta, { fontSize: s(10) }]} numberOfLines={1}>{sub}</Text>
      </View>
      <Text style={[styles.lovedOneMeta, { fontSize: s(10) }]}>{time}</Text>
    </View>
  );
}

// ─── Med Row ──────────────────────────────────────────────────────────────────

function MedRow({ name, dose, dueTime, dueColor, s }: {
  name: string; dose: string; dueTime: string; dueColor: string; s: (n: number) => number;
}) {
  return (
    <View style={[styles.updateRow, { gap: s(10) }]}>
      <View style={[styles.iconCircle, { backgroundColor: PURPLE_BG, width: s(32), height: s(32), borderRadius: s(16) }]}>
        <Ionicons name="medical-outline" size={s(14)} color={PURPLE} />
      </View>
      <View style={{ flex: 1 }}>
        <Text style={[styles.updateTitle, { fontSize: s(12) }]}>{name}</Text>
        <Text style={[styles.lovedOneMeta, { fontSize: s(10) }]}>{dose}</Text>
      </View>
      <View style={[styles.duePill, { backgroundColor: dueColor + '20', borderColor: dueColor + '40', borderRadius: s(8), paddingHorizontal: s(8), paddingVertical: s(4) }]}>
        <Text style={[{ fontSize: s(10), fontWeight: '700', color: dueColor }]}>Due {dueTime}</Text>
        <Text style={[{ fontSize: s(9), color: dueColor }]}>Today</Text>
      </View>
    </View>
  );
}

// ─── Care Team Row ────────────────────────────────────────────────────────────

function CareRow({ name, role, avatarId, s }: { name: string; role: string; avatarId: number; s: (n: number) => number }) {
  return (
    <View style={[styles.updateRow, { gap: s(10) }]}>
      <Image source={{ uri: `https://i.pravatar.cc/150?img=${avatarId}` }}
        style={{ width: s(36), height: s(36), borderRadius: s(18), backgroundColor: BORDER }} />
      <View style={{ flex: 1 }}>
        <Text style={[styles.updateTitle,  { fontSize: s(13) }]}>{name}</Text>
        <Text style={[styles.lovedOneMeta, { fontSize: s(11) }]}>{role}</Text>
      </View>
      <TouchableOpacity style={[styles.msgBtn, { borderRadius: s(8), paddingHorizontal: s(10), paddingVertical: s(5), gap: s(4) }]} activeOpacity={0.8}>
        <Ionicons name="chatbox-outline" size={s(12)} color={WHITE} />
        <Text style={[styles.msgBtnText, { fontSize: s(11) }]}>Message</Text>
      </TouchableOpacity>
    </View>
  );
}

// ─── Main ─────────────────────────────────────────────────────────────────────

export function PatientProxyMainDashboardMobile() {
  const { s, sh } = useScale();

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={{ padding: s(14), paddingBottom: sh(90) }} showsVerticalScrollIndicator={false}>

      {/* Stat cards — horizontal scroll */}
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ marginBottom: s(16) }} contentContainerStyle={{ gap: s(10), paddingRight: s(4) }}>
        <StatCard icon="calendar-outline" iconBg="#EDE9FE" iconColor={PURPLE} label="Upcoming Appts"  value="2" sub="Tomorrow, 10:30 AM" />
        <StatCard icon="medical-outline"  iconBg="#D1FAE5" iconColor={GREEN}  label="Medications Due" value="3" sub="Today"              />
        <StatCard icon="flask-outline"    iconBg="#FEF3C7" iconColor={ORANGE} label="Pending Results" value="1" sub="Awaiting Review"    />
        <StatCard icon="chatbox-outline"  iconBg="#EDE9FE" iconColor={PURPLE} label="Unread Messages" value="2" sub="From Care Team"     />
        <StatCard icon="warning-outline"  iconBg="#FEE2E2" iconColor={RED}    label="Alerts"          value="1" sub="Requires Attention" subColor={RED} />
      </ScrollView>

      {/* Loved One Overview */}
      <Card title="Loved One Overview" s={s}>
        <LovedOneRow name="Mary Davis (Mom)"   access="Full Access" age="78 years" gender="Female" lastUpdated="2 hrs ago"  health="Stable"  lastVisit="24 May 2024" avatarId={47} dotColor={GREEN}  />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <LovedOneRow name="Robert Davis (Dad)" access="View Only"   age="82 years" gender="Male"   lastUpdated="1 day ago"  health="Monitor" lastVisit="18 May 2024" avatarId={15} dotColor={ORANGE} />
        <TouchableOpacity style={[styles.viewAllBtn, { marginTop: s(12) }]} activeOpacity={0.7}>
          <Text style={[styles.cardAction, { fontSize: s(12) }]}>View all loved ones →</Text>
        </TouchableOpacity>
      </Card>

      {/* Today's Schedule */}
      <Card title="Today's Schedule" action="View Calendar" s={s}>
        <ScheduleRow time="10:30 AM" title="Follow-up Consultation" person="Mary Davis (Mom)"  doctor="Dr. Matt (Physician)" avatarId={47} s={s} />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <ScheduleRow time="02:00 PM" timeColor={ORANGE} title="Lab Review" person="Mary Davis (Mom)" doctor="Dr. Matt (Physician)" avatarId={47} s={s} />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <ScheduleRow time="04:30 PM" title="Blood Pressure Check" person="Robert Davis (Dad)" doctor="Care Nurse" avatarId={15} s={s} />
        <TouchableOpacity style={[styles.viewAllBtn, { marginTop: s(12) }]} activeOpacity={0.7}>
          <Text style={[styles.cardAction, { fontSize: s(12) }]}>View all appointments →</Text>
        </TouchableOpacity>
      </Card>

      {/* Recent Updates */}
      <Card title="Recent Updates" action="View all" s={s}>
        <UpdateRow icon="document-text-outline" iconBg={PURPLE_LIGHT} iconColor={PURPLE} title="Dr. Matt sent consultation summary" sub="Mary Davis • 24 May 2024"   time="2 hrs ago"  s={s} />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <UpdateRow icon="medkit-outline"         iconBg="#FEF3C7"      iconColor={ORANGE} title="New prescription issued"            sub="Mary Davis • Amlodipine 5mg" time="3 hrs ago"  s={s} />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <UpdateRow icon="flask-outline"          iconBg="#D1FAE5"      iconColor={GREEN}  title="Lab results uploaded"               sub="Mary Davis • Blood Test"      time="1 day ago"  s={s} />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <UpdateRow icon="chatbox-outline"        iconBg={PURPLE_LIGHT} iconColor={PURPLE} title="Message from Dr. Matt"              sub='"Ensure medication is taken daily."' time="2 days ago" s={s} />
        <TouchableOpacity style={[styles.viewAllBtn, { marginTop: s(12) }]} activeOpacity={0.7}>
          <Text style={[styles.cardAction, { fontSize: s(12) }]}>View all updates →</Text>
        </TouchableOpacity>
      </Card>

      {/* Medications */}
      <Card title="Medications" action="View all" s={s}>
        <MedRow name="Amlodipine 5mg"    dose="1 tablet • Once daily"  dueTime="08:00 AM" dueColor={GREEN}  s={s} />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <MedRow name="Metformin 500mg"   dose="1 tablet • Twice daily" dueTime="08:00 AM" dueColor={GREEN}  s={s} />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <MedRow name="Atorvastatin 20mg" dose="1 tablet • At night"    dueTime="08:00 PM" dueColor={PURPLE} s={s} />
        <TouchableOpacity style={[styles.viewAllBtn, { marginTop: s(12) }]} activeOpacity={0.7}>
          <Text style={[styles.cardAction, { fontSize: s(12) }]}>View all medications →</Text>
        </TouchableOpacity>
      </Card>

      {/* Care Team */}
      <Card title="Care Team" action="View all" s={s}>
        <CareRow name="Dr. Matt"        role="Physician"  avatarId={12} s={s} />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <CareRow name="Nurse Clara"     role="Care Nurse" avatarId={32} s={s} />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <CareRow name="Lab Support"     role="Laboratory" avatarId={22} s={s} />
        <View style={[styles.sep, { marginVertical: s(10) }]} />
        <CareRow name="Pharmacy Support" role="Pharmacist" avatarId={55} s={s} />
        <TouchableOpacity style={[styles.viewAllBtn, { marginTop: s(12) }]} activeOpacity={0.7}>
          <Text style={[styles.cardAction, { fontSize: s(12) }]}>View full care team →</Text>
        </TouchableOpacity>
      </Card>

      {/* Proxy Access Banner */}
      <View style={[styles.banner, { borderRadius: s(14), padding: s(14), gap: s(12) }]}>
        <View style={[styles.bannerIcon, { width: s(48), height: s(48), borderRadius: s(24) }]}>
          <Ionicons name="shield-outline" size={s(24)} color={PURPLE} />
        </View>
        <View style={{ flex: 1 }}>
          <Text style={[styles.updateTitle, { fontSize: s(13), marginBottom: s(4) }]}>Full Access Proxy for Mary Davis</Text>
          <Text style={[styles.lovedOneMeta, { fontSize: s(11), lineHeight: s(16) }]}>
            You can view health info, appointments, medications and communicate with the care team.
          </Text>
        </View>
      </View>

    </ScrollView>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  scroll: { flex: 1, backgroundColor: BG },

  statCard:   { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  statIconBg: { alignItems: 'center', justifyContent: 'center' },
  statLabel:  { color: GRAY, marginBottom: 2 },
  statValue:  { fontWeight: '700', color: TEXT, marginBottom: 2 },
  statSub:    { color: GRAY },

  card:       { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  cardHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  cardTitle:  { fontWeight: '700', color: TEXT },
  cardAction: { color: PURPLE, fontWeight: '500' },

  sep:        { height: StyleSheet.hairlineWidth, backgroundColor: BORDER },
  viewAllBtn: { alignItems: 'center' },

  lovedOneRow:   { flexDirection: 'row', alignItems: 'center', backgroundColor: BG },
  dot:           { position: 'absolute', bottom: 1, right: 1, borderWidth: 2, borderColor: BG },
  lovedOneName:  { fontWeight: '700', color: TEXT, marginBottom: 2 },
  accessBadge:   { alignSelf: 'flex-start' },
  accessText:    { fontWeight: '600' },
  lovedOneMeta:  { color: GRAY },
  healthPill:    {},

  scheduleRow: { flexDirection: 'row', alignItems: 'center' },
  timePill:    { borderWidth: 1, alignItems: 'center' },
  videoBtn:    { borderWidth: 1, borderColor: GREEN + '50', backgroundColor: GREEN + '15', alignItems: 'center', justifyContent: 'center' },

  updateRow:   { flexDirection: 'row', alignItems: 'center' },
  iconCircle:  { alignItems: 'center', justifyContent: 'center' },
  updateTitle: { fontWeight: '600', color: TEXT },
  duePill:     { borderWidth: 1, alignItems: 'center' },

  msgBtn:      { flexDirection: 'row', alignItems: 'center', backgroundColor: PURPLE },
  msgBtnText:  { color: WHITE, fontWeight: '600' },

  banner:      { flexDirection: 'row', alignItems: 'center', backgroundColor: PURPLE_BG, borderWidth: 1, borderColor: PURPLE_LIGHT },
  bannerIcon:  { backgroundColor: WHITE, borderWidth: 1, borderColor: PURPLE_LIGHT, alignItems: 'center', justifyContent: 'center' },
});
