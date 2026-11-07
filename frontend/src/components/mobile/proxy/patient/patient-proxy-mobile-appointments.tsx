import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import {
  useCaregiverActivity,
  useCaregiverDependents,
  type CaregiverAppointment,
} from '@/hooks/use-caregiver-dependents';
import { isAfterClinicNow } from '@/hooks/use-appointments';
import { EmptyState, ErrorState, LoadingState } from '@/components/web/proxy/patient/proxy-states';
import {
  ALL, BG, BORDER, GRAY, GREEN, ORANGE, PURPLE, PURPLE_BG, PURPLE_LIGHT, RED, TEXT, WHITE,
  canView, depFirstName, depName, DependentPicker, FilterTabs, joinNames, NotSharedBanner, NotSharedState,
  ScreenHeader, StatRow, useDoctorNames, usePickedDependent, useScale, type IoniconsName,
} from './patient-proxy-mobile-shared';

type Filter = 'upcoming' | 'past' | 'cancelled' | 'all';

const FILTERS: { key: Filter; label: string }[] = [
  { key: 'upcoming',  label: 'Upcoming' },
  { key: 'past',      label: 'Past' },
  { key: 'cancelled', label: 'Cancelled' },
  { key: 'all',       label: 'All' },
];

const ACTIVE_STATUSES = new Set(['SCHEDULED', 'RESCHEDULED']);

function apptTime(a: CaregiverAppointment) {
  return new Date(a.scheduledAt).getTime() || 0;
}
/** Booked or moved, and still ahead in clinic time (scheduledAt is a clinic wall-clock value). `now` only re-evaluates per load. */
function isUpcoming(a: CaregiverAppointment, _now: number) {
  return ACTIVE_STATUSES.has(a.status) && isAfterClinicNow(a.scheduledAt);
}
function inFilter(a: CaregiverAppointment, f: Filter, now: number) {
  if (f === 'all')       return true;
  if (f === 'cancelled') return a.status === 'CANCELLED';
  if (f === 'upcoming')  return isUpcoming(a, now);
  return a.status !== 'CANCELLED' && !isUpcoming(a, now);
}

function statusInfo(a: CaregiverAppointment, now: number): { label: string; bg: string; text: string } {
  switch (a.status) {
    case 'CANCELLED': return { label: 'Cancelled', bg: '#FEE2E2', text: RED };
    case 'COMPLETED': return { label: 'Completed', bg: '#D1FAE5', text: GREEN };
    case 'NO_SHOW':   return { label: 'Missed',    bg: '#FEF3C7', text: ORANGE };
    case 'SCHEDULED':
    case 'RESCHEDULED':
      // A past visit that was never marked done or cancelled is not "upcoming"
      if (!isUpcoming(a, now)) return { label: 'Awaiting update', bg: '#F3F4F6', text: GRAY };
      return { label: a.status === 'RESCHEDULED' ? 'Rescheduled' : 'Scheduled', bg: PURPLE_LIGHT, text: PURPLE };
    default: {
      const t = (a.status ?? '').replace(/_/g, ' ').toLowerCase();
      return { label: t ? t.charAt(0).toUpperCase() + t.slice(1) : 'Unknown', bg: '#F3F4F6', text: GRAY };
    }
  }
}

