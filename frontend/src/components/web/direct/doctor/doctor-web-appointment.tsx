import { type ReactNode, useCallback, useEffect, useMemo, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import {
  ACTION_LABELS, availableActions, canReschedule, canSendReminder, consultationEntryPoint, doctorCallJoin,
  useAppointmentReminder, useDoctorAppointmentActions,
  type DoctorAppointmentAction,
} from '@/hooks/use-doctor-appointment-actions';
import { useDoctorMessagePatient } from '@/hooks/use-doctor-message-patient';
import {
  addClinicDays, applyAppointmentUpdate, clinicDayStart, clinicWeekStart, fetchAppointmentStats, fetchDoctorAppointmentEntry,
  fetchDoctorPatientName, fetchNeedsOutcome, searchDoctorPatients, useDoctorSchedule,
  type ApiAppointment, type ApiAppointmentStatus, type DoctorScheduleQuery, type ScheduleEntry,
} from '@/hooks/use-doctor-schedule';
import { useResource } from '@/hooks/use-resource';
import { useSectionNav } from '@/hooks/use-section-nav';
import { CallJoinButton } from '@/components/web/call/call-join-button';
import {
  accessibleName, ariaHidden, ariaLevel, ariaPressed, Banner, type BannerTone, Button, Card, colors, EmptyView, ErrorView, fontSize,
  Icon, IconButton, InfoRow, type IoniconsName, LoadingView, NoAccessView, radius, Screen, space, StatusBadge,
  statusLabel, statusTone, type StatusTone, Tabs, TextField, useUiTheme,
} from '@/components/web/ui';
import { callStatusLabel } from '@/lib/calls/join';
import type { SectionProps } from '@/lib/nav/sections';
import {
  clinicNowLocal, formatClinicDate as formatDay, formatClinicDateTime, formatClinicTime as formatTimeOfDay, isClinicToday,
} from '@/lib/time/clinic';
import { DoctorBookingDialog } from './doctor-booking-dialog';
import { DoctorReschedulePanel, formatClinicDate, formatClinicTime } from './doctor-reschedule-panel';

type AppType = ScheduleEntry['type'];
type TypeFilter = 'ALL' | AppType;
type StatusFilter = 'ALL' | ApiAppointmentStatus;
/** The list being shown. Kept in the URL as `tab` so a refresh, Back and notification links keep it. */
type ViewTab = 'all' | 'today' | 'week' | 'upcoming' | 'past' | 'needs-outcome';

const VIEW_TABS: readonly ViewTab[] = ['all', 'today', 'week', 'upcoming', 'past', 'needs-outcome'];
const API_STATUSES: readonly ApiAppointmentStatus[] = ['SCHEDULED', 'RESCHEDULED', 'COMPLETED', 'CANCELLED', 'NO_SHOW'];
const PATIENT_ID = /^PAT-\d{5,}$/;
const DATE_PARAM = /^\d{4}-\d{2}-\d{2}$/;
const PAGE_SIZE = 20;

// ─── Presentation helpers (shared with the Schedule screen) ───────────────────

const STATUS_TEXT: Record<string, string> = {
  SCHEDULED: 'Scheduled', COMPLETED: 'Completed', CANCELLED: 'Cancelled', NO_SHOW: 'No Show',
  RESCHEDULED: 'Rescheduled', IN_PROGRESS: 'In Progress',
};

/** The words for an appointment status ("No Show", "Rescheduled"). */
export function appointmentStatusText(status: string): string {
  return STATUS_TEXT[status] ?? statusLabel(status);
}

/**
 * The status badge of an appointment. One that ended without an outcome is labelled "Awaiting outcome" instead of
 * Scheduled or Rescheduled; the server decides that (`needs_outcome`), not the device clock.
 */
export function appointmentBadge(entry: ScheduleEntry): { label: string; tone: StatusTone; icon?: IoniconsName } {
  if (entry.needs_outcome) return { label: 'Awaiting outcome', tone: 'warning', icon: 'hourglass-outline' };
  return { label: appointmentStatusText(entry.status), tone: statusTone('appointment', entry.status) };
}

const TYPE_META: Record<AppType, { icon: IoniconsName; label: string; accent: string }> = {
  IN_PERSON: { icon: 'location-outline', label: 'In Person', accent: '#047857' },
  VIDEO:     { icon: 'videocam-outline', label: 'Video',     accent: '#4F46E5' },
  PHONE:     { icon: 'call-outline',     label: 'Phone',     accent: '#B45309' },
};

export function appointmentTypeMeta(type: AppType): { icon: IoniconsName; label: string; accent: string } {
  return TYPE_META[type] ?? { icon: 'calendar-outline', label: String(type), accent: colors.border };
}

function consultationBadge(c: ScheduleEntry['consultation']): { label: string; tone: StatusTone } {
  if (!c || c.status === 'CANCELLED') return { label: 'No consultation', tone: 'neutral' };
  switch (c.status) {
    case 'DRAFT':       return { label: 'Consultation: Draft',       tone: 'warning' };
    case 'IN_PROGRESS': return { label: 'Consultation: In progress', tone: 'info'    };
    default:            return { label: 'Consultation: Completed',   tone: 'success' };
  }
}

/** What to say about reminders on a row, or null when none went out. */
export function reminderText(entry: ScheduleEntry): string | null {
  if (entry.reminder_sent_at) return `Reminder sent ${formatClinicDateTime(entry.reminder_sent_at, entry.reminder_sent_at)}`;
  return entry.reminder_sent ? 'Automatic reminder sent' : null;
}

const AVATAR_COLORS = ['#4F46E5', '#0F766E', '#B45309', '#B91C1C', '#6D28D9', '#047857'];
function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}
function initials(name: string) {
  return name.split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
}

