import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import {
  KeyboardAvoidingView, Platform, ScrollView,
  StyleSheet, Text, TouchableOpacity, useWindowDimensions, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useLabResults, type LabResult } from '@/hooks/use-lab-results';

const TEAL   = '#0D9488';
const INDIGO = '#4F46E5';
const GREEN  = '#10B981';
const AMBER  = '#F59E0B';
const RED    = '#EF4444';
const PURPLE = '#8B5CF6';
const BLUE   = '#3B82F6';
const GRAY   = '#6B7280';
const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type TestType     = LabResult['test_type'];
type StatusType   = LabResult['status'];
type FilterKey    = 'ALL' | TestType;

const TYPE_CONFIG: Record<TestType, { label: string; color: string; bg: string; icon: IoniconsName }> = {
  BLOOD:   { label: 'Blood Test', color: RED,       bg: '#FEF2F2', icon: 'water-outline'      },
  URINE:   { label: 'Urinalysis', color: AMBER,     bg: '#FFFBEB', icon: 'beaker-outline'     },
  STOOL:   { label: 'Stool Test', color: '#78716C', bg: '#F5F5F4', icon: 'leaf-outline'       },
  CULTURE: { label: 'Culture',    color: GREEN,     bg: '#ECFDF5', icon: 'bug-outline'        },
  BIOPSY:  { label: 'Biopsy',     color: PURPLE,    bg: '#F5F3FF', icon: 'cut-outline'        },
  GENETIC: { label: 'Genetic',    color: INDIGO,    bg: '#EEF2FF', icon: 'git-branch-outline' },
  SWAB:    { label: 'Swab',       color: TEAL,      bg: '#E6F4F1', icon: 'bandage-outline'    },
  OTHER:   { label: 'Other',      color: GRAY,      bg: '#F3F4F6', icon: 'flask-outline'      },
};

const STATUS_CONFIG: Record<StatusType, { label: string; color: string; bg: string; icon: IoniconsName }> = {
  COMPLETED:      { label: 'Completed',    color: TEAL,   bg: '#E6F4F1', icon: 'checkmark-done-outline' },
  PENDING:        { label: 'Pending',      color: AMBER,  bg: '#FFFBEB', icon: 'hourglass-outline'      },
  CRITICAL:       { label: 'Critical',     color: RED,    bg: '#FEF2F2', icon: 'alert-circle-outline'   },
  REQUIRES_REVIEW:{ label: 'Needs Review', color: PURPLE, bg: '#F5F3FF', icon: 'eye-outline'            },
  CANCELLED:      { label: 'Cancelled',    color: GRAY,   bg: '#F3F4F6', icon: 'close-circle-outline'   },
};

const FILTERS: { key: FilterKey; label: string }[] = [
  { key: 'ALL',     label: 'All'     },
  { key: 'BLOOD',   label: 'Blood'   },
  { key: 'URINE',   label: 'Urine'   },
  { key: 'SWAB',    label: 'Swab'    },
  { key: 'CULTURE', label: 'Culture' },
  { key: 'BIOPSY',  label: 'Biopsy'  },
  { key: 'GENETIC', label: 'Genetic' },
  { key: 'STOOL',   label: 'Stool'   },
  { key: 'OTHER',   label: 'Other'   },
];

const FLAG_COLORS: Record<string, string> = {
  HIGH: RED, LOW: BLUE, NORMAL: GREEN, CRITICAL: RED, ABNORMAL: AMBER,
};

