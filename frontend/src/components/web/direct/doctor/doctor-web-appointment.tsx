import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import {
  ActivityIndicator, ScrollView, StyleSheet, Text,
  TextInput, TouchableOpacity, View,
} from 'react-native';
import { type ScheduleEntry, useDoctorSchedule } from '@/hooks/use-doctor-schedule';

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
const PURPLE = '#8B5CF6';
const TEAL   = '#0D9488';
const AMBER  = '#D97706';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type AppStatus    = ScheduleEntry['status'];
type AppType      = ScheduleEntry['type'];
type DateFilter   = 'all' | 'today' | 'week' | 'upcoming' | 'past';
type StatusFilter = 'ALL' | AppStatus;
type TypeFilter   = 'ALL' | AppType;

// ─── Helpers ──────────────────────────────────────────────────────────────────

function statusConfig(s: AppStatus) {
  switch (s) {
    case 'SCHEDULED':   return { bg: '#EEF2FF', color: BLUE,   label: 'Scheduled',   icon: 'time-outline'               as IoniconsName };
    case 'COMPLETED':   return { bg: '#D1FAE5', color: GREEN,  label: 'Completed',   icon: 'checkmark-circle-outline'   as IoniconsName };
    case 'CANCELLED':   return { bg: '#FEE2E2', color: RED,    label: 'Cancelled',   icon: 'close-circle-outline'       as IoniconsName };
    case 'NO_SHOW':     return { bg: '#FEF3C7', color: ORANGE, label: 'No Show',     icon: 'alert-circle-outline'       as IoniconsName };
    case 'RESCHEDULED': return { bg: '#F5F3FF', color: PURPLE, label: 'Rescheduled', icon: 'refresh-circle-outline'    as IoniconsName };
    case 'IN_PROGRESS': return { bg: '#CCFBF1', color: TEAL,  label: 'In Progress', icon: 'sync-outline'               as IoniconsName };
  }
}

function typeConfig(t: AppType) {
  switch (t) {
    case 'IN_PERSON': return { icon: 'location-outline' as IoniconsName,  label: 'In Person', color: GREEN,  bg: '#D1FAE5', accent: '#10B981' };
    case 'VIDEO':     return { icon: 'videocam-outline'  as IoniconsName, label: 'Video',     color: BLUE,   bg: '#EEF2FF', accent: '#4F46E5' };
    case 'PHONE':     return { icon: 'call-outline'      as IoniconsName, label: 'Phone',     color: ORANGE, bg: '#FEF3C7', accent: '#F59E0B' };
  }
}

function consultConfig(c: ScheduleEntry['consultation']) {
  if (!c) return { label: 'No consultation', color: LGRAY, bg: BG2,      icon: 'document-outline'          as IoniconsName };
  switch (c.status) {
    case 'DRAFT':       return { label: 'Draft',       color: AMBER, bg: '#FEF3C7', icon: 'document-outline'          as IoniconsName };
    case 'IN_PROGRESS': return { label: 'In Progress', color: TEAL,  bg: '#CCFBF1', icon: 'sync-outline'               as IoniconsName };
    case 'COMPLETED':   return { label: 'Completed',   color: GREEN, bg: '#D1FAE5', icon: 'checkmark-circle-outline'   as IoniconsName };
  }
}

function formatDate(iso: string) {
  return new Date(iso).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
}

function formatTime(iso: string) {
  return new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', hour12: true }).toUpperCase();
}

function isToday(iso: string) {
  const d = new Date(iso); const n = new Date();
  return d.toDateString() === n.toDateString();
}

const AVATAR_COLORS = ['#4F46E5', '#0D9488', '#F59E0B', '#EF4444', '#8B5CF6', '#10B981'];
function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}
function initials(name: string) {
  return name.split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
}

function dayStart(d = new Date()) { const x = new Date(d); x.setHours(0,0,0,0); return x; }
function dayEnd(d = new Date())   { const x = new Date(d); x.setHours(23,59,59,999); return x; }

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

// ─── Appointment Card ─────────────────────────────────────────────────────────

