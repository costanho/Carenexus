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

interface Prescription {
  prescription_id:      number;
  patient_id:           number;
  doctor_id:            number;
  medication_name:      string;
  generic_name:         string;
  dosage:               string;
  frequency:            string;
  route:                string;
  quantity:             string;
  refills_allowed:      number;
  special_instructions: string;
  status:               'ACTIVE' | 'COMPLETED' | 'CANCELLED' | 'SUSPENDED';
  prescribed_date:      string;
  expiry_date:          string | null;
  doctor_name?:         string;
}

function statusColor(s: string) {
  if (s === 'ACTIVE')    return { bg: '#D1FAE5', text: GREEN };
  if (s === 'COMPLETED') return { bg: '#F3F4F6', text: GRAY };
  if (s === 'SUSPENDED') return { bg: '#FEF3C7', text: ORANGE };
  return { bg: '#FEE2E2', text: RED };
}

function routeIcon(route: string): React.ComponentProps<typeof Ionicons>['name'] {
  if (route === 'ORAL')      return 'tablet-portrait-outline';
  if (route === 'TOPICAL')   return 'hand-right-outline';
  if (route === 'INJECTION') return 'medical-outline';
  return 'medical-outline';
}

function MedCard({ rx, dep }: { rx: Prescription; dep?: CaregiverDependent }) {
  const { bg, text } = statusColor(rx.status);
  const patientName = dep?.patient_name ?? `Patient #${rx.patient_id}`;
  const [expanded, setExpanded] = useState(false);

  return (
    <View style={[styles.card, rx.status === 'ACTIVE' && styles.cardActive]}>
      <View style={styles.cardHead}>
        <View style={[styles.medIcon, { backgroundColor: '#F5F3FF' }]}>
          <Ionicons name={routeIcon(rx.route)} size={20} color={PURPLE} />
        </View>
        <View style={{ flex: 1 }}>
          <Text style={styles.medName}>{rx.medication_name}</Text>
          <Text style={styles.medGeneric}>{rx.generic_name}</Text>
          <Text style={styles.medPatient}>{patientName}</Text>
        </View>
        <View style={[styles.badge, { backgroundColor: bg }]}>
          <Text style={[styles.badgeText, { color: text }]}>{rx.status}</Text>
        </View>
      </View>

      <View style={styles.detailGrid}>
        <View style={styles.detailItem}>
          <Text style={styles.detailLabel}>Dosage</Text>
          <Text style={styles.detailValue}>{rx.dosage}</Text>
        </View>
        <View style={styles.detailItem}>
          <Text style={styles.detailLabel}>Frequency</Text>
          <Text style={styles.detailValue}>{rx.frequency}</Text>
        </View>
        <View style={styles.detailItem}>
          <Text style={styles.detailLabel}>Route</Text>
          <Text style={styles.detailValue}>{rx.route}</Text>
        </View>
        <View style={styles.detailItem}>
          <Text style={styles.detailLabel}>Quantity</Text>
          <Text style={styles.detailValue}>{rx.quantity}</Text>
        </View>
        <View style={styles.detailItem}>
          <Text style={styles.detailLabel}>Refills</Text>
          <Text style={styles.detailValue}>{rx.refills_allowed}</Text>
        </View>
        <View style={styles.detailItem}>
          <Text style={styles.detailLabel}>Prescribed</Text>
          <Text style={styles.detailValue}>{new Date(rx.prescribed_date).toLocaleDateString('en-ZW', { day: 'numeric', month: 'short', year: 'numeric' })}</Text>
        </View>
      </View>

      {rx.special_instructions && (
        <TouchableOpacity onPress={() => setExpanded(e => !e)} activeOpacity={0.7}>
          <View style={styles.instructionHeader}>
            <Ionicons name="information-circle-outline" size={15} color={PURPLE} />
            <Text style={styles.instructionToggle}>Special Instructions</Text>
            <Ionicons name={expanded ? 'chevron-up' : 'chevron-down'} size={14} color={PURPLE} />
          </View>
          {expanded && (
            <View style={styles.instructions}>
              <Text style={styles.instructionText}>{rx.special_instructions}</Text>
            </View>
          )}
        </TouchableOpacity>
      )}
    </View>
  );
}

type Filter = 'all' | 'ACTIVE' | 'COMPLETED';

