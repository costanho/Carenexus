import { useEffect, useId, useRef } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import {
  ariaPressed, Banner, colors, CONTROL_HEIGHT, fontSize, Icon, LoadingView, radius, space, useUiTheme, webOnly,
} from '@/components/web/ui';
import { useDoctorSlots } from '@/hooks/use-doctor-slots';
import { slotTime } from '@/lib/appointments/rules';
import { CLINIC_ZONE_LABEL, isAfterClinicNow } from '@/lib/time/clinic';

export interface SlotPickerProps {
  /** The doctor whose times are offered (DOC-...). Nothing is loaded until it is set. */
  doctorSystemId:  string | null | undefined;
  /** Clinic calendar day, "yyyy-MM-dd". Nothing is loaded until it is set. */
  date:            string | null | undefined;
  /** Appointment length the times are computed for. */
  durationMinutes: number;
  /** The chosen start (`slot.start`, clinic wall clock), or '' when none is chosen. */
  value:           string;
  /** Called with `slot.start`, to be sent unchanged as `scheduledAt`; '' when the choice is no longer bookable. */
  onChange:        (start: string) => void;
  /** The appointment being rescheduled: it does not block its own time. */
  excludeAppointmentId?: number | null;
  /** Start of the appointment being rescheduled; that time is marked "Current time" and cannot be chosen again. */
  currentStart?:   string | null;
  /** Change this number to load the times again, after the server refused one (409 or DOCTOR_UNAVAILABLE). */
  refreshToken?:   number;
  /** A message about the chosen time (validation, or the server's refusal), shown under the times. */
  error?:          string | null;
  /** Visible label of the group. Default "Available times". */
  label?:          string;
  disabled?:       boolean;
}

/**
 * Selectable start times of one doctor on one clinic day, from GET /api/appointments/doctor/{id}/slots.
 * Every time of the day is shown; times that are taken, past, beyond the booking horizon or inside the doctor's
 * time off are disabled. Times are clinic wall-clock values and are never converted to the device's time zone.
 */
