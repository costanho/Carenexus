import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import {
  Image, KeyboardAvoidingView, Platform, ScrollView,
  StyleSheet, Text, TouchableOpacity, useWindowDimensions, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { StatusBar } from 'expo-status-bar';
import { goBackOr } from '../../shared/navigation';
import { isPatientVisibleConsultation, useConsultations, type Consultation } from '@/hooks/use-consultations';
import { tidyDoctorName } from '@/hooks/use-doctors';
import { formatDateOnly } from '@/lib/ui/date-only';

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

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type FilterKey = 'ALL' | 'FOLLOW_UP';

const STATUS_CONFIG: Record<string, { label: string; color: string; bg: string; icon: IoniconsName }> = {
  COMPLETED:   { label: 'Completed',   color: GREEN,  bg: '#ECFDF5', icon: 'checkmark-circle-outline' },
  IN_PROGRESS: { label: 'In Progress', color: INDIGO, bg: '#EEF2FF', icon: 'hourglass-outline'        },
  DRAFT:       { label: 'Draft',       color: AMBER,  bg: '#FFFBEB', icon: 'create-outline'            },
  CANCELLED:   { label: 'Cancelled',   color: RED,    bg: '#FEF2F2', icon: 'close-circle-outline'      },
};

const TYPE_CONFIG: Record<string, { label: string; color: string; bg: string; icon: IoniconsName }> = {
  IN_PERSON: { label: 'In Person',  color: TEAL,   bg: '#E6F4F1', icon: 'business-outline' },
  VIDEO:     { label: 'Video Call', color: INDIGO, bg: '#EEF2FF', icon: 'videocam-outline'  },
  PHONE:     { label: 'Phone Call', color: GREEN,  bg: '#F0FDF4', icon: 'call-outline'      },
};

// The backend only returns finalised (COMPLETED) consultations to patients, so there are no draft or
// in-progress filters; patients filter by whether a follow-up is needed instead (same as web).
const FILTERS: { key: FilterKey; label: string }[] = [
  { key: 'ALL',       label: 'All'       },
  { key: 'FOLLOW_UP', label: 'Follow-up' },
];

function matchesFilter(c: Consultation, filter: FilterKey): boolean {
  return filter === 'ALL' || (filter === 'FOLLOW_UP' && c.follow_up_required === 1);
}

function formatDate(iso: string | null) {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

/** Initials of a doctor's name, skipping a leading "Dr." title ("Dr. James Banda" -> "JB"). */
function getInitials(name: string) {
  const parts = name.trim().replace(/^dr(\.\s*|\s+)/i, '').split(/\s+/).filter(Boolean);
  if (parts.length === 0) return 'D';
  if (parts.length === 1) return parts[0][0].toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

function ConsultationCard({ item, s }: { item: Consultation; s: (n: number) => number }) {
  const [expanded, setExpanded] = useState(false);

  const status = STATUS_CONFIG[item.status] ?? STATUS_CONFIG.COMPLETED;
  const type   = item.appointment_type ? (TYPE_CONFIG[item.appointment_type] ?? TYPE_CONFIG.IN_PERSON) : null;

  return (
    <View style={[styles.card, { borderRadius: s(16), marginBottom: s(12), borderLeftColor: status.color }]}>
      <View style={{ padding: s(14) }}>
        {/* Header */}
        <View style={[styles.cardHeader, { gap: s(10), marginBottom: s(10) }]}>
          <View style={[styles.typeIconBg, { width: s(38), height: s(38), borderRadius: s(11), backgroundColor: type?.bg ?? '#E6F4F1' }]}>
            <Ionicons name={type?.icon ?? 'document-text-outline'} size={s(18)} color={type?.color ?? TEAL} />
          </View>
          <View style={{ flex: 1 }}>
            <Text style={[styles.doctorName, { fontSize: s(14) }]} numberOfLines={2}>{tidyDoctorName(item.doctor_name)}</Text>
            {item.doctor_specialization && (
              <Text style={[styles.doctorSpec, { fontSize: s(11) }]}>{item.doctor_specialization}</Text>
            )}
          </View>
          {item.doctor_avatar_url ? (
            <Image source={{ uri: item.doctor_avatar_url }} style={[styles.avatar, { width: s(36), height: s(36), borderRadius: s(18) }]} />
          ) : (
            <View style={[styles.avatar, styles.avatarFallback, { width: s(36), height: s(36), borderRadius: s(18) }]}>
              <Text style={[styles.avatarInitial, { fontSize: s(13) }]}>{getInitials(item.doctor_name)}</Text>
            </View>
          )}
        </View>

        {/* Badge row */}
        <View style={[styles.badgeRow, { gap: s(6), marginBottom: s(10) }]}>
          <View style={[styles.badge, { backgroundColor: status.bg, borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
            <Ionicons name={status.icon} size={s(11)} color={status.color} />
            <Text style={[styles.badgeText, { fontSize: s(10), color: status.color }]}>{status.label}</Text>
          </View>
          {type && (
            <View style={[styles.badge, { backgroundColor: type.bg, borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
              <Ionicons name={type.icon} size={s(11)} color={type.color} />
              <Text style={[styles.badgeText, { fontSize: s(10), color: type.color }]}>{type.label}</Text>
            </View>
          )}
        </View>

        {/* Date + duration chips */}
        <View style={[styles.metaGrid, { gap: s(8), marginBottom: s(10) }]}>
          <View style={[styles.metaChip, { borderRadius: s(8), paddingHorizontal: s(10), paddingVertical: s(6), gap: s(5) }]}>
            <Ionicons name="calendar-outline" size={s(13)} color={TEAL} />
            <Text style={[styles.metaText, { fontSize: s(11) }]}>{formatDate(item.completed_at ?? item.created_at)}</Text>
          </View>
          {item.duration_minutes != null && (
            <View style={[styles.metaChip, { borderRadius: s(8), paddingHorizontal: s(10), paddingVertical: s(6), gap: s(5) }]}>
              <Ionicons name="hourglass-outline" size={s(13)} color={TEAL} />
              <Text style={[styles.metaText, { fontSize: s(11) }]}>{item.duration_minutes} min</Text>
            </View>
          )}
        </View>

        {/* Chief complaint */}
        {item.chief_complaint && (
          <View style={[styles.complaintBox, { borderRadius: s(10), padding: s(10), gap: s(6), marginBottom: s(8) }]}>
            <Ionicons name="document-text-outline" size={s(13)} color={TEAL} />
            <Text style={[styles.complaintText, { fontSize: s(12) }]} numberOfLines={expanded ? undefined : 2}>
              {item.chief_complaint}
            </Text>
          </View>
        )}

        {/* Follow-up chip */}
        {item.follow_up_required === 1 && (
          <View style={[styles.followUpChip, { borderRadius: s(8), paddingHorizontal: s(10), paddingVertical: s(5), gap: s(6), marginBottom: s(8) }]}>
            <Ionicons name="calendar-outline" size={s(12)} color={AMBER} />
            <Text style={[styles.followUpText, { fontSize: s(11) }]}>
              Follow-up{item.follow_up_date ? `: ${formatDateOnly(item.follow_up_date)}` : ' required'}
            </Text>
          </View>
        )}

        {/* Expanded sections */}
        {expanded && (
          <View style={{ gap: s(8), marginBottom: s(8) }}>
            {item.diagnosis && (
              <View style={[styles.expandedBlock, { borderLeftColor: INDIGO, paddingLeft: s(10), paddingVertical: s(4) }]}>
                <View style={[styles.expandedBlockHeader, { gap: s(5), marginBottom: s(3) }]}>
                  <Ionicons name="medal-outline" size={s(12)} color={INDIGO} />
                  <Text style={[styles.expandedBlockLabel, { fontSize: s(10), color: INDIGO }]}>DIAGNOSIS</Text>
                </View>
                <Text style={[styles.expandedBlockText, { fontSize: s(12) }]}>{item.diagnosis}</Text>
              </View>
            )}
            {item.treatment_plan && (
              <View style={[styles.expandedBlock, { borderLeftColor: TEAL, paddingLeft: s(10), paddingVertical: s(4) }]}>
                <View style={[styles.expandedBlockHeader, { gap: s(5), marginBottom: s(3) }]}>
                  <Ionicons name="list-outline" size={s(12)} color={TEAL} />
                  <Text style={[styles.expandedBlockLabel, { fontSize: s(10), color: TEAL }]}>TREATMENT PLAN</Text>
                </View>
                <Text style={[styles.expandedBlockText, { fontSize: s(12) }]}>{item.treatment_plan}</Text>
              </View>
            )}
            {item.follow_up_instructions && (
              <View style={[styles.expandedBlock, { borderLeftColor: AMBER, paddingLeft: s(10), paddingVertical: s(4) }]}>
                <View style={[styles.expandedBlockHeader, { gap: s(5), marginBottom: s(3) }]}>
                  <Ionicons name="calendar-outline" size={s(12)} color={AMBER} />
                  <Text style={[styles.expandedBlockLabel, { fontSize: s(10), color: AMBER }]}>FOLLOW-UP INSTRUCTIONS</Text>
                </View>
                <Text style={[styles.expandedBlockText, { fontSize: s(12) }]}>{item.follow_up_instructions}</Text>
              </View>
            )}
            {item.clinical_notes && (
              <View style={[styles.expandedBlock, { borderLeftColor: PURPLE, paddingLeft: s(10), paddingVertical: s(4) }]}>
                <View style={[styles.expandedBlockHeader, { gap: s(5), marginBottom: s(3) }]}>
                  <Ionicons name="create-outline" size={s(12)} color={PURPLE} />
                  <Text style={[styles.expandedBlockLabel, { fontSize: s(10), color: PURPLE }]}>CLINICAL NOTES</Text>
                </View>
                <Text style={[styles.expandedBlockText, { fontSize: s(12) }]}>{item.clinical_notes}</Text>
              </View>
            )}
          </View>
        )}

        {/* Expand toggle */}
        <TouchableOpacity
          style={[styles.expandToggle, { paddingTop: s(8), gap: s(4) }]}
          onPress={() => setExpanded(e => !e)}
          activeOpacity={0.7}
          accessibilityRole="button"
          accessibilityLabel={expanded ? 'Hide full record' : 'Show full record'}
          accessibilityState={{ expanded }}
        >
          <Text style={[styles.expandToggleText, { fontSize: s(12) }]}>{expanded ? 'Hide ▲' : 'Show full record ▼'}</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

export function PatientMobileConsultationHistory({ onBack }: { onBack?: () => void }) {
  const router  = useRouter();
  const insets  = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  const { consultations: all, loading, error, refetch } = useConsultations();
  const [filter, setFilter] = useState<FilterKey>('ALL');

  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));

  const consultations = all.filter(isPatientVisibleConsultation);
  const filtered      = consultations.filter(c => matchesFilter(c, filter));
  const total         = consultations.length;
  const followUps     = consultations.filter(c => matchesFilter(c, 'FOLLOW_UP')).length;
  const withDiagnosis = consultations.filter(c => !!c.diagnosis).length;

  return (
    <KeyboardAvoidingView style={styles.screen} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <StatusBar style="light" />
      {/* Teal header */}
      <View style={[styles.header, {
        paddingTop:        insets.top + sh(12),
        paddingBottom:     sh(20),
        paddingHorizontal: s(16),
        gap:               s(10),
      }]}>
        <TouchableOpacity
          style={[styles.headerBack, { width: s(34), height: s(34), borderRadius: s(17) }]}
          onPress={() => onBack ? onBack() : goBackOr(router, '/dashboard/patient')}
          activeOpacity={0.7}
          accessibilityRole="button"
          accessibilityLabel="Go back"
        >
          <Ionicons name="arrow-back-outline" size={s(18)} color={WHITE} />
        </TouchableOpacity>
        <View style={{ flex: 1 }}>
          <Text style={[styles.headerTitle, { fontSize: s(18) }]} accessibilityRole="header" numberOfLines={1}>Consultation History</Text>
          <Text style={[styles.headerSub, { fontSize: s(11) }]}>Your complete clinical record</Text>
        </View>
        <TouchableOpacity
          style={[styles.headerRefresh, { width: s(34), height: s(34), borderRadius: s(17) }]}
          onPress={refetch}
          activeOpacity={0.7}
          accessibilityRole="button"
          accessibilityLabel="Refresh"
        >
          <Ionicons name="refresh-outline" size={s(17)} color={TEAL} />
        </TouchableOpacity>
      </View>

      {/* Stats strip */}
      <View style={[styles.statsStrip, { paddingHorizontal: s(16), paddingVertical: s(14), gap: s(10) }]}>
        {[
          { value: total,         label: 'Total',          color: INDIGO },
          { value: withDiagnosis, label: 'With diagnosis', color: GREEN  },
          { value: followUps,     label: 'Follow-ups',     color: AMBER  },
        ].map(stat => (
          <View key={stat.label} style={[styles.statItem, { borderRadius: s(12), padding: s(10) }]} accessible accessibilityLabel={`${stat.label}: ${stat.value}`}>
            <Text style={[styles.statValue, { fontSize: s(20), color: stat.color }]}>{stat.value}</Text>
            <Text style={[styles.statLabel, { fontSize: s(10) }]} numberOfLines={1}>{stat.label}</Text>
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
          const count = f.key === 'ALL' ? total : followUps;
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
              accessibilityRole="tab"
              accessibilityLabel={`${f.label}, ${count}`}
              accessibilityState={{ selected: on }}
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
        {loading && consultations.length === 0 ? (
          <View style={styles.centerState}>
            <Ionicons name="hourglass-outline" size={s(40)} color={BORDER} />
            <Text style={[styles.centerText, { fontSize: s(14) }]}>Loading consultations…</Text>
          </View>
        ) : error && consultations.length === 0 ? (
          <View style={styles.centerState}>
            <Ionicons name="alert-circle-outline" size={s(40)} color={RED} />
            <Text style={[styles.centerText, { fontSize: s(13), color: RED }]}>{error}</Text>
            <TouchableOpacity
              style={[styles.retryBtn, { borderRadius: s(10), paddingHorizontal: s(20), paddingVertical: s(10), marginTop: s(8) }]}
              onPress={refetch}
              activeOpacity={0.8}
              accessibilityRole="button"
            >
              <Text style={[styles.retryBtnText, { fontSize: s(13) }]}>Try Again</Text>
            </TouchableOpacity>
          </View>
        ) : filtered.length === 0 ? (
          <View style={styles.centerState}>
            <Ionicons name="calendar-outline" size={s(48)} color={BORDER} />
            <Text style={[styles.centerText, { fontSize: s(14) }]}>
              {filter === 'ALL'
                ? 'No consultation records yet. They appear here once your doctor completes a consultation.'
                : 'No consultations need a follow-up.'}
            </Text>
          </View>
        ) : (
          filtered.map(item => <ConsultationCard key={item.consultation_id} item={item} s={s} />)
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

  filterScroll:        { flexGrow: 0, backgroundColor: WHITE, borderBottomWidth: 1, borderBottomColor: BORDER },
  filterPill:          { flexDirection: 'row', alignItems: 'center', borderWidth: 1.5, borderColor: BORDER, backgroundColor: WHITE },
  filterPillActive:    { borderColor: TEAL, backgroundColor: '#E6F4F1' },
  filterPillText:      { fontWeight: '500', color: GRAY },
  filterPillTextActive:{ fontWeight: '700', color: TEAL },
  filterPillCount:     { paddingVertical: 1 },

  scroll: { flex: 1 },

  card:           { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER, borderLeftWidth: 4, overflow: 'hidden', shadowColor: '#000', shadowOpacity: 0.04, shadowRadius: 8, elevation: 2 } as any,
  cardHeader:     { flexDirection: 'row', alignItems: 'center' },
  typeIconBg:     { alignItems: 'center', justifyContent: 'center' },
  doctorName:     { fontWeight: '700', color: TEXT },
  doctorSpec:     { color: GRAY, marginTop: 2 },
  avatar:         {},
  avatarFallback: { backgroundColor: '#E6F4F1', alignItems: 'center', justifyContent: 'center' },
  avatarInitial:  { fontWeight: '700', color: TEAL },

  badgeRow:  { flexDirection: 'row' },
  badge:     { flexDirection: 'row', alignItems: 'center' },
  badgeText: { fontWeight: '700' },

  metaGrid: { flexDirection: 'row', flexWrap: 'wrap' },
  metaChip: { flexDirection: 'row', alignItems: 'center', backgroundColor: BG, borderWidth: 1, borderColor: BORDER },
  metaText: { color: TEXT, fontWeight: '500' },

  complaintBox:  { flexDirection: 'row', alignItems: 'flex-start', backgroundColor: '#E6F4F1' },
  complaintText: { flex: 1, color: TEXT, lineHeight: 18 },

  followUpChip: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#FFFBEB', borderWidth: 1, borderColor: '#FDE68A', alignSelf: 'flex-start' },
  followUpText: { fontWeight: '600', color: AMBER },

  expandedBlock:       { borderLeftWidth: 3 },
  expandedBlockHeader: { flexDirection: 'row', alignItems: 'center' },
  expandedBlockLabel:  { fontWeight: '700', letterSpacing: 0.5 },
  expandedBlockText:   { color: TEXT, lineHeight: 18 },

  expandToggle:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', borderTopWidth: 1, borderTopColor: BORDER },
  expandToggleText: { fontWeight: '600', color: TEAL },

  centerState: { alignItems: 'center', justifyContent: 'center', paddingVertical: 60, gap: 12 },
  centerText:  { color: GRAY, textAlign: 'center' },
  retryBtn:    { backgroundColor: TEAL },
  retryBtnText:{ fontWeight: '700', color: WHITE },
});

export default PatientMobileConsultationHistory;
