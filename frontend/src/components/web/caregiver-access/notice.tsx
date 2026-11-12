import type { ReactNode, RefObject } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { fontSize, Icon, type IoniconsName, radius, space, STATUS_TONES } from '@/components/web/ui';

/**
 * A standing note inside a card (waiting for approval, access ended), with optional buttons. Unlike `Banner`
 * it is not a live region: it describes the state the card is in, so it is read with the card instead of being
 * announced when the list loads.
 */
export function Notice({ tone, icon, title, text, children }: {
  tone:      'info' | 'warning';
  icon:      IoniconsName;
  title:     string;
  text:      string;
  /** Buttons for what the person can do about it. */
  children?: ReactNode;
}) {
  const c = STATUS_TONES[tone];
  return (
    <View style={[styles.notice, { backgroundColor: c.bg, borderColor: c.border }]}>
      <Icon name={icon} size={18} color={c.fg} style={styles.icon} />
      <View style={styles.body}>
        <Text style={[styles.title, { color: c.fg }]}>{title}</Text>
        <Text style={[styles.text, { color: c.fg }]}>{text}</Text>
      </View>
      {children ? <View style={styles.actions}>{children}</View> : null}
    </View>
  );
}

/** Brings a card into view on web (after a link or a button pointed at it). Does nothing on native. */
export function scrollIntoView(ref: RefObject<unknown>): void {
  const node = ref.current as { scrollIntoView?: (options: { block: string; behavior: string }) => void } | null;
  node?.scrollIntoView?.({ block: 'center', behavior: 'smooth' });
}

const styles = StyleSheet.create({
  notice: {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm,
    paddingHorizontal: space.md, paddingVertical: space.sm, borderWidth: 1, borderRadius: radius.md,
  },
  icon:    { flexShrink: 0, alignSelf: 'flex-start', marginTop: 1 },
  // flexBasis keeps a readable text width before the buttons wrap under it on a phone.
  body:    { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, gap: 2 },
  title:   { fontSize: fontSize.sm, fontWeight: '700' },
  text:    { fontSize: fontSize.sm, lineHeight: 19 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
});
