import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { type ScheduleEntry, useDoctorSchedule } from '@/hooks/use-doctor-schedule';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const BLUE   = '#4F46E5';
const GREEN  = '#10B981';
const ORANGE = '#F59E0B';
const RED    = '#EF4444';
const PURPLE = '#8B5CF6';
const TEAL   = '#0D9488';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type DateFilter   = 'today' | 'week' | 'all';
type AppStatus    = ScheduleEntry['status'];
type StatusFilter = 'ALL' | AppStatus;

// ─── Helpers ──────────────────────────────────────────────────────────────────

function statusConfig(s: AppStatus) {
  switch (s) {
    case 'SCHEDULED':   return { bg: '#EEF2FF', color: BLUE,   label: 'Scheduled'    };
    case 'COMPLETED':   return { bg: '#D1FAE5', color: GREEN,  label: 'Completed'    };
    case 'CANCELLED':   return { bg: '#FEE2E2', color: RED,    label: 'Cancelled'    };
    case 'NO_SHOW':     return { bg: '#FEF3C7', color: ORANGE, label: 'No Show'      };
    case 'RESCHEDULED': return { bg: '#F5F3FF', color: PURPLE, label: 'Rescheduled'  };
    case 'IN_PROGRESS': return { bg: '#CCFBF1', color: TEAL,  label: 'In Progress'  };
  }
}

function typeConfig(t: ScheduleEntry['type']) {
  switch (t) {
    case 'IN_PERSON': return { icon: 'location-outline' as IoniconsName,  label: 'In Person', color: GREEN,  bg: '#D1FAE5' };
    case 'VIDEO':     return { icon: 'videocam-outline'  as IoniconsName, label: 'Video',     color: BLUE,   bg: '#EEF2FF' };
    case 'PHONE':     return { icon: 'call-outline'      as IoniconsName, label: 'Phone',     color: ORANGE, bg: '#FEF3C7' };
  }
}

function formatTime(iso: string) {
  return new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', hour12: true }).toUpperCase();
}

function formatDate(iso: string) {
  return new Date(iso).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });
}

function isTodayDate(iso: string) {
  const d   = new Date(iso);
  const now = new Date();
  return d.getDate() === now.getDate() && d.getMonth() === now.getMonth() && d.getFullYear() === now.getFullYear();
}

function groupByDate(entries: ScheduleEntry[]): { label: string; items: ScheduleEntry[] }[] {
  const map = new Map<string, ScheduleEntry[]>();
  for (const e of entries) {
    const d   = new Date(e.scheduled_at);
    const now = new Date();
    const sameDay = d.toDateString() === now.toDateString();
    const key = sameDay
      ? `Today · ${now.toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric' })}`
      : d.toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
    if (!map.has(key)) map.set(key, []);
    map.get(key)!.push(e);
  }
  return Array.from(map.entries()).map(([label, items]) => ({ label, items }));
}

