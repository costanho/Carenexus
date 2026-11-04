import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View, useWindowDimensions } from 'react-native';
import {
  useCaregiverActivity,
  useCaregiverDependents,
  type CaregiverAppointment,
  type CaregiverConsultation,
  type CaregiverDependent,
} from '@/hooks/use-caregiver-dependents';
import { CallJoinButton } from '@/components/web/call/call-join-button';
import { describeCallJoin } from '@/lib/calls/join';
import { EmptyState, ErrorState, LoadingState } from './proxy-states';

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

function Card({ title, action, onAction, children, style }: {
  title: string; action?: string; onAction?: () => void; children: React.ReactNode; style?: object;
}) {
  return (
    <View style={[styles.card, style]}>
      <View style={styles.cardHeader}>
        <Text style={styles.cardTitle}>{title}</Text>
        {action && (
          <TouchableOpacity activeOpacity={0.7} onPress={onAction} accessibilityRole="button">
            <Text style={styles.cardAction}>{action}</Text>
          </TouchableOpacity>
        )}
      </View>
      {children}
    </View>
  );
}


// ─── Schedule Row ─────────────────────────────────────────────────────────────

function ScheduleRow({ appt, person, canJoinCalls }: { appt: CaregiverAppointment; person: string; canJoinCalls: boolean }) {
  const time    = new Date(appt.scheduledAt).toLocaleTimeString('en-ZW', { hour: '2-digit', minute: '2-digit' });
  const join    = canJoinCalls ? describeCallJoin(appt, false) : null;
  return (
    <View style={styles.scheduleRow}>
      <View style={[styles.scheduleAvatar, { backgroundColor: avatarColor(person), alignItems: 'center', justifyContent: 'center' }]}>
        <Text style={{ color: WHITE, fontSize: 14, fontWeight: '700' }}>{initials(person)}</Text>
      </View>
      <View style={[styles.scheduleTimePill, { borderColor: BORDER }]}>
        <Text style={[styles.scheduleTime, { color: TEXT }]}>{time}</Text>
      </View>
      <View style={{ flex: 1 }}>
        <Text style={styles.scheduleTitle}>{appt.reasonForVisit ?? 'Appointment'}</Text>
        <Text style={styles.schedulePerson}>{person}</Text>
      </View>
      <CallJoinButton
        info={join}
        withName={person}
        style={styles.videoBtn}
        textStyle={styles.videoBtnText}
        iconSize={16}
        iconColor={GREEN}
      />
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
        <Ionicons name={icon} size={15} color={iconColor} importantForAccessibility="no" />
      </View>
      <View style={{ flex: 1 }}>
        <Text style={styles.updateTitle}>{title}</Text>
        {sub ? <Text style={styles.updateSub} numberOfLines={1}>{sub}</Text> : null}
      </View>
      <Text style={styles.updateTime}>{time}</Text>
    </View>
  );
}

function formatShortDate(iso: string | null) {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '';
  return d.toLocaleDateString('en-ZW', { day: 'numeric', month: 'short' });
}

function isSameDay(a: Date, b: Date) {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

const AVATAR_COLORS = ['#4F46E5', '#0D9488', '#F59E0B', '#EF4444', '#8B5CF6', '#10B981', '#0EA5E9'];
function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < (name?.length ?? 0); i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}
function initials(name: string) {
  return (name ?? '?').split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
}
function healthDotColor(s: string | null) {
  if (s === 'STABLE')   return GREEN;
  if (s === 'MONITOR')  return ORANGE;
  if (s === 'CRITICAL') return RED;
  return '#9CA3AF';
}
function calcAge(dob: string | null) {
  if (!dob) return '';
  const diff = Date.now() - new Date(dob).getTime();
  return `${Math.floor(diff / (365.25 * 24 * 60 * 60 * 1000))} yrs`;
}
function accessLabel(level: string) {
  if (level === 'FULL_ACCESS') return 'Full Access';
  if (level === 'VIEW_ONLY')   return 'View Only';
  if (level === 'EDIT_ONLY')   return 'Edit Only';
  return 'Custom';
}

// ─── Live Loved One Row ───────────────────────────────────────────────────────

