import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import {
  ActivityIndicator, ScrollView, StyleSheet, Text,
  TextInput, TouchableOpacity, View, useWindowDimensions,
} from 'react-native';
import {
  type DoctorPrescription, type PrescriptionStatus,
  useDoctorPrescriptions,
} from '@/hooks/use-doctor-prescriptions';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const LGRAY  = '#9CA3AF';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const BLUE   = '#4F46E5';
const GREEN  = '#10B981';
const AMBER  = '#F59E0B';
const RED    = '#EF4444';
const TEAL   = '#0D9488';
const PURPLE = '#8B5CF6';

type IoniconsName  = React.ComponentProps<typeof Ionicons>['name'];
type StatusFilter  = 'ALL' | PrescriptionStatus;

// ─── Helpers ──────────────────────────────────────────────────────────────────

function statusConfig(s: PrescriptionStatus) {
  switch (s) {
    case 'ACTIVE':    return { label: 'Active',    color: GREEN, bg: '#D1FAE5', border: '#6EE7B7', icon: 'checkmark-circle-outline' as IoniconsName };
    case 'COMPLETED': return { label: 'Completed', color: BLUE,  bg: '#EEF2FF', border: '#C7D2FE', icon: 'checkbox-outline'         as IoniconsName };
    case 'EXPIRED':   return { label: 'Expired',   color: RED,   bg: '#FEE2E2', border: '#FECACA', icon: 'close-circle-outline'     as IoniconsName };
    default:          return { label: s,           color: GRAY,  bg: '#F3F4F6', border: BORDER,    icon: 'help-circle-outline'      as IoniconsName };
  }
}

function routeConfig(r: string) {
  switch (r.toUpperCase()) {
    case 'ORAL':       return { label: 'Oral',       color: TEAL,   bg: '#CCFBF1', icon: 'cafe-outline'      as IoniconsName };
    case 'TOPICAL':    return { label: 'Topical',    color: PURPLE, bg: '#EDE9FE', icon: 'color-fill-outline' as IoniconsName };
    case 'IV':         return { label: 'IV',         color: RED,    bg: '#FEE2E2', icon: 'pulse-outline'     as IoniconsName };
    case 'IM':         return { label: 'IM',         color: AMBER,  bg: '#FEF3C7', icon: 'fitness-outline'   as IoniconsName };
    case 'INHALED':    return { label: 'Inhaled',    color: BLUE,   bg: '#EEF2FF', icon: 'cloudy-outline'    as IoniconsName };
    case 'SUBLINGUAL': return { label: 'Sublingual', color: AMBER,  bg: '#FEF3C7', icon: 'water-outline'     as IoniconsName };
    default:           return { label: r,            color: GRAY,   bg: '#F3F4F6', icon: 'medical-outline'   as IoniconsName };
  }
}

function formatDate(iso: string): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

function isExpiringSoon(expiry: string | null): boolean {
  if (!expiry) return false;
  const days = (new Date(expiry).getTime() - Date.now()) / (1000 * 60 * 60 * 24);
  return days >= 0 && days <= 14;
}

const AVATAR_COLORS = ['#4F46E5', '#0D9488', '#F59E0B', '#EF4444', '#8B5CF6', '#10B981', '#0EA5E9'];
function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}
function initials(name: string) {
  return name.split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
}

// ─── Stat Card ────────────────────────────────────────────────────────────────

function StatCard({ icon, iconBg, iconColor, label, value }: {
  icon: IoniconsName; iconBg: string; iconColor: string; label: string; value: number;
}) {
  return (
    <View style={styles.statCard}>
      <View style={[styles.statIconBg, { backgroundColor: iconBg }]}>
        <Ionicons name={icon} size={20} color={iconColor} />
      </View>
      <Text style={styles.statValue}>{value}</Text>
      <Text style={styles.statLabel}>{label}</Text>
    </View>
  );
}

// ─── Prescription Card ───────────────────────────────────────────────────────

