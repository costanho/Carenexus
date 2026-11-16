import { Pressable, StyleSheet, Text, View } from 'react-native';
import {
  accessibleName, ariaPressed, colors, decorative, fontSize, Icon, type IoniconsName, radius, space, useUiTheme,
} from '@/components/web/ui';

export interface FilterChipOption<K extends string> {
  key:    K;
  label:  string;
  /** How many rows the filter matches, shown after the label; null or absent shows no count. */
  count?: number | null;
}

/**
 * One choice out of a few, as a row of toggle buttons that wraps onto more lines on a narrow window instead of
 * scrolling sideways. The pressed state is exposed to assistive technology (aria-pressed), not only by colour:
 * the selected chip also carries a check mark.
 */
export function FilterChips<K extends string>({ label, options, value, onChange }: {
  /** Names the group, for example "Filter by test type". */
  label:    string;
  options:  readonly FilterChipOption<K>[];
  value:    K;
  onChange: (key: K) => void;
}) {
  return (
    <View style={styles.row} role="group" aria-label={label}>
      {options.map(option => (
        <ToggleChip
          key={option.key}
          label={option.label}
          count={option.count}
          pressed={option.key === value}
          onChange={() => { if (option.key !== value) onChange(option.key); }}
        />
      ))}
    </View>
  );
}

/** A single on/off filter ("Critical only"), or one member of `FilterChips`. */
export function ToggleChip({ label, pressed, onChange, icon, count }: {
  label:    string;
  pressed:  boolean;
  onChange: (pressed: boolean) => void;
  /** Decorative icon shown while the chip is off. */
  icon?:    IoniconsName;
  /** How many rows the filter matches, shown after the label and read as part of its name. */
  count?:   number | null;
}) {
  const { accent, accentBg } = useUiTheme();
  const glyph: IoniconsName | undefined = pressed ? 'checkmark' : icon;
  const hasCount = typeof count === 'number';
  return (
    <Pressable
      onPress={() => onChange(!pressed)}
      accessibilityRole="button"
      accessibilityLabel={hasCount ? accessibleName(label, String(count)) : label}
      accessibilityState={{ selected: pressed }}
      {...ariaPressed(pressed)}
      style={({ pressed: down }) => [
        styles.chip,
        pressed && { borderColor: accent, backgroundColor: accentBg },
        down && styles.down,
      ]}
    >
      {glyph ? <Icon name={glyph} size={14} color={pressed ? accent : colors.textMuted} /> : null}
      <Text style={[styles.text, pressed && { color: accent, fontWeight: '700' }]}>{label}</Text>
      {hasCount ? (
        <View style={[styles.count, pressed && { backgroundColor: accent }]} {...decorative}>
          <Text style={[styles.countText, pressed && { color: accentBg }]}>{count}</Text>
        </View>
      ) : null}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row:  { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, maxWidth: '100%' },
  chip: {
    minHeight: 36, flexDirection: 'row', alignItems: 'center', gap: space.xs,
    paddingHorizontal: space.md, paddingVertical: space.xs,
    borderWidth: 1, borderColor: colors.borderInput, borderRadius: radius.pill, backgroundColor: colors.surface,
  },
  text: { fontSize: fontSize.sm, fontWeight: '500', color: colors.textMuted },
  down: { opacity: 0.8 },

  count:     { minWidth: 22, paddingHorizontal: 6, paddingVertical: 1, borderRadius: radius.pill, backgroundColor: colors.bgSubtle, alignItems: 'center' },
  countText: { fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted },
});
