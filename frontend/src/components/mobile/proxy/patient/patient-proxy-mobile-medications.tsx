import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { api } from '@/lib/auth/auth.interceptor';
import { useCaregiverDependents, type CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import { daysUntilDateOnly, formatDateOnly } from '@/lib/ui/date-only';
import { EmptyState, ErrorState, LoadingState } from '@/components/web/proxy/patient/proxy-states';
import {
  ALL, BG, BORDER, GRAY, GREEN, ORANGE, PURPLE, PURPLE_BG, RED, TEXT, WHITE,
  canView, capitalize, depFirstName, depName, DependentPicker, FilterTabs, joinNames, NotSharedBanner, NotSharedState,
  ScreenHeader, StatRow, useDoctorNames, usePerDependentRecords, usePickedDependent, useScale, type IoniconsName,
} from './patient-proxy-mobile-shared';

interface Prescription {
  prescriptionId:      number;
  patientSystemId:     string;
  doctorSystemId:      string | null;
  medicationName:      string;
  genericName:         string | null;
  dosage:              string | null;
  frequency:           string | null;
  route:               string | null;
  quantity:            string | null;
  refillsAllowed:      number | null;
  specialInstructions: string | null;
  status:              string;
  prescribedDate:      string | null;
  expiryDate:          string | null;
}

function str(v: unknown): string | null {
  return typeof v === 'string' && v.trim() ? v.trim() : typeof v === 'number' ? String(v) : null;
}

function mapPrescription(r: any, patientSystemId: string): Prescription {
  return {
    prescriptionId:      r?.prescriptionId ?? 0,
    patientSystemId:     r?.patientSystemId ?? patientSystemId,
    doctorSystemId:      str(r?.doctorSystemId),
    medicationName:      str(r?.medicationName) ?? 'Medication',
    genericName:         str(r?.genericName),
    dosage:              str(r?.dosage),
    frequency:           str(r?.frequency),
    route:               str(r?.route),
    quantity:            str(r?.quantity),
    refillsAllowed:      typeof r?.refillsAllowed === 'number' ? r.refillsAllowed : null,
    specialInstructions: str(r?.specialInstructions),
    status:              str(r?.status) ?? 'ACTIVE',
    prescribedDate:      str(r?.prescribedDate),
    expiryDate:          str(r?.expiryDate),
  };
}

// Module scope so usePerDependentRecords gets a stable loader
function loadPrescriptions(dep: CaregiverDependent): Promise<Prescription[]> {
  return api.get<any[]>(`/api/prescriptions/patient/${dep.patientSystemId}`)
    .then(r => (Array.isArray(r.data) ? r.data : []).map(x => mapPrescription(x, dep.patientSystemId)));
}

function statusColors(s: string) {
  if (s === 'ACTIVE')    return { bg: '#D1FAE5', text: GREEN };
  if (s === 'COMPLETED') return { bg: '#F3F4F6', text: GRAY };
  if (s === 'SUSPENDED') return { bg: '#FEF3C7', text: ORANGE };
  return { bg: '#FEE2E2', text: RED };
}
function routeIcon(route: string | null): IoniconsName {
  if (route === 'ORAL')    return 'tablet-portrait-outline';
  if (route === 'TOPICAL') return 'hand-right-outline';
  return 'medical-outline';
}
/** A short note when an active prescription ends soon or has passed its end date (date-only, no time-zone shift). */
function endNote(rx: Prescription): string | null {
  if (rx.status !== 'ACTIVE') return null;
  const days = daysUntilDateOnly(rx.expiryDate);
  if (days === null || days > 7) return null;
  if (days < 0)   return 'Past its end date';
  if (days === 0) return 'Ends today';
  return days === 1 ? 'Ends tomorrow' : `Ends in ${days} days`;
}

type Filter = 'all' | 'ACTIVE' | 'COMPLETED';
const FILTERS: { key: Filter; label: string }[] = [
  { key: 'all',       label: 'All' },
  { key: 'ACTIVE',    label: 'Active' },
  { key: 'COMPLETED', label: 'Completed' },
];

function Field({ label, value }: { label: string; value: string }) {
  const { s } = useScale();
  return (
    <View style={{ width: '50%', paddingRight: s(8), marginBottom: s(8) }}>
      <Text style={{ fontSize: s(11), color: GRAY, marginBottom: s(1) }} numberOfLines={1}>{label}</Text>
      <Text style={{ fontSize: s(13), fontWeight: '600', color: TEXT }} numberOfLines={2}>{value}</Text>
    </View>
  );
}

function MedCard({ rx, person, showPerson, doctor }: { rx: Prescription; person: string; showPerson: boolean; doctor: string | null }) {
  const { s } = useScale();
  const [expanded, setExpanded] = useState(false);
  const sc   = statusColors(rx.status);
  const note = endNote(rx);

  return (
    <View style={[styles.card, { borderRadius: s(14), padding: s(14), gap: s(10), marginBottom: s(10) }, rx.status === 'ACTIVE' && styles.cardActive]}>
      <View
        style={[styles.cardHead, { gap: s(10) }]}
        accessible
        accessibilityLabel={[rx.medicationName, rx.genericName, showPerson ? person : null, capitalize(rx.status)].filter(Boolean).join(', ')}
      >
        <View style={[styles.medIcon, { width: s(40), height: s(40), borderRadius: s(10) }]}>
          <Ionicons name={routeIcon(rx.route)} size={s(18)} color={PURPLE} importantForAccessibility="no" />
        </View>
        <View style={{ flex: 1, minWidth: 0 }}>
          <Text style={[styles.medName, { fontSize: s(14) }]} numberOfLines={2}>{rx.medicationName}</Text>
          {rx.genericName ? <Text style={{ fontSize: s(12), color: GRAY, marginTop: s(1) }} numberOfLines={1}>{rx.genericName}</Text> : null}
          {showPerson ? <Text style={[styles.person, { fontSize: s(12), marginTop: s(2) }]} numberOfLines={1}>{person}</Text> : null}
        </View>
        <View style={{ backgroundColor: sc.bg, borderRadius: s(12), paddingHorizontal: s(8), paddingVertical: s(3), flexShrink: 0 }}>
          <Text style={{ fontSize: s(11), fontWeight: '600', color: sc.text }} numberOfLines={1}>{capitalize(rx.status)}</Text>
        </View>
      </View>

      <View style={styles.grid}>
        <Field label="Dosage"    value={rx.dosage ?? '—'} />
        <Field label="Frequency" value={rx.frequency ?? '—'} />
        {rx.route ? <Field label="Route" value={capitalize(rx.route)} /> : null}
        {rx.quantity ? <Field label="Quantity" value={rx.quantity} /> : null}
        {rx.refillsAllowed !== null ? <Field label="Refills" value={String(rx.refillsAllowed)} /> : null}
        <Field label="Prescribed" value={formatDateOnly(rx.prescribedDate)} />
        {rx.expiryDate ? <Field label="Ends" value={formatDateOnly(rx.expiryDate)} /> : null}
      </View>

      {doctor ? (
        <View style={[styles.row, { gap: s(6) }]}>
          <Ionicons name="person-outline" size={s(13)} color={GRAY} importantForAccessibility="no" />
          <Text style={{ fontSize: s(12), color: GRAY, flexShrink: 1 }} numberOfLines={1}>Prescribed by {doctor}</Text>
        </View>
      ) : null}

      {note ? (
        <View style={[styles.row, { gap: s(6) }]}>
          <Ionicons name="alert-circle-outline" size={s(13)} color={ORANGE} importantForAccessibility="no" />
          <Text style={{ fontSize: s(12), color: ORANGE, fontWeight: '600', flexShrink: 1 }}>{note}</Text>
        </View>
      ) : null}

      {rx.specialInstructions ? (
        <View>
          <TouchableOpacity
            style={[styles.row, { gap: s(6), paddingVertical: s(4) }]}
            onPress={() => setExpanded(e => !e)}
            activeOpacity={0.7}
            accessibilityRole="button"
            accessibilityLabel={`Special instructions for ${rx.medicationName}`}
            accessibilityState={{ expanded }}
          >
            <Ionicons name="information-circle-outline" size={s(15)} color={PURPLE} importantForAccessibility="no" />
            <Text style={{ fontSize: s(13), color: PURPLE, fontWeight: '600', flex: 1 }}>Special instructions</Text>
            <Ionicons name={expanded ? 'chevron-up' : 'chevron-down'} size={s(14)} color={PURPLE} importantForAccessibility="no" />
          </TouchableOpacity>
          {expanded && (
            <View style={[styles.instructions, { borderRadius: s(8), padding: s(10), marginTop: s(6) }]}>
              <Text style={{ fontSize: s(12), color: TEXT, lineHeight: s(18) }}>{rx.specialInstructions}</Text>
            </View>
          )}
        </View>
      ) : null}
    </View>
  );
}

/** Medications: prescriptions for each loved one who shares them (GET /api/prescriptions/patient/{id}). */
export default function PatientProxyMobileMedications({ initialPatientId, onBack }: {
  initialPatientId?: string;
  onBack: () => void;
}) {
  const { s } = useScale();
  const { dependents, loading: depsLoading, error: depsError, refetch: refetchDeps } = useCaregiverDependents();
  const { items, loading, error, denied, refetch } =
    usePerDependentRecords(dependents, 'canViewMedications', loadPrescriptions, 'Some medications could not be loaded.');
  const doctorName = useDoctorNames();
  const { value: who, setValue: setWho, selected } = usePickedDependent(dependents, initialPatientId, 'all');
  const [filter, setFilter] = useState<Filter>('all');

  const isShared = (d: CaregiverDependent) => canView(d, 'canViewMedications') && !denied.includes(d.patientSystemId);
  const notShared  = dependents.filter(d => !isShared(d));
  const noneShared = dependents.length > 0 && notShared.length === dependents.length;

  const scoped = useMemo(() => (who === ALL ? items : items.filter(r => r.patientSystemId === who)), [items, who]);
  const visible = useMemo(() => {
    const list = filter === 'all' ? [...scoped] : scoped.filter(r => r.status === filter);
    // Active first, then newest prescription first ("YYYY-MM-DD" sorts as text)
    return list.sort((a, b) =>
      (a.status === 'ACTIVE' ? 0 : 1) - (b.status === 'ACTIVE' ? 0 : 1)
      || (b.prescribedDate ?? '').localeCompare(a.prescribedDate ?? ''));
  }, [scoped, filter]);
  const nameFor = (id: string) => depName(dependents.find(d => d.patientSystemId === id));

  function renderBody() {
    if (depsLoading && dependents.length === 0) return <LoadingState label="Loading loved ones" />;
    if (depsError && dependents.length === 0)   return <ErrorState message={depsError} onRetry={refetchDeps} />;
    if (dependents.length === 0) return <EmptyState icon="people-outline" title="No loved ones linked yet" />;
    if (selected && !isShared(selected)) {
      return (
        <NotSharedState
          title={`${depFirstName(selected)}'s medications are not shared with you`}
          sub={`Ask ${depFirstName(selected)} to update the permissions on your caregiver access.`}
        />
      );
    }
    if (!selected && noneShared) {
      return <NotSharedState title="Medications are not shared with you" sub="Ask your loved ones to update the permissions on your caregiver access." />;
    }
    const busy = loading && items.length === 0;
    return (
      <>
        <StatRow items={[
          { label: 'Active', value: busy ? '…' : String(scoped.filter(r => r.status === 'ACTIVE').length), bg: '#D1FAE5', color: GREEN },
          { label: 'Total',  value: busy ? '…' : String(scoped.length),                                    bg: PURPLE_BG, color: PURPLE },
        ]} />
        <FilterTabs label="Medication filter" options={FILTERS} value={filter} onChange={setFilter} />

        {!selected && notShared.length > 0 && (
          <NotSharedBanner text={`Medications for ${joinNames(notShared.map(depName))} are not shared with you.`} />
        )}

        {busy ? (
          <LoadingState label="Loading medications" />
        ) : error && items.length === 0 ? (
          <ErrorState message={error} onRetry={refetch} />
        ) : (
          <>
            {error ? <ErrorState compact message={error} onRetry={refetch} /> : null}
            {visible.length === 0 ? (
              <EmptyState
                icon="medical-outline"
                title={filter === 'ACTIVE' ? 'No active medications' : filter === 'COMPLETED' ? 'No completed medications' : 'No medications recorded'}
              />
            ) : (
              visible.map(rx => (
                <MedCard
                  key={`${rx.patientSystemId}-${rx.prescriptionId}`}
                  rx={rx}
                  person={nameFor(rx.patientSystemId)}
                  showPerson={!selected}
                  doctor={doctorName(rx.doctorSystemId)}
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
        title="Medications"
        sub={selected ? `Prescriptions for ${depName(selected)}` : 'Prescriptions for your loved ones'}
        onBack={onBack}
      />
      <DependentPicker dependents={dependents} value={who} onChange={setWho} />
      {renderBody()}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll: { flex: 1, backgroundColor: BG },

  card:       { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  cardActive: { borderLeftWidth: 3, borderLeftColor: GREEN },
  cardHead:   { flexDirection: 'row', alignItems: 'flex-start' },
  medIcon:    { backgroundColor: PURPLE_BG, alignItems: 'center', justifyContent: 'center' },
  medName:    { fontWeight: '700', color: TEXT },
  person:     { color: PURPLE, fontWeight: '500' },

  grid:         { flexDirection: 'row', flexWrap: 'wrap', marginBottom: -8 },
  row:          { flexDirection: 'row', alignItems: 'center' },
  instructions: { backgroundColor: PURPLE_BG },
});
