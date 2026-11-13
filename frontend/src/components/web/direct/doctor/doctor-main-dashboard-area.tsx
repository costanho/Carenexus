import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, TouchableOpacity, View, useWindowDimensions } from 'react-native';
import { useUnacknowledgedAlertCount } from '@/hooks/use-ai-alerts';
import { useCurrentUser } from '@/hooks/use-current-user';
import { doctorCallJoin, isActiveAppointment } from '@/hooks/use-doctor-appointment-actions';
import { CallJoinButton } from '@/components/web/call/call-join-button';
import { greetingForHour, useDoctorDashboard } from '@/hooks/use-doctor-dashboard';
import type { ScheduleEntry } from '@/hooks/use-doctor-schedule';
import { doctorDisplayName } from '@/hooks/use-doctors';
import { useSectionNav } from '@/hooks/use-section-nav';
import type { SectionParamsInput, SectionProps } from '@/lib/nav/sections';
import { clinicToday, formatClinicTime, isAfterClinicNow, toClinicLocal } from '@/lib/time/clinic';
import { ariaLevel, decorative, Icon, type IoniconsName } from '@/components/web/ui';

const WHITE   = '#FFFFFF';
const TEXT    = '#111827';
const GRAY    = '#6B7280';
const BORDER  = '#E5E7EB';
const BG      = '#F9FAFB';
const BLUE    = '#4F46E5';
const GREEN   = '#047857';
const ORANGE  = '#B45309';
const RED     = '#B91C1C';
const PURPLE  = '#6D28D9';

// ─── Helpers ─────────────────────────────────────────────────────────────────

// Appointment times are clinic wall-clock values: they are shown as they are, never converted to the device zone.

/** "10:15 CAT". */
function formatTime(local: string) {
  return formatClinicTime(local, local);
}

/** A clinic date "YYYY-MM-DD" in words, without shifting it through the device time zone. */
function formatDate(date: string, options: Intl.DateTimeFormatOptions) {
  const [y, m, d] = date.split('-').map(Number);
  const at = new Date(Date.UTC(y, m - 1, d));
  return Number.isNaN(at.getTime()) ? date : new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', ...options }).format(at);
}

/** "Fri 20 Nov". */
function formatDay(local: string) {
  const day = (toClinicLocal(local) ?? '').slice(0, 10);
  return day ? formatDate(day, { weekday: 'short', day: 'numeric', month: 'short' }) : local;
}

function initials(name: string) {
  return name.split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
}

const AVATAR_COLORS = ['#4F46E5', '#0F766E', '#B45309', '#B91C1C', '#6D28D9', '#047857'];
function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}

