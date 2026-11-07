import { Ionicons } from '@expo/vector-icons';
import { ActivityIndicator, ScrollView, StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';
import { useCurrentUser } from '@/hooks/use-current-user';
import { greetingForHour, useDoctorDashboard } from '@/hooks/use-doctor-dashboard';
import type { ScheduleEntry } from '@/hooks/use-doctor-schedule';
import { doctorDisplayName } from '@/hooks/use-doctors';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const BLUE   = '#4F46E5';
const GREEN  = '#10B981';
const ORANGE = '#F59E0B';
const RED    = '#EF4444';
const PURPLE = '#8B5CF6';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

function useScale() {
  const { width, height } = useWindowDimensions();
  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));
  return { s, sh, width };
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatTime(iso: string) {
  return new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', hour12: true }).toUpperCase();
}

function formatDay(iso: string) {
  return new Date(iso).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });
}

function initials(name: string) {
  return name.split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
}

const AVATAR_COLORS = ['#4F46E5', '#0D9488', '#F59E0B', '#EF4444', '#8B5CF6', '#10B981'];
function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}

function statusBadge(status: ScheduleEntry['status']) {
  switch (status) {
    case 'COMPLETED':   return { label: 'Completed',   color: GREEN,  bg: '#D1FAE5' };
    case 'CANCELLED':   return { label: 'Cancelled',   color: RED,    bg: '#FEE2E2' };
    case 'NO_SHOW':     return { label: 'No Show',     color: ORANGE, bg: '#FEF3C7' };
    case 'RESCHEDULED': return { label: 'Rescheduled', color: PURPLE, bg: '#F5F3FF' };
    case 'IN_PROGRESS': return { label: 'In Progress', color: BLUE,   bg: '#EEF2FF' };
    default:            return { label: 'Scheduled',   color: BLUE,   bg: '#EEF2FF' };
  }
}

const TYPE_LABEL: Record<ScheduleEntry['type'], string> = { IN_PERSON: 'In person', VIDEO: 'Video', PHONE: 'Phone' };

// ─── Stat Card ────────────────────────────────────────────────────────────────

type StatCardProps = {
  icon: IoniconsName; iconBg: string; iconColor: string;
  label: string; value: string; sub: string; subColor: string;
};

