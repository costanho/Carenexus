import { ActivityIndicator, Pressable, type StyleProp, StyleSheet, Text, View, type ViewStyle } from 'react-native';
import { accessibleName, decorative } from './a11y';
import { Icon, type IoniconsName } from './icon';
import { useUiTheme } from './theme';
import { colors, CONTROL_HEIGHT, fontSize, radius, type RoleTheme, space } from './tokens';

export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'ghost';

export interface ButtonProps {
  /** The visible text. */
  label:    string;
  onPress:  () => void;
  variant?: ButtonVariant;
  /** Decorative icon before the label (hidden from assistive technology). */
  icon?:    IoniconsName;
  /** Shows a spinner and ignores presses until the work is done. */
  loading?: boolean;
  disabled?: boolean;
  /**
   * Extra context for assistive technology ("Cancel appointment with Dr. Moyo"). The accessible name always starts
   * with the visible label; text that does not start with it is appended after the label.
   */
  accessibilityLabel?: string;
  size?:      'sm' | 'md';
  /** Stretch to the width of the container (stacked buttons on a phone). */
  fullWidth?: boolean;
  style?:     StyleProp<ViewStyle>;
  testID?:    string;
}

interface Paint { bg: string; fg: string; border: string }

function paint(variant: ButtonVariant, theme: RoleTheme, inactive: boolean): Paint {
  if (inactive) return { bg: colors.disabledBg, fg: colors.disabledText, border: colors.disabledBg };
  switch (variant) {
    case 'secondary': return { bg: colors.surface, fg: theme.accent, border: theme.accent };
    case 'danger':    return { bg: colors.danger, fg: '#FFFFFF', border: colors.danger };
    case 'ghost':     return { bg: 'transparent', fg: theme.accent, border: 'transparent' };
    default:          return { bg: theme.accent, fg: theme.accentText, border: theme.accent };
  }
}

export function Button({
  label, onPress, variant = 'primary', icon, loading = false, disabled = false,
  accessibilityLabel, size = 'md', fullWidth = false, style, testID,
}: ButtonProps) {
  const theme = useUiTheme();
  const p = paint(variant, theme, disabled);
  const small = size === 'sm';
  return (
    <Pressable
      onPress={onPress}
      // While loading the button is disabled too (react-native-web then sets aria-disabled); it keeps the focus it has.
      disabled={disabled || loading}
      accessibilityRole="button"
      accessibilityLabel={accessibleName(label, accessibilityLabel)}
      accessibilityState={{ disabled: disabled || loading, busy: loading }}
      aria-busy={loading}
      testID={testID}
      style={({ pressed }) => [
        styles.base,
        small && styles.small,
        fullWidth && styles.fullWidth,
        { backgroundColor: p.bg, borderColor: p.border },
        pressed && !disabled && !loading && styles.pressed,
        style,
      ]}
    >
      {loading ? (
        <View {...decorative}>
          <ActivityIndicator size="small" color={p.fg} />
        </View>
      ) : icon ? (
        <Icon name={icon} size={small ? 14 : 16} color={p.fg} />
      ) : null}
      <Text style={[styles.label, small && styles.labelSmall, { color: p.fg }]} numberOfLines={2}>{label}</Text>
    </Pressable>
  );
}

export interface IconButtonProps {
  /** The accessible name (there is no visible text), for example "Close" or "Remove penicillin". */
  label:    string;
  onPress:  () => void;
  icon:     IoniconsName;
  variant?: ButtonVariant;
  loading?: boolean;
  disabled?: boolean;
  /** Same as `label` when omitted. Kept so Button and IconButton take the same props. */
  accessibilityLabel?: string;
  size?:   'sm' | 'md';
  style?:  StyleProp<ViewStyle>;
  testID?: string;
}

/** A square button that shows only an icon. The icon is decorative; `label` is what assistive technology reads. */
export function IconButton({
  label, onPress, icon, variant = 'ghost', loading = false, disabled = false,
  accessibilityLabel, size = 'md', style, testID,
}: IconButtonProps) {
  const theme = useUiTheme();
  const p = paint(variant, theme, disabled);
  const fg = variant === 'ghost' && !disabled ? colors.textMuted : p.fg;
  const side = size === 'sm' ? 32 : CONTROL_HEIGHT;
  return (
    <Pressable
      onPress={onPress}
      // While loading the button is disabled too (react-native-web then sets aria-disabled); it keeps the focus it has.
      disabled={disabled || loading}
      accessibilityRole="button"
      accessibilityLabel={accessibleName(label, accessibilityLabel)}
      accessibilityState={{ disabled: disabled || loading, busy: loading }}
      aria-busy={loading}
      testID={testID}
      style={({ pressed }) => [
        styles.iconBase,
        { width: side, height: side, backgroundColor: p.bg, borderColor: p.border },
        pressed && !disabled && !loading && styles.pressed,
        style,
      ]}
    >
      {loading ? (
        <View {...decorative}>
          <ActivityIndicator size="small" color={fg} />
        </View>
      ) : (
        <Icon name={icon} size={size === 'sm' ? 16 : 20} color={fg} />
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  base: {
    minHeight: CONTROL_HEIGHT, maxWidth: '100%', flexDirection: 'row', alignItems: 'center', justifyContent: 'center',
    gap: space.sm, paddingHorizontal: space.lg, paddingVertical: space.sm,
    borderWidth: 1, borderRadius: radius.md, alignSelf: 'flex-start',
  },
  small:      { minHeight: 32, paddingHorizontal: space.md, paddingVertical: space.xs },
  fullWidth:  { alignSelf: 'stretch' },
  pressed:    { opacity: 0.85 },
  label:      { fontSize: fontSize.md, fontWeight: '600', textAlign: 'center', flexShrink: 1 },
  labelSmall: { fontSize: fontSize.sm },
  iconBase:   { alignItems: 'center', justifyContent: 'center', borderWidth: 1, borderRadius: radius.md },
});
