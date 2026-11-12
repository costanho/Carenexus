import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, colors, fontSize, FormActions, FormError, FormField, radius, type RoleTheme, space, UiThemeProvider,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { useBookingRules } from '@/hooks/use-doctor-slots';
import { getAppointment, moveAppointment } from '@/lib/appointments/api';
import { bookingFailureKind, bookingWindow, dayClosedReason, isActiveStatus } from '@/lib/appointments/rules';
import type { AppointmentDto } from '@/lib/appointments/types';
import { clinicToday, formatClinicDateTime, isAfterClinicNow, toClinicLocal } from '@/lib/time/clinic';
import { rescheduleFormSchema } from '@/lib/validation/appointment';
import { CalendarPicker } from './calendar-picker';
import { SlotPicker } from './slot-picker';

/** What the panel needs to know about the appointment being moved. */
export interface RescheduleTarget {
  appointmentId:   number;
  doctorSystemId:  string;
  /** Current start, clinic wall clock. */
  scheduledAt:     string;
  durationMinutes: number;
}

export interface ReschedulePanelProps {
  appointment: RescheduleTarget;
  /**
   * Called with the appointment the server returned. `changed` is false when its start time is the one it
   * already had (the server then changes nothing), so the caller does not announce a move that did not happen.
   */
  onDone:  (updated: AppointmentDto, changed: boolean) => void;
  onClose: () => void;
  /**
   * Called after a refused move with the appointment as the server now has it, so the caller can apply it to its
   * list (for example with applyAppointmentUpdate). `reason` is the server's message when the appointment can no
   * longer be moved (it was completed, cancelled or has started meanwhile): the caller then closes the panel and
   * shows the reason. It is null while the appointment is still open to a new time; the panel stays open then.
   */
  onStale?: (latest: AppointmentDto, reason: string | null) => void;
  /** Accent colours of the surrounding dashboard. Defaults to the theme already provided by the screen. */
  theme?:  RoleTheme;
}

function minuteOf(value: string | null | undefined): string {
  return (toClinicLocal(value) ?? '').slice(0, 16);
}

/** Whether the appointment can still be given a new time: open and not started yet in clinic time. */
function canStillMove(a: AppointmentDto): boolean {
  return isActiveStatus(a.status) && isAfterClinicNow(a.scheduledAt);
}