function formatDate(iso: string | null) {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

type AnalyteEntry = { value?: unknown; unit?: string; reference?: string; flag?: string };

function isAnalyteEntry(v: unknown): v is AnalyteEntry {
  return typeof v === 'object' && v !== null && 'value' in v;
}

function ResultDataSection({ data, s }: { data: Record<string, unknown>; s: (n: number) => number }) {
  const analytes: [string, AnalyteEntry][]                          = [];
  const textEntries: [string, string][]                             = [];
  const objectEntries: [string, Record<string, unknown>][]          = [];

  for (const [k, v] of Object.entries(data)) {
    if (k === 'comment') continue;
    if (isAnalyteEntry(v))              analytes.push([k, v]);
    else if (typeof v === 'string')     textEntries.push([k, v]);
    else if (typeof v === 'object' && v !== null) objectEntries.push([k, v as Record<string, unknown>]);
  }

  const comment = data['comment'] as string | undefined;

  return (
    <View style={{ gap: s(10) }}>
      {/* Analyte rows */}
      {analytes.length > 0 && (
        <View style={[styles.analyteTable, { borderRadius: s(10) }]}>
          {/* header */}
          <View style={[styles.analyteHeader, { paddingHorizontal: s(10), paddingVertical: s(7) }]}>
            <Text style={[styles.analyteColHdr, { fontSize: s(10), flex: 2 }]}>Test</Text>
            <Text style={[styles.analyteColHdr, { fontSize: s(10), flex: 1.5 }]}>Result</Text>
            <Text style={[styles.analyteColHdr, { fontSize: s(10), flex: 1 }]}>Flag</Text>
          </View>
          {analytes.map(([key, a]) => {
            const flag       = (a.flag ?? '').toUpperCase();
            const flagColor  = FLAG_COLORS[flag] ?? GRAY;
            const isAbnorm   = flag !== 'NORMAL' && flag !== '';
            return (
              <View
                key={key}
                style={[
                  styles.analyteRow,
                  { paddingHorizontal: s(10), paddingVertical: s(8) },
                  isAbnorm && { backgroundColor: flagColor + '08' },
                ]}
              >
                <Text style={[styles.analyteTestName, { fontSize: s(11), flex: 2 }]}>
                  {key.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase())}
                </Text>
                <Text style={[styles.analyteVal, { fontSize: s(11), flex: 1.5, color: isAbnorm ? flagColor : TEXT, fontWeight: isAbnorm ? '700' : '500' }]}>
                  {String(a.value ?? '—')}{a.unit ? ` ${a.unit}` : ''}
                </Text>
                <View style={{ flex: 1, alignItems: 'flex-start' }}>
                  {flag ? (
                    <View style={[styles.flagBadge, { borderRadius: s(5), paddingHorizontal: s(5), paddingVertical: s(2), backgroundColor: flagColor + '18', borderColor: flagColor + '40' }]}>
                      <Text style={[styles.flagBadgeText, { fontSize: s(9), color: flagColor }]}>{flag}</Text>
                    </View>
                  ) : null}
                </View>
              </View>
            );
          })}
        </View>
      )}

      {/* String fields */}
      {textEntries.map(([k, v]) => (
        <View key={k} style={{ gap: s(3) }}>
          <Text style={[styles.textLabel, { fontSize: s(10) }]}>{k.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase())}</Text>
          <Text style={[styles.textValue, { fontSize: s(12) }]}>{v}</Text>
        </View>
      ))}

      {/* Sensitivity objects */}
      {objectEntries.map(([k, obj]) => (
        <View key={k} style={{ gap: s(5) }}>
          <Text style={[styles.textLabel, { fontSize: s(10) }]}>{k.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase())}</Text>
          <View style={[styles.sensitivityWrap, { gap: s(6) }]}>
            {Object.entries(obj).map(([drug, result]) => (
              <View key={drug} style={[styles.sensitivityItem, { borderRadius: s(8), paddingHorizontal: s(8), paddingVertical: s(4), gap: s(3),
                backgroundColor: result === 'SENSITIVE' ? '#ECFDF5' : result === 'RESISTANT' ? '#FEF2F2' : '#FFFBEB' }]}>
                <Text style={[styles.sensitivityDrug, { fontSize: s(10) }]}>{drug.replace(/_/g, ' ')}</Text>
                <Text style={[styles.sensitivityResult, { fontSize: s(10),
                  color: result === 'SENSITIVE' ? GREEN : result === 'RESISTANT' ? RED : AMBER }]}>
                  {String(result)}
                </Text>
              </View>
            ))}
          </View>
        </View>
      ))}

      {/* Comment */}
      {comment && (
        <View style={[styles.commentBox, { borderRadius: s(10), padding: s(10), gap: s(6) }]}>
          <Ionicons name="information-circle-outline" size={s(13)} color={TEAL} />
          <Text style={[styles.commentText, { fontSize: s(12) }]}>{comment}</Text>
        </View>
      )}
    </View>
  );
}

