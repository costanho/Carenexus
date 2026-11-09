import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';
import {
  useCaregiverActivity,
  useCaregiverDependents,
  type CaregiverAppointment,
  type CaregiverConsultation,
  type CaregiverDependent,
} from '@/hooks/use-caregiver-dependents';
import { tidyDoctorName, useDoctors } from '@/hooks/use-doctors';
import { ageFromDateOnly } from '@/lib/ui/date-only';
import { EmptyState, ErrorState } from '@/components/web/proxy/patient/proxy-states';
import { MobileMessageCareTeamButton } from '../../messages/messaging-actions';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const PURPLE = '#7C3AED';
const PURPLE_BG   = '#F5F3FF';
const PURPLE_LIGHT= '#EDE9FE';
const GREEN  = '#047857';
const ORANGE = '#B45309';
const RED    = '#B91C1C';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

const AVATAR_COLORS = ['#4F46E5', '#0F766E', '#B45309', '#B91C1C', '#6D28D9', '#047857', '#0369A1'];
function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < (name?.length ?? 0); i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}
function initials(name: string) {
  return (name ?? '?').split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
}
function healthDotColor(status: string | null) {
  switch (status) {
    case 'STABLE':   return GREEN;
    case 'MONITOR':  return ORANGE;
    case 'CRITICAL': return RED;
    default:         return '#6B7280';
  }
}
// A null status means the patient has not shared their records with this caregiver (the backend redacts it).
function healthLabel(status: string | null) {
  if (!status) return 'Not shared';
  return status.charAt(0) + status.slice(1).toLowerCase();
}
function healthPillColors(status: string | null) {
  switch (status) {
    case 'STABLE':   return { bg: '#D1FAE5', text: GREEN };
    case 'MONITOR':  return { bg: '#FEF3C7', text: ORANGE };
    case 'CRITICAL': return { bg: '#FEE2E2', text: RED };
    default:         return { bg: '#F3F4F6', text: GRAY };
  }
}
function accessLabel(level: string) {
  switch (level) {
    case 'FULL_ACCESS': return 'Full Access';
    case 'VIEW_ONLY':   return 'View Only';
    case 'EDIT_ONLY':   return 'Edit Only';
    case 'CUSTOM':      return 'Custom';
    default:            return level;
  }
}
function calcAge(dob: string | null) {
  const years = ageFromDateOnly(dob);
  return years === null ? null : `${years} yrs`;
}
function consultationTime(c: CaregiverConsultation) {
  return new Date(c.completedAt ?? c.startedAt ?? c.createdAt ?? 0).getTime() || 0;
}

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
    <View
      style={[styles.statCard, { flex: 1, minWidth: 0, borderRadius: s(12), padding: s(14) }]}
      accessible
      accessibilityLabel={[label, value, sub].filter(Boolean).join(', ')}
    >
      <View style={[styles.statIconBg, { backgroundColor: iconBg, width: s(38), height: s(38), borderRadius: s(10), marginBottom: s(8) }]}>
        <Ionicons name={icon} size={s(18)} color={iconColor} importantForAccessibility="no" />
      </View>
      <Text style={[styles.statLabel, { fontSize: s(10) }]} numberOfLines={1}>{label}</Text>
      <Text style={[styles.statValue, { fontSize: s(22) }]} numberOfLines={1}>{value}</Text>
      <Text style={[styles.statSub,   { fontSize: s(10), color: subColor }]} numberOfLines={1}>{sub}</Text>
    </View>
  );
}

// ─── Card Wrapper ─────────────────────────────────────────────────────────────

function Card({ title, s, children }: {
  title: string; s: (n: number) => number; children: React.ReactNode;
}) {
  return (
    <View style={[styles.card, { borderRadius: s(14), padding: s(14), marginBottom: s(14) }]}>
      <View style={[styles.cardHeader, { marginBottom: s(12) }]}>
        <Text style={[styles.cardTitle, { fontSize: s(14) }]} accessibilityRole="header">{title}</Text>
      </View>
      {children}
    </View>
  );
}

