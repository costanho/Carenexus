import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { usePrescriptions, type Prescription } from '@/hooks/use-prescriptions';

const TEAL   = '#0D9488';
const INDIGO = '#4F46E5';
const GREEN  = '#10B981';
const AMBER  = '#F59E0B';
const RED    = '#EF4444';
const PURPLE = '#8B5CF6';
const BLUE   = '#3B82F6';
const GRAY   = '#6B7280';
const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const CARD   = '#FFFFFF';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type StatusType   = Prescription['status'];
type RouteType    = Prescription['route'];
type FilterKey    = 'ALL' | StatusType;

const STATUS_CONFIG: Record<StatusType, { label: string; color: string; bg: string; icon: IoniconsName }> = {
  ACTIVE:    { label: 'Active',     color: GREEN,  bg: '#ECFDF5', icon: 'checkmark-circle-outline' },
  COMPLETED: { label: 'Completed',  color: TEAL,   bg: '#E6F4F1', icon: 'checkmark-done-outline'   },
  CANCELLED: { label: 'Cancelled',  color: RED,    bg: '#FEF2F2', icon: 'close-circle-outline'     },
  EXPIRED:   { label: 'Expired',    color: AMBER,  bg: '#FFFBEB', icon: 'time-outline'             },
  ON_HOLD:   { label: 'On Hold',    color: PURPLE, bg: '#F5F3FF', icon: 'pause-circle-outline'     },
};

const ROUTE_CONFIG: Record<RouteType, { label: string; color: string; bg: string; icon: IoniconsName }> = {
  ORAL:       { label: 'Oral',        color: BLUE,   bg: '#EFF6FF', icon: 'water-outline'           },
  TOPICAL:    { label: 'Topical',     color: GREEN,  bg: '#ECFDF5', icon: 'hand-left-outline'       },
  INJECTION:  { label: 'Injection',   color: RED,    bg: '#FEF2F2', icon: 'fitness-outline'         },
  INHALED:    { label: 'Inhaled',     color: INDIGO, bg: '#EEF2FF', icon: 'cloud-outline'           },
  SUBLINGUAL: { label: 'Sublingual',  color: PURPLE, bg: '#F5F3FF', icon: 'medical-outline'         },
  RECTAL:     { label: 'Rectal',      color: AMBER,  bg: '#FFFBEB', icon: 'bandage-outline'         },
  IV:         { label: 'IV',          color: RED,    bg: '#FEF2F2', icon: 'pulse-outline'           },
};

const FILTERS: { key: FilterKey; label: string }[] = [
  { key: 'ALL',       label: 'All'       },
  { key: 'ACTIVE',    label: 'Active'    },
  { key: 'COMPLETED', label: 'Completed' },
  { key: 'EXPIRED',   label: 'Expired'  },
  { key: 'ON_HOLD',   label: 'On Hold'  },
  { key: 'CANCELLED', label: 'Cancelled' },
];

