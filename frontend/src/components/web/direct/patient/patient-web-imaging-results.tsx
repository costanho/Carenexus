import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
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
const CARD   = '#FFFFFF';

type IoniconsName  = React.ComponentProps<typeof Ionicons>['name'];
type ImagingType   = ImagingResult['imaging_type'];
type StatusType    = ImagingResult['status'];
type FilterKey     = 'ALL' | ImagingType;

const TYPE_CONFIG: Record<ImagingType, { label: string; color: string; bg: string; icon: IoniconsName }> = {
  XRAY:        { label: 'X-Ray',        color: BLUE,   bg: '#EFF6FF', icon: 'scan-outline'       },
  MRI:         { label: 'MRI',          color: PURPLE, bg: '#F5F3FF', icon: 'radio-outline'      },
  CT_SCAN:     { label: 'CT Scan',      color: INDIGO, bg: '#EEF2FF', icon: 'layers-outline'     },
  ULTRASOUND:  { label: 'Ultrasound',   color: TEAL,   bg: '#E6F4F1', icon: 'pulse-outline'      },
  PET:         { label: 'PET Scan',     color: AMBER,  bg: '#FFFBEB', icon: 'flash-outline'      },
  MAMMOGRAPHY: { label: 'Mammography',  color: ROSE,   bg: '#FFF1F2', icon: 'body-outline'       },
  DEXA:        { label: 'DEXA Scan',    color: GREEN,  bg: '#ECFDF5', icon: 'fitness-outline'    },
  ECHO:        { label: 'Echo',         color: RED,    bg: '#FEF2F2', icon: 'heart-outline'      },
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

function ReportSection({
  icon, label, color, text, collapsible,
}: {
  icon: IoniconsName; label: string; color: string; text: string; collapsible?: boolean;
}) {
  const [open, setOpen] = useState(!collapsible);
  const MAX_LINES = collapsible ? (open ? undefined : 3) : undefined;
  return (
    <View style={[styles.reportSection, { borderLeftColor: color }]}>
      <TouchableOpacity
        style={styles.reportSectionHeader}
        onPress={() => collapsible && setOpen(o => !o)}
        activeOpacity={collapsible ? 0.7 : 1}
      >
        <Ionicons name={icon} size={13} color={color} />
        <Text style={[styles.reportSectionLabel, { color }]}>{label}</Text>
        {collapsible && (
          <Ionicons name={open ? 'chevron-up' : 'chevron-down'} size={13} color={GRAY} style={styles.reportChevron} />
        )}
      </TouchableOpacity>
      <Text style={styles.reportSectionText} numberOfLines={MAX_LINES}>{text}</Text>
    </View>
  );
}

function ImagingCard({ item }: { item: ImagingResult }) {
  const [expanded, setExpanded] = useState(false);
  const typeCfg   = TYPE_CONFIG[item.imaging_type]  ?? TYPE_CONFIG.XRAY;
  const statusCfg = STATUS_CONFIG[item.status]       ?? STATUS_CONFIG.PENDING;
  const isCritical = item.status === 'CRITICAL';
  const { title, subtitle } = splitBodyPart(item.body_part);
  const hasDetail = !!(item.findings || item.radiologist_report);

  return (
    <View style={[styles.card, { borderLeftColor: isCritical ? RED : typeCfg.color }]}>
      {/* Critical banner */}
      {isCritical && (
        <View style={styles.criticalBanner}>
          <Ionicons name="alert-circle" size={14} color={WHITE} />
          <Text style={styles.criticalBannerText}>CRITICAL — Requires immediate radiologist review</Text>
        </View>
      )}

      {/* Header */}
      <View style={styles.cardHeader}>
        <View style={[styles.typeIconSquare, { backgroundColor: typeCfg.bg }]}>
          <Ionicons name={typeCfg.icon} size={22} color={typeCfg.color} />
        </View>
        <View style={styles.cardHeaderText}>
          <Text style={styles.cardTitle}>{title}</Text>
          {subtitle && <Text style={styles.cardSubtitle} numberOfLines={1}>{subtitle}</Text>}
          <Text style={styles.cardDoctorName}>{item.doctor_name}</Text>
        </View>
        <View style={[styles.statusBadge, { backgroundColor: statusCfg.bg }]}>
          <Ionicons name={statusCfg.icon} size={12} color={statusCfg.color} />
          <Text style={[styles.statusBadgeText, { color: statusCfg.color }]}>{statusCfg.label}</Text>
        </View>
      </View>

      {/* Badges */}
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
          <Text style={styles.metaLabel}>Image date</Text>
          <Text style={styles.metaValue}>{formatDate(item.image_date)}</Text>
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

      {/* Impression — always visible */}
      {item.impression && (
        <View style={styles.impressionBox}>
          <View style={styles.impressionHeader}>
            <Ionicons name="document-text-outline" size={14} color={typeCfg.color} />
            <Text style={[styles.impressionLabel, { color: typeCfg.color }]}>Impression</Text>
          </View>
          <Text style={styles.impressionText} numberOfLines={expanded ? undefined : 3}>
            {item.impression}
          </Text>
        </View>
      )}

      {/* Expanded: Findings + Radiologist Report */}
      {expanded && (
        <View style={styles.expandedSections}>
          {item.findings && (
            <ReportSection
              icon="search-outline"
              label="Findings"
              color={INDIGO}
              text={item.findings}
              collapsible={false}
            />
          )}
          {item.radiologist_report && (
            <ReportSection
              icon="document-outline"
              label="Radiologist Report"
              color={GRAY}
              text={item.radiologist_report}
              collapsible
            />
          )}
        </View>
      )}

      {/* Expand toggle */}
      {(hasDetail || (item.impression && item.impression.length > 120)) && (
        <TouchableOpacity style={styles.expandToggle} onPress={() => setExpanded(e => !e)} activeOpacity={0.7}>
          <Text style={styles.expandToggleText}>
            {expanded ? 'Collapse report ▲' : 'View full report ▼'}
          </Text>
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

export function PatientWebImagingResults({ onBack }: { onBack?: () => void }) {
  const { results, loading, error, refetch } = useImagingResults();
  const [filter, setFilter] = useState<FilterKey>('ALL');

  const filtered      = filter === 'ALL' ? results : results.filter(r => r.imaging_type === filter);
  const total         = results.length;
  const completed     = results.filter(r => r.status === 'COMPLETED').length;
  const pending       = results.filter(r => r.status === 'PENDING' || r.status === 'REQUIRES_REVIEW').length;
  const critical      = results.filter(r => r.status === 'CRITICAL').length;

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
      {/* Header */}
      <View style={styles.pageHeader}>
        <TouchableOpacity style={styles.backBtn} onPress={onBack} activeOpacity={0.7}>
          <Ionicons name="arrow-back-outline" size={16} color={TEAL} />
          <Text style={styles.backBtnText}>Back</Text>
        </TouchableOpacity>
        <View style={styles.pageHeaderCenter}>
          <Text style={styles.pageTitle}>Imaging Results</Text>
          <Text style={styles.pageSub}>Your radiology and imaging history</Text>
        </View>
        <TouchableOpacity style={styles.refreshBtn} onPress={refetch} activeOpacity={0.7}>
          <Ionicons name="refresh-outline" size={18} color={TEAL} />
        </TouchableOpacity>
      </View>

      {/* Stats */}
      <View style={styles.statsRow}>
        <StatCard value={total}     label="Total"     color={INDIGO} icon="image-outline"            />
        <StatCard value={completed} label="Completed" color={GREEN}  icon="checkmark-done-outline"   />
        <StatCard value={pending}   label="Pending"   color={AMBER}  icon="hourglass-outline"        />
        <StatCard value={critical}  label="Critical"  color={RED}    icon="alert-circle-outline"     />
      </View>

      {/* Filter pills */}
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.filterScroll} contentContainerStyle={styles.filterRow}>
        {FILTERS.map(f => {
          const on    = filter === f.key;
          const count = f.key === 'ALL' ? total : results.filter(r => r.imaging_type === f.key).length;
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
          <Text style={styles.centerStateText}>Loading imaging results…</Text>
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
          <Ionicons name="image-outline" size={48} color={BORDER} />
          <Text style={styles.centerStateText}>
            {filter === 'ALL'
              ? 'No imaging results found.'
              : `No ${FILTERS.find(f => f.key === filter)?.label} results.`}
          </Text>
        </View>
      ) : (
        <View style={styles.cardList}>
          {filtered.map(item => (
            <ImagingCard key={item.imaging_id} item={item} />
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

  cardList: { gap: 16 },

  card: { backgroundColor: CARD, borderRadius: 14, borderWidth: 1, borderColor: BORDER, borderLeftWidth: 4, overflow: 'hidden', shadowColor: '#000', shadowOpacity: 0.05, shadowRadius: 12, elevation: 3 } as any,

  criticalBanner:     { flexDirection: 'row', alignItems: 'center', gap: 8, backgroundColor: RED, paddingHorizontal: 16, paddingVertical: 9 },
  criticalBannerText: { fontSize: 12, fontWeight: '700', color: WHITE, flex: 1 },

  cardHeader:     { flexDirection: 'row', alignItems: 'flex-start', gap: 14, padding: 18, paddingBottom: 0 },
  typeIconSquare: { width: 48, height: 48, borderRadius: 12, alignItems: 'center', justifyContent: 'center' },
  cardHeaderText: { flex: 1, paddingTop: 2 },
  cardTitle:      { fontSize: 16, fontWeight: '800', color: TEXT },
  cardSubtitle:   { fontSize: 12, color: GRAY, marginTop: 2, fontStyle: 'italic' },
  cardDoctorName: { fontSize: 12, color: GRAY, marginTop: 4 },
  statusBadge:    { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 10, paddingVertical: 5, borderRadius: 20, marginTop: 2 },
  statusBadgeText:{ fontSize: 11, fontWeight: '700' },

  badgeRow:  { flexDirection: 'row', gap: 8, paddingHorizontal: 18, paddingVertical: 12 },
  badge:     { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 10, paddingVertical: 4, borderRadius: 20 },
  badgeText: { fontSize: 11, fontWeight: '700' },

  metaRow:   { flexDirection: 'row', gap: 14, paddingHorizontal: 18, paddingBottom: 14, flexWrap: 'wrap' },
  metaItem:  { flexDirection: 'row', alignItems: 'center', gap: 4 },
  metaLabel: { fontSize: 11, color: GRAY },
  metaValue: { fontSize: 12, fontWeight: '600', color: TEXT },

  impressionBox:    { marginHorizontal: 18, marginBottom: 14, backgroundColor: '#F0FDF4', borderRadius: 12, padding: 14, borderLeftWidth: 3, borderLeftColor: GREEN },
  impressionHeader: { flexDirection: 'row', alignItems: 'center', gap: 6, marginBottom: 8 },
  impressionLabel:  { fontSize: 11, fontWeight: '800', textTransform: 'uppercase', letterSpacing: 0.8 },
  impressionText:   { fontSize: 13, color: TEXT, lineHeight: 20 },

  expandedSections: { marginHorizontal: 18, marginBottom: 12, gap: 10 },

  reportSection:       { borderLeftWidth: 3, paddingLeft: 12, paddingVertical: 6 },
  reportSectionHeader: { flexDirection: 'row', alignItems: 'center', gap: 6, marginBottom: 6 },
  reportSectionLabel:  { fontSize: 11, fontWeight: '800', textTransform: 'uppercase', letterSpacing: 0.8, flex: 1 },
  reportChevron:       { marginLeft: 'auto' as any },
  reportSectionText:   { fontSize: 13, color: TEXT, lineHeight: 20 },

  expandToggle:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', paddingVertical: 12, borderTopWidth: 1, borderTopColor: BORDER },
  expandToggleText: { fontSize: 12, fontWeight: '700', color: TEAL },

  centerState:     { alignItems: 'center', justifyContent: 'center', paddingVertical: 64, gap: 12 },
  centerStateText: { fontSize: 14, color: GRAY, textAlign: 'center' },
  retryBtn:        { backgroundColor: TEAL, borderRadius: 10, paddingHorizontal: 20, paddingVertical: 10, marginTop: 4 },
  retryBtnText:    { fontSize: 13, fontWeight: '700', color: WHITE },
});

export default PatientWebImagingResults;
