import { type ReactNode, useCallback, useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, Button, colors, ErrorView, fontSize, LoadingView, NoAccessView, type PagedState, radius,
  Screen, space, Tabs, useUiTheme,
} from '@/components/web/ui';
import type { ResourceState } from '@/hooks/use-resource';
import { useSectionNav } from '@/hooks/use-section-nav';
import type { SectionParams, SectionParamsInput, SectionProps } from '@/lib/nav/sections';
import type { RecordCardActions } from './card-parts';

// The frame shared by the patient record screens (lab results, imaging results, medical records, consultation
// history): page header with Refresh, optional tabs driven by the `tab` URL param, the dismissible banner for a
// refresh that failed after rows were loaded, and the panel for a row opened by `id` from a link.

/** A tab added to a record screen by the dashboard (for example Corrections on Medical Records). */
export interface RecordTab {
  /** Value of the `tab` URL param that selects it. */
  key:    string;
  label:  string;
  /** A count shown after the label. */
  badge?: number;
  render: () => ReactNode;
}

/**
 * Props of every patient record screen. All optional: the screens work when the registry passes its
 * `SectionProps` and when it only passes `onBack`.
 */
export interface RecordScreenProps<T> extends Partial<SectionProps> {
  /** Shows a Back button that calls it. */
  onBack?:      () => void;
  /** Extra controls for each card (attachment, correction request); receives the row. */
  cardActions?: RecordCardActions<T>;
  /** Tabs shown after the screen's own list; selected through the `tab` URL param. */
  extraTabs?:   RecordTab[];
}

/** URL params and navigation for a record screen: from its props when given, else from the surrounding dashboard. */
export function useRecordScreenNav(props: Partial<SectionProps>): {
  params:    SectionParams;
  /** Updates params of this section in place (filters, the selected tab, the opened row). */
  setParams: (patch: SectionParamsInput) => void;
  navigate:  (section: string, params?: SectionParamsInput) => void;
} {
  const nav = useSectionNav();
  return {
    params:    props.params ?? nav.params,
    setParams: nav.setParams,
    navigate:  props.navigate ?? nav.navigate,
  };
}

/** A positive integer URL param (`id`), or null when it is missing or not a number. */
export function positiveIntParam(value: string | undefined): number | null {
  if (!value || !/^\d{1,15}$/.test(value)) return null;
  const n = Number(value);
  return n > 0 ? n : null;
}

/** A URL param that must be one of a fixed set of upper-case values (a type filter); anything else is no filter. */
export function enumParam<T extends string>(value: string | undefined, allowed: readonly T[]): T | undefined {
  const key = (value ?? '').trim().toUpperCase();
  return (allowed as readonly string[]).includes(key) ? (key as T) : undefined;
}

export interface ResolvedRecordTab {
  /** Key of the tab on screen: the main tab unless `tab` names a registered extra tab. */
  active:  string;
  /** The extra tab on screen, or null when the screen's own list is shown. */
  extra:   RecordTab | null;
  /** True when the screen's own list is shown. */
  isMain:  boolean;
  /**
   * True when the `id` URL param refers to a row of the screen's own list. With another `tab` in the URL the id
   * belongs to that tab (a correction request, for example), even before that tab is registered.
   */
  ownsId:  boolean;
}

export function resolveRecordTab(mainKey: string, extraTabs: RecordTab[] | undefined, tabParam: string | undefined): ResolvedRecordTab {
  const extra = (extraTabs ?? []).find(t => t.key === tabParam) ?? null;
  return {
    active: extra ? extra.key : mainKey,
    extra,
    isMain: !extra,
    ownsId: !tabParam || tabParam === mainKey,
  };
}

export interface PagedFeedback<T> {
  /** Pass to `PagedList`: the same list, with the error left out while rows are on screen (the banner shows it). */
  state:   PagedState<T>;
  /** The failure to show above the rows, or null. */
  failure: { text: string; retry: () => void; dismiss: () => void } | null;
  /** Reloads the list from the first page. */
  refetch: () => void;
}

/**
 * A refresh or a next page that fails keeps the loaded rows. This reports that failure for a dismissible banner
 * above them, whose Retry repeats the request that failed. A dismissed failure stays hidden until the next attempt.
 */
export function usePagedFeedback<T>(state: PagedState<T>): PagedFeedback<T> {
  const lastAction = useRef<'refresh' | 'more'>('refresh');
  const [dismissed, setDismissed] = useState(false);
  const { refetch: stateRefetch, loadMore: stateLoadMore } = state;

  const busy = state.loading || state.refreshing || state.loadingMore;
  useEffect(() => {
    // The list recovered: a later failure is new and worth showing.
    if (!state.error) setDismissed(false);
  }, [state.error]);

  // Every new attempt un-dismisses, so its failure is shown even when the message is the same as the last one.
  const refetch = useCallback(() => {
    lastAction.current = 'refresh';
    setDismissed(false);
    stateRefetch();
  }, [stateRefetch]);
  const loadMore = useCallback(() => {
    lastAction.current = 'more';
    setDismissed(false);
    stateLoadMore();
  }, [stateLoadMore]);

  const hasRows = state.items.length > 0;
  const showFailure = hasRows && !!state.error && !dismissed && !busy;
  const afterMore = lastAction.current === 'more';

  return {
    state: {
      ...state, refetch, loadMore,
      error:       hasRows ? null : state.error,
      errorStatus: hasRows ? null : state.errorStatus,
    },
    failure: showFailure ? {
      text:    afterMore ? `${state.error} The rest of the list could not be loaded.` : `${state.error} Showing what was loaded earlier.`,
      retry:   afterMore ? loadMore : refetch,
      dismiss: () => setDismissed(true),
    } : null,
    refetch,
  };
}

