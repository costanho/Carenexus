import { StyleSheet, Text, View } from 'react-native';
import { Button, colors, DateField, fontSize, IconButton, space } from '@/components/web/ui';
import { addDays, latestScheduleDate, normaliseScheduleDate, scheduleDayLabel } from '@/lib/medication/dates';
import { clinicToday } from '@/lib/time/clinic';
import { isValidDateOnly } from '@/lib/ui/date-only';

export interface DateSwitcherProps {
  /** The day shown, "yyyy-MM-dd" (a clinic calendar day). */
  date:     string;
  onChange: (date: string) => void;
  /** Whose schedule the day belongs to, added to the accessible names ("Previous day, Thomas Dube"). */
  context?: string;
}

/**
 * Moves the medication schedule between days: previous and next day, back to today, or any earlier date.
 * The latest day is the clinic's tomorrow, the last day the server prepares doses for.
 */
export function DateSwitcher({ date, onChange, context }: DateSwitcherProps) {
  const today  = clinicToday();
  const latest = latestScheduleDate(today);
  const atEnd  = date >= latest;

  return (
    <View style={styles.wrap}>
      <View style={styles.stepper} role="group" aria-label={context ? `Schedule day, ${context}` : 'Schedule day'}>
        <IconButton
          label="Previous day"
          accessibilityLabel={context ? `Previous day, ${context}` : undefined}
          icon="chevron-back"
          variant="secondary"
          onPress={() => onChange(addDays(date, -1))}
        />
        <Text style={styles.day} role="status" aria-live="polite">{scheduleDayLabel(date, today)}</Text>
        <IconButton
          label="Next day"
          accessibilityLabel={context ? `Next day, ${context}` : undefined}
          icon="chevron-forward"
          variant="secondary"
          disabled={atEnd}
          onPress={() => { if (!atEnd) onChange(addDays(date, 1)); }}
        />
      </View>
      <View style={styles.jump}>
        {date !== today ? (
          <Button label="Today" variant="ghost" size="sm" icon="today-outline" onPress={() => onChange(today)} />
        ) : null}
        <View style={styles.picker}>
          <DateField
            label="Go to date"
            value={date}
            max={latest}
            // The browser's date input reports '' while a date is half typed; only a whole date moves the day.
            onChange={value => { if (isValidDateOnly(value)) onChange(normaliseScheduleDate(value, today)); }}
          />
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-end', justifyContent: 'space-between', gap: space.md },
  stepper: { flexDirection: 'row', alignItems: 'center', gap: space.sm, flexShrink: 1, minWidth: 0 },
  day:     { flexShrink: 1, minWidth: 0, fontSize: fontSize.md, fontWeight: '600', color: colors.text, textAlign: 'center' },
  jump:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-end', gap: space.sm, maxWidth: '100%' },
  picker:  { width: 180, maxWidth: '100%' },
});