function LabResultCard({ item, s }: { item: LabResult; s: (n: number) => number }) {
  const [expanded, setExpanded] = useState(false);
  const typeCfg    = TYPE_CONFIG[item.test_type]  ?? TYPE_CONFIG.OTHER;
  const statusCfg  = STATUS_CONFIG[item.status]   ?? STATUS_CONFIG.PENDING;
  const isCritical = item.is_critical === 1 || item.status === 'CRITICAL';

  return (
    <View style={[styles.card, { borderRadius: s(16), marginBottom: s(12), borderLeftColor: isCritical ? RED : typeCfg.color }]}>
      {/* Critical banner */}
      {isCritical && (
        <View style={[styles.criticalBanner, { paddingHorizontal: s(12), paddingVertical: s(7), gap: s(6) }]}>
          <Ionicons name="alert-circle" size={s(12)} color={WHITE} />
          <Text style={[styles.criticalText, { fontSize: s(11) }]}>CRITICAL — Requires immediate attention</Text>
        </View>
      )}

      <View style={{ padding: s(14) }}>
        {/* Header */}
        <View style={[styles.cardHeader, { gap: s(10), marginBottom: s(10) }]}>
          <View style={[styles.typeIconCircle, { width: s(38), height: s(38), borderRadius: s(19), backgroundColor: typeCfg.bg }]}>
            <Ionicons name={typeCfg.icon} size={s(18)} color={typeCfg.color} />
          </View>
          <View style={{ flex: 1 }}>
            <Text style={[styles.cardTitle, { fontSize: s(13) }]}>{item.test_name}</Text>
            <Text style={[styles.cardDoctor, { fontSize: s(11) }]}>{item.doctor_name}</Text>
          </View>
          <View style={[styles.statusBadge, { borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3), backgroundColor: statusCfg.bg, gap: s(3) }]}>
            <Ionicons name={statusCfg.icon} size={s(10)} color={statusCfg.color} />
            <Text style={[styles.statusText, { fontSize: s(10), color: statusCfg.color }]}>{statusCfg.label}</Text>
          </View>
        </View>

        {/* Type badge + spec */}
        <View style={[styles.badgeRow, { gap: s(6), marginBottom: s(10) }]}>
          <View style={[styles.badge, { borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3), backgroundColor: typeCfg.bg, gap: s(4) }]}>
            <Ionicons name={typeCfg.icon} size={s(11)} color={typeCfg.color} />
            <Text style={[styles.badgeText, { fontSize: s(10), color: typeCfg.color }]}>{typeCfg.label}</Text>
          </View>
          {item.doctor_specialization && (
            <View style={[styles.badge, { borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3), backgroundColor: BG, gap: s(4) }]}>
              <Ionicons name="person-outline" size={s(11)} color={GRAY} />
              <Text style={[styles.badgeText, { fontSize: s(10), color: GRAY }]}>{item.doctor_specialization}</Text>
            </View>
          )}
        </View>

        {/* Meta */}
        <View style={[styles.metaRow, { gap: s(10), marginBottom: s(10), flexWrap: 'wrap' }]}>
          <View style={[styles.metaItem, { gap: s(3) }]}>
            <Ionicons name="calendar-outline" size={s(11)} color={GRAY} />
            <Text style={[styles.metaLabel, { fontSize: s(10) }]}>Test date</Text>
            <Text style={[styles.metaValue, { fontSize: s(11) }]}>{formatDate(item.test_date)}</Text>
          </View>
          {item.reviewed_at && (
            <View style={[styles.metaItem, { gap: s(3) }]}>
              <Ionicons name="checkmark-circle-outline" size={s(11)} color={TEAL} />
              <Text style={[styles.metaLabel, { fontSize: s(10) }]}>Reviewed</Text>
              <Text style={[styles.metaValue, { fontSize: s(11), color: TEAL }]}>{formatDate(item.reviewed_at)}</Text>
            </View>
          )}
        </View>

        {/* Top-level result value */}
        {item.result_value && (
          <View style={[styles.resultValueBox, { borderRadius: s(10), padding: s(10), marginBottom: s(10), gap: s(6) }]}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(5) }}>
              <Ionicons name="flask-outline" size={s(13)} color={INDIGO} />
              <Text style={[styles.resultValueLabel, { fontSize: s(11) }]}>Result</Text>
            </View>
            <Text style={[styles.resultValueText, { fontSize: s(13) }]}>
              {item.result_value}{item.unit ? ` ${item.unit}` : ''}
            </Text>
            {item.reference_range && (
              <Text style={[styles.resultRef, { fontSize: s(10) }]}>Ref: {item.reference_range}</Text>
            )}
          </View>
        )}

        {/* Expanded result data */}
        {expanded && item.result_data && (
          <View style={{ marginBottom: s(8) }}>
            <ResultDataSection data={item.result_data as Record<string, unknown>} s={s} />
          </View>
        )}

        {/* Expand toggle */}
        {item.result_data && (
          <TouchableOpacity
            style={[styles.expandToggle, { paddingTop: s(8), gap: s(4) }]}
            onPress={() => setExpanded(e => !e)}
            activeOpacity={0.7}
          >
            <Text style={[styles.expandToggleText, { fontSize: s(12) }]}>
              {expanded ? 'Hide results ▲' : 'View full results ▼'}
            </Text>
          </TouchableOpacity>
        )}
      </View>
    </View>
  );
}