// ─── Row actions shared by the Appointments and Schedule screens ──────────────

export type AppointmentChange = 'status' | 'rescheduled' | 'reminder';

export interface AppointmentFeedback {
  appointmentId: number;
  tone:          BannerTone;
  text:          string;
}

export interface AppointmentController {
  runAction:         (entry: ScheduleEntry, action: DoctorAppointmentAction) => void;
  actionPendingId:   number | null;
  sendReminder:      (entry: ScheduleEntry) => void;
  reminderPendingId: number | null;
  rescheduled:       (entry: ScheduleEntry, date: string, time: string, updated?: ApiAppointment) => void;
  /** Opens the consultation of an appointment in the Consultations section; absent when that section is not available. */
  openConsultation?: (entry: ScheduleEntry) => void;
  /** Opens the private conversation with the patient; absent when Messages is not available. */
  messagePatient?:   (entry: ScheduleEntry) => void;
  messagingPatient:  string | null;
  /** The result of the last reminder or reschedule, shown on its row. */
  feedback:          AppointmentFeedback | null;
  dismissFeedback:   () => void;
}

/**
 * Everything an appointment row can do: Complete / No-show / Cancel, Reschedule, Send reminder, Start or Continue
 * consultation and Message patient. `onChanged` receives the row as it is after an action, so the screen can
 * update its list and counts.
 */
export function useAppointmentController({ navigate, onOpenThread, onChanged }: {
  navigate?:     SectionProps['navigate'];
  onOpenThread?: (threadId: number) => void;
  onChanged:     (updated: ScheduleEntry, change: AppointmentChange) => void;
}): AppointmentController {
  const [feedback, setFeedback] = useState<AppointmentFeedback | null>(null);
  const actions  = useDoctorAppointmentActions(updated => { setFeedback(null); onChanged(updated, 'status'); });
  const reminder = useAppointmentReminder();
  const messages = useDoctorMessagePatient(onOpenThread);

  async function sendReminder(entry: ScheduleEntry) {
    const outcome = await reminder.send(entry);
    if (outcome.ok) {
      const at = formatClinicDateTime(outcome.result.reminderSentAt, 'earlier this hour');
      setFeedback({
        appointmentId: entry.appointment_id,
        tone: outcome.result.alreadySent ? 'info' : 'success',
        text: outcome.result.alreadySent
          ? `A reminder was already sent to ${entry.patient_name} on ${at}. Another one can be sent in the next hour.`
          : `Reminder sent to ${entry.patient_name}.`,
      });
      onChanged(outcome.entry, 'reminder');
    } else if (outcome.error) {
      // A 429 arrives here as "Too many attempts. Try again in N minutes."
      setFeedback({ appointmentId: entry.appointment_id, tone: 'danger', text: outcome.error.message });
    }
  }

  function rescheduled(entry: ScheduleEntry, date: string, time: string, updated?: ApiAppointment) {
    setFeedback({
      appointmentId: entry.appointment_id,
      tone: 'success',
      text: `Appointment with ${entry.patient_name} moved to ${formatClinicDate(date)}, ${formatClinicTime(time)} CAT.`,
    });
    // The automatic reminder is re-armed by a move; the manual reminder time is kept.
    const next: ScheduleEntry = updated
      ? applyAppointmentUpdate(entry, updated)
      : { ...entry, scheduled_at: `${date}T${time}:00`, status: 'RESCHEDULED', reminder_sent: false, needs_outcome: false };
    onChanged(next, 'rescheduled');
  }

  return {
    runAction:         actions.run,
    actionPendingId:   actions.pendingId,
    sendReminder,
    reminderPendingId: reminder.pendingId,
    rescheduled,
    openConsultation:  navigate
      ? entry => navigate('consultations', { appointmentId: entry.appointment_id, mode: 'edit' })
      : undefined,
    messagePatient:    messages.enabled ? entry => messages.messagePatient(entry.patient_system_id) : undefined,
    messagingPatient:  messages.pendingPatient,
    feedback,
    dismissFeedback:   () => setFeedback(null),
  };
}

// ─── Appointment card ─────────────────────────────────────────────────────────

