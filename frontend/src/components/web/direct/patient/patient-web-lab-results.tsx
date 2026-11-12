import { StyleSheet, View } from 'react-native';
import {
  enumParam, FilterChips, type FilterChipOption, FocusedRecord, LabResultCard, positiveIntParam, type RecordScreenProps,
  RecordsScreen, RecordStats, RefreshFailureBanner, resolveRecordTab, ToggleChip, usePagedFeedback, useRecordScreenNav,
} from '@/components/web/records';
import { Banner, PagedList, space } from '@/components/web/ui';
import {
  LAB_TEST_TYPES, useLabResultById, useLabResults, useLabResultSummary, type LabResult, type LabTestType,
} from '@/hooks/use-lab-results';

/** `cardActions` receives the lab result of the card; `extraTabs` adds tabs selected by the `tab` URL param. */
export type PatientWebLabResultsProps = RecordScreenProps<LabResult>;

const PAGE_SIZE = 20;
const MAIN_TAB = { key: 'results', label: 'Results' };

type TypeFilter = 'ALL' | LabTestType;

const TYPE_FILTERS: FilterChipOption<TypeFilter>[] = [
  { key: 'ALL',     label: 'All' },
  { key: 'BLOOD',   label: 'Blood' },
  { key: 'URINE',   label: 'Urine' },
  { key: 'SWAB',    label: 'Swab' },
  { key: 'CULTURE', label: 'Culture' },
  { key: 'BIOPSY',  label: 'Biopsy' },
  { key: 'GENETIC', label: 'Genetic' },
  { key: 'STOOL',   label: 'Stool' },
  { key: 'OTHER',   label: 'Other' },
];

/**
 * The patient's lab results, newest first, one page at a time. URL params: `type` (test type filter),
 * `critical=true` (critical results only), `id` (a result opened from a link) and `tab`.
 */
export function PatientWebLabResults(props: PatientWebLabResultsProps) {
  const { cardActions, extraTabs } = props;
  const { params, setParams } = useRecordScreenNav(props);
  const tab = resolveRecordTab(MAIN_TAB.key, extraTabs, params.tab);

  const type = enumParam(params.type, LAB_TEST_TYPES);
  const criticalOnly = params.critical === 'true';
  const focusId = tab.isMain && tab.ownsId ? positiveIntParam(params.id) : null;

  const list = useLabResults({ type, critical: criticalOnly ? true : undefined, pageSize: PAGE_SIZE, enabled: tab.isMain });
  const summary = useLabResultSummary({ enabled: tab.isMain });
  const focused = useLabResultById(focusId);
  const feedback = usePagedFeedback(list.paged);

  const filtered = !!type || criticalOnly;
  const criticalCount = summary.critical ?? 0;

  const typeFilters = TYPE_FILTERS.map(option => ({
    ...option,
    count: option.key === 'ALL' ? summary.total : summary.byType[option.key],
  }));

  const refresh = () => {
    feedback.refetch();
    summary.refetch();
    if (focusId !== null) focused.refetch();
  };

  return (
    <RecordsScreen
      title="Lab Results"
      subtitle="Your laboratory test history"
      screen={props}
      mainTab={MAIN_TAB}
      extraTabs={extraTabs}
      tab={tab}
      onTabChange={key => setParams({ tab: key === MAIN_TAB.key ? undefined : key, id: undefined })}
      onRefresh={refresh}
      refreshing={list.refreshing}
    >
      {criticalCount > 0 && !criticalOnly ? (
        <Banner
          tone="danger"
          icon="warning"
          text={criticalCount === 1
            ? '1 of your lab results is marked critical.'
            : `${criticalCount} of your lab results are marked critical.`}
          action={{ label: 'Show critical results', onPress: () => setParams({ critical: 'true', type: undefined }) }}
        />
      ) : null}

      <RefreshFailureBanner failure={feedback.failure} />

      {focusId !== null ? (
        <FocusedRecord
          state={focused}
          title="Lab result from your link"
          noun="lab result"
          onClose={() => setParams({ id: undefined })}
          render={item => <LabResultCard item={item} actions={cardActions} defaultExpanded />}
        />
      ) : null}

      <PagedList
        state={feedback.state}
        label="Lab results"
        keyOf={item => item.result_id}
        renderItem={item => <LabResultCard item={item} actions={cardActions} />}
        header={(
          <View style={styles.header}>
            <RecordStats
              label="Lab results summary"
              stats={[
                { key: 'total',     label: 'Total',     value: summary.total,     icon: 'flask-outline',          tone: 'neutral' },
                { key: 'completed', label: 'Completed', value: summary.completed, icon: 'checkmark-done-outline', tone: 'success' },
                { key: 'pending',   label: 'Pending',   value: summary.pending,   icon: 'hourglass-outline',      tone: 'warning' },
                { key: 'critical',  label: 'Critical',  value: summary.critical,  icon: 'alert-circle-outline',   tone: 'danger' },
              ]}
            />
            <View style={styles.filters}>
              <FilterChips
                label="Filter by test type"
                options={typeFilters}
                value={type ?? 'ALL'}
                onChange={key => setParams({ type: key === 'ALL' ? undefined : key })}
              />
              <ToggleChip
                label="Critical only"
                icon="warning-outline"
                pressed={criticalOnly}
                count={summary.critical}
                onChange={on => setParams({ critical: on ? 'true' : undefined })}
              />
            </View>
          </View>
        )}
        empty={filtered ? {
          icon:   'flask-outline',
          title:  'No lab results match these filters',
          action: { label: 'Clear filters', onPress: () => setParams({ type: undefined, critical: undefined }) },
        } : {
          icon:  'flask-outline',
          title: 'No lab results yet',
          sub:   'Results appear here once your doctor records them.',
        }}
      />
    </RecordsScreen>
  );
}

const styles = StyleSheet.create({
  header:  { gap: space.md, minWidth: 0 },
  filters: { gap: space.sm, alignItems: 'flex-start' },
});

export default PatientWebLabResults;