export default function PatientProxyMedications() {
  const { dependents, loading: depsLoading } = useCaregiverDependents();
  const [prescriptions, setPrescriptions]   = useState<Prescription[]>([]);
  const [loading, setLoading]               = useState(false);
  const [filter,  setFilter]                = useState<Filter>('all');

  const depMap = new Map(dependents.map(d => [d.patient_id, d]));

  useEffect(() => {
    if (dependents.length === 0) return;
    setLoading(true);
    Promise.all(
      dependents.map(d =>
        api.get<Prescription[]>(`/api/prescriptions?patient_id=${d.patient_id}`).then(r => r.data)
      )
    )
      .then(results => setPrescriptions(results.flat()))
      .finally(() => setLoading(false));
  }, [dependents.length]);

  const filters: { key: Filter; label: string }[] = [
    { key: 'all',       label: 'All' },
    { key: 'ACTIVE',    label: 'Active' },
    { key: 'COMPLETED', label: 'Completed' },
  ];

  const visible  = filter === 'all' ? prescriptions : prescriptions.filter(r => r.status === filter);
  const active   = prescriptions.filter(r => r.status === 'ACTIVE').length;
  const total    = prescriptions.length;

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>
      <View style={styles.pageHeader}>
        <View>
          <Text style={styles.pageTitle}>Medications</Text>
          <Text style={styles.pageSub}>Prescriptions for all linked patients</Text>
        </View>
      </View>

      <View style={styles.statsRow}>
        {[
          { label: 'Total',  value: total,  bg: '#F5F3FF', color: PURPLE },
          { label: 'Active', value: active, bg: '#D1FAE5', color: GREEN  },
        ].map(s => (
          <View key={s.label} style={[styles.statPill, { backgroundColor: s.bg }]}>
            <Text style={[styles.statVal, { color: s.color }]}>{s.value}</Text>
            <Text style={[styles.statLabel, { color: s.color }]}>{s.label}</Text>
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
          <Ionicons name="medical-outline" size={40} color={BORDER} />
          <Text style={styles.emptyText}>No prescriptions found</Text>
        </View>
      ) : (
        visible.map(rx => <MedCard key={rx.prescription_id} rx={rx} dep={depMap.get(rx.patient_id)} />)
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll:    { flex: 1, backgroundColor: BG },
  container: { padding: 24, paddingBottom: 48, gap: 16 },

  pageHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 4 },
  pageTitle:  { fontSize: 22, fontWeight: '700', color: TEXT },
  pageSub:    { fontSize: 13, color: GRAY, marginTop: 3 },

  statsRow: { flexDirection: 'row', gap: 12 },
  statPill: { borderRadius: 12, paddingHorizontal: 20, paddingVertical: 12, alignItems: 'center' },
  statVal:  { fontSize: 24, fontWeight: '700' },
  statLabel:{ fontSize: 12, fontWeight: '500', marginTop: 2 },

  filterRow:        { flexDirection: 'row', gap: 8, backgroundColor: WHITE, borderRadius: 10, padding: 4, borderWidth: 1, borderColor: BORDER, alignSelf: 'flex-start' },
  filterTab:        { paddingHorizontal: 16, paddingVertical: 7, borderRadius: 8 },
  filterTabActive:  { backgroundColor: PURPLE },
  filterText:       { fontSize: 13, color: GRAY, fontWeight: '500' },
  filterTextActive: { color: WHITE, fontWeight: '600' },

  card:       { backgroundColor: WHITE, borderRadius: 14, borderWidth: 1, borderColor: BORDER, padding: 18, gap: 14 },
  cardActive: { borderLeftWidth: 3, borderLeftColor: GREEN },
  cardHead:   { flexDirection: 'row', alignItems: 'flex-start', gap: 12 },
  medIcon:    { width: 44, height: 44, borderRadius: 12, alignItems: 'center', justifyContent: 'center' },
  medName:    { fontSize: 15, fontWeight: '700', color: TEXT },
  medGeneric: { fontSize: 12, color: GRAY, marginTop: 2 },
  medPatient: { fontSize: 12, color: PURPLE, fontWeight: '500', marginTop: 3 },

  badge:     { borderRadius: 20, paddingHorizontal: 10, paddingVertical: 3 },
  badgeText: { fontSize: 11, fontWeight: '600' },

  detailGrid:  { flexDirection: 'row', flexWrap: 'wrap', gap: 12 },
  detailItem:  { minWidth: 130 },
  detailLabel: { fontSize: 11, color: GRAY, marginBottom: 2 },
  detailValue: { fontSize: 13, fontWeight: '600', color: TEXT },

  instructionHeader: { flexDirection: 'row', alignItems: 'center', gap: 6, paddingTop: 2 },
  instructionToggle: { fontSize: 13, color: PURPLE, fontWeight: '600', flex: 1 },
  instructions:      { backgroundColor: '#F5F3FF', borderRadius: 8, padding: 12, marginTop: 8 },
  instructionText:   { fontSize: 12, color: TEXT, lineHeight: 20 },

  empty:     { alignItems: 'center', paddingVertical: 60, gap: 12 },
  emptyText: { fontSize: 14, color: GRAY },
});
