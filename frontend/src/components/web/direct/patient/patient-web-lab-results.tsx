import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
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
const CARD   = '#FFFFFF';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type TestType     = LabResult['test_type'];
type StatusType   = LabResult['status'];
type FilterKey    = 'ALL' | TestType;

const TYPE_CONFIG: Record<TestType, { label: string; color: string; bg: string; icon: IoniconsName }> = {
  BLOOD:   { label: 'Blood Test',  color: RED,    bg: '#FEF2F2', icon: 'water-outline'        },
  URINE:   { label: 'Urinalysis',  color: AMBER,  bg: '#FFFBEB', icon: 'beaker-outline'       },
  STOOL:   { label: 'Stool Test',  color: '#78716C', bg: '#F5F5F4', icon: 'leaf-outline'      },
  CULTURE: { label: 'Culture',     color: GREEN,  bg: '#ECFDF5', icon: 'bug-outline'          },
  BIOPSY:  { label: 'Biopsy',      color: PURPLE, bg: '#F5F3FF', icon: 'cut-outline'          },
  GENETIC: { label: 'Genetic',     color: INDIGO, bg: '#EEF2FF', icon: 'git-branch-outline'   },
  SWAB:    { label: 'Swab',        color: TEAL,   bg: '#E6F4F1', icon: 'bandage-outline'      },
  OTHER:   { label: 'Other',       color: GRAY,   bg: '#F3F4F6', icon: 'flask-outline'        },
};

const STATUS_CONFIG: Record<StatusType, { label: string; color: string; bg: string; icon: IoniconsName }> = {
  COMPLETED:      { label: 'Completed',      color: TEAL,   bg: '#E6F4F1', icon: 'checkmark-done-outline'   },
  PENDING:        { label: 'Pending',        color: AMBER,  bg: '#FFFBEB', icon: 'hourglass-outline'        },
  CRITICAL:       { label: 'Critical',       color: RED,    bg: '#FEF2F2', icon: 'alert-circle-outline'     },
  REQUIRES_REVIEW:{ label: 'Needs Review',   color: PURPLE, bg: '#F5F3FF', icon: 'eye-outline'              },
  CANCELLED:      { label: 'Cancelled',      color: GRAY,   bg: '#F3F4F6', icon: 'close-circle-outline'     },
};

const FILTERS: { key: FilterKey; label: string }[] = [
  { key: 'ALL',     label: 'All'      },
  { key: 'BLOOD',   label: 'Blood'    },
  { key: 'URINE',   label: 'Urine'    },
  { key: 'SWAB',    label: 'Swab'     },
  { key: 'CULTURE', label: 'Culture'  },
  { key: 'BIOPSY',  label: 'Biopsy'   },
  { key: 'GENETIC', label: 'Genetic'  },
  { key: 'STOOL',   label: 'Stool'    },
  { key: 'OTHER',   label: 'Other'    },
];

const FLAG_COLORS: Record<string, string> = {
  HIGH: RED, LOW: BLUE, NORMAL: GREEN, CRITICAL: RED, ABNORMAL: AMBER,
};

