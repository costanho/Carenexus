import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useMedicalRecords, type MedicalRecord } from '@/hooks/use-medical-records';

const TEAL   = '#0D9488';
const INDIGO = '#4F46E5';
const GREEN  = '#10B981';
const AMBER  = '#F59E0B';
const RED    = '#EF4444';
const PURPLE = '#8B5CF6';
const BLUE   = '#3B82F6';
const ROSE   = '#F43F5E';
const GRAY   = '#6B7280';
const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const CARD   = '#FFFFFF';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type RecordType = MedicalRecord['record_type'];
type FilterKey  = 'ALL' | RecordType;

const RECORD_CONFIG: Record<RecordType, { label: string; color: string; bg: string; icon: IoniconsName }> = {
  CONSULT:      { label: 'Consultation', color: TEAL,   bg: '#E6F4F1', icon: 'medkit-outline'    },
  LAB:          { label: 'Lab Result',   color: BLUE,   bg: '#EFF6FF', icon: 'flask-outline'     },
  IMAGING:      { label: 'Imaging',      color: PURPLE, bg: '#F5F3FF', icon: 'image-outline'     },
  PRESCRIPTION: { label: 'Prescription', color: GREEN,  bg: '#ECFDF5', icon: 'document-outline'  },
  REFERRAL:     { label: 'Referral',     color: AMBER,  bg: '#FFFBEB', icon: 'git-compare-outline'},
  DISCHARGE:    { label: 'Discharge',    color: INDIGO, bg: '#EEF2FF', icon: 'exit-outline'      },
  NOTE:         { label: 'Note',         color: GRAY,   bg: '#F3F4F6', icon: 'create-outline'    },
  OPERATION:    { label: 'Operation',    color: ROSE,   bg: '#FFF1F2', icon: 'fitness-outline'   },
};

const FILTERS: { key: FilterKey; label: string }[] = [
  { key: 'ALL',          label: 'All'          },
  { key: 'CONSULT',      label: 'Consultation' },
  { key: 'LAB',          label: 'Lab'          },
  { key: 'IMAGING',      label: 'Imaging'      },
  { key: 'PRESCRIPTION', label: 'Prescription' },
  { key: 'REFERRAL',     label: 'Referral'     },
  { key: 'NOTE',         label: 'Note'         },
  { key: 'DISCHARGE',    label: 'Discharge'    },
  { key: 'OPERATION',    label: 'Operation'    },
];

