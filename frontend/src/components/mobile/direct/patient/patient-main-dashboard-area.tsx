import { Ionicons } from '@expo/vector-icons';
import { ScrollView, StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';
import { isUpcomingAppointment, useAppointments, type Appointment } from '@/hooks/use-appointments';
import { usePatientProfile } from '@/hooks/use-patient-profile';
import { usePrescriptions, type Prescription } from '@/hooks/use-prescriptions';

const TEAL       = '#0D9488';
const NAVY       = '#1E3A5F';
const GRAY       = '#6B7280';
const GRAY_LIGHT = '#F9FAFB';
const BORDER     = '#E5E7EB';
const WHITE      = '#FFFFFF';
const TEXT       = '#111827';
const RED        = '#EF4444';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

function useScale() {
  const { width, height } = useWindowDimensions();
  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));
  return { s, sh, width, height };
}

// ── Shared section states ────────────────────────────────────────────

function SectionMessage({ text }: { text: string }) {
  const { s } = useScale();
  return <Text style={[styles.listDate, { fontSize: s(12), padding: s(8) }]}>{text}</Text>;
}

function SectionError({ message, onRetry }: { message: string; onRetry: () => void }) {
  const { s } = useScale();
  return (
    <View style={[styles.errorRow, { gap: s(8), padding: s(10), borderRadius: s(8) }]}>
      <Ionicons name="alert-circle-outline" size={s(15)} color={RED} />
      <Text style={[styles.errorText, { fontSize: s(12) }]}>{message}</Text>
      <TouchableOpacity onPress={onRetry} activeOpacity={0.7} accessibilityRole="button" accessibilityLabel="Retry loading">
        <Text style={[styles.retryText, { fontSize: s(12) }]}>Retry</Text>
      </TouchableOpacity>
    </View>
  );
}

// ── Quick Actions ────────────────────────────────────────────────────

type QuickActionKey = 'book' | 'appointments' | 'consultations' | 'records' | 'lab' | 'imaging' | 'prescriptions';
type QuickAction = { key: QuickActionKey; label: string; icon: IoniconsName; iconBg: string; iconColor: string };

const quickActions: QuickAction[] = [
  { key: 'book',          label: 'Book Appointment',      icon: 'calendar-outline',        iconBg: '#EEE8FF', iconColor: '#7C5CFC' },
  { key: 'appointments',  label: 'My Appointments',       icon: 'calendar-number-outline', iconBg: '#FFF3E6', iconColor: '#F4A124' },
  { key: 'consultations', label: 'Consultation History',  icon: 'document-text-outline',   iconBg: '#EEF2FF', iconColor: '#4F46E5' },
  { key: 'records',       label: 'Medical Records',       icon: 'folder-open-outline',     iconBg: '#F0FDF4', iconColor: '#10B981' },
  { key: 'lab',           label: 'Lab Results',           icon: 'flask-outline',           iconBg: '#EFF6FF', iconColor: '#3B82F6' },
  { key: 'imaging',       label: 'Imaging Results',       icon: 'image-outline',           iconBg: '#F5F3FF', iconColor: '#8B5CF6' },
  { key: 'prescriptions', label: 'Prescriptions',         icon: 'medkit-outline',          iconBg: '#ECFDF5', iconColor: '#10B981' },
];

type NavProps = {
  onBookAppointment?: () => void;
  onViewConsultations?: () => void;
  onViewPrescriptions?: () => void;
  onViewAppointments?: () => void;
  onViewRecords?: () => void;
  onViewLabResults?: () => void;
  onViewImagingResults?: () => void;
};

function QuickActions(props: NavProps) {
  const { s, width } = useScale();
  const cardWidth   = width * 0.42;
  const iconBox     = s(40);
  const gap         = s(10);

  const handlers: Record<QuickActionKey, (() => void) | undefined> = {
    book:          props.onBookAppointment,
    appointments:  props.onViewAppointments,
    consultations: props.onViewConsultations,
    records:       props.onViewRecords,
    lab:           props.onViewLabResults,
    imaging:       props.onViewImagingResults,
    prescriptions: props.onViewPrescriptions,
  };

  return (
    <ScrollView
      horizontal nestedScrollEnabled
      showsHorizontalScrollIndicator={false}
      contentContainerStyle={{ gap, paddingVertical: s(2) }}
    >
      {quickActions.map((a) => (
        <TouchableOpacity
          key={a.key}
          style={[styles.quickCard, { width: cardWidth, padding: s(12), borderRadius: s(12), gap: s(6) }]}
          activeOpacity={0.8}
          onPress={() => handlers[a.key]?.()}
          accessibilityRole="button"
          accessibilityLabel={a.label}
        >
          <View style={[styles.iconBox, { width: iconBox, height: iconBox, borderRadius: iconBox / 2, backgroundColor: a.iconBg }]}>
            <Ionicons name={a.icon} size={s(20)} color={a.iconColor} />
          </View>
          <Text style={[styles.quickLabel, { fontSize: s(12) }]}>{a.label}</Text>
          <Ionicons name="arrow-forward" size={s(13)} color={GRAY} />
        </TouchableOpacity>
      ))}
    </ScrollView>
  );
}

