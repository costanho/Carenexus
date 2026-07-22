import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import {
  KeyboardAvoidingView, Platform, ScrollView,
  StyleSheet, Text, TouchableOpacity, useWindowDimensions, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
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

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type StatusType   = Prescription['status'];
type RouteType    = Prescription['route'];
type FilterKey    = 'ALL' | StatusType;

const STATUS_CONFIG: Record<StatusType, { label: string; color: string; bg: string; icon: IoniconsName }> = {
  ACTIVE:    { label: 'Active',    color: GREEN,  bg: '#ECFDF5', icon: 'checkmark-circle-outline' },
  COMPLETED: { label: 'Completed', color: TEAL,   bg: '#E6F4F1', icon: 'checkmark-done-outline'   },
  CANCELLED: { label: 'Cancelled', color: RED,    bg: '#FEF2F2', icon: 'close-circle-outline'     },
  EXPIRED:   { label: 'Expired',   color: AMBER,  bg: '#FFFBEB', icon: 'time-outline'             },
  ON_HOLD:   { label: 'On Hold',   color: PURPLE, bg: '#F5F3FF', icon: 'pause-circle-outline'     },
};

const ROUTE_CONFIG: Record<RouteType, { label: string; color: string; icon: IoniconsName }> = {
  ORAL:       { label: 'Oral',       color: BLUE,   icon: 'water-outline'   },
  TOPICAL:    { label: 'Topical',    color: GREEN,  icon: 'hand-left-outline' },
  INJECTION:  { label: 'Injection',  color: RED,    icon: 'fitness-outline' },
  INHALED:    { label: 'Inhaled',    color: INDIGO, icon: 'cloud-outline'   },
  SUBLINGUAL: { label: 'Sublingual', color: PURPLE, icon: 'medical-outline' },
  RECTAL:     { label: 'Rectal',     color: AMBER,  icon: 'bandage-outline' },
  IV:         { label: 'IV',         color: RED,    icon: 'pulse-outline'   },
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

function PrescriptionCard({ item, s }: { item: Prescription; s: (n: number) => number }) {
  const [expanded, setExpanded] = useState(false);
  const statusCfg = STATUS_CONFIG[item.status] ?? STATUS_CONFIG.ACTIVE;
  const routeCfg  = ROUTE_CONFIG[item.route]   ?? ROUTE_CONFIG.ORAL;

  return (
    <View style={[styles.card, { borderRadius: s(16), marginBottom: s(12), borderLeftColor: statusCfg.color }]}>
      <View style={{ padding: s(14) }}>
        {/* Header */}
        <View style={[styles.cardHeader, { gap: s(10), marginBottom: s(10) }]}>
          <View style={[styles.medIconCircle, { width: s(38), height: s(38), borderRadius: s(19), backgroundColor: statusCfg.bg }]}>
            <Ionicons name="medkit-outline" size={s(18)} color={statusCfg.color} />
          </View>
          <View style={{ flex: 1 }}>
            <Text style={[styles.cardTitle, { fontSize: s(14) }]}>{item.medication_name}</Text>
            {item.generic_name && (
              <Text style={[styles.cardGeneric, { fontSize: s(10) }]}>{item.generic_name}</Text>
            )}
            <Text style={[styles.cardDoctorName, { fontSize: s(11) }]}>{item.doctor_name}</Text>
          </View>
          <View style={[styles.statusBadge, { borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3), backgroundColor: statusCfg.bg, gap: s(3) }]}>
            <Ionicons name={statusCfg.icon} size={s(10)} color={statusCfg.color} />
            <Text style={[styles.statusBadgeText, { fontSize: s(10), color: statusCfg.color }]}>{statusCfg.label}</Text>
          </View>
        </View>

        {/* Dosage / frequency row */}
        <View style={[styles.pillRow, { gap: s(6), marginBottom: s(10), flexWrap: 'wrap' }]}>
          <View style={[styles.infoPill, { borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3), gap: s(4) }]}>
            <Ionicons name="flask-outline" size={s(11)} color={INDIGO} />
            <Text style={[styles.infoPillText, { fontSize: s(10), color: INDIGO }]}>{item.dosage}</Text>
          </View>
          <View style={[styles.infoPill, { borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3), gap: s(4) }]}>
            <Ionicons name="time-outline" size={s(11)} color={TEAL} />
            <Text style={[styles.infoPillText, { fontSize: s(10), color: TEAL }]}>{item.frequency}</Text>
          </View>
          <View style={[styles.routePill, { borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3), gap: s(4) }]}>
            <Ionicons name={routeCfg.icon} size={s(11)} color={routeCfg.color} />
            <Text style={[styles.infoPillText, { fontSize: s(10), color: routeCfg.color }]}>{routeCfg.label}</Text>
          </View>
        </View>

        {/* Meta: prescribed / expires / refills */}
        <View style={[styles.metaRow, { gap: s(10), marginBottom: s(8), flexWrap: 'wrap' }]}>
          <View style={[styles.metaItem, { gap: s(3) }]}>
            <Ionicons name="calendar-outline" size={s(11)} color={GRAY} />
            <Text style={[styles.metaLabel, { fontSize: s(10) }]}>Prescribed</Text>
            <Text style={[styles.metaValue, { fontSize: s(11) }]}>{formatDate(item.prescribed_date)}</Text>
          </View>
          {item.expiry_date && (
            <View style={[styles.metaItem, { gap: s(3) }]}>
              <Ionicons name="hourglass-outline" size={s(11)} color={AMBER} />
              <Text style={[styles.metaLabel, { fontSize: s(10) }]}>Expires</Text>
              <Text style={[styles.metaValue, { fontSize: s(11), color: AMBER }]}>{formatDate(item.expiry_date)}</Text>
            </View>
          )}
          <View style={[styles.metaItem, { gap: s(3) }]}>
            <Ionicons name="refresh-circle-outline" size={s(11)} color={BLUE} />
            <Text style={[styles.metaLabel, { fontSize: s(10) }]}>Refills</Text>
            <Text style={[styles.metaValue, { fontSize: s(11), color: BLUE }]}>{item.refills_allowed}</Text>
          </View>
        </View>

        {/* Quantity */}
        {item.quantity && (
          <View style={[styles.quantityRow, { gap: s(5), marginBottom: s(8) }]}>
            <Ionicons name="cube-outline" size={s(12)} color={GRAY} />
            <Text style={[styles.quantityText, { fontSize: s(11) }]}>Quantity: {item.quantity}</Text>
          </View>
        )}

        {/* Special instructions */}
        {item.special_instructions && (
          <View style={[styles.instructionsBox, { borderRadius: s(10), padding: s(10), gap: s(6), marginBottom: s(8) }]}>
            <Ionicons name="information-circle-outline" size={s(13)} color={TEAL} />
            <Text style={[styles.instructionsText, { fontSize: s(12) }]} numberOfLines={expanded ? undefined : 2}>
              {item.special_instructions}
            </Text>
          </View>
        )}

        {/* Doctor specialty */}
        {expanded && item.doctor_specialization && (
          <View style={[styles.specRow, { gap: s(5), marginBottom: s(8) }]}>
            <Ionicons name="person-outline" size={s(12)} color={GRAY} />
            <Text style={[styles.specText, { fontSize: s(11) }]}>{item.doctor_specialization}</Text>
          </View>
        )}

        {/* Expand toggle */}
        <TouchableOpacity
          style={[styles.expandToggle, { paddingTop: s(8), gap: s(4) }]}
          onPress={() => setExpanded(e => !e)}
          activeOpacity={0.7}
        >
          <Text style={[styles.expandToggleText, { fontSize: s(12) }]}>{expanded ? 'Hide ▲' : 'Show details ▼'}</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

