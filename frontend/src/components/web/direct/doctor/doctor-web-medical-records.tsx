import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { DoctorCorrectionQueue } from '@/components/web/corrections';
import {
  ariaPressed, Banner, type BannerTone, Button, Card, colors, CONTROL_HEIGHT, fontSize, Icon, IconButton,
  type IoniconsName, PagedList, type PagedState, radius, Screen, space, Tabs, useUiTheme,
} from '@/components/web/ui';
import { useOpenCorrectionCount } from '@/hooks/use-correction-requests';
import {
  deleteMedicalRecord, type DoctorMedicalRecord, type MedicalRecordDto, type RecordCounts, type RecordType,
  useDoctorMedicalRecords, useDoctorRecordCounts,
} from '@/hooks/use-doctor-medical-records';
import { useSectionNav } from '@/hooks/use-section-nav';
import { apiError } from '@/lib/api/errors';
import { ROLE_THEME, type SectionProps } from '@/lib/nav/sections';
import { confirmAction } from '@/lib/ui/dialogs';
import {
  isRecordType, normaliseRecordSearch, RECORD_SEARCH_MAX, RECORD_TYPES, SYSTEM_ID_PATTERN,
} from '@/lib/validation/medical-record';
import { RecordCard } from './records/record-card';
import { RecordDetailPanel } from './records/record-detail-panel';
import { RecordForm } from './records/record-form';
import { RECORD_TYPE_META } from './records/record-meta';

// Doctor > Medical Records. Two tabs, kept in the URL (`tab`):
//   records      the records the doctor wrote: server-side type, patient and text filters, "Load more",
//                new / edit / delete, and a record opened by `id` (notification and queue links)
//   corrections  the correction requests assigned to the doctor (`id` = the request to highlight)
// The reserved `patient` param filters both tabs to one patient and shows a removable chip.

type TabKey = 'records' | 'corrections';
type TypeFilter = 'ALL' | RecordType;

const PAGE_SIZE = 20;
const SEARCH_DEBOUNCE_MS = 300;

interface Notice { tone: BannerTone; text: string }

function toPositiveInt(value: string | undefined): number | null {
  if (!value || !/^[1-9]\d*$/.test(value.trim())) return null;
  const n = Number(value.trim());
  return Number.isSafeInteger(n) ? n : null;
}

function useDebounced(value: string, delayMs: number): string {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return settled;
}

