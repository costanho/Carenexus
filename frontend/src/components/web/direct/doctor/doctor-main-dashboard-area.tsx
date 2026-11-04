import { Ionicons } from '@expo/vector-icons';
import { ActivityIndicator, ScrollView, StyleSheet, Text, TouchableOpacity, View, useWindowDimensions } from 'react-native';
import { useCurrentUser } from '@/hooks/use-current-user';
import { doctorCallJoin } from '@/hooks/use-doctor-appointment-actions';
import { CallJoinButton } from '@/components/web/call/call-join-button';
import { greetingForHour, useDoctorDashboard } from '@/hooks/use-doctor-dashboard';
import type { ScheduleEntry } from '@/hooks/use-doctor-schedule';

const WHITE   = '#FFFFFF';
const TEXT    = '#111827';
const GRAY    = '#6B7280';
const BORDER  = '#E5E7EB';
const BG      = '#F9FAFB';
const BLUE    = '#4F46E5';
const GREEN   = '#10B981';
const ORANGE  = '#F59E0B';
const RED     = '#EF4444';
const PURPLE  = '#8B5CF6';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

// ─── Helpers ─────────────────────────────────────────────────────────────────

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
    <View style={styles.statCard} accessible accessibilityLabel={`${label}: ${value}. ${sub}`}>
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

function AppointmentRow({ entry, showDay }: { entry: ScheduleEntry; showDay?: boolean }) {
  const badge = statusBadge(entry.status);
  return (
    <View style={styles.apptRow}>
      <View style={styles.apptTime}>
        <Text style={styles.apptTimeText}>{showDay ? formatDay(entry.scheduled_at) : formatTime(entry.scheduled_at)}</Text>
        <Text style={styles.apptDuration}>
          {showDay ? formatTime(entry.scheduled_at) : `${entry.duration_minutes} mins`}
        </Text>
      </View>
      <View style={[styles.apptAvatar, { backgroundColor: avatarColor(entry.patient_name) }]}>
        <Text style={styles.apptAvatarText}>{initials(entry.patient_name)}</Text>
      </View>
      <View style={styles.apptInfo}>
        <Text style={styles.apptName}>{entry.patient_name}</Text>
        <Text style={styles.apptType} numberOfLines={1}>
          {TYPE_LABEL[entry.type] ?? entry.type}{entry.reason_for_visit ? ` · ${entry.reason_for_visit}` : ''}
        </Text>
      </View>
      <View style={[styles.badge, { backgroundColor: badge.bg }]}>
        <Text style={[styles.badgeText, { color: badge.color }]}>{badge.label}</Text>
      </View>
      <CallJoinButton
        info={doctorCallJoin(entry)}
        withName={entry.patient_name}
        style={[styles.actionBtn, styles.actionBtnJoin, { gap: 4 }]}
        textStyle={[styles.actionBtnText, { color: BLUE }]}
        iconColor={BLUE}
      />
    </View>
  );
}

// ─── Section Card Wrapper ─────────────────────────────────────────────────────

function Card({ title, action, onAction, children }: {
  title: string; action?: string; onAction?: () => void; children: React.ReactNode;
}) {
  return (
    <View style={styles.card}>
      <View style={styles.cardHeader}>
        <Text style={styles.cardTitle} accessibilityRole="header">{title}</Text>
        {!!action && !!onAction && (
          <TouchableOpacity activeOpacity={0.7} onPress={onAction} accessibilityRole="button" accessibilityLabel={action}>
            <Text style={styles.cardAction}>{action}</Text>
          </TouchableOpacity>
        )}
      </View>
      {children}
    </View>
  );
}

function EmptyLine({ icon, text }: { icon: IoniconsName; text: string }) {
  return (
    <View style={styles.emptyLine}>
      <Ionicons name={icon} size={28} color={BORDER} />
      <Text style={styles.emptyText}>{text}</Text>
    </View>
  );
}

// ─── Main Component ──────────────────────────────────────────────────────────