function statusBadge(entry: ScheduleEntry) {
  // Ended without an outcome (decided by the server): say so instead of "Scheduled".
  if (entry.needs_outcome) return { label: 'Awaiting outcome', color: ORANGE, bg: '#FEF3C7' };
  switch (entry.status) {
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
  /** Makes the card a button that opens the list behind the number. */
  onPress?: () => void;
};

function StatCard({ icon, iconBg, iconColor, label, value, sub, subColor, onPress }: StatCardProps) {
  const content = (
    <>
      <View style={[styles.statIconBg, { backgroundColor: iconBg }]} {...decorative}>
        <Icon name={icon} size={22} color={iconColor} />
      </View>
      <Text style={styles.statLabel}>{label}</Text>
      <Text style={styles.statValue}>{value}</Text>
      <Text style={[styles.statSub, { color: subColor }]}>{sub}</Text>
    </>
  );
  if (onPress) {
    return (
      <Pressable
        style={({ pressed }) => [styles.statCard, pressed && styles.statCardPressed]}
        onPress={onPress}
        accessibilityRole="button"
        accessibilityLabel={`${label}: ${value}. ${sub}`}
      >
        {content}
      </Pressable>
    );
  }
  return (
    <View style={styles.statCard} accessible accessibilityLabel={`${label}: ${value}. ${sub}`}>
      {content}
    </View>
  );
}

// ─── Schedule Row ─────────────────────────────────────────────────────────────

function AppointmentRow({ entry, showDay }: { entry: ScheduleEntry; showDay?: boolean }) {
  const badge = statusBadge(entry);
  return (
    <View style={styles.apptRow}>
      <View style={styles.apptTime}>
        <Text style={styles.apptTimeText}>{showDay ? formatDay(entry.scheduled_at) : formatTime(entry.scheduled_at)}</Text>
        <Text style={styles.apptDuration}>
          {showDay ? formatTime(entry.scheduled_at) : `${entry.duration_minutes} mins`}
        </Text>
      </View>
      {/* The initials repeat the name beside them, so they are hidden from assistive technology. */}
      <View style={[styles.apptAvatar, { backgroundColor: avatarColor(entry.patient_name) }]} {...decorative}>
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
        <Text style={styles.cardTitle} accessibilityRole="header" {...ariaLevel(2)}>{title}</Text>
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
      <Icon name={icon} size={28} color={BORDER} />
      <Text style={styles.emptyText}>{text}</Text>
    </View>
  );
}

// ─── Main Component ──────────────────────────────────────────────────────────

/**
 * The doctor's home. Rendered with `SectionProps` (`navigate` opens a section with params); the older
 * `onNavigate(key)` prop keeps working for plain section changes.
 */
export function DoctorMainDashboardArea(props: Partial<SectionProps> & { onNavigate?: (key: string) => void } = {}) {
  const { onNavigate } = props;
  const nav = useSectionNav();
  const open = (section: string, params?: SectionParamsInput) => {
    if (props.navigate) props.navigate(section, params);
    else if (onNavigate && !params) onNavigate(section);
    else nav.navigate(section, params);
  };
  const { width } = useWindowDimensions();
  const isLarge = width >= 1024;
  // Phones: smaller greeting, no date pill, tighter padding (no horizontal overflow at 375 px).
  const isNarrow = width < 640;
  const { user } = useCurrentUser();
  const { dashboard, loading, error, refetch } = useDoctorDashboard();

  const first = dashboard?.first_name || user?.first_name || '';
  const last  = dashboard?.last_name  || user?.last_name  || '';
  const name  = first ? doctorDisplayName(first, last) : 'Doctor';
  // "Today" is the clinic's day, and "still to come" is measured against the clinic's clock, not the device's.
  const today = formatDate(clinicToday(), { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });

  const todayEntries = dashboard?.today ?? [];
  const stillToCome  = todayEntries.filter(e => isActiveAppointment(e) && isAfterClinicNow(e.scheduled_at)).length;
  const todayActive  = todayEntries.filter(e => e.status !== 'CANCELLED').length;
  const upcomingCount = dashboard?.upcoming_appointments ?? 0;
  const needsOutcome  = dashboard?.needs_outcome_count ?? 0;
  // Polled every 30 s by a shared store (the sidebar badge reads the same one). Until it has answered once the
  // card shows a dash rather than a 0 that could be read as "no alerts".
  const alerts = useUnacknowledgedAlertCount(true);
  const alertsKnown = alerts.loaded;
  const alertsSub = !alertsKnown
    ? (alerts.error ? 'Could not be loaded' : 'Loading…')
    : alerts.count > 0 ? 'Review and acknowledge' : 'Nothing to review';

  return (
    <ScrollView
      style={styles.scroll}
      contentContainerStyle={[styles.container, isNarrow && styles.containerNarrow]}
      showsVerticalScrollIndicator={false}
    >
      {/* Header */}
      <View style={styles.header}>
        <View style={styles.greetingBlock}>
          {/* The greeting is the page's main heading; the cards below are level-2 sections. */}
          <Text style={[styles.greeting, isNarrow && styles.greetingNarrow]} accessibilityRole="header" {...ariaLevel(1)}>
            {greetingForHour()}, {name}
          </Text>
          <Text style={styles.greetingSub}>Here's your practice at a glance.</Text>
        </View>
        {!isNarrow && (
          <View style={styles.dateBtn}>
            <Icon name="calendar-outline" size={16} color={GRAY} />
            <Text style={styles.dateText}>{today}</Text>
          </View>
        )}
      </View>

      {loading && !dashboard ? (
        <View style={styles.center} role="status" aria-live="polite" accessibilityLabel="Loading dashboard">
          <View {...decorative}><ActivityIndicator size="large" color={BLUE} /></View>
        </View>
      ) : error && !dashboard ? (
        <View style={styles.center} accessibilityRole="alert">
          <Icon name="alert-circle-outline" size={44} color={RED} />
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
            <StatCard icon="time-outline"      iconBg="#F5F3FF" iconColor={PURPLE}  label="Upcoming Appointments"  value={String(upcomingCount)}                            sub="View upcoming"                  subColor={BLUE}  onPress={() => open('appointments', { tab: 'upcoming' })} />
            <StatCard icon="hourglass-outline" iconBg="#FEF3C7" iconColor={ORANGE}  label="Needs Outcome"          value={String(needsOutcome)}                             sub={needsOutcome > 0 ? 'Review ended appointments' : 'Nothing waiting'} subColor={needsOutcome > 0 ? ORANGE : GRAY} onPress={() => open('appointments', { tab: 'needs-outcome' })} />
            <StatCard
              icon="warning-outline" iconBg="#FEE2E2" iconColor={RED}
              label="Unacknowledged Alerts"
              value={alertsKnown ? String(alerts.count) : '—'}
              sub={alertsSub}
              subColor={alertsKnown && alerts.count > 0 ? RED : GRAY}
              onPress={nav.hasSection('ai-alerts') ? () => open('ai-alerts') : undefined}
            />
            <StatCard icon="checkmark-circle-outline" iconBg="#D1FAE5" iconColor={GREEN} label="Completed Consultations" value={String(dashboard?.completed_consultations ?? 0)} sub="All time"                    subColor={GRAY}  />
            <StatCard icon="document-text-outline" iconBg="#FEF3C7" iconColor={ORANGE} label="Open Consultations"  value={String(dashboard?.pending_consultations ?? 0)}   sub="Draft or in progress"           subColor={ORANGE} />
            <StatCard icon="people-outline"    iconBg="#EFF6FF" iconColor="#1D4ED8" label="My Patients"            value={dashboard?.my_patients != null ? String(dashboard.my_patients) : '—'} sub="In your care" subColor={GRAY} />
          </View>

          {/* Two-column body */}
          <View style={[styles.body, isLarge && styles.bodyRow]}>
            <View style={[styles.leftCol, isLarge && styles.leftColWide]}>
              <Card title="Today's Schedule" action="View full schedule" onAction={() => open('schedule')}>
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
              <Card title="Coming Up" action="View appointments" onAction={() => open('appointments', { tab: 'upcoming' })}>
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
  containerNarrow: { padding: 16, paddingBottom: 32 },

  // Header
  header: {
    flexDirection: 'row', justifyContent: 'space-between',
    alignItems: 'flex-start', marginBottom: 24, flexWrap: 'wrap', gap: 12,
  },
  greetingBlock: { flexShrink: 1, minWidth: 0 },
  greeting:    { fontSize: 24, fontWeight: '700', color: TEXT, marginBottom: 4 },
  greetingNarrow: { fontSize: 20 },
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
    flex: 1, minWidth: 150,
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
  statCardPressed: { opacity: 0.85 },

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
    alignItems: 'center', marginBottom: 16, flexWrap: 'wrap', gap: 8,
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
