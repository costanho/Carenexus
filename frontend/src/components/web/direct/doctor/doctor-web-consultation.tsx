import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { type DoctorConsultation, useDoctorConsultations } from '@/hooks/use-doctor-consultations';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const LGRAY  = '#9CA3AF';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const BG2    = '#F3F4F6';
const BLUE   = '#4F46E5';
const GREEN  = '#10B981';
const ORANGE = '#F59E0B';
const RED    = '#EF4444';
const TEAL   = '#0D9488';
const AMBER  = '#D97706';

type IoniconsName    = React.ComponentProps<typeof Ionicons>['name'];
type ConsultStatus   = DoctorConsultation['status'];
type StatusFilter    = 'ALL' | ConsultStatus;

// ─── Helpers ──────────────────────────────────────────────────────────────────

function statusConfig(s: ConsultStatus) {
  switch (s) {
    case 'DRAFT':       return { bg: '#FEF3C7', color: AMBER,  icon: 'document-outline'       as IoniconsName, label: 'Draft'       };
    case 'IN_PROGRESS': return { bg: '#CCFBF1', color: TEAL,  icon: 'sync-outline'            as IoniconsName, label: 'In Progress' };
    case 'COMPLETED':   return { bg: '#D1FAE5', color: GREEN,  icon: 'checkmark-circle-outline' as IoniconsName, label: 'Completed'   };
  }
}

function apptTypeConfig(t: DoctorConsultation['appointment_type']) {
  switch (t) {
    case 'IN_PERSON': return { icon: 'location-outline'  as IoniconsName, label: 'In Person', color: GREEN,  bg: '#D1FAE5' };
    case 'VIDEO':     return { icon: 'videocam-outline'   as IoniconsName, label: 'Video',     color: BLUE,   bg: '#EEF2FF' };
    case 'PHONE':     return { icon: 'call-outline'       as IoniconsName, label: 'Phone',     color: ORANGE, bg: '#FEF3C7' };
    default:          return { icon: 'calendar-outline'   as IoniconsName, label: 'Unknown',   color: GRAY,   bg: BG2       };
  }
}

function formatDateTime(iso: string | null) {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-GB', {
    day: 'numeric', month: 'short', year: 'numeric',
    hour: '2-digit', minute: '2-digit', hour12: true,
  });
}