function formatDate(iso: string | null) {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

type AnalyteEntry = { value?: unknown; unit?: string; reference?: string; flag?: string; interpretation?: string };

function isAnalyteEntry(v: unknown): v is AnalyteEntry {
  return typeof v === 'object' && v !== null && 'value' in v;
}

function ResultDataSection({ data }: { data: Record<string, unknown> }) {
  const analytes: [string, AnalyteEntry][] = [];
  const textEntries: [string, string][]     = [];
  const objectEntries: [string, Record<string, unknown>][] = [];

  for (const [k, v] of Object.entries(data)) {
    if (k === 'comment') continue;
    if (isAnalyteEntry(v))              analytes.push([k, v]);
    else if (typeof v === 'string')     textEntries.push([k, v]);
    else if (typeof v === 'object' && v !== null) objectEntries.push([k, v as Record<string, unknown>]);
  }

  const comment = data['comment'] as string | undefined;

  return (
    <View style={styles.resultDataWrap}>
      {/* Analyte table */}
      {analytes.length > 0 && (
        <View style={styles.analyteTable}>
          <View style={styles.analyteHeader}>
            <Text style={[styles.analyteCol, styles.analyteColTest]}>Test</Text>
            <Text style={[styles.analyteCol, styles.analyteColVal]}>Result</Text>
            <Text style={[styles.analyteCol, styles.analyteColRef]}>Reference</Text>
            <Text style={[styles.analyteCol, styles.analyteColFlag]}>Flag</Text>
          </View>
          {analytes.map(([key, a]) => {
            const flag      = (a.flag ?? '').toUpperCase();
            const flagColor = FLAG_COLORS[flag] ?? GRAY;
            const isAbnorm  = flag !== 'NORMAL' && flag !== '';
            return (
              <View key={key} style={[styles.analyteRow, isAbnorm && { backgroundColor: flagColor + '08' }]}>
                <Text style={[styles.analyteCol, styles.analyteColTest, styles.analyteTestLabel]}>
                  {key.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase())}
                </Text>
                <Text style={[styles.analyteCol, styles.analyteColVal, styles.analyteVal, isAbnorm && { color: flagColor, fontWeight: '700' }]}>
                  {String(a.value ?? '—')} {a.unit ?? ''}
                </Text>
                <Text style={[styles.analyteCol, styles.analyteColRef, styles.analyteRef]}>{a.reference ?? '—'}</Text>
                <View style={[styles.analyteColFlag]}>
                  {flag ? (
                    <View style={[styles.flagBadge, { backgroundColor: flagColor + '18', borderColor: flagColor + '40' }]}>
                      <Text style={[styles.flagBadgeText, { color: flagColor }]}>{flag}</Text>
                    </View>
                  ) : null}
                </View>
              </View>
            );
          })}
        </View>
      )}

      {/* String text fields */}
      {textEntries.map(([k, v]) => (
        <View key={k} style={styles.textEntry}>
          <Text style={styles.textEntryLabel}>{k.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase())}</Text>
          <Text style={styles.textEntryValue}>{v}</Text>
        </View>
      ))}

      {/* Object fields (e.g. sensitivity) */}
      {objectEntries.map(([k, obj]) => (
        <View key={k} style={styles.textEntry}>
          <Text style={styles.textEntryLabel}>{k.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase())}</Text>
          <View style={styles.sensitivityGrid}>
            {Object.entries(obj).map(([drug, result]) => (
              <View key={drug} style={styles.sensitivityItem}>
                <Text style={styles.sensitivityDrug}>{drug.replace(/_/g, ' ')}</Text>
                <View style={[styles.sensitivityBadge, {
                  backgroundColor: result === 'SENSITIVE' ? '#ECFDF5' : result === 'RESISTANT' ? '#FEF2F2' : '#FFFBEB',
                }]}>
                  <Text style={[styles.sensitivityResult, {
                    color: result === 'SENSITIVE' ? GREEN : result === 'RESISTANT' ? RED : AMBER,
                  }]}>{String(result)}</Text>
                </View>
              </View>
            ))}
          </View>
        </View>
      ))}

      {/* Comment */}
      {comment && (
        <View style={styles.commentBox}>
          <Ionicons name="information-circle-outline" size={14} color={TEAL} />
          <Text style={styles.commentText}>{comment}</Text>
        </View>
      )}
    </View>
  );
}

