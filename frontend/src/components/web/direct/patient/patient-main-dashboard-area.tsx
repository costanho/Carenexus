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

// ── Shared section states ────────────────────────────────────────────

function SectionLoading({ label = 'Loading…' }: { label?: string }) {
  return <Text style={[styles.consultDate, { padding: 12 }]}>{label}</Text>;
}

function SectionError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <View style={styles.errorRow}>
      <Ionicons name="alert-circle-outline" size={16} color={RED} />
      <Text style={styles.errorText}>{message}</Text>
      <TouchableOpacity onPress={onRetry} activeOpacity={0.7} accessibilityRole="button" accessibilityLabel="Retry loading">
        <Text style={styles.retryText}>Retry</Text>
      </TouchableOpacity>
    </View>
  );
}

// ── Quick Actions ────────────────────────────────────────────────────

type QuickAction = { key: string; label: string; desc: string; icon: IoniconsName; iconBg: string; iconColor: string };

const quickActions: QuickAction[] = [
  { key: 'book-appointment', label: 'Book\nAppointment',   desc: 'Schedule a new appointment',         icon: 'calendar-outline',        iconBg: '#EEE8FF', iconColor: '#7C5CFC' },
  { key: 'my-appointments',  label: 'My\nAppointments',    desc: 'Reschedule or cancel a visit',       icon: 'calendar-number-outline', iconBg: '#E6F4F1', iconColor: TEAL },
  { key: 'prescriptions',    label: 'View\nPrescriptions', desc: 'See your current prescriptions',     icon: 'medkit-outline',          iconBg: '#FFF3E6', iconColor: '#F4A124' },
  { key: 'lab-results',      label: 'Lab\nResults',        desc: 'Review your latest test results',    icon: 'flask-outline',           iconBg: '#E8F0FE', iconColor: '#4285F4' },
  { key: 'medical-records',  label: 'Medical\nRecords',    desc: 'Browse your health record',          icon: 'folder-open-outline',     iconBg: '#EEE8FF', iconColor: '#7C5CFC' },
];

function QuickActions({ onAction }: { onAction: (key: string) => void }) {
  const { width } = useWindowDimensions();
  const isSmall = width < 768;

  if (isSmall) {
    return (
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 10, paddingVertical: 2 }}>
        {quickActions.map((a) => (
          <TouchableOpacity
            key={a.key}
            style={[styles.quickCard, { width: 140 }]}
            activeOpacity={0.8}
            onPress={() => onAction(a.key)}
            accessibilityRole="button"
            accessibilityLabel={a.label.replace('\n', ' ')}
          >
            <View style={[styles.quickIconBg, { backgroundColor: a.iconBg }]}>
              <Ionicons name={a.icon} size={20} color={a.iconColor} />
            </View>
            <Text style={styles.quickLabel}>{a.label}</Text>
            <Text style={styles.quickArrow}>→</Text>
          </TouchableOpacity>
        ))}
      </ScrollView>
    );
  }

  return (
    <View style={styles.quickRow}>
      {quickActions.map((a) => (
        <TouchableOpacity
          key={a.key}
          style={styles.quickCard}
          activeOpacity={0.8}
          onPress={() => onAction(a.key)}
          accessibilityRole="button"
          accessibilityLabel={`${a.label.replace('\n', ' ')}. ${a.desc}`}
        >
          <View style={[styles.quickIconBg, { backgroundColor: a.iconBg }]}>
            <Ionicons name={a.icon} size={22} color={a.iconColor} />
          </View>
          <Text style={styles.quickLabel}>{a.label}</Text>
          <Text style={styles.quickDesc}>{a.desc}</Text>
          <Text style={styles.quickArrow}>→</Text>
        </TouchableOpacity>
      ))}
    </View>
  );
}

// ── Welcome Banner ───────────────────────────────────────────────────

