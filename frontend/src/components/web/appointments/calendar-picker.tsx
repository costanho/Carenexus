import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { ariaPressed, colors, decorative, fontSize, Icon, radius, space, useUiTheme, webOnly } from '@/components/web/ui';
import { formatLongDate } from '@/lib/appointments/rules';
import { clinicToday } from '@/lib/time/clinic';

const MONTH_NAMES = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const DAY_LABELS: { short: string; long: string }[] = [
  { short: 'Su', long: 'Sunday' }, { short: 'Mo', long: 'Monday' }, { short: 'Tu', long: 'Tuesday' },
  { short: 'We', long: 'Wednesday' }, { short: 'Th', long: 'Thursday' }, { short: 'Fr', long: 'Friday' },
  { short: 'Sa', long: 'Saturday' },
];

const DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/;

function pad(n: number): string {
  return String(n).padStart(2, '0');
}

function yearMonth(date: string | null | undefined): { year: number; month: number } | null {
  const m = DATE_ONLY.exec(date ?? '');
  return m ? { year: Number(m[1]), month: Number(m[2]) - 1 } : null;
}

export interface CalendarPickerProps {
  /** The selected clinic day, "yyyy-MM-dd", or '' when none is chosen. */
  value:     string;
  onChange:  (date: string) => void;
  /** First day that can be chosen. Default: today at the clinic, so past days are never offered. */
  minDate?:  string;
  /** Last day that can be chosen (the booking horizon). No limit when omitted. */
  maxDate?:  string;
  /** Why a day inside the range cannot be chosen (a closed weekday), or null when it can. */
  disabledReason?: (date: string) => string | null;
  disabled?: boolean;
}

/**
 * A month calendar for choosing one clinic day. Days are clinic calendar days: "today" and "past" come from the
 * clinic's date, never from the device clock. Days before `minDate`, after `maxDate` and days `disabledReason`
 * rejects are shown but cannot be chosen; the month arrows stop at the first and last month with a choosable day.
 */