/** One appointment with its details and every action that applies to it right now. */
export function AppointmentCard({ entry, controller, defaultExpanded = false, headingLevel = 2 }: {
  entry:            ScheduleEntry;
  controller:       AppointmentController;
  /** Start with the details open (an appointment opened from a link). */
  defaultExpanded?: boolean;
  /**
   * Heading level of the patient name: 2 in a plain list, 3 under a level-2 group heading (a day of the schedule,
   * "Opened appointment"). The reschedule panel's heading is one level below it.
   */
  headingLevel?:    2 | 3;
}) {
  const { accent } = useUiTheme();
  const [expanded, setExpanded] = useState(defaultExpanded);
  const [rescheduling, setRescheduling] = useState(false);

  const actions       = availableActions(entry);
  const reschedulable = canReschedule(entry);
  const remindable    = canSendReminder(entry);
  const consult       = controller.openConsultation ? consultationEntryPoint(entry) : null;
  const badge         = appointmentBadge(entry);
  const type          = appointmentTypeMeta(entry.type);
  const consultation  = consultationBadge(entry.consultation);
  const reminder      = reminderText(entry);
  const busy          = controller.actionPendingId === entry.appointment_id;
  const reminding     = controller.reminderPendingId === entry.appointment_id;
  const messaging     = controller.messagingPatient === entry.patient_system_id;
  const feedback      = controller.feedback?.appointmentId === entry.appointment_id ? controller.feedback : null;
  const name          = entry.patient_name;
  const hasActionRow  = actions.length > 0 || reschedulable || remindable || !!consult || !!controller.messagePatient;

  return (
    <Card padded={false} style={[styles.card, { borderLeftColor: type.accent }]}>
      {isClinicToday(entry.scheduled_at) && (
        <View style={styles.todayBanner}>
          <Icon name="radio-button-on" size={10} color={colors.text} />
          <Text style={styles.todayText}>Today</Text>
        </View>
      )}

      <View style={styles.body}>
        <View style={styles.left}>
          <View style={[styles.avatar, { backgroundColor: avatarColor(name) }]} {...ariaHidden}>
            <Text style={styles.avatarText}>{initials(name)}</Text>
          </View>
          <View style={styles.patientCol}>
            {/* Each card is a section named by its patient, so screen-reader users can move card to card by heading. */}
            <Text style={styles.patientName} accessibilityRole="header" {...ariaLevel(headingLevel)}>{name}</Text>
            <Text style={styles.apptId}>Appt #{entry.appointment_id}</Text>
            <View style={styles.metaRow}>
              <Icon name="calendar-outline" size={12} color={colors.textMuted} />
              <Text style={styles.metaText}>{formatDay(entry.scheduled_at, entry.scheduled_at)}</Text>
            </View>
            <View style={styles.metaRow}>
              <Icon name="time-outline" size={12} color={colors.textMuted} />
              <Text style={styles.metaText}>{formatTimeOfDay(entry.scheduled_at)} · {entry.duration_minutes} min</Text>
            </View>
          </View>
        </View>

        <View style={styles.center}>
          {!!entry.reason_for_visit && (
            <View style={styles.detailRow}>
              <Icon name="clipboard-outline" size={13} color={colors.textMuted} style={styles.detailIcon} />
              <Text style={styles.detailText} numberOfLines={2}>{entry.reason_for_visit}</Text>
            </View>
          )}
          {!!entry.facility_name && (
            <View style={styles.detailRow}>
              <Icon name="business-outline" size={13} color={colors.textMuted} style={styles.detailIcon} />
              <Text style={styles.detailMuted}>{entry.facility_name}</Text>
            </View>
          )}
          {entry.type === 'PHONE' && (
            <View style={styles.detailRow}>
              <Icon name="call-outline" size={13} color={colors.textMuted} style={styles.detailIcon} />
              {entry.contact_phone ? (
                <Text style={styles.detailText} selectable>Call {entry.contact_phone}</Text>
              ) : (
                <Text style={styles.detailMuted}>No contact number on this appointment</Text>
              )}
            </View>
          )}
          {entry.type === 'VIDEO' && !entry.facility_name && (
            <View style={styles.detailRow}>
              <Icon name="globe-outline" size={13} color={colors.textMuted} style={styles.detailIcon} />
              <Text style={styles.detailMuted}>Virtual</Text>
            </View>
          )}
          {!!entry.notes && (
            <View style={styles.detailRow}>
              <Icon name="chatbubble-outline" size={13} color={colors.textMuted} style={styles.detailIcon} />
              <Text style={styles.detailMuted} numberOfLines={2}>{entry.notes}</Text>
            </View>
          )}
          {!!reminder && (
            <View style={styles.detailRow}>
              <Icon name="notifications-outline" size={13} color={colors.textMuted} style={styles.detailIcon} />
              <Text style={styles.detailMuted}>{reminder}</Text>
            </View>
          )}
        </View>

        <View style={styles.right}>
          <View style={styles.badges}>
            <StatusBadge label={type.label} tone="neutral" icon={type.icon} />
            <StatusBadge label={badge.label} tone={badge.tone} icon={badge.icon} />
            <StatusBadge label={consultation.label} tone={consultation.tone} icon="document-text-outline" />
          </View>
          <CallJoinButton
            info={doctorCallJoin(entry)}
            withName={name}
            style={[styles.joinBtn, { borderColor: accent }]}
            textStyle={[styles.joinBtnText, { color: accent }]}
            iconColor={accent}
            iconSize={13}
          />
          <Pressable
            style={styles.detailsBtn}
            onPress={() => setExpanded(v => !v)}
            accessibilityRole="button"
            // Starts with the visible words ("Details" / "Hide details"); the rest keeps the earlier name
            // ("... appointment details"), which the e2e specs use to find a card.
            accessibilityLabel={expanded
              ? accessibleName('Hide details', 'hide appointment details')
              : accessibleName('Details', 'show appointment details')}
            accessibilityState={{ expanded }}
            aria-expanded={expanded}
          >
            <Text style={[styles.detailsBtnText, { color: accent }]}>{expanded ? 'Hide details' : 'Details'}</Text>
            <Icon name={expanded ? 'chevron-up' : 'chevron-down'} size={13} color={accent} />
          </Pressable>
        </View>
      </View>

      {expanded && (
        <View style={styles.detailsPanel}>
          <InfoRow label="Patient ID" value={entry.patient_system_id} />
          <InfoRow label="Appointment" value={`#${entry.appointment_id}`} />
          <InfoRow label="Date & time" value={formatClinicDateTime(entry.scheduled_at, entry.scheduled_at)} />
          <InfoRow label="Duration" value={`${entry.duration_minutes} min`} />
          <InfoRow label="Type" value={type.label} />
          <InfoRow
            label="Status"
            value={entry.needs_outcome ? `Awaiting outcome (${appointmentStatusText(entry.status).toLowerCase()})` : appointmentStatusText(entry.status)}
          />
          <InfoRow label="Consultation" value={consultation.label} />
          {entry.type === 'IN_PERSON' && <InfoRow label="Facility" value={entry.facility_name} emptyText="No facility recorded" />}
          {entry.type === 'PHONE' && <InfoRow label="Contact number" value={entry.contact_phone} emptyText="No contact number recorded" />}
          {entry.type === 'VIDEO' && <InfoRow label="Video call" value={callStatusLabel(entry.call_status)} />}
          <InfoRow label="Reason" value={entry.reason_for_visit} emptyText="No reason given" />
          <InfoRow label="Notes" value={entry.notes} emptyText="No notes" />
          <InfoRow label="Reminders" value={reminder} emptyText="No reminder sent yet" />
        </View>
      )}

      {rescheduling && reschedulable && (
        <DoctorReschedulePanel
          entry={entry}
          headingLevel={headingLevel === 2 ? 3 : 4}
          onClose={() => setRescheduling(false)}
          onDone={(date, time, updated) => { setRescheduling(false); controller.rescheduled(entry, date, time, updated); }}
        />
      )}

      {!!feedback && (
        <View style={styles.feedback}>
          <Banner tone={feedback.tone} text={feedback.text} onDismiss={controller.dismissFeedback} />
        </View>
      )}

      {hasActionRow && (
        <View style={styles.actionRow}>
          {!!controller.messagePatient && (
            <Button
              label="Message patient"
              accessibilityLabel={`Message patient ${name}`}
              icon="chatbubble-ellipses-outline"
              variant="ghost"
              size="sm"
              loading={messaging}
              onPress={() => controller.messagePatient?.(entry)}
            />
          )}
          {!!consult && (
            <Button
              label={consult.label}
              accessibilityLabel={`${consult.label} with ${name}`}
              icon={consult.kind === 'view' ? 'document-text-outline' : 'create-outline'}
              variant={consult.kind === 'view' ? 'secondary' : 'primary'}
              size="sm"
              onPress={() => controller.openConsultation?.(entry)}
            />
          )}
          {remindable && (
            <Button
              label="Send reminder"
              accessibilityLabel={`Send reminder to ${name}`}
              icon="notifications-outline"
              variant="secondary"
              size="sm"
              loading={reminding}
              disabled={busy}
              onPress={() => controller.sendReminder(entry)}
            />
          )}
          {reschedulable && !rescheduling && (
            <Button
              label="Reschedule"
              accessibilityLabel={`Reschedule appointment with ${name}`}
              icon="calendar-outline"
              variant="secondary"
              size="sm"
              disabled={busy}
              onPress={() => setRescheduling(true)}
            />
          )}
          {actions.map(a => (
            <Button
              key={a}
              label={ACTION_LABELS[a]}
              accessibilityLabel={`${ACTION_LABELS[a]} appointment with ${name}`}
              icon={ACTION_ICON[a]}
              variant="secondary"
              size="sm"
              loading={busy}
              onPress={() => controller.runAction(entry, a)}
            />
          ))}
        </View>
      )}
    </Card>
  );
}

