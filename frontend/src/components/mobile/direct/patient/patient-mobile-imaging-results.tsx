import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import {
  KeyboardAvoidingView, Platform, ScrollView,
  StyleSheet, Text, TouchableOpacity, useWindowDimensions, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useImagingResults, type ImagingResult } from '@/hooks/use-imaging-results';

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

type IoniconsName  = React.ComponentProps<typeof Ionicons>['name'];
type ImagingType   = ImagingResult['imaging_type'];
type StatusType    = ImagingResult['status'];
type FilterKey     = 'ALL' | ImagingType;

const TYPE_CONFIG: Record<ImagingType, { label: string; color: string; bg: string; icon: IoniconsName }> = {
  XRAY:        { label: 'X-Ray',       color: BLUE,   bg: '#EFF6FF', icon: 'scan-outline'    },
  MRI:         { label: 'MRI',         color: PURPLE, bg: '#F5F3FF', icon: 'radio-outline'   },
  CT_SCAN:     { label: 'CT Scan',     color: INDIGO, bg: '#EEF2FF', icon: 'layers-outline'  },
  ULTRASOUND:  { label: 'Ultrasound',  color: TEAL,   bg: '#E6F4F1', icon: 'pulse-outline'   },
  PET:         { label: 'PET Scan',    color: AMBER,  bg: '#FFFBEB', icon: 'flash-outline'   },
  MAMMOGRAPHY: { label: 'Mammography', color: ROSE,   bg: '#FFF1F2', icon: 'body-outline'    },
  DEXA:        { label: 'DEXA Scan',   color: GREEN,  bg: '#ECFDF5', icon: 'fitness-outline' },
  ECHO:        { label: 'Echo',        color: RED,    bg: '#FEF2F2', icon: 'heart-outline'   },
};

const STATUS_CONFIG: Record<StatusType, { label: string; color: string; bg: string; icon: IoniconsName }> = {
  COMPLETED:      { label: 'Completed',    color: TEAL,   bg: '#E6F4F1', icon: 'checkmark-done-outline' },
  PENDING:        { label: 'Pending',      color: AMBER,  bg: '#FFFBEB', icon: 'hourglass-outline'      },
  REQUIRES_REVIEW:{ label: 'Needs Review', color: PURPLE, bg: '#F5F3FF', icon: 'eye-outline'            },
  CRITICAL:       { label: 'Critical',     color: RED,    bg: '#FEF2F2', icon: 'alert-circle-outline'   },
};

const FILTERS: { key: FilterKey; label: string }[] = [
  { key: 'ALL',        label: 'All'        },
  { key: 'XRAY',       label: 'X-Ray'      },
  { key: 'ULTRASOUND', label: 'Ultrasound' },
  { key: 'CT_SCAN',    label: 'CT Scan'    },
  { key: 'MRI',        label: 'MRI'        },
  { key: 'ECHO',       label: 'Echo'       },
  { key: 'DEXA',       label: 'DEXA'       },
  { key: 'MAMMOGRAPHY',label: 'Mammo'      },
  { key: 'PET',        label: 'PET'        },
];

