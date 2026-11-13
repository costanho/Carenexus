import { StyleSheet, Text, View } from 'react-native';
import { Button, IconButton } from './button';
import { Icon, type IoniconsName } from './icon';
import { fontSize, radius, space, STATUS_TONES } from './tokens';

export type BannerTone = 'info' | 'success' | 'warning' | 'danger';

const DEFAULT_ICON: Record<BannerTone, IoniconsName> = {
  info:    'information-circle-outline',
  success: 'checkmark-circle-outline',
  warning: 'warning-outline',
  danger:  'alert-circle-outline',
};

export interface BannerProps {
  tone:  BannerTone;
  text:  string;
  /** Decorative icon; each tone has a default, so the tone is never conveyed by colour alone. */
  icon?: IoniconsName;
  /** One thing the person can do about the message (Retry, Review, Undo). */
  action?:    { label: string; onPress: () => void };
  /** Shows a Dismiss button that calls it. */
  onDismiss?: () => void;
}

/**
 * An inline message above the content it is about. Warnings and errors are announced at once (role alert);
 * information and confirmations are announced politely (role status).
 */
export function Banner({ tone, text, icon, action, onDismiss }: BannerProps) {
  const c = STATUS_TONES[tone];
  const urgent = tone === 'danger' || tone === 'warning';
  return (
    <View
      style={[styles.banner, { backgroundColor: c.bg, borderColor: c.border }]}
      role={urgent ? 'alert' : 'status'}
      aria-live={urgent ? 'assertive' : 'polite'}
    >
      <Icon name={icon ?? DEFAULT_ICON[tone]} size={18} color={c.fg} style={styles.icon} />
      <Text style={[styles.text, { color: c.fg }]}>{text}</Text>
      {action || onDismiss ? (
        <View style={styles.controls}>
          {action ? <Button label={action.label} onPress={action.onPress} variant="secondary" size="sm" /> : null}
          {onDismiss ? <IconButton label="Dismiss" icon="close" size="sm" onPress={onDismiss} /> : null}
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  banner: {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm,
    paddingHorizontal: space.md, paddingVertical: space.sm, borderWidth: 1, borderRadius: radius.md,
  },
  icon:     { flexShrink: 0 },
  // flexBasis lets the text keep a readable width before the controls wrap under it on a phone.
  text:     { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0, fontSize: fontSize.sm, fontWeight: '500', lineHeight: 19 },
  controls: { flexDirection: 'row', alignItems: 'center', gap: space.xs, marginLeft: 'auto' },
});
