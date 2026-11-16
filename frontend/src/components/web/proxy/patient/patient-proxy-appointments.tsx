import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, type BannerTone, Button, Card, colors, decorative, EmptyView, ErrorView, fontSize, Icon, type IoniconsName,
  LoadingView, ROLE_THEME, Screen, SelectField, space, STATUS_TONES, type StatusToneName, Tabs,
} from '@/components/web/ui';
import { useCaregiverActivity, useCaregiverDependents } from '@/hooks/use-caregiver-dependents';
import { useSectionNav } from '@/hooks/use-section-nav';
import type { AppointmentDto } from '@/lib/appointments/types';
import { effectivePermission } from '@/lib/caregiver/permissions';
import type { CaregiverAppointment, CaregiverDependent } from '@/lib/caregiver/types';
import type { SectionProps } from '@/lib/nav/sections';
import { clinicNowLocal, formatClinicDate, formatClinicDateTime } from '@/lib/time/clinic';
import { CancelAppointmentDialog } from './booking/cancel-appointment-dialog';
import { ProxyAppointmentCard } from './booking/proxy-appointment-card';
import { ProxyBookingPanel } from './booking/proxy-booking-panel';
import {
  type AppointmentPhase, appointmentPhase, canSchedule, compareForList, dependentName,
} from './booking/scheduling';
import { useSchedulingRefusal } from './booking/use-scheduling-refusal';

// Caregiver Appointments (proxy section `appointments`). Lists the loved ones' appointments from the activity
// feed (paged) and lets the caregiver book, reschedule and cancel for every loved one whose grant allows
// scheduling. URL params: `patient` (PAT id) limits the list to one loved one and preselects them for booking;
// `mode=book` opens the booking form (the dependent profile links here); `tab` picks the filter; `date` is the
// clinic day the booking calendar starts on.

type Filter = 'all' | 'upcoming' | 'awaiting-outcome' | 'completed' | 'cancelled';

const FILTERS: { key: Filter; label: string }[] = [
  { key: 'all',              label: 'All' },
  { key: 'upcoming',         label: 'Upcoming' },
  { key: 'awaiting-outcome', label: 'Awaiting outcome' },
  { key: 'completed',        label: 'Completed' },
  { key: 'cancelled',        label: 'Cancelled' },
];

const EMPTY_FILTER: Record<Filter, string> = {
  'all':              'No appointments found',
  'upcoming':         'No upcoming appointments',
  'awaiting-outcome': 'No appointments awaiting an outcome',
  'completed':        'No completed appointments',
  'cancelled':        'No cancelled appointments',
};

function isFilter(value: string | undefined): value is Filter {
  return !!value && FILTERS.some(f => f.key === value);
}

function inFilter(filter: Filter, phase: AppointmentPhase): boolean {
  switch (filter) {
    case 'all':       return true;
    case 'upcoming':  return phase === 'upcoming' || phase === 'in-progress';
    default:          return phase === filter;
  }
}

/** The clinic's wall clock, refreshed every minute so an appointment moves from Upcoming on time. */
function useClinicMinute(): string {
  const [now, setNow] = useState(() => clinicNowLocal());
  useEffect(() => {
    const id = setInterval(() => setNow(clinicNowLocal()), 60_000);
    return () => clearInterval(id);
  }, []);
  return now;
}

function StatTile({ value, label, icon, tone }: { value: string; label: string; icon: IoniconsName; tone: StatusToneName }) {
  const c = STATUS_TONES[tone];
  return (
    <Card style={styles.stat}>
      <View role="group" aria-label={`${label}: ${value}`}>
        <Text style={[styles.statValue, { color: c.fg }]} {...decorative}>{value}</Text>
        <Text style={styles.statLabel} {...decorative}>{label}</Text>
      </View>
      <View style={[styles.statIcon, { backgroundColor: c.bg }]} {...decorative}>
        <Icon name={icon} size={18} color={c.fg} />
      </View>
    </Card>
  );
}

type Props = Partial<Pick<SectionProps, 'navigate' | 'params' | 'theme'>>;

