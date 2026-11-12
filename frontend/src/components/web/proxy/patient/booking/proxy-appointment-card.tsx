import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { CallJoinButton } from '@/components/web/call/call-join-button';
import { ReschedulePanel, type RescheduleTarget } from '@/components/web/appointments';
import {
  ariaLevel, Banner, Button, Card, colors, fontSize, FormError, Icon, type IoniconsName, radius, space, StatusBadge,
  type StatusTone, useUiTheme,
} from '@/components/web/ui';
import { apiErrorMessage } from '@/lib/api/errors';
import { getAppointment } from '@/lib/appointments/api';
import type { AppointmentDto } from '@/lib/appointments/types';
import { describeCallJoin } from '@/lib/calls/join';
import type { CaregiverAppointment } from '@/lib/caregiver/types';
import { formatClinicDate, formatClinicTime } from '@/lib/time/clinic';
import { type AppointmentPhase, DEFAULT_DURATION_MINUTES } from './scheduling';

const TYPE_LABEL: Record<string, { label: string; icon: IoniconsName }> = {
  VIDEO:     { label: 'Video call', icon: 'videocam-outline' },
  PHONE:     { label: 'Phone call', icon: 'call-outline' },
  IN_PERSON: { label: 'In person',  icon: 'business-outline' },
};

const PHASE_BADGE: Record<AppointmentPhase, { label: string; tone: StatusTone; icon?: IoniconsName }> = {
  'upcoming':         { label: 'Upcoming',         tone: 'info' },
  'in-progress':      { label: 'In progress',      tone: 'info',    icon: 'time-outline' },
  'awaiting-outcome': { label: 'Awaiting outcome', tone: 'warning', icon: 'hourglass-outline' },
  'completed':        { label: 'Completed',        tone: 'success', icon: 'checkmark-circle-outline' },
  'cancelled':        { label: 'Cancelled',        tone: 'neutral', icon: 'close-circle-outline' },
  'no-show':          { label: 'No show',          tone: 'danger',  icon: 'alert-circle-outline' },
  'other':            { label: 'Unknown status',   tone: 'neutral' },
};

export interface ProxyAppointmentCardProps {
  appt:          CaregiverAppointment;
  phase:         AppointmentPhase;
  /** The loved one's display name. */
  patientName:   string;
  /** The caregiver may book, reschedule and cancel for this loved one (effective permission, active grant). */
  canManage:     boolean;
  /** The patient approved joining video calls. */
  canJoinCalls:  boolean;
  /** Confirmation for this card (owned by the list, so it survives a refetch). */
  notice?:       string | null;
  onDismissNotice?: () => void;
  /** The caregiver pressed Cancel: the list opens the confirmation. */
  onCancel:      (appt: CaregiverAppointment) => void;
  /** Called when a reschedule starts, so older confirmations on the list are cleared. */
  onActionStart: () => void;
  /** The move went through; `changed` is false when the server kept the same time. */
  onRescheduled: (appt: CaregiverAppointment, updated: AppointmentDto, changed: boolean) => void;
  /** A move was refused because the appointment was completed, cancelled or started meanwhile. */
  onClosedMeanwhile: (appt: CaregiverAppointment, reason: string) => void;
}

function MetaItem({ icon, text }: { icon: IoniconsName; text: string }) {
  return (
    <View style={styles.meta}>
      <Icon name={icon} size={14} color={colors.textMuted} />
      <Text style={styles.metaText}>{text}</Text>
    </View>
  );
}

/**
 * One appointment of a loved one. Upcoming appointments get Reschedule and Cancel when the caregiver may
 * schedule for that person; an appointment that has started or is awaiting its outcome offers no change.
 */