const ACTION_ICON: Record<DoctorAppointmentAction, IoniconsName> = {
  'complete': 'checkmark-circle-outline',
  'no-show':  'alert-circle-outline',
  'cancel':   'close-circle-outline',
};

// ─── Small pieces ─────────────────────────────────────────────────────────────

/** A count at the top of the screen. With `onPress` it is a button that opens the matching list. */
export function StatTile({ icon, label, value, onPress, hint, testID }: {
  icon: IoniconsName; label: string; value: number | null; onPress?: () => void; hint?: string; testID?: string;
}) {
  const { accent, accentBg } = useUiTheme();
  const shown = value === null ? '—' : String(value);
  const content = (
    <>
      <View style={[styles.statIcon, { backgroundColor: accentBg }]} {...ariaHidden}>
        <Icon name={icon} size={18} color={accent} />
      </View>
      <Text style={styles.statValue}>{shown}</Text>
      <Text style={styles.statLabel}>{label}</Text>
      {onPress ? <Text style={[styles.statLink, { color: accent }]}>{hint ?? 'View'}</Text> : null}
    </>
  );
  if (!onPress) {
    return <View style={styles.statTile} testID={testID} accessible accessibilityLabel={`${shown} ${label}`}>{content}</View>;
  }
  return (
    <Pressable
      style={({ pressed }) => [styles.statTile, pressed && styles.pressed]}
      onPress={onPress}
      accessibilityRole="button"
      // In the order the words are shown (number, label, link text), so the name contains the visible text.
      accessibilityLabel={`${shown} ${label}, ${hint ?? 'View'}`}
      testID={testID}
    >
      {content}
    </Pressable>
  );
}

/** A filter choice that stays a button and reports whether it is on. */
export function FilterPill({ label, selected, onPress, icon }: {
  label: string; selected: boolean; onPress: () => void; icon?: IoniconsName;
}) {
  const { accent, accentBg } = useUiTheme();
  return (
    <Pressable
      style={[styles.pill, selected && { backgroundColor: accentBg, borderColor: accent }]}
      onPress={onPress}
      accessibilityRole="button"
      {...ariaPressed(selected)}
    >
      {icon ? <Icon name={icon} size={12} color={selected ? accent : colors.textMuted} /> : null}
      <Text style={[styles.pillText, selected && { color: accent }]}>{label}</Text>
    </Pressable>
  );
}

