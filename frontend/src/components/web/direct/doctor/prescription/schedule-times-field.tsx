import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  Button, colors, fontSize, Icon, IconButton, radius, space, TimeField, useFieldIds, describedBy, useUiTheme, webOnly,
} from '@/components/web/ui';
import { isScheduleTime, normaliseScheduleTimes, PRESCRIPTION_LIMITS } from '@/lib/validation/prescription';
import { CLINIC_ZONE_LABEL } from '@/lib/time/clinic';

export interface ScheduleTimesFieldProps {
  /** The daily dose times "HH:mm", kept distinct and in ascending order. */
  value:     string[];
  onChange:  (times: string[]) => void;
  label?:    string;
  hint?:     string;
  /** A validation or server message for the whole list. */
  error?:    string | null;
  disabled?: boolean;
  onBlur?:   () => void;
}

/**
 * The daily dose times of a prescription as removable chips. Pick a time and press "Add time"; a time left in
 * the box when focus moves on is added too, so nothing chosen is lost on Save. At most 8 times, each "HH:mm" in
 * 24-hour clinic time.
 */
export function ScheduleTimesField({
  value, onChange, label = 'Dose times', hint, error, disabled = false, onBlur,
}: ScheduleTimesFieldProps) {
  const { accentBg } = useUiTheme();
  const ids = useFieldIds();
  const [draft, setDraft] = useState('');
  const [draftError, setDraftError] = useState<string | null>(null);

  const max = PRESCRIPTION_LIMITS.scheduleTimes;
  const full = value.length >= max;
  const message = error?.trim() ? error : null;

  /** Adds the time in the box. `quiet` (focus moving on) ignores an empty or unfinished time instead of flagging it. */
  const add = (quiet: boolean) => {
    const time = draft.trim().slice(0, 5);
    if (!time) {
      if (!quiet) setDraftError('Choose a time first');
      return;
    }
    if (!isScheduleTime(time)) {
      if (!quiet) setDraftError('Enter the time as HH:mm, for example 08:00');
      return;
    }
    if (value.includes(time)) {
      setDraft('');
      setDraftError(quiet ? null : `${time} is already in the list`);
      return;
    }
    if (full) {
      setDraftError(`A prescription can have at most ${max} dose times`);
      return;
    }
    onChange(normaliseScheduleTimes([...value, time]));
    setDraft('');
    setDraftError(null);
  };

  return (
    <View
      style={styles.wrap}
      role="group"
      aria-labelledby={ids.labelId}
      {...webOnly({ 'aria-describedby': describedBy(hint && ids.hintId, message && ids.errorId) })}
    >
      <Text id={ids.labelId} style={styles.label}>{label}</Text>
      {hint ? <Text id={ids.hintId} style={styles.hint}>{hint}</Text> : null}

      <View style={styles.addRow}>
        <View style={styles.timeBox}>
          <TimeField
            label={`Time to add (24-hour, ${CLINIC_ZONE_LABEL})`}
            value={draft}
            onChange={next => { setDraft(next); setDraftError(null); }}
            onBlur={() => { add(true); onBlur?.(); }}
            error={draftError}
            disabled={disabled || full}
            hint={full ? `Limit of ${max} times reached. Remove one to add another.` : undefined}
          />
        </View>
        <Button
          label="Add time"
          icon="add"
          variant="secondary"
          onPress={() => add(false)}
          disabled={disabled || full || !draft.trim()}
          style={styles.addButton}
        />
      </View>

      {value.length > 0 ? (
        <View style={styles.chips} role="list" aria-label={`${label}: ${value.length} of ${max}`}>
          {value.map(time => (
            <View key={time} role="listitem" style={[styles.chip, { backgroundColor: accentBg }]}>
              <Text style={styles.chipText}>{time}</Text>
              {!disabled && (
                <IconButton
                  label={`Remove ${time}`}
                  icon="close"
                  size="sm"
                  onPress={() => { onChange(value.filter(t => t !== time)); setDraftError(null); }}
                  style={styles.chipRemove}
                />
              )}
            </View>
          ))}
        </View>
      ) : (
        <Text style={styles.none}>No times added.</Text>
      )}

      {message ? (
        <View style={styles.errorRow} accessibilityRole="alert">
          <Icon name="alert-circle-outline" size={14} color={colors.danger} style={styles.errorIcon} />
          <Text id={ids.errorId} style={styles.error}>{message}</Text>
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:  { gap: space.sm, minWidth: 0, maxWidth: '100%' },
  label: { fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  hint:  { fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 17 },

  // The button lines up with the time box (under its label); both wrap onto two lines on a narrow panel.
  addRow:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.sm },
  timeBox: { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0 },
  // Height of the time box's label plus the gap under it.
  addButton: { marginTop: 21 },

  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs },
  chip: {
    flexDirection: 'row', alignItems: 'center', gap: 2, maxWidth: '100%',
    paddingLeft: space.md, paddingRight: 2, paddingVertical: 2, borderRadius: radius.pill,
  },
  chipText:   { fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  chipRemove: { borderRadius: radius.pill },
  none:       { fontSize: fontSize.sm, color: colors.textSubtle },

  errorRow:  { flexDirection: 'row', alignItems: 'flex-start', gap: space.xs },
  errorIcon: { flexShrink: 0, marginTop: 1 },
  error:    { flex: 1, minWidth: 0, fontSize: fontSize.xs, fontWeight: '500', color: colors.danger, lineHeight: 17 },
});
