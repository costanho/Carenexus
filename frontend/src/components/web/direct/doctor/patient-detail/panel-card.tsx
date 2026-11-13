import type { ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { ariaLevel, Card, colors, fontSize, space } from '@/components/web/ui';

/**
 * A card of the patient detail panel. It looks like the shared SectionCard, but its title is a level-3 heading,
 * because the cards sit inside a side panel whose own title is the level-2 heading. Headings inside the card go
 * at level 4.
 */
export function PanelCard({ title, subtitle, action, children }: {
  title:     string;
  subtitle?: string;
  /** A button shown beside the heading (View all, View yours). */
  action?:   ReactNode;
  children?: ReactNode;
}) {
  return (
    <Card style={styles.section}>
      <View style={styles.header}>
        <View style={styles.titles}>
          <Text style={styles.title} accessibilityRole="header" {...ariaLevel(3)}>{title}</Text>
          {subtitle ? <Text style={styles.subtitle}>{subtitle}</Text> : null}
        </View>
        {action ? <View style={styles.action}>{action}</View> : null}
      </View>
      {children}
    </Card>
  );
}

// The same layout and type as SectionCard (components/web/ui/card.tsx).
const styles = StyleSheet.create({
  section:  { gap: space.md },
  header:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  titles:   { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0, gap: 2 },
  title:    { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  subtitle: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  action:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, maxWidth: '100%' },
});
