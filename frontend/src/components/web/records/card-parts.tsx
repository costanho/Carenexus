import { type ReactNode, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import {
  accessibleName, ariaLevel, colors, decorative, fontSize, Icon, type IoniconsName, radius, space, STATUS_TONES,
  useUiTheme,
} from '@/components/web/ui';
import { formatClinicDate } from '@/lib/time/clinic';
import { formatDateOnly } from '@/lib/ui/date-only';

// Building blocks shared by the record cards (lab result, imaging result, medical record, consultation).

/**
 * Extra controls for a card, given the row it shows (an attachment button, "Request a correction"). Return null
 * for a row that gets none. The card places them in its footer, so screens add buttons without editing the card.
 */
export type RecordCardActions<T> = (item: T) => ReactNode;

/** "10 Nov 2026" for a date-only value (test date, image date, follow-up date). */
export function formatRecordDay(value: string | null | undefined): string {
  return formatDateOnly(value);
}

/** "Tue 10 Nov 2026" for a clinic wall-clock date-time (reviewed at, filed at, completed at). */
export function formatRecordMoment(value: string | null | undefined): string {
  return formatClinicDate(value, '—');
}

/** "whiteCellCount" and "white_cell_count" become "White Cell Count". */
export function humaniseKey(key: string): string {
  return key
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .trim()
    .replace(/\b\w/g, c => c.toUpperCase());
}

/** Open/closed state of a card's detail area. */
export function useExpanded(initial = false): [boolean, () => void] {
  const [expanded, setExpanded] = useState(initial);
  return [expanded, () => setExpanded(e => !e)];
}

/** The card surface. `criticalText` adds the red banner across the top of a critical result. */
export function RecordCard({ criticalText, children }: { criticalText?: string | null; children: ReactNode }) {
  return (
    <View style={[styles.card, criticalText ? styles.cardCritical : null]}>
      {criticalText ? (
        <View style={styles.criticalBanner}>
          <Icon name="warning" size={16} color={STATUS_TONES.critical.fg} />
          <Text style={styles.criticalText}>{criticalText}</Text>
        </View>
      ) : null}
      <View style={styles.body}>{children}</View>
    </View>
  );
}

/** Icon, title (a level-2 heading), optional lines under it and something on the right (a status, a date). */
export function RecordCardHeader({ icon, lead, title, subtitle, byline, aside }: {
  /** Decorative icon in a tinted square. */
  icon?:     IoniconsName;
  /** A custom decorative element instead of `icon` (an avatar). */
  lead?:     ReactNode;
  title:     string;
  subtitle?: string | null;
  /** Who recorded it, for example "Dr. James Banda · Cardiology". */
  byline?:   string | null;
  aside?:    ReactNode;
}) {
  const { accent, accentBg } = useUiTheme();
  return (
    <View style={styles.header}>
      {lead ?? (icon ? (
        <View style={[styles.iconSquare, { backgroundColor: accentBg }]} {...decorative}>
          <Icon name={icon} size={20} color={accent} />
        </View>
      ) : null)}
      <View style={styles.titles}>
        <Text style={styles.title} accessibilityRole="header" {...ariaLevel(2)}>{title}</Text>
        {subtitle ? <Text style={styles.subtitle}>{subtitle}</Text> : null}
        {byline ? <Text style={styles.byline}>{byline}</Text> : null}
      </View>
      {aside ? <View style={styles.aside}>{aside}</View> : null}
    </View>
  );
}

/** "Dr. James Banda · Cardiology", or just the name. */
export function doctorByline(name: string, specialization: string | null | undefined): string {
  return specialization ? `${name} · ${specialization}` : name;
}

/** A wrapping row of badges. */
export function RecordBadges({ children }: { children: ReactNode }) {
  return <View style={styles.badges}>{children}</View>;
}

export interface RecordMetaItem { icon: IoniconsName; label: string; value: string }

/** Small label and value pairs (dates, reviewer). They wrap instead of widening the card. */
export function RecordMeta({ items }: { items: (RecordMetaItem | null | false | undefined)[] }) {
  const shown = items.filter((item): item is RecordMetaItem => !!item);
  if (shown.length === 0) return null;
  return (
    <View style={styles.meta}>
      {shown.map(item => (
        <View key={item.label} style={styles.metaItem}>
          <Icon name={item.icon} size={14} color={colors.textMuted} />
          <Text style={styles.metaText}>
            <Text style={styles.metaLabel}>{item.label}: </Text>
            {item.value}
          </Text>
        </View>
      ))}
    </View>
  );
}

/** A labelled paragraph of the record (impression, diagnosis, treatment plan). */
export function RecordTextBlock({ label, text, icon, lines, tinted = false }: {
  label:   string;
  text:    string;
  icon?:   IoniconsName;
  /** Cut the text to this many lines (a preview); undefined shows all of it. */
  lines?:  number;
  /** Draw the block on a tinted panel (the headline content of a card). */
  tinted?: boolean;
}) {
  return (
    <View style={[styles.block, tinted && styles.blockTinted]}>
      <View style={styles.blockHeader}>
        {icon ? <Icon name={icon} size={14} color={colors.textMuted} /> : null}
        <Text style={styles.blockLabel}>{label}</Text>
      </View>
      <Text style={styles.blockText} numberOfLines={lines}>{text}</Text>
    </View>
  );
}

/** The button that opens and closes a card's detail area. `subject` names the card for assistive technology. */
export function ExpandToggle({ expanded, onToggle, showLabel, hideLabel, subject }: {
  expanded:  boolean;
  onToggle:  () => void;
  showLabel: string;
  hideLabel: string;
  subject?:  string;
}) {
  const { accent } = useUiTheme();
  const label = expanded ? hideLabel : showLabel;
  return (
    <Pressable
      onPress={onToggle}
      accessibilityRole="button"
      accessibilityLabel={accessibleName(label, subject)}
      accessibilityState={{ expanded }}
      aria-expanded={expanded}
      style={({ pressed }) => [styles.toggle, pressed && styles.pressed]}
    >
      <Text style={[styles.toggleText, { color: accent }]}>{label}</Text>
      <Icon name={expanded ? 'chevron-up' : 'chevron-down'} size={14} color={accent} />
    </Pressable>
  );
}

/**
 * The bottom row of a card: the expand toggle on the left and the screen's extra controls on the right. Renders
 * nothing when the card has neither.
 */
export function RecordCardFooter({ toggle, children }: { toggle?: ReactNode; children?: ReactNode }) {
  const hasActions = children !== null && children !== undefined && children !== false;
  if (!toggle && !hasActions) return null;
  return (
    <View style={styles.footer}>
      {toggle ?? <View />}
      {hasActions ? <View style={styles.footerActions}>{children}</View> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: radius.lg,
    overflow: 'hidden', minWidth: 0, maxWidth: '100%',
  },
  cardCritical:   { borderColor: STATUS_TONES.critical.border },
  criticalBanner: {
    flexDirection: 'row', alignItems: 'center', gap: space.sm,
    backgroundColor: STATUS_TONES.critical.bg, paddingHorizontal: space.lg, paddingVertical: space.sm,
  },
  criticalText:   { flex: 1, minWidth: 0, fontSize: fontSize.sm, fontWeight: '700', color: STATUS_TONES.critical.fg },
  body:           { padding: space.lg, gap: space.md, minWidth: 0 },

  header:     { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.md },
  iconSquare: { width: 40, height: 40, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  // flexBasis keeps the title readable before the status wraps under it on a phone.
  titles:     { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0, gap: 2 },
  title:      { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  subtitle:   { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  byline:     { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  aside:      { alignItems: 'flex-end', gap: space.xs, maxWidth: '100%' },

  badges: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },

  meta:      { flexDirection: 'row', flexWrap: 'wrap', columnGap: space.lg, rowGap: space.xs },
  metaItem:  { flexDirection: 'row', alignItems: 'center', gap: space.xs, maxWidth: '100%' },
  metaText:  { fontSize: fontSize.sm, color: colors.text, flexShrink: 1 },
  metaLabel: { color: colors.textMuted },

  block:       { gap: space.xs, minWidth: 0 },
  blockTinted: { backgroundColor: colors.bgSubtle, borderRadius: radius.md, padding: space.md },
  blockHeader: { flexDirection: 'row', alignItems: 'center', gap: space.xs },
  blockLabel:  { fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted, textTransform: 'uppercase', letterSpacing: 0.5 },
  blockText:   { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },

  toggle:     { minHeight: 32, flexDirection: 'row', alignItems: 'center', gap: space.xs, paddingVertical: space.xs, paddingRight: space.sm, borderRadius: radius.sm },
  toggleText: { fontSize: fontSize.sm, fontWeight: '600' },
  pressed:    { opacity: 0.7 },

  footer: {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm,
    borderTopWidth: 1, borderTopColor: colors.border, paddingTop: space.md,
  },
  footerActions: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, maxWidth: '100%' },
});
