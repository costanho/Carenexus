import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import {
  ActivityIndicator, ScrollView, StyleSheet, Text,
  TextInput, TouchableOpacity, View, useWindowDimensions,
} from 'react-native';
import { type DoctorPatient, useDoctorPatients } from '@/hooks/use-doctor-patients';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const LGRAY  = '#9CA3AF';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const BLUE   = '#4F46E5';
const GREEN  = '#10B981';
const ORANGE = '#F59E0B';
const RED    = '#EF4444';
const TEAL   = '#0D9488';

type IoniconsName    = React.ComponentProps<typeof Ionicons>['name'];
type HealthFilter    = 'ALL' | 'STABLE' | 'MONITOR' | 'CRITICAL';
type GenderFilter    = 'ALL' | 'MALE' | 'FEMALE' | 'OTHER';

// ─── Helpers ──────────────────────────────────────────────────────────────────

function healthConfig(h: DoctorPatient['health_status']) {
  switch (h) {
    case 'STABLE':   return { bg: '#D1FAE5', color: GREEN,  icon: 'checkmark-circle-outline'  as IoniconsName, label: 'Stable'   };
    case 'MONITOR':  return { bg: '#FEF3C7', color: ORANGE, icon: 'alert-circle-outline'      as IoniconsName, label: 'Monitor'  };
    case 'CRITICAL': return { bg: '#FEE2E2', color: RED,    icon: 'close-circle-outline'      as IoniconsName, label: 'Critical' };
    default:         return { bg: '#F3F4F6', color: LGRAY,  icon: 'help-circle-outline'       as IoniconsName, label: 'Unknown'  };
  }
}

function calcAge(dob: string | null): string {
  if (!dob) return '—';
  const years = Math.floor((Date.now() - new Date(dob).getTime()) / (365.25 * 24 * 60 * 60 * 1000));
  return `${years} yrs`;
}