function ReschedulePanelBody({ appointment, onDone, onClose, onStale }: Omit<ReschedulePanelProps, 'theme'>) {
  const { rules } = useBookingRules();
  const today = clinicToday();
  const bookable = bookingWindow(rules, today);

  const [date, setDate] = useState('');
  const [slotStart, setSlotStart] = useState('');
  const [dateError, setDateError] = useState<string | null>(null);
  const [slotError, setSlotError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [slotRefresh, setSlotRefresh] = useState(0);

  const move = useAction(
    (scheduledAt: string) => moveAppointment(appointment.appointmentId, scheduledAt),
    { errorMessage: 'Failed to reschedule appointment. Please try again.' },
  );

  function chooseDate(next: string) {
    if (next === date) return;
    setDate(next);
    setSlotStart('');
    setDateError(null);
    setSlotError(null);
    setFormError(null);
  }

  function chooseSlot(start: string) {
    setSlotStart(start);
    setSlotError(null);
    setFormError(null);
  }

  async function save() {
    setFormError(null);
    const parsed = rescheduleFormSchema.safeParse({ date, slotStart });
    if (!parsed.success) {
      const issues = parsed.error.issues;
      setDateError(issues.find(i => i.path[0] === 'date')?.message ?? null);
      setSlotError(issues.find(i => i.path[0] === 'slotStart')?.message ?? null);
      return;
    }
    setDateError(null);
    setSlotError(null);

    const result = await move.run(parsed.data.slotStart);
    if (result.ok) {
      onDone(result.data, minuteOf(result.data.scheduledAt) !== minuteOf(appointment.scheduledAt));
      return;
    }
    const failure = result.error;
    if (!failure) return;
    if (failure.status === 409 && !failure.code) {
      // A code-less 409 is a slot conflict or a refusal because the appointment was closed meanwhile (completed,
      // cancelled, marked no-show). Reading it again tells which.
      let latest: AppointmentDto | null = null;
      try {
        latest = await getAppointment(appointment.appointmentId);
      } catch {
        // Not readable now: treat the refusal as a slot conflict, which keeps the panel usable.
      }
      if (latest && !canStillMove(latest)) {
        if (onStale) onStale(latest, failure.message);
        else setFormError(failure.message);
        return;
      }
      if (latest) onStale?.(latest, null);
    }
    if (bookingFailureKind(failure) === 'SLOT_UNAVAILABLE') {
      // Taken in the meantime, or outside the doctor's hours: show why and offer the day's current times.
      setSlotStart('');
      setSlotError(failure.message);
      setSlotRefresh(n => n + 1);
    } else {
      setFormError(failure.message);
    }
  }

  return (
    <View style={styles.panel}>
      <View style={styles.heading}>
        <Text style={styles.title} accessibilityRole="header" {...ariaLevel(3)}>Choose a new date and time</Text>
        <Text style={styles.sub}>
          Currently {formatClinicDateTime(appointment.scheduledAt, 'not set')}, {appointment.durationMinutes} minutes.
          The length of the appointment stays the same.
        </Text>
      </View>

      <View style={styles.pickers}>
        <View style={styles.column}>
          <FormField label="New date" required error={dateError}>
            <View style={[styles.card, !!dateError && styles.cardInvalid]}>
              <CalendarPicker
                value={date}
                onChange={chooseDate}
                minDate={bookable.firstDay}
                maxDate={bookable.lastDay}
                disabledReason={d => dayClosedReason(d, rules, today)}
                disabled={move.pending}
              />
            </View>
          </FormField>
        </View>
        <View style={styles.column}>
          <View style={[styles.card, !!slotError && styles.cardInvalid]}>
            <SlotPicker
              label="New time"
              doctorSystemId={appointment.doctorSystemId}
              date={date}
              durationMinutes={appointment.durationMinutes}
              value={slotStart}
              onChange={chooseSlot}
              excludeAppointmentId={appointment.appointmentId}
              currentStart={appointment.scheduledAt}
              refreshToken={slotRefresh}
              error={slotError}
              disabled={move.pending}
            />
          </View>
        </View>
      </View>

      <FormError message={formError} />
      <FormActions
        submitLabel="Confirm new time"
        onSubmit={save}
        onCancel={onClose}
        cancelLabel="Keep current time"
        submitting={move.pending}
      />
    </View>
  );
}

/**
 * Inline panel that moves one appointment to another of the doctor's free times. Used by the patient's
 * My Appointments and by the caregiver screen; the appointment being moved does not block its own time.
 * The caller applies the returned appointment to its list.
 */
export function ReschedulePanel({ theme, ...props }: ReschedulePanelProps) {
  return (
    <UiThemeProvider theme={theme}>
      <ReschedulePanelBody {...props} />
    </UiThemeProvider>
  );
}

const styles = StyleSheet.create({
  panel:   { gap: space.md, padding: space.md, borderWidth: 1, borderColor: colors.border, borderRadius: radius.lg, backgroundColor: colors.bg, minWidth: 0 },
  heading: { gap: 2 },
  title:   { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  sub:     { fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 17 },
  pickers: { flexDirection: 'row', flexWrap: 'wrap', gap: space.md, alignItems: 'flex-start' },
  column:  { flexGrow: 1, flexShrink: 1, flexBasis: 260, minWidth: 0, maxWidth: '100%' },
  card:    { borderWidth: 1, borderColor: colors.border, borderRadius: radius.lg, padding: space.md, backgroundColor: colors.surface },
  cardInvalid: { borderColor: colors.danger },
});
