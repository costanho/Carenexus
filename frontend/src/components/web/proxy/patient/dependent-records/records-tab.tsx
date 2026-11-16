import type { DependencyList, ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, Button, colors, EmptyView, ErrorView, fontSize, type IoniconsName, LoadingView, NoAccessView, radius,
  SectionCard, SidePanel, space, useUiTheme,
} from '@/components/web/ui';
import type { CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import {
  type DependentRecordsGroup, useDependentRecords,
} from '@/hooks/use-dependent-records';
import { useResource } from '@/hooks/use-resource';
import type { PageResponse } from '@/lib/api/types';
import type { CaregiverFlag } from '@/lib/caregiver/permissions';
import { AccessNote, isAccessStatus } from './common';
import { dependentName, KIND_TEXT, type RecordKind } from './format';

// One tab of Documents & Results: a kind of record, listed per loved one. Each loved one has their own block
// with its own state (loading, not shared, failed with Retry, empty, rows with "Load more"), so one person's
// missing permission or failed request never hides another's records. A record opens in a side panel; which
// record is open lives in the `id` URL param, so notification links and a refresh open it too.

export interface RecordsTabProps<T> {
  kind:         RecordKind;
  /** The permission this kind of record needs, evaluated with the shared caregiver rule. */
  flag:         CaregiverFlag;
  icon:         IoniconsName;
  /** The loved ones to list (already narrowed by the `patient` URL param). */
  dependents:   readonly CaregiverDependent[];
  /** Filter values `fetchPageFor` closes over; a change reloads the lists. */
  filterDeps:   DependencyList;
  /** True when a filter narrower than "everything" is on (changes the empty wording). */
  filtered?:    boolean;
  fetchPageFor: (patientSystemId: string, page: number, size: number) => Promise<PageResponse<T>>;
  /** Reads one record by id, for a link to a record that is not among the loaded rows. */
  getOne:       (id: number) => Promise<T>;
  idOf:         (row: T) => number;
  patientOf:    (row: T) => string;
  /** One card; `dependent` is the loved one whose block the card is in. */
  renderCard:   (row: T, patientName: string, open: () => void, dependent: CaregiverDependent) => ReactNode;
  renderDetail: (row: T, patientName: string) => ReactNode;
  /** Filter controls shown above the lists. */
  filters?:     ReactNode;
  /** The record to show in the side panel (the `id` URL param), or null. */
  openId:       number | null;
  onOpen:       (id: number | null) => void;
}

export function RecordsTab<T>({
  kind, flag, icon, dependents, filterDeps, filtered = false, fetchPageFor, getOne, idOf, patientOf,
  renderCard, renderDetail, filters, openId, onOpen,
}: RecordsTabProps<T>) {
  const words = KIND_TEXT[kind];
  const records = useDependentRecords<T, CaregiverDependent>(dependents, flag, fetchPageFor, filterDeps, {
    keyOf: idOf,
    errorMessage: `Could not load ${words.plural}. Please try again.`,
  });

  const nameOf = (patientSystemId: string, fallback?: string | null) =>
    dependentName(dependents.find(d => d.patientSystemId === patientSystemId), fallback);

  // A link to one record makes no request when nothing of this kind is shared for the people in view: the note
  // in their block already says so.
  const anyAllowed = records.groups.some(g => !isAccessStatus(g.status));
  const panelId = anyAllowed ? openId : null;
  const { accent, accentBg } = useUiTheme();

  return (
    <View style={styles.tab}>
      {anyAllowed ? (
        <View style={styles.summary}>
          {/* Rows on the server for the loved ones in view and the filter in use, not only the loaded pages. */}
          <View style={[styles.stat, { backgroundColor: accentBg }]}>
            <Text style={[styles.statValue, { color: accent }]}>{records.loading ? '…' : records.total}</Text>
            <Text style={[styles.statLabel, { color: accent }]}>Total</Text>
          </View>
          {filters ? <View style={styles.filters}>{filters}</View> : null}
        </View>
      ) : null}
      {records.groups.map(group => (
        <DependentBlock
          key={group.patientSystemId}
          group={group}
          kind={kind}
          icon={icon}
          filtered={filtered}
          idOf={idOf}
          renderCard={row => renderCard(row, dependentName(group.dependent), () => onOpen(idOf(row)), group.dependent)}
          onRetry={() => records.retry(group.patientSystemId)}
          onLoadMore={() => records.loadMore(group.patientSystemId)}
        />
      ))}
      <RecordPanel
        kind={kind}
        id={panelId}
        // Wait for the lists before asking for the record on its own: it is usually among the loaded rows.
        waiting={records.loading}
        loaded={panelId === null ? null : findRow(records.groups, idOf, panelId)}
        getOne={getOne}
        onClose={() => onOpen(null)}
        render={row => renderDetail(row, nameOf(patientOf(row)))}
      />
    </View>
  );
}

function findRow<T>(groups: readonly DependentRecordsGroup<T, CaregiverDependent>[], idOf: (row: T) => number, id: number): T | null {
  for (const group of groups) {
    const row = group.items.find(item => idOf(item) === id);
    if (row) return row;
  }
  return null;
}

// ─── One loved one's block ────────────────────────────────────────────────────

function DependentBlock<T>({ group, kind, icon, filtered, idOf, renderCard, onRetry, onLoadMore }: {
  group:      DependentRecordsGroup<T, CaregiverDependent>;
  kind:       RecordKind;
  icon:       IoniconsName;
  filtered:   boolean;
  idOf:       (row: T) => number;
  renderCard: (row: T) => ReactNode;
  onRetry:    () => void;
  onLoadMore: () => void;
}) {
  const words = KIND_TEXT[kind];
  const name = dependentName(group.dependent);
  const shown = group.items.length;

  let body: ReactNode;
  if (group.status === 'loading') {
    body = <LoadingView compact label={`Loading ${words.plural} for ${name}`} />;
  } else if (isAccessStatus(group.status)) {
    body = <AccessNote status={group.status} what={words.capitalPlural} name={name} />;
  } else if (group.status === 'failed') {
    body = <ErrorView compact message={group.error ?? `Could not load ${words.plural} for ${name}.`} onRetry={onRetry} />;
  } else if (shown === 0) {
    body = (
      <EmptyView
        compact
        icon={icon}
        title={filtered ? `No ${words.plural} match this filter` : `No ${words.plural} yet`}
        sub={filtered ? undefined : `${words.capitalPlural} recorded for ${name} will appear here.`}
      />
    );
  } else {
    const count = group.total > shown
      ? `Showing ${shown} of ${group.total}`
      : `${shown} ${shown === 1 ? words.singular : words.plural}`;
    body = (
      <>
        {group.error ? <Banner tone="danger" text={group.error} action={{ label: 'Retry', onPress: onLoadMore }} /> : null}
        <View style={styles.list} role="list" aria-label={`${words.capitalPlural} for ${name}`} aria-busy={group.loadingMore}>
          {group.items.map(row => (
            <View key={idOf(row)} role="listitem" style={styles.item}>{renderCard(row)}</View>
          ))}
        </View>
        <View style={styles.footer}>
          <Text style={styles.count} role="status" aria-live="polite">{count}</Text>
          {group.hasMore ? (
            <Button
              label="Load more"
              accessibilityLabel={`Load more ${words.plural} for ${name}`}
              variant="secondary"
              icon="chevron-down"
              onPress={onLoadMore}
              loading={group.loadingMore}
            />
          ) : null}
        </View>
      </>
    );
  }

  return <SectionCard title={name}>{body}</SectionCard>;
}

// ─── Detail side panel ────────────────────────────────────────────────────────

function RecordPanel<T>({ kind, id, waiting, loaded, getOne, onClose, render }: {
  kind:    RecordKind;
  id:      number | null;
  waiting: boolean;
  /** The record when it is among the loaded rows. */
  loaded:  T | null;
  getOne:  (id: number) => Promise<T>;
  onClose: () => void;
  render:  (row: T) => ReactNode;
}) {
  const words = KIND_TEXT[kind];
  const title = words.singular.charAt(0).toUpperCase() + words.singular.slice(1);
  const single = useResource<T>(() => getOne(id as number), [kind, id], {
    enabled: id !== null && loaded === null && !waiting,
    errorMessage: `Could not load this ${words.singular}. Please try again.`,
  });
  if (id === null) return null;

  const row = loaded ?? single.data;
  let body: ReactNode;
  if (row) {
    body = render(row);
  } else if (single.errorStatus === 403) {
    body = (
      <NoAccessView
        message={`This ${words.singular} is not shared with you.`}
        sub="Ask your loved one to update your caregiver access if you need to see it."
      />
    );
  } else if (single.errorStatus === 404) {
    body = (
      <EmptyView
        icon="document-outline"
        title={`This ${words.singular} is no longer available`}
        sub="It may have been removed by the care team."
      />
    );
  } else if (single.error) {
    body = <ErrorView message={single.error} onRetry={single.refetch} />;
  } else {
    body = <LoadingView label={`Loading ${words.singular}`} />;
  }

  return (
    <SidePanel open title={title} onClose={onClose}>
      {body}
    </SidePanel>
  );
}

const styles = StyleSheet.create({
  tab:    { gap: space.lg, minWidth: 0 },

  summary:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.md, minWidth: 0 },
  stat:      { minWidth: 88, alignItems: 'center', paddingHorizontal: space.lg, paddingVertical: space.sm, borderRadius: radius.lg },
  statValue: { fontSize: fontSize.title, fontWeight: '700' },
  statLabel: { fontSize: fontSize.xs, fontWeight: '600' },
  filters:   { flexGrow: 1, flexShrink: 1, flexBasis: 220, minWidth: 0 },
  list:   { gap: space.sm, minWidth: 0 },
  item:   { minWidth: 0 },
  footer: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  count:  { fontSize: fontSize.sm, color: colors.textMuted },
});
