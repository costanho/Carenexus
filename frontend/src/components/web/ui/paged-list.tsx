import { Fragment, type ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import type { PagedState } from '@/hooks/use-paged';
import { Banner } from './banner';
import { Button } from './button';
import type { IoniconsName } from './icon';
import { EmptyView, type EmptyAction, ErrorView, LoadingView, NoAccessView } from './states';
import { colors, fontSize, space } from './tokens';

export interface PagedListProps<T> {
  /** The result of `usePaged`. */
  state:      PagedState<T>;
  renderItem: (item: T, index: number) => ReactNode;
  /** Stable identity of a row (its id). */
  keyOf:      (item: T) => string | number;
  /** What to show when the list has no rows. */
  empty:      { icon: IoniconsName; title: string; sub?: string; action?: EmptyAction | ReactNode };
  /**
   * Filters or a summary. Rendered above the list in every state (loading, failed, no access, empty, rows) and
   * kept mounted across them, so a search box keeps its focus and its text while the filtered request runs and
   * the filter that caused an error or an empty result can still be changed.
   */
  header?:    ReactNode;
  /** Text of the button that fetches the next page. Default "Load more". */
  loadMoreLabel?: string;
  /** Names the list for assistive technology, for example "Invoices". */
  label?:     string;
  /** Shown instead of the generic sentence when the server answers 403. */
  noAccessMessage?: string;
}

/**
 * Renders a paged list in every state: loading, failed (with Retry), no access, empty, and rows with a
 * "Load more" button and a "Showing X of Y" count. `header` stays above all of them. A refresh or a next page
 * that fails keeps the rows on screen and shows the error above them.
 */
export function PagedList<T>({
  state, renderItem, keyOf, empty, header, loadMoreLabel = 'Load more', label, noAccessMessage,
}: PagedListProps<T>) {
  const { items, loading, loadingMore, refreshing, error, errorStatus, hasMore, total, loadMore, refetch } = state;

  // One wrapper for every state, with the header always its first child: React keeps the header mounted when
  // the state under it changes (a filter change goes rows -> loading -> rows).
  const under = header != null && header !== false;
  let body: ReactNode;

  if (loading) {
    body = <LoadingView compact={under} />;
  } else if (error && items.length === 0) {
    body = errorStatus === 403
      ? <NoAccessView message={noAccessMessage} compact={under} />
      : <ErrorView message={error} onRetry={refetch} compact={under} />;
  } else if (items.length === 0) {
    body = <EmptyView icon={empty.icon} title={empty.title} sub={empty.sub} action={empty.action} />;
  } else {
    // The same row can arrive on two pages when rows are added between requests; a key must appear once.
    const seen = new Set<string | number>();
    const rows = items.filter(item => {
      const key = keyOf(item);
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
    const shown = rows.length;
    const count = total > shown ? `Showing ${shown} of ${total}` : `${shown} ${shown === 1 ? 'item' : 'items'}`;
    body = (
      <>
        {error ? <Banner tone="danger" text={error} action={{ label: 'Retry', onPress: refetch }} /> : null}
        <View style={styles.list} role="list" aria-label={label} aria-busy={refreshing || loadingMore}>
          {rows.map((item, index) => (
            <View key={keyOf(item)} role="listitem" style={styles.item}>
              {renderItem(item, index)}
            </View>
          ))}
        </View>
        <View style={styles.footer}>
          <Text style={styles.count} role="status" aria-live="polite">{count}</Text>
          {hasMore ? (
            <Button label={loadMoreLabel} variant="secondary" icon="chevron-down" onPress={loadMore} loading={loadingMore} />
          ) : null}
        </View>
      </>
    );
  }

  return (
    <View style={styles.wrap}>
      <Fragment key="header">{header}</Fragment>
      <Fragment key="body">{body}</Fragment>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:   { gap: space.md, minWidth: 0 },
  list:   { gap: space.sm, minWidth: 0 },
  item:   { minWidth: 0 },
  footer: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  count:  { fontSize: fontSize.sm, color: colors.textMuted },
});
