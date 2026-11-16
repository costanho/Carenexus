import { ScrollView, StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';
import { AmountDueCard } from '@/components/web/billing/amount-due-card';
import { LatestVitalsCard } from '@/components/web/health/latest-vitals-card';
import {
  Button, decorative, ErrorView, Icon, type IoniconsName, LoadingView, ROLE_THEME, SectionCard, StatusBadge,
  UiThemeProvider,
} from '@/components/web/ui';
import { isUpcomingAppointment, useAppointments, type Appointment } from '@/hooks/use-appointments';
import { useConsultations, type Consultation } from '@/hooks/use-consultations';
import { useCurrentUser } from '@/hooks/use-current-user';
import { tidyDoctorName } from '@/hooks/use-doctors';
import { useMedicationSchedule, type MedicationDose } from '@/hooks/use-medication-schedule';
import { usePatientProfile } from '@/hooks/use-patient-profile';
import { usePrescriptions, type Prescription } from '@/hooks/use-prescriptions';
import { addDays } from '@/lib/medication/dates';
import { doseName, isDoseOverdue } from '@/lib/medication/dose';
import type { SectionParamsInput, SectionProps } from '@/lib/nav/sections';
import { CLINIC_ZONE_LABEL, clinicToday, formatClinicDate, formatClinicDateTime } from '@/lib/time/clinic';
import { ariaLevel } from './a11y';

const TEAL       = '#0F766E';
const NAVY       = '#1E3A5F';
const GRAY       = '#6B7280';
const GRAY_LIGHT = '#F9FAFB';
const BORDER     = '#E5E7EB';
const WHITE      = '#FFFFFF';
const TEXT       = '#111827';
const RED        = '#B91C1C';
const LINK       = '#4338CA';

type Navigate = (section: string, params?: SectionParamsInput) => void;

// ── Shared section states ────────────────────────────────────────────

function SectionLoading({ label = 'Loading…' }: { label?: string }) {
  return <Text style={[styles.consultDate, { padding: 12 }]} role="status">{label}</Text>;
}

function SectionError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <View style={styles.errorRow}>
      <Icon name="alert-circle-outline" size={16} color={RED} />
      <Text style={styles.errorText} role="alert">{message}</Text>
      <TouchableOpacity onPress={onRetry} activeOpacity={0.7} accessibilityRole="button" accessibilityLabel="Retry loading">
        <Text style={styles.retryText}>Retry</Text>
      </TouchableOpacity>
    </View>
  );
}

// ── Quick Actions ────────────────────────────────────────────────────

type QuickAction = {
  key: string; label: string; desc: string; icon: IoniconsName; iconBg: string; iconColor: string;
  params?: SectionParamsInput;
};

const quickActions: QuickAction[] = [
  { key: 'book-appointment', label: 'Book\nAppointment',   desc: 'Schedule a new appointment',          icon: 'calendar-outline',        iconBg: '#EEE8FF', iconColor: '#7C5CFC' },
  { key: 'my-appointments',  label: 'My\nAppointments',    desc: 'Reschedule or cancel a visit',        icon: 'calendar-number-outline', iconBg: '#E6F4F1', iconColor: TEAL },
  { key: 'prescriptions',    label: "Today's\nMedication", desc: 'Mark doses taken or missed',          icon: 'medkit-outline',          iconBg: '#FFF3E6', iconColor: '#B45309', params: { tab: 'schedule' } },
  { key: 'health-tracking',  label: 'Record\nVitals',      desc: 'Log readings and symptoms',           icon: 'pulse-outline',           iconBg: '#FEE2E2', iconColor: RED },
  { key: 'lab-results',      label: 'Lab\nResults',        desc: 'Review your latest test results',     icon: 'flask-outline',           iconBg: '#E8F0FE', iconColor: '#1D4ED8' },
  { key: 'messages',         label: 'Message\nCare Team',  desc: 'Write to your doctors and care team', icon: 'chatbubbles-outline',     iconBg: '#E6F4F1', iconColor: TEAL },
];