export default function DoctorWebMedicalRecords(props: Partial<SectionProps>) {
  const nav = useSectionNav();
  const params = props.params ?? nav.params;
  const navigate = props.navigate ?? nav.navigate;
  const { setParams, hasSection } = nav;
  const theme = props.theme ?? ROLE_THEME.doctor;

  const tab: TabKey = params.tab === 'corrections' ? 'corrections' : 'records';
  const patientFilter = SYSTEM_ID_PATTERN.test(params.patient ?? '') ? (params.patient as string) : null;
  const typeParam = (params.type ?? '').trim().toUpperCase();
  const typeFilter: TypeFilter = isRecordType(typeParam) ? typeParam : 'ALL';
  const openRecordId = tab === 'records' ? toPositiveInt(params.id) : null;

  const [search, setSearch] = useState('');
  const q = normaliseRecordSearch(useDebounced(search, SEARCH_DEBOUNCE_MS));

  const list = useDoctorMedicalRecords({
    type:            typeFilter === 'ALL' ? null : typeFilter,
    patientSystemId: patientFilter,
    q,
    pageSize:        PAGE_SIZE,
    enabled:         tab === 'records',
  });
  const counts = useDoctorRecordCounts(patientFilter, tab === 'records');
  const openCorrections = useOpenCorrectionCount();

  const [form, setForm] = useState<{ record: DoctorMedicalRecord | null; key: number } | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const deleting = useRef(false);
  const formSeq = useRef(0);

  const { refetch: refetchList, doctorSystemId, patientNames, patientNameOf } = list;
  const refetchCounts = counts.refetch;
  const refresh = useCallback(() => { refetchList(); refetchCounts(); }, [refetchList, refetchCounts]);

  const openForm = (record: DoctorMedicalRecord | null) => {
    setNotice(null);
    formSeq.current += 1;
    setForm({ record, key: formSeq.current });
  };

  const closeDetail = useCallback(() => setParams({ id: undefined }), [setParams]);

  const onSaved = (saved: MedicalRecordDto, created: boolean) => {
    setForm(null);
    setNotice({
      tone: 'success',
      text: created ? `Record filed for ${patientNameOf(saved.patientSystemId)}.` : 'Record updated.',
    });
    refresh();
  };

  const remove = async (record: DoctorMedicalRecord) => {
    if (deleting.current) return;
    const confirmed = await confirmAction(
      'Delete this record?',
      `"${record.title}" will be removed from the medical records of ${record.patient_name}. This cannot be undone.`,
      'Delete',
      true,
    );
    if (!confirmed || deleting.current) return;
    deleting.current = true;
    setDeletingId(record.record_id);
    setNotice(null);
    try {
      await deleteMedicalRecord(record.record_id);
      setNotice({ tone: 'success', text: 'Record deleted.' });
    } catch (e) {
      const failure = apiError(e, 'The record could not be deleted.');
      // 404: someone (or an earlier click) already deleted it; the list just needs a refresh.
      if (failure.status !== 404) {
        setNotice({ tone: 'danger', text: failure.message });
        return;
      }
      setNotice({ tone: 'info', text: 'This record had already been deleted.' });
    } finally {
      deleting.current = false;
      setDeletingId(null);
    }
    if (openRecordId === record.record_id) closeDetail();
    refresh();
  };

  const openConsultation = hasSection('consultations')
    ? (record: DoctorMedicalRecord) => {
        if (record.consultation_id) navigate('consultations', { id: record.consultation_id, patient: record.patient_system_id });
      }
    : undefined;

  const state = useMemo<PagedState<DoctorMedicalRecord>>(() => ({
    items:       list.records,
    loading:     list.loading,
    loadingMore: list.loadingMore,
    refreshing:  list.refreshing,
    error:       list.error,
    errorStatus: list.errorStatus,
    hasMore:     list.hasMore,
    total:       list.total,
    loadMore:    list.loadMore,
    refetch:     refresh,
    // Rows are replaced by reloading after a change, never patched in place.
    setItems:    () => undefined,
  }), [list.records, list.loading, list.loadingMore, list.refreshing, list.error, list.errorStatus, list.hasMore, list.total, list.loadMore, refresh]);

  const filtered = typeFilter !== 'ALL' || !!q || !!patientFilter;

  return (
    <Screen
      title="Medical Records"
      subtitle="All records for patients associated with you"
      theme={theme}
      actions={tab === 'records' ? (
        <>
          <Button label="Refresh" icon="refresh-outline" variant="secondary" onPress={refresh} loading={list.refreshing} />
          <Button label="New record" icon="add" onPress={() => openForm(null)} disabled={!doctorSystemId} />
        </>
      ) : undefined}
    >
      <Tabs<TabKey>
        label="Medical records views"
        tabs={[
          { key: 'records', label: 'Records' },
          { key: 'corrections', label: 'Correction requests', badge: openCorrections.count ?? undefined },
        ]}
        active={tab}
        onChange={key => { setNotice(null); setParams({ tab: key === 'corrections' ? 'corrections' : undefined, id: undefined }); }}
      />

      {patientFilter ? (
        <PatientChip name={patientNameOf(patientFilter)} onRemove={() => setParams({ patient: undefined })} />
      ) : null}

      {notice ? <Banner tone={notice.tone} text={notice.text} onDismiss={() => setNotice(null)} /> : null}

      {tab === 'corrections' ? (
        <DoctorCorrectionQueue
          highlightId={params.id}
          patientSystemId={patientFilter}
          patientName={patientFilter ? patientNameOf(patientFilter) : null}
        />
      ) : (
        <>
          {list.patientNamesError && !list.loading ? (
            <Banner tone="warning" text="Patient names could not be loaded, so records show the patient ID." action={{ label: 'Retry', onPress: refetchList }} />
          ) : null}
          <PagedList
            state={state}
            label="Medical records"
            keyOf={r => r.record_id}
            header={(
              <View style={styles.listHeader}>
                {counts.counts ? <RecordStats counts={counts.counts} /> : null}
                <RecordFilters
                  type={typeFilter}
                  onType={next => setParams({ type: next === 'ALL' ? undefined : next })}
                  search={search}
                  onSearch={setSearch}
                />
              </View>
            )}
            empty={filtered
              ? { icon: 'search-outline', title: 'No records match', sub: 'Change the type, the search or the patient filter to see more records.' }
              : {
                  icon: 'folder-open-outline', title: 'No medical records yet',
                  sub: 'Records you file, and the ones created when you complete a consultation, appear here.',
                  action: { label: 'New record', icon: 'add', onPress: () => openForm(null) },
                }}
            renderItem={record => (
              <RecordCard
                record={record}
                canEdit={!!doctorSystemId && record.doctor_system_id === doctorSystemId}
                onEdit={openForm}
                onDelete={r => { void remove(r); }}
                deleting={deletingId === record.record_id}
                onOpenConsultation={openConsultation}
              />
            )}
          />
        </>
      )}

      {openRecordId !== null && !form ? (
        <RecordDetailPanel
          key={openRecordId}
          recordId={openRecordId}
          doctorSystemId={doctorSystemId}
          patientNames={patientNames}
          onClose={closeDetail}
          onEdit={record => { closeDetail(); openForm(record); }}
          onDelete={r => { void remove(r); }}
          deleting={deletingId === openRecordId}
          onOpenConsultation={openConsultation}
        />
      ) : null}

      {form ? (
        <RecordForm
          key={form.key}
          record={form.record}
          initialPatientSystemId={patientFilter}
          onClose={() => setForm(null)}
          onSaved={onSaved}
          onMissing={() => {
            setForm(null);
            setNotice({ tone: 'info', text: 'This record no longer exists. It may have been deleted.' });
            refresh();
          }}
        />
      ) : null}
    </Screen>
  );
}

