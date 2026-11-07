import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { api } from '@/lib/auth/auth.interceptor';
import { useCaregiverDependents, type CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import { formatDateOnly } from '@/lib/ui/date-only';
import { EmptyState, ErrorState, LoadingState } from '@/components/web/proxy/patient/proxy-states';
import {
  ALL, BG, BORDER, GRAY, GREEN, ORANGE, PURPLE, PURPLE_BG, RED, TEXT, WHITE,
  canView, capitalize, depFirstName, depName, DependentPicker, FilterTabs, joinNames, NotSharedBanner, NotSharedState,
  ScreenHeader, StatRow, useDoctorNames, usePerDependentRecords, usePickedDependent, useScale,
} from './patient-proxy-mobile-shared';

interface SubTest {
  value:          string | null;
  unit:           string | null;
  reference:      string | null;
  flag:           string | null;
  interpretation: string | null;
}

interface LabResult {
  resultId:        number;
  patientSystemId: string;
  doctorSystemId:  string | null;
  testName:        string;
  testType:        string | null;
  resultValue:     string | null;
  unit:            string | null;
  referenceRange:  string | null;
  subTests:        [string, SubTest][] | null;
  status:          string;
  isCritical:      boolean;
  testDate:        string | null;
  reviewedAt:      string | null;
}

/** Only strings and numbers are rendered (a nested object in result_data must never reach a <Text>). */
function str(v: unknown): string | null {
  return typeof v === 'string' && v.trim() ? v.trim() : typeof v === 'number' ? String(v) : null;
}

function parseSubTests(raw: unknown): [string, SubTest][] | null {
  if (!raw) return null;
  let v: unknown = raw;
  if (typeof raw === 'string') {
    try { v = JSON.parse(raw); } catch { return null; }
  }
  if (!v || typeof v !== 'object' || Array.isArray(v)) return null;
  const rows = Object.entries(v as Record<string, unknown>).map(([k, sub]): [string, SubTest] => {
    const o = sub && typeof sub === 'object' ? (sub as Record<string, unknown>) : { value: sub };
    return [k, { value: str(o.value), unit: str(o.unit), reference: str(o.reference), flag: str(o.flag), interpretation: str(o.interpretation) }];
  });
  return rows.length ? rows : null;
}

function mapLabResult(r: any, patientSystemId: string): LabResult {
  return {
    resultId:        r?.resultId ?? 0,
    patientSystemId: r?.patientSystemId ?? patientSystemId,
    doctorSystemId:  str(r?.doctorSystemId),
    testName:        str(r?.testName) ?? 'Lab test',
    testType:        str(r?.testType),
    resultValue:     str(r?.resultValue),
    unit:            str(r?.unit),
    referenceRange:  str(r?.referenceRange),
    subTests:        parseSubTests(r?.resultData),
    status:          str(r?.status) ?? 'PENDING',
    isCritical:      !!r?.isCritical,
    testDate:        str(r?.testDate),
    reviewedAt:      str(r?.reviewedAt),
  };
}

// Module scope so usePerDependentRecords gets a stable loader
function loadLabResults(dep: CaregiverDependent): Promise<LabResult[]> {
  return api.get<any[]>(`/api/lab-results/patient/${dep.patientSystemId}`)
    .then(r => (Array.isArray(r.data) ? r.data : []).map(x => mapLabResult(x, dep.patientSystemId)));
}

function flagColors(f: string | null) {
  if (f === 'HIGH' || f === 'CRITICAL') return { bg: '#FEE2E2', text: RED };
  if (f === 'LOW')                      return { bg: '#FEF3C7', text: ORANGE };
  return { bg: '#D1FAE5', text: GREEN };
}
function tone(r: LabResult) {
  if (r.isCritical)                    return { color: RED,    bg: '#FEE2E2', label: 'Critical' };
  if (r.status === 'REQUIRES_REVIEW')  return { color: ORANGE, bg: '#FEF3C7', label: 'Needs review' };
  if (r.status === 'PENDING')          return { color: GRAY,   bg: '#F3F4F6', label: 'Pending' };
  if (r.status === 'CANCELLED')        return { color: GRAY,   bg: '#F3F4F6', label: 'Cancelled' };
  return { color: GREEN, bg: '#D1FAE5', label: capitalize(r.status) };
}

type Filter = 'all' | 'REQUIRES_REVIEW' | 'COMPLETED';
const FILTERS: { key: Filter; label: string }[] = [
  { key: 'all',             label: 'All' },
  { key: 'REQUIRES_REVIEW', label: 'Needs review' },
  { key: 'COMPLETED',       label: 'Reviewed' },
];

function SubTestRow({ name, sub }: { name: string; sub: SubTest }) {
  const { s } = useScale();
  const fc = flagColors(sub.flag);
  const label = name.replace(/_/g, ' ');
  return (
    <View
      style={[styles.subRow, { paddingVertical: s(8), gap: s(3) }]}
      accessible
      accessibilityLabel={[label, [sub.value, sub.unit].filter(Boolean).join(' ') || 'no value', sub.flag, sub.reference ? `reference ${sub.reference}` : null, sub.interpretation].filter(Boolean).join(', ')}
    >
      <View style={[styles.row, { gap: s(8) }]}>
        <Text style={{ flex: 1, fontSize: s(12), color: TEXT, textTransform: 'capitalize' }} numberOfLines={2}>{label}</Text>
        <Text style={{ fontSize: s(13), fontWeight: '700', color: TEXT, flexShrink: 1, textAlign: 'right' }} numberOfLines={2}>
          {[sub.value ?? '—', sub.unit].filter(Boolean).join(' ')}
        </Text>
        {sub.flag ? (
          <View style={{ backgroundColor: fc.bg, borderRadius: s(6), paddingHorizontal: s(6), paddingVertical: s(2), flexShrink: 0 }}>
            <Text style={{ fontSize: s(10), fontWeight: '700', color: fc.text }}>{sub.flag}</Text>
          </View>
        ) : null}
      </View>
      {sub.reference ? <Text style={{ fontSize: s(11), color: GRAY }}>Reference: {sub.reference}</Text> : null}
      {sub.interpretation ? <Text style={{ fontSize: s(11), color: GRAY, fontStyle: 'italic' }}>{sub.interpretation}</Text> : null}
    </View>
  );
}

function LabCard({ result, person, showPerson, doctor }: { result: LabResult; person: string; showPerson: boolean; doctor: string | null }) {
  const { s } = useScale();
  const isReview = result.status === 'REQUIRES_REVIEW';
  const [open, setOpen] = useState(result.isCritical || isReview);
  const t = tone(result);
  const meta = [showPerson ? person : null, result.testType ? capitalize(result.testType) : null, formatDateOnly(result.testDate, undefined, '')]
    .filter(Boolean).join(' · ');

  return (
    <View style={[styles.card, { borderRadius: s(14), marginBottom: s(10) }, result.isCritical && { borderColor: RED + '60' }, isReview && !result.isCritical && { borderColor: ORANGE + '60' }]}>
      {(result.isCritical || isReview) && (
        <View style={[styles.row, { backgroundColor: t.bg, paddingHorizontal: s(14), paddingVertical: s(7), gap: s(6) }]}>
          <Ionicons name={result.isCritical ? 'warning-outline' : 'alert-circle-outline'} size={s(13)} color={t.color} importantForAccessibility="no" />
          <Text style={{ fontSize: s(12), fontWeight: '700', color: t.color, flexShrink: 1 }}>
            {result.isCritical ? 'Critical result' : 'Awaiting doctor review'}
          </Text>
        </View>
      )}

      <TouchableOpacity
        style={[styles.row, { padding: s(14), gap: s(10) }]}
        onPress={() => setOpen(o => !o)}
        activeOpacity={0.8}
        accessibilityRole="button"
        accessibilityLabel={[result.testName, meta, t.label].filter(Boolean).join(', ')}
        accessibilityHint={open ? 'Hides the result details' : 'Shows the result details'}
        accessibilityState={{ expanded: open }}
      >
        <View style={[styles.labIcon, { backgroundColor: t.bg, width: s(38), height: s(38), borderRadius: s(10) }]}>
          <Ionicons name="flask-outline" size={s(17)} color={t.color} importantForAccessibility="no" />
        </View>
        <View style={{ flex: 1, minWidth: 0 }}>
          <Text style={{ fontSize: s(14), fontWeight: '700', color: TEXT }} numberOfLines={2}>{result.testName}</Text>
          {meta ? <Text style={{ fontSize: s(12), color: GRAY, marginTop: s(2) }} numberOfLines={2}>{meta}</Text> : null}
        </View>
        <View style={{ backgroundColor: t.bg, borderRadius: s(12), paddingHorizontal: s(8), paddingVertical: s(3), flexShrink: 0 }}>
          <Text style={{ fontSize: s(11), fontWeight: '700', color: t.color }} numberOfLines={1}>{t.label}</Text>
        </View>
        <Ionicons name={open ? 'chevron-up' : 'chevron-down'} size={s(15)} color={GRAY} importantForAccessibility="no" />
      </TouchableOpacity>

      {open && (
        <View style={[styles.subList, { paddingHorizontal: s(14), paddingBottom: s(12) }]}>
          {result.subTests ? (
            result.subTests.map(([k, sub]) => <SubTestRow key={k} name={k} sub={sub} />)
          ) : result.resultValue ? (
            <SubTestRow name="Result" sub={{ value: result.resultValue, unit: result.unit, reference: result.referenceRange, flag: null, interpretation: null }} />
          ) : (
            <Text style={{ fontSize: s(12), color: GRAY, paddingTop: s(10) }}>No result values have been recorded yet.</Text>
          )}
          {doctor || result.reviewedAt ? (
            <Text style={{ fontSize: s(11), color: GRAY, marginTop: s(8) }}>
              {[doctor ? `Doctor: ${doctor}` : null, result.reviewedAt ? `Reviewed ${formatDateOnly(result.reviewedAt)}` : null].filter(Boolean).join(' · ')}
            </Text>
          ) : null}
        </View>
      )}
    </View>
  );
}

/** Documents & results: lab results for each loved one who shares them (GET /api/lab-results/patient/{id}). */
export default function PatientProxyMobileDocuments({ initialPatientId, onBack }: {
  initialPatientId?: string;
  onBack: () => void;
}) {
  const { s } = useScale();
  const { dependents, loading: depsLoading, error: depsError, refetch: refetchDeps } = useCaregiverDependents();
  const { items, loading, error, denied, refetch } =
    usePerDependentRecords(dependents, 'canViewLabResults', loadLabResults, 'Some lab results could not be loaded.');
  const doctorName = useDoctorNames();
  const { value: who, setValue: setWho, selected } = usePickedDependent(dependents, initialPatientId, 'all');
  const [filter, setFilter] = useState<Filter>('all');

  const isShared = (d: CaregiverDependent) => canView(d, 'canViewLabResults') && !denied.includes(d.patientSystemId);
  const notShared  = dependents.filter(d => !isShared(d));
  const noneShared = dependents.length > 0 && notShared.length === dependents.length;

  const scoped = useMemo(() => (who === ALL ? items : items.filter(r => r.patientSystemId === who)), [items, who]);
  const visible = useMemo(() => {
    const list = filter === 'all' ? [...scoped] : scoped.filter(r => r.status === filter);
    // Newest test first ("YYYY-MM-DD" sorts as text)
    return list.sort((a, b) => (b.testDate ?? '').localeCompare(a.testDate ?? ''));
  }, [scoped, filter]);
  const nameFor = (id: string) => depName(dependents.find(d => d.patientSystemId === id));

  function renderBody() {
    if (depsLoading && dependents.length === 0) return <LoadingState label="Loading loved ones" />;
    if (depsError && dependents.length === 0)   return <ErrorState message={depsError} onRetry={refetchDeps} />;
    if (dependents.length === 0) return <EmptyState icon="people-outline" title="No loved ones linked yet" />;
    if (selected && !isShared(selected)) {
      return (
        <NotSharedState
          title={`${depFirstName(selected)}'s lab results are not shared with you`}
          sub={`Ask ${depFirstName(selected)} to update the permissions on your caregiver access.`}
        />
      );
    }
    if (!selected && noneShared) {
      return <NotSharedState title="Lab results are not shared with you" sub="Ask your loved ones to update the permissions on your caregiver access." />;
    }
    const busy = loading && items.length === 0;
    return (
      <>
        <StatRow items={[
          { label: 'Total',    value: busy ? '…' : String(scoped.length),                                                                   bg: PURPLE_BG, color: PURPLE },
          { label: 'Critical', value: busy ? '…' : String(scoped.filter(r => r.isCritical).length),                                         bg: '#FEE2E2', color: RED },
          { label: 'Pending',  value: busy ? '…' : String(scoped.filter(r => r.status === 'REQUIRES_REVIEW' || r.status === 'PENDING').length), bg: '#FEF3C7', color: ORANGE },
        ]} />
        <FilterTabs label="Lab result filter" options={FILTERS} value={filter} onChange={setFilter} />

        {!selected && notShared.length > 0 && (
          <NotSharedBanner text={`Lab results for ${joinNames(notShared.map(depName))} are not shared with you.`} />
        )}

        {busy ? (
          <LoadingState label="Loading lab results" />
        ) : error && items.length === 0 ? (
          <ErrorState message={error} onRetry={refetch} />
        ) : (
          <>
            {error ? <ErrorState compact message={error} onRetry={refetch} /> : null}
            {visible.length === 0 ? (
              <EmptyState
                icon="flask-outline"
                title={filter === 'REQUIRES_REVIEW' ? 'No results waiting for review' : filter === 'COMPLETED' ? 'No reviewed results' : 'No lab results yet'}
              />
            ) : (
              visible.map(r => (
                <LabCard
                  key={`${r.patientSystemId}-${r.resultId}`}
                  result={r}
                  person={nameFor(r.patientSystemId)}
                  showPerson={!selected}
                  doctor={doctorName(r.doctorSystemId)}
                />
              ))
            )}
          </>
        )}
      </>
    );
  }

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={{ padding: s(14), paddingBottom: s(24) }} showsVerticalScrollIndicator={false}>
      <ScreenHeader
        title="Documents & Results"
        sub={selected ? `Lab results for ${depName(selected)}` : 'Lab results for your loved ones'}
        onBack={onBack}
      />
      <DependentPicker dependents={dependents} value={who} onChange={setWho} />
      {renderBody()}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll: { flex: 1, backgroundColor: BG },

  card:    { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER, overflow: 'hidden' },
  row:     { flexDirection: 'row', alignItems: 'center' },
  labIcon: { alignItems: 'center', justifyContent: 'center' },
  subList: { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: BORDER },
  subRow:  { borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: BORDER },
});