export function SlotPicker({
  doctorSystemId, date, durationMinutes, value, onChange, excludeAppointmentId, currentStart, refreshToken,
  error, label = 'Available times', disabled = false,
}: SlotPickerProps) {
  const { accent, accentBg, accentText } = useUiTheme();
  const labelId = useId();
  const errorId = useId();
  const state = useDoctorSlots({ doctorSystemId, date, durationMinutes, excludeAppointmentId });
  const { slots, refetch } = state;
  const current = (currentStart ?? '').slice(0, 16);

  // The parent asks for a reload by changing the token; the first value is not a request.
  const seenToken = useRef(refreshToken);
  useEffect(() => {
    if (seenToken.current === refreshToken) return;
    seenToken.current = refreshToken;
    refetch();
  }, [refreshToken, refetch]);

  // A choice that the loaded day no longer offers (taken meanwhile, another doctor or date) is dropped.
  const settled = state.enabled && !state.loading && !state.error && state.data !== null;
  const stillOffered = !value || slots.some(s => s.start === value && s.available && s.start.slice(0, 16) !== current);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;
  useEffect(() => {
    if (value && settled && !stillOffered) onChangeRef.current('');
  }, [value, settled, stillOffered]);

  const message = error?.trim() ? error : null;
  const header = (
    <View style={styles.header}>
      <Text id={labelId} style={styles.label}>{label}</Text>
      <Text style={styles.zone}>Clinic time ({CLINIC_ZONE_LABEL})</Text>
    </View>
  );
  const errorRow = message ? (
    <View style={styles.errorRow} accessibilityRole="alert">
      <Icon name="alert-circle-outline" size={14} color={colors.danger} />
      <Text id={errorId} style={styles.errorText}>{message}</Text>
    </View>
  ) : null;

  if (!state.enabled) {
    return (
      <View style={styles.wrap}>
        {header}
        <Text style={styles.note}>
          {!doctorSystemId ? 'Choose a doctor and a date to see the available times.' : 'Choose a date to see the available times.'}
        </Text>
        {errorRow}
      </View>
    );
  }

  if (state.loading) {
    return (
      <View style={styles.wrap}>
        {header}
        <LoadingView compact label="Loading available times…" />
      </View>
    );
  }

  if (state.error && state.data === null) {
    return (
      <View style={styles.wrap}>
        {header}
        <Banner tone="danger" text={state.error} action={{ label: 'Retry', onPress: refetch }} />
      </View>
    );
  }

  const offered = slots.map(slot => {
    const isCurrent = !!current && slot.start.slice(0, 16) === current;
    // The server flags past times; the clock is checked again because the list may have been loaded a while ago.
    const free = slot.available && !isCurrent && isAfterClinicNow(slot.start);
    return { slot, isCurrent, free };
  });
  const anyFree = offered.some(o => o.free);

  return (
    <View style={styles.wrap}>
      {header}
      {state.error ? <Banner tone="danger" text={state.error} action={{ label: 'Retry', onPress: refetch }} /> : null}
      {slots.length === 0 ? (
        <Text style={styles.note}>No bookable times on this day. Please choose another date.</Text>
      ) : (
        <>
          {!anyFree ? <Text style={styles.note}>No free times left on this day. Please choose another date.</Text> : null}
          <View
            style={styles.grid}
            role="group"
            {...webOnly({ 'aria-labelledby': labelId, 'aria-describedby': message ? errorId : undefined, 'aria-busy': state.refreshing || undefined })}
          >
            {offered.map(({ slot, isCurrent, free }) => {
              const time = slotTime(slot.start);
              const selected = slot.start === value;
              const unavailable = disabled || !free;
              return (
                <Pressable
                  key={slot.start}
                  onPress={() => onChange(slot.start)}
                  disabled={unavailable}
                  accessibilityRole="button"
                  accessibilityLabel={isCurrent ? `${time}, current time` : free ? time : `${time}, not available`}
                  accessibilityState={{ disabled: unavailable, selected }}
                  {...ariaPressed(selected)}
                  style={[
                    styles.slot,
                    !free && styles.slotUnavailable,
                    selected && { backgroundColor: accent, borderColor: accent },
                    !selected && free && { borderColor: accent, backgroundColor: accentBg },
                  ]}
                >
                  <Text style={[
                    styles.slotText,
                    free && !selected && { color: accent },
                    !free && styles.slotTextUnavailable,
                    selected && { color: accentText },
                  ]}>{time}</Text>
                  {isCurrent ? <Text style={styles.slotNote}>Current</Text> : null}
                </Pressable>
              );
            })}
          </View>
        </>
      )}
      {errorRow}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:    { gap: space.sm, minWidth: 0 },
  header:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'baseline', justifyContent: 'space-between', columnGap: space.md, rowGap: 2 },
  label:   { fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  zone:    { fontSize: fontSize.xs, color: colors.textMuted },
  note:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  grid:    { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  slot: {
    minWidth: 68, minHeight: CONTROL_HEIGHT, paddingHorizontal: space.md, alignItems: 'center', justifyContent: 'center',
    borderWidth: 1, borderRadius: radius.md, borderColor: colors.borderInput, backgroundColor: colors.surface,
  },
  slotUnavailable:     { borderColor: colors.border, backgroundColor: colors.bgSubtle },
  slotText:            { fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  // Struck through as well as greyed, so an unavailable time does not differ by colour alone.
  slotTextUnavailable: { color: colors.disabledText, fontWeight: '400', textDecorationLine: 'line-through' },
  slotNote:            { fontSize: 10, color: colors.textMuted },
  errorRow:  { flexDirection: 'row', alignItems: 'flex-start', gap: space.xs },
  errorText: { flex: 1, minWidth: 0, fontSize: fontSize.xs, fontWeight: '500', color: colors.danger, lineHeight: 17 },
});