function formatDate(iso: string | null) {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
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

function RecordCard({ item }: { item: MedicalRecord }) {
  const [expanded, setExpanded] = useState(false);
  const cfg = RECORD_CONFIG[item.record_type] ?? RECORD_CONFIG.NOTE;

  return (
    <View style={[styles.card, { borderLeftColor: cfg.color }]}>
      {/* Header row */}
      <View style={styles.cardHeader}>
        <View style={[styles.typeIconSquare, { backgroundColor: cfg.bg }]}>
          <Ionicons name={cfg.icon} size={20} color={cfg.color} />
        </View>
        <View style={styles.cardHeaderText}>
          <Text style={styles.cardTitle}>{item.title}</Text>
          <Text style={styles.cardDoctorName}>{item.doctor_name}</Text>
        </View>
        <Text style={styles.cardDate}>{formatDate(item.created_at)}</Text>
      </View>

      {/* Type + specialty badges */}
      <View style={styles.badgeRow}>
        <View style={[styles.badge, { backgroundColor: cfg.bg }]}>
          <Ionicons name={cfg.icon} size={12} color={cfg.color} />
          <Text style={[styles.badgeText, { color: cfg.color }]}>{cfg.label}</Text>
        </View>
        {item.doctor_specialization && (
          <View style={[styles.badge, { backgroundColor: BG }]}>
            <Ionicons name="person-outline" size={12} color={GRAY} />
            <Text style={[styles.badgeText, { color: GRAY }]}>{item.doctor_specialization}</Text>
          </View>
        )}
      </View>

      {/* Description box */}
      {item.description && (
        <View style={styles.descBox}>
          <Ionicons name="document-text-outline" size={14} color={TEAL} />
          <Text style={styles.descText} numberOfLines={expanded ? undefined : 2}>
            {item.description}
          </Text>
        </View>
      )}

      {/* Expanded sections */}
      {expanded && (
        <View style={styles.expandedSections}>
          {item.observations && (
            <ExpandedSection icon="eye-outline" label="Observations" content={item.observations} color={BLUE} />
          )}
          {item.treatment_plan && (
            <ExpandedSection icon="list-outline" label="Treatment Plan" content={item.treatment_plan} color={TEAL} />
          )}
          {item.icd_codes && item.icd_codes.length > 0 && (
            <View style={[styles.expandedBlock, { borderLeftColor: PURPLE }]}>
              <View style={styles.expandedBlockHeader}>
                <Ionicons name="barcode-outline" size={13} color={PURPLE} />
                <Text style={[styles.expandedBlockLabel, { color: PURPLE }]}>ICD Codes</Text>
              </View>
              <View style={styles.icdChipsRow}>
                {item.icd_codes.map((icd, i) => (
                  <View key={i} style={styles.icdChip}>
                    <Text style={styles.icdChipText}>{icd.code} — {icd.description}</Text>
                  </View>
                ))}
              </View>
            </View>
          )}
        </View>
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

export function PatientWebMedicalRecords({ onBack }: { onBack?: () => void }) {
  const { records, loading, error, refetch } = useMedicalRecords();
  const [filter, setFilter] = useState<FilterKey>('ALL');

  const filtered    = filter === 'ALL' ? records : records.filter(r => r.record_type === filter);
  const total       = records.length;
  const clinical    = records.filter(r => r.record_type === 'CONSULT' || r.record_type === 'OPERATION').length;
  const diagnostics = records.filter(r => r.record_type === 'LAB' || r.record_type === 'IMAGING').length;
  const documents   = records.filter(r =>
    r.record_type === 'PRESCRIPTION' ||
    r.record_type === 'REFERRAL' ||
    r.record_type === 'NOTE' ||
    r.record_type === 'DISCHARGE'
  ).length;

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
      {/* Header bar */}
      <View style={styles.pageHeader}>
        <TouchableOpacity style={styles.backBtn} onPress={onBack} activeOpacity={0.7}>
          <Ionicons name="arrow-back-outline" size={16} color={TEAL} />
          <Text style={styles.backBtnText}>Back</Text>
        </TouchableOpacity>
        <View style={styles.pageHeaderCenter}>
          <Text style={styles.pageTitle}>Medical Records</Text>
          <Text style={styles.pageSub}>Your complete health record</Text>
        </View>
        <TouchableOpacity style={styles.refreshBtn} onPress={refetch} activeOpacity={0.7}>
          <Ionicons name="refresh-outline" size={18} color={TEAL} />
        </TouchableOpacity>
      </View>

      {/* Stats row */}
      <View style={styles.statsRow}>
        <StatCard value={total}       label="Total"       color={INDIGO} icon="document-text-outline" />
        <StatCard value={clinical}    label="Clinical"    color={TEAL}   icon="medkit-outline"        />
        <StatCard value={diagnostics} label="Diagnostics" color={BLUE}   icon="flask-outline"         />
        <StatCard value={documents}   label="Documents"   color={GREEN}  icon="document-outline"      />
      </View>

      {/* Filter pills */}
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.filterScroll} contentContainerStyle={styles.filterRow}>
        {FILTERS.map(f => {
          const on    = filter === f.key;
          const count = f.key === 'ALL' ? total : records.filter(r => r.record_type === f.key).length;
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
          <Text style={styles.centerStateText}>Loading records…</Text>
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
          <Ionicons name="document-outline" size={48} color={BORDER} />
          <Text style={styles.centerStateText}>
            {filter === 'ALL' ? 'No medical records found.' : `No ${FILTERS.find(f => f.key === filter)?.label.toLowerCase()} records.`}
          </Text>
        </View>
      ) : (
        <View style={styles.cardList}>
          {filtered.map(item => (
            <RecordCard key={item.record_id} item={item} />
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

  cardHeader:     { flexDirection: 'row', alignItems: 'center', gap: 12, marginBottom: 12 },
  typeIconSquare: { width: 40, height: 40, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  cardHeaderText: { flex: 1 },
  cardTitle:      { fontSize: 15, fontWeight: '700', color: TEXT },
  cardDoctorName: { fontSize: 12, color: GRAY, marginTop: 2 },
  cardDate:       { fontSize: 12, color: GRAY, fontWeight: '500' },

  badgeRow:  { flexDirection: 'row', gap: 8, marginBottom: 10 },
  badge:     { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 10, paddingVertical: 4, borderRadius: 20 },
  badgeText: { fontSize: 11, fontWeight: '700' },

  descBox:  { flexDirection: 'row', alignItems: 'flex-start', gap: 8, backgroundColor: '#E6F4F1', borderRadius: 10, padding: 12, marginBottom: 10 },
  descText: { flex: 1, fontSize: 13, color: TEXT, lineHeight: 19 },

  expandedSections:    { gap: 8, marginBottom: 10 },
  expandedBlock:       { borderLeftWidth: 3, paddingLeft: 10, paddingVertical: 4 },
  expandedBlockHeader: { flexDirection: 'row', alignItems: 'center', gap: 5, marginBottom: 4 },
  expandedBlockLabel:  { fontSize: 11, fontWeight: '700', textTransform: 'uppercase', letterSpacing: 0.5 },
  expandedBlockText:   { fontSize: 13, color: TEXT, lineHeight: 19 },

  icdChipsRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginTop: 4 },
  icdChip:     { backgroundColor: '#F5F3FF', borderRadius: 6, paddingHorizontal: 8, paddingVertical: 3, borderWidth: 1, borderColor: '#DDD6FE' },
  icdChipText: { fontSize: 11, color: PURPLE, fontWeight: '600' },

  expandToggle:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', paddingTop: 12, borderTopWidth: 1, borderTopColor: BORDER, marginTop: 6 },
  expandToggleText: { fontSize: 12, fontWeight: '600', color: TEAL },

  centerState:     { alignItems: 'center', justifyContent: 'center', paddingVertical: 64, gap: 12 },
  centerStateText: { fontSize: 14, color: GRAY, textAlign: 'center' },
  retryBtn:        { backgroundColor: TEAL, borderRadius: 10, paddingHorizontal: 20, paddingVertical: 10, marginTop: 4 },
  retryBtnText:    { fontSize: 13, fontWeight: '700', color: WHITE },
});

export default PatientWebMedicalRecords;
