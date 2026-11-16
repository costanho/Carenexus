import { Pressable, type StyleProp, StyleSheet, Text, type ViewStyle } from 'react-native';
import { accessibleName, Icon, radius, space, fontSize, useUiTheme } from '@/components/web/ui';

/**
 * A small text button that shows or hides more of a card ("Show details" / "Hide details", "View full record" /
 * "Show less"). It looks like `<Button variant="ghost" size="sm">` with a chevron, and also tells assistive
 * technology whether the extra content is open (`aria-expanded`), which the shared Button cannot do yet.
 *
 * The label is the visible text; the accessible name starts with it (WCAG 2.5.3) and `accessibilityLabel` adds
 * context after it. Pass the label for the current state, so the visible text and the name change together.
 */
export function DisclosureButton({ label, expanded, onPress, accessibilityLabel, style, testID }: {
  label:     string;
  expanded:  boolean;
  onPress:   () => void;
  accessibilityLabel?: string;
  style?:    StyleProp<ViewStyle>;
  testID?:   string;
}) {
  const theme = useUiTheme();
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={accessibleName(label, accessibilityLabel)}
      accessibilityState={{ expanded }}
      aria-expanded={expanded}
      testID={testID}
      style={({ pressed }) => [styles.base, pressed && styles.pressed, style]}
    >
      <Icon name={expanded ? 'chevron-up' : 'chevron-down'} size={14} color={theme.accent} />
      <Text style={[styles.label, { color: theme.accent }]} numberOfLines={2}>{label}</Text>
    </Pressable>
  );
}

// The same box as the shared Button at size "sm" in the ghost variant.
const styles = StyleSheet.create({
  base: {
    minHeight: 32, maxWidth: '100%', flexDirection: 'row', alignItems: 'center', justifyContent: 'center',
    gap: space.sm, paddingHorizontal: space.md, paddingVertical: space.xs,
    borderWidth: 1, borderColor: 'transparent', borderRadius: radius.md, alignSelf: 'flex-start',
    backgroundColor: 'transparent',
  },
  pressed: { opacity: 0.85 },
  label:   { fontSize: fontSize.sm, fontWeight: '600', textAlign: 'center', flexShrink: 1 },
});