function AppCard({ entry }: { entry: ScheduleEntry }) {
  const sc  = statusConfig(entry.status);
  const tc  = typeConfig(entry.type);
  const cc  = consultConfig(entry.consultation);
  const ac  = avatarColor(entry.patient_name);
  const tod = isToday(entry.scheduled_at);

  return (
    <View style={[styles.apptCard, { borderLeftColor: tc.accent }]}>
      {/* Today badge */}
      {tod && (
        <View style={styles.todayBanner}>
          <Ionicons name="radio-button-on" size={10} color={BLUE} />
          <Text style={styles.todayText}>Today</Text>
        </View>
      )}

      {/* Main body */}
      <View style={styles.apptBody}>
        {/* Left: avatar + patient info */}
        <View style={styles.apptLeft}>
          <View style={[styles.avatar, { backgroundColor: ac }]}>
            <Text style={styles.avatarText}>{initials(entry.patient_name)}</Text>
          </View>
          <View style={styles.apptPatientCol}>
            <Text style={styles.patientName}>{entry.patient_name}</Text>
            <Text style={styles.apptId}>Appt #{entry.appointment_id}</Text>
            <View style={styles.apptDateTime}>
              <Ionicons name="calendar-outline" size={12} color={GRAY} />
              <Text style={styles.apptDateText}>{formatDate(entry.scheduled_at)}</Text>
            </View>
            <View style={styles.apptDateTime}>
              <Ionicons name="time-outline" size={12} color={GRAY} />
              <Text style={styles.apptDateText}>
                {formatTime(entry.scheduled_at)} · {entry.duration_minutes} min
              </Text>
            </View>
          </View>
        </View>

        {/* Center: details */}
        <View style={styles.apptCenter}>
          {!!entry.reason_for_visit && (
            <View style={styles.detailRow}>
              <Ionicons name="clipboard-outline" size={13} color={BLUE} style={styles.detailIcon} />
              <Text style={styles.detailText} numberOfLines={2}>{entry.reason_for_visit}</Text>
            </View>
          )}
          {!!entry.facility_name && (
            <View style={styles.detailRow}>
              <Ionicons name="business-outline" size={13} color={GRAY} style={styles.detailIcon} />
              <Text style={styles.detailFacility}>{entry.facility_name}</Text>
            </View>
          )}
          {!entry.facility_name && entry.type !== 'IN_PERSON' && (
            <View style={styles.detailRow}>
              <Ionicons name="globe-outline" size={13} color={GRAY} style={styles.detailIcon} />
              <Text style={styles.detailFacility}>Virtual</Text>
            </View>
          )}
          {!!entry.notes && (
            <View style={styles.detailRow}>
              <Ionicons name="chatbubble-outline" size={13} color={LGRAY} style={styles.detailIcon} />
              <Text style={styles.detailNotes} numberOfLines={2}>{entry.notes}</Text>
            </View>
          )}
        </View>

        {/* Right: badges + actions */}
        <View style={styles.apptRight}>
          <View style={styles.badgeStack}>
            <View style={[styles.badge, { backgroundColor: tc.bg }]}>
              <Ionicons name={tc.icon} size={11} color={tc.color} />
              <Text style={[styles.badgeText, { color: tc.color }]}>{tc.label}</Text>
            </View>
            <View style={[styles.badge, { backgroundColor: sc.bg }]}>
              <Ionicons name={sc.icon} size={11} color={sc.color} />
              <Text style={[styles.badgeText, { color: sc.color }]}>{sc.label}</Text>
            </View>
          </View>

          {/* Consultation status */}
          <View style={[styles.consultPill, { backgroundColor: cc.bg }]}>
            <Ionicons name={cc.icon} size={11} color={cc.color} />
            <Text style={[styles.consultText, { color: cc.color }]}>{cc.label}</Text>
          </View>

          {/* Video link */}
          {entry.type === 'VIDEO' && !!entry.video_consultation_link && (
            <TouchableOpacity style={styles.videoBtn} activeOpacity={0.8}>
              <Ionicons name="videocam-outline" size={13} color={BLUE} />
              <Text style={styles.videoBtnText}>Join Call</Text>
            </TouchableOpacity>
          )}

          <TouchableOpacity style={styles.viewBtn} activeOpacity={0.8}>
            <Text style={styles.viewBtnText}>Details</Text>
            <Ionicons name="chevron-forward" size={13} color={BLUE} />
          </TouchableOpacity>
        </View>
      </View>
    </View>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────

export default function DoctorWebAppointment() {
  const { entries, loading, error, refetch } = useDoctorSchedule();

  const [dateFilter,   setDateFilter]   = useState<DateFilter>('all');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');
  const [typeFilter,   setTypeFilter]   = useState<TypeFilter>('ALL');
  const [search,       setSearch]       = useState('');

  const now     = useMemo(() => new Date(), []);
  const todayS  = useMemo(() => dayStart(), []);
  const todayE  = useMemo(() => dayEnd(),   []);
  const weekE   = useMemo(() => { const d = dayEnd(); d.setDate(d.getDate() + 7); return d; }, []);

  const stats = useMemo(() => ({
    total:      entries.length,
    scheduled:  entries.filter(e => e.status === 'SCHEDULED').length,
    completed:  entries.filter(e => e.status === 'COMPLETED').length,
    cancelled:  entries.filter(e => e.status === 'CANCELLED').length,
    noShow:     entries.filter(e => e.status === 'NO_SHOW').length,
    inProgress: entries.filter(e => e.status === 'IN_PROGRESS').length,
  }), [entries]);

  const filtered = useMemo(() => {
    return entries
      .filter(e => {
        const d = new Date(e.scheduled_at);
        switch (dateFilter) {
          case 'today':    return d >= todayS && d <= todayE;
          case 'week':     return d >= todayS && d <= weekE;
          case 'upcoming': return d >= now;
          case 'past':     return d < todayS;
          default:         return true;
        }
      })
      .filter(e => statusFilter === 'ALL' || e.status === statusFilter)
      .filter(e => typeFilter   === 'ALL' || e.type   === typeFilter)
      .filter(e => !search || e.patient_name.toLowerCase().includes(search.toLowerCase()))
      .sort((a, b) => new Date(b.scheduled_at).getTime() - new Date(a.scheduled_at).getTime());
  }, [entries, dateFilter, statusFilter, typeFilter, search, now, todayS, todayE, weekE]);

  const dateFilters:   { key: DateFilter;   label: string }[] = [
    { key: 'all',      label: 'All Time'  },
    { key: 'today',    label: 'Today'     },
    { key: 'week',     label: 'This Week' },
    { key: 'upcoming', label: 'Upcoming'  },
    { key: 'past',     label: 'Past'      },
  ];

  const statusFilters: { key: StatusFilter; label: string }[] = [
    { key: 'ALL',         label: 'All'         },
    { key: 'SCHEDULED',   label: 'Scheduled'   },
    { key: 'IN_PROGRESS', label: 'In Progress' },
    { key: 'COMPLETED',   label: 'Completed'   },
    { key: 'CANCELLED',   label: 'Cancelled'   },
    { key: 'NO_SHOW',     label: 'No Show'     },
    { key: 'RESCHEDULED', label: 'Rescheduled' },
  ];

  const typeFilters: { key: TypeFilter; label: string; icon: IoniconsName }[] = [
    { key: 'ALL',       label: 'All Types', icon: 'apps-outline'       },
    { key: 'IN_PERSON', label: 'In Person', icon: 'location-outline'   },
    { key: 'VIDEO',     label: 'Video',     icon: 'videocam-outline'   },
    { key: 'PHONE',     label: 'Phone',     icon: 'call-outline'       },
  ];

  if (loading) {
    return <View style={styles.center}><ActivityIndicator size="large" color={BLUE} /></View>;
  }

  if (error) {
    return (
      <View style={styles.center}>
        <Ionicons name="alert-circle-outline" size={52} color={RED} />
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
          <Text style={styles.pageTitle}>Appointments</Text>
          <Text style={styles.pageSub}>Complete appointment history for your patients</Text>
        </View>
        <TouchableOpacity style={styles.refreshBtn} onPress={refetch} activeOpacity={0.8}>
          <Ionicons name="refresh-outline" size={16} color={BLUE} />
          <Text style={styles.refreshText}>Refresh</Text>
        </TouchableOpacity>
      </View>

      {/* Stats */}
      <View style={styles.statsRow}>
        <StatCard icon="calendar-number-outline"   iconBg="#EEF2FF" iconColor={BLUE}   label="Total"       value={stats.total}      />
        <StatCard icon="time-outline"              iconBg="#EEF2FF" iconColor={BLUE}   label="Scheduled"   value={stats.scheduled}  />
        <StatCard icon="sync-outline"              iconBg="#CCFBF1" iconColor={TEAL}   label="In Progress" value={stats.inProgress} />
        <StatCard icon="checkmark-circle-outline"  iconBg="#D1FAE5" iconColor={GREEN}  label="Completed"   value={stats.completed}  />
        <StatCard icon="close-circle-outline"      iconBg="#FEE2E2" iconColor={RED}    label="Cancelled"   value={stats.cancelled}  />
        <StatCard icon="alert-circle-outline"      iconBg="#FEF3C7" iconColor={ORANGE} label="No Show"     value={stats.noShow}     />
      </View>

      {/* Filters */}
      <View style={styles.filterCard}>
        {/* Row 1 — Date tabs */}
        <View style={styles.filterRow}>
          {dateFilters.map(f => (
            <TouchableOpacity
              key={f.key}
              style={[styles.dateTab, dateFilter === f.key && styles.dateTabActive]}
              onPress={() => setDateFilter(f.key)}
              activeOpacity={0.7}
            >
              <Text style={[styles.dateTabText, dateFilter === f.key && styles.dateTabTextActive]}>{f.label}</Text>
            </TouchableOpacity>
          ))}
        </View>

        <View style={styles.filterDivider} />

        {/* Row 2 — Status + Type pills + Search */}
        <View style={styles.filterRow2}>
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 6 }}>
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
            <View style={styles.pillDivider} />
            {typeFilters.map(f => (
              <TouchableOpacity
                key={f.key}
                style={[styles.pill, styles.pillType, typeFilter === f.key && styles.pillTypeActive]}
                onPress={() => setTypeFilter(f.key)}
                activeOpacity={0.7}
              >
                <Ionicons name={f.icon} size={12} color={typeFilter === f.key ? GREEN : GRAY} />
                <Text style={[styles.pillText, typeFilter === f.key && styles.pillTypeTextActive]}>{f.label}</Text>
              </TouchableOpacity>
            ))}
          </ScrollView>

          {/* Search */}
          <View style={styles.searchBox}>
            <Ionicons name="search-outline" size={15} color={LGRAY} />
            <TextInput
              style={styles.searchInput}
              placeholder="Search patient…"
              placeholderTextColor={LGRAY}
              value={search}
              onChangeText={setSearch}
            />
            {!!search && (
              <TouchableOpacity onPress={() => setSearch('')} activeOpacity={0.7}>
                <Ionicons name="close-circle" size={15} color={LGRAY} />
              </TouchableOpacity>
            )}
          </View>
        </View>
      </View>

      {/* Result count */}
      <Text style={styles.resultCount}>
        {filtered.length} appointment{filtered.length !== 1 ? 's' : ''}
      </Text>

      {/* List */}
      {filtered.length === 0 ? (
        <View style={styles.emptyState}>
          <Ionicons name="calendar-outline" size={60} color={BORDER} />
          <Text style={styles.emptyTitle}>No appointments found</Text>
          <Text style={styles.emptySub}>Try adjusting your filters or search term</Text>
        </View>
      ) : (
        <View style={styles.list}>
          {filtered.map(e => <AppCard key={e.appointment_id} entry={e} />)}
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

  statsRow: { flexDirection: 'row', gap: 10, marginBottom: 20, flexWrap: 'wrap' },
  statCard: {
    flex: 1, minWidth: 90,
    backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER,
    borderRadius: 12, padding: 14, gap: 5,
  },
  statIconBg: { width: 36, height: 36, borderRadius: 9, alignItems: 'center', justifyContent: 'center' },
  statValue:  { fontSize: 24, fontWeight: '700', color: TEXT },
  statLabel:  { fontSize: 11, color: GRAY },

  filterCard:    { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER, borderRadius: 12, padding: 16, marginBottom: 16 },
  filterRow:     { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  filterDivider: { height: 1, backgroundColor: BORDER, marginVertical: 12 },
  filterRow2:    { flexDirection: 'row', alignItems: 'center', gap: 10, flexWrap: 'wrap' },

  dateTab:           { paddingHorizontal: 14, paddingVertical: 7, borderRadius: 8, borderWidth: 1, borderColor: BORDER },
  dateTabActive:     { backgroundColor: BLUE, borderColor: BLUE },
  dateTabText:       { fontSize: 13, color: GRAY, fontWeight: '500' },
  dateTabTextActive: { color: WHITE },

  pill:             { paddingHorizontal: 12, paddingVertical: 6, borderRadius: 20, borderWidth: 1, borderColor: BORDER, backgroundColor: WHITE },
  pillActive:       { backgroundColor: '#EEF2FF', borderColor: BLUE },
  pillText:         { fontSize: 12, color: GRAY, fontWeight: '500' },
  pillTextActive:   { color: BLUE },
  pillType:         { flexDirection: 'row', alignItems: 'center', gap: 4 },
  pillTypeActive:   { backgroundColor: '#F0FDF4', borderColor: GREEN },
  pillTypeTextActive: { color: GREEN },
  pillDivider:      { width: 1, backgroundColor: BORDER, marginHorizontal: 4 },

  searchBox: {
    flex: 1, minWidth: 180,
    flexDirection: 'row', alignItems: 'center', gap: 8,
    borderWidth: 1, borderColor: BORDER, borderRadius: 8,
    paddingHorizontal: 12, paddingVertical: 8, backgroundColor: BG,
  },
  searchInput: { flex: 1, fontSize: 13, color: TEXT, outlineStyle: 'none' } as any,

  resultCount: { fontSize: 13, color: GRAY, fontWeight: '500', marginBottom: 12 },

  list: { gap: 12 },

  // Card
  apptCard: {
    backgroundColor: WHITE,
    borderWidth: 1, borderColor: BORDER,
    borderRadius: 16, borderLeftWidth: 4,
    overflow: 'hidden',
  },
  todayBanner: {
    flexDirection: 'row', alignItems: 'center', gap: 5,
    backgroundColor: '#EEF2FF',
    paddingHorizontal: 14, paddingVertical: 6,
  },
  todayText: { fontSize: 11, color: BLUE, fontWeight: '700' },

  apptBody:  { flexDirection: 'row', gap: 16, padding: 16, flexWrap: 'wrap' },

  apptLeft: { flexDirection: 'row', gap: 12, minWidth: 200 },
  avatar:   { width: 48, height: 48, borderRadius: 24, alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  avatarText: { color: WHITE, fontSize: 16, fontWeight: '700' },
  apptPatientCol: { gap: 3 },
  patientName:    { fontSize: 15, fontWeight: '700', color: TEXT },
  apptId:         { fontSize: 11, color: LGRAY, fontWeight: '500' },
  apptDateTime:   { flexDirection: 'row', alignItems: 'center', gap: 4, marginTop: 2 },
  apptDateText:   { fontSize: 12, color: GRAY },

  apptCenter: { flex: 1, minWidth: 200, gap: 8, justifyContent: 'center' },
  detailRow:  { flexDirection: 'row', alignItems: 'flex-start', gap: 6 },
  detailIcon: { marginTop: 1 },
  detailText:     { flex: 1, fontSize: 13, color: TEXT, lineHeight: 18 },
  detailFacility: { flex: 1, fontSize: 12, color: GRAY },
  detailNotes:    { flex: 1, fontSize: 12, color: LGRAY, lineHeight: 18 },

  apptRight:    { alignItems: 'flex-end', gap: 8, minWidth: 130, justifyContent: 'center' },
  badgeStack:   { gap: 5, alignItems: 'flex-end' },
  badge:        { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 8, paddingVertical: 3, borderRadius: 20 },
  badgeText:    { fontSize: 11, fontWeight: '600' },
  consultPill:  { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 8, paddingVertical: 4, borderRadius: 8 },
  consultText:  { fontSize: 11, fontWeight: '600' },
  videoBtn: {
    flexDirection: 'row', alignItems: 'center', gap: 4,
    borderWidth: 1, borderColor: BLUE, borderRadius: 8,
    paddingHorizontal: 10, paddingVertical: 5,
  },
  videoBtnText: { fontSize: 12, color: BLUE, fontWeight: '500' },
  viewBtn:    { flexDirection: 'row', alignItems: 'center', gap: 3 },
  viewBtnText:{ fontSize: 13, color: BLUE, fontWeight: '500' },

  emptyState: { alignItems: 'center', gap: 10, paddingVertical: 64 },
  emptyTitle: { fontSize: 18, fontWeight: '600', color: TEXT },
  emptySub:   { fontSize: 14, color: GRAY, textAlign: 'center' },

  errorText:    { fontSize: 15, color: GRAY, textAlign: 'center' },
  retryBtn:     { backgroundColor: BLUE, borderRadius: 8, paddingHorizontal: 20, paddingVertical: 10 },
  retryBtnText: { color: WHITE, fontWeight: '600', fontSize: 14 },
});