// ─── Patient filter chip ─────────────────────────────────────────────────────

function PatientChip({ name, onRemove }: { name: string; onRemove: () => void }) {
  const { accent, accentBg } = useUiTheme();
  return (
    <View style={styles.chipRow}>
      <View style={[styles.chip, { backgroundColor: accentBg, borderColor: accent }]}>
        <Icon name="person-outline" size={14} color={accent} />
        <Text style={styles.chipText}>{`Patient: ${name}`}</Text>
        <IconButton label={`Remove the patient filter (${name})`} icon="close" size="sm" onPress={onRemove} />
      </View>
    </View>
  );
}

// ─── Counts ──────────────────────────────────────────────────────────────────

/** Types that always get a tile; the others appear once the doctor has a record of that type. */
const ALWAYS_COUNTED: readonly RecordType[] = ['CONSULT', 'LAB', 'PRESCRIPTION', 'REFERRAL', 'NOTE'];

/** How many records the doctor has written, in total and per type (for the filtered patient when one is set). */
function RecordStats({ counts }: { counts: RecordCounts }) {
  const types = RECORD_TYPES.filter(t => ALWAYS_COUNTED.includes(t) || counts[t] > 0);
  return (
    <View style={styles.stats} role="group" aria-label="Number of records">
      <StatTile label="Total" value={counts.ALL} icon="folder-open-outline" fg={colors.text} bg={colors.bgSubtle} />
      {types.map(t => {
        const meta = RECORD_TYPE_META[t];
        return <StatTile key={t} label={meta.plural} value={counts[t]} icon={meta.icon} fg={meta.fg} bg={meta.bg} />;
      })}
    </View>
  );
}

function StatTile({ label, value, icon, fg, bg }: { label: string; value: number; icon: IoniconsName; fg: string; bg: string }) {
  return (
    <View style={styles.stat}>
      <View style={[styles.statIcon, { backgroundColor: bg }]}>
        <Icon name={icon} size={16} color={fg} />
      </View>
      <Text style={styles.statValue}>{value}</Text>
      <Text style={styles.statLabel}>{label}</Text>
    </View>
  );
}

