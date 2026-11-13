import { StyleSheet, View } from 'react-native';
import {
  ConsultationCard, FilterChips, type FilterChipOption, FocusedRecord, positiveIntParam, type RecordScreenProps,
  RecordsScreen, RecordStats, RefreshFailureBanner, resolveRecordTab, usePagedFeedback, useRecordScreenNav,
} from '@/components/web/records';
import { Button, type PagedState, PagedList, space } from '@/components/web/ui';
import { useConsultationById, useConsultations, type Consultation } from '@/hooks/use-consultations';
import { MAX_PAGE_SIZE } from '@/lib/api/paged';

/** `cardActions` receives the consultation of the card; `extraTabs` adds tabs selected by the `tab` URL param. */
export type PatientWebConsultationHistoryProps = RecordScreenProps<Consultation>;

const PAGE_SIZE = 20;
const MAIN_TAB = { key: 'history', label: 'History' };

type ShowFilter = 'ALL' | 'FOLLOW_UP';

const needsFollowUp = (c: Consultation) => c.follow_up_required === 1;

/**
 * The patient's finalised consultations, newest first, one page at a time. Drafts and notes in progress are the
 * doctor's working record and are never sent to the patient. URL params: `show=follow-up` (only consultations that
 * ask for a follow-up), `id` (a consultation opened from a link) and `tab`. Each card shows the medical record
 * filed for the consultation and links to it.
 *
 * The API has no follow-up filter or count, so "Follow-ups" and "With diagnosis" are counted from the rows loaded
 * so far and shown as a lower bound ("3+") while older consultations remain on the server; the Follow-ups filter
 * works on the loaded rows and loads larger pages, and offers to check older consultations when none of the loaded
 * ones match. "Total" is the server's count.
 */
export function PatientWebConsultationHistory(props: PatientWebConsultationHistoryProps) {
  const { cardActions, extraTabs } = props;
  const { params, setParams, navigate } = useRecordScreenNav(props);
  const tab = resolveRecordTab(MAIN_TAB.key, extraTabs, params.tab);

  const followUpOnly = params.show === 'follow-up';
  const focusId = tab.isMain && tab.ownsId ? positiveIntParam(params.id) : null;

  const list = useConsultations({ pageSize: followUpOnly ? MAX_PAGE_SIZE : PAGE_SIZE, enabled: tab.isMain });
  const focused = useConsultationById(focusId);
  const feedback = usePagedFeedback(list.paged);

  // Counts are shown once the first page is in; until then (or when it failed) they are unknown.
  const rows = list.consultations;
  const counted = !list.loading && (rows.length > 0 || !list.error);
  const lowerBound = list.hasMore;
  const followUps = rows.filter(needsFollowUp);
  const total = counted ? list.total : null;
  const followUpCount = counted ? followUps.length : null;
  const withDiagnosisCount = counted ? rows.filter(c => !!c.diagnosis).length : null;

  const showFilters: FilterChipOption<ShowFilter>[] = [
    { key: 'ALL',       label: 'All',        count: total },
    // A count of the loaded rows only would understate the filter while older pages remain, so it is left off.
    { key: 'FOLLOW_UP', label: 'Follow-ups', count: lowerBound ? null : followUpCount },
  ];

  const listState: PagedState<Consultation> = followUpOnly
    ? { ...feedback.state, items: feedback.state.items.filter(needsFollowUp), total: followUps.length }
    : feedback.state;

  const openRecord = (recordId: number) => navigate('medical-records', { id: recordId });
  const showAll = () => setParams({ show: undefined });

  const refresh = () => {
    feedback.refetch();
    if (focusId !== null) focused.refetch();
  };

  return (
    <RecordsScreen
      title="Consultation History"
      subtitle="Your complete clinical record"
      screen={props}
      mainTab={MAIN_TAB}
      extraTabs={extraTabs}
      tab={tab}
      onTabChange={key => setParams({ tab: key === MAIN_TAB.key ? undefined : key, id: undefined })}
      onRefresh={refresh}
      refreshing={list.refreshing}
    >
      <RefreshFailureBanner failure={feedback.failure} />

      {focusId !== null ? (
        <FocusedRecord
          state={focused}
          title="Consultation from your link"
          noun="consultation"
          onClose={() => setParams({ id: undefined })}
          render={item => <ConsultationCard item={item} actions={cardActions} onOpenRecord={openRecord} defaultExpanded />}
        />
      ) : null}

      <PagedList
        state={listState}
        label={followUpOnly ? 'Consultations that need a follow-up' : 'Consultations'}
        keyOf={item => item.consultation_id}
        renderItem={item => <ConsultationCard item={item} actions={cardActions} onOpenRecord={openRecord} />}
        loadMoreLabel={followUpOnly ? 'Check older consultations' : undefined}
        header={(
          <View style={styles.header}>
            <RecordStats
              label="Consultation summary"
              stats={[
                { key: 'total',     label: 'Total',          value: total,              icon: 'document-text-outline', tone: 'neutral' },
                { key: 'diagnosis', label: 'With diagnosis', value: withDiagnosisCount, icon: 'medal-outline',         tone: 'success', atLeast: lowerBound },
                { key: 'follow-up', label: 'Follow-ups',     value: followUpCount,      icon: 'calendar-outline',      tone: 'warning', atLeast: lowerBound },
              ]}
            />
            <FilterChips
              label="Show consultations"
              options={showFilters}
              value={followUpOnly ? 'FOLLOW_UP' : 'ALL'}
              onChange={key => setParams({ show: key === 'FOLLOW_UP' ? 'follow-up' : undefined })}
            />
          </View>
        )}
        empty={!followUpOnly ? {
          icon:  'document-text-outline',
          title: 'No completed consultations yet',
          sub:   'A consultation appears here once your doctor has completed it.',
        } : list.hasMore ? {
          icon:   'calendar-outline',
          title:  'No follow-ups in the consultations loaded so far',
          sub:    `Checked the ${rows.length} most recent of ${list.total} consultations.`,
          action: (
            <View style={styles.emptyActions}>
              <Button
                label="Check older consultations"
                size="sm"
                variant="secondary"
                loading={list.loadingMore}
                onPress={feedback.state.loadMore}
              />
              <Button label="Show all consultations" size="sm" variant="ghost" onPress={showAll} />
            </View>
          ),
        } : {
          icon:   'calendar-outline',
          title:  'No consultations need a follow-up',
          sub:    rows.length > 0 ? 'None of your completed consultations asks for a follow-up visit.' : undefined,
          action: { label: 'Show all consultations', onPress: showAll },
        }}
      />
    </RecordsScreen>
  );
}

const styles = StyleSheet.create({
  header:       { gap: space.md, minWidth: 0 },
  emptyActions: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'center', gap: space.sm, marginTop: space.sm },
});

export default PatientWebConsultationHistory;