// scheduledAt is a timestamp (not a date-only value), shown in the device's time zone like the rest of the app
function formatDay(iso: string) {
  const d = new Date(iso);
  return isNaN(d.getTime()) ? '—' : d.toLocaleDateString('en-ZW', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
}
function formatTime(iso: string) {
  const d = new Date(iso);
  return isNaN(d.getTime()) ? '' : d.toLocaleTimeString('en-ZW', { hour: '2-digit', minute: '2-digit' });
}
function typeInfo(type: string | null): { label: string; icon: IoniconsName } {
  if (type === 'VIDEO') return { label: 'Video call', icon: 'videocam-outline' };
  if (type === 'PHONE') return { label: 'Phone call', icon: 'call-outline' };
  return { label: 'In person', icon: 'business-outline' };
}

function Detail({ icon, text, color = GRAY }: { icon: IoniconsName; text: string; color?: string }) {
  const { s } = useScale();
  return (
    <View style={[styles.detail, { gap: s(5) }]}>
      <Ionicons name={icon} size={s(13)} color={color} importantForAccessibility="no" />
      <Text style={[styles.detailText, { fontSize: s(12), color }]} numberOfLines={1}>{text}</Text>
    </View>
  );
}

function AppointmentCard({ appt, person, showPerson, doctor, now }: {
  appt: CaregiverAppointment; person: string; showPerson: boolean; doctor: string | null; now: number;
}) {
  const { s } = useScale();
  const st       = statusInfo(appt, now);
  const type     = typeInfo(appt.type);
  const upcoming = isUpcoming(appt, now);
  const day      = formatDay(appt.scheduledAt);
  const time     = formatTime(appt.scheduledAt);
  const duration = appt.durationMinutes ? `${appt.durationMinutes} min` : '';
  // Only the doctor can confirm the time, so for the caregiver this is a plain status, never a control
  const timePending = upcoming && appt.type === 'VIDEO' && appt.callStatus === 'NEEDS_TIME_CONFIRMATION';
  const title = appt.reasonForVisit?.trim() || 'Appointment';

  const a11y = [
    title, showPerson ? person : null, st.label, day, time, duration, type.label,
    doctor ? `with ${doctor}` : null, timePending ? 'Time to be confirmed' : null,
  ].filter(Boolean).join(', ');

  return (
    <View
      style={[styles.card, { borderRadius: s(14), padding: s(14), gap: s(10), marginBottom: s(10) }, upcoming && styles.cardUpcoming]}
      accessible
      accessibilityLabel={a11y}
    >
      <View style={[styles.cardHead, { gap: s(10) }]}>
        <View style={{ flex: 1, minWidth: 0 }}>
          <Text style={[styles.cardTitle, { fontSize: s(14) }]} numberOfLines={2}>{title}</Text>
          {showPerson && <Text style={[styles.person, { fontSize: s(12), marginTop: s(2) }]} numberOfLines={1}>{person}</Text>}
        </View>
        <View style={[styles.badge, { backgroundColor: st.bg, borderRadius: s(12), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
          <Text style={{ fontSize: s(11), fontWeight: '600', color: st.text }} numberOfLines={1}>{st.label}</Text>
        </View>
      </View>

      <View style={[styles.details, { rowGap: s(6), columnGap: s(14) }]}>
        <Detail icon="calendar-outline" text={day} />
        <Detail icon="time-outline" text={[time, duration].filter(Boolean).join(' · ') || '—'} />
        <Detail icon={type.icon} text={type.label} color={appt.type === 'VIDEO' ? PURPLE : GRAY} />
        {doctor ? <Detail icon="person-outline" text={doctor} /> : null}
      </View>

      {timePending && (
        <View style={[styles.pending, { borderRadius: s(8), paddingHorizontal: s(10), paddingVertical: s(6), gap: s(6) }]}>
          <Ionicons name="hourglass-outline" size={s(13)} color={ORANGE} importantForAccessibility="no" />
          <Text style={{ fontSize: s(12), color: TEXT, flexShrink: 1 }}>Time to be confirmed</Text>
        </View>
      )}

      {appt.notes?.trim() ? (
        <View style={[styles.notes, { borderRadius: s(8), padding: s(10) }]}>
          <Text style={{ fontSize: s(12), color: GRAY, lineHeight: s(17) }} numberOfLines={4}>{appt.notes.trim()}</Text>
        </View>
      ) : null}
    </View>
  );
}

/**
 * Schedule tab: the loved ones' appointments (GET /api/caregiver/dependents/activity). The backend only returns
 * appointments for grants with the appointments permission; loved ones without it are named as "not shared".
 */
export default function PatientProxyMobileAppointments({ initialPatientId, onBack }: {
  initialPatientId?: string;
  onBack?: () => void;
}) {
  const { s } = useScale();
  const { dependents, loading: depsLoading, error: depsError, refetch: refetchDeps } = useCaregiverDependents();
  const { appointments, loading, error, refetch } = useCaregiverActivity();
  const doctorName = useDoctorNames();
  const { value: who, setValue: setWho, selected } = usePickedDependent(dependents, initialPatientId, 'all');
  const [filter, setFilter] = useState<Filter>('upcoming');

  const now = useMemo(() => Date.now(), [appointments]);
  const nameFor = (a: CaregiverAppointment) =>
    dependents.find(d => d.patientSystemId === a.patientSystemId)?.patientName?.trim() || a.patientName?.trim() || 'Your loved one';

  const scoped = useMemo(
    () => (who === ALL ? appointments : appointments.filter(a => a.patientSystemId === who)),
    [appointments, who],
  );
  const visible = useMemo(() => {
    const list = scoped.filter(a => inFilter(a, filter, now));
    // Upcoming: soonest first. Everything else: most recent first.
    return list.sort((a, b) => (filter === 'upcoming' ? apptTime(a) - apptTime(b) : apptTime(b) - apptTime(a)));
  }, [scoped, filter, now]);

  const upcomingCount  = scoped.filter(a => isUpcoming(a, now)).length;
  const completedCount = scoped.filter(a => a.status === 'COMPLETED').length;

  const notShared = dependents.filter(d => !canView(d, 'canViewAppointments'));
  const noneShared = dependents.length > 0 && notShared.length === dependents.length;

  function renderBody() {
    if (depsLoading && dependents.length === 0) return <LoadingState label="Loading loved ones" />;
    if (depsError && dependents.length === 0)   return <ErrorState message={depsError} onRetry={refetchDeps} />;
    if (dependents.length === 0) {
      return <EmptyState icon="people-outline" title="No loved ones linked yet" sub="Add a loved one from the dashboard to see their appointments here." />;
    }
    if (selected && !canView(selected, 'canViewAppointments')) {
      return (
        <NotSharedState
          title={`${depFirstName(selected)}'s appointments are not shared with you`}
          sub={`Ask ${depFirstName(selected)} to update the permissions on your caregiver access.`}
        />
      );
    }
    if (!selected && noneShared) {
      return <NotSharedState title="Appointments are not shared with you" sub="Ask your loved ones to update the permissions on your caregiver access." />;
    }
    return (
      <>
        <StatRow items={[
          { label: 'Upcoming',  value: loading && scoped.length === 0 ? '…' : String(upcomingCount),  bg: PURPLE_LIGHT, color: PURPLE },
          { label: 'Completed', value: loading && scoped.length === 0 ? '…' : String(completedCount), bg: '#D1FAE5',    color: GREEN },
          { label: 'Total',     value: loading && scoped.length === 0 ? '…' : String(scoped.length),  bg: PURPLE_BG,    color: PURPLE },
        ]} />
        <FilterTabs label="Appointment filter" options={FILTERS} value={filter} onChange={setFilter} />

        {!selected && notShared.length > 0 && (
          <NotSharedBanner text={`Appointments for ${joinNames(notShared.map(depName))} are not shared with you.`} />
        )}

        {loading && appointments.length === 0 ? (
          <LoadingState label="Loading appointments" />
        ) : error && appointments.length === 0 ? (
          <ErrorState message={error} onRetry={refetch} />
        ) : (
          <>
            {error ? <ErrorState compact message={error} onRetry={refetch} /> : null}
            {visible.length === 0 ? (
              <EmptyState
                icon="calendar-outline"
                title={filter === 'upcoming' ? 'No upcoming appointments' : filter === 'cancelled' ? 'No cancelled appointments' : filter === 'past' ? 'No past appointments' : 'No appointments yet'}
              />
            ) : (
              visible.map(a => (
                <AppointmentCard
                  key={a.appointmentId}
                  appt={a}
                  person={nameFor(a)}
                  showPerson={!selected}
                  doctor={doctorName(a.doctorSystemId)}
                  now={now}
                />
              ))
            )}
          </>
        )}
      </>
    );
  }

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={{ padding: s(14), paddingBottom: s(24) }} showsVerticalScrollIndicator={false}>
      <ScreenHeader
        title="Schedule"
        sub={selected ? `Appointments for ${depName(selected)}` : 'Appointments for your loved ones'}
        onBack={onBack}
        backLabel="Back to profile"
      />
      <DependentPicker dependents={dependents} value={who} onChange={setWho} />
      {renderBody()}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll: { flex: 1, backgroundColor: BG },

  card:         { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  cardUpcoming: { borderLeftWidth: 3, borderLeftColor: PURPLE },
  cardHead:     { flexDirection: 'row', alignItems: 'flex-start' },
  cardTitle:    { fontWeight: '700', color: TEXT },
  person:       { color: PURPLE, fontWeight: '500' },
  badge:        { flexShrink: 0 },

  details:    { flexDirection: 'row', flexWrap: 'wrap' },
  detail:     { flexDirection: 'row', alignItems: 'center', maxWidth: '100%' },
  detailText: { flexShrink: 1 },

  pending: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#FFFBEB', alignSelf: 'flex-start' },
  notes:   { backgroundColor: BG },
});
