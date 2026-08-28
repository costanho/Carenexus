import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import {
  ActivityIndicator, ScrollView, StyleSheet, Text,
  TouchableOpacity, View, useWindowDimensions,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { type DoctorConsultation, useDoctorConsultations } from '@/hooks/use-doctor-consultations';

const NAVY   = '#0D1B2E';
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

type IoniconsName  = React.ComponentProps<typeof Ionicons>['name'];
type ConsultStatus = DoctorConsultation['status'];
type StatusFilter  = 'ALL' | ConsultStatus;

function useScale() {
  const { width, height } = useWindowDimensions();
  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));
  return { s, sh };
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function statusConfig(st: ConsultStatus) {
  switch (st) {
    case 'DRAFT':       return { bg: '#FEF3C7', color: AMBER,  icon: 'document-outline'          as IoniconsName, label: 'Draft'       };
    case 'IN_PROGRESS': return { bg: '#CCFBF1', color: TEAL,  icon: 'sync-outline'               as IoniconsName, label: 'In Progress' };
    case 'COMPLETED':   return { bg: '#D1FAE5', color: GREEN,  icon: 'checkmark-circle-outline'  as IoniconsName, label: 'Completed'   };
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

function formatDateShort(iso: string | null) {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

function formatDateTime(iso: string | null) {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-GB', {
    day: 'numeric', month: 'short', year: 'numeric',
    hour: '2-digit', minute: '2-digit', hour12: true,
  });
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

function StatCard({ icon, iconBg, iconColor, label, value, s }: {
  icon: IoniconsName; iconBg: string; iconColor: string;
  label: string; value: number; s: (n: number) => number;
}) {
  return (
    <View style={[styles.statCard, { width: s(115), borderRadius: s(12), padding: s(12) }]}>
      <View style={[styles.statIconBg, { backgroundColor: iconBg, width: s(34), height: s(34), borderRadius: s(9) }]}>
        <Ionicons name={icon} size={s(16)} color={iconColor} />
      </View>
      <Text style={[styles.statValue, { fontSize: s(20), marginTop: s(6) }]}>{value}</Text>
      <Text style={[styles.statLabel, { fontSize: s(10) }]}>{label}</Text>
    </View>
  );
}

// ─── Clinical Section Row ─────────────────────────────────────────────────────

function ClinicalRow({ icon, iconColor, label, content, s }: {
  icon: IoniconsName; iconColor: string; label: string; content: string; s: (n: number) => number;
}) {
  return (
    <View style={[styles.clinicalRow, { marginBottom: s(12) }]}>
      <View style={[styles.clinicalLabelRow, { gap: s(6), marginBottom: s(5) }]}>
        <View style={[styles.clinicalIconBg, { backgroundColor: iconColor + '18', width: s(20), height: s(20), borderRadius: s(5) }]}>
          <Ionicons name={icon} size={s(11)} color={iconColor} />
        </View>
        <Text style={[styles.clinicalLabel, { fontSize: s(11) }]}>{label}</Text>
      </View>
      <Text style={[styles.clinicalContent, { fontSize: s(12), lineHeight: s(18), paddingLeft: s(26) }]}>{content}</Text>
    </View>
  );
}

// ─── Consultation Card ────────────────────────────────────────────────────────

function ConsultCard({ c, s }: { c: DoctorConsultation; s: (n: number) => number }) {
  const [expanded, setExpanded] = useState(false);
  const sc = statusConfig(c.status);
  const tc = apptTypeConfig(c.appointment_type);
  const ac = avatarColor(c.patient_name);

  const dateLabel = c.completed_at
    ? formatDateShort(c.completed_at)
    : c.started_at
      ? formatDateShort(c.started_at)
      : formatDateShort(c.created_at);

  const hasDetails = c.chief_complaint || c.history_of_present_illness || c.physical_examination ||
    c.diagnosis || c.treatment_plan || c.clinical_notes || c.follow_up_instructions;

  return (
    <View style={[styles.card, { borderRadius: s(14), marginBottom: s(12), borderLeftWidth: s(4), borderLeftColor: statusConfig(c.status).color }]}>
      {/* Header */}
      <View style={[styles.cardHeader, { padding: s(14), gap: s(10) }]}>
        <View style={[styles.avatar, { width: s(42), height: s(42), borderRadius: s(21), backgroundColor: ac }]}>
          <Text style={[styles.avatarText, { fontSize: s(14) }]}>{initials(c.patient_name)}</Text>
        </View>
        <View style={{ flex: 1 }}>
          <Text style={[styles.patientName, { fontSize: s(14) }]} numberOfLines={1}>{c.patient_name}</Text>
          <Text style={[styles.metaDate, { fontSize: s(11), marginTop: s(2) }]}>{dateLabel}</Text>
        </View>
        <View style={[styles.badge, { backgroundColor: sc.bg, borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
          <Ionicons name={sc.icon} size={s(10)} color={sc.color} />
          <Text style={[styles.badgeText, { fontSize: s(10), color: sc.color, marginLeft: s(3) }]}>{sc.label}</Text>
        </View>
      </View>

      {/* Chips row */}
      <View style={[styles.chipsRow, { paddingHorizontal: s(14), paddingBottom: s(10), gap: s(6) }]}>
        <View style={[styles.chip, { backgroundColor: tc.bg, borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
          <Ionicons name={tc.icon} size={s(10)} color={tc.color} />
          <Text style={[styles.chipText, { fontSize: s(10), color: tc.color, marginLeft: s(3) }]}>{tc.label}</Text>
        </View>
        {!!c.duration_minutes && (
          <View style={[styles.chip, { backgroundColor: BG, borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
            <Ionicons name="time-outline" size={s(10)} color={GRAY} />
            <Text style={[styles.chipText, { fontSize: s(10), color: GRAY, marginLeft: s(3) }]}>{c.duration_minutes} min</Text>
          </View>
        )}
        {!!c.follow_up_required && (
          <View style={[styles.chip, { backgroundColor: '#FEE2E2', borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
            <Ionicons name="arrow-forward-circle-outline" size={s(10)} color={RED} />
            <Text style={[styles.chipText, { fontSize: s(10), color: RED, marginLeft: s(3) }]}>Follow-up</Text>
          </View>
        )}
      </View>

      {/* Preview: chief complaint or clinical notes */}
      {!!(c.chief_complaint || c.clinical_notes) && (
        <View style={[styles.previewBox, { marginHorizontal: s(14), marginBottom: s(10), borderRadius: s(8), padding: s(10) }]}>
          <Text style={[styles.previewLabel, { fontSize: s(10), marginBottom: s(4) }]}>
            {c.chief_complaint ? 'Chief Complaint' : 'Clinical Notes'}
          </Text>
          <Text style={[styles.previewText, { fontSize: s(12), lineHeight: s(18) }]} numberOfLines={expanded ? undefined : 2}>
            {c.chief_complaint ?? c.clinical_notes}
          </Text>
        </View>
      )}

      {/* Diagnosis */}
      {!!c.diagnosis && (
        <View style={[styles.previewBox, { backgroundColor: '#F0FDF4', borderColor: '#BBF7D0', marginHorizontal: s(14), marginBottom: s(10), borderRadius: s(8), padding: s(10) }]}>
          <Text style={[styles.previewLabel, { fontSize: s(10), color: GREEN, marginBottom: s(4) }]}>Diagnosis</Text>
          <Text style={[styles.previewText, { fontSize: s(12), lineHeight: s(18) }]} numberOfLines={expanded ? undefined : 2}>
            {c.diagnosis}
          </Text>
        </View>
      )}

      {/* Expanded details */}
      {expanded && (
        <View style={[styles.expandedSection, { paddingHorizontal: s(14), paddingBottom: s(4) }]}>
          <View style={[styles.sectionDivider, { marginBottom: s(12) }]} />
          <Text style={[styles.sectionTitle, { fontSize: s(10), marginBottom: s(10) }]}>Clinical Details</Text>

          {!!c.history_of_present_illness && (
            <ClinicalRow icon="journal-outline" iconColor={BLUE} label="History of Present Illness" content={c.history_of_present_illness} s={s} />
          )}
          {!!c.physical_examination && (
            <ClinicalRow icon="body-outline" iconColor="#8B5CF6" label="Physical Examination" content={c.physical_examination} s={s} />
          )}
          {!!c.clinical_notes && (
            <ClinicalRow icon="clipboard-outline" iconColor={TEAL} label="Clinical Notes" content={c.clinical_notes} s={s} />
          )}
          {!!c.treatment_plan && (
            <ClinicalRow icon="medkit-outline" iconColor={ORANGE} label="Treatment Plan" content={c.treatment_plan} s={s} />
          )}
          {!!c.follow_up_instructions && (
            <ClinicalRow icon="arrow-forward-circle-outline" iconColor={RED} label="Follow-up Instructions" content={c.follow_up_instructions} s={s} />
          )}

          {!!c.follow_up_date && (
            <View style={[styles.followUpRow, { borderRadius: s(8), padding: s(10), marginBottom: s(10), gap: s(5) }]}>
              <Ionicons name="calendar-outline" size={s(13)} color={RED} />
              <Text style={[styles.followUpText, { fontSize: s(11) }]}>
                Follow-up due: <Text style={{ fontWeight: '600' }}>{formatDateShort(c.follow_up_date)}</Text>
              </Text>
            </View>
          )}

          <View style={[styles.timestampRow, { gap: s(12), marginBottom: s(10) }]}>
            {!!c.started_at && (
              <Text style={[styles.timestamp, { fontSize: s(10) }]}>Started: {formatDateTime(c.started_at)}</Text>
            )}
            {!!c.completed_at && (
              <Text style={[styles.timestamp, { fontSize: s(10) }]}>Completed: {formatDateTime(c.completed_at)}</Text>
            )}
          </View>
        </View>
      )}

      {/* Expand toggle */}
      {!!hasDetails && (
        <TouchableOpacity
          style={[styles.expandBtn, { paddingVertical: s(11) }]}
          onPress={() => setExpanded(e => !e)}
          activeOpacity={0.7}
        >
          <Text style={[styles.expandBtnText, { fontSize: s(12) }]}>{expanded ? 'Show less' : 'View full details'}</Text>
          <Ionicons name={expanded ? 'chevron-up' : 'chevron-down'} size={s(13)} color={BLUE} />
        </TouchableOpacity>
      )}
    </View>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────

export default function DoctorMobileConsultation() {
  const { s, sh } = useScale();
  const insets = useSafeAreaInsets();
  const { consultations, loading, error, refetch } = useDoctorConsultations();
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');

  const stats = useMemo(() => ({
    total:      consultations.length,
    completed:  consultations.filter(c => c.status === 'COMPLETED').length,
    inProgress: consultations.filter(c => c.status === 'IN_PROGRESS').length,
    draft:      consultations.filter(c => c.status === 'DRAFT').length,
    followUp:   consultations.filter(c => c.follow_up_required === 1).length,
  }), [consultations]);

  const filtered = useMemo(() => (
    statusFilter === 'ALL'
      ? consultations
      : consultations.filter(c => c.status === statusFilter)
  ), [consultations, statusFilter]);

  const statusFilters: { key: StatusFilter; label: string }[] = [
    { key: 'ALL',         label: 'All'         },
    { key: 'COMPLETED',   label: 'Completed'   },
    { key: 'IN_PROGRESS', label: 'In Progress' },
    { key: 'DRAFT',       label: 'Draft'       },
  ];

  return (
    <View style={styles.container}>
      {/* Navy Header */}
      <View style={[styles.header, { paddingTop: insets.top + s(12), paddingHorizontal: s(16), paddingBottom: s(16) }]}>
        <View style={{ flex: 1 }}>
          <Text style={[styles.headerTitle, { fontSize: s(20) }]}>Consultations</Text>
          <Text style={[styles.headerSub, { fontSize: s(12), marginTop: s(2) }]}>Your consultation history</Text>
        </View>
        <TouchableOpacity
          onPress={refetch}
          style={[styles.refreshBtn, { width: s(36), height: s(36), borderRadius: s(18) }]}
          activeOpacity={0.7}
        >
          <Ionicons name="refresh-outline" size={s(18)} color={WHITE} />
        </TouchableOpacity>
      </View>

      <ScrollView style={styles.scroll} contentContainerStyle={{ padding: s(16), paddingBottom: sh(80) }} showsVerticalScrollIndicator={false}>

        {loading ? (
          <View style={[styles.center, { paddingVertical: s(60) }]}>
            <ActivityIndicator size="large" color={BLUE} />
          </View>
        ) : error ? (
          <View style={[styles.center, { paddingVertical: s(60), gap: s(10) }]}>
            <Ionicons name="alert-circle-outline" size={s(48)} color={RED} />
            <Text style={[styles.errorText, { fontSize: s(14) }]}>{error}</Text>
            <TouchableOpacity
              style={[styles.retryBtn, { borderRadius: s(8), paddingHorizontal: s(20), paddingVertical: s(10) }]}
              onPress={refetch}
              activeOpacity={0.8}
            >
              <Text style={[styles.retryBtnText, { fontSize: s(14) }]}>Retry</Text>
            </TouchableOpacity>
          </View>
        ) : (
          <>
            {/* Stats */}
            <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ marginBottom: s(16) }} contentContainerStyle={{ gap: s(10) }}>
              <StatCard icon="document-text-outline"        iconBg="#EEF2FF" iconColor={BLUE}  label="Total"       value={stats.total}      s={s} />
              <StatCard icon="checkmark-circle-outline"     iconBg="#D1FAE5" iconColor={GREEN} label="Completed"   value={stats.completed}  s={s} />
              <StatCard icon="sync-outline"                 iconBg="#CCFBF1" iconColor={TEAL}  label="In Progress" value={stats.inProgress} s={s} />
              <StatCard icon="document-outline"             iconBg="#FEF3C7" iconColor={AMBER} label="Draft"       value={stats.draft}      s={s} />
              <StatCard icon="arrow-forward-circle-outline" iconBg="#FEE2E2" iconColor={RED}   label="Follow-up"   value={stats.followUp}   s={s} />
            </ScrollView>

            {/* Status filter pills */}
            <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ marginBottom: s(16) }} contentContainerStyle={{ gap: s(8) }}>
              {statusFilters.map(f => (
                <TouchableOpacity
                  key={f.key}
                  style={[
                    styles.pill,
                    { borderRadius: s(20), paddingHorizontal: s(14), paddingVertical: s(7) },
                    statusFilter === f.key && styles.pillActive,
                  ]}
                  onPress={() => setStatusFilter(f.key)}
                  activeOpacity={0.7}
                >
                  <Text style={[styles.pillText, { fontSize: s(12) }, statusFilter === f.key && styles.pillTextActive]}>
                    {f.label}
                  </Text>
                </TouchableOpacity>
              ))}
            </ScrollView>

            {/* Result count */}
            <Text style={[styles.resultCount, { fontSize: s(12), marginBottom: s(12) }]}>
              {filtered.length} consultation{filtered.length !== 1 ? 's' : ''}
            </Text>

            {/* Consultation cards */}
            {filtered.length === 0 ? (
              <View style={[styles.center, { paddingVertical: s(48), gap: s(8) }]}>
                <Ionicons name="document-text-outline" size={s(52)} color={BORDER} />
                <Text style={[styles.emptyTitle, { fontSize: s(16) }]}>No consultations found</Text>
                <Text style={[styles.errorText, { fontSize: s(13) }]}>Try adjusting your filters</Text>
              </View>
            ) : (
              filtered.map(c => <ConsultCard key={c.consultation_id} c={c} s={s} />)
            )}
          </>
        )}
      </ScrollView>
    </View>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: BG },
  scroll:    { flex: 1 },
  center:    { alignItems: 'center', justifyContent: 'center' },

  header:      { backgroundColor: NAVY, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-end' },
  headerTitle: { color: WHITE, fontWeight: '700' },
  headerSub:   { color: '#8BA3BE' },
  refreshBtn:  { backgroundColor: 'rgba(255,255,255,0.12)', alignItems: 'center', justifyContent: 'center' },

  statCard:   { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  statIconBg: { alignItems: 'center', justifyContent: 'center' },
  statValue:  { fontWeight: '700', color: TEXT },
  statLabel:  { color: GRAY },

  pill:          { borderWidth: 1, borderColor: BORDER, backgroundColor: WHITE },
  pillActive:    { backgroundColor: '#EEF2FF', borderColor: BLUE },
  pillText:      { fontWeight: '500', color: GRAY },
  pillTextActive:{ color: BLUE, fontWeight: '600' },

  resultCount: { color: GRAY, fontWeight: '500' },

  card:       { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  cardHeader: { flexDirection: 'row', alignItems: 'center' },
  avatar:     { alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  avatarText: { color: WHITE, fontWeight: '700' },
  patientName:{ fontWeight: '700', color: TEXT },
  metaDate:   { color: GRAY },
  badge:      { flexDirection: 'row', alignItems: 'center' },
  badgeText:  { fontWeight: '600' },
  chipsRow:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center' },
  chip:       { flexDirection: 'row', alignItems: 'center' },
  chipText:   { fontWeight: '500' },

  previewBox:   { backgroundColor: BG, borderWidth: 1, borderColor: BORDER },
  previewLabel: { fontWeight: '700', color: GRAY, textTransform: 'uppercase', letterSpacing: 0.5 },
  previewText:  { color: TEXT },

  expandedSection:  {},
  sectionDivider:   { height: 1, backgroundColor: BORDER },
  sectionTitle:     { fontWeight: '700', color: TEXT, textTransform: 'uppercase', letterSpacing: 0.5 },
  clinicalRow:      {},
  clinicalLabelRow: { flexDirection: 'row', alignItems: 'center' },
  clinicalIconBg:   { alignItems: 'center', justifyContent: 'center' },
  clinicalLabel:    { fontWeight: '700', color: TEXT },
  clinicalContent:  { color: GRAY },
  followUpRow:      { flexDirection: 'row', alignItems: 'center', backgroundColor: '#FEE2E2' },
  followUpText:     { color: RED },
  timestampRow:     { flexDirection: 'row', flexWrap: 'wrap' },
  timestamp:        { color: LGRAY },

  expandBtn:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 4, borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: BORDER, backgroundColor: BG },
  expandBtnText: { color: BLUE, fontWeight: '500' },

  errorText:    { color: GRAY, textAlign: 'center' },
  retryBtn:     { backgroundColor: BLUE },
  retryBtnText: { color: WHITE, fontWeight: '600' },
  emptyTitle:   { fontWeight: '600', color: TEXT },
});