export default function PatientProxyAppointments(props: Props) {
  const nav = useSectionNav();
  const params = props.params ?? nav.params;
  const navigate = props.navigate ?? nav.navigate;
  const theme = props.theme ?? ROLE_THEME.proxy;

  const patientParam = params.patient?.trim() || '';
  const booking = params.mode === 'book';
  const filter: Filter = isFilter(params.tab) ? params.tab : 'all';
  const initialDate = params.date?.trim() || null;

  const people = useCaregiverDependents();
  const activity = useCaregiverActivity();
  const now = useClinicMinute();

  const [notice, setNotice] = useState<{ tone: BannerTone; text: string } | null>(null);
  const [cancelling, setCancelling] = useState<CaregiverAppointment | null>(null);

  const byPatient = useMemo(
    () => new Map(people.dependents.map(d => [d.patientSystemId, d] as const)),
    [people.dependents],
  );
  const filteredDependent = patientParam ? byPatient.get(patientParam) ?? null : null;
  const anyBookable = people.dependents.some(d => canSchedule(d));

  const nameOf = useCallback((patientSystemId: string | null, fallback?: string | null) => {
    const dependent = patientSystemId ? byPatient.get(patientSystemId) : undefined;
    return dependent ? dependentName(dependent) : fallback?.trim() || 'your loved one';
  }, [byPatient]);

  // ── A refusal because the caregiver's permission was removed while the screen was open ────────────────────

  // The loved one the last booking, reschedule or cancel was for; names them in the message.
  const actingFor = useRef<string | null>(null);
  const { refetch: refetchPeople } = people;
  const { refetch: refetchActivity } = activity;

  const onForbidden = useCallback((patientSystemId?: string | null) => {
    const who = nameOf(patientSystemId ?? actingFor.current);
    setNotice({
      tone: 'danger',
      text: `Your access no longer allows you to book or change appointments for ${who}. `
        + 'The change was not made. Your loved ones and their appointments have been reloaded.',
    });
    setCancelling(null);
    refetchPeople();
    refetchActivity();
  }, [nameOf, refetchPeople, refetchActivity]);

  // A booking is for the loved one chosen in the form; a reschedule for the appointment's patient.
  useSchedulingRefusal(() => onForbidden(booking && patientParam ? patientParam : null));

  // ── Navigation inside the screen (kept in the URL so refresh and Back work) ───────────────────────────────

  const setFilter = (next: Filter) => nav.setParams({ tab: next === 'all' ? undefined : next });
  const setPatient = (next: string) => nav.setParams({ patient: next || undefined });

  function openBooking() {
    setNotice(null);
    const bookable = people.dependents.filter(d => canSchedule(d));
    // Keep the loved one already in view, or the only one the caregiver can book for.
    const preset = patientParam || (bookable.length === 1 ? bookable[0].patientSystemId : undefined);
    nav.setParams({ mode: 'book', patient: preset });
  }

  function closeBooking() {
    nav.setParams({ mode: undefined, date: undefined });
  }

  function chooseForBooking(patientSystemId: string) {
    actingFor.current = patientSystemId;
    nav.setParams({ patient: patientSystemId });
  }

  function handleBooked(appointment: AppointmentDto, dependent: CaregiverDependent) {
    const doctor = appointment.doctorName?.trim();
    setNotice({
      tone: 'success',
      text: `Appointment booked for ${dependentName(dependent)}${doctor ? ` with ${doctor}` : ''} on `
        + `${formatClinicDateTime(appointment.scheduledAt, 'the chosen time')}.`,
    });
    nav.setParams({ mode: undefined, date: undefined, tab: 'upcoming', patient: dependent.patientSystemId });
    refetchActivity();
  }

  // ── Actions on a listed appointment ───────────────────────────────────────────────────────────────────────

  function startAction(appt: CaregiverAppointment) {
    actingFor.current = appt.patientSystemId;
    setNotice(null);
  }

  function handleRescheduled(appt: CaregiverAppointment, updated: AppointmentDto, changed: boolean) {
    setNotice({
      tone: changed ? 'success' : 'info',
      text: changed
        ? `${nameOf(appt.patientSystemId, appt.patientName)}'s appointment was moved to ${formatClinicDateTime(updated.scheduledAt)}.`
        : 'The appointment already had that time, so nothing changed.',
    });
    refetchActivity();
  }

  function handleClosedMeanwhile(_appt: CaregiverAppointment, reason: string) {
    setNotice({ tone: 'warning', text: reason });
    refetchActivity();
  }

  function handleCancelled(appt: CaregiverAppointment) {
    setCancelling(null);
    setNotice({
      tone: 'success',
      text: `${nameOf(appt.patientSystemId, appt.patientName)}'s appointment on ${formatClinicDate(appt.scheduledAt)} was cancelled.`,
    });
    refetchActivity();
  }

  // ── The list ──────────────────────────────────────────────────────────────────────────────────────────────

  const phaseOf = useCallback((a: CaregiverAppointment) => appointmentPhase(a, now), [now]);

  const rows = useMemo(() => {
    const mine = patientParam
      ? activity.appointments.filter(a => a.patientSystemId === patientParam)
      : activity.appointments;
    return [...mine].sort((a, b) => compareForList(a, b, phaseOf));
  }, [activity.appointments, patientParam, phaseOf]);

  const counts = useMemo(() => {
    const c = { upcoming: 0, awaiting: 0, completed: 0 };
    for (const a of rows) {
      const p = phaseOf(a);
      if (p === 'upcoming' || p === 'in-progress') c.upcoming++;
      else if (p === 'awaiting-outcome') c.awaiting++;
      else if (p === 'completed') c.completed++;
    }
    return c;
  }, [rows, phaseOf]);

  const visible = rows.filter(a => inFilter(filter, phaseOf(a)));
  const total = patientParam ? `${rows.length}${activity.hasMore ? '+' : ''}` : String(activity.totalAppointments);
  const partial = activity.hasMore ? '+' : '';

  const patientOptions = useMemo(
    () => people.dependents
      .map(d => ({ value: d.patientSystemId, label: dependentName(d) }))
      .sort((a, b) => a.label.localeCompare(b.label)),
    [people.dependents],
  );

  // ── Booking view ──────────────────────────────────────────────────────────────────────────────────────────

  if (booking) {
    return (
      <Screen
        title="Book an Appointment"
        subtitle="Book on behalf of a loved one whose access allows scheduling"
        theme={theme}
        actions={<Button label="Back to appointments" icon="arrow-back-outline" variant="secondary" onPress={closeBooking} />}
      >
        <View style={styles.page}>
          {notice ? <Banner tone={notice.tone} text={notice.text} onDismiss={() => setNotice(null)} /> : null}
          <ProxyBookingPanel
            dependents={people.dependents}
            loading={people.loading}
            error={people.error}
            onRetry={refetchPeople}
            patientSystemId={patientParam}
            onChoose={chooseForBooking}
            initialDate={initialDate}
            onBooked={handleBooked}
            onAddDependent={() => navigate('add-dependent')}
            theme={theme}
          />
        </View>
      </Screen>
    );
  }

  // ── List view ─────────────────────────────────────────────────────────────────────────────────────────────

  const loadingFirst = activity.loading && activity.appointments.length === 0;

  return (
    <Screen
      title="Appointments"
      subtitle="Appointments for your linked patients"
      theme={theme}
      actions={(
        <Button
          label="Book appointment"
          icon="add-circle-outline"
          onPress={openBooking}
          disabled={!people.loading && people.dependents.length > 0 && !anyBookable}
          accessibilityLabel={!people.loading && people.dependents.length > 0 && !anyBookable
            ? 'Book appointment, unavailable: no loved one\'s access allows scheduling'
            : undefined}
        />
      )}
    >
      <View style={styles.page}>
        {notice ? <Banner tone={notice.tone} text={notice.text} onDismiss={() => setNotice(null)} /> : null}

        {people.error ? (
          <Banner
            tone="warning"
            text={`${people.error} Booking, rescheduling and cancelling stay unavailable until your access is loaded.`}
            action={{ label: 'Retry', onPress: refetchPeople }}
          />
        ) : !people.loading && people.dependents.length > 0 && !anyBookable ? (
          <Banner
            tone="info"
            text="None of your loved ones' access allows scheduling appointments, so you can view appointments but not book or change them."
          />
        ) : null}

        {patientOptions.length > 1 || patientParam ? (
          <View style={styles.filterBar}>
            <View style={styles.patientFilter}>
              <SelectField
                label="Loved one"
                value={patientParam}
                onChange={setPatient}
                options={patientParam && !filteredDependent && !people.loading
                  ? [...patientOptions, { value: patientParam, label: 'Not one of your loved ones' }]
                  : patientOptions}
                placeholder="All loved ones"
                allowEmpty
              />
            </View>
            {patientParam ? (
              <Button label="Show everyone" variant="ghost" size="sm" icon="close-outline" onPress={() => setPatient('')} />
            ) : null}
          </View>
        ) : null}

        {patientParam && !filteredDependent && !people.loading && !people.error ? (
          <Banner
            tone="warning"
            text="The person in this link is not one of your loved ones, or your access to them has ended."
            action={{ label: 'Show everyone', onPress: () => setPatient('') }}
          />
        ) : null}

        {loadingFirst ? (
          <LoadingView label="Loading appointments…" />
        ) : activity.error && activity.appointments.length === 0 ? (
          <ErrorView message={activity.error} onRetry={refetchActivity} />
        ) : (
          <>
            {activity.error ? (
              <Banner tone="danger" text={activity.error} action={{ label: 'Retry', onPress: refetchActivity }} />
            ) : null}

            <View style={styles.stats}>
              <StatTile value={total} label="Total" icon="calendar-outline" tone="neutral" />
              <StatTile value={`${counts.upcoming}`} label="Upcoming" icon="time-outline" tone="info" />
              <StatTile value={`${counts.awaiting}${partial}`} label="Awaiting outcome" icon="hourglass-outline" tone="warning" />
              <StatTile value={`${counts.completed}${partial}`} label="Done" icon="checkmark-circle-outline" tone="success" />
            </View>

            {activity.from ? (
              <Text style={styles.window}>
                Showing appointments from {formatClinicDate(activity.from)}
                {activity.to ? ` to ${formatClinicDate(activity.to)}` : ' onwards'}.
                {activity.hasMore ? ' Older appointments load with "Load more appointments".' : ''}
              </Text>
            ) : null}

            <Tabs tabs={FILTERS} active={filter} onChange={setFilter} label="Appointment filter" />

            {visible.length === 0 ? (
              <EmptyView
                icon="calendar-outline"
                title={rows.length === 0 ? 'No appointments yet' : EMPTY_FILTER[filter]}
                sub={rows.length === 0
                  ? (patientParam
                    ? `There are no appointments for ${nameOf(patientParam)} in this period.`
                    : 'Appointments you or your loved ones book appear here.')
                  : activity.hasMore ? 'More appointments may be on later pages.' : undefined}
                action={rows.length === 0 && anyBookable
                  ? { label: 'Book appointment', icon: 'add-circle-outline', onPress: openBooking }
                  : undefined}
              />
            ) : (
              <View style={styles.list}>
                {visible.map(a => {
                  const dependent = a.patientSystemId ? byPatient.get(a.patientSystemId) : undefined;
                  return (
                    <ProxyAppointmentCard
                      key={a.appointmentId}
                      appt={a}
                      phase={phaseOf(a)}
                      patientName={nameOf(a.patientSystemId, a.patientName)}
                      canManage={canSchedule(dependent)}
                      canJoinCalls={effectivePermission(dependent, 'canJoinCalls')}
                      onActionStart={() => startAction(a)}
                      onCancel={setCancelling}
                      onRescheduled={handleRescheduled}
                      onClosedMeanwhile={handleClosedMeanwhile}
                    />
                  );
                })}
              </View>
            )}

            {activity.hasMore ? (
              <Button
                label="Load more appointments"
                variant="secondary"
                icon="chevron-down-outline"
                onPress={activity.loadMore}
                loading={activity.loadingMore}
                fullWidth
              />
            ) : null}
          </>
        )}
      </View>

      <CancelAppointmentDialog
        appointment={cancelling}
        patientName={cancelling ? nameOf(cancelling.patientSystemId, cancelling.patientName) : ''}
        onClose={() => setCancelling(null)}
        onCancelled={handleCancelled}
        onForbidden={appt => onForbidden(appt.patientSystemId)}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  page:   { gap: space.lg, minWidth: 0 },
  list:   { gap: space.md },
  stats:  { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  stat:   {
    flexGrow: 1, flexShrink: 1, flexBasis: 140, minWidth: 0,
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space.sm,
  },
  statValue: { fontSize: fontSize.title, fontWeight: '700' },
  statLabel: { fontSize: fontSize.xs, color: colors.textMuted, marginTop: 2 },
  statIcon:  { width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center' },
  filterBar: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-end', gap: space.sm },
  patientFilter: { flexGrow: 1, flexShrink: 1, flexBasis: 240, maxWidth: 360, minWidth: 0 },
  window:    { fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 17 },
});