function consultLabel(c: ScheduleEntry['consultation']) {
  if (!c) return 'No consultation';
  if (c.status === 'DRAFT')       return 'Consultation: Draft';
  if (c.status === 'IN_PROGRESS') return 'Consultation: In Progress';
  return 'Consultation: Completed';
}

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
  const sc    = statusConfig(entry.status);
  const tc    = typeConfig(entry.type);
  const today = isTodayDate(entry.scheduled_at);

  return (
    <View style={[styles.apptCard, today && styles.apptCardToday]}>
      {/* Time column */}
      <View style={styles.apptTimeCol}>
        {today && <View style={styles.todayDot} />}
        <Text style={styles.apptTime}>{formatTime(entry.scheduled_at)}</Text>
        <Text style={styles.apptDate}>{formatDate(entry.scheduled_at)}</Text>
        <Text style={styles.apptDur}>{entry.duration_minutes} min</Text>
      </View>

      {/* Colour accent */}
      <View style={[styles.apptAccent, { backgroundColor: tc.color }]} />

      {/* Details */}
      <View style={styles.apptBody}>
        <View style={styles.apptTopRow}>
          <Text style={styles.apptPatient}>{entry.patient_name}</Text>
          <View style={styles.badgeRow}>
            <View style={[styles.badge, { backgroundColor: tc.bg }]}>
              <Ionicons name={tc.icon} size={11} color={tc.color} />
              <Text style={[styles.badgeText, { color: tc.color }]}>{tc.label}</Text>
            </View>
            <View style={[styles.badge, { backgroundColor: sc.bg }]}>
              <Text style={[styles.badgeText, { color: sc.color }]}>{sc.label}</Text>
            </View>
          </View>
        </View>

        {!!entry.reason_for_visit && (
          <Text style={styles.apptReason} numberOfLines={2}>{entry.reason_for_visit}</Text>
        )}

        <View style={styles.apptFooter}>
          <View style={styles.metaPill}>
            <Ionicons
              name={entry.consultation ? 'document-text-outline' : 'document-outline'}
              size={12}
              color={entry.consultation ? BLUE : GRAY}
            />
            <Text style={[styles.metaText, { color: entry.consultation ? BLUE : GRAY }]}>
              {consultLabel(entry.consultation)}
            </Text>
          </View>

          {!!entry.facility_name && (
            <View style={styles.metaPill}>
              <Ionicons name="business-outline" size={12} color={GRAY} />
              <Text style={styles.metaText} numberOfLines={1}>{entry.facility_name}</Text>
            </View>
          )}

          <TouchableOpacity style={styles.viewBtn} activeOpacity={0.8}>
            <Text style={styles.viewBtnText}>View Details</Text>
            <Ionicons name="chevron-forward" size={14} color={BLUE} />
          </TouchableOpacity>
        </View>
      </View>
    </View>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────

export default function DoctorWebSchedule() {
  const { entries, loading, error, refetch } = useDoctorSchedule();
  const [dateFilter,   setDateFilter]   = useState<DateFilter>('today');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');

  const todayStart = useMemo(() => { const d = new Date(); d.setHours(0, 0, 0, 0);          return d; }, []);
  const todayEnd   = useMemo(() => { const d = new Date(); d.setHours(23, 59, 59, 999);      return d; }, []);
  const weekEnd    = useMemo(() => { const d = new Date(); d.setDate(d.getDate() + 7); d.setHours(23, 59, 59, 999); return d; }, []);

  const stats = useMemo(() => ({
    total:     entries.length,
    today:     entries.filter(e => { const d = new Date(e.scheduled_at); return d >= todayStart && d <= todayEnd; }).length,
    completed: entries.filter(e => e.status === 'COMPLETED').length,
    upcoming:  entries.filter(e => e.status === 'SCHEDULED' || e.status === 'IN_PROGRESS').length,
  }), [entries, todayStart, todayEnd]);

  const filtered = useMemo(() => {
    return entries
      .filter(e => {
        const d = new Date(e.scheduled_at);
        if (dateFilter === 'today') return d >= todayStart && d <= todayEnd;
        if (dateFilter === 'week')  return d >= todayStart && d <= weekEnd;
        return true;
      })
      .filter(e => statusFilter === 'ALL' || e.status === statusFilter)
      .sort((a, b) => new Date(a.scheduled_at).getTime() - new Date(b.scheduled_at).getTime());
  }, [entries, dateFilter, statusFilter, todayStart, todayEnd, weekEnd]);

  const groups = useMemo(() => groupByDate(filtered), [filtered]);

  const dateFilters: { key: DateFilter; label: string }[] = [
    { key: 'today', label: 'Today'     },
    { key: 'week',  label: 'This Week' },
    { key: 'all',   label: 'All Time'  },
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

  if (loading) {
    return <View style={styles.center}><ActivityIndicator size="large" color={BLUE} /></View>;
  }

  if (error) {
    return (
      <View style={styles.center}>
        <Ionicons name="alert-circle-outline" size={48} color={RED} />
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
          <Text style={styles.pageTitle}>My Schedule</Text>
          <Text style={styles.pageSub}>Manage your appointments and consultations</Text>
        </View>
        <TouchableOpacity style={styles.refreshBtn} onPress={refetch} activeOpacity={0.8}>
          <Ionicons name="refresh-outline" size={16} color={BLUE} />
          <Text style={styles.refreshText}>Refresh</Text>
        </TouchableOpacity>
      </View>

      {/* Stats Row */}
      <View style={styles.statsRow}>
        <StatCard icon="calendar-outline"         iconBg="#EEF2FF" iconColor={BLUE}  label="Total"     value={stats.total}     />
        <StatCard icon="today-outline"            iconBg="#D1FAE5" iconColor={GREEN} label="Today"     value={stats.today}     />
        <StatCard icon="checkmark-circle-outline" iconBg="#D1FAE5" iconColor={GREEN} label="Completed" value={stats.completed} />
        <StatCard icon="time-outline"             iconBg="#EEF2FF" iconColor={BLUE}  label="Upcoming"  value={stats.upcoming}  />
      </View>

      {/* Filters */}
      <View style={styles.filterCard}>
        <View style={styles.dateTabs}>
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
        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ marginTop: 12 }} contentContainerStyle={{ gap: 8 }}>
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
        </ScrollView>
      </View>

      {/* Result count */}
      <Text style={styles.resultCount}>{filtered.length} appointment{filtered.length !== 1 ? 's' : ''}</Text>

      {/* Appointment groups */}
      {groups.length === 0 ? (
        <View style={styles.emptyState}>
          <Ionicons name="calendar-outline" size={56} color={BORDER} />
          <Text style={styles.emptyTitle}>No appointments found</Text>
          <Text style={styles.emptySub}>Try adjusting your date range or status filter</Text>
        </View>
      ) : (
        <View style={styles.list}>
          {groups.map(g => (
            <View key={g.label} style={styles.group}>
              <Text style={styles.groupLabel}>{g.label}</Text>
              {g.items.map(e => <AppCard key={e.appointment_id} entry={e} />)}
            </View>
          ))}
        </View>
      )}
    </ScrollView>
  );
}

// ─── Styles ──────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  scroll:     { flex: 1, backgroundColor: BG },
  container:  { padding: 24, paddingBottom: 48 },
  center:     { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 12 },

  pageHeader:  { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 24 },
  pageTitle:   { fontSize: 24, fontWeight: '700', color: TEXT, marginBottom: 4 },
  pageSub:     { fontSize: 14, color: GRAY },
  refreshBtn:  {
    flexDirection: 'row', alignItems: 'center', gap: 6,
    borderWidth: 1, borderColor: BORDER, borderRadius: 8,
    paddingHorizontal: 12, paddingVertical: 8, backgroundColor: WHITE,
  },
  refreshText: { fontSize: 13, color: BLUE, fontWeight: '500' },

  statsRow: { flexDirection: 'row', gap: 12, marginBottom: 24, flexWrap: 'wrap' },
  statCard: {
    flex: 1, minWidth: 110,
    backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER,
    borderRadius: 12, padding: 16, gap: 6,
  },
  statIconBg: { width: 40, height: 40, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  statValue:  { fontSize: 28, fontWeight: '700', color: TEXT },
  statLabel:  { fontSize: 12, color: GRAY },

  filterCard: {
    backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER,
    borderRadius: 12, padding: 16, marginBottom: 16,
  },
  dateTabs:       { flexDirection: 'row', gap: 6 },
  dateTab:        { paddingHorizontal: 18, paddingVertical: 8, borderRadius: 8, borderWidth: 1, borderColor: BORDER },
  dateTabActive:  { backgroundColor: BLUE, borderColor: BLUE },
  dateTabText:    { fontSize: 14, fontWeight: '500', color: GRAY },
  dateTabTextActive: { color: WHITE },
  pill:           { paddingHorizontal: 14, paddingVertical: 6, borderRadius: 20, borderWidth: 1, borderColor: BORDER, backgroundColor: WHITE },
  pillActive:     { backgroundColor: '#EEF2FF', borderColor: BLUE },
  pillText:       { fontSize: 13, color: GRAY, fontWeight: '500' },
  pillTextActive: { color: BLUE },

  resultCount: { fontSize: 13, color: GRAY, marginBottom: 12, fontWeight: '500' },

  list:       { gap: 20 },
  group:      { gap: 10 },
  groupLabel: { fontSize: 11, fontWeight: '700', color: GRAY, letterSpacing: 1, textTransform: 'uppercase', marginBottom: 4 },

  apptCard: {
    backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER,
    borderRadius: 16, padding: 16, flexDirection: 'row', gap: 14,
  },
  apptCardToday: { borderColor: BLUE, borderWidth: 1.5 },

  apptTimeCol: { width: 82, gap: 2 },
  todayDot:    { width: 8, height: 8, borderRadius: 4, backgroundColor: BLUE, marginBottom: 4 },
  apptTime:    { fontSize: 14, fontWeight: '700', color: TEXT },
  apptDate:    { fontSize: 11, color: GRAY },
  apptDur:     { fontSize: 11, color: GRAY, marginTop: 2 },
  apptAccent:  { width: 3, borderRadius: 2, alignSelf: 'stretch' },

  apptBody:    { flex: 1, gap: 6 },
  apptTopRow:  { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: 6 },
  apptPatient: { fontSize: 16, fontWeight: '700', color: TEXT, flex: 1 },
  badgeRow:    { flexDirection: 'row', gap: 6, flexShrink: 0 },
  badge:       { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 8, paddingVertical: 3, borderRadius: 20 },
  badgeText:   { fontSize: 11, fontWeight: '600' },
  apptReason:  { fontSize: 13, color: GRAY, lineHeight: 18 },

  apptFooter:  { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: 8, marginTop: 4 },
  metaPill:    { flexDirection: 'row', alignItems: 'center', gap: 4, backgroundColor: BG, borderRadius: 6, paddingHorizontal: 8, paddingVertical: 4 },
  metaText:    { fontSize: 11, color: GRAY, fontWeight: '500' },
  viewBtn:     { flexDirection: 'row', alignItems: 'center', gap: 2, marginLeft: 'auto' as any },
  viewBtnText: { fontSize: 13, color: BLUE, fontWeight: '500' },

  emptyState:  { alignItems: 'center', gap: 8, paddingVertical: 64 },
  emptyTitle:  { fontSize: 18, fontWeight: '600', color: TEXT },
  emptySub:    { fontSize: 14, color: GRAY, textAlign: 'center' },

  errorText:    { fontSize: 15, color: GRAY, textAlign: 'center' },
  retryBtn:     { backgroundColor: BLUE, borderRadius: 8, paddingHorizontal: 20, paddingVertical: 10 },
  retryBtnText: { color: WHITE, fontWeight: '600', fontSize: 14 },
});
