import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import { Image, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useConsultations, type Consultation } from '@/hooks/use-consultations';

const TEAL   = '#0D9488';
const INDIGO = '#4F46E5';
const GREEN  = '#10B981';
const AMBER  = '#F59E0B';
const RED    = '#EF4444';
const PURPLE = '#8B5CF6';
const BLUE   = '#3B82F6';
const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const CARD   = '#FFFFFF';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type FilterKey = 'ALL' | 'COMPLETED' | 'IN_PROGRESS' | 'DRAFT' | 'CANCELLED';

const STATUS_CONFIG: Record<string, { label: string; color: string; bg: string; icon: IoniconsName; stripe: string }> = {
  COMPLETED:   { label: 'Completed',   color: GREEN,  bg: '#ECFDF5', icon: 'checkmark-circle-outline', stripe: GREEN  },
  IN_PROGRESS: { label: 'In Progress', color: INDIGO, bg: '#EEF2FF', icon: 'hourglass-outline',        stripe: INDIGO },
  DRAFT:       { label: 'Draft',       color: AMBER,  bg: '#FFFBEB', icon: 'create-outline',            stripe: AMBER  },
  CANCELLED:   { label: 'Cancelled',   color: RED,    bg: '#FEF2F2', icon: 'close-circle-outline',      stripe: RED    },
};

const TYPE_CONFIG: Record<string, { label: string; color: string; bg: string; icon: IoniconsName }> = {
  IN_PERSON: { label: 'In Person',  color: TEAL,   bg: '#E6F4F1', icon: 'business-outline'  },
  VIDEO:     { label: 'Video Call', color: INDIGO, bg: '#EEF2FF', icon: 'videocam-outline'   },
  PHONE:     { label: 'Phone Call', color: GREEN,  bg: '#F0FDF4', icon: 'call-outline'       },
};

const FILTERS: { key: FilterKey; label: string }[] = [
  { key: 'ALL',         label: 'All'         },
  { key: 'COMPLETED',   label: 'Completed'   },
  { key: 'IN_PROGRESS', label: 'In Progress' },
  { key: 'DRAFT',       label: 'Draft'       },
  { key: 'CANCELLED',   label: 'Cancelled'   },
];