function WelcomeBanner() {
  const { width } = useWindowDimensions();
  const isSmall = width < 768;

  return (
    <View style={styles.banner}>
      <View
        style={[styles.bannerAvatar, styles.bannerIcon, isSmall && { width: 60, height: 60, borderRadius: 30 }]}
        accessibilityElementsHidden
        importantForAccessibility="no-hide-descendants"
      >
        <Ionicons name="heart-outline" size={isSmall ? 28 : 36} color={TEAL} />
      </View>
      <View style={styles.bannerText}>
        <Text style={[styles.bannerTitle, isSmall && { fontSize: 15 }]}>Welcome to CareNexus Direct</Text>
        <Text style={[styles.bannerDesc, isSmall && { fontSize: 12 }]}>
          {isSmall
            ? 'Book appointments, view your records and keep track of your care.'
            : 'Book and manage appointments, review your prescriptions and results,\nand keep your health record in one place.'}
        </Text>
      </View>
      {!isSmall && (
        <View style={styles.bannerDecor}>
          <View style={styles.decorCircleOuter} />
          <View style={styles.decorCircleInner} />
        </View>
      )}
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
  prescriptions, rxLoading, rxError, onRetryRx, onViewPrescriptions, onViewProfile,
}: {
  prescriptions: Prescription[];
  rxLoading: boolean;
  rxError: string | null;
  onRetryRx: () => void;
  onViewPrescriptions?: () => void;
  onViewProfile?: () => void;
}) {
  const { width } = useWindowDimensions();
  const isSmall = width < 768;
  const { profile, loading, error, refetch } = usePatientProfile();

  const activeRx = prescriptions.filter(p => p.status === 'ACTIVE');
  const metrics: HealthMetric[] = profile ? [
    { label: 'Conditions',          value: String(profile.chronic_conditions.length), sub: summarise(profile.chronic_conditions), icon: 'heart-outline',   iconBg: '#E6F4F1', iconColor: TEAL },
    { label: 'Allergies',           value: String(profile.allergies.length),          sub: summarise(profile.allergies),          icon: 'warning-outline', iconBg: '#FEE2E2', iconColor: RED },
    {
      label: 'Current Medications',
      value: rxLoading ? '…' : rxError ? '—' : String(activeRx.length),
      sub:   rxError ? 'Could not load' : 'Active prescriptions',
      icon: 'medkit-outline', iconBg: '#E8F0FE', iconColor: '#4285F4',
      onPress: onViewPrescriptions,
    },
    { label: 'Blood Type', value: profile.blood_type ?? '—', sub: profile.blood_type ? undefined : 'Not recorded', icon: 'water-outline', iconBg: '#FEE2E2', iconColor: RED },
  ] : [];

  function renderMetric(m: HealthMetric, compact: boolean) {
    const body = (
      <>
        <View style={[styles.metricIconBg, { backgroundColor: m.iconBg }]}>
          <Ionicons name={m.icon} size={compact ? 18 : 20} color={m.iconColor} />
        </View>
        <Text style={styles.metricLabel}>{m.label}</Text>
        <Text style={[styles.metricValue, compact && { fontSize: 18 }]}>{m.value}</Text>
        {m.sub ? <Text style={styles.metricSub} numberOfLines={2}>{m.sub}</Text> : null}
        {m.onPress ? <Text style={styles.link}>View details</Text> : null}
      </>
    );
    const style = [styles.metricCard, compact && { width: 130, flex: undefined }];
    return m.onPress ? (
      <TouchableOpacity key={m.label} style={style} onPress={m.onPress} activeOpacity={0.8} accessibilityRole="button" accessibilityLabel={`${m.label}: ${m.value}. View details`}>
        {body}
      </TouchableOpacity>
    ) : (
      <View key={m.label} style={style} accessible accessibilityLabel={`${m.label}: ${m.value}${m.sub ? `. ${m.sub}` : ''}`}>
        {body}
      </View>
    );
  }

  return (
    <View style={styles.section}>
      <View style={styles.sectionHeader}>
        <Text style={styles.sectionTitle}>Health Overview</Text>
        {onViewProfile && (
          <TouchableOpacity onPress={onViewProfile} accessibilityRole="link"><Text style={styles.link}>View profile →</Text></TouchableOpacity>
        )}
      </View>
      {loading ? (
        <SectionLoading label="Loading your health summary…" />
      ) : error || !profile ? (
        <SectionError message={error ?? 'Could not load your health summary.'} onRetry={() => { refetch(); onRetryRx(); }} />
      ) : isSmall ? (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 10, paddingVertical: 2 }}>
          {metrics.map((m) => renderMetric(m, true))}
        </ScrollView>
      ) : (
        <View style={styles.metricsRow}>
          {metrics.map((m) => renderMetric(m, false))}
        </View>
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
  const recent = appointments.filter(a => a.status === 'COMPLETED').slice(0, 3);

  return (
    <View style={[styles.section, { flex: 1 }]}>
      <View style={styles.sectionHeader}>
        <Text style={styles.sectionTitle}>Recent Consultations</Text>
        <TouchableOpacity onPress={onViewConsultations} accessibilityRole="link"><Text style={styles.link}>View all</Text></TouchableOpacity>
      </View>
      {loading ? (
        <SectionLoading />
      ) : error ? (
        <SectionError message={error} onRetry={onRetry} />
      ) : recent.length === 0 ? (
        <Text style={[styles.consultDate, { padding: 12 }]}>No completed consultations yet.</Text>
      ) : recent.map((a) => (
        <View key={a.appointment_id} style={styles.consultRow}>
          <View style={styles.consultIconBg}>
            <Ionicons name={TYPE_ICON[a.type] ?? 'calendar-outline'} size={18} color={TEAL} />
          </View>
          <View style={styles.consultInfo}>
            <Text style={styles.consultDate}>{formatApptDate(a.scheduled_at)}</Text>
            <Text style={styles.consultTitle}>{a.reason_for_visit ?? 'Consultation'}</Text>
            <Text style={styles.consultDoctor}>{a.doctor_name}{a.doctor_specialization ? ` (${a.doctor_specialization})` : ''}</Text>
          </View>
          <View style={styles.badge}><Text style={styles.badgeText}>Completed</Text></View>
        </View>
      ))}
      <TouchableOpacity style={styles.viewAllRow} onPress={onViewConsultations} accessibilityRole="link">
        <Text style={styles.link}>View all consultations →</Text>
      </TouchableOpacity>
    </View>
  );
}

// ── Latest Prescriptions ─────────────────────────────────────────────

function LatestPrescriptions({
  prescriptions, loading, error, onRetry, onViewPrescriptions,
}: {
  prescriptions: Prescription[];
  loading: boolean;
  error: string | null;
  onRetry: () => void;
  onViewPrescriptions?: () => void;
}) {
  const active = prescriptions.filter(p => p.status === 'ACTIVE').slice(0, 3);

  return (
    <View style={[styles.section, { flex: 1 }]}>
      <View style={styles.sectionHeader}>
        <Text style={styles.sectionTitle}>Current Prescriptions</Text>
        <TouchableOpacity onPress={onViewPrescriptions} accessibilityRole="link"><Text style={styles.link}>View all</Text></TouchableOpacity>
      </View>
      {loading ? (
        <SectionLoading />
      ) : error ? (
        <SectionError message={error} onRetry={onRetry} />
      ) : active.length === 0 ? (
        <Text style={[styles.consultDate, { padding: 12 }]}>You have no active prescriptions.</Text>
      ) : active.map((p) => (
        <View key={p.prescription_id} style={styles.consultRow}>
          <Ionicons name="document-outline" size={22} color={GRAY} />
          <View style={styles.consultInfo}>
            <Text style={styles.consultTitle}>{p.medication_name}{p.dosage ? ` ${p.dosage}` : ''}</Text>
            {!!p.frequency && <Text style={styles.consultDoctor}>{p.frequency}</Text>}
            <Text style={styles.consultDate}>
              {formatDay(p.prescribed_date) ? `Prescribed ${formatDay(p.prescribed_date)}` : ''}{p.doctor_name ? ` • ${p.doctor_name}` : ''}
            </Text>
          </View>
          <View style={[styles.badge, styles.badgeActive]}>
            <Text style={[styles.badgeText, styles.badgeActiveText]}>Active</Text>
          </View>
        </View>
      ))}
      <TouchableOpacity style={styles.viewAllRow} onPress={onViewPrescriptions} accessibilityRole="link">
        <Text style={styles.link}>View all prescriptions →</Text>
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
  const { width } = useWindowDimensions();
  const isSmall = width < 768;
  const upcoming = appointments
    .filter(isUpcomingAppointment)
    .sort((a, b) => new Date(a.scheduled_at).getTime() - new Date(b.scheduled_at).getTime())
    .slice(0, 3);

  return (
    <View style={styles.section}>
      <View style={styles.sectionHeader}>
        <View style={styles.remindersHeader}>
          <Ionicons name="calendar-outline" size={20} color={TEXT} />
          <Text style={styles.sectionTitle}>Upcoming Appointments</Text>
        </View>
        <TouchableOpacity onPress={onViewAppointments} accessibilityRole="link"><Text style={styles.link}>Manage</Text></TouchableOpacity>
      </View>
      {loading ? (
        <SectionLoading />
      ) : error ? (
        <SectionError message={error} onRetry={onRetry} />
      ) : upcoming.length === 0 ? (
        <View style={styles.emptyRow}>
          <Text style={styles.consultDate}>You have no upcoming appointments.</Text>
          {onBookAppointment && (
            <TouchableOpacity onPress={onBookAppointment} accessibilityRole="button"><Text style={styles.link}>Book one →</Text></TouchableOpacity>
          )}
        </View>
      ) : (
        <View style={[styles.remindersRow, isSmall && { flexDirection: 'column' }]}>
          {upcoming.map((a) => (
            <TouchableOpacity
              key={a.appointment_id}
              style={[styles.reminderCard, isSmall && { flex: undefined }]}
              activeOpacity={0.8}
              onPress={onViewAppointments}
              accessibilityRole="button"
              accessibilityLabel={`Appointment with ${a.doctor_name} on ${formatApptDate(a.scheduled_at)}`}
            >
              <View style={[styles.reminderIconBg, { backgroundColor: '#E8F0FE' }]}>
                <Ionicons name={TYPE_ICON[a.type] ?? 'calendar-outline'} size={18} color="#4285F4" />
              </View>
              <View style={styles.reminderInfo}>
                <Text style={styles.reminderLabel} numberOfLines={1}>{a.doctor_name}</Text>
                <Text style={styles.reminderSub}>{formatApptDate(a.scheduled_at)}</Text>
              </View>
              <Ionicons name="chevron-forward" size={16} color={GRAY} />
            </TouchableOpacity>
          ))}
        </View>
      )}
    </View>
  );
}

// ── Main export ──────────────────────────────────────────────────────

export function PatientMainDashboardArea({
  onBookAppointment,
  onViewConsultations,
  onViewPrescriptions,
  onNavigate,
}: {
  onBookAppointment?: () => void;
  onViewConsultations?: () => void;
  onViewPrescriptions?: () => void;
  onNavigate?: (key: string) => void;
}) {
  const { width } = useWindowDimensions();
  const isSmall = width < 768;
  const appts = useAppointments();
  const rx    = usePrescriptions();

  function handleAction(key: string) {
    if (key === 'book-appointment') onBookAppointment?.();
    else if (key === 'prescriptions') onViewPrescriptions?.();
    else onNavigate?.(key);
  }

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
      <QuickActions onAction={handleAction} />
      <WelcomeBanner />
      <HealthOverview
        prescriptions={rx.prescriptions}
        rxLoading={rx.loading}
        rxError={rx.error}
        onRetryRx={rx.refetch}
        onViewPrescriptions={onViewPrescriptions}
        onViewProfile={onNavigate ? () => onNavigate('profile-settings') : undefined}
      />
      <UpcomingAppointments
        appointments={appts.appointments}
        loading={appts.loading}
        error={appts.error}
        onRetry={appts.refetch}
        onViewAppointments={onNavigate ? () => onNavigate('my-appointments') : undefined}
        onBookAppointment={onBookAppointment}
      />
      <View style={[styles.twoCol, isSmall && { flexDirection: 'column' }]}>
        <RecentConsultations
          appointments={appts.appointments}
          loading={appts.loading}
          error={appts.error}
          onRetry={appts.refetch}
          onViewConsultations={onViewConsultations}
        />
        <LatestPrescriptions
          prescriptions={rx.prescriptions}
          loading={rx.loading}
          error={rx.error}
          onRetry={rx.refetch}
          onViewPrescriptions={onViewPrescriptions}
        />
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: GRAY_LIGHT },
  scroll:    { padding: 20, gap: 16 },

  quickRow:    { flexDirection: 'row', gap: 12, flexWrap: 'wrap' },
  quickCard:   { flex: 1, minWidth: 100, backgroundColor: WHITE, borderRadius: 12, padding: 14, borderWidth: 1, borderColor: BORDER, gap: 4 },
  quickIconBg: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center', marginBottom: 4 },
  quickLabel:  { fontSize: 13, fontWeight: '700', color: TEXT, lineHeight: 18 },
  quickDesc:   { fontSize: 11, color: GRAY, lineHeight: 15 },
  quickArrow:  { fontSize: 14, color: GRAY, marginTop: 4 },

  banner:           { backgroundColor: '#F0F4FF', borderRadius: 14, padding: 20, flexDirection: 'row', alignItems: 'center', gap: 16, borderWidth: 1, borderColor: BORDER, overflow: 'hidden' },
  bannerAvatar:     { width: 80, height: 80, borderRadius: 40, backgroundColor: BORDER },
  bannerIcon:       { backgroundColor: '#E6F4F1', alignItems: 'center', justifyContent: 'center' },
  bannerText:       { flex: 1 },
  bannerTitle:      { fontSize: 18, fontWeight: '700', color: NAVY, marginBottom: 6 },
  bannerDesc:       { fontSize: 13, color: GRAY, lineHeight: 20 },
  bannerDecor:      { position: 'absolute', right: -20, top: -20, opacity: 0.15 },
  decorCircleOuter: { width: 140, height: 140, borderRadius: 70, borderWidth: 24, borderColor: TEAL },
  decorCircleInner: { width: 80, height: 80, borderRadius: 40, borderWidth: 16, borderColor: TEAL, position: 'absolute', right: 10, bottom: -30 },

  section:       { backgroundColor: WHITE, borderRadius: 14, padding: 16, borderWidth: 1, borderColor: BORDER, gap: 2 },
  sectionHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 },
  sectionTitle:  { fontSize: 15, fontWeight: '700', color: TEXT },
  link:          { fontSize: 13, color: '#4F6EF7', fontWeight: '500' },

  metricsRow: { flexDirection: 'row', gap: 10, flexWrap: 'wrap' },
  metricCard: { flex: 1, minWidth: 100, borderRadius: 10, borderWidth: 1, borderColor: BORDER, padding: 12, gap: 4, alignItems: 'flex-start' },
  metricIconBg: { width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center', marginBottom: 4 },
  metricLabel:  { fontSize: 11, color: GRAY },
  metricValue:  { fontSize: 22, fontWeight: '700', color: TEXT },
  metricSub:    { fontSize: 11, color: GRAY, lineHeight: 16 },

  twoCol:        { flexDirection: 'row', gap: 16 },
  consultRow:    { flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 10, borderTopWidth: 1, borderTopColor: BORDER },
  consultIconBg: { width: 38, height: 38, borderRadius: 10, backgroundColor: '#E6F4F1', alignItems: 'center', justifyContent: 'center' },
  consultInfo:   { flex: 1 },
  consultDate:   { fontSize: 11, color: GRAY },
  consultTitle:  { fontSize: 13, fontWeight: '600', color: TEXT },
  consultDoctor: { fontSize: 12, color: GRAY },
  badge:         { backgroundColor: '#E6F4F1', borderRadius: 6, paddingHorizontal: 8, paddingVertical: 3 },
  badgeText:     { fontSize: 11, color: TEAL, fontWeight: '600' },
  badgeActive:   { backgroundColor: '#E6F4F1' },
  badgeActiveText: { color: TEAL },
  viewAllRow:    { alignItems: 'center', paddingTop: 12 },

  remindersHeader: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  remindersRow:    { flexDirection: 'row', gap: 12 },
  reminderCard:    { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 10, backgroundColor: WHITE, borderRadius: 12, padding: 14, borderWidth: 1, borderColor: BORDER },
  reminderIconBg:  { width: 36, height: 36, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  reminderInfo:    { flex: 1 },
  reminderLabel:   { fontSize: 13, fontWeight: '600', color: TEXT },
  reminderSub:     { fontSize: 11, color: GRAY },

  emptyRow:  { flexDirection: 'row', alignItems: 'center', gap: 12, padding: 12, flexWrap: 'wrap' },
  errorRow:  { flexDirection: 'row', alignItems: 'center', gap: 8, padding: 12, backgroundColor: '#FEF2F2', borderRadius: 10 },
  errorText: { flex: 1, fontSize: 13, color: RED },
  retryText: { fontSize: 13, fontWeight: '700', color: RED, textDecorationLine: 'underline' },
});
