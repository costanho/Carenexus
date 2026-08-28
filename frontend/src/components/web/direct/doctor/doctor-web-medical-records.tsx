import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import {
  ActivityIndicator, ScrollView, StyleSheet, Text,
  TextInput, TouchableOpacity, View, useWindowDimensions,
} from 'react-native';
import { type DoctorMedicalRecord, type RecordType, useDoctorMedicalRecords } from '@/hooks/use-doctor-medical-records';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const LGRAY  = '#9CA3AF';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const BLUE   = '#4F46E5';
const GREEN  = '#10B981';
const TEAL   = '#0D9488';
const AMBER  = '#F59E0B';
const RED    = '#EF4444';
const PURPLE = '#8B5CF6';
const NAVY   = '#1E3A5F';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type TypeFilter   = 'ALL' | RecordType;

// ─── Helpers ──────────────────────────────────────────────────────────────────

function typeConfig(t: RecordType): { label: string; color: string; bg: string; border: string; icon: IoniconsName } {
  switch (t) {
    case 'CONSULT':      return { label: 'Consultation',  color: BLUE,   bg: '#EEF2FF', border: '#C7D2FE', icon: 'stethoscope'          as any };
    case 'LAB':          return { label: 'Lab Result',    color: TEAL,   bg: '#CCFBF1', border: '#99F6E4', icon: 'flask-outline'             };
    case 'PRESCRIPTION': return { label: 'Prescription',  color: GREEN,  bg: '#D1FAE5', border: '#6EE7B7', icon: 'medkit-outline'            };
    case 'REFERRAL':     return { label: 'Referral',      color: NAVY,   bg: '#DBEAFE', border: '#93C5FD', icon: 'git-compare-outline'       };
    case 'NOTE':         return { label: 'Note',          color: AMBER,  bg: '#FEF3C7', border: '#FDE68A', icon: 'document-text-outline'     };
    case 'IMAGING':      return { label: 'Imaging',       color: PURPLE, bg: '#EDE9FE', border: '#C4B5FD', icon: 'image-outline'             };
    default:             return { label: t,               color: GRAY,   bg: '#F3F4F6', border: BORDER,    icon: 'document-outline'          };
  }
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
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

// ─── Record Card ─────────────────────────────────────────────────────────────

function RecordCard({ record }: { record: DoctorMedicalRecord }) {
  const [expanded, setExpanded] = useState(false);
  const tc  = typeConfig(record.record_type);
  const pac = avatarColor(record.patient_name);
  const dac = avatarColor(record.doctor_name);
  const hasExtra = !!(record.treatment_plan || record.observations || record.icd_codes.length > 0);

  return (
    <View style={[styles.card, { borderLeftColor: tc.color }]}>
      {/* Card header */}
      <View style={styles.cardHeader}>
        {/* Type badge */}
        <View style={[styles.typeBadge, { backgroundColor: tc.bg, borderColor: tc.border }]}>
          <Ionicons name={tc.icon} size={12} color={tc.color} />
          <Text style={[styles.typeBadgeText, { color: tc.color }]}>{tc.label}</Text>
        </View>

        {/* Date + time */}
        <View style={styles.dateRow}>
          <Ionicons name="calendar-outline" size={12} color={LGRAY} />
          <Text style={styles.dateText}>{formatDate(record.created_at)}</Text>
          <Text style={styles.dateText}>·</Text>
          <Ionicons name="time-outline" size={12} color={LGRAY} />
          <Text style={styles.dateText}>{formatTime(record.created_at)}</Text>
        </View>
      </View>

      {/* Title */}
      <Text style={styles.cardTitle}>{record.title}</Text>

      {/* Patient + doctor row */}
      <View style={styles.personRow}>
        {/* Patient */}
        <View style={styles.personChip}>
          <View style={[styles.miniAvatar, { backgroundColor: pac }]}>
            <Text style={styles.miniAvatarText}>{initials(record.patient_name)}</Text>
          </View>
          <View>
            <Text style={styles.personRole}>Patient</Text>
            <Text style={styles.personName}>{record.patient_name}</Text>
          </View>
        </View>

        <View style={styles.personDivider} />

        {/* Doctor */}
        <View style={styles.personChip}>
          <View style={[styles.miniAvatar, { backgroundColor: dac }]}>
            <Text style={styles.miniAvatarText}>{initials(record.doctor_name)}</Text>
          </View>
          <View>
            <Text style={styles.personRole}>Author</Text>
            <Text style={styles.personName}>{record.doctor_name}</Text>
          </View>
        </View>

        {record.doctor_specialization && (
          <View style={[styles.specBadge]}>
            <Text style={styles.specText}>{record.doctor_specialization}</Text>
          </View>
        )}
      </View>

      {/* Description */}
      {!!record.description && (
        <View style={styles.descBox}>
          <Text style={styles.descText} numberOfLines={expanded ? undefined : 3}>
            {record.description}
          </Text>
        </View>
      )}

      {/* Expanded content */}
      {expanded && (
        <>
          {!!record.treatment_plan && (
            <View style={[styles.expandSection, { borderLeftColor: GREEN }]}>
              <View style={styles.expandLabelRow}>
                <Ionicons name="clipboard-outline" size={13} color={GREEN} />
                <Text style={[styles.expandLabel, { color: GREEN }]}>Treatment Plan</Text>
              </View>
              <Text style={styles.expandText}>{record.treatment_plan}</Text>
            </View>
          )}

          {!!record.observations && (
            <View style={[styles.expandSection, { borderLeftColor: BLUE }]}>
              <View style={styles.expandLabelRow}>
                <Ionicons name="eye-outline" size={13} color={BLUE} />
                <Text style={[styles.expandLabel, { color: BLUE }]}>Observations</Text>
              </View>
              <Text style={styles.expandText}>{record.observations}</Text>
            </View>
          )}

          {record.icd_codes.length > 0 && (
            <View style={styles.icdSection}>
              <View style={styles.expandLabelRow}>
                <Ionicons name="code-slash-outline" size={13} color={PURPLE} />
                <Text style={[styles.expandLabel, { color: PURPLE }]}>ICD Codes</Text>
              </View>
              <View style={styles.icdRow}>
                {record.icd_codes.map((c, i) => (
                  <View key={i} style={styles.icdChip}>
                    <Text style={styles.icdCode}>{c.code}</Text>
                    <Text style={styles.icdDesc}>{c.description}</Text>
                  </View>
                ))}
              </View>
            </View>
          )}
        </>
      )}

      {/* Footer */}
      {hasExtra && (
        <TouchableOpacity
          style={styles.expandBtn}
          onPress={() => setExpanded(e => !e)}
          activeOpacity={0.7}
        >
          <Text style={styles.expandBtnText}>{expanded ? 'Show less' : 'View full record'}</Text>
          <Ionicons name={expanded ? 'chevron-up' : 'chevron-down'} size={14} color={BLUE} />
        </TouchableOpacity>
      )}
    </View>
  );
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

// ─── Main Component ───────────────────────────────────────────────────────────

const TYPE_FILTERS: { key: TypeFilter; label: string; color: string }[] = [
  { key: 'ALL',          label: 'All',          color: BLUE   },
  { key: 'CONSULT',      label: 'Consultations', color: BLUE   },
  { key: 'LAB',          label: 'Lab Results',  color: TEAL   },
  { key: 'PRESCRIPTION', label: 'Prescriptions', color: GREEN  },
  { key: 'REFERRAL',     label: 'Referrals',    color: NAVY   },
  { key: 'NOTE',         label: 'Notes',        color: AMBER  },
  { key: 'IMAGING',      label: 'Imaging',      color: PURPLE },
];

export default function DoctorWebMedicalRecords() {
  const { records, loading, error, refetch } = useDoctorMedicalRecords();
  const { width } = useWindowDimensions();
  const twoCol = width >= 1200;

  const [typeFilter, setTypeFilter] = useState<TypeFilter>('ALL');
  const [search,     setSearch]     = useState('');

  const stats = useMemo(() => ({
    total:        records.length,
    consults:     records.filter(r => r.record_type === 'CONSULT').length,
    labs:         records.filter(r => r.record_type === 'LAB').length,
    prescriptions:records.filter(r => r.record_type === 'PRESCRIPTION').length,
    referrals:    records.filter(r => r.record_type === 'REFERRAL').length,
    notes:        records.filter(r => r.record_type === 'NOTE').length,
  }), [records]);

  const filtered = useMemo(() => (
    records
      .filter(r => typeFilter === 'ALL' || r.record_type === typeFilter)
      .filter(r => !search
        || r.title.toLowerCase().includes(search.toLowerCase())
        || r.patient_name.toLowerCase().includes(search.toLowerCase())
        || r.doctor_name.toLowerCase().includes(search.toLowerCase())
        || r.icd_codes.some(c => c.code.toLowerCase().includes(search.toLowerCase()) || c.description.toLowerCase().includes(search.toLowerCase()))
      )
  ), [records, typeFilter, search]);

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
          <Text style={styles.pageTitle}>Medical Records</Text>
          <Text style={styles.pageSub}>All records for patients associated with you</Text>
        </View>
        <TouchableOpacity style={styles.refreshBtn} onPress={refetch} activeOpacity={0.8}>
          <Ionicons name="refresh-outline" size={16} color={BLUE} />
          <Text style={styles.refreshText}>Refresh</Text>
        </TouchableOpacity>
      </View>

      {/* Stats */}
      <View style={styles.statsRow}>
        <StatCard icon="folder-open-outline"   iconBg="#EEF2FF" iconColor={BLUE}   label="Total"         value={stats.total}         />
        <StatCard icon="stethoscope"            iconBg="#EEF2FF" iconColor={BLUE}   label="Consultations" value={stats.consults}      />
        <StatCard icon="flask-outline"          iconBg="#CCFBF1" iconColor={TEAL}   label="Lab Results"   value={stats.labs}          />
        <StatCard icon="medkit-outline"         iconBg="#D1FAE5" iconColor={GREEN}  label="Prescriptions" value={stats.prescriptions} />
        <StatCard icon="git-compare-outline"    iconBg="#DBEAFE" iconColor={NAVY}   label="Referrals"     value={stats.referrals}     />
        <StatCard icon="document-text-outline"  iconBg="#FEF3C7" iconColor={AMBER}  label="Notes"         value={stats.notes}         />
      </View>

      {/* Filters */}
      <View style={styles.filterCard}>
        <View style={styles.filterRow}>
          <View style={styles.pillGroup}>
            {TYPE_FILTERS.map(f => (
              <TouchableOpacity
                key={f.key}
                style={[styles.pill, typeFilter === f.key && { backgroundColor: f.color + '18', borderColor: f.color }]}
                onPress={() => setTypeFilter(f.key)}
                activeOpacity={0.7}
              >
                <Text style={[styles.pillText, typeFilter === f.key && { color: f.color, fontWeight: '700' }]}>
                  {f.label}
                </Text>
              </TouchableOpacity>
            ))}
          </View>

          <View style={styles.searchBox}>
            <Ionicons name="search-outline" size={15} color={LGRAY} />
            <TextInput
              style={styles.searchInput}
              placeholder="Search by title, patient, ICD code…"
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
      <Text style={styles.resultCount}>{filtered.length} record{filtered.length !== 1 ? 's' : ''}</Text>

      {/* Records grid / list */}
      {filtered.length === 0 ? (
        <View style={styles.emptyState}>
          <Ionicons name="folder-open-outline" size={60} color={BORDER} />
          <Text style={styles.emptyTitle}>No records found</Text>
          <Text style={styles.emptySub}>Try adjusting your filters or search</Text>
        </View>
      ) : twoCol ? (
        <View style={styles.grid}>
          <View style={styles.col}>
            {filtered.filter((_, i) => i % 2 === 0).map(r => <RecordCard key={r.record_id} record={r} />)}
          </View>
          <View style={styles.col}>
            {filtered.filter((_, i) => i % 2 === 1).map(r => <RecordCard key={r.record_id} record={r} />)}
          </View>
        </View>
      ) : (
        <View style={{ gap: 14 }}>
          {filtered.map(r => <RecordCard key={r.record_id} record={r} />)}
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
    borderRadius: 12, padding: 14, gap: 5, alignItems: 'flex-start',
  },
  statIconBg: { width: 36, height: 36, borderRadius: 8, alignItems: 'center', justifyContent: 'center' },
  statValue:  { fontSize: 24, fontWeight: '700', color: TEXT },
  statLabel:  { fontSize: 11, color: GRAY },

  filterCard: { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER, borderRadius: 12, padding: 16, marginBottom: 16 },
  filterRow:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 10 },
  pillGroup:  { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  pill:       { paddingHorizontal: 12, paddingVertical: 6, borderRadius: 20, borderWidth: 1, borderColor: BORDER, backgroundColor: WHITE },
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
    padding: 18, gap: 12,
  },
  cardHeader:  { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8 },
  typeBadge:   { flexDirection: 'row', alignItems: 'center', gap: 5, paddingHorizontal: 10, paddingVertical: 4, borderRadius: 20, borderWidth: 1 },
  typeBadgeText: { fontSize: 11, fontWeight: '700' },
  dateRow:     { flexDirection: 'row', alignItems: 'center', gap: 4 },
  dateText:    { fontSize: 11, color: LGRAY },
  cardTitle:   { fontSize: 15, fontWeight: '700', color: TEXT, lineHeight: 22 },

  personRow:     { flexDirection: 'row', alignItems: 'center', gap: 12, flexWrap: 'wrap' },
  personChip:    { flexDirection: 'row', alignItems: 'center', gap: 8 },
  personDivider: { width: 1, height: 28, backgroundColor: BORDER },
  miniAvatar:    { width: 30, height: 30, borderRadius: 15, alignItems: 'center', justifyContent: 'center' },
  miniAvatarText:{ color: WHITE, fontSize: 10, fontWeight: '700' },
  personRole:    { fontSize: 10, color: LGRAY, textTransform: 'uppercase', letterSpacing: 0.5 },
  personName:    { fontSize: 12, fontWeight: '600', color: TEXT },
  specBadge:     { paddingHorizontal: 8, paddingVertical: 3, borderRadius: 6, backgroundColor: '#F3F4F6', borderWidth: 1, borderColor: BORDER },
  specText:      { fontSize: 11, color: GRAY },

  descBox:  { backgroundColor: BG, borderRadius: 8, padding: 12, borderWidth: 1, borderColor: BORDER },
  descText: { fontSize: 13, color: GRAY, lineHeight: 20 },

  expandSection: { borderLeftWidth: 3, paddingLeft: 12, gap: 6 },
  expandLabelRow:{ flexDirection: 'row', alignItems: 'center', gap: 5 },
  expandLabel:   { fontSize: 11, fontWeight: '700', textTransform: 'uppercase', letterSpacing: 0.5 },
  expandText:    { fontSize: 13, color: GRAY, lineHeight: 20 },

  icdSection: { gap: 8 },
  icdRow:     { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  icdChip:    {
    flexDirection: 'row', alignItems: 'center', gap: 6,
    backgroundColor: '#EDE9FE', borderWidth: 1, borderColor: '#C4B5FD',
    borderRadius: 8, paddingHorizontal: 10, paddingVertical: 5,
  },
  icdCode:    { fontSize: 11, fontWeight: '700', color: PURPLE },
  icdDesc:    { fontSize: 11, color: GRAY },

  expandBtn:     { flexDirection: 'row', alignItems: 'center', gap: 4, alignSelf: 'flex-start', paddingTop: 4 },
  expandBtnText: { fontSize: 13, color: BLUE, fontWeight: '500' },

  emptyState: { alignItems: 'center', gap: 10, paddingVertical: 64 },
  emptyTitle: { fontSize: 18, fontWeight: '600', color: TEXT },
  emptySub:   { fontSize: 14, color: GRAY },

  errorText:    { fontSize: 15, color: GRAY, textAlign: 'center' },
  retryBtn:     { backgroundColor: BLUE, borderRadius: 8, paddingHorizontal: 20, paddingVertical: 10 },
  retryBtnText: { color: WHITE, fontWeight: '600', fontSize: 14 },
});
