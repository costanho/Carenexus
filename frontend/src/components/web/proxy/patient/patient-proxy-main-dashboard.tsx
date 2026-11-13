import { useMemo, useState } from 'react';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View, useWindowDimensions } from 'react-native';
import { ariaLevel, decorative } from '@/components/web/ui/a11y';
import { Icon, type IoniconsName } from '@/components/web/ui/icon';
import {
  useCaregiverActivity,
  type CaregiverAppointment,
  type CaregiverConsultation,
  type CaregiverDependent,
} from '@/hooks/use-caregiver-dependents';
import { useCareTeamConversation } from '@/hooks/use-caregiver-messaging';
import { useSectionNav } from '@/hooks/use-section-nav';
import { CallJoinButton } from '@/components/web/call/call-join-button';
import { useDependents } from '@/components/web/sections/proxy-dependents';
import { describeCallJoin } from '@/lib/calls/join';
import { effectivePermission } from '@/lib/caregiver/permissions';
import type { SectionParamsInput } from '@/lib/nav/sections';
import { formatClinicDateTime, formatClinicTime, isAfterClinicNow, isClinicToday, toClinicLocal } from '@/lib/time/clinic';
import { formatDateOnly } from '@/lib/ui/date-only';
import { DependentHighlights } from './dependent-records';
import { MessageCareTeamButton } from './message-care-team-button';
import { EmptyState, ErrorState, LoadingState } from './proxy-states';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const PURPLE = '#7C3AED';
const PURPLE_LIGHT = '#EDE9FE';
const PURPLE_BG    = '#F5F3FF';
const GREEN  = '#047857';
const ORANGE = '#B45309';
const RED    = '#B91C1C';

// Off-screen but read by screen readers (not display: none or opacity 0, which would hide it from them too).
const VISUALLY_HIDDEN = {
  position: 'absolute', width: 1, height: 1, margin: -1, padding: 0, overflow: 'hidden', borderWidth: 0,
  whiteSpace: 'nowrap',
} as object;

// ─── Stat Card ────────────────────────────────────────────────────────────────

function StatCard({ icon, iconBg, iconColor, label, value, sub, subColor = GRAY }: {
  icon: IoniconsName; iconBg: string; iconColor: string;
  label: string; value: string; sub: string; subColor?: string;
}) {
  return (
    <View style={styles.statCard}>
      <View style={[styles.statIconBg, { backgroundColor: iconBg }]} {...decorative}>
        <Icon name={icon} size={22} color={iconColor} />
      </View>
      <Text style={styles.statLabel}>{label}</Text>
      <Text style={styles.statValue}>{value}</Text>
      <Text style={[styles.statSub, { color: subColor }]}>{sub}</Text>
    </View>
  );
}

// ─── Section Card ─────────────────────────────────────────────────────────────

/**
 * A titled card of the home page. The title is a level-2 heading under the page's (visually hidden) level-1
 * heading. `actionLabel` is the action's accessible name when the visible word alone is ambiguous; it must
 * start with the visible text.
 */
