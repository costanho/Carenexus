import { Ionicons } from '@expo/vector-icons';
import { useEffect, useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { api } from '@/lib/auth/auth.interceptor';
import { useCaregiverDependents, type CaregiverDependent } from '@/hooks/use-caregiver-dependents';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const PURPLE = '#7C3AED';
const GREEN  = '#10B981';
const ORANGE = '#F59E0B';
const RED    = '#EF4444';

type LabFlag = 'NORMAL' | 'LOW' | 'HIGH';

interface LabSubTest {
  value:          number;
  unit:           string;
  reference:      string;
  flag:           LabFlag;
  interpretation?: string;
}

interface LabResult {
  result_id:    number;
  patient_id:   number;
  doctor_id:    number;
  test_name:    string;
  test_type:    string;
  result_data:  Record<string, LabSubTest> | null;
  status:       'COMPLETED' | 'REQUIRES_REVIEW';
  is_critical:  number;
  test_date:    string;
  reviewed_by:  number | null;
  reviewed_at:  string | null;
}

function flagColor(f: LabFlag) {
  if (f === 'HIGH') return { bg: '#FEE2E2', text: RED };
  if (f === 'LOW')  return { bg: '#FEF3C7', text: ORANGE };
  return { bg: '#D1FAE5', text: GREEN };
}

function SubTestRow({ name, sub }: { name: string; sub: LabSubTest }) {
  const { bg, text } = flagColor(sub.flag);
  return (
    <View style={styles.subRow}>
      <Text style={styles.subName}>{name.replace(/_/g, ' ')}</Text>
      <Text style={styles.subVal}>{sub.value} {sub.unit}</Text>
      <Text style={styles.subRef}>{sub.reference}</Text>
      <View style={[styles.flagPill, { backgroundColor: bg }]}>
        <Text style={[styles.flagText, { color: text }]}>{sub.flag}</Text>
      </View>
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
  const patientName = dep?.patient_name ?? `Patient #${result.patient_id}`;

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
      <TouchableOpacity style={styles.labHead} onPress={() => setOpen(o => !o)} activeOpacity={0.8}>
        <View style={[styles.labIcon, { backgroundColor: isCritical ? '#FEE2E2' : isReview ? '#FEF3C7' : '#D1FAE5' }]}>
          <Ionicons name="flask-outline" size={18} color={isCritical ? RED : isReview ? ORANGE : GREEN} />
        </View>
        <View style={{ flex: 1 }}>
          <Text style={styles.labName}>{result.test_name}</Text>
          <Text style={styles.labMeta}>
            {patientName} · {result.test_type} · {new Date(result.test_date).toLocaleDateString('en-ZW', { day: 'numeric', month: 'short', year: 'numeric' })}
          </Text>
        </View>
        <View style={[styles.statusBadge, { backgroundColor: isCritical ? '#FEE2E2' : isReview ? '#FEF3C7' : '#D1FAE5' }]}>
          <Text style={[styles.statusText, { color: isCritical ? RED : isReview ? ORANGE : GREEN }]}>
            {isCritical ? 'CRITICAL' : result.status.replace('_', ' ')}
          </Text>
        </View>
        <Ionicons name={open ? 'chevron-up' : 'chevron-down'} size={16} color={GRAY} style={{ marginLeft: 8 }} />
      </TouchableOpacity>

      {/* Sub-tests */}
      {open && result.result_data && (
        <View style={styles.subList}>
          <View style={styles.subHeader}>
            <Text style={[styles.subCol, { flex: 2 }]}>Test</Text>
            <Text style={styles.subCol}>Result</Text>
            <Text style={styles.subCol}>Reference</Text>
            <Text style={styles.subCol}>Flag</Text>
          </View>
          {Object.entries(result.result_data).map(([key, sub]) => (
            <SubTestRow key={key} name={key} sub={sub} />
          ))}
          {result.reviewed_at && (
            <Text style={styles.reviewedAt}>
              Reviewed {new Date(result.reviewed_at).toLocaleDateString('en-ZW', { day: 'numeric', month: 'short', year: 'numeric' })}
            </Text>
          )}
        </View>
      )}
    </View>
  );
}

type Filter = 'all' | 'REQUIRES_REVIEW' | 'COMPLETED';

export default function PatientProxyDocuments() {
  const { dependents, loading: depsLoading } = useCaregiverDependents();
  const [results, setResults]   = useState<LabResult[]>([]);
  const [loading, setLoading]   = useState(false);
  const [filter, setFilter]     = useState<Filter>('all');

  const depMap = new Map(dependents.map(d => [d.patient_id, d]));

  useEffect(() => {
    if (dependents.length === 0) return;
    setLoading(true);
    Promise.all(
      dependents.map(d =>
        api.get<LabResult[]>(`/api/lab-results?patient_id=${d.patient_id}`).then(r => r.data)
      )
    )
      .then(res => {
        const all = res.flat().sort(
          (a, b) => new Date(b.test_date).getTime() - new Date(a.test_date).getTime()
        );
        setResults(all);
      })
      .finally(() => setLoading(false));
  }, [dependents.length]);

  const filters: { key: Filter; label: string }[] = [
    { key: 'all',              label: 'All Results' },
    { key: 'REQUIRES_REVIEW',  label: 'Needs Review' },
    { key: 'COMPLETED',        label: 'Reviewed' },
  ];

  const visible   = filter === 'all' ? results : results.filter(r => r.status === filter);
  const critical  = results.filter(r => r.is_critical).length;
  const pending   = results.filter(r => r.status === 'REQUIRES_REVIEW').length;

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>
      <View style={styles.pageHeader}>
        <View>
          <Text style={styles.pageTitle}>Documents & Results</Text>
          <Text style={styles.pageSub}>Lab results for all linked patients</Text>
        </View>
      </View>

      <View style={styles.statsRow}>
        {[
          { label: 'Total',    value: results.length, bg: '#F5F3FF', color: PURPLE },
          { label: 'Critical', value: critical,        bg: '#FEE2E2', color: RED    },
          { label: 'Pending',  value: pending,         bg: '#FEF3C7', color: ORANGE },
        ].map(s => (
          <View key={s.label} style={[styles.statPill, { backgroundColor: s.bg }]}>
            <Text style={[styles.statVal, { color: s.color }]}>{s.value}</Text>
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
          >
            <Text style={[styles.filterText, filter === f.key && styles.filterTextActive]}>{f.label}</Text>
          </TouchableOpacity>
        ))}
      </View>

      {depsLoading || loading ? (
        <ActivityIndicator size="large" color={PURPLE} style={{ marginTop: 60 }} />
      ) : visible.length === 0 ? (
        <View style={styles.empty}>
          <Ionicons name="flask-outline" size={40} color={BORDER} />
          <Text style={styles.emptyText}>No lab results found</Text>
        </View>
      ) : (
        visible.map(r => <LabCard key={r.result_id} result={r} dep={depMap.get(r.patient_id)} />)
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll:     { flex: 1, backgroundColor: BG },
  container:  { padding: 24, paddingBottom: 48, gap: 16 },
  pageHeader: { marginBottom: 4 },
  pageTitle:  { fontSize: 22, fontWeight: '700', color: TEXT },
  pageSub:    { fontSize: 13, color: GRAY, marginTop: 3 },

  statsRow:   { flexDirection: 'row', gap: 12 },
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

  empty:     { alignItems: 'center', paddingVertical: 60, gap: 12 },
  emptyText: { fontSize: 14, color: GRAY },
});
