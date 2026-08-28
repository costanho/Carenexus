import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import {
  ActivityIndicator, ScrollView, StyleSheet, Text,
  TextInput, TouchableOpacity, View, useWindowDimensions,
} from 'react-native';
import {
  type DoctorLabResult, type LabFlag, type LabStatus, type LabTestType,
  useDoctorLabResults,
} from '@/hooks/use-doctor-lab-results';

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
const NAVY   = '#0D1B2E';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type TypeFilter   = 'ALL' | LabTestType;
type StatusFilter = 'ALL' | LabStatus;

// ─── Helpers ──────────────────────────────────────────────────────────────────

function testTypeConfig(t: LabTestType) {
  switch (t.toUpperCase()) {
    case 'BLOOD':   return { label: 'Blood',   color: RED,    bg: '#FEE2E2', icon: 'water-outline'        as IoniconsName, border: '#FECACA' };
    case 'SWAB':    return { label: 'Swab',    color: TEAL,   bg: '#CCFBF1', icon: 'medical-outline'      as IoniconsName, border: '#99F6E4' };
    case 'URINE':   return { label: 'Urine',   color: AMBER,  bg: '#FEF3C7', icon: 'flask-outline'        as IoniconsName, border: '#FDE68A' };
    case 'CULTURE': return { label: 'Culture', color: PURPLE, bg: '#EDE9FE', icon: 'bug-outline'          as IoniconsName, border: '#DDD6FE' };
    default:        return { label: t,         color: BLUE,   bg: '#EEF2FF', icon: 'analytics-outline'    as IoniconsName, border: '#C7D2FE' };
  }
}

function statusConfig(s: LabStatus) {
  switch (s) {
    case 'COMPLETED':       return { label: 'Completed',       color: GREEN, bg: '#D1FAE5', border: '#6EE7B7', icon: 'checkmark-circle-outline' as IoniconsName };
    case 'REQUIRES_REVIEW': return { label: 'Requires Review', color: AMBER, bg: '#FEF3C7', border: '#FDE68A', icon: 'eye-outline'               as IoniconsName };
    default:                return { label: s,                 color: GRAY,  bg: '#F3F4F6', border: BORDER,    icon: 'help-circle-outline'      as IoniconsName };
  }
}

function flagColor(f: LabFlag) {
  switch (f) {
    case 'LOW':  return { text: AMBER, bg: '#FEF3C7', border: '#FDE68A', icon: 'arrow-down-outline'  as IoniconsName };
    case 'HIGH': return { text: RED,   bg: '#FEE2E2', border: '#FECACA', icon: 'arrow-up-outline'    as IoniconsName };
    default:     return { text: GREEN, bg: '#D1FAE5', border: '#6EE7B7', icon: 'checkmark-outline'   as IoniconsName };
  }
}

function formatDate(iso: string): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

