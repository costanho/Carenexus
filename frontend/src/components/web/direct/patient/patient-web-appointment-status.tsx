import { useEffect, useMemo, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { ReschedulePanel } from '@/components/web/appointments/reschedule-panel';
import { CallJoinButton } from '@/components/web/call/call-join-button';
import {
  ariaLevel, Banner, Button, Card, colors, decorative, EmptyView, ErrorView, fontSize, Icon, type IoniconsName,
  LoadingView, radius, Screen, space, STATUS_TONES, StatusBadge, type StatusTone, type TabItem, Tabs, useUiTheme,
  webOnly,
} from '@/components/web/ui';
import {
  apiErrorMessage, appointmentCallJoin, applyAppointmentUpdate, cancelAppointment, isAppointmentAwaitingOutcome,
  isUpcomingAppointment, toAppointment, useAppointmentCounts, useAppointments, type Appointment, type AppointmentFilter,
} from '@/hooks/use-appointments';
import { doctorInitial } from '@/hooks/use-doctors';
import { useResource } from '@/hooks/use-resource';
import { getAppointment } from '@/lib/appointments/api';
import { APPOINTMENT_TYPE_LABEL } from '@/lib/appointments/rules';
import type { AppointmentDto } from '@/lib/appointments/types';
import { parseAppointmentId } from '@/lib/calls/join';
import type { SectionProps } from '@/lib/nav/sections';
import { formatClinicDate, formatClinicTime } from '@/lib/time/clinic';
import { confirmAction, showMessage } from '@/lib/ui/dialogs';
import { CallTimePending } from './call-time-pending';

const INDIGO = '#4F46E5';

const TYPE_ICON: Record<Appointment['type'], IoniconsName> = {
  IN_PERSON: 'business-outline',
  VIDEO:     'videocam-outline',
  PHONE:     'call-outline',
};

const FILTERS: TabItem<AppointmentFilter>[] = [
  { key: 'ALL',              label: 'All' },
  { key: 'UPCOMING',         label: 'Upcoming' },
  { key: 'AWAITING_OUTCOME', label: 'Awaiting outcome' },
  { key: 'COMPLETED',        label: 'Completed' },
  { key: 'RESCHEDULED',      label: 'Rescheduled' },
  { key: 'CANCELLED',        label: 'Cancelled' },
  { key: 'NO_SHOW',          label: 'No Show' },
];

const EMPTY_TITLE: Record<AppointmentFilter, string> = {
  ALL:              'No appointments yet',
  UPCOMING:         'No upcoming appointments',
  AWAITING_OUTCOME: 'No appointments are awaiting an outcome',
  COMPLETED:        'No completed appointments',
  RESCHEDULED:      'No rescheduled appointments',
  CANCELLED:        'No cancelled appointments',
  NO_SHOW:          'No missed appointments',
};

/** Cards revealed at a time; more are loaded from the server when the loaded ones run out. */
const PAGE = 10;

const RESCHEDULED_NOTICE = 'Your appointment has been moved to the new time.';

function isFilter(value: string | undefined): value is AppointmentFilter {
  return !!value && FILTERS.some(f => f.key === value);
}

/**
 * The status shown on a card. An appointment the doctor has not closed although its end has passed is
 * "Awaiting outcome", never "Scheduled" or "Upcoming": the visit is over and only its result is missing.
 */
function displayStatus(a: Appointment): { label: string; tone: StatusTone; icon?: IoniconsName } {
  if (isAppointmentAwaitingOutcome(a)) return { label: 'Awaiting outcome', tone: 'warning', icon: 'hourglass-outline' };
  switch (a.status) {
    case 'RESCHEDULED': return { label: 'Rescheduled', tone: 'info', icon: 'refresh-outline' };
    case 'COMPLETED':   return { label: 'Completed', tone: 'success' };
    case 'CANCELLED':   return { label: 'Cancelled', tone: 'neutral', icon: 'close-circle-outline' };
    case 'NO_SHOW':     return { label: 'No Show', tone: 'danger', icon: 'alert-circle-outline' };
    default:            return isUpcomingAppointment(a) ? { label: 'Upcoming', tone: 'info' } : { label: 'In progress', tone: 'info' };
  }
}

/** Upcoming appointments first, soonest first; everything else after them, most recent first. */
function upcomingFirst(a: Appointment, b: Appointment): number {
  const aUpcoming = isUpcomingAppointment(a);
  const bUpcoming = isUpcomingAppointment(b);
  if (aUpcoming !== bUpcoming) return aUpcoming ? -1 : 1;
  const byTime = a.scheduled_at.localeCompare(b.scheduled_at);
  return aUpcoming ? byTime : -byTime;
}

function StatCard({ value, label, icon, tone }: { value: number | null; label: string; icon: IoniconsName; tone: StatusTone }) {
  const c = STATUS_TONES[tone];
  return (
    <Card style={styles.statCard}>
      <View style={[styles.statIcon, { backgroundColor: c.bg }]}>
        <Icon name={icon} size={18} color={c.fg} />
      </View>
      {/* The value and label stay exposed as plain text (a name on a generic group is skipped by NVDA and JAWS in
          browse mode). The "–" shown when the count is unknown is hidden and replaced by words. */}
      <View>
        {value === null ? (
          <>
            <Text style={styles.statValue} {...decorative}>–</Text>
            <Text style={styles.srOnly}>Not available</Text>
          </>
        ) : (
          <Text style={styles.statValue}>{value}</Text>
        )}
        <Text style={styles.statLabel}>{label}</Text>
      </View>
    </Card>
  );
}

function MetaItem({ icon, text }: { icon: IoniconsName; text: string }) {
  return (
    <View style={styles.metaItem}>
      <Icon name={icon} size={13} color={colors.textMuted} />
      <Text style={styles.metaText}>{text}</Text>
    </View>
  );
}

function AppointmentCard({
  appt, notice, closedNotice, pinned, onMutationStart, onRescheduled, onClosedMeanwhile, onDismissClosed, onChanged, onMessageDoctor,
}: {
  appt: Appointment;
  /** Confirmation for this card, owned by the list so it survives the card being re-rendered after a change. */
  notice: string;
  /** Why a move was refused because the appointment was closed meanwhile; '' for none. Owned by the list. */
  closedNotice: string;
  /** The appointment a link opened; drawn with the accent border. */
  pinned?: boolean;
  /** A cancel or reschedule is starting: an older confirmation no longer describes the list. */
  onMutationStart: () => void;
  /** The appointment was moved; `changed` is false when the server kept the same time. */
  onRescheduled: (appointmentId: number, changed: boolean) => void;
  /** A move was refused because the appointment was completed, cancelled or started meanwhile. */
  onClosedMeanwhile: (appointmentId: number, reason: string) => void;
  onDismissClosed: () => void;
  /** A cancel or reschedule went through. */
  onChanged: () => void;
  onMessageDoctor?: (doctorSystemId: string, doctorName: string) => void;
}) {
  const { accent, accentBg } = useUiTheme();
  const [expanded, setExpanded]         = useState(false);
  const [cancelling, setCancelling]     = useState(false);
  const [rescheduling, setRescheduling] = useState(false);
  const status = displayStatus(appt);
  const typeLabel = APPOINTMENT_TYPE_LABEL[appt.type] ?? appt.type;
  const date = formatClinicDate(appt.scheduled_at, 'Date not set');
  const time = formatClinicTime(appt.scheduled_at, '');
  // Only appointments that are still ahead of us can be changed; one awaiting its outcome offers nothing.
  const canChange = isUpcomingAppointment(appt);
  const awaitingOutcome = isAppointmentAwaitingOutcome(appt);
  const join = appointmentCallJoin(appt);

  async function handleCancel() {
    const ok = await confirmAction(
      'Cancel appointment',
      `Cancel your appointment with ${appt.doctor_name} on ${date}${time ? ` at ${time}` : ''}?`,
      'Cancel appointment',
      true,
    );
    if (!ok) return;
    onMutationStart();
    setCancelling(true);
    try {
      // The cancellation is applied to the shared appointment lists, so every panel shows it.
      await cancelAppointment(appt.appointment_id);
      onChanged();
    } catch (e) {
      showMessage('Could not cancel appointment', apiErrorMessage(e, 'Failed to cancel appointment. Please try again.'));
    } finally {
      setCancelling(false);
    }
  }

  function handleStale(latest: AppointmentDto, reason: string | null) {
    // The list shows the appointment as the server now has it (a new status or time made elsewhere).
    applyAppointmentUpdate(latest);
    if (reason === null) return;
    setRescheduling(false);
    onClosedMeanwhile(latest.appointmentId, reason);
    onChanged();
  }

  function handleRescheduled(updated: AppointmentDto, changed: boolean) {
    setRescheduling(false);
    applyAppointmentUpdate(updated);
    onRescheduled(updated.appointmentId, changed);
    onChanged();
  }

  return (
    <Card style={[styles.card, pinned && { borderColor: accent, borderWidth: 2 }]}>
      <View style={styles.cardHeader}>
        <View style={[styles.typeIcon, { backgroundColor: accentBg }]}>
          <Icon name={TYPE_ICON[appt.type] ?? 'calendar-outline'} size={20} color={accent} />
        </View>
        <View style={styles.cardHeaderText}>
          <Text style={styles.doctorName} accessibilityRole="header" {...ariaLevel(2)}>{appt.doctor_name}</Text>
          {/* The accent border marks the appointment a link opened; screen readers get it in words, right after the
              card's heading so a user who reaches the card by heading navigation hears it next. */}
          {pinned ? <Text style={styles.srOnly}>Opened from your link.</Text> : null}
          {appt.doctor_specialization ? <Text style={styles.doctorSpec}>{appt.doctor_specialization}</Text> : null}
        </View>
        <View style={[styles.avatar, { backgroundColor: accentBg }]} {...decorative}>
          <Text style={[styles.avatarInitial, { color: accent }]}>{doctorInitial(appt.doctor_name)}</Text>
        </View>
      </View>

      <View style={styles.metaRow}>
        <MetaItem icon="calendar-outline" text={date} />
        {time ? <MetaItem icon="time-outline" text={time} /> : null}
        <MetaItem icon="hourglass-outline" text={`${appt.duration_minutes} min`} />
      </View>

      <View style={styles.badgeRow}>
        <StatusBadge label={status.label} tone={status.tone} icon={status.icon} />
        <StatusBadge label={typeLabel} tone="neutral" icon={TYPE_ICON[appt.type] ?? null} />
      </View>

      {appt.facility_name ? (
        <View style={styles.detailRow}>
          <Icon name="location-outline" size={15} color={colors.textMuted} style={styles.detailIcon} />
          <View style={styles.detailText}>
            <Text style={styles.detailTitle}>{appt.facility_name}</Text>
            {appt.facility_address ? <Text style={styles.detailSub}>{appt.facility_address}</Text> : null}
          </View>
        </View>
      ) : null}

      {appt.type === 'PHONE' && appt.contact_phone ? (
        <View style={styles.detailRow}>
          <Icon name="call-outline" size={15} color={colors.textMuted} style={styles.detailIcon} />
          <View style={styles.detailText}>
            <Text style={styles.detailTitle}>{appt.contact_phone}</Text>
            <Text style={styles.detailSub}>
              {canChange ? 'The doctor will call you on this number.' : 'Contact number for this appointment.'}
            </Text>
          </View>
        </View>
      ) : null}

      {awaitingOutcome ? (
        <Text style={styles.outcomeNote}>
          This appointment has ended. Your doctor has not recorded its outcome yet, so it can no longer be rescheduled or cancelled.
        </Text>
      ) : null}

      {appt.reason_for_visit ? (
        <View style={styles.reasonBox}>
          <Icon name="document-text-outline" size={13} color={colors.textMuted} style={styles.detailIcon} />
          <Text style={styles.reasonText} numberOfLines={expanded ? undefined : 2}>{appt.reason_for_visit}</Text>
        </View>
      ) : null}

      {/* Video call: a time still awaiting the clinic's confirmation is a note, not a button. */}
      {appt.call_status === 'NEEDS_TIME_CONFIRMATION' ? (
        join && canChange ? <CallTimePending /> : null
      ) : (
        <CallJoinButton
          info={join}
          withName={appt.doctor_name}
          style={styles.videoBtn}
          textStyle={styles.videoBtnText}
          iconSize={15}
          iconColor={INDIGO}
        />
      )}

      {expanded ? (
        <View style={styles.notesBox}>
          <Text style={styles.notesLabel}>Notes</Text>
          <Text style={styles.notesText}>{appt.notes?.trim() ? appt.notes : 'No notes were added to this appointment.'}</Text>
        </View>
      ) : null}

      {notice ? <Banner tone="success" text={notice} /> : null}
      {closedNotice ? <Banner tone="warning" text={closedNotice} onDismiss={onDismissClosed} /> : null}

      {/* Reschedule / cancel: only for appointments that have not started yet */}
      {canChange && rescheduling ? (
        <ReschedulePanel
          appointment={{
            appointmentId:   appt.appointment_id,
            doctorSystemId:  appt.doctor_system_id ?? '',
            scheduledAt:     appt.scheduled_at,
            durationMinutes: appt.duration_minutes,
          }}
          onDone={handleRescheduled}
          onClose={() => setRescheduling(false)}
          onStale={handleStale}
        />
      ) : null}
      {canChange && !rescheduling ? (
        <View style={styles.actionRow}>
          <Button
            label="Reschedule"
            accessibilityLabel={`Reschedule appointment with ${appt.doctor_name}`}
            icon="calendar-outline"
            variant="secondary"
            onPress={() => { onMutationStart(); setRescheduling(true); }}
            disabled={cancelling}
            style={styles.actionButton}
          />
          <Button
            label="Cancel appointment"
            accessibilityLabel={`Cancel appointment with ${appt.doctor_name}`}
            icon="close-circle-outline"
            variant="danger"
            onPress={handleCancel}
            loading={cancelling}
            style={styles.actionButton}
          />
        </View>
      ) : null}

      {onMessageDoctor && appt.doctor_system_id ? (
        <Button
          label="Message"
          // The doctor's name is already on the card; the accessible name carries it.
          accessibilityLabel={`Message ${appt.doctor_name}`}
          icon="chatbubble-ellipses-outline"
          variant="secondary"
          onPress={() => onMessageDoctor(appt.doctor_system_id!, appt.doctor_name)}
          fullWidth
        />
      ) : null}

      <Pressable
        style={styles.expandToggle}
        onPress={() => setExpanded(e => !e)}
        accessibilityRole="button"
        accessibilityState={{ expanded }}
        {...webOnly({ 'aria-expanded': expanded })}
      >
        <Text style={[styles.expandToggleText, { color: accent }]}>{expanded ? 'Show less' : 'Show details'}</Text>
        <Icon name={expanded ? 'chevron-up' : 'chevron-down'} size={13} color={accent} />
      </Pressable>
    </Card>
  );
}

type Props = Partial<Pick<SectionProps, 'navigate' | 'params' | 'theme'>> & {
  /** Opens a private conversation with the appointment's doctor in Messages. */
  onMessageDoctor?: (doctorSystemId: string, doctorName: string) => void;
};

/**
 * Patient > My Appointments. Each tab is a server-side filter read page by page. `?id=<appointmentId>` (the
 * link in an appointment notification) shows that appointment first; `?tab=` opens a filter.
 */
export function PatientWebAppointmentStatus({ onMessageDoctor, navigate, params, theme }: Props = {}) {
  const [filter, setFilter] = useState<AppointmentFilter>(isFilter(params?.tab) ? params.tab : 'ALL');
  // This is the screen where a change made elsewhere (by the doctor) must show, so it reloads on opening.
  const { appointments, loading, error, refetch, refreshing, hasMore, loadMore, loadingMore, total } =
    useAppointments({ filter, maxAgeMs: 1_000 });
  const { counts, refetch: refetchCounts } = useAppointmentCounts();
  const [visibleCount, setVisibleCount] = useState(PAGE);
  // Kept here, not in the card, so it survives the card being re-rendered and a card leaving the filter.
  const [rescheduledId, setRescheduledId] = useState<number | null>(null);
  /** A move refused because the appointment was closed meanwhile, with the server's reason. */
  const [closed, setClosed] = useState<{ id: number; text: string } | null>(null);
  /** The refresh error the person dismissed; a later failure shows again. */
  const [dismissedError, setDismissedError] = useState<string | null>(null);

  useEffect(() => {
    if (!error) setDismissedError(null);
  }, [error]);

  // ── The appointment a link opened ─────────────────────────────────────────

  const focusId = parseAppointmentId(params?.id);
  const [focusOpen, setFocusOpen] = useState(true);
  useEffect(() => { setFocusOpen(true); }, [focusId]);
  const focus = useResource(
    () => getAppointment(focusId ?? 0),
    [focusId],
    { enabled: focusId !== null, errorMessage: 'The appointment you opened could not be loaded.' },
  );

  const sorted = useMemo(
    () => (filter === 'ALL' ? [...appointments].sort(upcomingFirst) : appointments),
    [appointments, filter],
  );
  const pinned = useMemo(() => {
    if (focusId === null || !focusOpen) return null;
    return sorted.find(a => a.appointment_id === focusId) ?? (focus.data ? toAppointment(focus.data) : null);
  }, [focusId, focusOpen, sorted, focus.data]);

  const rest = pinned ? sorted.filter(a => a.appointment_id !== pinned.appointment_id) : sorted;
  const visible = rest.slice(0, visibleCount);
  const canShowMore = visibleCount < rest.length || hasMore;
  // The opened appointment is shown above the list, so it is not counted in it.
  const listTotal = Math.max(0, total - (pinned && sorted.some(a => a.appointment_id === pinned.appointment_id) ? 1 : 0));
  const remaining = Math.max(0, listTotal - visible.length);
  const noticeOnCard = rescheduledId !== null
    && (pinned?.appointment_id === rescheduledId || visible.some(a => a.appointment_id === rescheduledId));
  const closedOnCard = closed !== null
    && (pinned?.appointment_id === closed.id || visible.some(a => a.appointment_id === closed.id));

  function changeFilter(next: AppointmentFilter) {
    setFilter(next);
    setVisibleCount(PAGE);
    // The confirmation and the opened appointment belong to the list the person just left.
    setRescheduledId(null);
    setClosed(null);
    setFocusOpen(false);
    setDismissedError(null);
  }

  function refresh() {
    setRescheduledId(null);
    setClosed(null);
    setDismissedError(null);
    refetch();
    refetchCounts();
    if (focusId !== null) focus.refetch();
  }

  function showMore() {
    const next = visibleCount + PAGE;
    setVisibleCount(next);
    if (next > rest.length && hasMore) loadMore();
  }

  function handleRescheduled(appointmentId: number, changed: boolean) {
    // Confirming the time the appointment already had changes nothing, so nothing is announced.
    setRescheduledId(changed ? appointmentId : null);
  }

  function handleChanged() {
    // The opened appointment is loaded on its own, so it is read again after a change.
    if (focusId !== null) focus.refetch();
  }

  const cardProps = {
    onMutationStart:   () => { setRescheduledId(null); setClosed(null); },
    onRescheduled:     handleRescheduled,
    onClosedMeanwhile: (id: number, text: string) => setClosed({ id, text }),
    onDismissClosed:   () => setClosed(null),
    onChanged:       handleChanged,
    onMessageDoctor,
  };
  const showErrorBanner = !!error && appointments.length > 0 && error !== dismissedError;

  return (
    <Screen
      title="My Appointments"
      subtitle="Track and manage all your healthcare appointments"
      theme={theme}
      role="patient"
      actions={(
        <Button
          label="Refresh"
          accessibilityLabel="Refresh appointments"
          icon="refresh-outline"
          variant="secondary"
          onPress={refresh}
          loading={refreshing}
        />
      )}
    >
      <View style={styles.column}>
        <View style={styles.statsRow}>
          <StatCard value={counts?.total ?? null}     label="Total"     icon="calendar-outline"         tone="info" />
          <StatCard value={counts?.upcoming ?? null}  label="Upcoming"  icon="time-outline"             tone="info" />
          <StatCard value={counts?.completed ?? null} label="Completed" icon="checkmark-circle-outline" tone="success" />
          <StatCard value={counts?.cancelled ?? null} label="Cancelled" icon="close-circle-outline"     tone="neutral" />
        </View>

        <Tabs tabs={FILTERS} active={filter} onChange={changeFilter} label="Filter appointments" />

        {/* A refresh that failed after the list was loaded: the rows stay and the failure is said, not hidden. */}
        {showErrorBanner ? (
          <Banner
            tone="danger"
            text={`${error} The list below may be out of date.`}
            action={{ label: 'Retry', onPress: refresh }}
            onDismiss={() => setDismissedError(error)}
          />
        ) : null}

        {/* Reschedule confirmation when the moved card is not on screen (filtered out or not revealed yet) */}
        {rescheduledId !== null && !noticeOnCard && !loading ? (
          <Banner tone="success" text={RESCHEDULED_NOTICE} onDismiss={() => setRescheduledId(null)} />
        ) : null}

        {/* A refused move whose card left this list once its new status was applied */}
        {closed !== null && !closedOnCard && !loading ? (
          <Banner tone="warning" text={closed.text} onDismiss={() => setClosed(null)} />
        ) : null}

        {focusId !== null && focusOpen && focus.error && !pinned ? (
          <Banner tone="warning" text={focus.error} onDismiss={() => setFocusOpen(false)} />
        ) : null}

        {pinned ? (
          <View style={styles.cardList}>
            <AppointmentCard
              appt={pinned}
              pinned
              notice={pinned.appointment_id === rescheduledId ? RESCHEDULED_NOTICE : ''}
              closedNotice={closed?.id === pinned.appointment_id ? closed.text : ''}
              {...cardProps}
            />
          </View>
        ) : null}

        {loading ? (
          <LoadingView label="Loading appointments…" />
        ) : error && appointments.length === 0 ? (
          <ErrorView message={error} onRetry={refresh} />
        ) : rest.length === 0 ? (
          pinned ? null : (
            <EmptyView
              icon="calendar-outline"
              title={EMPTY_TITLE[filter]}
              sub={filter === 'ALL' ? 'When you book a visit it appears here.' : undefined}
              action={filter === 'ALL' && navigate
                ? { label: 'Book an appointment', icon: 'add-circle-outline', onPress: () => navigate('book-appointment') }
                : undefined}
            />
          )
        ) : (
          <View style={styles.cardList}>
            {visible.map(appt => (
              <AppointmentCard
                key={appt.appointment_id}
                appt={appt}
                notice={appt.appointment_id === rescheduledId ? RESCHEDULED_NOTICE : ''}
                closedNotice={closed?.id === appt.appointment_id ? closed.text : ''}
                {...cardProps}
              />
            ))}
            {canShowMore ? (
              <Button
                label={remaining > 0 ? `Show ${Math.min(PAGE, remaining)} more appointment${Math.min(PAGE, remaining) !== 1 ? 's' : ''}` : 'Show more appointments'}
                icon="chevron-down-circle-outline"
                variant="secondary"
                onPress={showMore}
                loading={loadingMore}
                fullWidth
              />
            ) : null}
            <Text style={styles.countLine} role="status">
              Showing {visible.length} of {Math.max(listTotal, visible.length)} {listTotal === 1 ? 'appointment' : 'appointments'}
            </Text>
          </View>
        )}
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  column: { width: '100%', maxWidth: 860, minWidth: 0, gap: space.lg },

  statsRow:  { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  statCard:  { flexGrow: 1, flexShrink: 1, flexBasis: 150, minWidth: 0, flexDirection: 'row', alignItems: 'center', gap: space.md },
  statIcon:  { width: 36, height: 36, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  statValue: { fontSize: fontSize.title, fontWeight: '700', color: colors.text },
  statLabel: { fontSize: fontSize.xs, color: colors.textMuted, fontWeight: '500' },

  cardList:  { gap: space.md },
  countLine: { fontSize: fontSize.xs, color: colors.textMuted, textAlign: 'center' },

  card:           { gap: space.md },
  // Visually hidden, still read by screen readers.
  srOnly:         { position: 'absolute', width: 1, height: 1, overflow: 'hidden', opacity: 0 },
  cardHeader:     { flexDirection: 'row', alignItems: 'center', gap: space.md },
  typeIcon:       { width: 40, height: 40, borderRadius: radius.lg, alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  cardHeaderText: { flex: 1, minWidth: 0 },
  doctorName:     { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  doctorSpec:     { fontSize: fontSize.xs, color: colors.textMuted, marginTop: 2 },
  avatar:         { width: 40, height: 40, borderRadius: radius.pill, alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  avatarInitial:  { fontSize: fontSize.lg, fontWeight: '700' },

  metaRow:  { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', columnGap: space.md, rowGap: space.xs },
  metaItem: { flexDirection: 'row', alignItems: 'center', gap: space.xs },
  metaText: { fontSize: fontSize.sm, color: colors.textMuted },
  badgeRow: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },

  detailRow:   { flexDirection: 'row', alignItems: 'flex-start', gap: space.sm },
  detailIcon:  { marginTop: 2, flexShrink: 0 },
  detailText:  { flex: 1, minWidth: 0 },
  detailTitle: { fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  detailSub:   { fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 17 },
  outcomeNote: { fontSize: fontSize.sm, color: STATUS_TONES.warning.fg, lineHeight: 19 },

  reasonBox:  { flexDirection: 'row', alignItems: 'flex-start', gap: space.sm, backgroundColor: colors.bg, borderRadius: radius.md, padding: space.md },
  reasonText: { flex: 1, minWidth: 0, fontSize: fontSize.sm, color: colors.text, lineHeight: 19 },

  notesBox:   { backgroundColor: STATUS_TONES.warning.bg, borderRadius: radius.md, padding: space.md, gap: space.xs },
  notesLabel: { fontSize: fontSize.xs, fontWeight: '700', color: STATUS_TONES.warning.fg, textTransform: 'uppercase', letterSpacing: 0.5 },
  notesText:  { fontSize: fontSize.sm, color: colors.text, lineHeight: 19 },

  videoBtn:     { flexDirection: 'row', alignItems: 'center', gap: space.sm, backgroundColor: '#EEF2FF', borderRadius: radius.md, padding: space.md, borderWidth: 1, borderColor: '#C7D2FE' },
  videoBtnText: { flex: 1, fontSize: fontSize.sm, fontWeight: '600', color: INDIGO },

  actionRow:    { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  actionButton: { flexGrow: 1, flexBasis: 160, alignSelf: 'auto' },

  expandToggle:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: space.xs, minHeight: 32, paddingTop: space.sm, borderTopWidth: 1, borderTopColor: colors.border },
  expandToggleText: { fontSize: fontSize.xs, fontWeight: '600' },
});

export default PatientWebAppointmentStatus;