/** The dismissible banner for `usePagedFeedback().failure`. Renders nothing when there is none. */
export function RefreshFailureBanner({ failure }: { failure: PagedFeedback<unknown>['failure'] }) {
  if (!failure) return null;
  return (
    <Banner
      tone="danger"
      text={failure.text}
      action={{ label: 'Retry', onPress: failure.retry }}
      onDismiss={failure.dismiss}
    />
  );
}

/**
 * The row named by the `id` URL param (a notification link, a link from another record), shown above the list
 * so it is found whatever page it is on. Says in words when the row no longer exists or may not be read.
 */
export function FocusedRecord<T>({ state, title, noun, onClose, render }: {
  state:   ResourceState<T>;
  /** Heading of the panel, for example "Lab result from your link". */
  title:   string;
  /** What the row is, in a sentence: "lab result", "consultation". */
  noun:    string;
  /** Removes the `id` param, closing the panel. */
  onClose: () => void;
  render:  (item: T) => ReactNode;
}) {
  const { accent, accentBg } = useUiTheme();
  const { data, error, errorStatus, refetch } = state;

  let body: ReactNode;
  if (data) body = render(data);
  else if (error && errorStatus === 404) body = <Text style={styles.focusNote}>This {noun} is no longer available.</Text>;
  else if (error && errorStatus === 403) body = <NoAccessView compact message={`You don't have access to this ${noun}.`} />;
  else if (error) body = <ErrorView compact message={error} onRetry={refetch} />;
  // Also covers the moment before the account is known, when nothing has been requested yet.
  else body = <LoadingView compact label={`Loading the ${noun}…`} />;

  return (
    <View style={[styles.focus, { borderColor: accent, backgroundColor: accentBg }]} role="region" aria-label={title}>
      <View style={styles.focusHeader}>
        <Text style={styles.focusTitle} accessibilityRole="header" {...ariaLevel(2)}>{title}</Text>
        <Button label="Close" icon="close" variant="ghost" size="sm" accessibilityLabel={`Close ${noun}`} onPress={onClose} />
      </View>
      {body}
    </View>
  );
}

export interface RecordsScreenProps {
  title:       string;
  subtitle:    string;
  /** The screen's own props (theme, role, onBack). */
  screen:      Partial<SectionProps> & { onBack?: () => void };
  /** The tab that shows the screen's own list. Tabs are drawn only when `extraTabs` has entries. */
  mainTab:     { key: string; label: string };
  extraTabs?:  RecordTab[];
  tab:         ResolvedRecordTab;
  onTabChange: (key: string) => void;
  /** Reloads the screen's own list. */
  onRefresh:   () => void;
  refreshing:  boolean;
  /** The screen's own list (the main tab). */
  children:    ReactNode;
}

/** Page frame of a patient record screen. */
export function RecordsScreen({
  title, subtitle, screen, mainTab, extraTabs, tab, onTabChange, onRefresh, refreshing, children,
}: RecordsScreenProps) {
  const tabs = extraTabs && extraTabs.length > 0
    ? [{ key: mainTab.key, label: mainTab.label }, ...extraTabs.map(t => ({ key: t.key, label: t.label, badge: t.badge }))]
    : null;
  const activeLabel = tab.extra ? tab.extra.label : mainTab.label;

  return (
    <Screen
      title={title}
      subtitle={subtitle}
      theme={screen.theme}
      role={screen.role ?? 'patient'}
      actions={(
        <>
          {screen.onBack ? <Button label="Back" icon="arrow-back-outline" variant="ghost" onPress={screen.onBack} /> : null}
          {tab.isMain ? (
            <Button
              label="Refresh"
              icon="refresh-outline"
              variant="secondary"
              accessibilityLabel={`Refresh ${title.toLowerCase()}`}
              loading={refreshing}
              onPress={onRefresh}
            />
          ) : null}
        </>
      )}
    >
      {tabs ? <Tabs tabs={tabs} active={tab.active} onChange={onTabChange} label={`${title} views`} /> : null}
      <View
        key={tab.active}
        style={styles.panel}
        role={tabs ? 'tabpanel' : undefined}
        aria-label={tabs ? activeLabel : undefined}
      >
        {tab.extra ? tab.extra.render() : children}
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  panel: { gap: space.lg, minWidth: 0 },

  focus:       { borderWidth: 1, borderRadius: radius.lg, padding: space.md, gap: space.md, minWidth: 0 },
  focusHeader: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  focusTitle:  { flexShrink: 1, fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  focusNote:   { fontSize: fontSize.md, color: colors.textMuted, lineHeight: 20 },
});