function formatDate(iso: string | null) {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

function splitBodyPart(raw: string | null): { title: string; subtitle: string | null } {
  if (!raw) return { title: 'Study', subtitle: null };
  const parts = raw.split(' — ');
  return { title: parts[0].trim(), subtitle: parts.length > 1 ? parts.slice(1).join(' — ').trim() : null };
}

function ImagingCard({ item, s }: { item: ImagingResult; s: (n: number) => number }) {
  const [expanded, setExpanded] = useState(false);
  const [reportOpen, setReportOpen] = useState(false);
  const typeCfg    = TYPE_CONFIG[item.imaging_type] ?? TYPE_CONFIG.XRAY;
  const statusCfg  = STATUS_CONFIG[item.status]     ?? STATUS_CONFIG.PENDING;
  const isCritical = item.status === 'CRITICAL';
  const { title, subtitle } = splitBodyPart(item.body_part);
  const hasDetail = !!(item.findings || item.radiologist_report);

  return (
    <View style={[styles.card, { borderRadius: s(16), marginBottom: s(12), borderLeftColor: isCritical ? RED : typeCfg.color }]}>
      {/* Critical banner */}
      {isCritical && (
        <View style={[styles.criticalBanner, { paddingHorizontal: s(12), paddingVertical: s(7), gap: s(6) }]}>
          <Ionicons name="alert-circle" size={s(12)} color={WHITE} />
          <Text style={[styles.criticalText, { fontSize: s(11) }]}>CRITICAL — Requires immediate review</Text>
        </View>
      )}

      <View style={{ padding: s(14) }}>
        {/* Header */}
        <View style={[styles.cardHeader, { gap: s(10), marginBottom: s(10) }]}>
          <View style={[styles.typeIconCircle, { width: s(44), height: s(44), borderRadius: s(22), backgroundColor: typeCfg.bg }]}>
            <Ionicons name={typeCfg.icon} size={s(22)} color={typeCfg.color} />
          </View>
          <View style={{ flex: 1 }}>
            <Text style={[styles.cardTitle, { fontSize: s(14) }]}>{title}</Text>
            {subtitle && (
              <Text style={[styles.cardSubtitle, { fontSize: s(10) }]} numberOfLines={1}>{subtitle}</Text>
            )}
            <Text style={[styles.cardDoctor, { fontSize: s(11) }]}>{item.doctor_name}</Text>
          </View>
          <View style={[styles.statusBadge, { borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3), backgroundColor: statusCfg.bg, gap: s(3) }]}>
            <Ionicons name={statusCfg.icon} size={s(10)} color={statusCfg.color} />
            <Text style={[styles.statusText, { fontSize: s(10), color: statusCfg.color }]}>{statusCfg.label}</Text>
          </View>
        </View>

        {/* Badges */}
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
        <View style={[styles.metaRow, { gap: s(10), marginBottom: s(12), flexWrap: 'wrap' }]}>
          <View style={[styles.metaItem, { gap: s(3) }]}>
            <Ionicons name="calendar-outline" size={s(11)} color={GRAY} />
            <Text style={[styles.metaLabel, { fontSize: s(10) }]}>Image date</Text>
            <Text style={[styles.metaValue, { fontSize: s(11) }]}>{formatDate(item.image_date)}</Text>
          </View>
          {item.reviewed_at && (
            <View style={[styles.metaItem, { gap: s(3) }]}>
              <Ionicons name="checkmark-circle-outline" size={s(11)} color={TEAL} />
              <Text style={[styles.metaLabel, { fontSize: s(10) }]}>Reviewed</Text>
              <Text style={[styles.metaValue, { fontSize: s(11), color: TEAL }]}>{formatDate(item.reviewed_at)}</Text>
            </View>
          )}
          {item.reviewer_name && (
            <View style={[styles.metaItem, { gap: s(3) }]}>
              <Ionicons name="person-circle-outline" size={s(11)} color={GRAY} />
              <Text style={[styles.metaLabel, { fontSize: s(10) }]}>By</Text>
              <Text style={[styles.metaValue, { fontSize: s(11) }]}>{item.reviewer_name}</Text>
            </View>
          )}
        </View>

        {/* Impression — always visible */}
        {item.impression && (
          <View style={[styles.impressionBox, { borderRadius: s(12), padding: s(12), marginBottom: s(10) }]}>
            <View style={[styles.impressionHeader, { gap: s(5), marginBottom: s(6) }]}>
              <Ionicons name="document-text-outline" size={s(13)} color={typeCfg.color} />
              <Text style={[styles.impressionLabel, { fontSize: s(10), color: typeCfg.color }]}>Impression</Text>
            </View>
            <Text style={[styles.impressionText, { fontSize: s(12) }]} numberOfLines={expanded ? undefined : 3}>
              {item.impression}
            </Text>
          </View>
        )}

        {/* Expanded: Findings */}
        {expanded && item.findings && (
          <View style={[styles.reportSection, { borderLeftColor: INDIGO, paddingLeft: s(10), paddingVertical: s(6), marginBottom: s(8) }]}>
            <View style={[styles.reportHeader, { gap: s(5), marginBottom: s(6) }]}>
              <Ionicons name="search-outline" size={s(12)} color={INDIGO} />
              <Text style={[styles.reportLabel, { fontSize: s(10), color: INDIGO }]}>Findings</Text>
            </View>
            <Text style={[styles.reportText, { fontSize: s(12) }]}>{item.findings}</Text>
          </View>
        )}

        {/* Expanded: Radiologist Report (collapsible within expand) */}
        {expanded && item.radiologist_report && (
          <View style={[styles.reportSection, { borderLeftColor: GRAY, paddingLeft: s(10), paddingVertical: s(6), marginBottom: s(8) }]}>
            <TouchableOpacity
              style={[styles.reportHeader, { gap: s(5), marginBottom: reportOpen ? s(6) : 0 }]}
              onPress={() => setReportOpen(o => !o)}
              activeOpacity={0.7}
            >
              <Ionicons name="document-outline" size={s(12)} color={GRAY} />
              <Text style={[styles.reportLabel, { fontSize: s(10), color: GRAY, flex: 1 }]}>Radiologist Report</Text>
              <Ionicons name={reportOpen ? 'chevron-up' : 'chevron-down'} size={s(12)} color={GRAY} />
            </TouchableOpacity>
            {reportOpen && (
              <Text style={[styles.reportText, { fontSize: s(11) }]}>{item.radiologist_report}</Text>
            )}
          </View>
        )}

        {/* Expand toggle */}
        {(hasDetail || (item.impression && item.impression.length > 120)) && (
          <TouchableOpacity
            style={[styles.expandToggle, { paddingTop: s(10), gap: s(4) }]}
            onPress={() => { setExpanded(e => !e); if (expanded) setReportOpen(false); }}
            activeOpacity={0.7}
          >
            <Text style={[styles.expandToggleText, { fontSize: s(12) }]}>
              {expanded ? 'Collapse report ▲' : 'View full report ▼'}
            </Text>
          </TouchableOpacity>
        )}
      </View>
    </View>
  );
}