// ─── Screen ───────────────────────────────────────────────────────────────────

const TAB_LABEL: Record<ViewTab, string> = {
  'all': 'All Time', 'today': 'Today', 'week': 'This Week', 'upcoming': 'Upcoming', 'past': 'Past',
  'needs-outcome': 'Needs outcome',
};

const STATUS_PILLS: readonly { key: StatusFilter; label: string }[] = [
  { key: 'ALL',         label: 'All'         },
  { key: 'SCHEDULED',   label: 'Scheduled'   },
  { key: 'RESCHEDULED', label: 'Rescheduled' },
  { key: 'COMPLETED',   label: 'Completed'   },
  { key: 'CANCELLED',   label: 'Cancelled'   },
  { key: 'NO_SHOW',     label: 'No Show'     },
];

const TYPE_PILLS: readonly { key: TypeFilter; label: string; icon: IoniconsName }[] = [
  { key: 'ALL',       label: 'All Types', icon: 'apps-outline'     },
  { key: 'IN_PERSON', label: 'In Person', icon: 'location-outline' },
  { key: 'VIDEO',     label: 'Video',     icon: 'videocam-outline' },
  { key: 'PHONE',     label: 'Phone',     icon: 'call-outline'     },
];

function isViewTab(value: string | undefined): value is ViewTab {
  return !!value && (VIEW_TABS as readonly string[]).includes(value);
}

/** The request behind each tab. "Today", "this week", "upcoming" and "past" are bounded in clinic time. */
function queryFor(tab: ViewTab, status: StatusFilter, patient: string | null, clock: string): DoctorScheduleQuery {
  const base: DoctorScheduleQuery = { patientSystemId: patient, pageSize: PAGE_SIZE };
  const today = clock.slice(0, 10);
  const chosen = status === 'ALL' ? undefined : [status];
  switch (tab) {
    case 'needs-outcome':
      return { ...base, source: 'needs-outcome' };
    case 'today':
      return { ...base, status: chosen, order: 'asc', from: clinicDayStart(today), to: clinicDayStart(addClinicDays(today, 1)) };
    case 'week': {
      const monday = clinicWeekStart(today);
      return { ...base, status: chosen, order: 'asc', from: clinicDayStart(monday), to: clinicDayStart(addClinicDays(monday, 7)) };
    }
    case 'upcoming':
      // Upcoming = still active and not started yet.
      return { ...base, status: chosen ?? ['SCHEDULED', 'RESCHEDULED'], order: 'asc', from: clock };
    case 'past':
      return { ...base, status: chosen, order: 'desc', to: clock };
    default:
      return { ...base, status: chosen, order: 'desc' };
  }
}

/**
 * The doctor's appointments: a paged, server-filtered list with the actions of each row.
 *
 * URL params: `tab` (all | today | week | upcoming | past | needs-outcome), `status`, `patient` (PAT id: only that
 * patient's appointments, shown as a removable chip) and `id` (an appointment to open, for example from a
 * notification). Rendered with `SectionProps`; the older `onOpenThread` prop keeps working.
 */