function Card({ title, action, actionLabel, onAction, children, style }: {
  title: string; action?: string; actionLabel?: string; onAction?: () => void; children: React.ReactNode; style?: object;
}) {
  return (
    <View style={[styles.card, style]}>
      <View style={styles.cardHeader}>
        <Text style={styles.cardTitle} accessibilityRole="header" {...ariaLevel(2)}>{title}</Text>
        {action && (
          <TouchableOpacity activeOpacity={0.7} onPress={onAction} accessibilityRole="button" accessibilityLabel={actionLabel}>
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
  // Appointment times are clinic wall-clock values: shown as they are, never shifted into the device's zone.
  const time    = formatClinicTime(appt.scheduledAt, 'Time not set');
  const join    = canJoinCalls ? describeCallJoin(appt, false) : null;
  return (
    <View style={styles.scheduleRow}>
      <View style={[styles.scheduleAvatar, { backgroundColor: avatarColor(person), alignItems: 'center', justifyContent: 'center' }]} {...decorative}>
        <Text style={{ color: WHITE, fontSize: 14, fontWeight: '700' }}>{initials(person)}</Text>
      </View>
      <View style={[styles.scheduleTimePill, { borderColor: BORDER }]}>
        <Text style={[styles.scheduleTime, { color: TEXT }]}>{time}</Text>
      </View>
      <View style={styles.scheduleText}>
        <Text style={styles.scheduleTitle}>{appt.reasonForVisit ?? 'Appointment'}</Text>
        <Text style={styles.schedulePerson}>
          {appt.status === 'RESCHEDULED' ? `${person} • Rescheduled` : person}
        </Text>
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

function UpdateRow({ icon, iconBg, iconColor, title, sub, time, onPress, openLabel }: {
  icon: IoniconsName; iconBg: string; iconColor: string; title: string; sub: string; time: string;
  /** Makes the row a button that opens the item. */
  onPress?: () => void;
  /** What the button opens, for assistive technology ("Open consultation details"). */
  openLabel?: string;
}) {
  const content = (
    <>
      <View style={[styles.updateIcon, { backgroundColor: iconBg }]} {...decorative}>
        <Icon name={icon} size={15} color={iconColor} />
      </View>
      <View style={{ flex: 1, minWidth: 0 }}>
        <Text style={styles.updateTitle}>{title}</Text>
        {sub ? <Text style={styles.updateSub} numberOfLines={1}>{sub}</Text> : null}
      </View>
      <Text style={styles.updateTime}>{time}</Text>
    </>
  );
  if (!onPress) return <View style={styles.updateRow}>{content}</View>;
  return (
    <TouchableOpacity
      style={styles.updateRow}
      activeOpacity={0.7}
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={[title, sub, time, openLabel].filter(Boolean).join(', ')}
    >
      {content}
      <Icon name="chevron-forward" size={16} color={GRAY} />
    </TouchableOpacity>
  );
}

/** "10 Nov" for a clinic date-time; empty when missing. */
function formatShortDate(value: string | null) {
  return formatDateOnly(toClinicLocal(value), { day: 'numeric', month: 'short' }, '');
}

/** Appointments that are still going to happen: a rescheduled appointment is as upcoming as a scheduled one. */
const UPCOMING_STATUSES = new Set(['SCHEDULED', 'RESCHEDULED']);

/** Sort key of a clinic date-time (ISO wall-clock strings order as text). */
function clinicKey(value: string | null | undefined): string {
  return toClinicLocal(value) ?? '';
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

const AVATAR_COLORS = ['#4F46E5', '#0F766E', '#B45309', '#B91C1C', '#6D28D9', '#047857', '#0369A1'];
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
  return '#6B7280';
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

function LiveLovedOneRow({ dep, onSelect, compact, message }: {
  dep: CaregiverDependent;
  onSelect?: (dep: CaregiverDependent) => void;
  /** Narrow layout (phones): the stats wrap under the name and the dividers/chevron are hidden. */
  compact?: boolean;
  /** "Message Care Team" for this row; omitted when the dashboard cannot open Messages. */
  message?: { onPress: (dep: CaregiverDependent) => void; busy: boolean; error: string | null };
}) {
  const name     = dep.patientName ?? 'Unknown';
  const dotColor = healthDotColor(dep.patientHealthStatus);
  const access   = accessLabel(dep.accessLevel);
  const isFullAccess = dep.accessLevel === 'FULL_ACCESS';
  const age      = calcAge(dep.patientDateOfBirth);
  const gender   = dep.patientGender
    ? dep.patientGender.charAt(0) + dep.patientGender.slice(1).toLowerCase()
    : '';
  const healthWord = dep.patientHealthStatus
    ? dep.patientHealthStatus.charAt(0) + dep.patientHealthStatus.slice(1).toLowerCase()
    : 'Unknown';
  const relationship = dep.relationship
    ? dep.relationship.charAt(0).toUpperCase() + dep.relationship.slice(1)
    : '';
  const ageGender = [age, gender].filter(Boolean).join(', ');

  // The profile link and "Message Care Team" are sibling buttons (a button inside a button is not operable).
  // The name repeats every fact the card shows in visible reading order (the loved one's name, access level,
  // age and gender, then the three stats with their visible labels) and puts the action, "open profile",
  // last, so the visible text stays a contiguous part of the accessible name (WCAG 2.5.3 label in name).
  // A stat with no value ("—" on screen) is announced as "not recorded".
  const rowLabel = [
    name,
    access,
    ageGender,
    `health status ${healthWord}`,
    `blood type ${dep.patientBloodType ?? 'not recorded'}`,
    `relationship ${relationship || 'not recorded'}`,
    'open profile',
  ].filter(Boolean).join(', ');
  return (
    <View style={styles.lovedOneCard}>
      <TouchableOpacity
        style={styles.lovedOneRow}
        activeOpacity={0.8}
        onPress={() => onSelect?.(dep)}
        accessibilityRole="button"
        accessibilityLabel={rowLabel}
      >
        <View style={styles.lovedOneLeft}>
          <View style={styles.lovedOneAvatarWrapper} {...decorative}>
            <View style={[styles.lovedOneAvatar, { backgroundColor: avatarColor(name), alignItems: 'center', justifyContent: 'center' }]}>
              <Text style={{ color: '#fff', fontSize: 16, fontWeight: '700' }}>{initials(name)}</Text>
            </View>
            <View style={[styles.lovedOneDot, { backgroundColor: dotColor }]} />
          </View>
          <View style={{ minWidth: 0 }}>
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
              <Icon name="heart-outline" size={14} color={dotColor} />
              <Text style={[styles.lovedOneStatText, { color: dotColor }]}>{healthWord}</Text>
            </View>
          </View>
          {!compact && <View style={styles.statDivider} />}
          <View style={styles.lovedOneStat}>
            <Text style={styles.lovedOneStatLabel}>Blood Type</Text>
            <View style={styles.lovedOneStatVal}>
              <Icon name="water-outline" size={14} color={GRAY} />
              <Text style={styles.lovedOneStatText}>{dep.patientBloodType ?? '—'}</Text>
            </View>
          </View>
          {!compact && <View style={styles.statDivider} />}
          <View style={styles.lovedOneStat}>
            <Text style={styles.lovedOneStatLabel}>Relationship</Text>
            <View style={styles.lovedOneStatVal}>
              <Icon name="people-outline" size={14} color={GRAY} />
              <Text style={styles.lovedOneStatText}>{relationship || '—'}</Text>
            </View>
          </View>
        </View>
        {!compact && <Icon name="chevron-forward" size={18} color={GRAY} style={{ marginLeft: 8 }} />}
      </TouchableOpacity>
      {message && (
        <View style={styles.lovedOneActions}>
          <MessageCareTeamButton compact dependent={dep} onPress={message.onPress} busy={message.busy} error={message.error} />
        </View>
      )}
    </View>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────

const SHOW_LIMIT = 3;
const UPDATES_LIMIT = 4;

export function PatientProxyMainDashboard({ onNavigate, onSelectPatient, onOpenThread }: {
  /** Opens a section of the caregiver dashboard, with its URL params (a loved one, a tab, a record). */
  onNavigate?:      (key: string, params?: SectionParamsInput) => void;
  onSelectPatient?: (dep: CaregiverDependent) => void;
  /** Switches the dashboard to Messages on this thread (used by "Message Care Team"). */
  onOpenThread?:    (threadId: number) => void;
}) {
  const { width } = useWindowDimensions();
  const isLarge = width >= 1024;
  const isMedium = width >= 768;
  const careTeam = useCareTeamConversation(onOpenThread);
  const [errorFor, setErrorFor] = useState<number | null>(null);
  const messageCareTeam = (dep: CaregiverDependent) => { setErrorFor(dep.accessId); careTeam.open(dep); };
  // The list the dashboard shell already loaded for the sidebar (reloaded when stale), not a request of its own.
  const { dependents, loading: lovedLoading, error: lovedError, refetch: refetchLoved } = useDependents();
  const nav = useSectionNav();
  /** Opens a section with its params (a loved one, a tab, a record); without the prop through the dashboard's own navigation. */
  const open = (key: string, params?: SectionParamsInput) => {
    if (onNavigate) onNavigate(key, params);
    else nav.navigate(key, params);
  };
  const { appointments, consultations, loading: actLoading, error: actError, refetch: refetchActivity } = useCaregiverActivity();
  const [lovedExpanded, setLovedExpanded] = useState(false);

  const nameFor = (patientSystemId: string | null, fallback: string | null) =>
    dependents.find(d => d.patientSystemId === patientSystemId)?.patientName ?? fallback ?? 'Your loved one';

  const { upcoming, todays, nextSub } = useMemo(() => {
    // Scheduled and rescheduled appointments, soonest first. "Upcoming" and "today" are judged in clinic time,
    // the clock the appointment times are written in, not the device's.
    const active = appointments
      .filter(a => UPCOMING_STATUSES.has(a.status))
      .sort((a, b) => clinicKey(a.scheduledAt).localeCompare(clinicKey(b.scheduledAt)));
    const future = active.filter(a => isAfterClinicNow(a.scheduledAt));
    const today  = active.filter(a => isClinicToday(a.scheduledAt));
    const sub = future.length > 0
      ? `Next: ${formatClinicDateTime(future[0].scheduledAt, 'time to be confirmed')}`
      : 'No upcoming';
    return { upcoming: future.length, todays: today, nextSub: sub };
  }, [appointments]);

  const consultationWhen = (c: CaregiverConsultation) => c.completedAt ?? c.startedAt ?? c.createdAt;
  const recentConsultations = useMemo(
    () => [...consultations]
      .sort((a, b) => clinicKey(b.completedAt ?? b.startedAt ?? b.createdAt)
        .localeCompare(clinicKey(a.completedAt ?? a.startedAt ?? a.createdAt)))
      .slice(0, UPDATES_LIMIT),
    [consultations],
  );

  const consultTitle = (c: CaregiverConsultation) =>
    c.status === 'COMPLETED' ? 'Consultation completed' : 'Consultation recorded';

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>
      {/* The page heading for screen readers: the home has no visible title (the top bar greets instead), and
          every card title below is a level-2 heading under it. */}
      <Text style={VISUALLY_HIDDEN} accessibilityRole="header" {...ariaLevel(1)}>Caregiver dashboard</Text>

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
                  <LiveLovedOneRow
                    dep={dep}
                    onSelect={onSelectPatient}
                    compact={!isMedium}
                    message={onOpenThread ? {
                      onPress: messageCareTeam,
                      busy:    careTeam.openingFor === dep.accessId,
                      error:   errorFor === dep.accessId ? careTeam.error : null,
                    } : undefined}
                  />
                </View>
              ))}
              {dependents.length > SHOW_LIMIT && (
                <TouchableOpacity
                  style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 5, paddingTop: 12 }}
                  activeOpacity={0.7}
                  onPress={() => setLovedExpanded(e => !e)}
                  accessibilityRole="button"
                  // react-native-web ignores accessibilityState; the state is announced through aria-expanded.
                  aria-expanded={lovedExpanded}
                >
                  <Icon name={lovedExpanded ? 'chevron-up' : 'chevron-down'} size={14} color={PURPLE} />
                  <Text style={styles.viewAllText}>
                    {lovedExpanded ? 'Show less' : `+${dependents.length - SHOW_LIMIT} more`}
                  </Text>
                </TouchableOpacity>
              )}
            </>
          )}
          <TouchableOpacity
            style={styles.viewAllBtn}
            activeOpacity={0.7}
            onPress={() => open('health-summary')}
            accessibilityRole="button"
            // The arrow is decoration; the name is the visible words without it.
            accessibilityLabel="View health summary"
          >
            <Text style={styles.viewAllText}>View health summary →</Text>
          </TouchableOpacity>
        </Card>

        {/* Today's Schedule — from caregiver activity */}
        <Card
          title="Today's Schedule"
          action="View all"
          actionLabel="View all appointments"
          onAction={() => open('appointments')}
          style={[styles.colNarrow, !isLarge && styles.colFull]}
        >
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
                  canJoinCalls={dependents.some(d => d.patientSystemId === a.patientSystemId && effectivePermission(d, 'canJoinCalls'))}
                />
              </View>
            ))
          )}
          <TouchableOpacity
            style={styles.viewAllBtn}
            activeOpacity={0.7}
            onPress={() => open('appointments')}
            accessibilityRole="button"
            accessibilityLabel="View all appointments"
          >
            <Text style={styles.viewAllText}>View all appointments →</Text>
          </TouchableOpacity>
        </Card>
      </View>

      {/* Per loved one: active medications and recent lab results, or a note when they are not shared */}
      {!lovedLoading && !lovedError && dependents.length > 0 && (
        <DependentHighlights
          dependents={dependents}
          onOpenMedications={dep => open('medications', { patient: dep.patientSystemId })}
          onOpenLabResults={(dep, resultId) => open('documents-results', { tab: 'labs', patient: dep.patientSystemId, id: resultId })}
        />
      )}

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
                  time={formatShortDate(consultationWhen(c))}
                  // Opens the consultation (diagnosis, treatment plan, follow-up) in Documents & Results.
                  openLabel="Open consultation details"
                  onPress={c.patientSystemId ? () => open('documents-results', {
                    tab: 'consultations', patient: c.patientSystemId ?? undefined, id: c.consultationId,
                  }) : undefined}
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
              <TouchableOpacity style={styles.linkRow} activeOpacity={0.7} onPress={() => open(l.key)} accessibilityRole="button">
                <View style={[styles.updateIcon, { backgroundColor: PURPLE_BG }]} {...decorative}>
                  <Icon name={l.icon} size={15} color={PURPLE} />
                </View>
                <Text style={[styles.updateTitle, { flex: 1 }]}>{l.label}</Text>
                <Icon name="chevron-forward" size={16} color={GRAY} />
              </TouchableOpacity>
            </View>
          ))}
        </Card>
      </View>

      {/* Proxy Access Banner */}
      {dependents.length > 0 && (
      <View style={styles.banner}>
        <View style={styles.bannerIconWrapper} {...decorative}>
          <Icon name="shield-outline" size={28} color={PURPLE} />
        </View>
        <View style={styles.bannerText}>
          <Text style={styles.bannerTitle}>
            {dependents.length === 1
              ? `You are a ${accessLabel(dependents[0].accessLevel)} proxy for ${dependents[0].patientName ?? 'your loved one'}`
              : `You are a proxy for ${dependents.length} loved ones`}
          </Text>
          <Text style={styles.bannerSub}>
            Depending on the permissions each loved one has granted, you can view their appointments, medications, lab and imaging results, medical records, consultations and health profile.
          </Text>
        </View>
        <TouchableOpacity style={styles.manageBtn} activeOpacity={0.8} onPress={() => open('profile-settings')} accessibilityRole="button">
          <Icon name="people-outline" size={15} color={PURPLE} />
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
  statsRow: { flexDirection: 'row', gap: 12, flexWrap: 'wrap' },
  statCard: {
    flexGrow: 1, flexBasis: 150, backgroundColor: WHITE,
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
  // Stacked columns size to their content. `flex: undefined` did not reset flex: 1.4 on web, so the
  // cards shrank to a share of the column and the next card sat on top of their overflowing rows.
  colFull:       { flexGrow: 0, flexShrink: 0, flexBasis: 'auto' },

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
  // The row wraps: on a phone the stats move under the name instead of running past the screen edge.
  lovedOneCard: { backgroundColor: BG, borderRadius: 12, padding: 14, gap: 12 },
  lovedOneRow: {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 12, minWidth: 0,
  },
  lovedOneActions:      { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 8, minWidth: 0 },
  lovedOneLeft:         { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0 },
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
  lovedOneStats:   { flexGrow: 2, flexShrink: 1, flexBasis: 240, minWidth: 0, flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 12 },
  lovedOneStat:    { flexGrow: 1, flexShrink: 1, flexBasis: 72, minWidth: 0 },
  lovedOneStatLabel: { fontSize: 11, color: GRAY, marginBottom: 4 },
  lovedOneStatVal: { flexDirection: 'row', alignItems: 'center', gap: 5, minWidth: 0 },
  lovedOneStatText:{ fontSize: 13, fontWeight: '600', color: TEXT, flexShrink: 1 },
  lovedOneStatSub: { fontSize: 11, color: GRAY },
  statDivider:     { width: 1, height: 40, backgroundColor: BORDER },

  // Schedule
  // Wraps so the video-call button moves under the appointment at 375 px instead of running past the edge.
  scheduleRow: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 10 },
  scheduleText: { flexGrow: 1, flexShrink: 1, flexBasis: 120, minWidth: 0 },
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
  updateRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 10, minWidth: 0 },
  linkRow:   { flexDirection: 'row', alignItems: 'center', gap: 10 },
  updateIcon:{ width: 32, height: 32, borderRadius: 16, alignItems: 'center', justifyContent: 'center', marginTop: 1 },
  updateTitle:{ fontSize: 13, fontWeight: '600', color: TEXT },
  updateSub:  { fontSize: 11, color: GRAY, marginTop: 1 },
  updateTime: { fontSize: 11, color: GRAY },

  // Care team

  // Banner
  // Wraps so "View Access" moves under the text on a phone.
  banner: {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 16,
    backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER,
    borderRadius: 16, padding: 20,
  },
  bannerIconWrapper: {
    width: 56, height: 56, borderRadius: 28,
    backgroundColor: PURPLE_BG, borderWidth: 1, borderColor: PURPLE_LIGHT,
    alignItems: 'center', justifyContent: 'center',
  },
  bannerText:  { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0 },
  bannerTitle: { fontSize: 15, fontWeight: '700', color: TEXT, marginBottom: 4 },
  bannerSub:   { fontSize: 12, color: GRAY, lineHeight: 18 },
  manageBtn: {
    flexDirection: 'row', alignItems: 'center', gap: 6,
    borderWidth: 1, borderColor: BORDER, borderRadius: 10,
    paddingHorizontal: 14, paddingVertical: 10, backgroundColor: WHITE,
  },
  manageBtnText: { fontSize: 13, color: PURPLE, fontWeight: '600' },
});