export function PatientMobileImagingResults({ onBack }: { onBack?: () => void }) {
  const router  = useRouter();
  const insets  = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  const { results, loading, error, refetch } = useImagingResults();
  const [filter, setFilter] = useState<FilterKey>('ALL');

  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));

  const filtered  = filter === 'ALL' ? results : results.filter(r => r.imaging_type === filter);
  const total     = results.length;
  const completed = results.filter(r => r.status === 'COMPLETED').length;
  const pending   = results.filter(r => r.status === 'PENDING' || r.status === 'REQUIRES_REVIEW').length;
  const critical  = results.filter(r => r.status === 'CRITICAL').length;

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
          <Text style={[styles.headerTitle, { fontSize: s(18) }]}>Imaging Results</Text>
          <Text style={[styles.headerSub, { fontSize: s(11) }]}>Your radiology and imaging history</Text>
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
          { value: total,     label: 'Total',     color: INDIGO },
          { value: completed, label: 'Completed', color: GREEN  },
          { value: pending,   label: 'Pending',   color: AMBER  },
          { value: critical,  label: 'Critical',  color: RED    },
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
        style={[styles.filterScroll]}
        contentContainerStyle={{ paddingHorizontal: s(16), paddingVertical: s(8), gap: s(8) }}
      >
        {FILTERS.map(f => {
          const on    = filter === f.key;
          const count = f.key === 'ALL' ? total : results.filter(r => r.imaging_type === f.key).length;
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
            <Text style={[styles.centerText, { fontSize: s(14) }]}>Loading imaging results…</Text>
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
            <Ionicons name="image-outline" size={s(48)} color={BORDER} />
            <Text style={[styles.centerText, { fontSize: s(14) }]}>
              {filter === 'ALL'
                ? 'No imaging results found.'
                : `No ${FILTERS.find(f => f.key === filter)?.label} results.`}
            </Text>
          </View>
        ) : (
          filtered.map(item => <ImagingCard key={item.imaging_id} item={item} s={s} />)
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

  card:          { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER, borderLeftWidth: 4, overflow: 'hidden', shadowColor: '#000', shadowOpacity: 0.05, shadowRadius: 10, elevation: 3 } as any,
  criticalBanner:{ flexDirection: 'row', alignItems: 'center', backgroundColor: RED },
  criticalText:  { color: WHITE, fontWeight: '700', flex: 1 },

  cardHeader:    { flexDirection: 'row', alignItems: 'flex-start' },
  typeIconCircle:{ alignItems: 'center', justifyContent: 'center' },
  cardTitle:     { fontWeight: '800', color: TEXT },
  cardSubtitle:  { color: GRAY, fontStyle: 'italic', marginTop: 2 },
  cardDoctor:    { color: GRAY, marginTop: 4 },

  statusBadge: { flexDirection: 'row', alignItems: 'center' },
  statusText:  { fontWeight: '700' },

  badgeRow:  { flexDirection: 'row' },
  badge:     { flexDirection: 'row', alignItems: 'center' },
  badgeText: { fontWeight: '700' },

  metaRow:   { flexDirection: 'row' },
  metaItem:  { flexDirection: 'row', alignItems: 'center' },
  metaLabel: { color: GRAY },
  metaValue: { fontWeight: '600', color: TEXT },

  impressionBox:    { backgroundColor: '#F0FDF4', borderLeftWidth: 3, borderLeftColor: GREEN },
  impressionHeader: { flexDirection: 'row', alignItems: 'center' },
  impressionLabel:  { fontWeight: '800', textTransform: 'uppercase', letterSpacing: 0.8 },
  impressionText:   { color: TEXT, lineHeight: 20 },

  reportSection: { borderLeftWidth: 3 },
  reportHeader:  { flexDirection: 'row', alignItems: 'center' },
  reportLabel:   { fontWeight: '800', textTransform: 'uppercase', letterSpacing: 0.8 },
  reportText:    { color: TEXT, lineHeight: 19 },

  expandToggle:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', borderTopWidth: 1, borderTopColor: BORDER },
  expandToggleText: { fontWeight: '700', color: TEAL },

  centerState: { alignItems: 'center', justifyContent: 'center', paddingVertical: 60, gap: 12 },
  centerText:  { color: GRAY, textAlign: 'center' },
  retryBtn:    { backgroundColor: TEAL },
  retryBtnText:{ fontWeight: '700', color: WHITE },
});

export default PatientMobileImagingResults;