function RxCard({ rx }: { rx: DoctorPrescription }) {
  const [expanded, setExpanded] = useState(false);
  const sc  = statusConfig(rx.status);
  const rc  = routeConfig(rx.route);
  const pac = avatarColor(rx.patient_name);
  const dac = avatarColor(rx.doctor_name);
  const expiringSoon = isExpiringSoon(rx.expiry_date);

  return (
    <View style={[styles.card, { borderLeftColor: sc.color }]}>
      {/* Expiring soon banner */}
      {expiringSoon && rx.status === 'ACTIVE' && (
        <View style={styles.expiryBanner}>
          <Ionicons name="warning-outline" size={13} color={AMBER} />
          <Text style={styles.expiryBannerText}>Expiring soon — {formatDate(rx.expiry_date)}</Text>
        </View>
      )}

      {/* Header row */}
      <View style={styles.cardHeader}>
        <View style={styles.badgeRow}>
          <View style={[styles.badge, { backgroundColor: sc.bg, borderColor: sc.border }]}>
            <Ionicons name={sc.icon} size={11} color={sc.color} />
            <Text style={[styles.badgeText, { color: sc.color }]}>{sc.label}</Text>
          </View>
          <View style={[styles.badge, { backgroundColor: rc.bg, borderColor: BORDER }]}>
            <Ionicons name={rc.icon} size={11} color={rc.color} />
            <Text style={[styles.badgeText, { color: rc.color }]}>{rc.label}</Text>
          </View>
          {rx.refills_allowed > 0 && (
            <View style={[styles.badge, { backgroundColor: '#F3F4F6', borderColor: BORDER }]}>
              <Ionicons name="refresh-outline" size={11} color={GRAY} />
              <Text style={[styles.badgeText, { color: GRAY }]}>{rx.refills_allowed} refill{rx.refills_allowed !== 1 ? 's' : ''}</Text>
            </View>
          )}
        </View>
        <Text style={styles.prescribedDate}>{formatDate(rx.prescribed_date)}</Text>
      </View>

      {/* Medication name */}
      <View style={styles.medicationBlock}>
        <View style={styles.rxIconWrap}>
          <Ionicons name="medkit-outline" size={22} color={BLUE} />
        </View>
        <View style={{ flex: 1 }}>
          <Text style={styles.medicationName}>{rx.medication_name}</Text>
          {!!rx.generic_name && (
            <Text style={styles.genericName}>{rx.generic_name}</Text>
          )}
        </View>
      </View>

      {/* Dosage / Frequency / Quantity row */}
      <View style={styles.infoGrid}>
        <View style={styles.infoCell}>
          <Ionicons name="flask-outline" size={13} color={LGRAY} />
          <View>
            <Text style={styles.infoCellLabel}>Dosage</Text>
            <Text style={styles.infoCellValue}>{rx.dosage}</Text>
          </View>
        </View>
        <View style={styles.infoCell}>
          <Ionicons name="time-outline" size={13} color={LGRAY} />
          <View>
            <Text style={styles.infoCellLabel}>Frequency</Text>
            <Text style={styles.infoCellValue}>{rx.frequency}</Text>
          </View>
        </View>
        {!!rx.quantity && (
          <View style={styles.infoCell}>
            <Ionicons name="layers-outline" size={13} color={LGRAY} />
            <View>
              <Text style={styles.infoCellLabel}>Quantity</Text>
              <Text style={styles.infoCellValue}>{rx.quantity}</Text>
            </View>
          </View>
        )}
      </View>

      {/* Patient + doctor */}
      <View style={styles.personRow}>
        <View style={styles.personChip}>
          <View style={[styles.miniAvatar, { backgroundColor: pac }]}>
            <Text style={styles.miniAvatarText}>{initials(rx.patient_name)}</Text>
          </View>
          <View>
            <Text style={styles.personRole}>Patient</Text>
            <Text style={styles.personName}>{rx.patient_name}</Text>
          </View>
        </View>
        <View style={styles.personDivider} />
        <View style={styles.personChip}>
          <View style={[styles.miniAvatar, { backgroundColor: dac }]}>
            <Text style={styles.miniAvatarText}>{initials(rx.doctor_name)}</Text>
          </View>
          <View>
            <Text style={styles.personRole}>Prescribed by</Text>
            <Text style={styles.personName}>{rx.doctor_name}</Text>
          </View>
        </View>
        {!!rx.doctor_specialization && (
          <View style={styles.specBadge}>
            <Text style={styles.specText}>{rx.doctor_specialization}</Text>
          </View>
        )}
      </View>

      {/* Expiry date row */}
      {!!rx.expiry_date && (
        <View style={styles.expiryRow}>
          <Ionicons name="calendar-outline" size={13} color={LGRAY} />
          <Text style={styles.expiryLabel}>Expires:</Text>
          <Text style={[styles.expiryValue, expiringSoon && { color: AMBER, fontWeight: '700' }]}>
            {formatDate(rx.expiry_date)}
          </Text>
        </View>
      )}

      {/* Special instructions — expandable */}
      {!!rx.special_instructions && (
        <>
          {expanded && (
            <View style={styles.instructionsBox}>
              <View style={styles.instructionsHeader}>
                <Ionicons name="information-circle-outline" size={15} color={BLUE} />
                <Text style={styles.instructionsLabel}>Special Instructions</Text>
              </View>
              <Text style={styles.instructionsText}>{rx.special_instructions}</Text>
            </View>
          )}
          <TouchableOpacity style={styles.expandBtn} onPress={() => setExpanded(e => !e)} activeOpacity={0.7}>
            <Text style={styles.expandBtnText}>{expanded ? 'Hide instructions' : 'View instructions'}</Text>
            <Ionicons name={expanded ? 'chevron-up' : 'chevron-down'} size={14} color={BLUE} />
          </TouchableOpacity>
        </>
      )}
    </View>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────

const STATUS_FILTERS: { key: StatusFilter; label: string; color: string }[] = [
  { key: 'ALL',       label: 'All',       color: BLUE  },
  { key: 'ACTIVE',    label: 'Active',    color: GREEN },
  { key: 'COMPLETED', label: 'Completed', color: BLUE  },
  { key: 'EXPIRED',   label: 'Expired',   color: RED   },
];

export default function DoctorWebPrescription() {
  const { prescriptions, loading, error, refetch } = useDoctorPrescriptions();
  const { width } = useWindowDimensions();
  const twoCol = width >= 1200;

  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');
  const [search,       setSearch]       = useState('');

  const stats = useMemo(() => ({
    total:     prescriptions.length,
    active:    prescriptions.filter(r => r.status === 'ACTIVE').length,
    completed: prescriptions.filter(r => r.status === 'COMPLETED').length,
    expired:   prescriptions.filter(r => r.status === 'EXPIRED').length,
    refills:   prescriptions.filter(r => r.refills_allowed > 0 && r.status === 'ACTIVE').length,
    expiring:  prescriptions.filter(r => isExpiringSoon(r.expiry_date) && r.status === 'ACTIVE').length,
  }), [prescriptions]);

  const filtered = useMemo(() => (
    prescriptions
      .filter(r => statusFilter === 'ALL' || r.status === statusFilter)
      .filter(r => !search
        || r.medication_name.toLowerCase().includes(search.toLowerCase())
        || r.generic_name?.toLowerCase().includes(search.toLowerCase())
        || r.patient_name.toLowerCase().includes(search.toLowerCase())
        || r.doctor_name.toLowerCase().includes(search.toLowerCase())
      )
  ), [prescriptions, statusFilter, search]);

  if (loading) return <View style={styles.center}><ActivityIndicator size="large" color={BLUE} /></View>;

  if (error) return (
    <View style={styles.center}>
      <Ionicons name="alert-circle-outline" size={52} color={RED} />
      <Text style={styles.errorText}>{error}</Text>
      <TouchableOpacity style={styles.retryBtn} onPress={refetch} activeOpacity={0.8}>
        <Text style={styles.retryBtnText}>Retry</Text>
      </TouchableOpacity>
    </View>
  );

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>

      {/* Page header */}
      <View style={styles.pageHeader}>
        <View>
          <Text style={styles.pageTitle}>Prescriptions</Text>
          <Text style={styles.pageSub}>Medication prescriptions for all your patients</Text>
        </View>
        <TouchableOpacity style={styles.refreshBtn} onPress={refetch} activeOpacity={0.8}>
          <Ionicons name="refresh-outline" size={16} color={BLUE} />
          <Text style={styles.refreshText}>Refresh</Text>
        </TouchableOpacity>
      </View>

      {/* Stats */}
      <View style={styles.statsRow}>
        <StatCard icon="medical-outline"          iconBg="#EEF2FF" iconColor={BLUE}  label="Total"     value={stats.total}     />
        <StatCard icon="checkmark-circle-outline" iconBg="#D1FAE5" iconColor={GREEN} label="Active"    value={stats.active}    />
        <StatCard icon="checkbox-outline"         iconBg="#EEF2FF" iconColor={BLUE}  label="Completed" value={stats.completed} />
        <StatCard icon="close-circle-outline"     iconBg="#FEE2E2" iconColor={RED}   label="Expired"   value={stats.expired}   />
        <StatCard icon="refresh-outline"          iconBg="#F3F4F6" iconColor={GRAY}  label="Refillable" value={stats.refills}  />
        <StatCard icon="warning-outline"          iconBg="#FEF3C7" iconColor={AMBER} label="Expiring"  value={stats.expiring}  />
      </View>

      {/* Filters */}
      <View style={styles.filterCard}>
        <View style={styles.filterRow}>
          <View style={styles.pillGroup}>
            {STATUS_FILTERS.map(f => (
              <TouchableOpacity
                key={f.key}
                style={[styles.pill, statusFilter === f.key && { backgroundColor: f.color + '18', borderColor: f.color }]}
                onPress={() => setStatusFilter(f.key)}
                activeOpacity={0.7}
              >
                <Text style={[styles.pillText, statusFilter === f.key && { color: f.color, fontWeight: '700' }]}>
                  {f.label}
                </Text>
              </TouchableOpacity>
            ))}
          </View>

          <View style={styles.searchBox}>
            <Ionicons name="search-outline" size={15} color={LGRAY} />
            <TextInput
              style={styles.searchInput}
              placeholder="Search medication, patient, doctor…"
              placeholderTextColor={LGRAY}
              value={search}
              onChangeText={setSearch}
            />
            {!!search && (
              <TouchableOpacity onPress={() => setSearch('')} activeOpacity={0.7}>
                <Ionicons name="close-circle" size={15} color={LGRAY} />
              </TouchableOpacity>
            )}
          </View>
        </View>
      </View>

      {/* Result count */}
      <Text style={styles.resultCount}>
        {filtered.length} prescription{filtered.length !== 1 ? 's' : ''}
      </Text>

      {/* Cards */}
      {filtered.length === 0 ? (
        <View style={styles.emptyState}>
          <Ionicons name="medical-outline" size={60} color={BORDER} />
          <Text style={styles.emptyTitle}>No prescriptions found</Text>
          <Text style={styles.emptySub}>Try adjusting your filters or search</Text>
        </View>
      ) : twoCol ? (
        <View style={styles.grid}>
          <View style={styles.col}>
            {filtered.filter((_, i) => i % 2 === 0).map(r => <RxCard key={r.prescription_id} rx={r} />)}
          </View>
          <View style={styles.col}>
            {filtered.filter((_, i) => i % 2 === 1).map(r => <RxCard key={r.prescription_id} rx={r} />)}
          </View>
        </View>
      ) : (
        <View style={{ gap: 14 }}>
          {filtered.map(r => <RxCard key={r.prescription_id} rx={r} />)}
        </View>
      )}
    </ScrollView>
  );
}

// ─── Styles ──────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  scroll:    { flex: 1, backgroundColor: BG },
  container: { padding: 24, paddingBottom: 48 },
  center:    { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 12 },

  pageHeader:  { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 24 },
  pageTitle:   { fontSize: 24, fontWeight: '700', color: TEXT, marginBottom: 4 },
  pageSub:     { fontSize: 14, color: GRAY },
  refreshBtn:  {
    flexDirection: 'row', alignItems: 'center', gap: 6,
    borderWidth: 1, borderColor: BORDER, borderRadius: 8,
    paddingHorizontal: 12, paddingVertical: 8, backgroundColor: WHITE,
  },
  refreshText: { fontSize: 13, color: BLUE, fontWeight: '500' },

  statsRow: { flexDirection: 'row', gap: 10, marginBottom: 20, flexWrap: 'wrap' },
  statCard: {
    flex: 1, minWidth: 90,
    backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER,
    borderRadius: 12, padding: 14, gap: 5,
  },
  statIconBg: { width: 36, height: 36, borderRadius: 8, alignItems: 'center', justifyContent: 'center' },
  statValue:  { fontSize: 24, fontWeight: '700', color: TEXT },
  statLabel:  { fontSize: 11, color: GRAY },

  filterCard:  { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER, borderRadius: 12, padding: 16, marginBottom: 16 },
  filterRow:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 10 },
  pillGroup:   { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  pill:        { paddingHorizontal: 13, paddingVertical: 7, borderRadius: 20, borderWidth: 1, borderColor: BORDER, backgroundColor: WHITE },
  pillText:    { fontSize: 13, color: GRAY, fontWeight: '500' },
  searchBox:   {
    flex: 1, minWidth: 220,
    flexDirection: 'row', alignItems: 'center', gap: 8,
    borderWidth: 1, borderColor: BORDER, borderRadius: 8,
    paddingHorizontal: 12, paddingVertical: 8, backgroundColor: BG,
  },
  searchInput: { flex: 1, fontSize: 13, color: TEXT, outlineStyle: 'none' } as any,

  resultCount: { fontSize: 13, color: GRAY, fontWeight: '500', marginBottom: 16 },

  grid: { flexDirection: 'row', gap: 14, alignItems: 'flex-start' },
  col:  { flex: 1, gap: 14 },

  // Card
  card: {
    backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER,
    borderRadius: 16, borderLeftWidth: 4, overflow: 'hidden',
    padding: 18, gap: 14,
  },

  expiryBanner: {
    flexDirection: 'row', alignItems: 'center', gap: 6,
    backgroundColor: '#FEF3C7', borderRadius: 8, paddingHorizontal: 10, paddingVertical: 6,
    borderWidth: 1, borderColor: '#FDE68A',
    marginBottom: -4,
  },
  expiryBannerText: { fontSize: 12, color: AMBER, fontWeight: '600' },

  cardHeader:    { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8 },
  badgeRow:      { flexDirection: 'row', gap: 6, flexWrap: 'wrap' },
  badge:         { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 9, paddingVertical: 4, borderRadius: 20, borderWidth: 1 },
  badgeText:     { fontSize: 11, fontWeight: '600' },
  prescribedDate:{ fontSize: 12, color: LGRAY },

  medicationBlock:{ flexDirection: 'row', alignItems: 'center', gap: 14 },
  rxIconWrap:     {
    width: 48, height: 48, borderRadius: 12,
    backgroundColor: '#EEF2FF', alignItems: 'center', justifyContent: 'center',
  },
  medicationName: { fontSize: 16, fontWeight: '700', color: TEXT, lineHeight: 22 },
  genericName:    { fontSize: 12, color: GRAY, marginTop: 2, fontStyle: 'italic' },

  infoGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 12, backgroundColor: BG, borderRadius: 10, padding: 12, borderWidth: 1, borderColor: BORDER },
  infoCell: { flexDirection: 'row', alignItems: 'flex-start', gap: 6, minWidth: 130, flex: 1 },
  infoCellLabel: { fontSize: 10, color: LGRAY, textTransform: 'uppercase', letterSpacing: 0.5 },
  infoCellValue: { fontSize: 13, color: TEXT, fontWeight: '500', marginTop: 1 },

  personRow:     { flexDirection: 'row', alignItems: 'center', gap: 12, flexWrap: 'wrap' },
  personChip:    { flexDirection: 'row', alignItems: 'center', gap: 8 },
  personDivider: { width: 1, height: 28, backgroundColor: BORDER },
  miniAvatar:    { width: 30, height: 30, borderRadius: 15, alignItems: 'center', justifyContent: 'center' },
  miniAvatarText:{ color: WHITE, fontSize: 10, fontWeight: '700' },
  personRole:    { fontSize: 10, color: LGRAY, textTransform: 'uppercase', letterSpacing: 0.5 },
  personName:    { fontSize: 12, fontWeight: '600', color: TEXT },
  specBadge:     { paddingHorizontal: 8, paddingVertical: 3, borderRadius: 6, backgroundColor: '#F3F4F6', borderWidth: 1, borderColor: BORDER },
  specText:      { fontSize: 11, color: GRAY },

  expiryRow:  { flexDirection: 'row', alignItems: 'center', gap: 6 },
  expiryLabel:{ fontSize: 12, color: GRAY },
  expiryValue:{ fontSize: 12, color: TEXT, fontWeight: '600' },

  instructionsBox: {
    backgroundColor: '#EFF6FF', borderRadius: 10, padding: 14,
    borderWidth: 1, borderColor: '#BFDBFE', gap: 8,
  },
  instructionsHeader: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  instructionsLabel:  { fontSize: 12, fontWeight: '700', color: BLUE },
  instructionsText:   { fontSize: 13, color: GRAY, lineHeight: 20 },

  expandBtn:     { flexDirection: 'row', alignItems: 'center', gap: 4, alignSelf: 'flex-start' },
  expandBtnText: { fontSize: 13, color: BLUE, fontWeight: '500' },

  emptyState: { alignItems: 'center', gap: 10, paddingVertical: 64 },
  emptyTitle: { fontSize: 18, fontWeight: '600', color: TEXT },
  emptySub:   { fontSize: 14, color: GRAY },

  errorText:    { fontSize: 15, color: GRAY, textAlign: 'center' },
  retryBtn:     { backgroundColor: BLUE, borderRadius: 8, paddingHorizontal: 20, paddingVertical: 10 },
  retryBtnText: { color: WHITE, fontWeight: '600', fontSize: 14 },
});