function formatDate(iso: string | null) {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

function getInitials(name: string) {
  const parts = name.trim().split(' ');
  if (parts.length === 1) return parts[0][0]?.toUpperCase() ?? 'D';
  return ((parts[0][0] ?? '') + (parts[parts.length - 1][0] ?? '')).toUpperCase();
}

function ExpandedSection({ icon, label, content, color }: { icon: IoniconsName; label: string; content: string; color: string }) {
  return (
    <View style={[styles.expandedBlock, { borderLeftColor: color }]}>
      <View style={styles.expandedBlockHeader}>
        <Ionicons name={icon} size={13} color={color} />
        <Text style={[styles.expandedBlockLabel, { color }]}>{label}</Text>
      </View>
      <Text style={styles.expandedBlockText}>{content}</Text>
    </View>
  );
}

function ConsultationCard({ item }: { item: Consultation }) {
  const [expanded, setExpanded] = useState(false);

  const status = STATUS_CONFIG[item.status] ?? STATUS_CONFIG.COMPLETED;
  const type   = item.appointment_type ? (TYPE_CONFIG[item.appointment_type] ?? TYPE_CONFIG.IN_PERSON) : null;

  return (
    <View style={[styles.card, { borderLeftColor: status.stripe }]}>
      {/* Card top row */}
      <View style={styles.cardHeader}>
        {item.doctor_avatar_url ? (
          <Image source={{ uri: item.doctor_avatar_url }} style={styles.avatar} />
        ) : (
          <View style={[styles.avatar, styles.avatarFallback]}>
            <Text style={styles.avatarInitial}>{getInitials(item.doctor_name)}</Text>
          </View>
        )}
        <View style={styles.cardHeaderText}>
          <Text style={styles.doctorName}>{item.doctor_name}</Text>
          {item.doctor_specialization && (
            <Text style={styles.doctorSpec}>{item.doctor_specialization}</Text>
          )}
        </View>
        <View style={styles.cardHeaderRight}>
          <Text style={styles.cardDate}>{formatDate(item.completed_at ?? item.created_at)}</Text>
          {item.duration_minutes != null && (
            <Text style={styles.cardDuration}>{item.duration_minutes} min</Text>
          )}
        </View>
      </View>

      {/* Badge row */}
      <View style={styles.badgeRow}>
        <View style={[styles.badge, { backgroundColor: status.bg }]}>
          <Ionicons name={status.icon} size={12} color={status.color} />
          <Text style={[styles.badgeText, { color: status.color }]}>{status.label}</Text>
        </View>
        {type && (
          <View style={[styles.badge, { backgroundColor: type.bg }]}>
            <Ionicons name={type.icon} size={12} color={type.color} />
            <Text style={[styles.badgeText, { color: type.color }]}>{type.label}</Text>
          </View>
        )}
      </View>

      {/* Follow-up chip */}
      {item.follow_up_required === 1 && (
        <View style={styles.followUpChip}>
          <Ionicons name="calendar-outline" size={12} color={AMBER} />
          <Text style={styles.followUpText}>
            Follow-up{item.follow_up_date ? `: ${formatDate(item.follow_up_date)}` : ' required'}
          </Text>
        </View>
      )}

      {/* Chief complaint box */}
      {item.chief_complaint && (
        <View style={styles.complaintBox}>
          <Ionicons name="document-text-outline" size={14} color={TEAL} />
          <Text style={styles.complaintText} numberOfLines={expanded ? undefined : 2}>
            {item.chief_complaint}
          </Text>
        </View>
      )}

      {/* Expanded sections */}
      {expanded && (
        <View style={styles.expandedSections}>
          {item.diagnosis && (
            <ExpandedSection icon="medal-outline" label="Diagnosis" content={item.diagnosis} color={INDIGO} />
          )}
          {item.treatment_plan && (
            <ExpandedSection icon="list-outline" label="Treatment Plan" content={item.treatment_plan} color={TEAL} />
          )}
          {item.follow_up_instructions && (
            <ExpandedSection icon="calendar-outline" label="Follow-up Instructions" content={item.follow_up_instructions} color={AMBER} />
          )}
          {item.clinical_notes && (
            <ExpandedSection icon="create-outline" label="Clinical Notes" content={item.clinical_notes} color={PURPLE} />
          )}
          {item.history_of_present_illness && (
            <ExpandedSection icon="time-outline" label="History of Present Illness" content={item.history_of_present_illness} color={BLUE} />
          )}
        </View>
      )}

      {/* Expand toggle */}
      <TouchableOpacity style={styles.expandToggle} onPress={() => setExpanded(e => !e)} activeOpacity={0.7}>
        <Text style={styles.expandToggleText}>{expanded ? 'Hide ▲' : 'Show full record ▼'}</Text>
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

export function PatientWebConsultationHistory({ onBack }: { onBack?: () => void }) {
  const { consultations, loading, error, refetch } = useConsultations();
  const [filter, setFilter] = useState<FilterKey>('ALL');

  const filtered    = filter === 'ALL' ? consultations : consultations.filter(c => c.status === filter);
  const total       = consultations.length;
  const completed   = consultations.filter(c => c.status === 'COMPLETED').length;
  const inProgress  = consultations.filter(c => c.status === 'IN_PROGRESS').length;
  const drafts      = consultations.filter(c => c.status === 'DRAFT').length;

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
      {/* Header bar */}
      <View style={styles.pageHeader}>
        <TouchableOpacity style={styles.backBtn} onPress={onBack} activeOpacity={0.7}>
          <Ionicons name="arrow-back-outline" size={16} color={TEAL} />
          <Text style={styles.backBtnText}>Back</Text>
        </TouchableOpacity>
        <View style={styles.pageHeaderCenter}>
          <Text style={styles.pageTitle}>Consultation History</Text>
          <Text style={styles.pageSub}>Your complete clinical record</Text>
        </View>
        <TouchableOpacity style={styles.refreshBtn} onPress={refetch} activeOpacity={0.7}>
          <Ionicons name="refresh-outline" size={18} color={TEAL} />
        </TouchableOpacity>
      </View>

      {/* Stats row */}
      <View style={styles.statsRow}>
        <StatCard value={total}      label="Total"       color={INDIGO} icon="document-text-outline"      />
        <StatCard value={completed}  label="Completed"   color={GREEN}  icon="checkmark-circle-outline"   />
        <StatCard value={inProgress} label="In Progress" color={INDIGO} icon="hourglass-outline"          />
        <StatCard value={drafts}     label="Drafts"      color={AMBER}  icon="create-outline"             />
      </View>

      {/* Filter tabs */}
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.filterScroll} contentContainerStyle={styles.filterRow}>
        {FILTERS.map(f => {
          const on    = filter === f.key;
          const count = f.key === 'ALL' ? total : consultations.filter(c => c.status === f.key).length;
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
          <Text style={styles.centerStateText}>Loading consultations…</Text>
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
          <Ionicons name="calendar-outline" size={48} color={BORDER} />
          <Text style={styles.centerStateText}>
            {filter === 'ALL' ? 'No consultations found.' : `No ${FILTERS.find(f => f.key === filter)?.label.toLowerCase()} consultations.`}
          </Text>
        </View>
      ) : (
        <View style={styles.cardList}>
          {filtered.map(item => (
            <ConsultationCard key={item.consultation_id} item={item} />
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

  filterScroll:           { marginBottom: 20 },
  filterRow:              { flexDirection: 'row', gap: 8, paddingBottom: 4 },
  filterTab:              { flexDirection: 'row', alignItems: 'center', gap: 6, paddingHorizontal: 16, paddingVertical: 8, borderRadius: 20, borderWidth: 1.5, borderColor: BORDER, backgroundColor: CARD },
  filterTabActive:        { borderColor: TEAL, backgroundColor: '#E6F4F1' },
  filterTabText:          { fontSize: 13, fontWeight: '500', color: GRAY },
  filterTabTextActive:    { color: TEAL, fontWeight: '700' },
  filterCount:            { borderRadius: 10, paddingHorizontal: 6, paddingVertical: 1 },
  filterCountActive:      { backgroundColor: TEAL },
  filterCountInactive:    { backgroundColor: BORDER },
  filterCountText:        { fontSize: 10, fontWeight: '700' },
  filterCountTextActive:  { color: WHITE },
  filterCountTextInactive:{ color: GRAY },

  cardList: { gap: 14 },

  card: { backgroundColor: CARD, borderRadius: 12, padding: 18, borderWidth: 1, borderColor: BORDER, borderLeftWidth: 4, shadowColor: '#000', shadowOpacity: 0.04, shadowRadius: 10, elevation: 2 } as any,

  cardHeader:      { flexDirection: 'row', alignItems: 'center', gap: 12, marginBottom: 12 },
  avatar:          { width: 40, height: 40, borderRadius: 20 },
  avatarFallback:  { backgroundColor: '#E6F4F1', alignItems: 'center', justifyContent: 'center' },
  avatarInitial:   { fontSize: 15, fontWeight: '700', color: TEAL },
  cardHeaderText:  { flex: 1 },
  doctorName:      { fontSize: 15, fontWeight: '700', color: TEXT },
  doctorSpec:      { fontSize: 12, color: GRAY, marginTop: 2 },
  cardHeaderRight: { alignItems: 'flex-end' },
  cardDate:        { fontSize: 12, color: GRAY, fontWeight: '500' },
  cardDuration:    { fontSize: 11, color: GRAY, marginTop: 2 },

  badgeRow:  { flexDirection: 'row', gap: 8, marginBottom: 10 },
  badge:     { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 10, paddingVertical: 4, borderRadius: 20 },
  badgeText: { fontSize: 11, fontWeight: '700' },

  followUpChip: { flexDirection: 'row', alignItems: 'center', gap: 6, backgroundColor: '#FFFBEB', borderRadius: 8, paddingHorizontal: 10, paddingVertical: 5, marginBottom: 10, alignSelf: 'flex-start', borderWidth: 1, borderColor: '#FDE68A' },
  followUpText: { fontSize: 12, fontWeight: '600', color: AMBER },

  complaintBox:  { flexDirection: 'row', alignItems: 'flex-start', gap: 8, backgroundColor: '#E6F4F1', borderRadius: 10, padding: 12, marginBottom: 10 },
  complaintText: { flex: 1, fontSize: 13, color: TEXT, lineHeight: 19 },

  expandedSections: { gap: 8, marginBottom: 10 },
  expandedBlock:    { borderLeftWidth: 3, paddingLeft: 10, paddingVertical: 4 },
  expandedBlockHeader: { flexDirection: 'row', alignItems: 'center', gap: 5, marginBottom: 4 },
  expandedBlockLabel:  { fontSize: 11, fontWeight: '700', textTransform: 'uppercase', letterSpacing: 0.5 },
  expandedBlockText:   { fontSize: 13, color: TEXT, lineHeight: 19 },

  expandToggle:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', paddingTop: 12, borderTopWidth: 1, borderTopColor: BORDER, marginTop: 6 },
  expandToggleText: { fontSize: 12, fontWeight: '600', color: TEAL },

  centerState:     { alignItems: 'center', justifyContent: 'center', paddingVertical: 64, gap: 12 },
  centerStateText: { fontSize: 14, color: GRAY, textAlign: 'center' },
  retryBtn:        { backgroundColor: TEAL, borderRadius: 10, paddingHorizontal: 20, paddingVertical: 10, marginTop: 4 },
  retryBtnText:    { fontSize: 13, fontWeight: '700', color: WHITE },
});

export default PatientWebConsultationHistory;
