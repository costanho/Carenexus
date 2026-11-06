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
const GREEN  = '#10B981';
const ORANGE = '#F59E0B';
const RED    = '#EF4444';

interface Prescription {
  prescription_id:      number;
  patientSystemId:      string;
  medication_name:      string;
  generic_name:         string | null;
  dosage:               string | null;
  frequency:            string | null;
  route:                string | null;
  quantity:             string | null;
  refills_allowed:      number | null;
  special_instructions: string | null;
  status:               'ACTIVE' | 'COMPLETED' | 'CANCELLED' | 'SUSPENDED' | string;
  prescribed_date:      string | null;
  expiry_date:          string | null;
}

function mapPrescription(r: any): Prescription {
  return {
    prescription_id:      r.prescriptionId ?? 0,
    patientSystemId:      r.patientSystemId ?? '',
    medication_name:      r.medicationName ?? 'Medication',
    generic_name:         r.genericName ?? null,
    dosage:               r.dosage ?? null,
    frequency:            r.frequency ?? null,
    route:                r.route ?? null,
    quantity:             r.quantity ?? null,
    refills_allowed:      r.refillsAllowed ?? null,
    special_instructions: r.specialInstructions ?? null,
    status:               r.status ?? 'ACTIVE',
    prescribed_date:      r.prescribedDate ?? null,
    expiry_date:          r.expiryDate ?? null,
  };
}

function formatDate(d: string | null) {
  if (!d) return '—';
  const dt = new Date(d);
  if (isNaN(dt.getTime())) return '—';
  return dt.toLocaleDateString('en-ZW', { day: 'numeric', month: 'short', year: 'numeric' });
}

function statusColor(s: string) {
  if (s === 'ACTIVE')    return { bg: '#D1FAE5', text: GREEN };
  if (s === 'COMPLETED') return { bg: '#F3F4F6', text: GRAY };
  if (s === 'SUSPENDED') return { bg: '#FEF3C7', text: ORANGE };
  return { bg: '#FEE2E2', text: RED };
}

function routeIcon(route: string | null): React.ComponentProps<typeof Ionicons>['name'] {
  if (route === 'ORAL')      return 'tablet-portrait-outline';
  if (route === 'TOPICAL')   return 'hand-right-outline';
  if (route === 'INJECTION') return 'medical-outline';
  return 'medical-outline';
}