// ─── Schedule Row ─────────────────────────────────────────────────────────────

function ScheduleRow({ appt, person, s }: { appt: CaregiverAppointment; person: string; s: (n: number) => number }) {
  const date    = new Date(appt.scheduledAt);
  const time    = date.toLocaleTimeString('en-ZW', { hour: '2-digit', minute: '2-digit' });
  const day     = date.toLocaleDateString('en-ZW', { weekday: 'short', day: 'numeric', month: 'short' });
  const isVideo = appt.type === 'VIDEO' && (appt.status === 'SCHEDULED' || appt.status === 'RESCHEDULED');
  return (
    <View style={{ gap: s(6) }}>
      <View style={[styles.scheduleRow, { gap: s(8) }]}>
        <View
          style={{ width: s(38), height: s(38), borderRadius: s(19), backgroundColor: avatarColor(person), alignItems: 'center', justifyContent: 'center' }}
          importantForAccessibility="no-hide-descendants"
          accessibilityElementsHidden
        >
          <Text style={{ color: WHITE, fontSize: s(13), fontWeight: '700' }}>{initials(person)}</Text>
        </View>
        <View style={{ flex: 1, minWidth: 0 }}>
          <Text style={[styles.cardTitle, { fontSize: s(12) }]} numberOfLines={1}>{appt.reasonForVisit ?? 'Appointment'}</Text>
          <Text style={[styles.lovedOneMeta, { fontSize: s(11) }]} numberOfLines={1}>{person}</Text>
          <Text style={[styles.lovedOneMeta, { fontSize: s(10) }]} numberOfLines={1}>
            {day}{appt.status === 'RESCHEDULED' ? ' • Rescheduled' : ''}
          </Text>
        </View>
        <View style={[styles.timePill, { borderRadius: s(6), paddingHorizontal: s(7), paddingVertical: s(3), borderColor: BORDER, flexShrink: 0 }]}>
          <Text style={[{ fontSize: s(11), fontWeight: '700', color: TEXT }]} numberOfLines={1}>{time}</Text>
        </View>
      </View>
      {isVideo && (
        <Text style={{ fontSize: s(10), color: GRAY, marginLeft: s(46) }}>Join video consultations from the CareNexus web app.</Text>
      )}
    </View>
  );
}

// ─── Consultation Row ─────────────────────────────────────────────────────────

function ConsultationRow({ c, person, doctor, s }: {
  c: CaregiverConsultation; person: string; doctor: string | null; s: (n: number) => number;
}) {
  // A timestamp (not a date-only value), so it is shown in the device's time zone
  const t    = consultationTime(c);
  const when = t ? new Date(t).toLocaleDateString('en-ZW', { day: 'numeric', month: 'short' }) : '';
  const meta = [person, doctor].filter(Boolean).join(' • ');
  return (
    <View style={[styles.scheduleRow, { gap: s(10) }]} accessible accessibilityLabel={[`Consultation: ${c.chiefComplaint || c.diagnosis || 'completed'}`, meta, when].filter(Boolean).join(', ')}>
      <View style={[styles.statIconBg, { backgroundColor: PURPLE_LIGHT, width: s(34), height: s(34), borderRadius: s(10) }]}>
        <Ionicons name="document-text-outline" size={s(16)} color={PURPLE} importantForAccessibility="no" />
      </View>
      <View style={{ flex: 1, minWidth: 0 }}>
        <Text style={[styles.cardTitle, { fontSize: s(12) }]} numberOfLines={1}>{c.chiefComplaint || c.diagnosis || 'Consultation completed'}</Text>
        {meta ? <Text style={[styles.lovedOneMeta, { fontSize: s(11) }]} numberOfLines={1}>{meta}</Text> : null}
      </View>
      {when ? <Text style={[styles.lovedOneMeta, { fontSize: s(10), flexShrink: 0 }]}>{when}</Text> : null}
    </View>
  );
}

