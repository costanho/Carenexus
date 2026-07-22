import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import {
  KeyboardAvoidingView, Platform, ScrollView,
  StyleSheet, Text, TouchableOpacity, useWindowDimensions, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
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

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type RecordType = MedicalRecord['record_type'];
type FilterKey  = 'ALL' | RecordType;

const RECORD_CONFIG: Record<RecordType, { label: string; color: string; bg: string; icon: IoniconsName }> = {
  CONSULT:      { label: 'Consultation', color: TEAL,   bg: '#E6F4F1', icon: 'medkit-outline'     },
  LAB:          { label: 'Lab Result',   color: BLUE,   bg: '#EFF6FF', icon: 'flask-outline'      },
  IMAGING:      { label: 'Imaging',      color: PURPLE, bg: '#F5F3FF', icon: 'image-outline'      },
  PRESCRIPTION: { label: 'Prescription', color: GREEN,  bg: '#ECFDF5', icon: 'document-outline'   },
  REFERRAL:     { label: 'Referral',     color: AMBER,  bg: '#FFFBEB', icon: 'git-compare-outline' },
  DISCHARGE:    { label: 'Discharge',    color: INDIGO, bg: '#EEF2FF', icon: 'exit-outline'       },
  NOTE:         { label: 'Note',         color: GRAY,   bg: '#F3F4F6', icon: 'create-outline'     },
  OPERATION:    { label: 'Operation',    color: ROSE,   bg: '#FFF1F2', icon: 'fitness-outline'    },
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

function RecordCard({ item, s }: { item: MedicalRecord; s: (n: number) => number }) {
  const [expanded, setExpanded] = useState(false);
  const cfg = RECORD_CONFIG[item.record_type] ?? RECORD_CONFIG.NOTE;

  return (
    <View style={[styles.card, { borderRadius: s(16), marginBottom: s(12), borderLeftColor: cfg.color }]}>
      <View style={{ padding: s(14) }}>
        {/* Header */}
        <View style={[styles.cardHeader, { gap: s(10), marginBottom: s(10) }]}>
          <View style={[styles.typeIconCircle, { width: s(38), height: s(38), borderRadius: s(19), backgroundColor: cfg.bg }]}>
            <Ionicons name={cfg.icon} size={s(18)} color={cfg.color} />
          </View>
          <View style={{ flex: 1 }}>
            <Text style={[styles.cardTitle, { fontSize: s(14) }]}>{item.title}</Text>
            <Text style={[styles.cardDoctorName, { fontSize: s(11) }]}>{item.doctor_name}</Text>
            {item.doctor_specialization && (
              <Text style={[styles.cardDoctorSpec, { fontSize: s(10) }]}>{item.doctor_specialization}</Text>
            )}
          </View>
          <View style={[styles.dateChip, { borderRadius: s(8), paddingHorizontal: s(8), paddingVertical: s(4) }]}>
            <Text style={[styles.dateChipText, { fontSize: s(10) }]}>{formatDate(item.created_at)}</Text>
          </View>
        </View>

        {/* Badge row */}
        <View style={[styles.badgeRow, { gap: s(6), marginBottom: s(10) }]}>
          <View style={[styles.badge, { backgroundColor: cfg.bg, borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
            <Ionicons name={cfg.icon} size={s(11)} color={cfg.color} />
            <Text style={[styles.badgeText, { fontSize: s(10), color: cfg.color }]}>{cfg.label}</Text>
          </View>
          {item.doctor_specialization && (
            <View style={[styles.badge, { backgroundColor: BG, borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
              <Ionicons name="person-outline" size={s(11)} color={GRAY} />
              <Text style={[styles.badgeText, { fontSize: s(10), color: GRAY }]}>{item.doctor_specialization}</Text>
            </View>
          )}
        </View>

        {/* Description box */}
        {item.description && (
          <View style={[styles.descBox, { borderRadius: s(10), padding: s(10), gap: s(6), marginBottom: s(8) }]}>
            <Ionicons name="document-text-outline" size={s(13)} color={TEAL} />
            <Text style={[styles.descText, { fontSize: s(12) }]} numberOfLines={expanded ? undefined : 2}>
              {item.description}
            </Text>
          </View>
        )}

        {/* Expanded sections */}
        {expanded && (
          <View style={{ gap: s(8), marginBottom: s(8) }}>
            {item.observations && (
              <View style={[styles.expandedBlock, { borderLeftColor: BLUE, paddingLeft: s(10), paddingVertical: s(4) }]}>
                <View style={[styles.expandedBlockHeader, { gap: s(5), marginBottom: s(3) }]}>
                  <Ionicons name="eye-outline" size={s(12)} color={BLUE} />
                  <Text style={[styles.expandedBlockLabel, { fontSize: s(10), color: BLUE }]}>OBSERVATIONS</Text>
                </View>
                <Text style={[styles.expandedBlockText, { fontSize: s(12) }]}>{item.observations}</Text>
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
            {item.icd_codes && item.icd_codes.length > 0 && (
              <View style={[styles.expandedBlock, { borderLeftColor: PURPLE, paddingLeft: s(10), paddingVertical: s(4) }]}>
                <View style={[styles.expandedBlockHeader, { gap: s(5), marginBottom: s(3) }]}>
                  <Ionicons name="barcode-outline" size={s(12)} color={PURPLE} />
                  <Text style={[styles.expandedBlockLabel, { fontSize: s(10), color: PURPLE }]}>ICD CODES</Text>
                </View>
                <View style={[styles.icdChipsRow, { gap: s(6), marginTop: s(4) }]}>
                  {item.icd_codes.map((icd, i) => (
                    <View key={i} style={[styles.icdChip, { borderRadius: s(6), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
                      <Text style={[styles.icdChipText, { fontSize: s(10) }]}>{icd.code} — {icd.description}</Text>
                    </View>
                  ))}
                </View>
              </View>
            )}
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

export function PatientMobileMedicalRecords({ onBack }: { onBack?: () => void }) {
  const router  = useRouter();
  const insets  = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  const { records, loading, error, refetch } = useMedicalRecords();
  const [filter, setFilter] = useState<FilterKey>('ALL');

  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));

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
          <Text style={[styles.headerTitle, { fontSize: s(18) }]}>Medical Records</Text>
          <Text style={[styles.headerSub, { fontSize: s(11) }]}>Your complete health record</Text>
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
          { value: total,       label: 'Total',       color: INDIGO },
          { value: clinical,    label: 'Clinical',    color: TEAL   },
          { value: diagnostics, label: 'Diagnostics', color: BLUE   },
          { value: documents,   label: 'Documents',   color: GREEN  },
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
          const count = f.key === 'ALL' ? total : records.filter(r => r.record_type === f.key).length;
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
            <Text style={[styles.centerText, { fontSize: s(14) }]}>Loading records…</Text>
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
            <Ionicons name="document-outline" size={s(48)} color={BORDER} />
            <Text style={[styles.centerText, { fontSize: s(14) }]}>
              {filter === 'ALL'
                ? 'No medical records found.'
                : `No ${FILTERS.find(f => f.key === filter)?.label.toLowerCase()} records.`}
            </Text>
          </View>
        ) : (
          filtered.map(item => <RecordCard key={item.record_id} item={item} s={s} />)
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

  card:           { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER, borderLeftWidth: 4, overflow: 'hidden', shadowColor: '#000', shadowOpacity: 0.04, shadowRadius: 8, elevation: 2 } as any,
  cardHeader:     { flexDirection: 'row', alignItems: 'center' },
  typeIconCircle: { alignItems: 'center', justifyContent: 'center' },
  cardTitle:      { fontWeight: '700', color: TEXT },
  cardDoctorName: { color: GRAY, marginTop: 2 },
  cardDoctorSpec: { color: GRAY, marginTop: 1 },

  dateChip:     { backgroundColor: BG, borderWidth: 1, borderColor: BORDER },
  dateChipText: { color: GRAY, fontWeight: '500' },

  badgeRow:  { flexDirection: 'row' },
  badge:     { flexDirection: 'row', alignItems: 'center' },
  badgeText: { fontWeight: '700' },

  descBox:  { flexDirection: 'row', alignItems: 'flex-start', backgroundColor: '#E6F4F1' },
  descText: { flex: 1, color: TEXT, lineHeight: 18 },

  expandedBlock:       { borderLeftWidth: 3 },
  expandedBlockHeader: { flexDirection: 'row', alignItems: 'center' },
  expandedBlockLabel:  { fontWeight: '700', letterSpacing: 0.5 },
  expandedBlockText:   { color: TEXT, lineHeight: 18 },

  icdChipsRow: { flexDirection: 'row', flexWrap: 'wrap' },
  icdChip:     { backgroundColor: '#F5F3FF', borderWidth: 1, borderColor: '#DDD6FE' },
  icdChipText: { color: PURPLE, fontWeight: '600' },

  expandToggle:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', borderTopWidth: 1, borderTopColor: BORDER },
  expandToggleText: { fontWeight: '600', color: TEAL },

  centerState: { alignItems: 'center', justifyContent: 'center', paddingVertical: 60, gap: 12 },
  centerText:  { color: GRAY, textAlign: 'center' },
  retryBtn:    { backgroundColor: TEAL },
  retryBtnText:{ fontWeight: '700', color: WHITE },
});

export default PatientMobileMedicalRecords;