export function PatientMobileLabResults({ onBack }: { onBack?: () => void }) {
  const router  = useRouter();
  const insets  = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  const { results, loading, error, refetch } = useLabResults();
  const [filter, setFilter] = useState<FilterKey>('ALL');

  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));

  const filtered  = filter === 'ALL' ? results : results.filter(r => r.test_type === filter);
  const total     = results.length;
  const completed = results.filter(r => r.status === 'COMPLETED').length;
  const pending   = results.filter(r => r.status === 'PENDING' || r.status === 'REQUIRES_REVIEW').length;
  const critical  = results.filter(r => r.is_critical === 1 || r.status === 'CRITICAL').length;

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
          <Text style={[styles.headerTitle, { fontSize: s(18) }]}>Lab Results</Text>
          <Text style={[styles.headerSub, { fontSize: s(11) }]}>Your laboratory test history</Text>
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
          { value: total,     label: 'Total',     color: INDIGO  },
          { value: completed, label: 'Completed', color: GREEN   },
          { value: pending,   label: 'Pending',   color: AMBER   },
          { value: critical,  label: 'Critical',  color: RED     },
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
          const count = f.key === 'ALL' ? total : results.filter(r => r.test_type === f.key).length;
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
            <Text style={[styles.centerText, { fontSize: s(14) }]}>Loading lab results…</Text>
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
            <Ionicons name="flask-outline" size={s(48)} color={BORDER} />
            <Text style={[styles.centerText, { fontSize: s(14) }]}>
              {filter === 'ALL'
                ? 'No lab results found.'
                : `No ${FILTERS.find(f => f.key === filter)?.label.toLowerCase()} results.`}
            </Text>
          </View>
        ) : (
          filtered.map(item => <LabResultCard key={item.result_id} item={item} s={s} />)
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

  card:          { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER, borderLeftWidth: 4, overflow: 'hidden', shadowColor: '#000', shadowOpacity: 0.04, shadowRadius: 8, elevation: 2 } as any,
  criticalBanner:{ flexDirection: 'row', alignItems: 'center', backgroundColor: RED },
  criticalText:  { color: WHITE, fontWeight: '700', flex: 1 },

  cardHeader:    { flexDirection: 'row', alignItems: 'center' },
  typeIconCircle:{ alignItems: 'center', justifyContent: 'center' },
  cardTitle:     { fontWeight: '700', color: TEXT },
  cardDoctor:    { color: GRAY, marginTop: 2 },

  statusBadge: { flexDirection: 'row', alignItems: 'center' },
  statusText:  { fontWeight: '700' },

  badgeRow:  { flexDirection: 'row' },
  badge:     { flexDirection: 'row', alignItems: 'center' },
  badgeText: { fontWeight: '700' },

  metaRow:   { flexDirection: 'row' },
  metaItem:  { flexDirection: 'row', alignItems: 'center' },
  metaLabel: { color: GRAY },
  metaValue: { fontWeight: '600', color: TEXT },

  resultValueBox:  { backgroundColor: '#EEF2FF' },
  resultValueLabel:{ fontWeight: '700', color: INDIGO },
  resultValueText: { fontWeight: '700', color: TEXT },
  resultRef:       { color: GRAY },

  analyteTable:  { borderWidth: 1, borderColor: BORDER, overflow: 'hidden' },
  analyteHeader: { flexDirection: 'row', backgroundColor: BG, borderBottomWidth: 1, borderBottomColor: BORDER },
  analyteColHdr: { fontWeight: '700', color: GRAY, textTransform: 'uppercase', letterSpacing: 0.3 },
  analyteRow:    { flexDirection: 'row', borderBottomWidth: 1, borderBottomColor: BORDER },
  analyteTestName:{ color: TEXT, fontWeight: '500' },
  analyteVal:    { color: TEXT },
  flagBadge:     { borderWidth: 1 },
  flagBadgeText: { fontWeight: '700' },

  textLabel:  { color: GRAY, fontWeight: '700', textTransform: 'uppercase', letterSpacing: 0.5 },
  textValue:  { color: TEXT, lineHeight: 18 },

  sensitivityWrap:  { flexDirection: 'row', flexWrap: 'wrap' },
  sensitivityItem:  { alignItems: 'center' },
  sensitivityDrug:  { color: GRAY },
  sensitivityResult:{ fontWeight: '700' },

  commentBox:  { flexDirection: 'row', alignItems: 'flex-start', backgroundColor: '#E6F4F1' },
  commentText: { flex: 1, color: TEXT, lineHeight: 18 },

  expandToggle:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', borderTopWidth: 1, borderTopColor: BORDER },
  expandToggleText: { fontWeight: '600', color: TEAL },

  centerState: { alignItems: 'center', justifyContent: 'center', paddingVertical: 60, gap: 12 },
  centerText:  { color: GRAY, textAlign: 'center' },
  retryBtn:    { backgroundColor: TEAL },
  retryBtnText:{ fontWeight: '700', color: WHITE },
});

export default PatientMobileLabResults;
