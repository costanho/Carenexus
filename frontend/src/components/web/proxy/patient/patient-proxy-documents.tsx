import { Ionicons } from '@expo/vector-icons';
import { useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { api } from '@/lib/auth/auth.interceptor';
import {
  isForbidden,
  NO_ACCESS_MESSAGE,
  useCaregiverDependents,
  type CaregiverDependent,
} from '@/hooks/use-caregiver-dependents';
import { EmptyState, ErrorState, LoadingState } from './proxy-states';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const PURPLE = '#7C3AED';
const GREEN  = '#047857';
const ORANGE = '#B45309';
const RED    = '#B91C1C';

type LabFlag = 'NORMAL' | 'LOW' | 'HIGH' | string;

interface LabSubTest {
  value?:          number | string;
  unit?:           string;
  reference?:      string;
  flag?:           LabFlag;
  interpretation?: string;
}

type LabStatus = 'PENDING' | 'COMPLETED' | 'CRITICAL' | 'REQUIRES_REVIEW' | 'CANCELLED' | string;

interface LabResult {
  result_id:         number;
  patient_system_id: string;
  test_name:         string;
  test_type:         string;
  result_value:      string | null;
  unit:              string | null;
  reference_range:   string | null;
  result_data:       Record<string, LabSubTest> | null;
  status:            LabStatus;
  is_critical:       number;
  test_date:         string | null;
  reviewed_at:       string | null;
}

function parseResultData(raw: unknown): Record<string, LabSubTest> | null {
  if (!raw) return null;
  let v: unknown = raw;
  if (typeof raw === 'string') {
    try { v = JSON.parse(raw); } catch { return null; }
  }
  if (!v || typeof v !== 'object' || Array.isArray(v)) return null;
  const out: Record<string, LabSubTest> = {};
  Object.entries(v as Record<string, unknown>).forEach(([k, sub]) => {
    out[k] = sub && typeof sub === 'object' ? (sub as LabSubTest) : { value: String(sub ?? '') };
  });
  return out;
}

function mapLabResult(r: any): LabResult {
  return {
    result_id:         r.resultId ?? 0,
    patient_system_id: r.patientSystemId ?? '',
    test_name:         r.testName ?? 'Lab test',
    test_type:         r.testType ?? 'OTHER',
    result_value:      r.resultValue ?? null,
    unit:              r.unit ?? null,
    reference_range:   r.referenceRange ?? null,
    result_data:       parseResultData(r.resultData),
    status:            r.status ?? 'PENDING',
    is_critical:       r.isCritical ? 1 : 0,
    test_date:         r.testDate ?? null,
    reviewed_at:       r.reviewedAt ?? null,
  };
}

function formatDate(d: string | null) {
  if (!d) return '—';
  const dt = new Date(d);
  if (isNaN(dt.getTime())) return '—';
  return dt.toLocaleDateString('en-ZW', { day: 'numeric', month: 'short', year: 'numeric' });
}

function flagColor(f?: LabFlag) {
  if (f === 'HIGH') return { bg: '#FEE2E2', text: RED };
  if (f === 'LOW')  return { bg: '#FEF3C7', text: ORANGE };
  return { bg: '#D1FAE5', text: GREEN };
}

function SubTestRow({ name, sub }: { name: string; sub: LabSubTest }) {
  const { bg, text } = flagColor(sub.flag);
  return (
    <View style={styles.subRow}>
      <Text style={styles.subName}>{name.replace(/_/g, ' ')}</Text>
      <Text style={styles.subVal}>{sub.value ?? '—'} {sub.unit ?? ''}</Text>
      <Text style={styles.subRef}>{sub.reference ?? '—'}</Text>
      {sub.flag ? (
        <View style={[styles.flagPill, { backgroundColor: bg }]}>
          <Text style={[styles.flagText, { color: text }]}>{sub.flag}</Text>
        </View>
      ) : <Text style={styles.subRef}>—</Text>}
      {sub.interpretation && (
        <Text style={styles.subInterp}>{sub.interpretation}</Text>
      )}
    </View>
  );
}

function LabCard({ result, dep }: { result: LabResult; dep?: CaregiverDependent }) {
  const [open, setOpen] = useState(result.is_critical === 1 || result.status === 'REQUIRES_REVIEW');
  const isCritical = result.is_critical === 1;
  const isReview   = result.status === 'REQUIRES_REVIEW';
  const patientName = dep?.patientName ?? 'Patient';

  return (
    <View style={[styles.labCard, isCritical && styles.labCritical, isReview && !isCritical && styles.labReview]}>
      {/* Banners */}
      {isCritical && (
        <View style={styles.critBanner}>
          <Ionicons name="warning" size={14} color={RED} />
          <Text style={styles.critText}>CRITICAL — Requires immediate attention</Text>
        </View>
      )}
      {isReview && !isCritical && (
        <View style={styles.reviewBanner}>
          <Ionicons name="alert-circle-outline" size={14} color={ORANGE} />
          <Text style={styles.reviewText}>Awaiting doctor review</Text>
        </View>
      )}

      {/* Header */}
      <TouchableOpacity
        style={styles.labHead}
        onPress={() => setOpen(o => !o)}
        activeOpacity={0.8}
        accessibilityRole="button"
        accessibilityLabel={`${result.test_name} for ${patientName}`}
        accessibilityHint={open ? 'Collapses result details' : 'Expands result details'}
        accessibilityState={{ expanded: open }}
      >
        <View style={[styles.labIcon, { backgroundColor: isCritical ? '#FEE2E2' : isReview ? '#FEF3C7' : '#D1FAE5' }]}>
          <Ionicons name="flask-outline" size={18} color={isCritical ? RED : isReview ? ORANGE : GREEN} />
        </View>
        <View style={{ flex: 1 }}>
          <Text style={styles.labName}>{result.test_name}</Text>
          <Text style={styles.labMeta}>
            {patientName} · {result.test_type} · {formatDate(result.test_date)}
          </Text>
        </View>
        <View style={[styles.statusBadge, { backgroundColor: isCritical ? '#FEE2E2' : isReview ? '#FEF3C7' : '#D1FAE5' }]}>
          <Text style={[styles.statusText, { color: isCritical ? RED : isReview ? ORANGE : GREEN }]}>
            {isCritical ? 'CRITICAL' : String(result.status).replace(/_/g, ' ')}
          </Text>
        </View>
        <Ionicons name={open ? 'chevron-up' : 'chevron-down'} size={16} color={GRAY} style={{ marginLeft: 8 }} importantForAccessibility="no" />
      </TouchableOpacity>

      {/* Sub-tests */}
      {open && (
        <View style={styles.subList}>
          {result.result_data ? (
            <>
              <View style={styles.subHeader}>
                <Text style={[styles.subCol, { flex: 2 }]}>Test</Text>
                <Text style={styles.subCol}>Result</Text>
                <Text style={styles.subCol}>Reference</Text>
                <Text style={styles.subCol}>Flag</Text>
              </View>
              {Object.entries(result.result_data).map(([key, sub]) => (
                <SubTestRow key={key} name={key} sub={sub} />
              ))}
            </>
          ) : result.result_value ? (
            <View style={styles.subRow}>
              <Text style={styles.subName}>Result</Text>
              <Text style={styles.subVal}>{result.result_value} {result.unit ?? ''}</Text>
              <Text style={styles.subRef}>{result.reference_range ?? '—'}</Text>
            </View>
          ) : (
            <Text style={styles.subRef}>No result values have been recorded yet.</Text>
          )}
          {result.reviewed_at && (
            <Text style={styles.reviewedAt}>
              Reviewed {formatDate(result.reviewed_at)}
            </Text>
          )}
        </View>
      )}
    </View>
  );
}

type Filter = 'all' | 'REQUIRES_REVIEW' | 'COMPLETED';

export default function PatientProxyDocuments() {
  const { dependents, loading: depsLoading, error: depsError, refetch: refetchDeps } = useCaregiverDependents();
  const [results, setResults]   = useState<LabResult[]>([]);
  const [loading, setLoading]   = useState(false);
  const [error,   setError]     = useState<string | null>(null);
  const [denied,  setDenied]    = useState<string[]>([]);
  const [filter,  setFilter]    = useState<Filter>('all');
  const [tick,    setTick]      = useState(0);

  const depMap  = new Map(dependents.map(d => [d.patientSystemId, d]));
  const depKey  = dependents.map(d => `${d.patientSystemId}:${d.canViewLabResults ? 1 : 0}`).join(',');

  useEffect(() => {
    if (dependents.length === 0) { setResults([]); setDenied([]); return; }
    let cancelled = false;
    const allowed  = dependents.filter(d => d.canViewLabResults);
    const noAccess = dependents.filter(d => !d.canViewLabResults).map(d => d.patientName ?? 'a linked patient');
    setLoading(true);
    setError(null);
    Promise.allSettled(
      allowed.map(d =>
        api.get<any[]>(`/api/lab-results/patient/${d.patientSystemId}`)
          .then(r => (Array.isArray(r.data) ? r.data : []).map(mapLabResult))
      )
    )
      .then(settled => {
        if (cancelled) return;
        const all: LabResult[] = [];
        const forbidden = [...noAccess];
        let failed = 0;
        settled.forEach((res, i) => {
          if (res.status === 'fulfilled') all.push(...res.value);
          else if (isForbidden(res.reason)) forbidden.push(allowed[i].patientName ?? 'a linked patient');
          else failed++;
        });
        all.sort((a, b) => new Date(b.test_date ?? 0).getTime() - new Date(a.test_date ?? 0).getTime());
        setResults(all);
        setDenied(forbidden);
        setError(failed > 0 ? 'Some lab results could not be loaded.' : null);
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [depKey, tick]);

  const filters: { key: Filter; label: string }[] = [
    { key: 'all',              label: 'All Results' },
    { key: 'REQUIRES_REVIEW',  label: 'Needs Review' },
    { key: 'COMPLETED',        label: 'Reviewed' },
  ];

  const visible   = filter === 'all' ? results : results.filter(r => r.status === filter);
  const critical  = results.filter(r => r.is_critical).length;
  const pending   = results.filter(r => r.status === 'REQUIRES_REVIEW' || r.status === 'PENDING').length;
  const allDenied = dependents.length > 0 && denied.length === dependents.length;

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>
      <View style={styles.pageHeader}>
        <View style={styles.pageHeaderText}>
          <Text style={styles.pageTitle} accessibilityRole="header">Documents & Results</Text>
          <Text style={styles.pageSub}>Lab results for your linked patients</Text>
        </View>
      </View>

      {depsLoading ? (
        <LoadingState label="Loading linked patients" />
      ) : depsError ? (
        <ErrorState message={depsError} onRetry={refetchDeps} />
      ) : dependents.length === 0 ? (
        <EmptyState icon="people-outline" title="No linked patients yet" />
      ) : allDenied ? (
        <ErrorState message={NO_ACCESS_MESSAGE} />
      ) : (
        <>
          <View style={styles.statsRow}>
            {[
              { label: 'Total',    value: results.length, bg: '#F5F3FF', color: PURPLE },
              { label: 'Critical', value: critical,        bg: '#FEE2E2', color: RED    },
              { label: 'Pending',  value: pending,         bg: '#FEF3C7', color: ORANGE },
            ].map(s => (
              <View key={s.label} style={[styles.statPill, { backgroundColor: s.bg }]}>
                <Text style={[styles.statVal, { color: s.color }]}>{loading ? '…' : s.value}</Text>
                <Text style={[styles.statLabel2, { color: s.color }]}>{s.label}</Text>
              </View>
            ))}
          </View>

          <View style={styles.filterRow}>
            {filters.map(f => (
              <TouchableOpacity
                key={f.key}
                style={[styles.filterTab, filter === f.key && styles.filterTabActive]}
                activeOpacity={0.7}
                onPress={() => setFilter(f.key)}
                accessibilityRole="button"
                accessibilityState={{ selected: filter === f.key }}
              >
                <Text style={[styles.filterText, filter === f.key && styles.filterTextActive]}>{f.label}</Text>
              </TouchableOpacity>
            ))}
          </View>

          {denied.length > 0 && (
            <View style={styles.noticeBanner}>
              <Ionicons name="lock-closed-outline" size={14} color={ORANGE} importantForAccessibility="no" />
              <Text style={styles.noticeText}>
                You don't have access to lab results for {denied.join(', ')}.
              </Text>
            </View>
          )}

          {loading ? (
            <LoadingState label="Loading lab results" />
          ) : error && results.length === 0 ? (
            <ErrorState message={error} onRetry={() => setTick(t => t + 1)} />
          ) : (
            <>
              {error && <ErrorState compact message={error} onRetry={() => setTick(t => t + 1)} />}
              {visible.length === 0 ? (
                <EmptyState icon="flask-outline" title="No lab results found" />
              ) : (
                visible.map(r => <LabCard key={`${r.patient_system_id}-${r.result_id}`} result={r} dep={depMap.get(r.patient_system_id)} />)
              )}
            </>
          )}
        </>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll:     { flex: 1, backgroundColor: BG },
  container:  { padding: 24, paddingBottom: 48, gap: 16 },
  pageHeader: { marginBottom: 4, flexWrap: 'wrap', rowGap: 12, columnGap: 12 },
  pageHeaderText: { flexShrink: 1, minWidth: 0 },
  pageTitle:  { fontSize: 22, fontWeight: '700', color: TEXT },
  pageSub:    { fontSize: 13, color: GRAY, marginTop: 3 },

  statsRow:   { flexDirection: 'row', gap: 12, flexWrap: 'wrap' },
  statPill:   { borderRadius: 12, paddingHorizontal: 20, paddingVertical: 12, alignItems: 'center' },
  statVal:    { fontSize: 24, fontWeight: '700' },
  statLabel2: { fontSize: 12, fontWeight: '500', marginTop: 2 },

  filterRow:        { flexDirection: 'row', gap: 8, backgroundColor: WHITE, borderRadius: 10, padding: 4, borderWidth: 1, borderColor: BORDER, alignSelf: 'flex-start' },
  filterTab:        { paddingHorizontal: 16, paddingVertical: 7, borderRadius: 8 },
  filterTabActive:  { backgroundColor: PURPLE },
  filterText:       { fontSize: 13, color: GRAY, fontWeight: '500' },
  filterTextActive: { color: WHITE, fontWeight: '600' },

  labCard:    { backgroundColor: WHITE, borderRadius: 14, borderWidth: 1, borderColor: BORDER, overflow: 'hidden' },
  labCritical:{ borderColor: RED + '60' },
  labReview:  { borderColor: ORANGE + '60' },

  critBanner:   { flexDirection: 'row', alignItems: 'center', gap: 6, backgroundColor: '#FEE2E2', paddingHorizontal: 16, paddingVertical: 8 },
  critText:     { fontSize: 12, fontWeight: '700', color: RED },
  reviewBanner: { flexDirection: 'row', alignItems: 'center', gap: 6, backgroundColor: '#FEF3C7', paddingHorizontal: 16, paddingVertical: 8 },
  reviewText:   { fontSize: 12, fontWeight: '700', color: ORANGE },

  labHead:  { flexDirection: 'row', alignItems: 'center', gap: 12, padding: 16 },
  labIcon:  { width: 40, height: 40, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  labName:  { fontSize: 14, fontWeight: '700', color: TEXT },
  labMeta:  { fontSize: 12, color: GRAY, marginTop: 2 },

  statusBadge: { borderRadius: 20, paddingHorizontal: 10, paddingVertical: 3 },
  statusText:  { fontSize: 11, fontWeight: '700' },

  subList:   { borderTopWidth: 1, borderTopColor: BORDER, padding: 16, gap: 0 },
  subHeader: { flexDirection: 'row', paddingBottom: 8, borderBottomWidth: 1, borderBottomColor: BORDER, marginBottom: 8 },
  subCol:    { flex: 1, fontSize: 11, fontWeight: '700', color: GRAY, textTransform: 'uppercase' },

  subRow:    { flexDirection: 'row', alignItems: 'center', paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: BG, gap: 4, flexWrap: 'wrap' },
  subName:   { flex: 2, fontSize: 12, color: TEXT, textTransform: 'capitalize' },
  subVal:    { flex: 1, fontSize: 13, fontWeight: '700', color: TEXT },
  subRef:    { flex: 1, fontSize: 11, color: GRAY },
  flagPill:  { borderRadius: 6, paddingHorizontal: 6, paddingVertical: 2 },
  flagText:  { fontSize: 10, fontWeight: '700' },
  subInterp: { width: '100%', fontSize: 11, color: GRAY, marginTop: 2, fontStyle: 'italic' },

  reviewedAt: { fontSize: 11, color: GRAY, marginTop: 8, textAlign: 'right' },

  noticeBanner: { flexDirection: 'row', alignItems: 'center', gap: 8, backgroundColor: '#FFFBEB', borderRadius: 10, borderWidth: 1, borderColor: '#FDE68A', padding: 12 },
  noticeText:   { fontSize: 12, color: TEXT, flex: 1 },
});