function LabResultCard({ item }: { item: LabResult }) {
  const [expanded, setExpanded] = useState(false);
  const typeCfg   = TYPE_CONFIG[item.test_type]   ?? TYPE_CONFIG.OTHER;
  const statusCfg = STATUS_CONFIG[item.status]    ?? STATUS_CONFIG.PENDING;
  const isCritical = item.is_critical === 1 || item.status === 'CRITICAL';

  return (
    <View style={[styles.card, { borderLeftColor: isCritical ? RED : typeCfg.color }]}>
      {/* Critical banner */}
      {isCritical && (
        <View style={styles.criticalBanner}>
          <Ionicons name="alert-circle" size={14} color={WHITE} />
          <Text style={styles.criticalBannerText}>CRITICAL RESULT — Requires immediate attention</Text>
        </View>
      )}

      {/* Header */}
      <View style={styles.cardHeader}>
        <View style={[styles.typeIconSquare, { backgroundColor: typeCfg.bg }]}>
          <Ionicons name={typeCfg.icon} size={20} color={typeCfg.color} />
        </View>
        <View style={styles.cardHeaderText}>
          <Text style={styles.cardTitle}>{item.test_name}</Text>
          <Text style={styles.cardDoctorName}>{item.doctor_name}</Text>
        </View>
        <View style={[styles.statusBadge, { backgroundColor: statusCfg.bg }]}>
          <Ionicons name={statusCfg.icon} size={12} color={statusCfg.color} />
          <Text style={[styles.statusBadgeText, { color: statusCfg.color }]}>{statusCfg.label}</Text>
        </View>
      </View>

      {/* Badge row */}
      <View style={styles.badgeRow}>
        <View style={[styles.badge, { backgroundColor: typeCfg.bg }]}>
          <Ionicons name={typeCfg.icon} size={12} color={typeCfg.color} />
          <Text style={[styles.badgeText, { color: typeCfg.color }]}>{typeCfg.label}</Text>
        </View>
        {item.doctor_specialization && (
          <View style={[styles.badge, { backgroundColor: BG }]}>
            <Ionicons name="person-outline" size={12} color={GRAY} />
            <Text style={[styles.badgeText, { color: GRAY }]}>{item.doctor_specialization}</Text>
          </View>
        )}
      </View>

      {/* Meta row */}
      <View style={styles.metaRow}>
        <View style={styles.metaItem}>
          <Ionicons name="calendar-outline" size={13} color={GRAY} />
          <Text style={styles.metaLabel}>Test date</Text>
          <Text style={styles.metaValue}>{formatDate(item.test_date)}</Text>
        </View>
        {item.reviewed_at && (
          <View style={styles.metaItem}>
            <Ionicons name="checkmark-circle-outline" size={13} color={TEAL} />
            <Text style={styles.metaLabel}>Reviewed</Text>
            <Text style={[styles.metaValue, { color: TEAL }]}>{formatDate(item.reviewed_at)}</Text>
          </View>
        )}
        {item.reviewer_name && (
          <View style={styles.metaItem}>
            <Ionicons name="person-circle-outline" size={13} color={GRAY} />
            <Text style={styles.metaLabel}>By</Text>
            <Text style={styles.metaValue}>{item.reviewer_name}</Text>
          </View>
        )}
      </View>

      {/* Top-level result value */}
      {item.result_value && (
        <View style={styles.resultValueBox}>
          <View style={styles.resultValueLeft}>
            <Ionicons name="flask-outline" size={14} color={INDIGO} />
            <Text style={styles.resultValueLabel}>Result</Text>
          </View>
          <Text style={styles.resultValueText}>
            {item.result_value}{item.unit ? ` ${item.unit}` : ''}
          </Text>
          {item.reference_range && (
            <Text style={styles.resultRefText}>Ref: {item.reference_range}</Text>
          )}
        </View>
      )}

      {/* Expanded detail */}
      {expanded && item.result_data && (
        <ResultDataSection data={item.result_data as Record<string, unknown>} />
      )}

      {/* Expand toggle */}
      {item.result_data && (
        <TouchableOpacity style={styles.expandToggle} onPress={() => setExpanded(e => !e)} activeOpacity={0.7}>
          <Text style={styles.expandToggleText}>{expanded ? 'Hide results ▲' : 'View full results ▼'}</Text>
        </TouchableOpacity>
      )}
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

export function PatientWebLabResults({ onBack }: { onBack?: () => void }) {
  const { results, loading, error, refetch } = useLabResults();
  const [filter, setFilter] = useState<FilterKey>('ALL');

  const filtered      = filter === 'ALL' ? results : results.filter(r => r.test_type === filter);
  const total         = results.length;
  const completed     = results.filter(r => r.status === 'COMPLETED').length;
  const pending       = results.filter(r => r.status === 'PENDING' || r.status === 'REQUIRES_REVIEW').length;
  const critical      = results.filter(r => r.is_critical === 1 || r.status === 'CRITICAL').length;

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
      {/* Header */}
      <View style={styles.pageHeader}>
        <TouchableOpacity style={styles.backBtn} onPress={onBack} activeOpacity={0.7}>
          <Ionicons name="arrow-back-outline" size={16} color={TEAL} />
          <Text style={styles.backBtnText}>Back</Text>
        </TouchableOpacity>
        <View style={styles.pageHeaderCenter}>
          <Text style={styles.pageTitle}>Lab Results</Text>
          <Text style={styles.pageSub}>Your laboratory test history</Text>
        </View>
        <TouchableOpacity style={styles.refreshBtn} onPress={refetch} activeOpacity={0.7}>
          <Ionicons name="refresh-outline" size={18} color={TEAL} />
        </TouchableOpacity>
      </View>

      {/* Stats */}
      <View style={styles.statsRow}>
        <StatCard value={total}     label="Total"     color={INDIGO} icon="flask-outline"              />
        <StatCard value={completed} label="Completed" color={GREEN}  icon="checkmark-done-outline"     />
        <StatCard value={pending}   label="Pending"   color={AMBER}  icon="hourglass-outline"          />
        <StatCard value={critical}  label="Critical"  color={RED}    icon="alert-circle-outline"       />
      </View>

      {/* Filter pills */}
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.filterScroll} contentContainerStyle={styles.filterRow}>
        {FILTERS.map(f => {
          const on    = filter === f.key;
          const count = f.key === 'ALL' ? total : results.filter(r => r.test_type === f.key).length;
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
          <Text style={styles.centerStateText}>Loading lab results…</Text>
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
          <Ionicons name="flask-outline" size={48} color={BORDER} />
          <Text style={styles.centerStateText}>
            {filter === 'ALL' ? 'No lab results found.' : `No ${FILTERS.find(f => f.key === filter)?.label.toLowerCase()} results.`}
          </Text>
        </View>
      ) : (
        <View style={styles.cardList}>
          {filtered.map(item => (
            <LabResultCard key={item.result_id} item={item} />
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

  card: { backgroundColor: CARD, borderRadius: 12, borderWidth: 1, borderColor: BORDER, borderLeftWidth: 4, overflow: 'hidden', shadowColor: '#000', shadowOpacity: 0.04, shadowRadius: 10, elevation: 2 } as any,

  criticalBanner:     { flexDirection: 'row', alignItems: 'center', gap: 8, backgroundColor: RED, paddingHorizontal: 16, paddingVertical: 8 },
  criticalBannerText: { fontSize: 12, fontWeight: '700', color: WHITE, flex: 1 },

  cardHeader:     { flexDirection: 'row', alignItems: 'flex-start', gap: 12, padding: 18, paddingBottom: 0 },
  typeIconSquare: { width: 40, height: 40, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  cardHeaderText: { flex: 1 },
  cardTitle:      { fontSize: 15, fontWeight: '700', color: TEXT },
  cardDoctorName: { fontSize: 12, color: GRAY, marginTop: 2 },
  statusBadge:    { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 10, paddingVertical: 4, borderRadius: 20 },
  statusBadgeText:{ fontSize: 11, fontWeight: '700' },

  badgeRow:  { flexDirection: 'row', gap: 8, paddingHorizontal: 18, paddingVertical: 10 },
  badge:     { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 10, paddingVertical: 4, borderRadius: 20 },
  badgeText: { fontSize: 11, fontWeight: '700' },

  metaRow:   { flexDirection: 'row', gap: 14, paddingHorizontal: 18, paddingBottom: 12, flexWrap: 'wrap' },
  metaItem:  { flexDirection: 'row', alignItems: 'center', gap: 4 },
  metaLabel: { fontSize: 11, color: GRAY },
  metaValue: { fontSize: 12, fontWeight: '600', color: TEXT },

  resultValueBox:  { flexDirection: 'row', alignItems: 'flex-start', gap: 10, backgroundColor: '#EEF2FF', marginHorizontal: 18, marginBottom: 12, borderRadius: 10, padding: 12, flexWrap: 'wrap' },
  resultValueLeft: { flexDirection: 'row', alignItems: 'center', gap: 5 },
  resultValueLabel:{ fontSize: 12, fontWeight: '600', color: INDIGO },
  resultValueText: { flex: 1, fontSize: 14, fontWeight: '700', color: TEXT },
  resultRefText:   { fontSize: 11, color: GRAY, marginTop: 2, width: '100%' },

  resultDataWrap: { marginHorizontal: 18, marginBottom: 12, gap: 10 },

  analyteTable:  { borderRadius: 10, borderWidth: 1, borderColor: BORDER, overflow: 'hidden' },
  analyteHeader: { flexDirection: 'row', backgroundColor: BG, paddingHorizontal: 10, paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: BORDER },
  analyteRow:    { flexDirection: 'row', paddingHorizontal: 10, paddingVertical: 9, borderBottomWidth: 1, borderBottomColor: BORDER },
  analyteCol:    { fontSize: 12 },
  analyteColTest:{ flex: 2, color: GRAY, fontWeight: '600', fontSize: 11, textTransform: 'uppercase', letterSpacing: 0.3 },
  analyteColVal: { flex: 1.5 },
  analyteColRef: { flex: 2, color: GRAY },
  analyteColFlag:{ flex: 1, alignItems: 'flex-end' },
  analyteTestLabel:{ color: TEXT, fontSize: 12, fontWeight: '500', textTransform: 'none', letterSpacing: 0 },
  analyteVal:    { color: TEXT, fontWeight: '600' },
  analyteRef:    { color: GRAY, fontSize: 11 },
  flagBadge:     { borderRadius: 6, borderWidth: 1, paddingHorizontal: 6, paddingVertical: 2 },
  flagBadgeText: { fontSize: 10, fontWeight: '700' },

  textEntry:      { gap: 4 },
  textEntryLabel: { fontSize: 11, fontWeight: '700', color: GRAY, textTransform: 'uppercase', letterSpacing: 0.5 },
  textEntryValue: { fontSize: 13, color: TEXT, lineHeight: 19 },

  sensitivityGrid:  { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 4 },
  sensitivityItem:  { alignItems: 'center', gap: 3 },
  sensitivityDrug:  { fontSize: 11, color: GRAY, textTransform: 'capitalize' },
  sensitivityBadge: { borderRadius: 6, paddingHorizontal: 8, paddingVertical: 3 },
  sensitivityResult:{ fontSize: 11, fontWeight: '700' },

  commentBox:  { flexDirection: 'row', alignItems: 'flex-start', gap: 8, backgroundColor: '#E6F4F1', borderRadius: 10, padding: 12 },
  commentText: { flex: 1, fontSize: 13, color: TEXT, lineHeight: 19 },

  expandToggle:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', paddingVertical: 12, borderTopWidth: 1, borderTopColor: BORDER, marginTop: 4 },
  expandToggleText: { fontSize: 12, fontWeight: '600', color: TEAL },

  centerState:     { alignItems: 'center', justifyContent: 'center', paddingVertical: 64, gap: 12 },
  centerStateText: { fontSize: 14, color: GRAY, textAlign: 'center' },
  retryBtn:        { backgroundColor: TEAL, borderRadius: 10, paddingHorizontal: 20, paddingVertical: 10, marginTop: 4 },
  retryBtnText:    { fontSize: 13, fontWeight: '700', color: WHITE },
});

export default PatientWebLabResults;