function QuickActions({ navigate }: { navigate: Navigate }) {
  const { width } = useWindowDimensions();
  const isSmall = width < 768;

  if (isSmall) {
    // Two tiles per row on a phone: the tiles wrap instead of scrolling sideways (WCAG 1.4.10 Reflow).
    return (
      <View style={styles.quickRow}>
        {quickActions.map((a) => (
          <TouchableOpacity
            key={a.key}
            style={[styles.quickCardBase, styles.quickCardCompact]}
            activeOpacity={0.8}
            onPress={() => navigate(a.key, a.params)}
            accessibilityRole="button"
            accessibilityLabel={a.label.replace('\n', ' ')}
          >
            <View style={[styles.quickIconBg, { backgroundColor: a.iconBg }]}>
              <Icon name={a.icon} size={20} color={a.iconColor} />
            </View>
            <Text style={styles.quickLabel}>{a.label}</Text>
            <Text style={styles.quickArrow} {...decorative}>→</Text>
          </TouchableOpacity>
        ))}
      </View>
    );
  }

  return (
    <View style={styles.quickRow}>
      {quickActions.map((a) => (
        <TouchableOpacity
          key={a.key}
          style={[styles.quickCardBase, styles.quickCardWide]}
          activeOpacity={0.8}
          onPress={() => navigate(a.key, a.params)}
          accessibilityRole="button"
          accessibilityLabel={`${a.label.replace('\n', ' ')}. ${a.desc}`}
        >
          <View style={[styles.quickIconBg, { backgroundColor: a.iconBg }]}>
            <Icon name={a.icon} size={22} color={a.iconColor} />
          </View>
          <Text style={styles.quickLabel}>{a.label}</Text>
          <Text style={styles.quickDesc}>{a.desc}</Text>
          <Text style={styles.quickArrow} {...decorative}>→</Text>
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
        {...decorative}
      >
        <Icon name="heart-outline" size={isSmall ? 28 : 36} color={TEAL} />
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
        <View style={styles.bannerDecor} {...decorative}>
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
  activeCount, rxLoading, rxError, onRetryRx, onViewPrescriptions, onViewProfile,
}: {
  /** Number of prescriptions whose effective status is ACTIVE (server count). */
  activeCount: number;
  rxLoading: boolean;
  rxError: string | null;
  onRetryRx: () => void;
  onViewPrescriptions: () => void;
  onViewProfile: () => void;
}) {
  const { width } = useWindowDimensions();
  const isSmall = width < 768;
  const { profile, loading, error, refetch } = usePatientProfile();

  const metrics: HealthMetric[] = profile ? [
    { label: 'Conditions',          value: String(profile.chronic_conditions.length), sub: summarise(profile.chronic_conditions), icon: 'heart-outline',   iconBg: '#E6F4F1', iconColor: TEAL },
    { label: 'Allergies',           value: String(profile.allergies.length),          sub: summarise(profile.allergies),          icon: 'warning-outline', iconBg: '#FEE2E2', iconColor: RED },
    {
      label: 'Current Medications',
      value: rxLoading ? '…' : rxError ? '—' : String(activeCount),
      sub:   rxError ? 'Could not load' : 'Active prescriptions',
      icon: 'medkit-outline', iconBg: '#E8F0FE', iconColor: '#1D4ED8',
      onPress: onViewPrescriptions,
    },
    { label: 'Blood Type', value: profile.blood_type ?? '—', sub: profile.blood_type ? undefined : 'Not recorded', icon: 'water-outline', iconBg: '#FEE2E2', iconColor: RED },
  ] : [];

  function renderMetric(m: HealthMetric, compact: boolean) {
    // The placeholders "—" and "…" would be read as "dash" and "ellipsis", so they are hidden from screen readers.
    // When no sub-line explains them, a visually hidden word says what they mean instead.
    const isPlaceholder = m.value === '—' || m.value === '…';
    const placeholderWords = m.value === '…' ? 'Loading' : 'Not recorded';
    const valueStyle = [styles.metricValue, compact && { fontSize: 18 }];
    const body = (
      <>
        <View style={[styles.metricIconBg, { backgroundColor: m.iconBg }]}>
          <Icon name={m.icon} size={compact ? 18 : 20} color={m.iconColor} />
        </View>
        <Text style={styles.metricLabel}>{m.label}</Text>
        {isPlaceholder ? (
          <>
            <Text style={valueStyle} {...decorative}>{m.value}</Text>
            {m.sub ? null : <Text style={styles.srOnly}>{placeholderWords}</Text>}
          </>
        ) : (
          <Text style={valueStyle}>{m.value}</Text>
        )}
        {m.sub ? <Text style={styles.metricSub} numberOfLines={2}>{m.sub}</Text> : null}
        {m.onPress ? <Text style={styles.link}>View details</Text> : null}
      </>
    );
    // On a phone the four figures wrap two per row instead of scrolling sideways.
    const style = [styles.metricCard, compact ? styles.metricCardCompact : styles.metricCardWide];
    // The button's name repeats its visible text in reading order (WCAG 2.5.3), so it starts with the label;
    // a placeholder value is left out of it for the same reason as above.
    const spokenValue = isPlaceholder ? '' : `: ${m.value}`;
    const summary = `${m.label}${spokenValue}${m.sub ? `. ${m.sub}` : ''}`;
    return m.onPress ? (
      <TouchableOpacity key={m.label} style={style} onPress={m.onPress} activeOpacity={0.8} accessibilityRole="button" accessibilityLabel={`${summary}. View details`}>
        {body}
      </TouchableOpacity>
    ) : (
      // Not interactive: the label, value and sub-line stay exposed as plain text and are read in order
      // (a name on a generic group is skipped by NVDA and JAWS in browse mode).
      <View key={m.label} style={style}>
        {body}
      </View>
    );
  }

  return (
    <View style={styles.section}>
      <View style={styles.sectionHeader}>
        <Text style={styles.sectionTitle} accessibilityRole="header" {...ariaLevel(2)}>Health Overview</Text>
        <TouchableOpacity onPress={onViewProfile} accessibilityRole="link">
          <Text style={styles.link}>View profile <Text {...decorative}>→</Text></Text>
        </TouchableOpacity>
      </View>
      {loading ? (
        <SectionLoading label="Loading your health summary…" />
      ) : error || !profile ? (
        <SectionError message={error ?? 'Could not load your health summary.'} onRetry={() => { refetch(); onRetryRx(); }} />
      ) : isSmall ? (
        <View style={styles.metricsRow}>
          {metrics.map((m) => renderMetric(m, true))}
        </View>
      ) : (
        <View style={styles.metricsRow}>
          {metrics.map((m) => renderMetric(m, false))}
        </View>
      )}
    </View>
  );
}

// ── Next medication dose ─────────────────────────────────────────────

/**
 * The next dose still to take: the first pending dose of the clinic's today (an overdue one first, since the
 * schedule lists doses by time), otherwise the first one of tomorrow. Tomorrow is read only when today has
 * nothing pending.
 */
function NextDoseCard({ navigate }: { navigate: Navigate }) {
  const today = clinicToday();
  const tomorrow = addDays(today, 1);
  const todays = useMedicationSchedule(null, today, { self: true });
  const pendingToday = todays.doses.filter(d => d.status === 'PENDING');
  const todayReady = !todays.loading && !todays.error;
  const tomorrows = useMedicationSchedule(null, tomorrow, { self: true, enabled: todayReady && pendingToday.length === 0 });
  const pendingTomorrow = tomorrows.doses.filter(d => d.status === 'PENDING');

  const next: MedicationDose | null = pendingToday[0] ?? (todayReady ? pendingTomorrow[0] ?? null : null);
  const openSchedule = (date?: string) =>
    navigate('prescriptions', { tab: 'schedule', date: date && date !== today ? date : undefined });

  let body;
  if (todays.loading || (todayReady && pendingToday.length === 0 && tomorrows.loading)) {
    body = <LoadingView compact label="Loading your medication schedule" />;
  } else if (todays.error) {
    body = <ErrorView compact message={todays.error} onRetry={todays.refetch} />;
  } else if (next) {
    const overdue = isDoseOverdue(next);
    const isToday = next.scheduleDate === today;
    const laterToday = isToday ? pendingToday.length - 1 : 0;
    body = (
      <View style={styles.doseBody}>
        <View style={styles.doseRow}>
          <View style={styles.doseIcon}>
            <Icon name="medkit-outline" size={20} color={TEAL} />
          </View>
          <View style={styles.doseInfo}>
            <Text style={styles.doseName}>{doseName(next)}</Text>
            <Text style={styles.doseTime}>
              {isToday ? 'Today' : 'Tomorrow'} at {next.scheduledTime} {CLINIC_ZONE_LABEL}
            </Text>
          </View>
          <StatusBadge label={overdue ? 'Due now' : 'Due'} tone={overdue ? 'warning' : 'info'} />
        </View>
        {laterToday > 0 ? (
          <Text style={styles.doseMeta}>
            {laterToday === 1 ? '1 more dose is due later today.' : `${laterToday} more doses are due later today.`}
          </Text>
        ) : null}
        {!isToday && todays.doses.length > 0 ? (
          <Text style={styles.doseMeta}>Every dose for today has been recorded.</Text>
        ) : null}
      </View>
    );
  } else if (tomorrows.error) {
    body = <ErrorView compact message={tomorrows.error} onRetry={tomorrows.refetch} />;
  } else {
    body = (
      <Text style={styles.doseMeta}>
        {todays.doses.length > 0
          ? 'Every dose for today has been recorded, and none are scheduled for tomorrow.'
          : 'No doses are scheduled for today or tomorrow.'}
      </Text>
    );
  }

  return (
    <SectionCard
      title="Next medication dose"
      action={(
        <Button
          label="Open schedule"
          // Starts with the visible words (WCAG 2.5.3); the rest says which schedule.
          accessibilityLabel="Open schedule of your medication doses"
          variant="ghost"
          size="sm"
          icon="chevron-forward"
          onPress={() => openSchedule(next?.scheduleDate)}
        />
      )}
    >
      {body}
    </SectionCard>
  );
}

// ── Recent Consultations ─────────────────────────────────────────────

const TYPE_ICON: Record<string, IoniconsName> = {
  VIDEO:     'videocam-outline',
  IN_PERSON: 'business-outline',
  PHONE:     'call-outline',
};

function RecentConsultations({ navigate }: { navigate: Navigate }) {
  // Same source and rule as Consultation History: only finalised (COMPLETED) consultations, never a doctor's
  // draft or in-progress notes. The hook sorts newest first; one page of three is all the card shows.
  const { consultations, loading, error, refetch } = useConsultations({ pageSize: 3 });
  const recent = consultations.filter((c: Consultation) => c.status === 'COMPLETED').slice(0, 3);
  const viewAll = () => navigate('consultation-history');

  return (
    <View style={[styles.section, styles.col]}>
      <View style={styles.sectionHeader}>
        <Text style={styles.sectionTitle} accessibilityRole="header" {...ariaLevel(2)}>Recent Consultations</Text>
        <TouchableOpacity onPress={viewAll} accessibilityRole="link"><Text style={styles.link}>View all</Text></TouchableOpacity>
      </View>
      {loading ? (
        <SectionLoading />
      ) : error ? (
        <SectionError message={error} onRetry={refetch} />
      ) : recent.length === 0 ? (
        <Text style={[styles.consultDate, { padding: 12 }]}>No completed consultations yet.</Text>
      ) : recent.map((c) => {
        const day = formatClinicDate(c.completed_at ?? c.created_at);
        const title = c.chief_complaint ?? c.diagnosis ?? 'Consultation';
        const doctorLine = `${tidyDoctorName(c.doctor_name)}${c.doctor_specialization ? ` (${c.doctor_specialization})` : ''}`;
        return (
          <TouchableOpacity
            key={c.consultation_id}
            style={styles.consultRow}
            activeOpacity={0.8}
            onPress={() => navigate('consultation-history', { id: c.consultation_id })}
            accessibilityRole="button"
            // The card's visible text in reading order, then what pressing it does (WCAG 2.5.3 Label in Name).
            accessibilityLabel={`${day ? `${day}, ` : ''}${title}, ${doctorLine}, Completed. Open consultation`}
          >
            <View style={styles.consultIconBg}>
              <Icon name={(c.appointment_type && TYPE_ICON[c.appointment_type]) || 'document-text-outline'} size={18} color={TEAL} />
            </View>
            <View style={styles.consultInfo}>
              <Text style={styles.consultDate}>{day}</Text>
              <Text style={styles.consultTitle} numberOfLines={2}>{title}</Text>
              <Text style={styles.consultDoctor}>{doctorLine}</Text>
            </View>
            <View style={styles.badge}><Text style={styles.badgeText}>Completed</Text></View>
          </TouchableOpacity>
        );
      })}
      <TouchableOpacity style={styles.viewAllRow} onPress={viewAll} accessibilityRole="link">
        <Text style={styles.link}>View all consultations <Text {...decorative}>→</Text></Text>
      </TouchableOpacity>
    </View>
  );
}

// ── Current Prescriptions ────────────────────────────────────────────

function CurrentPrescriptions({
  prescriptions, loading, error, onRetry, navigate,
}: {
  /** The newest active prescriptions (effective status ACTIVE, so one past its end date is not listed). */
  prescriptions: Prescription[];
  loading: boolean;
  error: string | null;
  onRetry: () => void;
  navigate: Navigate;
}) {
  const viewAll = () => navigate('prescriptions');
  return (
    <View style={[styles.section, styles.col]}>
      <View style={styles.sectionHeader}>
        <Text style={styles.sectionTitle} accessibilityRole="header" {...ariaLevel(2)}>Current Prescriptions</Text>
        <TouchableOpacity onPress={viewAll} accessibilityRole="link"><Text style={styles.link}>View all</Text></TouchableOpacity>
      </View>
      {loading ? (
        <SectionLoading />
      ) : error ? (
        <SectionError message={error} onRetry={onRetry} />
      ) : prescriptions.length === 0 ? (
        <Text style={[styles.consultDate, { padding: 12 }]}>You have no active prescriptions.</Text>
      ) : prescriptions.map((p) => {
        const day = formatClinicDate(p.prescribed_date);
        const name = `${p.medication_name}${p.dosage ? ` ${p.dosage}` : ''}`;
        const prescribed = day ? `Prescribed ${day}` : '';
        const doctor = p.doctor_name ? tidyDoctorName(p.doctor_name) : '';
        // The card's visible text in reading order, then what pressing it does (WCAG 2.5.3 Label in Name).
        const label = [name, p.frequency, prescribed, doctor, 'Active'].filter(Boolean).join(', ');
        return (
          <TouchableOpacity
            key={p.prescription_id}
            style={styles.consultRow}
            activeOpacity={0.8}
            onPress={() => navigate('prescriptions', { id: p.prescription_id })}
            accessibilityRole="button"
            accessibilityLabel={`${label}. Open prescription`}
          >
            <Icon name="document-outline" size={22} color={GRAY} />
            <View style={styles.consultInfo}>
              <Text style={styles.consultTitle}>{name}</Text>
              {!!p.frequency && <Text style={styles.consultDoctor}>{p.frequency}</Text>}
              <Text style={styles.consultDate}>
                {prescribed}{doctor ? ` • ${doctor}` : ''}
              </Text>
            </View>
            <View style={styles.badge}>
              <Text style={styles.badgeText}>Active</Text>
            </View>
          </TouchableOpacity>
        );
      })}
      <TouchableOpacity style={styles.viewAllRow} onPress={viewAll} accessibilityRole="link">
        <Text style={styles.link}>View all prescriptions <Text {...decorative}>→</Text></Text>
      </TouchableOpacity>
    </View>
  );
}

// ── Upcoming Appointments ────────────────────────────────────────────

function UpcomingAppointments({
  appointments, loading, error, onRetry, navigate,
}: {
  appointments: Appointment[];
  loading: boolean;
  error: string | null;
  onRetry: () => void;
  navigate: Navigate;
}) {
  const { width } = useWindowDimensions();
  const isSmall = width < 768;
  const upcoming = appointments
    .filter(isUpcomingAppointment)
    .sort((a, b) => a.scheduled_at.localeCompare(b.scheduled_at))
    .slice(0, 3);

  return (
    <View style={styles.section}>
      <View style={styles.sectionHeader}>
        <View style={styles.remindersHeader}>
          <Icon name="calendar-outline" size={20} color={TEXT} />
          <Text style={styles.sectionTitle} accessibilityRole="header" {...ariaLevel(2)}>Upcoming Appointments</Text>
        </View>
        <TouchableOpacity onPress={() => navigate('my-appointments')} accessibilityRole="link"><Text style={styles.link}>Manage</Text></TouchableOpacity>
      </View>
      {loading ? (
        <SectionLoading />
      ) : error ? (
        <SectionError message={error} onRetry={onRetry} />
      ) : upcoming.length === 0 ? (
        <View style={styles.emptyRow}>
          <Text style={styles.consultDate}>You have no upcoming appointments.</Text>
          <TouchableOpacity onPress={() => navigate('book-appointment')} accessibilityRole="button"><Text style={styles.link}>Book one <Text {...decorative}>→</Text></Text></TouchableOpacity>
        </View>
      ) : (
        <View style={[styles.remindersRow, isSmall && { flexDirection: 'column' }]}>
          {upcoming.map((a) => {
            const when = formatClinicDateTime(a.scheduled_at);
            return (
              <TouchableOpacity
                key={a.appointment_id}
                style={[styles.reminderCard, isSmall && { flex: undefined }]}
                activeOpacity={0.8}
                onPress={() => navigate('my-appointments', { id: a.appointment_id })}
                accessibilityRole="button"
                // Starts with the card's visible text, the doctor and the time (WCAG 2.5.3 Label in Name).
                accessibilityLabel={`${a.doctor_name}, ${when}, open appointment`}
              >
                <View style={[styles.reminderIconBg, { backgroundColor: '#E8F0FE' }]}>
                  <Icon name={TYPE_ICON[a.type] ?? 'calendar-outline'} size={18} color="#1D4ED8" />
                </View>
                <View style={styles.reminderInfo}>
                  <Text style={styles.reminderLabel} numberOfLines={1}>{a.doctor_name}</Text>
                  <Text style={styles.reminderSub}>{when}</Text>
                </View>
                <Icon name="chevron-forward" size={16} color={GRAY} />
              </TouchableOpacity>
            );
          })}
        </View>
      )}
    </View>
  );
}

// ── Your care (quick links) ──────────────────────────────────────────

type CareLink = { key: string; label: string; desc: string; icon: IoniconsName };

const careLinks: CareLink[] = [
  { key: 'care-team',     label: 'My Care Team',  desc: 'The doctors and providers looking after you',   icon: 'people-circle-outline' },
  { key: 'care-plans',    label: 'Care Plans',    desc: 'Goals and next steps set out with you',         icon: 'clipboard-outline' },
  { key: 'referrals',     label: 'Referrals',     desc: 'Referrals to specialists and their progress',   icon: 'swap-horizontal-outline' },
  { key: 'documents',     label: 'Documents',     desc: 'Reports and files in your record',              icon: 'document-outline' },
  { key: 'health-alerts', label: 'Health Alerts', desc: 'Reminders and results your doctor confirmed',   icon: 'shield-checkmark-outline' },
];

function YourCare({ navigate }: { navigate: Navigate }) {
  return (
    <View style={styles.section}>
      <View style={styles.sectionHeader}>
        <Text style={styles.sectionTitle} accessibilityRole="header" {...ariaLevel(2)}>Your Care</Text>
      </View>
      <View style={styles.careGrid}>
        {careLinks.map(link => (
          <TouchableOpacity
            key={link.key}
            style={styles.careLink}
            activeOpacity={0.8}
            onPress={() => navigate(link.key)}
            accessibilityRole="button"
            accessibilityLabel={`${link.label}. ${link.desc}`}
          >
            <View style={styles.careIcon}>
              <Icon name={link.icon} size={20} color={TEAL} />
            </View>
            <View style={styles.careText}>
              <Text style={styles.careLabel}>{link.label}</Text>
              <Text style={styles.careDesc}>{link.desc}</Text>
            </View>
            <Icon name="chevron-forward" size={16} color={GRAY} />
          </TouchableOpacity>
        ))}
      </View>
    </View>
  );
}

// ── Main export ──────────────────────────────────────────────────────

export type PatientMainDashboardAreaProps = Pick<SectionProps, 'navigate'> & Partial<Pick<SectionProps, 'theme'>>;

/**
 * The patient's home section (`/dashboard/patient`). Every card links to the section that holds its details,
 * through the dashboard's `navigate`, so the URL, Back and refresh behave as for the sidebar.
 */
export function PatientMainDashboardArea({ navigate, theme = ROLE_THEME.patient }: PatientMainDashboardAreaProps) {
  const { width } = useWindowDimensions();
  const isSmall = width < 768;
  const { user, loading: userLoading, error: userError, refetch: refetchUser } = useCurrentUser();
  const ownId = user?.system_id ?? null;
  // The dashboard and the right rail share this list (one request for both).
  const appts = useAppointments();
  // Effective status ACTIVE, counted by the server; the card shows the newest three.
  const rx    = usePrescriptions({ status: 'ACTIVE', pageSize: 3 });

  return (
    <UiThemeProvider theme={theme}>
      <ScrollView style={styles.container} contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
        {/* The page heading of the home section, for screen-reader navigation; the cards below are level 2. */}
        <Text style={styles.srOnly} accessibilityRole="header" {...ariaLevel(1)}>Dashboard</Text>
        <QuickActions navigate={navigate} />
        <WelcomeBanner />
        <HealthOverview
          activeCount={rx.total}
          rxLoading={rx.loading}
          rxError={rx.error}
          onRetryRx={rx.refetch}
          onViewPrescriptions={() => navigate('prescriptions')}
          onViewProfile={() => navigate('profile-settings')}
        />
        <View style={[styles.twoCol, isSmall && styles.stack]}>
          <View style={styles.col}>
            <NextDoseCard navigate={navigate} />
          </View>
          <View style={styles.col}>
            {ownId ? (
              <AmountDueCard patientSystemId={ownId} onViewBilling={() => navigate('billing')} />
            ) : (
              // The balance is read by the patient's id: until the account has loaded there is nothing to ask for,
              // and a failed account read gets its own Retry instead of a card that waits forever.
              <SectionCard title="Amount due">
                {userLoading
                  ? <LoadingView compact label="Loading your balance" />
                  : <ErrorView compact message={userError ?? 'Your account could not be loaded.'} onRetry={refetchUser} />}
              </SectionCard>
            )}
          </View>
        </View>
        <LatestVitalsCard theme={theme} onOpen={() => navigate('health-tracking')} />
        <UpcomingAppointments
          appointments={appts.appointments}
          loading={appts.loading}
          error={appts.error}
          onRetry={appts.refetch}
          navigate={navigate}
        />
        <View style={[styles.twoCol, isSmall && styles.stack]}>
          <RecentConsultations navigate={navigate} />
          <CurrentPrescriptions
            prescriptions={rx.prescriptions}
            loading={rx.loading}
            error={rx.error}
            onRetry={rx.refetch}
            navigate={navigate}
          />
        </View>
        <YourCare navigate={navigate} />
      </ScrollView>
    </UiThemeProvider>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: GRAY_LIGHT },
  // Visually hidden, still read by screen readers.
  srOnly:    { position: 'absolute', width: 1, height: 1, overflow: 'hidden', opacity: 0 },
  scroll:    { padding: 20, gap: 16 },

  quickRow:    { flexDirection: 'row', gap: 12, flexWrap: 'wrap' },
  quickCardBase: { backgroundColor: WHITE, borderRadius: 12, padding: 14, borderWidth: 1, borderColor: BORDER, gap: 4 },
  quickCardWide: { flex: 1, minWidth: 100 },
  quickCardCompact: { flexGrow: 1, flexShrink: 1, flexBasis: 130, minWidth: 0 },
  quickIconBg: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center', marginBottom: 4 },
  quickLabel:  { fontSize: 13, fontWeight: '700', color: TEXT, lineHeight: 18 },
  quickDesc:   { fontSize: 11, color: GRAY, lineHeight: 15 },
  quickArrow:  { fontSize: 14, color: GRAY, marginTop: 4 },

  banner:           { backgroundColor: '#F0F4FF', borderRadius: 14, padding: 20, flexDirection: 'row', alignItems: 'center', gap: 16, borderWidth: 1, borderColor: BORDER, overflow: 'hidden' },
  bannerAvatar:     { width: 80, height: 80, borderRadius: 40, backgroundColor: BORDER },
  bannerIcon:       { backgroundColor: '#E6F4F1', alignItems: 'center', justifyContent: 'center' },
  bannerText:       { flex: 1, minWidth: 0 },
  bannerTitle:      { fontSize: 18, fontWeight: '700', color: NAVY, marginBottom: 6 },
  bannerDesc:       { fontSize: 13, color: '#374151', lineHeight: 20 },
  bannerDecor:      { position: 'absolute', right: -20, top: -20, opacity: 0.15 },
  decorCircleOuter: { width: 140, height: 140, borderRadius: 70, borderWidth: 24, borderColor: TEAL },
  decorCircleInner: { width: 80, height: 80, borderRadius: 40, borderWidth: 16, borderColor: TEAL, position: 'absolute', right: 10, bottom: -30 },

  section:       { backgroundColor: WHITE, borderRadius: 14, padding: 16, borderWidth: 1, borderColor: BORDER, gap: 2, minWidth: 0 },
  sectionHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12, gap: 8, flexWrap: 'wrap' },
  sectionTitle:  { fontSize: 15, fontWeight: '700', color: TEXT },
  link:          { fontSize: 13, color: LINK, fontWeight: '500' },

  metricsRow: { flexDirection: 'row', gap: 10, flexWrap: 'wrap' },
  metricCard: { borderRadius: 10, borderWidth: 1, borderColor: BORDER, padding: 12, gap: 4, alignItems: 'flex-start' },
  metricCardWide:    { flex: 1, minWidth: 100 },
  metricCardCompact: { flexGrow: 1, flexShrink: 1, flexBasis: 130, minWidth: 0 },
  metricIconBg: { width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center', marginBottom: 4 },
  metricLabel:  { fontSize: 11, color: GRAY },
  metricValue:  { fontSize: 22, fontWeight: '700', color: TEXT },
  metricSub:    { fontSize: 11, color: GRAY, lineHeight: 16 },

  twoCol:        { flexDirection: 'row', gap: 16 },
  stack:         { flexDirection: 'column' },
  col:           { flex: 1, minWidth: 0 },
  consultRow:    { flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 10, borderTopWidth: 1, borderTopColor: BORDER },
  consultIconBg: { width: 38, height: 38, borderRadius: 10, backgroundColor: '#E6F4F1', alignItems: 'center', justifyContent: 'center' },
  consultInfo:   { flex: 1, minWidth: 0 },
  consultDate:   { fontSize: 11, color: GRAY },
  consultTitle:  { fontSize: 13, fontWeight: '600', color: TEXT },
  consultDoctor: { fontSize: 12, color: GRAY },
  badge:         { backgroundColor: '#E6F4F1', borderRadius: 6, paddingHorizontal: 8, paddingVertical: 3 },
  badgeText:     { fontSize: 11, color: TEAL, fontWeight: '600' },
  viewAllRow:    { alignItems: 'center', paddingTop: 12 },

  remindersHeader: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  remindersRow:    { flexDirection: 'row', gap: 12 },
  reminderCard:    { flex: 1, minWidth: 0, flexDirection: 'row', alignItems: 'center', gap: 10, backgroundColor: WHITE, borderRadius: 12, padding: 14, borderWidth: 1, borderColor: BORDER },
  reminderIconBg:  { width: 36, height: 36, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  reminderInfo:    { flex: 1, minWidth: 0 },
  reminderLabel:   { fontSize: 13, fontWeight: '600', color: TEXT },
  reminderSub:     { fontSize: 11, color: GRAY },

  doseBody:  { gap: 8, minWidth: 0 },
  doseRow:   { flexDirection: 'row', alignItems: 'center', gap: 12, flexWrap: 'wrap' },
  doseIcon:  { width: 40, height: 40, borderRadius: 10, backgroundColor: '#E6F4F1', alignItems: 'center', justifyContent: 'center' },
  doseInfo:  { flex: 1, minWidth: 140 },
  doseName:  { fontSize: 15, fontWeight: '700', color: TEXT },
  doseTime:  { fontSize: 13, color: '#4B5563', marginTop: 2 },
  doseMeta:  { fontSize: 13, color: '#4B5563', lineHeight: 19 },

  careGrid:  { flexDirection: 'row', flexWrap: 'wrap', gap: 10 },
  careLink:  { flexGrow: 1, flexBasis: 240, minWidth: 0, flexDirection: 'row', alignItems: 'center', gap: 12, borderRadius: 12, borderWidth: 1, borderColor: BORDER, padding: 12, backgroundColor: WHITE },
  careIcon:  { width: 38, height: 38, borderRadius: 10, backgroundColor: '#E6F4F1', alignItems: 'center', justifyContent: 'center' },
  careText:  { flex: 1, minWidth: 0 },
  careLabel: { fontSize: 13, fontWeight: '600', color: TEXT },
  careDesc:  { fontSize: 12, color: GRAY, lineHeight: 16 },

  emptyRow:  { flexDirection: 'row', alignItems: 'center', gap: 12, padding: 12, flexWrap: 'wrap' },
  errorRow:  { flexDirection: 'row', alignItems: 'center', gap: 8, padding: 12, backgroundColor: '#FEF2F2', borderRadius: 10 },
  errorText: { flex: 1, fontSize: 13, color: RED },
  retryText: { fontSize: 13, fontWeight: '700', color: RED, textDecorationLine: 'underline' },
});
