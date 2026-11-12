import { StyleSheet, View } from 'react-native';
import {
  enumParam, FilterChips, type FilterChipOption, FocusedRecord, ImagingResultCard, positiveIntParam,
  type RecordScreenProps, RecordsScreen, RecordStats, RefreshFailureBanner, resolveRecordTab, ToggleChip,
  usePagedFeedback, useRecordScreenNav,
} from '@/components/web/records';
import { Banner, PagedList, space } from '@/components/web/ui';
import {
  IMAGING_TYPES, useImagingResultById, useImagingResults, useImagingResultSummary,
  type ImagingResultRecord, type ImagingType,
} from '@/hooks/use-imaging-results';

/**
 * `cardActions` receives the imaging result of the card (a list row, or a result opened by id, which can be
 * cancelled); `extraTabs` adds tabs selected by the `tab` URL param.
 */
export type PatientWebImagingResultsProps = RecordScreenProps<ImagingResultRecord>;

const PAGE_SIZE = 20;
const MAIN_TAB = { key: 'results', label: 'Results' };

type TypeFilter = 'ALL' | ImagingType;

const TYPE_FILTERS: FilterChipOption<TypeFilter>[] = [
  { key: 'ALL',         label: 'All' },
  { key: 'XRAY',        label: 'X-ray' },
  { key: 'ULTRASOUND',  label: 'Ultrasound' },
  { key: 'CT_SCAN',     label: 'CT scan' },
  { key: 'MRI',         label: 'MRI' },
  { key: 'ECHO',        label: 'Echo' },
  { key: 'DEXA',        label: 'DEXA' },
  { key: 'MAMMOGRAPHY', label: 'Mammography' },
  { key: 'PET',         label: 'PET' },
];

/**
 * The patient's imaging results, newest first, one page at a time. URL params: `type` (imaging type filter),
 * `critical=true` (critical results only), `id` (a result opened from a link) and `tab`.
 */
export function PatientWebImagingResults(props: PatientWebImagingResultsProps) {
  const { cardActions, extraTabs } = props;
  const { params, setParams } = useRecordScreenNav(props);
  const tab = resolveRecordTab(MAIN_TAB.key, extraTabs, params.tab);

  const type = enumParam(params.type, IMAGING_TYPES);
  const criticalOnly = params.critical === 'true';
  const focusId = tab.isMain && tab.ownsId ? positiveIntParam(params.id) : null;

  const list = useImagingResults({ type, critical: criticalOnly ? true : undefined, pageSize: PAGE_SIZE, enabled: tab.isMain });
  const summary = useImagingResultSummary({ enabled: tab.isMain });
  const focused = useImagingResultById(focusId);
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
      title="Imaging Results"
      subtitle="Your radiology and imaging history"
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
            ? '1 of your imaging results is marked critical.'
            : `${criticalCount} of your imaging results are marked critical.`}
          action={{ label: 'Show critical results', onPress: () => setParams({ critical: 'true', type: undefined }) }}
        />
      ) : null}

      <RefreshFailureBanner failure={feedback.failure} />

      {focusId !== null ? (
        <FocusedRecord
          state={focused}
          title="Imaging result from your link"
          noun="imaging result"
          onClose={() => setParams({ id: undefined })}
          render={item => <ImagingResultCard item={item} actions={cardActions} defaultExpanded />}
        />
      ) : null}

      <PagedList
        state={feedback.state}
        label="Imaging results"
        keyOf={item => item.imaging_id}
        renderItem={item => <ImagingResultCard item={item} actions={cardActions} />}
        header={(
          <View style={styles.header}>
            <RecordStats
              label="Imaging results summary"
              stats={[
                { key: 'total',     label: 'Total',     value: summary.total,     icon: 'image-outline',          tone: 'neutral' },
                { key: 'completed', label: 'Completed', value: summary.completed, icon: 'checkmark-done-outline', tone: 'success' },
                { key: 'pending',   label: 'Pending',   value: summary.pending,   icon: 'hourglass-outline',      tone: 'warning' },
                { key: 'critical',  label: 'Critical',  value: summary.critical,  icon: 'alert-circle-outline',   tone: 'danger' },
              ]}
            />
            <View style={styles.filters}>
              <FilterChips
                label="Filter by imaging type"
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
          icon:   'image-outline',
          title:  'No imaging results match these filters',
          action: { label: 'Clear filters', onPress: () => setParams({ type: undefined, critical: undefined }) },
        } : {
          icon:  'image-outline',
          title: 'No imaging results yet',
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

export default PatientWebImagingResults;