export function ProxyAppointmentCard({
  appt, phase, patientName, canManage, canJoinCalls, notice, onDismissNotice, onCancel, onActionStart, onRescheduled,
  onClosedMeanwhile,
}: ProxyAppointmentCardProps) {
  const { accent } = useUiTheme();
  const [target, setTarget] = useState<RescheduleTarget | null>(null);
  const [opening, setOpening] = useState(false);
  const [openError, setOpenError] = useState<string | null>(null);

  const badge = PHASE_BADGE[phase];
  const type = TYPE_LABEL[appt.type ?? ''] ?? { label: 'Appointment', icon: 'calendar-outline' as IoniconsName };
  const date = formatClinicDate(appt.scheduledAt, 'Date not set');
  const time = formatClinicTime(appt.scheduledAt);
  const duration = appt.durationMinutes ?? DEFAULT_DURATION_MINUTES;
  const doctor = appt.doctorName?.trim() || null;
  const title = appt.reasonForVisit?.trim() || 'Appointment';
  const changeable = canManage && phase === 'upcoming';
  const join = canJoinCalls ? describeCallJoin(appt, false) : null;
  // Accessible names start with the visible label (WCAG 2.5.3) and say whose appointment it is.
  const subject = `for ${patientName}${doctor ? ` with ${doctor}` : ''} on ${date}`;

  async function openReschedule() {
    onActionStart();
    setOpenError(null);
    if (appt.doctorSystemId) {
      setTarget({
        appointmentId: appt.appointmentId, doctorSystemId: appt.doctorSystemId,
        scheduledAt: appt.scheduledAt, durationMinutes: duration,
      });
      return;
    }
    // The feed left the doctor out: read the appointment itself to know whose times to offer.
    setOpening(true);
    try {
      const full = await getAppointment(appt.appointmentId);
      setTarget({
        appointmentId: full.appointmentId, doctorSystemId: full.doctorSystemId,
        scheduledAt: full.scheduledAt, durationMinutes: full.durationMinutes,
      });
    } catch (e) {
      setOpenError(apiErrorMessage(e, 'Could not open the appointment. Please try again.'));
    } finally {
      setOpening(false);
    }
  }

  function handleDone(updated: AppointmentDto, changed: boolean) {
    setTarget(null);
    onRescheduled(appt, updated, changed);
  }

  function handleStale(_latest: AppointmentDto, reason: string | null) {
    if (reason === null) return;
    setTarget(null);
    onClosedMeanwhile(appt, reason);
  }

  return (
    <Card style={[styles.card, (phase === 'upcoming' || phase === 'in-progress') && { borderLeftWidth: 3, borderLeftColor: accent }]}>
      <View style={styles.head}>
        <View style={styles.headText}>
          <Text style={styles.title} accessibilityRole="header" {...ariaLevel(2)}>{title}</Text>
          <View style={styles.patientRow}>
            <Icon name="person-circle-outline" size={13} color={colors.textMuted} />
            <Text style={styles.sub}>{patientName}</Text>
          </View>
        </View>
        <StatusBadge label={badge.label} tone={badge.tone} icon={badge.icon} />
      </View>

      <View style={styles.metaRow}>
        <MetaItem icon="calendar-outline" text={date} />
        {time ? <MetaItem icon="time-outline" text={`${time} · ${duration} min`} /> : null}
        <MetaItem icon={type.icon} text={type.label} />
        {doctor ? <MetaItem icon="person-outline" text={doctor} /> : null}
      </View>

      {phase === 'awaiting-outcome' ? (
        <Text style={styles.note}>
          This appointment has ended. The doctor has not recorded its outcome yet, so it can no longer be rescheduled or cancelled.
        </Text>
      ) : null}

      {appt.notes?.trim() ? (
        <View style={styles.notes}>
          <Text style={styles.notesText}>{appt.notes}</Text>
        </View>
      ) : null}

      {join ? (
        <CallJoinButton
          info={join}
          withName={patientName}
          style={[styles.joinBtn, { backgroundColor: accent }]}
          textStyle={styles.joinBtnText}
          iconSize={15}
          iconColor={colors.surface}
        />
      ) : null}

      {notice ? <Banner tone="success" text={notice} onDismiss={onDismissNotice} /> : null}

      {changeable && target ? (
        <ReschedulePanel
          appointment={target}
          onDone={handleDone}
          onClose={() => setTarget(null)}
          onStale={handleStale}
        />
      ) : null}

      {changeable && !target ? (
        <View style={styles.actions}>
          <Button
            label="Reschedule"
            accessibilityLabel={`Reschedule appointment ${subject}`}
            icon="calendar-outline"
            variant="secondary"
            size="sm"
            onPress={openReschedule}
            loading={opening}
          />
          <Button
            label="Cancel appointment"
            accessibilityLabel={`Cancel appointment ${subject}`}
            icon="close-circle-outline"
            variant="danger"
            size="sm"
            onPress={() => { onActionStart(); onCancel(appt); }}
            disabled={opening}
          />
        </View>
      ) : null}
      <FormError message={openError} />
    </Card>
  );
}

const styles = StyleSheet.create({
  card:     { gap: space.md, minWidth: 0 },
  head:     { flexDirection: 'row', alignItems: 'flex-start', gap: space.md, flexWrap: 'wrap' },
  headText: { flex: 1, minWidth: 160 },
  title:    { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  patientRow: { flexDirection: 'row', alignItems: 'center', gap: 4, marginTop: 3 },
  sub:      { fontSize: fontSize.xs, color: colors.textMuted },
  metaRow:  { flexDirection: 'row', flexWrap: 'wrap', columnGap: space.md, rowGap: space.xs },
  meta:     { flexDirection: 'row', alignItems: 'center', gap: 5 },
  metaText: { fontSize: fontSize.sm, color: colors.textMuted },
  note:     { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  notes:    { backgroundColor: colors.bg, borderRadius: radius.md, padding: space.sm },
  notesText: { fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 18 },
  actions:  { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  joinBtn:  {
    flexDirection: 'row', alignItems: 'center', gap: 6, borderRadius: radius.md,
    paddingHorizontal: space.lg, paddingVertical: 10, alignSelf: 'flex-start',
  },
  joinBtnText: { fontSize: fontSize.sm, color: colors.surface, fontWeight: '600' },
});
