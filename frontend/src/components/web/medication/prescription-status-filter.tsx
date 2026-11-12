import { Pressable, StyleSheet, Text, View } from 'react-native';
import { ariaPressed, colors, CONTROL_HEIGHT, fontSize, radius, space, statusLabel, useUiTheme } from '@/components/web/ui';
import { PRESCRIPTION_STATUSES, type PrescriptionCounts, type PrescriptionStatus } from '@/hooks/use-prescriptions';

export type PrescriptionFilter = 'ALL' | PrescriptionStatus;

/** The filter a `status` URL value stands for; anything that is not a status means "all". */
export function parsePrescriptionFilter(value: string | null | undefined): PrescriptionFilter {
  const v = (value ?? '').trim().toUpperCase();
  return (PRESCRIPTION_STATUSES as readonly string[]).includes(v) ? (v as PrescriptionStatus) : 'ALL';
}

export function prescriptionFilterLabel(filter: PrescriptionFilter): string {
  return filter === 'ALL' ? 'All' : statusLabel(filter);
}

export interface PrescriptionStatusFilterProps {
  value:    PrescriptionFilter;
  onChange: (value: PrescriptionFilter) => void;
  /** Number of prescriptions per status; the numbers are left out while they are not known. */
  counts?:  PrescriptionCounts | null;
  /** Names the group for assistive technology. */
  label?:   string;
}

const FILTERS: readonly PrescriptionFilter[] = ['ALL', ...PRESCRIPTION_STATUSES];

/**
 * Chooses which prescriptions are listed. The list is filtered by the server on the effective status, so an
 * active prescription past its expiry date is found under Expired.
 */
export function PrescriptionStatusFilter({
  value, onChange, counts, label = 'Filter prescriptions by status',
}: PrescriptionStatusFilterProps) {
  const { accent, accentBg } = useUiTheme();
  const total = counts ? PRESCRIPTION_STATUSES.reduce((sum, s) => sum + counts[s], 0) : null;
  return (
    <View style={styles.row} role="group" aria-label={label}>
      {FILTERS.map(filter => {
        const on = filter === value;
        const count = filter === 'ALL' ? total : counts?.[filter] ?? null;
        const text = prescriptionFilterLabel(filter);
        return (
          <Pressable
            key={filter}
            accessibilityRole="button"
            accessibilityLabel={count === null ? text : `${text}, ${count}`}
            accessibilityState={{ selected: on }}
            {...ariaPressed(on)}
            onPress={() => { if (!on) onChange(filter); }}
            style={[styles.pill, on && { borderColor: accent, backgroundColor: accentBg }]}
          >
            <Text style={[styles.text, on && { color: accent, fontWeight: '700' }]}>{text}</Text>
            {count !== null ? (
              <View style={[styles.count, on && { backgroundColor: accent }]}>
                <Text style={[styles.countText, on && styles.countTextOn]}>{count}</Text>
              </View>
            ) : null}
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  row:  { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  pill: {
    minHeight: CONTROL_HEIGHT, flexDirection: 'row', alignItems: 'center', gap: space.sm,
    paddingHorizontal: space.md, paddingVertical: space.xs,
    borderWidth: 1, borderColor: colors.borderInput, borderRadius: radius.pill, backgroundColor: colors.surface,
  },
  text:        { fontSize: fontSize.sm, fontWeight: '500', color: colors.textMuted },
  count:       { minWidth: 22, paddingHorizontal: 6, paddingVertical: 1, borderRadius: radius.pill, backgroundColor: colors.bgSubtle, alignItems: 'center' },
  countText:   { fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted },
  countTextOn: { color: '#FFFFFF' },
});