function LiveLovedOneRow({ dep, onSelect }: { dep: CaregiverDependent; onSelect?: (dep: CaregiverDependent) => void }) {
  const name     = dep.patientName ?? 'Unknown';
  const dotColor = healthDotColor(dep.patientHealthStatus);
  const access   = accessLabel(dep.accessLevel);
  const isFullAccess = dep.accessLevel === 'FULL_ACCESS';
  const age      = calcAge(dep.patientDateOfBirth);
  const gender   = dep.patientGender
    ? dep.patientGender.charAt(0) + dep.patientGender.slice(1).toLowerCase()
    : '';

  return (
    <TouchableOpacity
      style={styles.lovedOneRow}
      activeOpacity={0.8}
      onPress={() => onSelect?.(dep)}
      accessibilityRole="button"
      accessibilityLabel={`Open profile for ${name}`}
    >
      <View style={styles.lovedOneLeft}>
        <View style={styles.lovedOneAvatarWrapper}>
          <View style={[styles.lovedOneAvatar, { backgroundColor: avatarColor(name), alignItems: 'center', justifyContent: 'center' }]}>
            <Text style={{ color: '#fff', fontSize: 16, fontWeight: '700' }}>{initials(name)}</Text>
          </View>
          <View style={[styles.lovedOneDot, { backgroundColor: dotColor }]} />
        </View>
        <View>
          <Text style={styles.lovedOneName}>{name}</Text>
          <View style={[styles.accessBadge, { backgroundColor: isFullAccess ? '#D1FAE5' : '#FEF3C7' }]}>
            <Text style={[styles.accessText, { color: isFullAccess ? GREEN : ORANGE }]}>{access}</Text>
          </View>
          {(age || gender) && <Text style={styles.lovedOneMeta}>{[age, gender].filter(Boolean).join(' • ')}</Text>}
        </View>
      </View>
      <View style={styles.lovedOneStats}>
        <View style={styles.lovedOneStat}>
          <Text style={styles.lovedOneStatLabel}>Health Status</Text>
          <View style={styles.lovedOneStatVal}>
            <Ionicons name="heart-outline" size={14} color={dotColor} />
            <Text style={[styles.lovedOneStatText, { color: dotColor }]}>
              {dep.patientHealthStatus
                ? dep.patientHealthStatus.charAt(0) + dep.patientHealthStatus.slice(1).toLowerCase()
                : 'Unknown'}
            </Text>
          </View>
        </View>
        <View style={styles.statDivider} />
        <View style={styles.lovedOneStat}>
          <Text style={styles.lovedOneStatLabel}>Blood Type</Text>
          <View style={styles.lovedOneStatVal}>
            <Ionicons name="water-outline" size={14} color={GRAY} />
            <Text style={styles.lovedOneStatText}>{dep.patientBloodType ?? '—'}</Text>
          </View>
        </View>
        <View style={styles.statDivider} />
        <View style={styles.lovedOneStat}>
          <Text style={styles.lovedOneStatLabel}>Relationship</Text>
          <View style={styles.lovedOneStatVal}>
            <Ionicons name="people-outline" size={14} color={GRAY} />
            <Text style={styles.lovedOneStatText}>
              {dep.relationship
                ? dep.relationship.charAt(0).toUpperCase() + dep.relationship.slice(1)
                : '—'}
            </Text>
          </View>
        </View>
      </View>
      <Ionicons name="chevron-forward" size={18} color={GRAY} style={{ marginLeft: 8 }} />
    </TouchableOpacity>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────

const SHOW_LIMIT = 3;
const UPDATES_LIMIT = 4;

export function PatientProxyMainDashboard({ onNavigate, onSelectPatient }: { onNavigate?: (key: string) => void; onSelectPatient?: (dep: CaregiverDependent) => void }) {
  const { width } = useWindowDimensions();
  const isLarge = width >= 1024;
  const isMedium = width >= 768;
  const { dependents, loading: lovedLoading, error: lovedError, refetch: refetchLoved } = useCaregiverDependents();
  const { appointments, consultations, loading: actLoading, error: actError, refetch: refetchActivity } = useCaregiverActivity();
  const [lovedExpanded, setLovedExpanded] = useState(false);

  const nameFor = (patientSystemId: string | null, fallback: string | null) =>
    dependents.find(d => d.patientSystemId === patientSystemId)?.patientName ?? fallback ?? 'Your loved one';

  const { upcoming, todays, nextSub } = useMemo(() => {
    const now   = new Date();
    const sched = appointments
      .filter(a => a.status === 'SCHEDULED')
      .sort((a, b) => new Date(a.scheduledAt).getTime() - new Date(b.scheduledAt).getTime());
    const future = sched.filter(a => new Date(a.scheduledAt).getTime() > now.getTime());
    const today  = sched.filter(a => isSameDay(new Date(a.scheduledAt), now));
    let sub = 'No upcoming';
    if (future.length > 0) {
      const next = new Date(future[0].scheduledAt);
      sub = `Next: ${next.toLocaleDateString('en-ZW', { weekday: 'short', day: 'numeric', month: 'short' })} ${next.toLocaleTimeString('en-ZW', { hour: '2-digit', minute: '2-digit' })}`;
    }
    return { upcoming: future.length, todays: today, nextSub: sub };
  }, [appointments]);

  const recentConsultations = useMemo(
    () => [...consultations]
      .sort((a, b) => new Date(b.completedAt ?? b.startedAt ?? b.createdAt ?? 0).getTime()
                    - new Date(a.completedAt ?? a.startedAt ?? a.createdAt ?? 0).getTime())
      .slice(0, UPDATES_LIMIT),
    [consultations],
  );

  const consultTitle = (c: CaregiverConsultation) =>
    c.status === 'COMPLETED' ? 'Consultation completed' : 'Consultation recorded';

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>

      {/* Stat Cards */}
      <View style={styles.statsRow}>
        <StatCard
          icon="calendar-outline" iconBg="#EDE9FE" iconColor={PURPLE}
          label="Upcoming Appointments"
          value={actLoading ? '…' : actError ? '—' : String(upcoming)}
          sub={actLoading ? '' : actError ? 'Unavailable' : nextSub}
        />
        <StatCard
          icon="people-outline" iconBg="#D1FAE5" iconColor={GREEN}
          label="Loved Ones"
          value={lovedLoading ? '…' : lovedError ? '—' : String(dependents.length)}
          sub={lovedLoading ? '' : lovedError ? 'Unavailable' : 'Linked to your account'}
        />
      </View>

      {/* Top row: Loved One Overview + Today's Schedule */}
      <View style={[styles.row, isLarge && styles.rowHorizontal]}>
        {/* Loved One Overview — live data */}
        <Card title="Loved One Overview" style={[styles.colWide, !isLarge && styles.colFull]}>
          {lovedLoading ? (
            <LoadingState compact label="Loading loved ones" />
          ) : lovedError ? (
            <ErrorState compact message={lovedError} onRetry={refetchLoved} />
          ) : dependents.length === 0 ? (
            <Text style={{ fontSize: 13, color: GRAY, padding: 8 }}>No linked patients yet.</Text>
          ) : (
            <>
              {(lovedExpanded ? dependents : dependents.slice(0, SHOW_LIMIT)).map((dep, i) => (
                <View key={dep.accessId}>
                  {i > 0 && <View style={styles.sep} />}
                  <LiveLovedOneRow dep={dep} onSelect={onSelectPatient} />
                </View>
              ))}
              {dependents.length > SHOW_LIMIT && (
                <TouchableOpacity
                  style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 5, paddingTop: 12 }}
                  activeOpacity={0.7}
                  onPress={() => setLovedExpanded(e => !e)}
                  accessibilityRole="button"
                  accessibilityState={{ expanded: lovedExpanded }}
                >
                  <Ionicons name={lovedExpanded ? 'chevron-up' : 'chevron-down'} size={14} color={PURPLE} importantForAccessibility="no" />
                  <Text style={styles.viewAllText}>
                    {lovedExpanded ? 'Show less' : `+${dependents.length - SHOW_LIMIT} more`}
                  </Text>
                </TouchableOpacity>
              )}
            </>
          )}
          <TouchableOpacity style={styles.viewAllBtn} activeOpacity={0.7} onPress={() => onNavigate?.('health-summary')} accessibilityRole="button">
            <Text style={styles.viewAllText}>View health summary →</Text>
          </TouchableOpacity>
        </Card>

        {/* Today's Schedule — from caregiver activity */}
        <Card title="Today's Schedule" action="View all" onAction={() => onNavigate?.('appointments')} style={[styles.colNarrow, !isLarge && styles.colFull]}>
          {actLoading ? (
            <LoadingState compact label="Loading schedule" />
          ) : actError ? (
            <ErrorState compact message={actError} onRetry={refetchActivity} />
          ) : todays.length === 0 ? (
            <EmptyState compact icon="calendar-outline" title="Nothing scheduled today" />
          ) : (
            todays.map((a, i) => (
              <View key={a.appointmentId}>
                {i > 0 && <View style={styles.sep} />}
                <ScheduleRow
                  appt={a}
                  person={nameFor(a.patientSystemId, a.patientName)}
                  canJoinCalls={dependents.some(d => d.patientSystemId === a.patientSystemId && d.joinCallsAllowed)}
                />
              </View>
            ))
          )}
          <TouchableOpacity style={styles.viewAllBtn} activeOpacity={0.7} onPress={() => onNavigate?.('appointments')} accessibilityRole="button">
            <Text style={styles.viewAllText}>View all appointments →</Text>
          </TouchableOpacity>
        </Card>
      </View>

      {/* Bottom row: Recent Updates (consultations) + quick links */}
      <View style={[styles.row, isMedium && styles.rowHorizontal]}>
        <Card title="Recent Consultations" style={[styles.colWide, !isMedium && styles.colFull]}>
          {actLoading ? (
            <LoadingState compact label="Loading recent consultations" />
          ) : actError ? (
            <ErrorState compact message={actError} onRetry={refetchActivity} />
          ) : recentConsultations.length === 0 ? (
            <EmptyState compact icon="document-text-outline" title="No recent consultations" />
          ) : (
            recentConsultations.map((c, i) => (
              <View key={c.consultationId}>
                {i > 0 && <View style={styles.sep} />}
                <UpdateRow
                  icon="document-text-outline" iconBg="#EDE9FE" iconColor={PURPLE}
                  title={consultTitle(c)}
                  sub={[nameFor(c.patientSystemId, c.patientName), c.chiefComplaint].filter(Boolean).join(' • ')}
                  time={formatShortDate(c.completedAt ?? c.startedAt ?? c.createdAt)}
                />
              </View>
            ))
          )}
        </Card>

        <Card title="Records" style={[styles.colNarrow, !isMedium && styles.colFull]}>
          {[
            { key: 'medications',       label: 'Medications',         icon: 'medical-outline' as IoniconsName },
            { key: 'documents-results', label: 'Documents & Results', icon: 'flask-outline' as IoniconsName },
            { key: 'health-summary',    label: 'Health Summary',      icon: 'heart-outline' as IoniconsName },
          ].map((l, i) => (
            <View key={l.key}>
              {i > 0 && <View style={styles.sep} />}
              <TouchableOpacity style={styles.linkRow} activeOpacity={0.7} onPress={() => onNavigate?.(l.key)} accessibilityRole="button">
                <View style={[styles.updateIcon, { backgroundColor: PURPLE_BG }]}>
                  <Ionicons name={l.icon} size={15} color={PURPLE} importantForAccessibility="no" />
                </View>
                <Text style={[styles.updateTitle, { flex: 1 }]}>{l.label}</Text>
                <Ionicons name="chevron-forward" size={16} color={GRAY} importantForAccessibility="no" />
              </TouchableOpacity>
            </View>
          ))}
        </Card>
      </View>

      {/* Proxy Access Banner */}
      {dependents.length > 0 && (
      <View style={styles.banner}>
        <View style={styles.bannerIconWrapper}>
          <Ionicons name="shield-outline" size={28} color={PURPLE} importantForAccessibility="no" />
        </View>
        <View style={{ flex: 1 }}>
          <Text style={styles.bannerTitle}>
            {dependents.length === 1
              ? `You are a ${accessLabel(dependents[0].accessLevel)} proxy for ${dependents[0].patientName ?? 'your loved one'}`
              : `You are a proxy for ${dependents.length} loved ones`}
          </Text>
          <Text style={styles.bannerSub}>
            Depending on the permissions each loved one has granted, you can view their appointments, prescriptions, lab results and health profile.
          </Text>
        </View>
        <TouchableOpacity style={styles.manageBtn} activeOpacity={0.8} onPress={() => onNavigate?.('profile-settings')} accessibilityRole="button">
          <Ionicons name="people-outline" size={15} color={PURPLE} importantForAccessibility="no" />
          <Text style={styles.manageBtnText}>View Access</Text>
        </TouchableOpacity>
      </View>
      )}

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
  videoBtn: {
    flexDirection: 'row', gap: 4, minHeight: 36, borderRadius: 8, borderWidth: 1, paddingHorizontal: 10,
    borderColor: GREEN + '50', backgroundColor: GREEN + '15',
    alignItems: 'center', justifyContent: 'center', flexShrink: 0,
  },
  videoBtnText: { fontSize: 12, fontWeight: '600', color: GREEN },

  // Updates & Meds
  updateRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 10 },
  linkRow:   { flexDirection: 'row', alignItems: 'center', gap: 10 },
  updateIcon:{ width: 32, height: 32, borderRadius: 16, alignItems: 'center', justifyContent: 'center', marginTop: 1 },
  updateTitle:{ fontSize: 13, fontWeight: '600', color: TEXT },
  updateSub:  { fontSize: 11, color: GRAY, marginTop: 1 },
  updateTime: { fontSize: 11, color: GRAY },

  // Care team

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
