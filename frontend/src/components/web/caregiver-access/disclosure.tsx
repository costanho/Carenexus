import type { ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { accessibleName, colors, CONTROL_HEIGHT, fontSize, Icon, type IoniconsName, space, useUiTheme } from '@/components/web/ui';

/**
 * A row that opens and closes the content under it (Activity, Consent history). The row is a button that
 * reports its state with aria-expanded; the content is mounted only while open, so a closed panel loads nothing.
 */
export function Disclosure({ label, context, icon, open, onToggle, divider = true, children }: {
  /** The visible text of the row. */
  label:    string;
  /** Whose panel it is, appended to the accessible name ("Activity, Sarah Davis"). */
  context?: string;
  icon:     IoniconsName;
  open:     boolean;
  onToggle: () => void;
  /** A line above the row, separating it from the content before it. Default true. */
  divider?: boolean;
  children: ReactNode;
}) {
  const { accent } = useUiTheme();
  return (
    <View style={[styles.wrap, divider && styles.divider]}>
      <Pressable
        onPress={onToggle}
        accessibilityRole="button"
        accessibilityLabel={accessibleName(label, context)}
        accessibilityState={{ expanded: open }}
        aria-expanded={open}
        style={({ pressed }) => [styles.row, pressed && styles.pressed]}
      >
        <Icon name={icon} size={16} color={accent} />
        <Text style={[styles.label, { color: accent }]}>{label}</Text>
        <Icon name={open ? 'chevron-up' : 'chevron-down'} size={16} color={accent} />
      </Pressable>
      {open ? <View style={styles.body}>{children}</View> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:    { minWidth: 0 },
  divider: { borderTopWidth: 1, borderTopColor: colors.border },
  row:     { minHeight: CONTROL_HEIGHT, flexDirection: 'row', alignItems: 'center', gap: space.sm, paddingVertical: space.sm },
  pressed: { opacity: 0.85 },
  label:   { flex: 1, minWidth: 0, fontSize: fontSize.md, fontWeight: '600' },
  body:    { paddingBottom: space.sm, minWidth: 0 },
});