function formatDateTime(iso: string): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function labelify(key: string) {
  return key.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
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

// ─── Sub-Test Row ─────────────────────────────────────────────────────────────

function SubTestRow({ paramKey, sub }: { paramKey: string; sub: import('@/hooks/use-doctor-lab-results').LabSubTest }) {
  const fc = flagColor(sub.flag);
  return (
    <View style={styles.subRow}>
      <Text style={styles.subParam}>{labelify(paramKey)}</Text>
      <View style={styles.subValueWrap}>
        <Text style={styles.subValue}>{sub.value} {sub.unit}</Text>
        <View style={[styles.flagChip, { backgroundColor: fc.bg, borderColor: fc.border }]}>
          <Ionicons name={fc.icon} size={10} color={fc.text} />
          <Text style={[styles.flagText, { color: fc.text }]}>{sub.flag}</Text>
        </View>
      </View>
      <Text style={styles.subReference}>{sub.reference}</Text>
      {!!sub.interpretation && (
        <Text style={styles.subInterp}>{sub.interpretation}</Text>
      )}
    </View>
  );
}

// ─── Lab Result Card ──────────────────────────────────────────────────────────

function LabCard({ result }: { result: DoctorLabResult }) {
  const [expanded, setExpanded] = useState(false);
  const tc  = testTypeConfig(result.test_type);
  const sc  = statusConfig(result.status);
  const pac = avatarColor(result.patient_name);
  const dac = avatarColor(result.doctor_name);

  const subTests   = result.result_data ? Object.entries(result.result_data) : [];
  const abnormal   = subTests.filter(([, s]) => s.flag !== 'NORMAL');
  const hasDetails = subTests.length > 0 || result.result_value;

  return (
    <View style={[styles.card, { borderLeftColor: result.is_critical ? RED : tc.color }]}>

      {/* Critical banner */}
      {!!result.is_critical && (
        <View style={styles.criticalBanner}>
          <Ionicons name="alert-circle" size={13} color={RED} />
          <Text style={styles.criticalBannerText}>Critical Result — Immediate attention required</Text>
        </View>
      )}

      {/* Requires review banner */}
      {result.status === 'REQUIRES_REVIEW' && !result.is_critical && (
        <View style={styles.reviewBanner}>
          <Ionicons name="eye-outline" size={13} color={AMBER} />
          <Text style={styles.reviewBannerText}>Pending review</Text>
        </View>
      )}

      {/* Header */}
      <View style={styles.cardHeader}>
        <View style={styles.badgeRow}>
          <View style={[styles.badge, { backgroundColor: tc.bg, borderColor: tc.border }]}>
            <Ionicons name={tc.icon} size={11} color={tc.color} />
            <Text style={[styles.badgeText, { color: tc.color }]}>{tc.label}</Text>
          </View>
          <View style={[styles.badge, { backgroundColor: sc.bg, borderColor: sc.border }]}>
            <Ionicons name={sc.icon} size={11} color={sc.color} />
            <Text style={[styles.badgeText, { color: sc.color }]}>{sc.label}</Text>
          </View>
          {abnormal.length > 0 && (
            <View style={[styles.badge, { backgroundColor: '#FEE2E2', borderColor: '#FECACA' }]}>
              <Ionicons name="warning-outline" size={11} color={RED} />
              <Text style={[styles.badgeText, { color: RED }]}>{abnormal.length} abnormal</Text>
            </View>
          )}
        </View>
        <Text style={styles.testDate}>{formatDate(result.test_date)}</Text>
      </View>

      {/* Test name */}
      <View style={styles.testNameBlock}>
        <View style={[styles.testIconWrap, { backgroundColor: tc.bg }]}>
          <Ionicons name={tc.icon} size={22} color={tc.color} />
        </View>
        <Text style={styles.testName}>{result.test_name}</Text>
      </View>

      {/* Simple result_value */}
      {!!result.result_value && (
        <View style={styles.simpleResultBox}>
          <Text style={styles.simpleResultLabel}>Result</Text>
          <Text style={styles.simpleResultValue}>
            {result.result_value}{result.unit ? ` ${result.unit}` : ''}
          </Text>
          {!!result.reference_range && (
            <Text style={styles.simpleResultRef}>Reference: {result.reference_range}</Text>
          )}
        </View>
      )}

      {/* Patient + doctor */}
      <View style={styles.personRow}>
        <View style={styles.personChip}>
          <View style={[styles.miniAvatar, { backgroundColor: pac }]}>
            <Text style={styles.miniAvatarText}>{initials(result.patient_name)}</Text>
          </View>
          <View>
            <Text style={styles.personRole}>Patient</Text>
            <Text style={styles.personName}>{result.patient_name}</Text>
          </View>
        </View>
        <View style={styles.personDivider} />
        <View style={styles.personChip}>
          <View style={[styles.miniAvatar, { backgroundColor: dac }]}>
            <Text style={styles.miniAvatarText}>{initials(result.doctor_name)}</Text>
          </View>
          <View>
            <Text style={styles.personRole}>Ordered by</Text>
            <Text style={styles.personName}>{result.doctor_name}</Text>
          </View>
        </View>
        {!!result.doctor_specialization && (
          <View style={styles.specBadge}>
            <Text style={styles.specText}>{result.doctor_specialization}</Text>
          </View>
        )}
      </View>

      {/* Reviewed row */}
      {result.reviewed_at ? (
        <View style={styles.reviewedRow}>
          <Ionicons name="checkmark-done-outline" size={13} color={GREEN} />
          <Text style={styles.reviewedText}>Reviewed {formatDateTime(result.reviewed_at)}</Text>
        </View>
      ) : (
        <View style={styles.reviewedRow}>
          <Ionicons name="time-outline" size={13} color={AMBER} />
          <Text style={[styles.reviewedText, { color: AMBER }]}>Awaiting review</Text>
        </View>
      )}

      {/* Expandable sub-tests */}
      {hasDetails && (
        <>
          {expanded && (
            <View style={styles.subTestsBox}>
              <Text style={styles.subTestsTitle}>
                {subTests.length} parameter{subTests.length !== 1 ? 's' : ''}
              </Text>
              {subTests.map(([key, sub]) => (
                <SubTestRow key={key} paramKey={key} sub={sub} />
              ))}
            </View>
          )}
          <TouchableOpacity style={styles.expandBtn} onPress={() => setExpanded(e => !e)} activeOpacity={0.7}>
            <Text style={styles.expandBtnText}>{expanded ? 'Hide results' : `View ${subTests.length > 0 ? `${subTests.length} parameters` : 'result'}`}</Text>
            <Ionicons name={expanded ? 'chevron-up' : 'chevron-down'} size={14} color={BLUE} />
          </TouchableOpacity>
        </>
      )}
    </View>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────

const TYPE_FILTERS: { key: TypeFilter; label: string }[] = [
  { key: 'ALL',     label: 'All Types' },
  { key: 'BLOOD',   label: 'Blood'    },
  { key: 'URINE',   label: 'Urine'    },
  { key: 'SWAB',    label: 'Swab'     },
  { key: 'CULTURE', label: 'Culture'  },
];

const STATUS_FILTERS: { key: StatusFilter; label: string }[] = [
  { key: 'ALL',             label: 'All'            },
  { key: 'COMPLETED',       label: 'Completed'      },
  { key: 'REQUIRES_REVIEW', label: 'Requires Review'},
];

export default function DoctorWebLabResults() {
  const { results, loading, error, refetch } = useDoctorLabResults();
  const { width } = useWindowDimensions();
  const twoCol = width >= 1200;

  const [typeFilter,   setTypeFilter]   = useState<TypeFilter>('ALL');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');
  const [search,       setSearch]       = useState('');

  const stats = useMemo(() => ({
    total:    results.length,
    completed: results.filter(r => r.status === 'COMPLETED').length,
    review:   results.filter(r => r.status === 'REQUIRES_REVIEW').length,
    critical: results.filter(r => r.is_critical).length,
    blood:    results.filter(r => r.test_type === 'BLOOD').length,
    abnormal: results.filter(r => {
      if (!r.result_data) return false;
      return Object.values(r.result_data).some(s => s.flag !== 'NORMAL');
    }).length,
  }), [results]);

  const filtered = useMemo(() => (
    results
      .filter(r => typeFilter === 'ALL'   || r.test_type === typeFilter)
      .filter(r => statusFilter === 'ALL' || r.status    === statusFilter)
      .filter(r => {
        if (!search) return true;
        const q = search.toLowerCase();
        return (
          r.test_name.toLowerCase().includes(q)   ||
          r.patient_name.toLowerCase().includes(q) ||
          r.doctor_name.toLowerCase().includes(q)  ||
          r.test_type.toLowerCase().includes(q)
        );
      })
  ), [results, typeFilter, statusFilter, search]);

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
          <Text style={styles.pageTitle}>Labs & Results</Text>
          <Text style={styles.pageSub}>Laboratory test results for all your patients</Text>
        </View>
        <TouchableOpacity style={styles.refreshBtn} onPress={refetch} activeOpacity={0.8}>
          <Ionicons name="refresh-outline" size={16} color={BLUE} />
          <Text style={styles.refreshText}>Refresh</Text>
        </TouchableOpacity>
      </View>

      {/* Stats */}
      <View style={styles.statsRow}>
        <StatCard icon="flask-outline"            iconBg="#EEF2FF" iconColor={BLUE}   label="Total"     value={stats.total}    />
        <StatCard icon="checkmark-circle-outline" iconBg="#D1FAE5" iconColor={GREEN}  label="Completed" value={stats.completed}/>
        <StatCard icon="eye-outline"              iconBg="#FEF3C7" iconColor={AMBER}  label="Review"    value={stats.review}   />
        <StatCard icon="alert-circle-outline"     iconBg="#FEE2E2" iconColor={RED}    label="Critical"  value={stats.critical} />
        <StatCard icon="water-outline"            iconBg="#FEE2E2" iconColor={RED}    label="Blood"     value={stats.blood}    />
        <StatCard icon="warning-outline"          iconBg="#FEF3C7" iconColor={AMBER}  label="Abnormal"  value={stats.abnormal} />
      </View>

      {/* Filters */}
      <View style={styles.filterCard}>
        <View style={styles.filterRow}>
          <View style={styles.pillGroup}>
            <Text style={styles.filterGroupLabel}>Type:</Text>
            {TYPE_FILTERS.map(f => {
              const tc = testTypeConfig(f.key as LabTestType);
              const active = typeFilter === f.key;
              return (
                <TouchableOpacity
                  key={f.key}
                  style={[styles.pill, active && { backgroundColor: (f.key === 'ALL' ? BLUE : tc.color) + '18', borderColor: f.key === 'ALL' ? BLUE : tc.color }]}
                  onPress={() => setTypeFilter(f.key)}
                  activeOpacity={0.7}
                >
                  <Text style={[styles.pillText, active && { color: f.key === 'ALL' ? BLUE : tc.color, fontWeight: '700' }]}>
                    {f.label}
                  </Text>
                </TouchableOpacity>
              );
            })}
          </View>
        </View>

        <View style={[styles.filterRow, { marginTop: 10 }]}>
          <View style={styles.pillGroup}>
            <Text style={styles.filterGroupLabel}>Status:</Text>
            {STATUS_FILTERS.map(f => {
              const active = statusFilter === f.key;
              const color  = f.key === 'COMPLETED' ? GREEN : f.key === 'REQUIRES_REVIEW' ? AMBER : BLUE;
              return (
                <TouchableOpacity
                  key={f.key}
                  style={[styles.pill, active && { backgroundColor: color + '18', borderColor: color }]}
                  onPress={() => setStatusFilter(f.key)}
                  activeOpacity={0.7}
                >
                  <Text style={[styles.pillText, active && { color, fontWeight: '700' }]}>
                    {f.label}
                  </Text>
                </TouchableOpacity>
              );
            })}
          </View>

          <View style={styles.searchBox}>
            <Ionicons name="search-outline" size={15} color={LGRAY} />
            <TextInput
              style={styles.searchInput}
              placeholder="Search test name, patient, doctor…"
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
        {filtered.length} result{filtered.length !== 1 ? 's' : ''}
      </Text>

      {/* Cards */}
      {filtered.length === 0 ? (
        <View style={styles.emptyState}>
          <Ionicons name="flask-outline" size={60} color={BORDER} />
          <Text style={styles.emptyTitle}>No lab results found</Text>
          <Text style={styles.emptySub}>Try adjusting your filters or search</Text>
        </View>
      ) : twoCol ? (
        <View style={styles.grid}>
          <View style={styles.col}>
            {filtered.filter((_, i) => i % 2 === 0).map(r => <LabCard key={r.result_id} result={r} />)}
          </View>
          <View style={styles.col}>
            {filtered.filter((_, i) => i % 2 === 1).map(r => <LabCard key={r.result_id} result={r} />)}
          </View>
        </View>
      ) : (
        <View style={{ gap: 14 }}>
          {filtered.map(r => <LabCard key={r.result_id} result={r} />)}
        </View>
      )}
    </ScrollView>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  scroll:    { flex: 1, backgroundColor: BG },
  container: { padding: 24, paddingBottom: 48 },
  center:    { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 12 },

  pageHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 24 },
  pageTitle:  { fontSize: 24, fontWeight: '700', color: TEXT, marginBottom: 4 },
  pageSub:    { fontSize: 14, color: GRAY },
  refreshBtn: {
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

  filterCard: { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER, borderRadius: 12, padding: 16, marginBottom: 16 },
  filterRow:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 10 },
  pillGroup:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 6 },
  filterGroupLabel: { fontSize: 12, color: GRAY, fontWeight: '600', marginRight: 2 },
  pill:       { paddingHorizontal: 13, paddingVertical: 7, borderRadius: 20, borderWidth: 1, borderColor: BORDER, backgroundColor: WHITE },
  pillText:   { fontSize: 13, color: GRAY, fontWeight: '500' },
  searchBox:  {
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

  criticalBanner: {
    flexDirection: 'row', alignItems: 'center', gap: 6,
    backgroundColor: '#FEE2E2', borderRadius: 8, paddingHorizontal: 10, paddingVertical: 6,
    borderWidth: 1, borderColor: '#FECACA', marginBottom: -4,
  },
  criticalBannerText: { fontSize: 12, color: RED, fontWeight: '700' },

  reviewBanner: {
    flexDirection: 'row', alignItems: 'center', gap: 6,
    backgroundColor: '#FEF3C7', borderRadius: 8, paddingHorizontal: 10, paddingVertical: 6,
    borderWidth: 1, borderColor: '#FDE68A', marginBottom: -4,
  },
  reviewBannerText: { fontSize: 12, color: AMBER, fontWeight: '600' },

  cardHeader:  { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8 },
  badgeRow:    { flexDirection: 'row', gap: 6, flexWrap: 'wrap' },
  badge:       { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 9, paddingVertical: 4, borderRadius: 20, borderWidth: 1 },
  badgeText:   { fontSize: 11, fontWeight: '600' },
  testDate:    { fontSize: 12, color: LGRAY },

  testNameBlock: { flexDirection: 'row', alignItems: 'center', gap: 14 },
  testIconWrap:  { width: 48, height: 48, borderRadius: 12, alignItems: 'center', justifyContent: 'center' },
  testName:      { flex: 1, fontSize: 16, fontWeight: '700', color: TEXT, lineHeight: 22 },

  simpleResultBox: {
    backgroundColor: BG, borderRadius: 10, padding: 14,
    borderWidth: 1, borderColor: BORDER, gap: 4,
  },
  simpleResultLabel: { fontSize: 10, color: LGRAY, textTransform: 'uppercase', letterSpacing: 0.5 },
  simpleResultValue: { fontSize: 18, fontWeight: '700', color: TEXT },
  simpleResultRef:   { fontSize: 12, color: GRAY, fontStyle: 'italic' },

  personRow:      { flexDirection: 'row', alignItems: 'center', gap: 12, flexWrap: 'wrap' },
  personChip:     { flexDirection: 'row', alignItems: 'center', gap: 8 },
  personDivider:  { width: 1, height: 28, backgroundColor: BORDER },
  miniAvatar:     { width: 30, height: 30, borderRadius: 15, alignItems: 'center', justifyContent: 'center' },
  miniAvatarText: { color: WHITE, fontSize: 10, fontWeight: '700' },
  personRole:     { fontSize: 10, color: LGRAY, textTransform: 'uppercase', letterSpacing: 0.5 },
  personName:     { fontSize: 12, fontWeight: '600', color: TEXT },
  specBadge:      { paddingHorizontal: 8, paddingVertical: 3, borderRadius: 6, backgroundColor: '#F3F4F6', borderWidth: 1, borderColor: BORDER },
  specText:       { fontSize: 11, color: GRAY },

  reviewedRow:  { flexDirection: 'row', alignItems: 'center', gap: 6 },
  reviewedText: { fontSize: 12, color: GRAY },

  subTestsBox:   {
    backgroundColor: BG, borderRadius: 10, borderWidth: 1, borderColor: BORDER,
    overflow: 'hidden',
  },
  subTestsTitle: {
    fontSize: 11, color: GRAY, fontWeight: '600', textTransform: 'uppercase',
    letterSpacing: 0.5, paddingHorizontal: 14, paddingTop: 12, paddingBottom: 8,
  },
  subRow: {
    paddingHorizontal: 14, paddingVertical: 10,
    borderTopWidth: 1, borderTopColor: BORDER, gap: 4,
  },
  subParam:     { fontSize: 13, fontWeight: '600', color: TEXT },
  subValueWrap: { flexDirection: 'row', alignItems: 'center', gap: 8, flexWrap: 'wrap' },
  subValue:     { fontSize: 13, color: TEXT },
  flagChip: {
    flexDirection: 'row', alignItems: 'center', gap: 3,
    paddingHorizontal: 7, paddingVertical: 2, borderRadius: 10, borderWidth: 1,
  },
  flagText:     { fontSize: 10, fontWeight: '700' },
  subReference: { fontSize: 11, color: LGRAY, fontStyle: 'italic' },
  subInterp:    { fontSize: 11, color: GRAY, marginTop: 2 },

  expandBtn:     { flexDirection: 'row', alignItems: 'center', gap: 4, alignSelf: 'flex-start' },
  expandBtnText: { fontSize: 13, color: BLUE, fontWeight: '500' },

  emptyState: { alignItems: 'center', gap: 10, paddingVertical: 64 },
  emptyTitle: { fontSize: 18, fontWeight: '600', color: TEXT },
  emptySub:   { fontSize: 14, color: GRAY },

  errorText:    { fontSize: 15, color: GRAY, textAlign: 'center' },
  retryBtn:     { backgroundColor: BLUE, borderRadius: 8, paddingHorizontal: 20, paddingVertical: 10 },
  retryBtnText: { color: WHITE, fontWeight: '600', fontSize: 14 },
});