export function DoctorMainDashboardArea({ onNavigate }: { onNavigate?: (key: string) => void }) {
  const { width } = useWindowDimensions();
  const isLarge = width >= 1024;
  const { user } = useCurrentUser();
  const { dashboard, loading, error, refetch } = useDoctorDashboard();

  const first = dashboard?.first_name || user?.first_name || '';
  const last  = dashboard?.last_name  || user?.last_name  || '';
  const name  = first ? `Dr. ${first} ${last}`.trim() : 'Doctor';
  const today = new Date().toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });

  const todayEntries = dashboard?.today ?? [];
  const nowMs        = Date.now();
  const stillToCome  = todayEntries.filter(e =>
    (e.status === 'SCHEDULED' || e.status === 'RESCHEDULED') && new Date(e.scheduled_at).getTime() >= nowMs,
  ).length;
  const todayActive  = todayEntries.filter(e => e.status !== 'CANCELLED').length;

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>
      {/* Header */}
      <View style={styles.header}>
        <View>
          <Text style={styles.greeting}>{greetingForHour()}, {name}</Text>
          <Text style={styles.greetingSub}>Here's your practice at a glance.</Text>
        </View>
        <View style={styles.dateBtn}>
          <Ionicons name="calendar-outline" size={16} color={GRAY} />
          <Text style={styles.dateText}>{today}</Text>
        </View>
      </View>

      {loading && !dashboard ? (
        <View style={styles.center}><ActivityIndicator size="large" color={BLUE} /></View>
      ) : error && !dashboard ? (
        <View style={styles.center}>
          <Ionicons name="alert-circle-outline" size={44} color={RED} />
          <Text style={styles.errorText}>{error}</Text>
          <TouchableOpacity style={styles.retryBtn} onPress={refetch} activeOpacity={0.8} accessibilityRole="button">
            <Text style={styles.retryBtnText}>Retry</Text>
          </TouchableOpacity>
        </View>
      ) : (
        <>
          {/* Stat Cards */}
          <View style={styles.statsRow}>
            <StatCard icon="calendar-outline"  iconBg="#EEF2FF" iconColor={BLUE}    label="Today's Appointments"   value={String(todayActive)}                              sub={`${stillToCome} still to come`} subColor={BLUE}  />
            <StatCard icon="time-outline"      iconBg="#F5F3FF" iconColor={PURPLE}  label="Upcoming Appointments"  value={String(dashboard?.upcoming.length ?? 0)}          sub="Scheduled ahead"                subColor={GRAY}  />
            <StatCard icon="checkmark-circle-outline" iconBg="#D1FAE5" iconColor={GREEN} label="Completed Consultations" value={String(dashboard?.completed_consultations ?? 0)} sub="All time"                    subColor={GRAY}  />
            <StatCard icon="document-text-outline" iconBg="#FEF3C7" iconColor={ORANGE} label="Open Consultations"  value={String(dashboard?.pending_consultations ?? 0)}   sub="Draft or in progress"           subColor={ORANGE} />
            <StatCard icon="people-outline"    iconBg="#EFF6FF" iconColor="#3B82F6" label="My Patients"            value={dashboard?.my_patients != null ? String(dashboard.my_patients) : '—'} sub="With appointments" subColor={GRAY} />
          </View>

          {/* Two-column body */}
          <View style={[styles.body, isLarge && styles.bodyRow]}>
            <View style={[styles.leftCol, isLarge && styles.leftColWide]}>
              <Card title="Today's Schedule" action="View full schedule" onAction={onNavigate && (() => onNavigate('schedule'))}>
                {todayEntries.length === 0 ? (
                  <EmptyLine icon="calendar-clear-outline" text="No appointments today" />
                ) : todayEntries.map((e, i) => (
                  <View key={e.appointment_id}>
                    {i > 0 && <View style={styles.separator} />}
                    <AppointmentRow entry={e} />
                  </View>
                ))}
              </Card>
            </View>

            <View style={[styles.rightCol, isLarge && styles.rightColNarrow]}>
              <Card title="Coming Up" action="View appointments" onAction={onNavigate && (() => onNavigate('appointments'))}>
                {(dashboard?.upcoming ?? []).length === 0 ? (
                  <EmptyLine icon="time-outline" text="No upcoming appointments" />
                ) : (dashboard?.upcoming ?? []).slice(0, 5).map((e, i) => (
                  <View key={e.appointment_id}>
                    {i > 0 && <View style={styles.separator} />}
                    <AppointmentRow entry={e} showDay />
                  </View>
                ))}
              </Card>
            </View>
          </View>
        </>
      )}
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
    flexDirection: 'row', flexWrap: 'wrap', gap: 12, marginBottom: 24,
  },
  statCard: {
    flex: 1, minWidth: 160,
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

  center:       { alignItems: 'center', justifyContent: 'center', gap: 12, paddingVertical: 64 },
  errorText:    { fontSize: 15, color: GRAY, textAlign: 'center' },
  retryBtn:     { backgroundColor: BLUE, borderRadius: 8, paddingHorizontal: 20, paddingVertical: 10 },
  retryBtnText: { color: WHITE, fontWeight: '600', fontSize: 14 },
  emptyLine:    { alignItems: 'center', gap: 6, paddingVertical: 20 },
  emptyText:    { fontSize: 13, color: GRAY },

  // Appointment row
  apptRow: {
    flexDirection: 'row', alignItems: 'center', gap: 10, flexWrap: 'wrap',
  },
  apptTime:     { width: 74 },
  apptTimeText: { fontSize: 13, fontWeight: '600', color: BLUE },
  apptDuration: { fontSize: 11, color: GRAY, marginTop: 2 },
  apptAvatar:   { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center' },
  apptAvatarText: { color: WHITE, fontSize: 14, fontWeight: '700' },
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
});