// ── Welcome Banner ───────────────────────────────────────────────────

function WelcomeBanner() {
  const { s } = useScale();
  const avatarSize = s(60);

  return (
    <View style={[styles.banner, { padding: s(16), borderRadius: s(14), gap: s(12) }]}>
      <View
        style={[styles.iconBox, { width: avatarSize, height: avatarSize, borderRadius: avatarSize / 2, backgroundColor: '#E6F4F1' }]}
        accessibilityElementsHidden
        importantForAccessibility="no-hide-descendants"
      >
        <Ionicons name="heart-outline" size={s(28)} color={TEAL} />
      </View>
      <View style={styles.flex1}>
        <Text style={[styles.bannerTitle, { fontSize: s(14), marginBottom: s(4) }]}>
          Welcome to CareNexus Direct
        </Text>
        <Text style={[styles.bannerDesc, { fontSize: s(11), lineHeight: s(17) }]}>
          Book and manage appointments, review your prescriptions and results, and keep your health record in one place.
        </Text>
      </View>
      <View style={[styles.bannerDecor, { right: -s(16), bottom: -s(16) }]}>
        <View style={{ width: s(90), height: s(90), borderRadius: s(45), borderWidth: s(18), borderColor: TEAL }} />
      </View>
    </View>
  );
}

// ── Health Overview ──────────────────────────────────────────────────

type HealthMetric = { label: string; value: string; sub?: string; icon: IoniconsName; iconBg: string; iconColor: string; onPress?: () => void };

function summarise(items: string[]): string {
  if (items.length === 0) return 'None recorded';
  const shown = items.slice(0, 2).join(', ');
  return items.length > 2 ? `${shown} +${items.length - 2} more` : shown;
}

function HealthOverview({
  prescriptions, rxLoading, rxError, onRetryRx, onViewPrescriptions,
}: {
  prescriptions: Prescription[];
  rxLoading: boolean;
  rxError: string | null;
  onRetryRx: () => void;
  onViewPrescriptions?: () => void;
}) {
  const { s, width } = useScale();
  const cardWidth = width * 0.4;
  const iconBox   = s(32);
  const { profile, loading, error, refetch } = usePatientProfile();

  const activeRx = prescriptions.filter(p => p.status === 'ACTIVE');
  const metrics: HealthMetric[] = profile ? [
    { label: 'Conditions', value: String(profile.chronic_conditions.length), sub: summarise(profile.chronic_conditions), icon: 'heart-outline',   iconBg: '#E6F4F1', iconColor: TEAL },
    { label: 'Allergies',  value: String(profile.allergies.length),          sub: summarise(profile.allergies),          icon: 'warning-outline', iconBg: '#FEE2E2', iconColor: RED },
    {
      label: 'Medications',
      value: rxLoading ? '…' : rxError ? '—' : String(activeRx.length),
      sub:   rxError ? 'Could not load' : 'Active prescriptions',
      icon: 'medkit-outline', iconBg: '#E8F0FE', iconColor: '#4285F4',
      onPress: onViewPrescriptions,
    },
    { label: 'Blood Type', value: profile.blood_type ?? '—', sub: profile.blood_type ? undefined : 'Not recorded', icon: 'water-outline', iconBg: '#FEE2E2', iconColor: RED },
  ] : [];

  return (
    <View style={[styles.section, { padding: s(14), borderRadius: s(14) }]}>
      <View style={[styles.row, { marginBottom: s(10) }]}>
        <Text style={[styles.sectionTitle, { fontSize: s(14) }]}>Health Overview</Text>
      </View>
      {loading ? (
        <SectionMessage text="Loading your health summary…" />
      ) : error || !profile ? (
        <SectionError message={error ?? 'Could not load your health summary.'} onRetry={() => { refetch(); onRetryRx(); }} />
      ) : (
        <ScrollView horizontal nestedScrollEnabled showsHorizontalScrollIndicator={false}
          contentContainerStyle={{ gap: s(10), paddingVertical: s(2) }}>
          {metrics.map((m) => {
            const body = (
              <>
                <View style={[styles.iconBox, { width: iconBox, height: iconBox, borderRadius: iconBox / 2, backgroundColor: m.iconBg, marginBottom: s(4) }]}>
                  <Ionicons name={m.icon} size={s(16)} color={m.iconColor} />
                </View>
                <Text style={[styles.metricLabel, { fontSize: s(10) }]}>{m.label}</Text>
                <Text style={[styles.metricValue, { fontSize: s(20) }]}>{m.value}</Text>
                {m.sub ? <Text style={[styles.metricSub, { fontSize: s(10), lineHeight: s(15) }]} numberOfLines={2}>{m.sub}</Text> : null}
                {m.onPress ? <Text style={[styles.link, { fontSize: s(11) }]}>View details</Text> : null}
              </>
            );
            const style = [styles.metricCard, { width: cardWidth, padding: s(12), borderRadius: s(10), gap: s(3) }];
            return m.onPress ? (
              <TouchableOpacity key={m.label} style={style} onPress={m.onPress} activeOpacity={0.8} accessibilityRole="button" accessibilityLabel={`${m.label}: ${m.value}. View details`}>
                {body}
              </TouchableOpacity>
            ) : (
              <View key={m.label} style={style} accessible accessibilityLabel={`${m.label}: ${m.value}${m.sub ? `. ${m.sub}` : ''}`}>
                {body}
              </View>
            );
          })}
        </ScrollView>
      )}
    </View>
  );
}