// ─── Main ─────────────────────────────────────────────────────────────────────

const SHOW_LIMIT = 3;

const UPCOMING_LIMIT = 5;

const CONSULTATIONS_LIMIT = 3;

const RECORD_LINKS: { key: string; label: string; icon: IoniconsName }[] = [
  { key: 'medications',    label: 'Medications',         icon: 'medical-outline' },
  { key: 'documents',      label: 'Documents & Results', icon: 'flask-outline' },
  { key: 'health-summary', label: 'Health Summary',      icon: 'heart-outline' },
  { key: 'care-plans',     label: 'Care Plans',          icon: 'clipboard-outline' },
];

export function PatientProxyMainDashboardMobile({ onNavigate, onSelectPatient }: { onNavigate?: (key: string) => void; onSelectPatient?: (dep: CaregiverDependent) => void }) {
  const { s, sh } = useScale();
  const { dependents, loading: depsLoading, error: depsError, refetch: refetchDeps } = useCaregiverDependents();
  const { appointments, consultations, loading: actLoading, error: actError, refetch: refetchActivity } = useCaregiverActivity();
  const { doctors } = useDoctors();
  const [expanded, setExpanded] = useState(false);

  const nameFor = (patientSystemId: string | null, fallback: string | null) =>
    dependents.find(d => d.patientSystemId === patientSystemId)?.patientName ?? fallback ?? 'Your loved one';

  const upcoming = useMemo(() => {
    const now = Date.now();
    return appointments
      .filter(a => (a.status === 'SCHEDULED' || a.status === 'RESCHEDULED') && new Date(a.scheduledAt).getTime() > now)
      .sort((a, b) => new Date(a.scheduledAt).getTime() - new Date(b.scheduledAt).getTime());
  }, [appointments]);

  // Only finalised consultations are shown (the backend already filters; this mirrors isPatientVisibleConsultation).
  const recentConsultations = useMemo(
    () => consultations
      .filter(c => c.status === 'COMPLETED')
      .sort((a, b) => consultationTime(b) - consultationTime(a))
      .slice(0, CONSULTATIONS_LIMIT),
    [consultations],
  );
  const doctorName = (doctorSystemId: string | null) => {
    if (!doctorSystemId) return null;
    const d = doctors.find(doc => doc.doctor_system_id === doctorSystemId);
    return d ? tidyDoctorName(d.full_name) || null : null;
  };

  const apptSub = upcoming.length > 0
    ? new Date(upcoming[0].scheduledAt).toLocaleDateString('en-ZW', { weekday: 'short', day: 'numeric', month: 'short' })
    : 'No upcoming';

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={{ padding: s(14), paddingBottom: sh(90) }} showsVerticalScrollIndicator={false}>

      {/* Stat cards */}
      <View style={{ flexDirection: 'row', gap: s(10), marginBottom: s(16) }}>
        <StatCard
          icon="calendar-outline" iconBg="#EDE9FE" iconColor={PURPLE}
          label="Upcoming Appts"
          value={actLoading ? '…' : actError ? '—' : String(upcoming.length)}
          sub={actLoading ? '' : actError ? 'Unavailable' : apptSub}
        />
        <StatCard
          icon="people-outline" iconBg="#D1FAE5" iconColor={GREEN}
          label="Loved Ones"
          value={depsLoading ? '…' : depsError ? '—' : String(dependents.length)}
          sub={depsLoading ? '' : depsError ? 'Unavailable' : 'Linked'}
        />
      </View>

      {/* Loved One Overview */}
      <Card title="Loved One Overview" s={s}>
        {depsLoading ? (
          <ActivityIndicator size="small" color={PURPLE} style={{ marginVertical: s(12) }} accessibilityLabel="Loading loved ones" />
        ) : depsError ? (
          <ErrorState compact message={depsError} onRetry={refetchDeps} />
        ) : dependents.length === 0 ? (
          <Text style={{ fontSize: s(13), color: GRAY, textAlign: 'center', paddingVertical: s(12) }}>
            No loved ones linked yet.
          </Text>
        ) : (
          <>
            {(expanded ? dependents : dependents.slice(0, SHOW_LIMIT)).map((dep, i) => {
              const name      = dep.patientName ?? 'Unknown';
              const dotColor  = healthDotColor(dep.patientHealthStatus);
              const health    = healthLabel(dep.patientHealthStatus);
              const hc        = healthPillColors(dep.patientHealthStatus);
              const access    = accessLabel(dep.accessLevel);
              const age       = calcAge(dep.patientDateOfBirth);
              const gender    = dep.patientGender
                ? dep.patientGender.charAt(0) + dep.patientGender.slice(1).toLowerCase()
                : null;
              const rel       = dep.relationship
                ? dep.relationship.charAt(0).toUpperCase() + dep.relationship.slice(1)
                : '';

              return (
                <View key={dep.accessId}>
                  {i > 0 && <View style={[styles.sep, { marginVertical: s(10) }]} />}
                  <TouchableOpacity
                    style={[styles.lovedOneRow, { gap: s(10), borderRadius: s(10), padding: s(10) }]}
                    activeOpacity={0.8}
                    onPress={() => onSelectPatient?.(dep)}
                    accessibilityRole="button"
                    accessibilityLabel={`Open profile for ${name}. ${access}. Health: ${health}`}
                  >
                    <View importantForAccessibility="no-hide-descendants" accessibilityElementsHidden>
                      <View style={{
                        width: s(46), height: s(46), borderRadius: s(23),
                        backgroundColor: avatarColor(name), alignItems: 'center', justifyContent: 'center',
                      }}>
                        <Text style={{ color: '#fff', fontSize: s(16), fontWeight: '700' }}>{initials(name)}</Text>
                      </View>
                      <View style={[styles.dot, { backgroundColor: dotColor, width: s(11), height: s(11), borderRadius: s(6) }]} />
                    </View>
                    <View style={{ flex: 1, minWidth: 0 }}>
                      <Text style={[styles.lovedOneName, { fontSize: s(13) }]} numberOfLines={1}>{name}</Text>
                      <Text style={{ fontSize: s(10), color: PURPLE, fontWeight: '500', marginBottom: s(2) }} numberOfLines={1}>{rel}</Text>
                      <View style={[styles.accessBadge, {
                        backgroundColor: dep.accessLevel === 'FULL_ACCESS' ? '#D1FAE5' : '#FEF3C7',
                        borderRadius: s(20), paddingHorizontal: s(7), paddingVertical: s(2), marginBottom: s(3),
                      }]}>
                        <Text style={[styles.accessText, {
                          fontSize: s(10),
                          color: dep.accessLevel === 'FULL_ACCESS' ? GREEN : ORANGE,
                        }]} numberOfLines={1}>{access}</Text>
                      </View>
                      {(age || gender) && (
                        <Text style={[styles.lovedOneMeta, { fontSize: s(11) }]} numberOfLines={1}>
                          {[age, gender].filter(Boolean).join(' • ')}
                        </Text>
                      )}
                    </View>
                    <View style={{ alignItems: 'flex-end', gap: s(4), flexShrink: 0 }}>
                      <View style={[styles.healthPill, {
                        backgroundColor: hc.bg,
                        borderRadius: s(8), paddingHorizontal: s(8), paddingVertical: s(3),
                      }]}>
                        <Text style={{ fontSize: s(11), fontWeight: '600', color: hc.text }} numberOfLines={1}>{health}</Text>
                      </View>
                    </View>
                    <Ionicons name="chevron-forward" size={s(14)} color={GRAY} importantForAccessibility="no" />
                  </TouchableOpacity>
                  <View style={{ paddingHorizontal: s(10), paddingTop: s(6) }}>
                    <MobileMessageCareTeamButton dependent={dep} compact />
                  </View>
                </View>
              );
            })}
            {dependents.length > SHOW_LIMIT && (
              <TouchableOpacity
                style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: s(5), paddingVertical: s(10) }}
                activeOpacity={0.7}
                onPress={() => setExpanded(e => !e)}
                accessibilityRole="button"
                accessibilityState={{ expanded }}
                accessibilityLabel={expanded ? 'Show fewer loved ones' : `Show ${dependents.length - SHOW_LIMIT} more loved ones`}
              >
                <Ionicons name={expanded ? 'chevron-up' : 'chevron-down'} size={s(13)} color={PURPLE} importantForAccessibility="no" />
                <Text style={[styles.cardAction, { fontSize: s(12) }]}>
                  {expanded ? 'Show less' : `+${dependents.length - SHOW_LIMIT} more`}
                </Text>
              </TouchableOpacity>
            )}
          </>
        )}
        <TouchableOpacity
          style={[styles.addLovedOneBtn, { borderRadius: s(8), paddingVertical: s(9), paddingHorizontal: s(12), gap: s(6), marginTop: s(8) }]}
          activeOpacity={0.8}
          onPress={() => onNavigate?.('add-dependent')}
          accessibilityRole="button"
        >
          <Ionicons name="add" size={s(16)} color={PURPLE} importantForAccessibility="no" />
          <Text style={[styles.cardAction, { fontSize: s(13), fontWeight: '500' }]}>
            {!depsLoading && !depsError && dependents.length === 0 ? 'Add a Loved One' : 'Add Another Loved One'}
          </Text>
        </TouchableOpacity>
      </Card>

      {/* Upcoming appointments — from caregiver activity */}
      <Card title="Upcoming Appointments" s={s}>
        {actLoading ? (
          <ActivityIndicator size="small" color={PURPLE} style={{ marginVertical: s(12) }} accessibilityLabel="Loading appointments" />
        ) : actError ? (
          <ErrorState compact message={actError} onRetry={refetchActivity} />
        ) : upcoming.length === 0 ? (
          <EmptyState compact icon="calendar-outline" title="No upcoming appointments" />
        ) : (
          upcoming.slice(0, UPCOMING_LIMIT).map((a, i) => (
            <View key={a.appointmentId}>
              {i > 0 && <View style={[styles.sep, { marginVertical: s(10) }]} />}
              <ScheduleRow appt={a} person={nameFor(a.patientSystemId, a.patientName)} s={s} />
            </View>
          ))
        )}
        {upcoming.length > UPCOMING_LIMIT && (
          <Text style={[styles.lovedOneMeta, { fontSize: s(11), textAlign: 'center', marginTop: s(10) }]}>
            +{upcoming.length - UPCOMING_LIMIT} more upcoming
          </Text>
        )}
        {onNavigate && (
          <TouchableOpacity
            style={[styles.linkRow, { gap: s(5), paddingTop: s(10), marginTop: s(8) }]}
            activeOpacity={0.7}
            onPress={() => onNavigate('schedule')}
            accessibilityRole="button"
            accessibilityLabel="View the full schedule"
          >
            <Text style={[styles.cardAction, { fontSize: s(12) }]}>View full schedule</Text>
            <Ionicons name="chevron-forward" size={s(13)} color={PURPLE} importantForAccessibility="no" />
          </TouchableOpacity>
        )}
      </Card>

      {/* Recent consultations — from caregiver activity */}
      <Card title="Recent Consultations" s={s}>
        {actLoading ? (
          <ActivityIndicator size="small" color={PURPLE} style={{ marginVertical: s(12) }} accessibilityLabel="Loading recent consultations" />
        ) : actError ? (
          <ErrorState compact message={actError} onRetry={refetchActivity} />
        ) : recentConsultations.length === 0 ? (
          <EmptyState compact icon="document-text-outline" title="No recent consultations" />
        ) : (
          recentConsultations.map((c, i) => (
            <View key={c.consultationId}>
              {i > 0 && <View style={[styles.sep, { marginVertical: s(10) }]} />}
              <ConsultationRow c={c} person={nameFor(c.patientSystemId, c.patientName)} doctor={doctorName(c.doctorSystemId)} s={s} />
            </View>
          ))
        )}
      </Card>

      {/* Records: per-loved-one screens, each filtered by what that loved one shares */}
      {onNavigate && (
        <Card title="Records" s={s}>
          {RECORD_LINKS.map((l, i) => (
            <View key={l.key}>
              {i > 0 && <View style={[styles.sep, { marginVertical: s(4) }]} />}
              <TouchableOpacity
                style={[styles.scheduleRow, { gap: s(10), paddingVertical: s(6) }]}
                activeOpacity={0.7}
                onPress={() => onNavigate(l.key)}
                accessibilityRole="button"
                accessibilityLabel={l.label}
              >
                <View style={[styles.statIconBg, { backgroundColor: PURPLE_BG, width: s(32), height: s(32), borderRadius: s(9) }]}>
                  <Ionicons name={l.icon} size={s(15)} color={PURPLE} importantForAccessibility="no" />
                </View>
                <Text style={[styles.cardTitle, { fontSize: s(13), flex: 1 }]} numberOfLines={1}>{l.label}</Text>
                <Ionicons name="chevron-forward" size={s(14)} color={GRAY} importantForAccessibility="no" />
              </TouchableOpacity>
            </View>
          ))}
        </Card>
      )}

      {/* Proxy Access Banner */}
      {dependents.length > 0 && (
      <View style={[styles.banner, { borderRadius: s(14), padding: s(14), gap: s(12) }]}>
        <View style={[styles.bannerIcon, { width: s(48), height: s(48), borderRadius: s(24) }]}>
          <Ionicons name="shield-outline" size={s(24)} color={PURPLE} importantForAccessibility="no" />
        </View>
        <View style={{ flex: 1 }}>
          <Text style={[styles.updateTitle, { fontSize: s(13), marginBottom: s(4) }]}>
            {dependents.length === 1
              ? `${accessLabel(dependents[0].accessLevel)} proxy for ${dependents[0].patientName ?? 'your loved one'}`
              : `Proxy for ${dependents.length} loved ones`}
          </Text>
          <Text style={[styles.lovedOneMeta, { fontSize: s(11), lineHeight: s(16) }]}>
            Tap a loved one to see their health profile and the access they have granted you.
          </Text>
        </View>
      </View>
      )}

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
  addLovedOneBtn: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', borderWidth: 1, borderColor: PURPLE, backgroundColor: WHITE },

  lovedOneRow:   { flexDirection: 'row', alignItems: 'center', backgroundColor: BG },
  dot:           { position: 'absolute', bottom: 1, right: 1, borderWidth: 2, borderColor: BG },
  lovedOneName:  { fontWeight: '700', color: TEXT, marginBottom: 2 },
  accessBadge:   { alignSelf: 'flex-start' },
  accessText:    { fontWeight: '600' },
  lovedOneMeta:  { color: GRAY },
  healthPill:    {},

  scheduleRow: { flexDirection: 'row', alignItems: 'center' },
  linkRow:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: BORDER },
  timePill:    { borderWidth: 1, alignItems: 'center' },
  videoBtn:    { borderWidth: 1, borderColor: GREEN + '50', backgroundColor: GREEN + '15', alignItems: 'center', justifyContent: 'center' },



  updateTitle: { fontWeight: '600', color: TEXT },
  banner:      { flexDirection: 'row', alignItems: 'center', backgroundColor: PURPLE_BG, borderWidth: 1, borderColor: PURPLE_LIGHT },
  bannerIcon:  { backgroundColor: WHITE, borderWidth: 1, borderColor: PURPLE_LIGHT, alignItems: 'center', justifyContent: 'center' },
});