function formatDateShort(iso: string | null) {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

function initials(name: string) {
  return name.split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
}

const AVATAR_COLORS = ['#4F46E5', '#0D9488', '#F59E0B', '#EF4444', '#8B5CF6', '#10B981'];
function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
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

// ─── Clinical Section ─────────────────────────────────────────────────────────

function ClinicalRow({ icon, iconColor, label, content }: {
  icon: IoniconsName; iconColor: string; label: string; content: string;
}) {
  return (
    <View style={styles.clinicalRow}>
      <View style={styles.clinicalLabelRow}>
        <View style={[styles.clinicalIconBg, { backgroundColor: iconColor + '18' }]}>
          <Ionicons name={icon} size={13} color={iconColor} />
        </View>
        <Text style={styles.clinicalLabel}>{label}</Text>
      </View>
      <Text style={styles.clinicalContent}>{content}</Text>
    </View>
  );
}

// ─── Consultation Card ────────────────────────────────────────────────────────

function ConsultCard({ consultation }: { consultation: DoctorConsultation }) {
  const [expanded, setExpanded] = useState(false);
  const sc = statusConfig(consultation.status);
  const tc = apptTypeConfig(consultation.appointment_type);
  const ac = avatarColor(consultation.patient_name);

  const dateLabel = consultation.completed_at
    ? formatDateShort(consultation.completed_at)
    : consultation.started_at
      ? formatDateShort(consultation.started_at)
      : formatDateShort(consultation.created_at);

  const hasDetails =
    consultation.chief_complaint ||
    consultation.history_of_present_illness ||
    consultation.physical_examination ||
    consultation.diagnosis ||
    consultation.treatment_plan ||
    consultation.clinical_notes ||
    consultation.follow_up_instructions;

  return (
    <View style={styles.card}>
      {/* Card Header */}
      <View style={styles.cardHeader}>
        {/* Avatar */}
        <View style={[styles.avatar, { backgroundColor: ac }]}>
          <Text style={styles.avatarText}>{initials(consultation.patient_name)}</Text>
        </View>

        {/* Patient + meta */}
        <View style={styles.cardMeta}>
          <Text style={styles.patientName}>{consultation.patient_name}</Text>
          <View style={styles.metaRow}>
            <Text style={styles.metaDate}>{dateLabel}</Text>
            {!!consultation.duration_minutes && (
              <View style={styles.metaPill}>
                <Ionicons name="time-outline" size={11} color={GRAY} />
                <Text style={styles.metaPillText}>{consultation.duration_minutes} min</Text>
              </View>
            )}
          </View>
        </View>

        {/* Badges */}
        <View style={styles.badgeGroup}>
          <View style={[styles.badge, { backgroundColor: tc.bg }]}>
            <Ionicons name={tc.icon} size={11} color={tc.color} />
            <Text style={[styles.badgeText, { color: tc.color }]}>{tc.label}</Text>
          </View>
          <View style={[styles.badge, { backgroundColor: sc.bg }]}>
            <Ionicons name={sc.icon} size={11} color={sc.color} />
            <Text style={[styles.badgeText, { color: sc.color }]}>{sc.label}</Text>
          </View>
          {!!consultation.follow_up_required && (
            <View style={[styles.badge, { backgroundColor: '#FEE2E2' }]}>
              <Ionicons name="arrow-forward-circle-outline" size={11} color={RED} />
              <Text style={[styles.badgeText, { color: RED }]}>Follow-up</Text>
            </View>
          )}
        </View>
      </View>

      {/* Chief complaint / clinical notes preview */}
      {!!(consultation.chief_complaint || consultation.clinical_notes) && (
        <View style={styles.previewBox}>
          <Text style={styles.previewLabel}>
            {consultation.chief_complaint ? 'Chief Complaint' : 'Clinical Notes'}
          </Text>
          <Text style={styles.previewText} numberOfLines={expanded ? undefined : 2}>
            {consultation.chief_complaint ?? consultation.clinical_notes}
          </Text>
        </View>
      )}

      {/* Diagnosis preview (completed) */}
      {!!consultation.diagnosis && (
        <View style={[styles.previewBox, { backgroundColor: '#F0FDF4', borderColor: '#BBF7D0' }]}>
          <Text style={[styles.previewLabel, { color: GREEN }]}>Diagnosis</Text>
          <Text style={styles.previewText} numberOfLines={expanded ? undefined : 2}>
            {consultation.diagnosis}
          </Text>
        </View>
      )}

      {/* Expanded clinical details */}
      {expanded && (
        <View style={styles.expandedSection}>
          <View style={styles.sectionDivider} />
          <Text style={styles.sectionTitle}>Clinical Details</Text>

          {!!consultation.history_of_present_illness && (
            <ClinicalRow
              icon="journal-outline" iconColor={BLUE}
              label="History of Present Illness"
              content={consultation.history_of_present_illness}
            />
          )}
          {!!consultation.physical_examination && (
            <ClinicalRow
              icon="body-outline" iconColor="#8B5CF6"
              label="Physical Examination"
              content={consultation.physical_examination}
            />
          )}
          {!!consultation.clinical_notes && (
            <ClinicalRow
              icon="clipboard-outline" iconColor={TEAL}
              label="Clinical Notes"
              content={consultation.clinical_notes}
            />
          )}
          {!!consultation.treatment_plan && (
            <ClinicalRow
              icon="medkit-outline" iconColor={ORANGE}
              label="Treatment Plan"
              content={consultation.treatment_plan}
            />
          )}
          {!!consultation.follow_up_instructions && (
            <ClinicalRow
              icon="arrow-forward-circle-outline" iconColor={RED}
              label="Follow-up Instructions"
              content={consultation.follow_up_instructions}
            />
          )}

          {/* Follow-up date */}
          {!!consultation.follow_up_date && (
            <View style={styles.followUpRow}>
              <Ionicons name="calendar-outline" size={14} color={RED} />
              <Text style={styles.followUpText}>
                Follow-up due: <Text style={{ fontWeight: '600' }}>{formatDateShort(consultation.follow_up_date)}</Text>
              </Text>
            </View>
          )}

          {/* Timestamps */}
          <View style={styles.timestampRow}>
            {!!consultation.started_at && (
              <Text style={styles.timestamp}>Started: {formatDateTime(consultation.started_at)}</Text>
            )}
            {!!consultation.completed_at && (
              <Text style={styles.timestamp}>Completed: {formatDateTime(consultation.completed_at)}</Text>
            )}
          </View>
        </View>
      )}

      {/* Expand toggle */}
      {hasDetails && (
        <TouchableOpacity
          style={styles.expandBtn}
          onPress={() => setExpanded(e => !e)}
          activeOpacity={0.7}
        >
          <Text style={styles.expandBtnText}>{expanded ? 'Show less' : 'View full details'}</Text>
          <Ionicons name={expanded ? 'chevron-up' : 'chevron-down'} size={14} color={BLUE} />
        </TouchableOpacity>
      )}
    </View>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────

export default function DoctorWebConsultation() {
  const { consultations, loading, error, refetch } = useDoctorConsultations();
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');
  const [search,       setSearch]       = useState('');

  const stats = useMemo(() => ({
    total:      consultations.length,
    completed:  consultations.filter(c => c.status === 'COMPLETED').length,
    inProgress: consultations.filter(c => c.status === 'IN_PROGRESS').length,
    draft:      consultations.filter(c => c.status === 'DRAFT').length,
    followUp:   consultations.filter(c => c.follow_up_required === 1).length,
  }), [consultations]);

  const filtered = useMemo(() => {
    return consultations
      .filter(c => statusFilter === 'ALL' || c.status === statusFilter)
      .filter(c => !search || c.patient_name.toLowerCase().includes(search.toLowerCase()));
  }, [consultations, statusFilter, search]);

  const statusFilters: { key: StatusFilter; label: string }[] = [
    { key: 'ALL',         label: 'All'         },
    { key: 'COMPLETED',   label: 'Completed'   },
    { key: 'IN_PROGRESS', label: 'In Progress' },
    { key: 'DRAFT',       label: 'Draft'       },
  ];

  if (loading) {
    return <View style={styles.center}><ActivityIndicator size="large" color={BLUE} /></View>;
  }

  if (error) {
    return (
      <View style={styles.center}>
        <Ionicons name="alert-circle-outline" size={48} color={RED} />
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
          <Text style={styles.pageTitle}>Consultations</Text>
          <Text style={styles.pageSub}>Your full consultation history and active sessions</Text>
        </View>
        <TouchableOpacity style={styles.refreshBtn} onPress={refetch} activeOpacity={0.8}>
          <Ionicons name="refresh-outline" size={16} color={BLUE} />
          <Text style={styles.refreshText}>Refresh</Text>
        </TouchableOpacity>
      </View>

      {/* Stats Row */}
      <View style={styles.statsRow}>
        <StatCard icon="document-text-outline"   iconBg="#EEF2FF" iconColor={BLUE}  label="Total"       value={stats.total}      />
        <StatCard icon="checkmark-circle-outline" iconBg="#D1FAE5" iconColor={GREEN} label="Completed"   value={stats.completed}  />
        <StatCard icon="sync-outline"            iconBg="#CCFBF1" iconColor={TEAL}  label="In Progress" value={stats.inProgress} />
        <StatCard icon="document-outline"        iconBg="#FEF3C7" iconColor={AMBER} label="Draft"       value={stats.draft}      />
        <StatCard icon="arrow-forward-circle-outline" iconBg="#FEE2E2" iconColor={RED} label="Follow-up"  value={stats.followUp}   />
      </View>

      {/* Filter + Search */}
      <View style={styles.filterCard}>
        <View style={styles.filterRow}>
          {/* Status pills */}
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 8 }}>
            {statusFilters.map(f => (
              <TouchableOpacity
                key={f.key}
                style={[styles.pill, statusFilter === f.key && styles.pillActive]}
                onPress={() => setStatusFilter(f.key)}
                activeOpacity={0.7}
              >
                <Text style={[styles.pillText, statusFilter === f.key && styles.pillTextActive]}>{f.label}</Text>
              </TouchableOpacity>
            ))}
          </ScrollView>

          {/* Search */}
          <View style={styles.searchBox}>
            <Ionicons name="search-outline" size={16} color={LGRAY} />
            <TextInput
              style={styles.searchInput}
              placeholder="Search patient…"
              placeholderTextColor={LGRAY}
              value={search}
              onChangeText={setSearch}
            />
            {!!search && (
              <TouchableOpacity onPress={() => setSearch('')} activeOpacity={0.7}>
                <Ionicons name="close-circle" size={16} color={LGRAY} />
              </TouchableOpacity>
            )}
          </View>
        </View>
      </View>

      {/* Result count */}
      <Text style={styles.resultCount}>{filtered.length} consultation{filtered.length !== 1 ? 's' : ''}</Text>

      {/* List */}
      {filtered.length === 0 ? (
        <View style={styles.emptyState}>
          <Ionicons name="document-text-outline" size={56} color={BORDER} />
          <Text style={styles.emptyTitle}>No consultations found</Text>
          <Text style={styles.emptySub}>Try adjusting your filters</Text>
        </View>
      ) : (
        <View style={styles.list}>
          {filtered.map(c => <ConsultCard key={c.consultation_id} consultation={c} />)}
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

  statsRow: { flexDirection: 'row', gap: 12, marginBottom: 24, flexWrap: 'wrap' },
  statCard: {
    flex: 1, minWidth: 100,
    backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER,
    borderRadius: 12, padding: 14, gap: 6,
  },
  statIconBg: { width: 38, height: 38, borderRadius: 9, alignItems: 'center', justifyContent: 'center' },
  statValue:  { fontSize: 26, fontWeight: '700', color: TEXT },
  statLabel:  { fontSize: 12, color: GRAY },

  filterCard: { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER, borderRadius: 12, padding: 16, marginBottom: 16 },
  filterRow:  { flexDirection: 'row', alignItems: 'center', gap: 12, flexWrap: 'wrap' },
  pill:       { paddingHorizontal: 14, paddingVertical: 7, borderRadius: 20, borderWidth: 1, borderColor: BORDER, backgroundColor: WHITE },
  pillActive: { backgroundColor: '#EEF2FF', borderColor: BLUE },
  pillText:   { fontSize: 13, color: GRAY, fontWeight: '500' },
  pillTextActive: { color: BLUE },
  searchBox: {
    flex: 1, minWidth: 180,
    flexDirection: 'row', alignItems: 'center', gap: 8,
    borderWidth: 1, borderColor: BORDER, borderRadius: 8,
    paddingHorizontal: 12, paddingVertical: 8, backgroundColor: BG,
  },
  searchInput: { flex: 1, fontSize: 13, color: TEXT, outlineStyle: 'none' } as any,

  resultCount: { fontSize: 13, color: GRAY, marginBottom: 12, fontWeight: '500' },

  list: { gap: 14 },

  // Card
  card: {
    backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER,
    borderRadius: 16, overflow: 'hidden',
  },
  cardHeader: {
    flexDirection: 'row', alignItems: 'flex-start',
    gap: 12, padding: 16,
  },
  avatar: {
    width: 44, height: 44, borderRadius: 22,
    alignItems: 'center', justifyContent: 'center', flexShrink: 0,
  },
  avatarText: { color: WHITE, fontSize: 15, fontWeight: '700' },
  cardMeta:   { flex: 1, gap: 4 },
  patientName:{ fontSize: 16, fontWeight: '700', color: TEXT },
  metaRow:    { flexDirection: 'row', alignItems: 'center', gap: 8 },
  metaDate:   { fontSize: 12, color: GRAY },
  metaPill:   { flexDirection: 'row', alignItems: 'center', gap: 3 },
  metaPillText: { fontSize: 11, color: GRAY },
  badgeGroup: { flexDirection: 'row', flexWrap: 'wrap', gap: 5, flexShrink: 0, justifyContent: 'flex-end' },
  badge:      { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 8, paddingVertical: 3, borderRadius: 20 },
  badgeText:  { fontSize: 11, fontWeight: '600' },

  previewBox: {
    marginHorizontal: 16, marginBottom: 12,
    backgroundColor: BG, borderWidth: 1, borderColor: BORDER,
    borderRadius: 10, padding: 12,
  },
  previewLabel: { fontSize: 11, fontWeight: '700', color: GRAY, textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 6 },
  previewText:  { fontSize: 13, color: TEXT, lineHeight: 20 },

  expandedSection: { paddingHorizontal: 16, paddingBottom: 4 },
  sectionDivider:  { height: 1, backgroundColor: BORDER, marginBottom: 14 },
  sectionTitle:    { fontSize: 13, fontWeight: '700', color: TEXT, marginBottom: 12, textTransform: 'uppercase', letterSpacing: 0.5 },

  clinicalRow:      { marginBottom: 14 },
  clinicalLabelRow: { flexDirection: 'row', alignItems: 'center', gap: 7, marginBottom: 6 },
  clinicalIconBg:   { width: 22, height: 22, borderRadius: 6, alignItems: 'center', justifyContent: 'center' },
  clinicalLabel:    { fontSize: 12, fontWeight: '700', color: TEXT },
  clinicalContent:  { fontSize: 13, color: GRAY, lineHeight: 20, paddingLeft: 29 },

  followUpRow: { flexDirection: 'row', alignItems: 'center', gap: 6, marginBottom: 12, backgroundColor: '#FEE2E2', borderRadius: 8, padding: 10 },
  followUpText:{ fontSize: 12, color: RED },

  timestampRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 16, marginBottom: 14 },
  timestamp:    { fontSize: 11, color: LGRAY },

  expandBtn: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center',
    gap: 4, paddingVertical: 12,
    borderTopWidth: 1, borderTopColor: BORDER,
    backgroundColor: BG,
  },
  expandBtnText: { fontSize: 13, color: BLUE, fontWeight: '500' },

  emptyState: { alignItems: 'center', gap: 8, paddingVertical: 64 },
  emptyTitle: { fontSize: 18, fontWeight: '600', color: TEXT },
  emptySub:   { fontSize: 14, color: GRAY },

  errorText:    { fontSize: 15, color: GRAY, textAlign: 'center' },
  retryBtn:     { backgroundColor: BLUE, borderRadius: 8, paddingHorizontal: 20, paddingVertical: 10 },
  retryBtnText: { color: WHITE, fontWeight: '600', fontSize: 14 },
});