function StatCard({ icon, iconBg, iconColor, label, value, sub, subColor }: StatCardProps) {
  const { s } = useScale();
  return (
    <View
      style={[styles.statCard, { width: s(148), borderRadius: s(12), padding: s(14) }]}
      accessible
      accessibilityLabel={`${label}: ${value}. ${sub}`}
    >
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

function AppointmentRow({ entry, showDay }: { entry: ScheduleEntry; showDay?: boolean }) {
  const { s } = useScale();
  const badge = statusBadge(entry.status);
  return (
    <View style={[styles.apptRow, { gap: s(8) }]}>
      <View style={{ width: s(62) }}>
        <Text style={[styles.apptTime, { fontSize: s(11) }]}>
          {showDay ? formatDay(entry.scheduled_at) : formatTime(entry.scheduled_at)}
        </Text>
        <Text style={[styles.apptDur,  { fontSize: s(10) }]}>
          {showDay ? formatTime(entry.scheduled_at) : `${entry.duration_minutes} mins`}
        </Text>
      </View>
      <View style={[styles.avatar, { width: s(36), height: s(36), borderRadius: s(18), backgroundColor: avatarColor(entry.patient_name) }]}>
        <Text style={[styles.avatarText, { fontSize: s(12) }]}>{initials(entry.patient_name)}</Text>
      </View>
      <View style={{ flex: 1, minWidth: 0 }}>
        <Text style={[styles.apptName, { fontSize: s(13) }]} numberOfLines={1}>{entry.patient_name}</Text>
        <Text style={[styles.apptType, { fontSize: s(11) }]} numberOfLines={1}>
          {TYPE_LABEL[entry.type] ?? entry.type}{entry.reason_for_visit ? ` · ${entry.reason_for_visit}` : ''}
        </Text>
        {entry.type === 'VIDEO' && (entry.status === 'SCHEDULED' || entry.status === 'RESCHEDULED') && (
          <Text style={{ fontSize: s(10), color: GRAY, marginTop: s(2) }} numberOfLines={2}>Join video consultations from the CareNexus web app.</Text>
        )}
      </View>
      <View style={[styles.badge, { backgroundColor: badge.bg, borderRadius: s(20), paddingHorizontal: s(7), paddingVertical: s(2) }]}>
        <Text style={[styles.badgeText, { fontSize: s(10), color: badge.color }]} numberOfLines={1}>{badge.label}</Text>
      </View>
    </View>
  );
}

// ─── Card Wrapper ─────────────────────────────────────────────────────────────

function Card({ title, action, onAction, s, children }: {
  title: string; action?: string; onAction?: () => void; s: (n: number) => number; children: React.ReactNode;
}) {
  return (
    <View style={[styles.card, { borderRadius: s(16), padding: s(16), marginBottom: s(16) }]}>
      <View style={[styles.cardHeader, { marginBottom: s(14) }]}>
        <Text style={[styles.cardTitle, { fontSize: s(15) }]} accessibilityRole="header">{title}</Text>
        {!!action && !!onAction && (
          <TouchableOpacity activeOpacity={0.7} onPress={onAction} accessibilityRole="button" accessibilityLabel={action}>
            <Text style={[styles.cardAction, { fontSize: s(12) }]}>{action}</Text>
          </TouchableOpacity>
        )}
      </View>
      {children}
    </View>
  );
}

// ─── Clinical records shortcuts ───────────────────────────────────────────────

const CLINICAL_SHORTCUTS: { key: string; label: string; hint: string; icon: IoniconsName; color: string; bg: string }[] = [
  { key: 'prescriptions',   label: 'Prescriptions',   hint: 'Prescriptions you have issued', icon: 'medkit-outline',        color: BLUE,      bg: '#EEF2FF' },
  { key: 'medical-records', label: 'Medical Records', hint: 'Patient records and notes',     icon: 'folder-open-outline',   color: '#0D9488', bg: '#CCFBF1' },
  { key: 'labs',            label: 'Lab Results',     hint: 'Lab test results',              icon: 'flask-outline',         color: PURPLE,    bg: '#F5F3FF' },
];

function ShortcutCard({ label, hint, icon, color, bg, onPress, s }: {
  label: string; hint: string; icon: IoniconsName; color: string; bg: string; onPress: () => void; s: (n: number) => number;
}) {
  return (
    <TouchableOpacity
      style={[styles.shortcut, { gap: s(10), paddingVertical: s(10) }]}
      activeOpacity={0.7}
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={`Open ${label}`}
      accessibilityHint={hint}
    >
      <View style={[styles.statIconBg, { backgroundColor: bg, width: s(36), height: s(36), borderRadius: s(10) }]}>
        <Ionicons name={icon} size={s(18)} color={color} />
      </View>
      <View style={{ flex: 1, minWidth: 0 }}>
        <Text style={[styles.apptName, { fontSize: s(13) }]} numberOfLines={1}>{label}</Text>
        <Text style={[styles.apptType, { fontSize: s(11) }]} numberOfLines={1}>{hint}</Text>
      </View>
      <Ionicons name="chevron-forward" size={s(16)} color={GRAY} />
    </TouchableOpacity>
  );
}

function EmptyLine({ icon, text, s }: { icon: IoniconsName; text: string; s: (n: number) => number }) {
  return (
    <View style={[styles.emptyLine, { gap: s(6), paddingVertical: s(16) }]}>
      <Ionicons name={icon} size={s(26)} color={BORDER} />
      <Text style={[styles.emptyText, { fontSize: s(12) }]}>{text}</Text>
    </View>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────

export function DoctorMainDashboardAreaMobile({ onNavigate }: { onNavigate?: (key: string) => void }) {
  const { s, sh } = useScale();
  const { user } = useCurrentUser();
  const { dashboard, loading, error, refetch } = useDoctorDashboard();

  const first = dashboard?.first_name || user?.first_name || '';
  const last  = dashboard?.last_name  || user?.last_name  || '';
  const name  = doctorDisplayName(first, last) || 'Doctor';
  const today = new Date().toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });

  const todayEntries = dashboard?.today ?? [];
  const upcoming     = dashboard?.upcoming ?? [];
  const nowMs        = Date.now();
  const stillToCome  = todayEntries.filter(e =>
    (e.status === 'SCHEDULED' || e.status === 'RESCHEDULED') && new Date(e.scheduled_at).getTime() >= nowMs,
  ).length;
  const todayActive  = todayEntries.filter(e => e.status !== 'CANCELLED').length;

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={{ padding: s(16), paddingBottom: sh(80) }} showsVerticalScrollIndicator={false}>

      {/* Header */}
      <View style={[styles.header, { marginBottom: s(20), gap: s(8) }]}>
        <View style={{ flex: 1, minWidth: 0 }}>
          <Text style={[styles.greeting, { fontSize: s(18) }]} accessibilityRole="header">{greetingForHour()}, {name}</Text>
          <Text style={[styles.greetingSub, { fontSize: s(12), marginTop: s(2) }]}>Here's your practice at a glance.</Text>
        </View>
        <View style={[styles.dateBtn, { gap: s(5), borderRadius: s(8), paddingHorizontal: s(10), paddingVertical: s(6) }]}>
          <Ionicons name="calendar-outline" size={s(13)} color={GRAY} />
          <Text style={[styles.dateText, { fontSize: s(11) }]}>{today}</Text>
        </View>
      </View>

      {loading && !dashboard ? (
        <View style={[styles.center, { paddingVertical: sh(48) }]}><ActivityIndicator size="large" color={BLUE} /></View>
      ) : error && !dashboard ? (
        <View style={[styles.center, { paddingVertical: sh(48), gap: s(10) }]}>
          <Ionicons name="alert-circle-outline" size={s(40)} color={RED} />
          <Text style={[styles.errorText, { fontSize: s(13) }]}>{error}</Text>
          <TouchableOpacity
            style={[styles.retryBtn, { borderRadius: s(8), paddingHorizontal: s(18), paddingVertical: s(9) }]}
            onPress={refetch}
            activeOpacity={0.8}
            accessibilityRole="button"
            accessibilityLabel="Retry loading dashboard"
          >
            <Text style={[styles.retryText, { fontSize: s(13) }]}>Retry</Text>
          </TouchableOpacity>
        </View>
      ) : (
        <>
          {/* Stat Cards — horizontal scroll */}
          <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ marginBottom: s(20) }} contentContainerStyle={{ gap: s(10), paddingRight: s(4) }}>
            <StatCard icon="calendar-outline"          iconBg="#EEF2FF" iconColor={BLUE}    label="Today's Appts"   value={String(todayActive)}                              sub={`${stillToCome} still to come`} subColor={BLUE}   />
            <StatCard icon="time-outline"              iconBg="#F5F3FF" iconColor={PURPLE}  label="Upcoming"        value={String(upcoming.length)}                          sub="Scheduled ahead"                subColor={GRAY}   />
            <StatCard icon="checkmark-circle-outline"  iconBg="#D1FAE5" iconColor={GREEN}   label="Consultations"   value={String(dashboard?.completed_consultations ?? 0)}  sub="Completed"                      subColor={GREEN}  />
            <StatCard icon="document-text-outline"     iconBg="#FEF3C7" iconColor={ORANGE}  label="Open Consults"   value={String(dashboard?.pending_consultations ?? 0)}    sub="Draft or in progress"           subColor={ORANGE} />
            <StatCard icon="people-outline"            iconBg="#EFF6FF" iconColor="#3B82F6" label="My Patients"     value={dashboard?.my_patients != null ? String(dashboard.my_patients) : '—'} sub="With appointments" subColor={GRAY} />
          </ScrollView>

          {/* Today's Schedule */}
          <Card title="Today's Schedule" action="Full schedule" onAction={onNavigate && (() => onNavigate('schedule'))} s={s}>
            {todayEntries.length === 0 ? (
              <EmptyLine icon="calendar-clear-outline" text="No appointments today" s={s} />
            ) : todayEntries.map((e, i) => (
              <View key={e.appointment_id}>
                {i > 0 && <View style={[styles.sep, { marginVertical: s(10) }]} />}
                <AppointmentRow entry={e} />
              </View>
            ))}
          </Card>

          {/* Coming up */}
          <Card title="Coming Up" action="Appointments" onAction={onNavigate && (() => onNavigate('appointments'))} s={s}>
            {upcoming.length === 0 ? (
              <EmptyLine icon="time-outline" text="No upcoming appointments" s={s} />
            ) : upcoming.slice(0, 5).map((e, i) => (
              <View key={e.appointment_id}>
                {i > 0 && <View style={[styles.sep, { marginVertical: s(10) }]} />}
                <AppointmentRow entry={e} showDay />
              </View>
            ))}
          </Card>
        </>
      )}

      {/* Clinical records — always reachable, even when the dashboard summary fails to load */}
      {!!onNavigate && (
        <Card title="Clinical records" s={s}>
          {CLINICAL_SHORTCUTS.map((c, i) => (
            <View key={c.key}>
              {i > 0 && <View style={styles.sep} />}
              <ShortcutCard label={c.label} hint={c.hint} icon={c.icon} color={c.color} bg={c.bg} onPress={() => onNavigate(c.key)} s={s} />
            </View>
          ))}
        </Card>
      )}

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
  badge:       { flexShrink: 0 },
  badgeText:   { fontWeight: '600' },
  actionBtn:   { flexDirection: 'row', alignItems: 'center', borderWidth: 1 },
  actionBtnText: { fontWeight: '500' },
  avatar:      { alignItems: 'center', justifyContent: 'center' },
  avatarText:  { color: WHITE, fontWeight: '700' },

  center:      { alignItems: 'center', justifyContent: 'center' },
  errorText:   { color: GRAY, textAlign: 'center' },
  retryBtn:    { backgroundColor: BLUE },
  retryText:   { color: WHITE, fontWeight: '600' },
  emptyLine:   { alignItems: 'center' },
  shortcut:    { flexDirection: 'row', alignItems: 'center' },
  emptyText:   { color: GRAY },

});