export default function DoctorWebAppointment(props: Partial<SectionProps> & {
  /** Switch to Messages with this thread open (legacy wiring; `openThread` of SectionProps wins). */
  onOpenThread?: (threadId: number) => void;
} = {}) {
  const nav = useSectionNav();
  const params = props.params ?? nav.params;
  const navigate = props.navigate ?? nav.navigate;
  const openThread = props.openThread ?? props.onOpenThread ?? (nav.hasSection('messages') ? nav.openThread : undefined);
  const { setParams } = nav;

  const tab: ViewTab = isViewTab(params.tab) ? params.tab : 'all';
  const requestedStatus = (params.status ?? '').toUpperCase().replace(/-/g, '_');
  const statusParam: StatusFilter = (API_STATUSES as readonly string[]).includes(requestedStatus)
    ? (requestedStatus as ApiAppointmentStatus) : 'ALL';
  // Upcoming appointments are active by definition: other statuses do not apply to that tab.
  const status: StatusFilter = tab === 'upcoming' && statusParam !== 'SCHEDULED' && statusParam !== 'RESCHEDULED' ? 'ALL' : statusParam;
  const patient = params.patient && PATIENT_ID.test(params.patient) ? params.patient : null;
  const linkedId = params.id && /^\d{1,9}$/.test(params.id) ? Number(params.id) : null;
  // mode=book opens the booking form (for `patient` when given, starting on `date`): the Book follow-up link of a
  // consultation and the Book appointment button below.
  const booking = params.mode === 'book';
  const bookingDate = params.date && DATE_PARAM.test(params.date) ? params.date : null;
  const [booked, setBooked] = useState<string | null>(null);

  // A `patient` value that is not a patient id cannot be a filter: drop it from the URL instead of showing everyone silently.
  useEffect(() => {
    if (params.patient && !patient) setParams({ patient: undefined });
  }, [params.patient, patient, setParams]);

  // The clinic time the time-bounded tabs are measured from; renewed on Refresh and when the tab changes.
  const [clock, setClock] = useState(() => clinicNowLocal());
  const [typeFilter, setTypeFilter] = useState<TypeFilter>('ALL');
  const [search, setSearch] = useState('');

  const query = useMemo(() => queryFor(tab, status, patient, clock), [tab, status, patient, clock]);
  const list = useDoctorSchedule(query);

  const stats = useResource(fetchAppointmentStats, []);
  // With a patient filter the badge counts that patient's rows; without one the stats already carry the count.
  const patientNeeds = useResource(
    () => fetchNeedsOutcome(patient, 0, 1).then(page => page.totalElements),
    [patient],
    { enabled: !!patient && tab !== 'needs-outcome' },
  );
  const needsOutcomeCount = tab === 'needs-outcome' && !list.loading && !list.error
    ? list.total
    : patient ? patientNeeds.data ?? 0 : stats.data?.needsOutcomeCount ?? 0;

  const patientName = useResource(() => fetchDoctorPatientName(patient ?? ''), [patient], { enabled: !!patient });
  const linked = useResource(() => fetchDoctorAppointmentEntry(linkedId ?? 0), [linkedId], {
    enabled: linkedId !== null, errorMessage: 'That appointment could not be opened.',
  });

  // Patients whose name matches the search text, offered as a server-side filter.
  const [lookup, setLookup] = useState('');
  useEffect(() => {
    const t = setTimeout(() => setLookup(search.trim()), 300);
    return () => clearTimeout(t);
  }, [search]);
  const suggestions = useResource(() => searchDoctorPatients(lookup), [lookup], { enabled: lookup.length >= 2 && !patient });

  const { updateEntry, refetch: refetchList } = list;
  const { refetch: refetchStats } = stats;
  const { refetch: refetchPatientNeeds } = patientNeeds;
  const { setData: setLinked } = linked;

  const onChanged = useCallback((updated: ScheduleEntry, change: AppointmentChange) => {
    setLinked(prev => (prev && prev.appointment_id === updated.appointment_id ? updated : prev));
    if (change === 'reminder') { updateEntry(updated.appointment_id, updated); return; }
    // A row that got an outcome (or a new time) leaves the Needs outcome list; elsewhere it is updated in place.
    if (tab === 'needs-outcome') refetchList();
    else updateEntry(updated.appointment_id, updated);
    refetchStats();
    refetchPatientNeeds();
  }, [tab, updateEntry, refetchList, refetchStats, refetchPatientNeeds, setLinked]);

  const controller = useAppointmentController({ navigate, onOpenThread: openThread, onChanged });

  function selectTab(next: ViewTab) {
    setClock(clinicNowLocal());
    setParams({ tab: next === 'all' ? undefined : next });
  }

  const openBooking = () => { setBooked(null); setParams({ mode: 'book' }); };
  const closeBooking = () => setParams({ mode: undefined, date: undefined });

  function refresh() {
    setClock(clinicNowLocal());
    list.refetch();
    stats.refetch();
    patientNeeds.refetch();
    linked.refetch();
  }

  const needle = search.trim().toLowerCase();
  const refined = typeFilter !== 'ALL' || needle.length > 0;
  const visible = useMemo(() => list.entries
    .filter(e => e.appointment_id !== linkedId)
    .filter(e => typeFilter === 'ALL' || e.type === typeFilter)
    .filter(e => !needle || e.patient_name.toLowerCase().includes(needle)),
  [list.entries, linkedId, typeFilter, needle]);

  const chipName = patientName.data
    ?? list.entries.find(e => e.patient_system_id === patient)?.patient_name
    ?? (linked.data?.patient_system_id === patient ? linked.data?.patient_name : undefined)
    ?? patient;

  const statusPills = tab === 'upcoming'
    ? STATUS_PILLS.filter(p => p.key === 'ALL' || p.key === 'SCHEDULED' || p.key === 'RESCHEDULED')
    : STATUS_PILLS;

  const feedbackOnRow = !!controller.feedback && (
    visible.some(e => e.appointment_id === controller.feedback?.appointmentId)
    || linked.data?.appointment_id === controller.feedback?.appointmentId
  );

  const count = visible.length;
  const countText = refined
    ? `${count} of ${list.entries.length} loaded appointment${list.entries.length === 1 ? '' : 's'} match`
    : list.total > list.entries.length
      ? `Showing ${list.entries.length} of ${list.total} appointments`
      : `${count} appointment${count === 1 ? '' : 's'}`;

  let body: ReactNode;
  if (list.loading && list.entries.length === 0) {
    body = <LoadingView label="Loading appointments…" compact />;
  } else if (list.error && list.entries.length === 0) {
    body = list.errorStatus === 403
      ? <NoAccessView compact />
      : <ErrorView message={list.error} onRetry={list.refetch} compact />;
  } else if (count === 0) {
    body = tab === 'needs-outcome' && !refined ? (
      <EmptyView
        icon="checkmark-done-outline"
        title="Nothing is awaiting an outcome"
        sub="Appointments that ended without being completed, cancelled or marked as no-show are listed here."
      />
    ) : (
      <EmptyView
        icon="calendar-outline"
        title="No appointments found"
        sub={refined || status !== 'ALL' || patient || tab !== 'all'
          ? 'Try adjusting your filters or search term'
          : 'Appointments booked with you will appear here.'}
      />
    );
  } else {
    body = (
      <View style={styles.list} role="list" aria-label="Appointments" aria-busy={list.refreshing || list.loadingMore}>
        {visible.map(e => (
          <View key={e.appointment_id} role="listitem" style={styles.listItem}>
            <AppointmentCard entry={e} controller={controller} />
          </View>
        ))}
      </View>
    );
  }

  return (
    <Screen
      title="Appointments"
      subtitle="Complete appointment history for your patients"
      theme={props.theme}
      role="doctor"
      actions={(
        <View style={styles.headerActions}>
          <Button label="Book appointment" icon="add" size="sm" onPress={openBooking} />
          <Button label="Refresh" icon="refresh-outline" variant="secondary" size="sm" onPress={refresh} loading={list.refreshing} />
        </View>
      )}
    >
      {booked ? <Banner tone="success" text={booked} onDismiss={() => setBooked(null)} /> : null}
      <DoctorBookingDialog
        open={booking}
        patientSystemId={patient}
        patientName={patient ? chipName : null}
        initialDate={bookingDate}
        onClose={closeBooking}
        onBooked={(appointment, name) => {
          closeBooking();
          setBooked(`Appointment booked${name ? ` for ${name}` : ''} on ${formatClinicDateTime(appointment.scheduledAt, appointment.scheduledAt)}.`);
          refresh();
        }}
      />
      <View style={styles.statsRow}>
        <StatTile icon="calendar-number-outline" label="Total" value={stats.data?.totalAppointments ?? null} />
        <StatTile
          icon="time-outline" label="Upcoming" value={stats.data?.scheduledCount ?? null}
          onPress={() => selectTab('upcoming')} hint="View upcoming"
        />
        <StatTile
          icon="hourglass-outline" label="Needs outcome" value={stats.data?.needsOutcomeCount ?? null}
          onPress={() => selectTab('needs-outcome')} hint="Review"
        />
        <StatTile icon="checkmark-circle-outline" label="Completed" value={stats.data?.completedCount ?? null} />
        <StatTile icon="close-circle-outline" label="Cancelled" value={stats.data?.cancelledCount ?? null} />
        <StatTile icon="alert-circle-outline" label="No Show" value={stats.data?.noShowCount ?? null} />
      </View>
      {!!stats.error && !stats.data && (
        <Banner tone="warning" text="The appointment counts could not be loaded." action={{ label: 'Retry', onPress: stats.refetch }} />
      )}

      <Tabs
        label="Appointment lists"
        tabs={VIEW_TABS.map(key => ({ key, label: TAB_LABEL[key], badge: key === 'needs-outcome' ? needsOutcomeCount : undefined }))}
        active={tab}
        onChange={selectTab}
      />

      <Card style={styles.filterCard}>
        {tab === 'needs-outcome' ? (
          <Text style={styles.filterNote}>
            These appointments ended but are still open. Record what happened: complete them, mark a no-show or cancel them.
          </Text>
        ) : (
          <View style={styles.pillRow} role="group" aria-label="Status">
            {statusPills.map(p => (
              <FilterPill
                key={p.key}
                label={p.label}
                selected={status === p.key}
                onPress={() => setParams({ status: p.key === 'ALL' ? undefined : p.key.toLowerCase().replace(/_/g, '-') })}
              />
            ))}
          </View>
        )}
        <View style={styles.pillRow} role="group" aria-label="Appointment type">
          {TYPE_PILLS.map(p => (
            <FilterPill key={p.key} label={p.label} icon={p.icon} selected={typeFilter === p.key} onPress={() => setTypeFilter(p.key)} />
          ))}
        </View>
        <View style={styles.searchWrap}>
          <TextField
            label="Search appointments by patient"
            value={search}
            onChange={setSearch}
            placeholder="Patient name"
            maxLength={100}
            autoCapitalize="none"
          />
        </View>
        {!patient && lookup.length >= 2 && (suggestions.data?.length ?? 0) > 0 && (
          <View style={styles.suggestions}>
            <Text style={styles.filterNote}>Show every appointment of one patient:</Text>
            <View style={styles.pillRow}>
              {(suggestions.data ?? []).slice(0, 6).map(option => (
                <Button
                  key={option.patientSystemId}
                  label={option.fullName}
                  accessibilityLabel={`${option.fullName}, show only this patient's appointments`}
                  icon="person-outline"
                  variant="secondary"
                  size="sm"
                  onPress={() => { setSearch(''); setParams({ patient: option.patientSystemId }); }}
                />
              ))}
            </View>
          </View>
        )}
        {refined && list.hasMore && (
          <Text style={styles.filterNote}>
            The type and name filters apply to the {list.entries.length} appointments loaded so far. Load more to look further.
          </Text>
        )}
      </Card>

      {!!patient && (
        <View style={styles.chipRow}>
          <View style={styles.chip}>
            <Icon name="person-outline" size={14} color={colors.textMuted} />
            <Text style={styles.chipText}>Patient: {chipName}</Text>
            <IconButton label="Remove patient filter" icon="close" size="sm" onPress={() => setParams({ patient: undefined })} />
          </View>
        </View>
      )}

      {linkedId !== null && (
        <View style={styles.linked}>
          <View style={styles.linkedHeader}>
            <Text style={styles.linkedTitle} accessibilityRole="header" {...ariaLevel(2)}>Opened appointment</Text>
            <Button label="Close" icon="close" variant="ghost" size="sm" accessibilityLabel="Close opened appointment" onPress={() => setParams({ id: undefined })} />
          </View>
          {linked.loading ? (
            <LoadingView label="Opening appointment…" compact />
          ) : linked.data ? (
            <AppointmentCard entry={linked.data} controller={controller} defaultExpanded headingLevel={3} />
          ) : (
            <Banner
              tone="warning"
              text={linked.error && linked.errorStatus !== 403 && linked.errorStatus !== 404
                ? linked.error
                : 'That appointment could not be opened. It may belong to another doctor or no longer exist.'}
              action={linked.error && linked.errorStatus !== 403 && linked.errorStatus !== 404 ? { label: 'Retry', onPress: linked.refetch } : undefined}
            />
          )}
        </View>
      )}

      {!!controller.feedback && !feedbackOnRow && (
        <Banner tone={controller.feedback.tone} text={controller.feedback.text} onDismiss={controller.dismissFeedback} />
      )}
      {!!list.error && list.entries.length > 0 && (
        <Banner tone="danger" text={list.error} action={{ label: 'Retry', onPress: list.refetch }} />
      )}

      <Text style={styles.count} role="status" aria-live="polite">{list.loading && list.entries.length === 0 ? ' ' : countText}</Text>

      {body}

      {list.hasMore && list.entries.length > 0 && (
        <View style={styles.footer}>
          <Button label="Load more" icon="chevron-down" variant="secondary" onPress={list.loadMore} loading={list.loadingMore} />
        </View>
      )}
    </Screen>
  );
}