export function PatientMobilePrescription({ onBack }: { onBack?: () => void }) {
  const router  = useRouter();
  const insets  = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  const { prescriptions, loading, error, refetch } = usePrescriptions();
  const [filter, setFilter] = useState<FilterKey>('ALL');

  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));

  const filtered  = filter === 'ALL' ? prescriptions : prescriptions.filter(p => p.status === filter);
  const total     = prescriptions.length;
  const active    = prescriptions.filter(p => p.status === 'ACTIVE').length;
  const topical   = prescriptions.filter(p => p.route === 'TOPICAL').length;
  const completed = prescriptions.filter(p => p.status === 'COMPLETED').length;

  return (
    <KeyboardAvoidingView style={styles.screen} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      {/* Teal header */}
      <View style={[styles.header, {
        paddingTop:        insets.top + sh(12),
        paddingBottom:     sh(20),
        paddingHorizontal: s(16),
        gap:               s(10),
      }]}>
        <TouchableOpacity
          style={[styles.headerBack, { width: s(34), height: s(34), borderRadius: s(17) }]}
          onPress={() => onBack ? onBack() : router.back()}
          activeOpacity={0.7}
        >
          <Ionicons name="arrow-back-outline" size={s(18)} color={WHITE} />
        </TouchableOpacity>
        <View style={{ flex: 1 }}>
          <Text style={[styles.headerTitle, { fontSize: s(18) }]}>Prescriptions</Text>
          <Text style={[styles.headerSub, { fontSize: s(11) }]}>Your medication history</Text>
        </View>
        <TouchableOpacity
          style={[styles.headerRefresh, { width: s(34), height: s(34), borderRadius: s(17) }]}
          onPress={refetch}
          activeOpacity={0.7}
        >
          <Ionicons name="refresh-outline" size={s(17)} color={TEAL} />
        </TouchableOpacity>
      </View>

      {/* Stats strip */}
      <View style={[styles.statsStrip, { paddingHorizontal: s(16), paddingVertical: s(14), gap: s(10) }]}>
        {[
          { value: total,     label: 'Total',     color: INDIGO },
          { value: active,    label: 'Active',    color: GREEN  },
          { value: topical,   label: 'Topical',   color: TEAL   },
          { value: completed, label: 'Completed', color: BLUE   },
        ].map(stat => (
          <View key={stat.label} style={[styles.statItem, { borderRadius: s(12), padding: s(10) }]}>
            <Text style={[styles.statValue, { fontSize: s(20), color: stat.color }]}>{stat.value}</Text>
            <Text style={[styles.statLabel, { fontSize: s(10) }]}>{stat.label}</Text>
          </View>
        ))}
      </View>

      {/* Filter pills */}
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        style={[styles.filterScroll, { marginBottom: s(4) }]}
        contentContainerStyle={{ paddingHorizontal: s(16), paddingVertical: s(8), gap: s(8) }}
      >
        {FILTERS.map(f => {
          const on    = filter === f.key;
          const count = f.key === 'ALL' ? total : prescriptions.filter(p => p.status === f.key).length;
          return (
            <TouchableOpacity
              key={f.key}
              style={[
                styles.filterPill,
                { borderRadius: s(20), paddingHorizontal: s(14), paddingVertical: s(7), gap: s(5) },
                on && styles.filterPillActive,
              ]}
              onPress={() => setFilter(f.key)}
              activeOpacity={0.7}
            >
              <Text style={[styles.filterPillText, { fontSize: s(12) }, on && styles.filterPillTextActive]}>
                {f.label}
              </Text>
              <View style={[styles.filterPillCount, { backgroundColor: on ? WHITE : BORDER, borderRadius: s(10), paddingHorizontal: s(5) }]}>
                <Text style={[{ fontSize: s(10), fontWeight: '700' }, on ? { color: TEAL } : { color: GRAY }]}>{count}</Text>
              </View>
            </TouchableOpacity>
          );
        })}
      </ScrollView>

      {/* Card list */}
      <ScrollView
        style={styles.scroll}
        contentContainerStyle={{ padding: s(16), paddingBottom: insets.bottom + sh(32) }}
        showsVerticalScrollIndicator={false}
      >
        {loading ? (
          <View style={styles.centerState}>
            <Ionicons name="hourglass-outline" size={s(40)} color={BORDER} />
            <Text style={[styles.centerText, { fontSize: s(14) }]}>Loading prescriptions…</Text>
          </View>
        ) : error ? (
          <View style={styles.centerState}>
            <Ionicons name="alert-circle-outline" size={s(40)} color={RED} />
            <Text style={[styles.centerText, { fontSize: s(13), color: RED }]}>{error}</Text>
            <TouchableOpacity
              style={[styles.retryBtn, { borderRadius: s(10), paddingHorizontal: s(20), paddingVertical: s(10), marginTop: s(8) }]}
              onPress={refetch}
              activeOpacity={0.8}
            >
              <Text style={[styles.retryBtnText, { fontSize: s(13) }]}>Try Again</Text>
            </TouchableOpacity>
          </View>
        ) : filtered.length === 0 ? (
          <View style={styles.centerState}>
            <Ionicons name="medkit-outline" size={s(48)} color={BORDER} />
            <Text style={[styles.centerText, { fontSize: s(14) }]}>
              {filter === 'ALL'
                ? 'No prescriptions found.'
                : `No ${FILTERS.find(f => f.key === filter)?.label.toLowerCase()} prescriptions.`}
            </Text>
          </View>
        ) : (
          filtered.map(item => <PrescriptionCard key={item.prescription_id} item={item} s={s} />)
        )}
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: BG },

  header:        { backgroundColor: TEAL, flexDirection: 'row', alignItems: 'center' },
  headerBack:    { backgroundColor: 'rgba(255,255,255,0.2)', alignItems: 'center', justifyContent: 'center' },
  headerTitle:   { fontWeight: '800', color: WHITE },
  headerSub:     { color: 'rgba(255,255,255,0.8)', marginTop: 2 },
  headerRefresh: { backgroundColor: WHITE, alignItems: 'center', justifyContent: 'center' },

  statsStrip: { flexDirection: 'row', backgroundColor: WHITE, borderBottomWidth: 1, borderBottomColor: BORDER },
  statItem:   { flex: 1, alignItems: 'center', backgroundColor: BG },
  statValue:  { fontWeight: '800' },
  statLabel:  { color: GRAY, fontWeight: '500', marginTop: 2 },

  filterScroll:         { flexGrow: 0, backgroundColor: WHITE, borderBottomWidth: 1, borderBottomColor: BORDER },
  filterPill:           { flexDirection: 'row', alignItems: 'center', borderWidth: 1.5, borderColor: BORDER, backgroundColor: WHITE },
  filterPillActive:     { borderColor: TEAL, backgroundColor: '#E6F4F1' },
  filterPillText:       { fontWeight: '500', color: GRAY },
  filterPillTextActive: { fontWeight: '700', color: TEAL },
  filterPillCount:      { paddingVertical: 1 },

  scroll: { flex: 1 },

  card:          { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER, borderLeftWidth: 4, overflow: 'hidden', shadowColor: '#000', shadowOpacity: 0.04, shadowRadius: 8, elevation: 2 } as any,
  cardHeader:    { flexDirection: 'row', alignItems: 'center' },
  medIconCircle: { alignItems: 'center', justifyContent: 'center' },
  cardTitle:     { fontWeight: '700', color: TEXT },
  cardGeneric:   { color: GRAY, fontStyle: 'italic', marginTop: 1 },
  cardDoctorName:{ color: GRAY, marginTop: 2 },

  statusBadge:     { flexDirection: 'row', alignItems: 'center' },
  statusBadgeText: { fontWeight: '700' },

  pillRow:      { flexDirection: 'row' },
  infoPill:     { flexDirection: 'row', alignItems: 'center', backgroundColor: BG, borderWidth: 1, borderColor: BORDER },
  routePill:    { flexDirection: 'row', alignItems: 'center', backgroundColor: '#ECFDF5', borderWidth: 1, borderColor: '#A7F3D0' },
  infoPillText: { fontWeight: '600' },

  metaRow:   { flexDirection: 'row' },
  metaItem:  { flexDirection: 'row', alignItems: 'center' },
  metaLabel: { color: GRAY },
  metaValue: { fontWeight: '600', color: TEXT },

  quantityRow:  { flexDirection: 'row', alignItems: 'center' },
  quantityText: { color: GRAY },

  instructionsBox:  { flexDirection: 'row', alignItems: 'flex-start', backgroundColor: '#E6F4F1' },
  instructionsText: { flex: 1, color: TEXT, lineHeight: 18 },

  specRow:  { flexDirection: 'row', alignItems: 'center' },
  specText: { color: GRAY },

  expandToggle:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', borderTopWidth: 1, borderTopColor: BORDER },
  expandToggleText: { fontWeight: '600', color: TEAL },

  centerState: { alignItems: 'center', justifyContent: 'center', paddingVertical: 60, gap: 12 },
  centerText:  { color: GRAY, textAlign: 'center' },
  retryBtn:    { backgroundColor: TEAL },
  retryBtnText:{ fontWeight: '700', color: WHITE },
});

export default PatientMobilePrescription;