function MedCard({ rx, dep }: { rx: Prescription; dep?: CaregiverDependent }) {
  const { bg, text } = statusColor(rx.status);
  const patientName = dep?.patientName ?? 'Unknown Patient';
  const [expanded, setExpanded] = useState(false);

  return (
    <View style={[styles.card, rx.status === 'ACTIVE' && styles.cardActive]}>
      <View style={styles.cardHead}>
        <View style={[styles.medIcon, { backgroundColor: '#F5F3FF' }]}>
          <Ionicons name={routeIcon(rx.route)} size={20} color={PURPLE} />
        </View>
        <View style={{ flex: 1 }}>
          <Text style={styles.medName}>{rx.medication_name}</Text>
          {rx.generic_name ? <Text style={styles.medGeneric}>{rx.generic_name}</Text> : null}
          <Text style={styles.medPatient}>{patientName}</Text>
        </View>
        <View style={[styles.badge, { backgroundColor: bg }]}>
          <Text style={[styles.badgeText, { color: text }]}>{rx.status}</Text>
        </View>
      </View>

      <View style={styles.detailGrid}>
        <View style={styles.detailItem}>
          <Text style={styles.detailLabel}>Dosage</Text>
          <Text style={styles.detailValue}>{rx.dosage ?? '—'}</Text>
        </View>
        <View style={styles.detailItem}>
          <Text style={styles.detailLabel}>Frequency</Text>
          <Text style={styles.detailValue}>{rx.frequency ?? '—'}</Text>
        </View>
        <View style={styles.detailItem}>
          <Text style={styles.detailLabel}>Route</Text>
          <Text style={styles.detailValue}>{rx.route ?? '—'}</Text>
        </View>
        <View style={styles.detailItem}>
          <Text style={styles.detailLabel}>Quantity</Text>
          <Text style={styles.detailValue}>{rx.quantity ?? '—'}</Text>
        </View>
        <View style={styles.detailItem}>
          <Text style={styles.detailLabel}>Refills</Text>
          <Text style={styles.detailValue}>{rx.refills_allowed ?? '—'}</Text>
        </View>
        <View style={styles.detailItem}>
          <Text style={styles.detailLabel}>Prescribed</Text>
          <Text style={styles.detailValue}>{formatDate(rx.prescribed_date)}</Text>
        </View>
      </View>

      {!!rx.special_instructions && (
        <TouchableOpacity
          onPress={() => setExpanded(e => !e)}
          activeOpacity={0.7}
          accessibilityRole="button"
          accessibilityLabel="Special instructions"
          accessibilityState={{ expanded }}
        >
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
  const { dependents, loading: depsLoading, error: depsError, refetch: refetchDeps } = useCaregiverDependents();
  const [prescriptions, setPrescriptions]   = useState<Prescription[]>([]);
  const [loading, setLoading]               = useState(false);
  const [error,   setError]                 = useState<string | null>(null);
  const [denied,  setDenied]                = useState<string[]>([]);
  const [filter,  setFilter]                = useState<Filter>('all');
  const [tick,    setTick]                  = useState(0);

  const depMap = new Map(dependents.map(d => [d.patientSystemId, d]));
  const depKey = dependents.map(d => `${d.patientSystemId}:${d.canViewMedications ? 1 : 0}`).join(',');

  useEffect(() => {
    if (dependents.length === 0) { setPrescriptions([]); setDenied([]); return; }
    let cancelled = false;
    const allowed  = dependents.filter(d => d.canViewMedications);
    const noAccess = dependents.filter(d => !d.canViewMedications).map(d => d.patientName ?? 'a linked patient');
    setLoading(true);
    setError(null);
    Promise.allSettled(
      allowed.map(d =>
        api.get<any[]>(`/api/prescriptions/patient/${d.patientSystemId}`)
          .then(r => (Array.isArray(r.data) ? r.data : []).map(mapPrescription))
      )
    )
      .then(settled => {
        if (cancelled) return;
        const all: Prescription[] = [];
        const forbidden = [...noAccess];
        let failed = 0;
        settled.forEach((res, i) => {
          if (res.status === 'fulfilled') all.push(...res.value);
          else if (isForbidden(res.reason)) forbidden.push(allowed[i].patientName ?? 'a linked patient');
          else failed++;
        });
        setPrescriptions(all);
        setDenied(forbidden);
        setError(failed > 0 ? 'Some prescriptions could not be loaded.' : null);
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [depKey, tick]);

  const filters: { key: Filter; label: string }[] = [
    { key: 'all',       label: 'All' },
    { key: 'ACTIVE',    label: 'Active' },
    { key: 'COMPLETED', label: 'Completed' },
  ];

  const visible   = filter === 'all' ? prescriptions : prescriptions.filter(r => r.status === filter);
  const active    = prescriptions.filter(r => r.status === 'ACTIVE').length;
  const total     = prescriptions.length;
  const allDenied = dependents.length > 0 && denied.length === dependents.length;

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>
      <View style={styles.pageHeader}>
        <View>
          <Text style={styles.pageTitle} accessibilityRole="header">Medications</Text>
          <Text style={styles.pageSub}>Prescriptions for your linked patients</Text>
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
              { label: 'Total',  value: total,  bg: '#F5F3FF', color: PURPLE },
              { label: 'Active', value: active, bg: '#D1FAE5', color: GREEN  },
            ].map(s => (
              <View key={s.label} style={[styles.statPill, { backgroundColor: s.bg }]}>
                <Text style={[styles.statVal, { color: s.color }]}>{loading ? '…' : s.value}</Text>
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
                You don't have access to medications for {denied.join(', ')}.
              </Text>
            </View>
          )}

          {loading ? (
            <LoadingState label="Loading prescriptions" />
          ) : error && prescriptions.length === 0 ? (
            <ErrorState message={error} onRetry={() => setTick(t => t + 1)} />
          ) : (
            <>
              {error && <ErrorState compact message={error} onRetry={() => setTick(t => t + 1)} />}
              {visible.length === 0 ? (
                <EmptyState icon="medical-outline" title="No prescriptions found" />
              ) : (
                visible.map(rx => <MedCard key={`${rx.patientSystemId}-${rx.prescription_id}`} rx={rx} dep={depMap.get(rx.patientSystemId)} />)
              )}
            </>
          )}
        </>
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

  noticeBanner: { flexDirection: 'row', alignItems: 'center', gap: 8, backgroundColor: '#FFFBEB', borderRadius: 10, borderWidth: 1, borderColor: '#FDE68A', padding: 12 },
  noticeText:   { fontSize: 12, color: TEXT, flex: 1 },
});
