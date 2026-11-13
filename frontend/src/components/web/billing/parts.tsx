import type { ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import {
  ariaPressed, Banner, Button, colors, CONTROL_HEIGHT, ErrorView, fontSize, Icon, LoadingView, NoAccessView, radius,
  space, useUiTheme, type PagedState,
} from '@/components/web/ui';

// Small building blocks shared by the billing components.

export interface ChipOption<K extends string> {
  key:   K;
  label: string;
}

/**
 * A row of filter chips: toggle buttons of which exactly one is pressed. They wrap onto more lines on a phone,
 * so the row never widens the page. Each chip is a button (Enter and Space activate it) whose pressed state is
 * announced, and the selected chip also carries a check mark so colour is not the only cue.
 */
export function FilterChips<K extends string>({ options, value, onChange, label }: {
  options:  readonly ChipOption<K>[];
  value:    K;
  onChange: (key: K) => void;
  /** Names the group for assistive technology, for example "Filter invoices by status". */
  label:    string;
}) {
  const { accent, accentBg } = useUiTheme();
  return (
    <View style={styles.chips} role="group" aria-label={label}>
      {options.map(option => {
        const selected = option.key === value;
        return (
          <Pressable
            key={option.key}
            onPress={() => { if (!selected) onChange(option.key); }}
            accessibilityRole="button"
            accessibilityLabel={option.label}
            accessibilityState={{ selected }}
            {...ariaPressed(selected)}
            style={({ pressed }) => [
              styles.chip,
              selected && { borderColor: accent, backgroundColor: accentBg },
              pressed && styles.pressed,
            ]}
          >
            {selected ? <Icon name="checkmark" size={14} color={accent} /> : null}
            <Text style={[styles.chipText, selected && { color: accent, fontWeight: '700' }]}>{option.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

/** One line of an invoice's totals: the label on the left, the amount on the right. */
export function AmountRow({ label, value, strong = false, tone }: {
  label:   string;
  value:   string;
  /** The line that matters most (Total, Balance due). */
  strong?: boolean;
  /** Colour of the amount for a line that needs attention; the label always says what it is. */
  tone?:   'danger';
}) {
  return (
    <View style={styles.amountRow}>
      <Text style={[styles.amountLabel, strong && styles.amountStrong]}>{label}</Text>
      <Text style={[styles.amountValue, strong && styles.amountStrong, tone === 'danger' && { color: colors.danger }]}>
        {value}
      </Text>
    </View>
  );
}

/**
 * A paged list inside a card (payments or claims of one invoice): loading, failed with Retry, no access, empty
 * text, rows, and a "Show more" button while the server has more rows.
 */
export function InlinePagedList<T>({ state, keyOf, renderItem, emptyText, label, noAccessMessage }: {
  state:      PagedState<T>;
  keyOf:      (item: T) => string | number;
  renderItem: (item: T) => ReactNode;
  emptyText:  string;
  /** Names the list for assistive technology. */
  label:      string;
  noAccessMessage?: string;
}) {
  const { items, loading, loadingMore, refreshing, error, errorStatus, hasMore, loadMore, refetch } = state;

  if (loading) return <LoadingView compact label={`Loading ${label.toLowerCase()}`} />;
  if (error && items.length === 0) {
    return errorStatus === 403
      ? <NoAccessView compact message={noAccessMessage} />
      : <ErrorView compact message={error} onRetry={refetch} />;
  }
  if (items.length === 0) return <Text style={styles.emptyText}>{emptyText}</Text>;

  return (
    <View style={styles.inlineWrap}>
      {error ? <Banner tone="danger" text={error} action={{ label: 'Retry', onPress: refetch }} /> : null}
      <View role="list" aria-label={label} aria-busy={refreshing || loadingMore} style={styles.inlineList}>
        {items.map((item, index) => (
          <View key={keyOf(item)} role="listitem" style={[styles.inlineItem, index > 0 && styles.inlineDivider]}>
            {renderItem(item)}
          </View>
        ))}
      </View>
      {hasMore ? (
        <Button label="Show more" variant="secondary" size="sm" icon="chevron-down" onPress={loadMore} loading={loadingMore} />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  chips:    { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, maxWidth: '100%' },
  chip:     {
    minHeight: CONTROL_HEIGHT, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: space.xs,
    paddingHorizontal: space.md, paddingVertical: space.xs, borderWidth: 1, borderColor: colors.borderInput, borderRadius: radius.pill, backgroundColor: colors.surface,
  },
  chipText: { fontSize: fontSize.sm, fontWeight: '500', color: colors.textMuted },
  pressed:  { opacity: 0.85 },

  amountRow:    { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'baseline', columnGap: space.md, rowGap: 2 },
  amountLabel:  { fontSize: fontSize.md, color: colors.textMuted },
  amountValue:  { fontSize: fontSize.md, color: colors.text, textAlign: 'right', marginLeft: 'auto' },
  amountStrong: { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },

  emptyText:     { fontSize: fontSize.md, color: colors.textMuted, lineHeight: 20 },
  inlineWrap:    { gap: space.md, minWidth: 0 },
  inlineList:    { minWidth: 0 },
  inlineItem:    { paddingVertical: space.md, minWidth: 0 },
  inlineDivider: { borderTopWidth: 1, borderTopColor: colors.border },
});
