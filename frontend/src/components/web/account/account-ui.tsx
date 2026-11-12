import type { ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { ariaLevel, colors, fontSize, radius, space } from '@/components/web/ui';

// Small layout pieces shared by the account cards, so every card reads the same way.

/** Body text of a card. */
export function Para({ children, muted = false }: { children: ReactNode; muted?: boolean }) {
  return <Text style={[styles.para, muted && styles.muted]}>{children}</Text>;
}

/** A level-3 heading inside a card (the card title is level 2). */
export function SubHeading({ children }: { children: string }) {
  return <Text style={styles.subHeading} accessibilityRole="header" {...ariaLevel(3)}>{children}</Text>;
}

/** Buttons side by side; they wrap onto further lines on a narrow card. */
export function ButtonRow({ children }: { children: ReactNode }) {
  return <View style={styles.buttonRow}>{children}</View>;
}

/** A part of a card with its own heading, separated from the part above by a rule. */
export function CardPart({ title, children, first = false }: { title: string; children: ReactNode; first?: boolean }) {
  return (
    <View style={[styles.part, !first && styles.partDivider]}>
      <SubHeading>{title}</SubHeading>
      {children}
    </View>
  );
}

/** One bordered row of a list inside a card (a session, an export, an acceptance). */
export function ListRow({ children }: { children: ReactNode }) {
  return <View style={styles.listRow}>{children}</View>;
}

/** The text column of a `ListRow`; it shrinks and wraps so the row never grows wider than the card. */
export function ListRowText({ title, lines }: { title: string; lines: (string | null | undefined | false)[] }) {
  return (
    <View style={styles.listRowText}>
      <Text style={styles.listRowTitle}>{title}</Text>
      {lines.filter((l): l is string => typeof l === 'string' && l.length > 0).map(line => (
        <Text key={line} style={styles.listRowLine}>{line}</Text>
      ))}
    </View>
  );
}

export const accountStyles = StyleSheet.create({
  /** Vertical stack of form fields. */
  form: { gap: space.md, maxWidth: 480, minWidth: 0 },
  /** Vertical stack of rows. */
  list: { gap: space.sm },
  stack: { gap: space.md, minWidth: 0 },
});

const styles = StyleSheet.create({
  para:  { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },
  muted: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  subHeading: { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  buttonRow:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },

  part:        { gap: space.md, minWidth: 0 },
  partDivider: { borderTopWidth: 1, borderTopColor: colors.border, paddingTop: space.lg },

  listRow: {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.md,
    padding: space.md, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.surface,
  },
  listRowText:  { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, gap: 2 },
  listRowTitle: { fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  listRowLine:  { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
