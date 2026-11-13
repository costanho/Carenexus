import { useMemo } from 'react';
import { Linking, StyleSheet, Text, View } from 'react-native';
import { ariaLevel, colors, decorative, fontSize, IS_WEB, space, useUiTheme, webOnly } from '@/components/web/ui';
import { type LegalInline, parseLegalMarkdown } from '@/lib/account/legal-markdown';

// Renders the restricted Markdown of a legal document as text nodes. Nothing in the text is interpreted as
// markup: unknown syntax and raw HTML are shown literally.

function Inlines({ inlines }: { inlines: LegalInline[] }) {
  const { accent } = useUiTheme();
  return (
    <>
      {inlines.map((part, i) => {
        if (!part.href) {
          return <Text key={i} style={part.bold ? styles.bold : undefined}>{part.text}</Text>;
        }
        const href = part.href;
        return (
          <Text
            key={i}
            accessibilityRole="link"
            style={[styles.link, { color: accent }, part.bold && styles.bold]}
            // On web the Text is a real anchor that opens in a new tab; elsewhere the press opens the browser.
            onPress={IS_WEB ? undefined : () => { Linking.openURL(href).catch(() => undefined); }}
            {...webOnly({ href, hrefAttrs: { target: '_blank', rel: 'noopener noreferrer' } })}
          >
            {part.text}
          </Text>
        );
      })}
    </>
  );
}

/**
 * The text of a legal document. `baseLevel` is the heading level a "#" heading gets, so the document's headings
 * sit below the heading of the dialog or page that shows it.
 */
export function LegalDocumentText({ contentMd, baseLevel = 3 }: { contentMd: string; baseLevel?: 2 | 3 }) {
  const blocks = useMemo(() => parseLegalMarkdown(contentMd), [contentMd]);
  return (
    <View style={styles.doc}>
      {blocks.map((block, i) => {
        if (block.kind === 'heading') {
          const level = Math.min(6, baseLevel + block.level - 1) as 2 | 3 | 4 | 5 | 6;
          return (
            <Text
              key={i}
              accessibilityRole="header"
              style={[styles.heading, block.level === 1 && styles.h1, block.level === 2 && styles.h2]}
              {...ariaLevel(level)}
            >
              <Inlines inlines={block.inlines} />
            </Text>
          );
        }
        if (block.kind === 'list') {
          return (
            <View key={i} style={styles.list} accessibilityRole="list">
              {block.items.map((item, j) => (
                <View key={j} style={styles.item} role="listitem">
                  <Text style={styles.bullet} {...decorative}>{'•'}</Text>
                  <Text style={styles.itemText}><Inlines inlines={item} /></Text>
                </View>
              ))}
            </View>
          );
        }
        return <Text key={i} style={styles.paragraph}><Inlines inlines={block.inlines} /></Text>;
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  doc:       { gap: space.md, minWidth: 0 },
  heading:   { fontSize: fontSize.md, fontWeight: '700', color: colors.text, lineHeight: 21 },
  h1:        { fontSize: fontSize.xl, lineHeight: 26 },
  h2:        { fontSize: fontSize.lg, lineHeight: 23 },
  paragraph: { fontSize: fontSize.md, color: colors.text, lineHeight: 22 },
  bold:      { fontWeight: '700' },
  link:      { textDecorationLine: 'underline' },
  list:      { gap: space.xs },
  item:      { flexDirection: 'row', alignItems: 'flex-start', gap: space.sm },
  bullet:    { fontSize: fontSize.md, color: colors.textMuted, lineHeight: 22 },
  itemText:  { flex: 1, minWidth: 0, fontSize: fontSize.md, color: colors.text, lineHeight: 22 },
});