function formatDate(iso: string | null): string {
  if (!iso) return 'Never';
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

function formatMemberSince(iso: string): string {
  return new Date(iso).toLocaleDateString('en-GB', { month: 'short', year: 'numeric' });
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

// ─── Patient Card ─────────────────────────────────────────────────────────────

function PatientCard({ patient }: { patient: DoctorPatient }) {
  const hc = healthConfig(patient.health_status);
  const ac = avatarColor(patient.full_name);
  const age = calcAge(patient.date_of_birth);

  return (
    <View style={styles.patientCard}>
      {/* Card top — avatar, name, health + blood type */}
      <View style={styles.cardTop}>
        <View style={[styles.avatar, { backgroundColor: ac }]}>
          <Text style={styles.avatarText}>{initials(patient.full_name)}</Text>
        </View>

        <View style={styles.cardTopInfo}>
          <Text style={styles.patientName}>{patient.full_name}</Text>
          <View style={styles.metaRow}>
            <Text style={styles.metaText}>{age}</Text>
            {!!patient.gender && (
              <>
                <View style={styles.dot} />
                <Text style={styles.metaText}>
                  {patient.gender.charAt(0) + patient.gender.slice(1).toLowerCase()}
                </Text>
              </>
            )}
            {!!patient.patient_created_at && (
              <>
                <View style={styles.dot} />
                <Text style={styles.metaText}>Since {formatMemberSince(patient.patient_created_at)}</Text>
              </>
            )}
          </View>
        </View>

        <View style={styles.cardBadgeCol}>
          <View style={[styles.badge, { backgroundColor: hc.bg }]}>
            <Ionicons name={hc.icon} size={11} color={hc.color} />
            <Text style={[styles.badgeText, { color: hc.color }]}>{hc.label}</Text>
          </View>
          {!!patient.blood_type && (
            <View style={[styles.badge, { backgroundColor: '#EEF2FF' }]}>
              <Ionicons name="water-outline" size={11} color={BLUE} />
              <Text style={[styles.badgeText, { color: BLUE }]}>{patient.blood_type}</Text>
            </View>
          )}
        </View>
      </View>

      {/* Divider */}
      <View style={styles.sep} />

      {/* Contact row */}
      <View style={styles.contactRow}>
        {!!patient.email && (
          <View style={styles.contactItem}>
            <Ionicons name="mail-outline" size={13} color={LGRAY} />
            <Text style={styles.contactText} numberOfLines={1}>{patient.email}</Text>
          </View>
        )}
        {!!patient.phone && (
          <View style={styles.contactItem}>
            <Ionicons name="call-outline" size={13} color={LGRAY} />
            <Text style={styles.contactText}>{patient.phone}</Text>
          </View>
        )}
      </View>

      {/* Allergies */}
      {patient.allergies.length > 0 && (
        <View style={styles.chipSection}>
          <View style={styles.chipLabelRow}>
            <Ionicons name="warning-outline" size={12} color={RED} />
            <Text style={[styles.chipLabel, { color: RED }]}>Allergies</Text>
          </View>
          <View style={styles.chipRow}>
            {patient.allergies.map(a => (
              <View key={a} style={[styles.chip, { backgroundColor: '#FEE2E2', borderColor: '#FECACA' }]}>
                <Text style={[styles.chipText, { color: RED }]}>{a}</Text>
              </View>
            ))}
          </View>
        </View>
      )}

      {/* Chronic conditions */}
      {patient.chronic_conditions.length > 0 && (
        <View style={styles.chipSection}>
          <View style={styles.chipLabelRow}>
            <Ionicons name="heart-outline" size={12} color={ORANGE} />
            <Text style={[styles.chipLabel, { color: ORANGE }]}>Chronic Conditions</Text>
          </View>
          <View style={styles.chipRow}>
            {patient.chronic_conditions.map(c => (
              <View key={c} style={[styles.chip, { backgroundColor: '#FEF3C7', borderColor: '#FDE68A' }]}>
                <Text style={[styles.chipText, { color: ORANGE }]}>{c}</Text>
              </View>
            ))}
          </View>
        </View>
      )}

      {/* Footer — visit stats */}
      <View style={styles.cardFooter}>
        <View style={styles.footerStat}>
          <Ionicons name="calendar-number-outline" size={14} color={BLUE} />
          <Text style={styles.footerStatNum}>{patient.appointment_count}</Text>
          <Text style={styles.footerStatLabel}>visit{patient.appointment_count !== 1 ? 's' : ''}</Text>
        </View>
        <View style={styles.footerDivider} />
        <View style={styles.footerStat}>
          <Ionicons name="time-outline" size={14} color={GRAY} />
          <Text style={styles.footerStatLabel}>
            Last seen: <Text style={styles.footerStatNum}>{formatDate(patient.last_appointment_at)}</Text>
          </Text>
        </View>

        <TouchableOpacity style={styles.viewBtn} activeOpacity={0.8}>
          <Text style={styles.viewBtnText}>View Profile</Text>
          <Ionicons name="chevron-forward" size={13} color={BLUE} />
        </TouchableOpacity>
      </View>
    </View>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────

export default function DoctorWebPatients() {
  const { patients, loading, error, refetch } = useDoctorPatients();
  const { width } = useWindowDimensions();
  const twoCol = width >= 1100;

  const [healthFilter, setHealthFilter] = useState<HealthFilter>('ALL');
  const [genderFilter, setGenderFilter] = useState<GenderFilter>('ALL');
  const [search, setSearch] = useState('');

  const stats = useMemo(() => ({
    total:    patients.length,
    stable:   patients.filter(p => p.health_status === 'STABLE').length,
    monitor:  patients.filter(p => p.health_status === 'MONITOR').length,
    critical: patients.filter(p => p.health_status === 'CRITICAL').length,
  }), [patients]);

  const filtered = useMemo(() => (
    patients
      .filter(p => healthFilter === 'ALL' || p.health_status === healthFilter)
      .filter(p => genderFilter === 'ALL' || p.gender === genderFilter)
      .filter(p => !search || p.full_name.toLowerCase().includes(search.toLowerCase())
        || p.email?.toLowerCase().includes(search.toLowerCase()))
  ), [patients, healthFilter, genderFilter, search]);

  const healthFilters: { key: HealthFilter; label: string; color: string }[] = [
    { key: 'ALL',      label: 'All',      color: BLUE   },
    { key: 'STABLE',   label: 'Stable',   color: GREEN  },
    { key: 'MONITOR',  label: 'Monitor',  color: ORANGE },
    { key: 'CRITICAL', label: 'Critical', color: RED    },
  ];

  const genderFilters: { key: GenderFilter; label: string }[] = [
    { key: 'ALL',    label: 'All Genders' },
    { key: 'FEMALE', label: 'Female'      },
    { key: 'MALE',   label: 'Male'        },
    { key: 'OTHER',  label: 'Other'       },
  ];

  if (loading) {
    return <View style={styles.center}><ActivityIndicator size="large" color={BLUE} /></View>;
  }

  if (error) {
    return (
      <View style={styles.center}>
        <Ionicons name="alert-circle-outline" size={52} color={RED} />
        <Text style={styles.errorText}>{error}</Text>
        <TouchableOpacity style={styles.retryBtn} onPress={refetch} activeOpacity={0.8}>
          <Text style={styles.retryBtnText}>Retry</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>

      {/* Page Header */}
      <View style={styles.pageHeader}>
        <View>
          <Text style={styles.pageTitle}>My Patients</Text>
          <Text style={styles.pageSub}>Patients who have had appointments with you</Text>
        </View>
        <TouchableOpacity style={styles.refreshBtn} onPress={refetch} activeOpacity={0.8}>
          <Ionicons name="refresh-outline" size={16} color={BLUE} />
          <Text style={styles.refreshText}>Refresh</Text>
        </TouchableOpacity>
      </View>

      {/* Stats Row */}
      <View style={styles.statsRow}>
        <StatCard icon="people-outline"           iconBg="#EEF2FF" iconColor={BLUE}   label="Total"    value={stats.total}    />
        <StatCard icon="checkmark-circle-outline" iconBg="#D1FAE5" iconColor={GREEN}  label="Stable"   value={stats.stable}   />
        <StatCard icon="alert-circle-outline"     iconBg="#FEF3C7" iconColor={ORANGE} label="Monitor"  value={stats.monitor}  />
        <StatCard icon="close-circle-outline"     iconBg="#FEE2E2" iconColor={RED}    label="Critical" value={stats.critical} />
      </View>

      {/* Filter Card */}
      <View style={styles.filterCard}>
        <View style={styles.filterRow}>
          {/* Health status pills */}
          <View style={styles.filterGroup}>
            {healthFilters.map(f => (
              <TouchableOpacity
                key={f.key}
                style={[
                  styles.pill,
                  healthFilter === f.key && { backgroundColor: f.color + '18', borderColor: f.color },
                ]}
                onPress={() => setHealthFilter(f.key)}
                activeOpacity={0.7}
              >
                <Text style={[styles.pillText, healthFilter === f.key && { color: f.color, fontWeight: '600' }]}>
                  {f.label}
                </Text>
              </TouchableOpacity>
            ))}
          </View>

          {/* Gender pills */}
          <View style={[styles.filterGroup, { marginLeft: 8 }]}>
            {genderFilters.map(f => (
              <TouchableOpacity
                key={f.key}
                style={[styles.pill, genderFilter === f.key && styles.pillActive]}
                onPress={() => setGenderFilter(f.key)}
                activeOpacity={0.7}
              >
                <Text style={[styles.pillText, genderFilter === f.key && styles.pillActiveText]}>{f.label}</Text>
              </TouchableOpacity>
            ))}
          </View>

          {/* Search */}
          <View style={styles.searchBox}>
            <Ionicons name="search-outline" size={15} color={LGRAY} />
            <TextInput
              style={styles.searchInput}
              placeholder="Search by name or email…"
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
      <Text style={styles.resultCount}>{filtered.length} patient{filtered.length !== 1 ? 's' : ''}</Text>

      {/* Patient grid */}
      {filtered.length === 0 ? (
        <View style={styles.emptyState}>
          <Ionicons name="people-outline" size={60} color={BORDER} />
          <Text style={styles.emptyTitle}>No patients found</Text>
          <Text style={styles.emptySub}>Try adjusting your filters or search</Text>
        </View>
      ) : (
        <View style={[styles.grid, twoCol && styles.gridTwo]}>
          {filtered.map(p => (
            <View key={p.patient_id} style={twoCol ? styles.gridItemTwo : styles.gridItemOne}>
              <PatientCard patient={p} />
            </View>
          ))}
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

  statsRow: { flexDirection: 'row', gap: 12, marginBottom: 20, flexWrap: 'wrap' },
  statCard: {
    flex: 1, minWidth: 110,
    backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER,
    borderRadius: 12, padding: 14, gap: 5,
  },
  statIconBg: { width: 38, height: 38, borderRadius: 9, alignItems: 'center', justifyContent: 'center' },
  statValue:  { fontSize: 26, fontWeight: '700', color: TEXT },
  statLabel:  { fontSize: 12, color: GRAY },

  filterCard:  { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER, borderRadius: 12, padding: 16, marginBottom: 16 },
  filterRow:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 10 },
  filterGroup: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  pill:        { paddingHorizontal: 13, paddingVertical: 7, borderRadius: 20, borderWidth: 1, borderColor: BORDER, backgroundColor: WHITE },
  pillActive:  { backgroundColor: '#EEF2FF', borderColor: BLUE },
  pillText:    { fontSize: 13, color: GRAY, fontWeight: '500' },
  pillActiveText: { color: BLUE },
  searchBox:  {
    flex: 1, minWidth: 220,
    flexDirection: 'row', alignItems: 'center', gap: 8,
    borderWidth: 1, borderColor: BORDER, borderRadius: 8,
    paddingHorizontal: 12, paddingVertical: 8, backgroundColor: BG,
  },
  searchInput: { flex: 1, fontSize: 13, color: TEXT, outlineStyle: 'none' } as any,

  resultCount: { fontSize: 13, color: GRAY, fontWeight: '500', marginBottom: 16 },

  grid:        { gap: 14 },
  gridTwo:     { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start' },
  gridItemOne: { width: '100%' },
  gridItemTwo: { width: '49%', marginBottom: 14, flexShrink: 0 },

  // Patient Card
  patientCard: {
    backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER,
    borderRadius: 16, overflow: 'hidden',
  },
  cardTop:     { flexDirection: 'row', alignItems: 'flex-start', gap: 14, padding: 18 },
  avatar:      { width: 52, height: 52, borderRadius: 26, alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  avatarText:  { color: WHITE, fontSize: 18, fontWeight: '700' },
  cardTopInfo: { flex: 1 },
  patientName: { fontSize: 17, fontWeight: '700', color: TEXT, marginBottom: 4 },
  metaRow:     { flexDirection: 'row', alignItems: 'center', gap: 5, flexWrap: 'wrap' },
  metaText:    { fontSize: 12, color: GRAY },
  dot:         { width: 3, height: 3, borderRadius: 2, backgroundColor: LGRAY },
  cardBadgeCol:{ gap: 5, alignItems: 'flex-end' },
  badge:       { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 8, paddingVertical: 3, borderRadius: 20 },
  badgeText:   { fontSize: 11, fontWeight: '600' },

  sep: { height: 1, backgroundColor: BORDER, marginHorizontal: 18 },

  contactRow:  { flexDirection: 'row', gap: 16, paddingHorizontal: 18, paddingVertical: 12, flexWrap: 'wrap' },
  contactItem: { flexDirection: 'row', alignItems: 'center', gap: 5 },
  contactText: { fontSize: 12, color: GRAY },

  chipSection:   { paddingHorizontal: 18, paddingBottom: 10 },
  chipLabelRow:  { flexDirection: 'row', alignItems: 'center', gap: 4, marginBottom: 6 },
  chipLabel:     { fontSize: 11, fontWeight: '700', textTransform: 'uppercase', letterSpacing: 0.5 },
  chipRow:       { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  chip:          { paddingHorizontal: 10, paddingVertical: 4, borderRadius: 20, borderWidth: 1 },
  chipText:      { fontSize: 11, fontWeight: '600' },

  cardFooter:    {
    flexDirection: 'row', alignItems: 'center', gap: 12,
    paddingHorizontal: 18, paddingVertical: 14,
    borderTopWidth: 1, borderTopColor: BORDER,
    backgroundColor: BG,
  },
  footerStat:    { flexDirection: 'row', alignItems: 'center', gap: 4 },
  footerStatNum: { fontSize: 13, fontWeight: '700', color: TEXT },
  footerStatLabel: { fontSize: 12, color: GRAY },
  footerDivider: { width: 1, height: 16, backgroundColor: BORDER },
  viewBtn:       { flexDirection: 'row', alignItems: 'center', gap: 3, marginLeft: 'auto' as any },
  viewBtnText:   { fontSize: 13, color: BLUE, fontWeight: '500' },

  emptyState: { alignItems: 'center', gap: 10, paddingVertical: 64 },
  emptyTitle: { fontSize: 18, fontWeight: '600', color: TEXT },
  emptySub:   { fontSize: 14, color: GRAY },

  errorText:    { fontSize: 15, color: GRAY, textAlign: 'center' },
  retryBtn:     { backgroundColor: BLUE, borderRadius: 8, paddingHorizontal: 20, paddingVertical: 10 },
  retryBtnText: { color: WHITE, fontWeight: '600', fontSize: 14 },
});
