import { StyleSheet, View } from 'react-native';
import {
  enumParam, FilterChips, type FilterChipOption, FocusedRecord, MedicalRecordCard, positiveIntParam,
  type RecordScreenProps, RecordsScreen, RecordStats, RefreshFailureBanner, resolveRecordTab, usePagedFeedback,
  useRecordScreenNav,
} from '@/components/web/records';
import { PagedList, space } from '@/components/web/ui';
import {
  MEDICAL_RECORD_TYPES, useMedicalRecordById, useMedicalRecords, useMedicalRecordSummary, type MedicalRecord,
  type MedicalRecordType,
} from '@/hooks/use-medical-records';

/**
 * `cardActions` receives the medical record of the card; `extraTabs` adds tabs selected by the `tab` URL param
 * (the dashboard adds Corrections, opened by `tab=corrections`).
 */
export type PatientWebMedicalRecordsProps = RecordScreenProps<MedicalRecord>;

const PAGE_SIZE = 20;
const MAIN_TAB = { key: 'records', label: 'Records' };

type TypeFilter = 'ALL' | MedicalRecordType;

const TYPE_FILTERS: FilterChipOption<TypeFilter>[] = [
  { key: 'ALL',          label: 'All' },
  { key: 'CONSULT',      label: 'Consultation' },
  { key: 'LAB',          label: 'Lab' },
  { key: 'IMAGING',      label: 'Imaging' },
  { key: 'PRESCRIPTION', label: 'Prescription' },
  { key: 'REFERRAL',     label: 'Referral' },
  { key: 'NOTE',         label: 'Note' },
  { key: 'DISCHARGE',    label: 'Discharge' },
  { key: 'OPERATION',    label: 'Operation' },
];

/**
 * The patient's medical records, newest first, one page at a time. URL params: `type` (record type filter),
 * `id` (a record opened from a link; with another `tab` the id belongs to that tab) and `tab`.
 */
export function PatientWebMedicalRecords(props: PatientWebMedicalRecordsProps) {
  const { cardActions, extraTabs } = props;
  const { params, setParams, navigate } = useRecordScreenNav(props);
  const tab = resolveRecordTab(MAIN_TAB.key, extraTabs, params.tab);

  const type = enumParam(params.type, MEDICAL_RECORD_TYPES);
  const focusId = tab.isMain && tab.ownsId ? positiveIntParam(params.id) : null;

  const list = useMedicalRecords({ type, pageSize: PAGE_SIZE, enabled: tab.isMain });
  const summary = useMedicalRecordSummary({ enabled: tab.isMain });
  const focused = useMedicalRecordById(focusId);
  const feedback = usePagedFeedback(list.paged);

  const typeFilters = TYPE_FILTERS.map(option => ({
    ...option,
    count: option.key === 'ALL' ? summary.total : summary.byType[option.key],
  }));

  const openConsultation = (consultationId: number) => navigate('consultation-history', { id: consultationId });

  const refresh = () => {
    feedback.refetch();
    summary.refetch();
    if (focusId !== null) focused.refetch();
  };

  return (
    <RecordsScreen
      title="Medical Records"
      subtitle="Your complete health record"
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
          title="Medical record from your link"
          noun="medical record"
          onClose={() => setParams({ id: undefined })}
          render={item => (
            <MedicalRecordCard item={item} actions={cardActions} onOpenConsultation={openConsultation} defaultExpanded />
          )}
        />
      ) : null}

      <PagedList
        state={feedback.state}
        label="Medical records"
        keyOf={item => item.record_id}
        renderItem={item => (
          <MedicalRecordCard item={item} actions={cardActions} onOpenConsultation={openConsultation} />
        )}
        header={(
          <View style={styles.header}>
            <RecordStats
              label="Medical records summary"
              stats={[
                { key: 'total',       label: 'Total',       value: summary.total,       icon: 'document-text-outline', tone: 'neutral' },
                { key: 'clinical',    label: 'Clinical',    value: summary.clinical,    icon: 'medkit-outline',        tone: 'success' },
                { key: 'diagnostics', label: 'Diagnostics', value: summary.diagnostics, icon: 'flask-outline',         tone: 'info' },
                { key: 'documents',   label: 'Documents',   value: summary.documents,   icon: 'document-outline',      tone: 'warning' },
              ]}
            />
            <FilterChips
              label="Filter by record type"
              options={typeFilters}
              value={type ?? 'ALL'}
              onChange={key => setParams({ type: key === 'ALL' ? undefined : key })}
            />
          </View>
        )}
        empty={type ? {
          icon:   'folder-open-outline',
          title:  'No records of this type',
          action: { label: 'Show all records', onPress: () => setParams({ type: undefined }) },
        } : {
          icon:  'folder-open-outline',
          title: 'No medical records yet',
          sub:   'Records appear here once your doctor files them.',
        }}
      />
    </RecordsScreen>
  );
}

const styles = StyleSheet.create({
  header: { gap: space.md, minWidth: 0 },
});

export default PatientWebMedicalRecords;
