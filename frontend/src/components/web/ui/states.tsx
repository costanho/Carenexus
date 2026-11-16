import { isValidElement, type ReactNode } from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import { decorative } from './a11y';
import { Button } from './button';
import { Icon, type IoniconsName } from './icon';
import { useUiTheme } from './theme';
import { colors, fontSize, space, STATUS_TONES } from './tokens';

// The states every screen renders besides its data: loading, error with retry, empty and no access.

export const NO_ACCESS_MESSAGE = "You don't have access to this information.";

export function LoadingView({ label = 'Loading…', compact = false }: { label?: string; compact?: boolean }) {
  const { accent } = useUiTheme();
  return (
    <View style={[styles.wrap, compact && styles.compact]} role="status" aria-live="polite" accessibilityLabel={label}>
      <View {...decorative}>
        <ActivityIndicator size={compact ? 'small' : 'large'} color={accent} />
      </View>
      {!compact && <Text style={styles.sub}>{label}</Text>}
    </View>
  );
}

export function ErrorView({ message, onRetry, compact = false }: {
  message:  string;
  /** Shows a Retry button that calls it. */
  onRetry?: () => void;
  compact?: boolean;
}) {
  return (
    <View style={[styles.wrap, compact && styles.compact]} accessibilityRole="alert">
      <Icon name="alert-circle-outline" size={compact ? 24 : 40} color={STATUS_TONES.danger.fg} />
      <Text style={styles.message}>{message}</Text>
      {onRetry && <Button label="Retry" icon="refresh-outline" variant="secondary" size="sm" onPress={onRetry} style={styles.action} />}
    </View>
  );
}

export interface EmptyAction { label: string; onPress: () => void; icon?: IoniconsName }

export function EmptyView({ icon, title, sub, action, compact = false }: {
  icon:     IoniconsName;
  title:    string;
  sub?:     string;
  /** What the person can do next: a `{ label, onPress }` button, or any element. */
  action?:  EmptyAction | ReactNode;
  compact?: boolean;
}) {
  const isButton = !!action && !isValidElement(action) && typeof action === 'object' && 'label' in action && 'onPress' in action;
  return (
    <View style={[styles.wrap, compact && styles.compact]}>
      <Icon name={icon} size={compact ? 24 : 40} color={colors.placeholder} />
      <Text style={styles.title}>{title}</Text>
      {sub ? <Text style={styles.sub}>{sub}</Text> : null}
      {isButton ? (
        <Button
          label={(action as EmptyAction).label}
          icon={(action as EmptyAction).icon}
          onPress={(action as EmptyAction).onPress}
          size="sm"
          style={styles.action}
        />
      ) : (action as ReactNode) ?? null}
    </View>
  );
}

/** Shown when the server refuses a read (403), or when the person lacks the permission a panel needs. */
export function NoAccessView({ message = NO_ACCESS_MESSAGE, sub, compact = false }: {
  message?: string;
  /** What to do about it, for example who can grant the permission. */
  sub?:     string;
  compact?: boolean;
}) {
  return (
    <View style={[styles.wrap, compact && styles.compact]} role="status">
      <Icon name="lock-closed-outline" size={compact ? 24 : 40} color={STATUS_TONES.warning.fg} />
      <Text style={styles.title}>{message}</Text>
      {sub ? <Text style={styles.sub}>{sub}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:    { alignItems: 'center', justifyContent: 'center', paddingVertical: 48, paddingHorizontal: space.lg, gap: space.sm },
  compact: { paddingVertical: space.lg },
  title:   { fontSize: fontSize.lg, fontWeight: '600', color: colors.text, textAlign: 'center', maxWidth: 480 },
  message: { fontSize: fontSize.md, color: colors.textMuted, textAlign: 'center', maxWidth: 480, lineHeight: 20 },
  sub:     { fontSize: fontSize.sm, color: colors.textMuted, textAlign: 'center', maxWidth: 480, lineHeight: 19 },
  action:  { alignSelf: 'center', marginTop: space.xs },
});