export function CalendarPicker({ value, onChange, minDate, maxDate, disabledReason, disabled = false }: CalendarPickerProps) {
  const { accent, accentBg, accentText } = useUiTheme();
  const today = clinicToday();
  const firstDay = minDate && DATE_ONLY.test(minDate) ? minDate : today;
  const lastDay = maxDate && DATE_ONLY.test(maxDate) ? maxDate : null;

  const start = yearMonth(value) ?? yearMonth(firstDay) ?? yearMonth(today)!;
  const [view, setView] = useState(start);

  // A value set from outside (a prefilled date, a reset form) brings its month into view.
  useEffect(() => {
    const target = yearMonth(value);
    if (target) setView(prev => (prev.year === target.year && prev.month === target.month ? prev : target));
  }, [value]);

  const monthKey = (y: number, m: number) => `${y}-${pad(m + 1)}`;
  const viewKey = monthKey(view.year, view.month);
  const prevDisabled = disabled || viewKey <= firstDay.slice(0, 7);
  const nextDisabled = disabled || (lastDay !== null && viewKey >= lastDay.slice(0, 7));

  function shift(delta: number) {
    setView(prev => {
      const index = prev.year * 12 + prev.month + delta;
      return { year: Math.floor(index / 12), month: index % 12 };
    });
  }

  const leading = new Date(Date.UTC(view.year, view.month, 1)).getUTCDay();
  const daysInMonth = new Date(Date.UTC(view.year, view.month + 1, 0)).getUTCDate();
  const cells: (number | null)[] = [
    ...Array<null>(leading).fill(null),
    ...Array.from({ length: daysInMonth }, (_, i) => i + 1),
  ];

  return (
    <View style={styles.wrap}>
      <View style={styles.header}>
        <Pressable
          style={[styles.navButton, { backgroundColor: prevDisabled ? colors.bgSubtle : accentBg }]}
          onPress={() => shift(-1)}
          disabled={prevDisabled}
          accessibilityRole="button"
          accessibilityLabel="Previous month"
          accessibilityState={{ disabled: prevDisabled }}
        >
          <Icon name="chevron-back" size={16} color={prevDisabled ? colors.disabledText : accent} />
        </Pressable>
        <Text style={styles.monthLabel} aria-live="polite">{MONTH_NAMES[view.month]} {view.year}</Text>
        <Pressable
          style={[styles.navButton, { backgroundColor: nextDisabled ? colors.bgSubtle : accentBg }]}
          onPress={() => shift(1)}
          disabled={nextDisabled}
          accessibilityRole="button"
          accessibilityLabel="Next month"
          accessibilityState={{ disabled: nextDisabled }}
        >
          <Icon name="chevron-forward" size={16} color={nextDisabled ? colors.disabledText : accent} />
        </Pressable>
      </View>

      <View style={styles.weekdays} {...decorative}>
        {DAY_LABELS.map(d => <Text key={d.long} style={styles.weekday}>{d.short}</Text>)}
      </View>

      <View style={styles.grid}>
        {cells.map((day, index) => {
          if (day === null) return <View key={`blank-${index}`} style={styles.cell} />;
          const date = `${view.year}-${pad(view.month + 1)}-${pad(day)}`;
          const reason = date < firstDay ? 'This day has passed'
            : lastDay !== null && date > lastDay ? 'Outside the booking period'
            : disabledReason?.(date) ?? null;
          const unavailable = disabled || reason !== null;
          const selected = date === value;
          const isToday = date === today;
          const name = formatLongDate(date);
          return (
            <Pressable
              key={date}
              style={styles.cell}
              disabled={unavailable}
              onPress={() => onChange(date)}
              accessibilityRole="button"
              accessibilityLabel={reason ? `${name}, not available: ${reason}` : name}
              accessibilityState={{ disabled: unavailable, selected }}
              {...ariaPressed(selected)}
              {...webOnly(isToday ? { 'aria-current': 'date' } : {})}
            >
              <View style={[
                styles.dayCircle,
                selected && { backgroundColor: accent },
                isToday && !selected && { borderWidth: 1.5, borderColor: accent },
              ]}>
                <Text style={[
                  styles.dayText,
                  isToday && !selected && !unavailable && { color: accent, fontWeight: '700' },
                  unavailable && styles.dayTextUnavailable,
                  selected && { color: accentText, fontWeight: '700' },
                ]}>{day}</Text>
              </View>
            </Pressable>
          );
        })}
      </View>

      {value && DATE_ONLY.test(value) ? (
        <View style={[styles.selected, { backgroundColor: accentBg }]}>
          <Icon name="calendar" size={14} color={accent} />
          <Text style={[styles.selectedText, { color: accent }]}>{formatLongDate(value)}</Text>
        </View>
      ) : (
        <Text style={styles.hint}>Choose a date</Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:        { minWidth: 0 },
  header:      { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: space.md },
  navButton:   { width: 36, height: 36, borderRadius: radius.pill, alignItems: 'center', justifyContent: 'center' },
  monthLabel:  { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  weekdays:    { flexDirection: 'row', marginBottom: space.xs },
  weekday:     { flex: 1, textAlign: 'center', fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted, paddingVertical: space.xs },
  grid:        { flexDirection: 'row', flexWrap: 'wrap' },
  cell:        { width: '14.2857%', alignItems: 'center', paddingVertical: 2 },
  dayCircle:   { width: 36, height: 36, borderRadius: radius.pill, alignItems: 'center', justifyContent: 'center' },
  dayText:     { fontSize: fontSize.sm, fontWeight: '500', color: colors.text },
  // Unavailable days stay readable but are struck through, so they do not differ by colour alone.
  dayTextUnavailable: { color: colors.placeholder, fontWeight: '400', textDecorationLine: 'line-through' },
  selected:     { flexDirection: 'row', alignItems: 'center', gap: space.sm, marginTop: space.md, padding: space.sm, borderRadius: radius.md },
  selectedText: { flex: 1, minWidth: 0, fontSize: fontSize.sm, fontWeight: '600' },
  hint:         { fontSize: fontSize.xs, color: colors.textMuted, textAlign: 'center', marginTop: space.md },
});