// ─── Type and text filters ───────────────────────────────────────────────────

function RecordFilters({ type, onType, search, onSearch }: {
  type:     TypeFilter;
  onType:   (type: TypeFilter) => void;
  search:   string;
  onSearch: (text: string) => void;
}) {
  const { accent, accentBg } = useUiTheme();
  const [focused, setFocused] = useState(false);
  const options: { key: TypeFilter; label: string }[] = [
    { key: 'ALL', label: 'All' },
    ...RECORD_TYPES.map(t => ({ key: t as TypeFilter, label: RECORD_TYPE_META[t].plural })),
  ];

  return (
    <Card style={styles.filters}>
      <View style={styles.pills} role="group" aria-label="Record type">
        {options.map(option => {
          const selected = option.key === type;
          return (
            <Pressable
              key={option.key}
              accessibilityRole="button"
              accessibilityLabel={option.label}
              {...ariaPressed(selected)}
              onPress={() => { if (!selected) onType(option.key); }}
              style={[styles.pill, selected && { backgroundColor: accentBg, borderColor: accent }]}
            >
              <Text style={[styles.pillText, selected && { color: accent, fontWeight: '700' }]}>{option.label}</Text>
            </Pressable>
          );
        })}
      </View>

      <View style={[styles.searchBox, focused && { borderColor: accent }]}>
        <Icon name="search-outline" size={16} color={colors.placeholder} />
        <TextInput
          style={styles.searchInput}
          value={search}
          onChangeText={onSearch}
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
          maxLength={RECORD_SEARCH_MAX}
          placeholder="Search by title or ICD-10 code"
          placeholderTextColor={colors.placeholder}
          // Starts with the words shown in the box (its placeholder), then names what is searched.
          accessibilityLabel="Search by title or ICD-10 code, medical records"
          inputMode="search"
        />
        {search ? <IconButton label="Clear search" icon="close-circle" size="sm" onPress={() => onSearch('')} /> : null}
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  chipRow:  { flexDirection: 'row', flexWrap: 'wrap' },
  chip: {
    flexDirection: 'row', alignItems: 'center', gap: space.xs, maxWidth: '100%',
    paddingLeft: space.md, paddingRight: 2, paddingVertical: 2, borderWidth: 1, borderRadius: radius.pill,
  },
  chipText: { flexShrink: 1, fontSize: fontSize.sm, fontWeight: '600', color: colors.text },

  listHeader: { gap: space.md, minWidth: 0 },
  stats:      { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  stat: {
    flexGrow: 1, flexShrink: 1, flexBasis: 100, minWidth: 100, gap: 2, padding: space.md,
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.lg, backgroundColor: colors.surface,
  },
  statIcon:  { width: 28, height: 28, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  statValue: { fontSize: fontSize.title, fontWeight: '700', color: colors.text },
  statLabel: { fontSize: fontSize.xs, color: colors.textMuted },

  filters: { gap: space.md },
  pills:   { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  pill: {
    minHeight: 32, flexDirection: 'row', alignItems: 'center', gap: space.xs,
    paddingHorizontal: space.md, paddingVertical: space.xs,
    borderWidth: 1, borderColor: colors.borderInput, borderRadius: radius.pill, backgroundColor: colors.surface,
  },
  pillText:  { fontSize: fontSize.sm, fontWeight: '500', color: colors.textMuted },

  searchBox: {
    minHeight: CONTROL_HEIGHT, flexDirection: 'row', alignItems: 'center', gap: space.sm,
    paddingLeft: space.md, paddingRight: space.xs,
    borderWidth: 1, borderColor: colors.borderInput, borderRadius: radius.md, backgroundColor: colors.surface,
  },
  searchInput: { flex: 1, minWidth: 0, minHeight: CONTROL_HEIGHT - 2, fontSize: fontSize.md, color: colors.text },
});