function formatDate(iso: string | null) {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

function PrescriptionCard({ item }: { item: Prescription }) {
  const [expanded, setExpanded] = useState(false);
  const statusCfg = STATUS_CONFIG[item.status] ?? STATUS_CONFIG.ACTIVE;
  const routeCfg  = ROUTE_CONFIG[item.route]   ?? ROUTE_CONFIG.ORAL;

  return (
    <View style={[styles.card, { borderLeftColor: statusCfg.color }]}>
      {/* Header row */}
      <View style={styles.cardHeader}>
        <View style={[styles.medIconSquare, { backgroundColor: statusCfg.bg }]}>
          <Ionicons name="medkit-outline" size={20} color={statusCfg.color} />
        </View>
        <View style={styles.cardHeaderText}>
          <Text style={styles.cardTitle}>{item.medication_name}</Text>
          {item.generic_name && (
            <Text style={styles.cardGeneric}>{item.generic_name}</Text>
          )}
          <Text style={styles.cardDoctorName}>{item.doctor_name}</Text>
        </View>
        <View style={[styles.statusBadge, { backgroundColor: statusCfg.bg }]}>
          <Ionicons name={statusCfg.icon} size={12} color={statusCfg.color} />
          <Text style={[styles.statusBadgeText, { color: statusCfg.color }]}>{statusCfg.label}</Text>
        </View>
      </View>

      {/* Dosage / frequency / route row */}
      <View style={styles.pillRow}>
        <View style={[styles.infoPill, { backgroundColor: BG, borderColor: BORDER }]}>
          <Ionicons name="flask-outline" size={12} color={INDIGO} />
          <Text style={[styles.infoPillText, { color: INDIGO }]}>{item.dosage}</Text>
        </View>
        <View style={[styles.infoPill, { backgroundColor: BG, borderColor: BORDER }]}>
          <Ionicons name="time-outline" size={12} color={TEAL} />
          <Text style={[styles.infoPillText, { color: TEAL }]}>{item.frequency}</Text>
        </View>
        <View style={[styles.infoPill, { backgroundColor: routeCfg.bg, borderColor: routeCfg.color + '40' }]}>
          <Ionicons name={routeCfg.icon} size={12} color={routeCfg.color} />
          <Text style={[styles.infoPillText, { color: routeCfg.color }]}>{routeCfg.label}</Text>
        </View>
      </View>

      {/* Meta row: dates + refills + doctor spec */}
      <View style={styles.metaRow}>
        <View style={styles.metaItem}>
          <Ionicons name="calendar-outline" size={13} color={GRAY} />
          <Text style={styles.metaLabel}>Prescribed</Text>
          <Text style={styles.metaValue}>{formatDate(item.prescribed_date)}</Text>
        </View>
        {item.expiry_date && (
          <View style={styles.metaItem}>
            <Ionicons name="hourglass-outline" size={13} color={AMBER} />
            <Text style={styles.metaLabel}>Expires</Text>
            <Text style={[styles.metaValue, { color: AMBER }]}>{formatDate(item.expiry_date)}</Text>
          </View>
        )}
        <View style={styles.metaItem}>
          <Ionicons name="refresh-circle-outline" size={13} color={BLUE} />
          <Text style={styles.metaLabel}>Refills</Text>
          <Text style={[styles.metaValue, { color: BLUE }]}>{item.refills_allowed}</Text>
        </View>
        {item.doctor_specialization && (
          <View style={styles.metaItem}>
            <Ionicons name="person-outline" size={13} color={GRAY} />
            <Text style={styles.metaLabel}>Specialty</Text>
            <Text style={styles.metaValue}>{item.doctor_specialization}</Text>
          </View>
        )}
      </View>

      {/* Quantity */}
      {item.quantity && (
        <View style={styles.quantityRow}>
          <Ionicons name="cube-outline" size={13} color={GRAY} />
          <Text style={styles.quantityText}>Quantity: {item.quantity}</Text>
        </View>
      )}

      {/* Special instructions (expandable) */}
      {item.special_instructions && (
        <>
          <View style={styles.instructionsBox}>
            <Ionicons name="information-circle-outline" size={14} color={TEAL} />
            <Text style={styles.instructionsText} numberOfLines={expanded ? undefined : 2}>
              {item.special_instructions}
            </Text>
          </View>
        </>
      )}

      {/* Expand toggle */}
      <TouchableOpacity style={styles.expandToggle} onPress={() => setExpanded(e => !e)} activeOpacity={0.7}>
        <Text style={styles.expandToggleText}>{expanded ? 'Hide ▲' : 'Show details ▼'}</Text>
      </TouchableOpacity>
    </View>
  );
}

function StatCard({ value, label, color, icon }: { value: number; label: string; color: string; icon: IoniconsName }) {
  return (
    <View style={[styles.statCard, { borderLeftColor: color }]}>
      <View style={[styles.statIconBg, { backgroundColor: color + '18' }]}>
        <Ionicons name={icon} size={18} color={color} />
      </View>
      <View>
        <Text style={[styles.statValue, { color }]}>{value}</Text>
        <Text style={styles.statLabel}>{label}</Text>
      </View>
    </View>
  );
}

export function PatientWebPrescription({ onBack }: { onBack?: () => void }) {
  const { prescriptions, loading, error, refetch } = usePrescriptions();
  const [filter, setFilter] = useState<FilterKey>('ALL');

  const filtered  = filter === 'ALL' ? prescriptions : prescriptions.filter(p => p.status === filter);
  const total     = prescriptions.length;
  const active    = prescriptions.filter(p => p.status === 'ACTIVE').length;
  const topical   = prescriptions.filter(p => p.route === 'TOPICAL').length;
  const completed = prescriptions.filter(p => p.status === 'COMPLETED').length;

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
      {/* Header bar */}
      <View style={styles.pageHeader}>
        <TouchableOpacity style={styles.backBtn} onPress={onBack} activeOpacity={0.7}>
          <Ionicons name="arrow-back-outline" size={16} color={TEAL} />
          <Text style={styles.backBtnText}>Back</Text>
        </TouchableOpacity>
        <View style={styles.pageHeaderCenter}>
          <Text style={styles.pageTitle}>Prescriptions</Text>
          <Text style={styles.pageSub}>Your medication history</Text>
        </View>
        <TouchableOpacity style={styles.refreshBtn} onPress={refetch} activeOpacity={0.7}>
          <Ionicons name="refresh-outline" size={18} color={TEAL} />
        </TouchableOpacity>
      </View>

      {/* Stats row */}
      <View style={styles.statsRow}>
        <StatCard value={total}     label="Total"     color={INDIGO} icon="medkit-outline"              />
        <StatCard value={active}    label="Active"    color={GREEN}  icon="checkmark-circle-outline"    />
        <StatCard value={topical}   label="Topical"   color={TEAL}   icon="hand-left-outline"           />
        <StatCard value={completed} label="Completed" color={BLUE}   icon="checkmark-done-outline"      />
      </View>

      {/* Filter pills */}
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.filterScroll} contentContainerStyle={styles.filterRow}>
        {FILTERS.map(f => {
          const on    = filter === f.key;
          const count = f.key === 'ALL' ? total : prescriptions.filter(p => p.status === f.key).length;
          return (
            <TouchableOpacity
              key={f.key}
              style={[styles.filterTab, on && styles.filterTabActive]}
              onPress={() => setFilter(f.key)}
              activeOpacity={0.7}
            >
              <Text style={[styles.filterTabText, on && styles.filterTabTextActive]}>{f.label}</Text>
              <View style={[styles.filterCount, on ? styles.filterCountActive : styles.filterCountInactive]}>
                <Text style={[styles.filterCountText, on ? styles.filterCountTextActive : styles.filterCountTextInactive]}>
                  {count}
                </Text>
              </View>
            </TouchableOpacity>
          );
        })}
      </ScrollView>

      {/* Content */}
      {loading ? (
        <View style={styles.centerState}>
          <Ionicons name="hourglass-outline" size={40} color={BORDER} />
          <Text style={styles.centerStateText}>Loading prescriptions…</Text>
        </View>
      ) : error ? (
        <View style={styles.centerState}>
          <Ionicons name="alert-circle-outline" size={40} color={RED} />
          <Text style={[styles.centerStateText, { color: RED }]}>{error}</Text>
          <TouchableOpacity style={styles.retryBtn} onPress={refetch} activeOpacity={0.8}>
            <Text style={styles.retryBtnText}>Try Again</Text>
          </TouchableOpacity>
        </View>
      ) : filtered.length === 0 ? (
        <View style={styles.centerState}>
          <Ionicons name="medkit-outline" size={48} color={BORDER} />
          <Text style={styles.centerStateText}>
            {filter === 'ALL' ? 'No prescriptions found.' : `No ${FILTERS.find(f => f.key === filter)?.label.toLowerCase()} prescriptions.`}
          </Text>
        </View>
      ) : (
        <View style={styles.cardList}>
          {filtered.map(item => (
            <PrescriptionCard key={item.prescription_id} item={item} />
          ))}
        </View>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen:        { flex: 1, backgroundColor: BG },
  scrollContent: { padding: 32, maxWidth: 760, alignSelf: 'center', width: '100%', paddingBottom: 48 },

  pageHeader:       { flexDirection: 'row', alignItems: 'center', marginBottom: 24, gap: 12 },
  backBtn:          { flexDirection: 'row', alignItems: 'center', gap: 6, paddingHorizontal: 14, paddingVertical: 8, borderRadius: 10, borderWidth: 1.5, borderColor: TEAL, backgroundColor: '#E6F4F1' },
  backBtnText:      { fontSize: 13, fontWeight: '600', color: TEAL },
  pageHeaderCenter: { flex: 1 },
  pageTitle:        { fontSize: 22, fontWeight: '800', color: TEXT },
  pageSub:          { fontSize: 13, color: GRAY, marginTop: 3 },
  refreshBtn:       { width: 36, height: 36, borderRadius: 10, borderWidth: 1.5, borderColor: TEAL, backgroundColor: '#E6F4F1', alignItems: 'center', justifyContent: 'center' },

  statsRow:   { flexDirection: 'row', gap: 12, marginBottom: 24 },
  statCard:   { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 12, backgroundColor: CARD, borderRadius: 14, padding: 16, borderLeftWidth: 4, shadowColor: '#000', shadowOpacity: 0.04, shadowRadius: 8, elevation: 2 } as any,
  statIconBg: { width: 36, height: 36, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  statValue:  { fontSize: 22, fontWeight: '800' },
  statLabel:  { fontSize: 11, color: GRAY, fontWeight: '500', marginTop: 1 },

  filterScroll:            { marginBottom: 20 },
  filterRow:               { flexDirection: 'row', gap: 8, paddingBottom: 4 },
  filterTab:               { flexDirection: 'row', alignItems: 'center', gap: 6, paddingHorizontal: 16, paddingVertical: 8, borderRadius: 20, borderWidth: 1.5, borderColor: BORDER, backgroundColor: CARD },
  filterTabActive:         { borderColor: TEAL, backgroundColor: '#E6F4F1' },
  filterTabText:           { fontSize: 13, fontWeight: '500', color: GRAY },
  filterTabTextActive:     { color: TEAL, fontWeight: '700' },
  filterCount:             { borderRadius: 10, paddingHorizontal: 6, paddingVertical: 1 },
  filterCountActive:       { backgroundColor: TEAL },
  filterCountInactive:     { backgroundColor: BORDER },
  filterCountText:         { fontSize: 10, fontWeight: '700' },
  filterCountTextActive:   { color: WHITE },
  filterCountTextInactive: { color: GRAY },

  cardList: { gap: 14 },

  card: { backgroundColor: CARD, borderRadius: 12, padding: 18, borderWidth: 1, borderColor: BORDER, borderLeftWidth: 4, shadowColor: '#000', shadowOpacity: 0.04, shadowRadius: 10, elevation: 2 } as any,

  cardHeader:     { flexDirection: 'row', alignItems: 'flex-start', gap: 12, marginBottom: 12 },
  medIconSquare:  { width: 40, height: 40, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  cardHeaderText: { flex: 1 },
  cardTitle:      { fontSize: 15, fontWeight: '700', color: TEXT },
  cardGeneric:    { fontSize: 12, color: GRAY, marginTop: 2, fontStyle: 'italic' },
  cardDoctorName: { fontSize: 12, color: GRAY, marginTop: 2 },
  statusBadge:    { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 10, paddingVertical: 4, borderRadius: 20 },
  statusBadgeText:{ fontSize: 11, fontWeight: '700' },

  pillRow:      { flexDirection: 'row', gap: 8, marginBottom: 12, flexWrap: 'wrap' },
  infoPill:     { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 10, paddingVertical: 5, borderRadius: 20, borderWidth: 1 },
  infoPillText: { fontSize: 11, fontWeight: '600' },

  metaRow:   { flexDirection: 'row', gap: 12, marginBottom: 10, flexWrap: 'wrap' },
  metaItem:  { flexDirection: 'row', alignItems: 'center', gap: 4 },
  metaLabel: { fontSize: 11, color: GRAY },
  metaValue: { fontSize: 12, fontWeight: '600', color: TEXT },

  quantityRow:  { flexDirection: 'row', alignItems: 'center', gap: 6, marginBottom: 10 },
  quantityText: { fontSize: 12, color: GRAY },

  instructionsBox:  { flexDirection: 'row', alignItems: 'flex-start', gap: 8, backgroundColor: '#E6F4F1', borderRadius: 10, padding: 12, marginBottom: 10 },
  instructionsText: { flex: 1, fontSize: 13, color: TEXT, lineHeight: 19 },

  expandToggle:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', paddingTop: 12, borderTopWidth: 1, borderTopColor: BORDER, marginTop: 4 },
  expandToggleText: { fontSize: 12, fontWeight: '600', color: TEAL },

  centerState:     { alignItems: 'center', justifyContent: 'center', paddingVertical: 64, gap: 12 },
  centerStateText: { fontSize: 14, color: GRAY, textAlign: 'center' },
  retryBtn:        { backgroundColor: TEAL, borderRadius: 10, paddingHorizontal: 20, paddingVertical: 10, marginTop: 4 },
  retryBtnText:    { fontSize: 13, fontWeight: '700', color: WHITE },
});

export default PatientWebPrescription;
