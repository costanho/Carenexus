import type { ReactNode } from 'react';
import { type StyleProp, StyleSheet, Text, View, type ViewStyle } from 'react-native';
import { ariaLevel } from './a11y';
import { colors, fontSize, radius, space } from './tokens';

/** A white bordered surface. */
export function Card({ children, padded = true, style }: {
  children: ReactNode;
  /** Inner padding. Default true; pass false for cards whose rows draw their own padding (lists, tables). */
  padded?:  boolean;
  style?:   StyleProp<ViewStyle>;
}) {
  return <View style={[styles.card, padded && styles.padded, style]}>{children}</View>;
}

/** A card with a level-2 heading and an optional action beside it. */
export function SectionCard({ title, subtitle, action, children, style }: {
  title:     string;
  subtitle?: string;
  /** A button or link shown beside the heading (Edit, Add, View all). */
  action?:   ReactNode;
  children?: ReactNode;
  style?:    StyleProp<ViewStyle>;
}) {
  return (
    <View style={[styles.card, styles.padded, styles.section, style]}>
      <View style={styles.sectionHeader}>
        <View style={styles.sectionTitles}>
          <Text style={styles.sectionTitle} accessibilityRole="header" {...ariaLevel(2)}>{title}</Text>
          {subtitle ? <Text style={styles.sectionSubtitle}>{subtitle}</Text> : null}
        </View>
        {action ? <View style={styles.sectionAction}>{action}</View> : null}
      </View>
      {children}
    </View>
  );
}

/**
 * One label and value pair of a detail view. On a narrow card the value wraps under the label instead of
 * pushing the card wider than the window; long values (emails, ids) break rather than overflow.
 */
export function InfoRow({ label, value, children, emptyText = 'Not provided' }: {
  label:      string;
  /** The value as text. Missing or blank values show `emptyText`. */
  value?:     string | number | null;
  /** Custom value content (a badge, a link) instead of `value`. */
  children?:  ReactNode;
  emptyText?: string;
}) {
  const text = value === null || value === undefined || String(value).trim() === '' ? null : String(value);
  return (
    <View style={styles.infoRow}>
      <Text style={styles.infoLabel}>{label}</Text>
      <View style={styles.infoValueWrap}>
        {children ?? <Text style={[styles.infoValue, !text && styles.infoEmpty]}>{text ?? emptyText}</Text>}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  card:    { backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: radius.lg, maxWidth: '100%', minWidth: 0 },
  padded:  { padding: space.lg },
  section: { gap: space.md },

  sectionHeader:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  sectionTitles:   { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0, gap: 2 },
  sectionTitle:    { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  sectionSubtitle: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  sectionAction:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, maxWidth: '100%' },

  infoRow:       { flexDirection: 'row', flexWrap: 'wrap', columnGap: space.md, rowGap: 2, paddingVertical: space.xs },
  infoLabel:     { width: 160, maxWidth: '100%', fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 20 },
  infoValueWrap: { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0 },
  infoValue:     { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  infoEmpty:     { color: colors.textSubtle },
});