// ─── Styles ──────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  headerActions: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
  statsRow: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  statTile: {
    flexGrow: 1, flexShrink: 1, flexBasis: 120, minWidth: 110,
    backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: radius.lg,
    padding: space.md, gap: 4,
  },
  pressed:   { opacity: 0.85 },
  statIcon:  { width: 34, height: 34, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  statValue: { fontSize: 22, fontWeight: '700', color: colors.text },
  statLabel: { fontSize: fontSize.xs, color: colors.textMuted },
  statLink:  { fontSize: fontSize.xs, fontWeight: '600' },

  filterCard:  { gap: space.md },
  filterNote:  { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  pillRow:     { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  pill: {
    flexDirection: 'row', alignItems: 'center', gap: 4, minHeight: 32,
    paddingHorizontal: space.md, paddingVertical: 6, borderRadius: radius.pill,
    borderWidth: 1, borderColor: colors.borderInput, backgroundColor: colors.surface,
  },
  pillText:    { fontSize: fontSize.sm, color: colors.textMuted, fontWeight: '500' },
  searchWrap:  { maxWidth: 420, width: '100%' },
  suggestions: { gap: space.sm },

  chipRow:  { flexDirection: 'row', flexWrap: 'wrap' },
  chip: {
    flexDirection: 'row', alignItems: 'center', gap: space.sm, maxWidth: '100%',
    paddingLeft: space.md, paddingRight: space.xs, paddingVertical: 2,
    borderWidth: 1, borderColor: colors.borderInput, borderRadius: radius.pill, backgroundColor: colors.surface,
  },
  chipText: { fontSize: fontSize.sm, fontWeight: '600', color: colors.text, flexShrink: 1 },

  linked:       { gap: space.sm },
  linkedHeader: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  linkedTitle:  { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },

  count:    { fontSize: fontSize.sm, color: colors.textMuted, fontWeight: '500' },
  list:     { gap: space.md, minWidth: 0 },
  listItem: { minWidth: 0 },
  footer:   { alignItems: 'center' },

  // Card
  card:        { borderLeftWidth: 4, overflow: 'hidden' },
  todayBanner: {
    flexDirection: 'row', alignItems: 'center', gap: 5,
    backgroundColor: colors.bgSubtle, paddingHorizontal: space.lg, paddingVertical: 6,
  },
  todayText:   { fontSize: 11, color: colors.text, fontWeight: '700' },

  body:        { flexDirection: 'row', flexWrap: 'wrap', gap: space.lg, padding: space.lg },
  left:        { flexDirection: 'row', gap: space.md, flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0 },
  avatar:      { width: 44, height: 44, borderRadius: 22, alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  avatarText:  { color: '#FFFFFF', fontSize: 15, fontWeight: '700' },
  patientCol:  { gap: 3, flexShrink: 1, minWidth: 0 },
  patientName: { fontSize: 15, fontWeight: '700', color: colors.text },
  apptId:      { fontSize: 11, color: colors.textSubtle, fontWeight: '500' },
  metaRow:     { flexDirection: 'row', alignItems: 'center', gap: 4, marginTop: 2 },
  metaText:    { fontSize: fontSize.xs, color: colors.textMuted },

  center:      { flexGrow: 2, flexShrink: 1, flexBasis: 200, minWidth: 0, gap: space.sm, justifyContent: 'center' },
  detailRow:   { flexDirection: 'row', alignItems: 'flex-start', gap: 6 },
  detailIcon:  { marginTop: 2 },
  detailText:  { flex: 1, fontSize: fontSize.sm, color: colors.text, lineHeight: 18 },
  detailMuted: { flex: 1, fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 18 },

  right:       { alignItems: 'flex-start', gap: space.sm, flexGrow: 1, flexShrink: 1, flexBasis: 150, minWidth: 0, justifyContent: 'center' },
  badges:      { gap: 5, alignItems: 'flex-start' },
  joinBtn: {
    flexDirection: 'row', alignItems: 'center', gap: 4,
    borderWidth: 1, borderRadius: radius.md,
    paddingHorizontal: 10, paddingVertical: 5,
  },
  joinBtnText:    { fontSize: fontSize.xs, fontWeight: '600' },
  detailsBtn:     { flexDirection: 'row', alignItems: 'center', gap: 3, minHeight: 28 },
  detailsBtnText: { fontSize: fontSize.sm, fontWeight: '600' },

  detailsPanel: { borderTopWidth: 1, borderTopColor: colors.border, paddingHorizontal: space.lg, paddingVertical: space.md, backgroundColor: colors.bg },
  feedback:     { paddingHorizontal: space.lg, paddingBottom: space.md },
  actionRow: {
    flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, justifyContent: 'flex-end',
    paddingHorizontal: space.lg, paddingBottom: space.md,
  },
});