// ── Recent Consultations ─────────────────────────────────────────────

const TYPE_ICON: Record<string, React.ComponentProps<typeof Ionicons>['name']> = {
  VIDEO:     'videocam-outline',
  IN_PERSON: 'business-outline',
  PHONE:     'call-outline',
};

function formatApptDate(iso: string) {
  const d = new Date(iso);
  return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }) +
    ' • ' + d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
}

function formatDay(iso: string) {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

function RecentConsultations({
  appointments, loading, error, onRetry, onViewConsultations,
}: {
  appointments: Appointment[];
  loading: boolean;
  error: string | null;
  onRetry: () => void;
  onViewConsultations?: () => void;
}) {
  const { s } = useScale();
  const iconBox = s(34);
  const recent = appointments.filter(a => a.status === 'COMPLETED').slice(0, 3);

  return (
    <View style={[styles.section, { padding: s(14), borderRadius: s(14) }]}>
      <View style={[styles.row, { marginBottom: s(10) }]}>
        <Text style={[styles.sectionTitle, { fontSize: s(14) }]}>Recent Consultations</Text>
        <TouchableOpacity onPress={onViewConsultations} accessibilityRole="link"><Text style={[styles.link, { fontSize: s(12) }]}>View all</Text></TouchableOpacity>
      </View>
      {loading ? (
        <SectionMessage text="Loading…" />
      ) : error ? (
        <SectionError message={error} onRetry={onRetry} />
      ) : recent.length === 0 ? (
        <SectionMessage text="No completed consultations yet." />
      ) : recent.map((a) => (
        <TouchableOpacity
          key={a.appointment_id}
          style={[styles.listRow, { gap: s(10), paddingVertical: s(10) }]}
          activeOpacity={0.7}
          onPress={onViewConsultations}
          accessibilityRole="button"
        >
          <View style={[styles.iconBox, { width: iconBox, height: iconBox, borderRadius: s(8), backgroundColor: '#E6F4F1' }]}>
            <Ionicons name={TYPE_ICON[a.type] ?? 'calendar-outline'} size={s(16)} color={TEAL} />
          </View>
          <View style={styles.flex1}>
            <Text style={[styles.listDate,  { fontSize: s(10) }]}>{formatApptDate(a.scheduled_at)}</Text>
            <Text style={[styles.listTitle, { fontSize: s(13) }]} numberOfLines={1}>{a.reason_for_visit ?? 'Consultation'}</Text>
            <Text style={[styles.listSub,   { fontSize: s(11) }]}>{a.doctor_name}{a.doctor_specialization ? ` (${a.doctor_specialization})` : ''}</Text>
          </View>
          <View style={[styles.badge, { paddingHorizontal: s(7), paddingVertical: s(3), borderRadius: s(6) }]}>
            <Text style={[styles.badgeText, { fontSize: s(10) }]}>Completed</Text>
          </View>
          <Ionicons name="chevron-forward" size={s(15)} color={GRAY} />
        </TouchableOpacity>
      ))}
      <TouchableOpacity style={[styles.viewAllRow, { paddingTop: s(10) }]} onPress={onViewConsultations} accessibilityRole="link">
        <Text style={[styles.link, { fontSize: s(12) }]}>View all consultations →</Text>
      </TouchableOpacity>
    </View>
  );
}

// ── Current Prescriptions ────────────────────────────────────────────

function LatestPrescriptions({
  prescriptions, loading, error, onRetry, onViewPrescriptions,
}: {
  prescriptions: Prescription[];
  loading: boolean;
  error: string | null;
  onRetry: () => void;
  onViewPrescriptions?: () => void;
}) {
  const { s } = useScale();
  const active = prescriptions.filter(p => p.status === 'ACTIVE').slice(0, 3);

  return (
    <View style={[styles.section, { padding: s(14), borderRadius: s(14) }]}>
      <View style={[styles.row, { marginBottom: s(10) }]}>
        <Text style={[styles.sectionTitle, { fontSize: s(14) }]}>Current Prescriptions</Text>
        <TouchableOpacity onPress={onViewPrescriptions} accessibilityRole="link"><Text style={[styles.link, { fontSize: s(12) }]}>View all</Text></TouchableOpacity>
      </View>
      {loading ? (
        <SectionMessage text="Loading…" />
      ) : error ? (
        <SectionError message={error} onRetry={onRetry} />
      ) : active.length === 0 ? (
        <SectionMessage text="You have no active prescriptions." />
      ) : active.map((p) => (
        <TouchableOpacity
          key={p.prescription_id}
          style={[styles.listRow, { gap: s(10), paddingVertical: s(10) }]}
          activeOpacity={0.7}
          onPress={onViewPrescriptions}
          accessibilityRole="button"
        >
          <Ionicons name="document-outline" size={s(20)} color={GRAY} />
          <View style={styles.flex1}>
            <Text style={[styles.listTitle, { fontSize: s(13) }]}>{p.medication_name}{p.dosage ? ` ${p.dosage}` : ''}</Text>
            {!!p.frequency && <Text style={[styles.listSub, { fontSize: s(11) }]}>{p.frequency}</Text>}
            <Text style={[styles.listDate,  { fontSize: s(10) }]}>
              {formatDay(p.prescribed_date) ? `Prescribed ${formatDay(p.prescribed_date)}` : ''}{p.doctor_name ? ` • ${p.doctor_name}` : ''}
            </Text>
          </View>
          <View style={[styles.badge, { backgroundColor: '#E6F4F1', paddingHorizontal: s(7), paddingVertical: s(3), borderRadius: s(6) }]}>
            <Text style={[styles.badgeText, { fontSize: s(10), color: TEAL }]}>Active</Text>
          </View>
        </TouchableOpacity>
      ))}
      <TouchableOpacity style={[styles.viewAllRow, { paddingTop: s(10) }]} onPress={onViewPrescriptions} accessibilityRole="link">
        <Text style={[styles.link, { fontSize: s(12) }]}>View all prescriptions →</Text>
      </TouchableOpacity>
    </View>
  );
}

// ── Upcoming Appointments ────────────────────────────────────────────

function UpcomingAppointments({
  appointments, loading, error, onRetry, onViewAppointments, onBookAppointment,
}: {
  appointments: Appointment[];
  loading: boolean;
  error: string | null;
  onRetry: () => void;
  onViewAppointments?: () => void;
  onBookAppointment?: () => void;
}) {
  const { s } = useScale();
  const iconBox = s(32);
  const upcoming = appointments
    .filter(isUpcomingAppointment)
    .sort((a, b) => new Date(a.scheduled_at).getTime() - new Date(b.scheduled_at).getTime())
    .slice(0, 3);

  return (
    <View style={[styles.section, { padding: s(14), borderRadius: s(14) }]}>
      <View style={[styles.row, { marginBottom: s(10) }]}>
        <Text style={[styles.sectionTitle, { fontSize: s(14) }]}>Upcoming Appointments</Text>
        <TouchableOpacity onPress={onViewAppointments} accessibilityRole="link"><Text style={[styles.link, { fontSize: s(12) }]}>Manage</Text></TouchableOpacity>
      </View>
      {loading ? (
        <SectionMessage text="Loading…" />
      ) : error ? (
        <SectionError message={error} onRetry={onRetry} />
      ) : upcoming.length === 0 ? (
        <View style={[styles.row, { padding: s(8) }]}>
          <Text style={[styles.listDate, { fontSize: s(12), flex: 1 }]}>You have no upcoming appointments.</Text>
          {onBookAppointment && (
            <TouchableOpacity onPress={onBookAppointment} accessibilityRole="button"><Text style={[styles.link, { fontSize: s(12) }]}>Book one →</Text></TouchableOpacity>
          )}
        </View>
      ) : upcoming.map((a) => (
        <TouchableOpacity
          key={a.appointment_id}
          style={[styles.listRow, { gap: s(10), paddingVertical: s(10) }]}
          activeOpacity={0.8}
          onPress={onViewAppointments}
          accessibilityRole="button"
          accessibilityLabel={`Appointment with ${a.doctor_name} on ${formatApptDate(a.scheduled_at)}`}
        >
          <View style={[styles.iconBox, { width: iconBox, height: iconBox, borderRadius: s(8), backgroundColor: '#E8F0FE' }]}>
            <Ionicons name={TYPE_ICON[a.type] ?? 'calendar-outline'} size={s(16)} color="#4285F4" />
          </View>
          <View style={styles.flex1}>
            <Text style={[styles.listTitle, { fontSize: s(13) }]} numberOfLines={1}>{a.doctor_name}</Text>
            <Text style={[styles.listSub,   { fontSize: s(11) }]}>{formatApptDate(a.scheduled_at)}</Text>
          </View>
          <Ionicons name="chevron-forward" size={s(15)} color={GRAY} />
        </TouchableOpacity>
      ))}
    </View>
  );
}

// ── Main export ──────────────────────────────────────────────────────

export function PatientMainDashboardAreaMobile(props: NavProps) {
  const { s } = useScale();
  const pad = s(16);
  const appts = useAppointments();
  const rx    = usePrescriptions();

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={{ padding: pad, gap: pad }}
      showsVerticalScrollIndicator={false}
      nestedScrollEnabled
    >
      <QuickActions {...props} />
      <WelcomeBanner />
      <HealthOverview
        prescriptions={rx.prescriptions}
        rxLoading={rx.loading}
        rxError={rx.error}
        onRetryRx={rx.refetch}
        onViewPrescriptions={props.onViewPrescriptions}
      />
      <UpcomingAppointments
        appointments={appts.appointments}
        loading={appts.loading}
        error={appts.error}
        onRetry={appts.refetch}
        onViewAppointments={props.onViewAppointments}
        onBookAppointment={props.onBookAppointment}
      />
      <RecentConsultations
        appointments={appts.appointments}
        loading={appts.loading}
        error={appts.error}
        onRetry={appts.refetch}
        onViewConsultations={props.onViewConsultations}
      />
      <LatestPrescriptions
        prescriptions={rx.prescriptions}
        loading={rx.loading}
        error={rx.error}
        onRetry={rx.refetch}
        onViewPrescriptions={props.onViewPrescriptions}
      />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container:    { flex: 1, backgroundColor: GRAY_LIGHT },
  flex1:        { flex: 1 },
  row:          { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  iconBox:      { alignItems: 'center', justifyContent: 'center' },

  quickCard:    { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  quickLabel:   { fontWeight: '700', color: TEXT, lineHeight: 17 },

  banner:       { backgroundColor: '#F0F4FF', flexDirection: 'row', alignItems: 'center', borderWidth: 1, borderColor: BORDER, overflow: 'hidden' },
  bannerTitle:  { fontWeight: '700', color: NAVY },
  bannerDesc:   { color: GRAY },
  bannerDecor:  { position: 'absolute', opacity: 0.12 },

  section:      { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  sectionTitle: { fontWeight: '700', color: TEXT },
  link:         { color: '#4F6EF7', fontWeight: '500' },

  metricCard:   { borderWidth: 1, borderColor: BORDER },
  metricLabel:  { color: GRAY },
  metricValue:  { fontWeight: '700', color: TEXT },
  metricSub:    { color: GRAY },

  listRow:      { flexDirection: 'row', alignItems: 'center', borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: BORDER },
  listDate:     { color: GRAY },
  listTitle:    { fontWeight: '600', color: TEXT },
  listSub:      { color: GRAY },
  badge:        { backgroundColor: '#F3F4F6' },
  badgeText:    { color: GRAY, fontWeight: '600' },
  viewAllRow:   { alignItems: 'center' },

  errorRow:     { flexDirection: 'row', alignItems: 'center', backgroundColor: '#FEF2F2' },
  errorText:    { flex: 1, color: RED },
  retryText:    { fontWeight: '700', color: RED, textDecorationLine: 'underline' },
});
