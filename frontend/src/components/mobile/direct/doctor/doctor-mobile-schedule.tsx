import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, TouchableOpacity, View, useWindowDimensions } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { type ScheduleEntry, useDoctorSchedule } from '@/hooks/use-doctor-schedule';

const NAVY   = '#0D1B2E';
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

function useScale() {
  const { width, height } = useWindowDimensions();
  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));
  return { s, sh };
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function statusConfig(st: AppStatus) {
  switch (st) {
    case 'SCHEDULED':   return { bg: '#EEF2FF', color: BLUE,   label: 'Scheduled'   };
    case 'COMPLETED':   return { bg: '#D1FAE5', color: GREEN,  label: 'Completed'   };
    case 'CANCELLED':   return { bg: '#FEE2E2', color: RED,    label: 'Cancelled'   };
    case 'NO_SHOW':     return { bg: '#FEF3C7', color: ORANGE, label: 'No Show'     };
    case 'RESCHEDULED': return { bg: '#F5F3FF', color: PURPLE, label: 'Rescheduled' };
    case 'IN_PROGRESS': return { bg: '#CCFBF1', color: TEAL,  label: 'In Progress' };
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
    const key = d.toDateString() === now.toDateString()
      ? `Today · ${now.toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric' })}`
      : d.toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
    if (!map.has(key)) map.set(key, []);
    map.get(key)!.push(e);
  }
  return Array.from(map.entries()).map(([label, items]) => ({ label, items }));
}

function consultLabel(c: ScheduleEntry['consultation']) {
  if (!c) return 'No consultation';
  if (c.status === 'DRAFT')       return 'Draft';
  if (c.status === 'IN_PROGRESS') return 'In Progress';
  return 'Completed';
}

// ─── Stat Card ────────────────────────────────────────────────────────────────

function StatCard({ icon, iconBg, iconColor, label, value, s }: {
  icon: IoniconsName; iconBg: string; iconColor: string;
  label: string; value: number; s: (n: number) => number;
}) {
  return (
    <View style={[styles.statCard, { width: s(130), borderRadius: s(12), padding: s(12) }]}>
      <View style={[styles.statIconBg, { backgroundColor: iconBg, width: s(36), height: s(36), borderRadius: s(10) }]}>
        <Ionicons name={icon} size={s(18)} color={iconColor} />
      </View>
      <Text style={[styles.statValue, { fontSize: s(22), marginTop: s(6) }]}>{value}</Text>
      <Text style={[styles.statLabel, { fontSize: s(11) }]}>{label}</Text>
    </View>
  );
}

// ─── Appointment Card ─────────────────────────────────────────────────────────

function AppCard({ entry, s }: { entry: ScheduleEntry; s: (n: number) => number }) {
  const sc    = statusConfig(entry.status);
  const tc    = typeConfig(entry.type);
  const today = isTodayDate(entry.scheduled_at);

  return (
    <View style={[
      styles.apptCard,
      { borderRadius: s(14), padding: s(14), marginBottom: s(10), borderLeftWidth: s(4), borderLeftColor: tc.color },
      today && styles.apptCardToday,
    ]}>
      {/* Header row: patient name + status badge */}
      <View style={[styles.apptHeader, { marginBottom: s(6) }]}>
        <Text style={[styles.apptPatient, { fontSize: s(15) }]} numberOfLines={1}>{entry.patient_name}</Text>
        <View style={[styles.badge, { backgroundColor: sc.bg, borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
          <Text style={[styles.badgeText, { fontSize: s(10), color: sc.color }]}>{sc.label}</Text>
        </View>
      </View>

      {/* Time row */}
      <Text style={[styles.apptTime, { fontSize: s(13), marginBottom: s(8) }]}>
        {formatTime(entry.scheduled_at)} · {formatDate(entry.scheduled_at)}
      </Text>

      {/* Type + duration chips */}
      <View style={[styles.metaRow, { gap: s(6), marginBottom: s(8) }]}>
        <View style={[styles.chip, { backgroundColor: tc.bg, borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
          <Ionicons name={tc.icon} size={s(11)} color={tc.color} />
          <Text style={[styles.chipText, { fontSize: s(10), color: tc.color, marginLeft: s(3) }]}>{tc.label}</Text>
        </View>
        <View style={[styles.chip, { backgroundColor: BG, borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
          <Ionicons name="time-outline" size={s(11)} color={GRAY} />
          <Text style={[styles.chipText, { fontSize: s(10), color: GRAY, marginLeft: s(3) }]}>{entry.duration_minutes} min</Text>
        </View>
        {!!entry.facility_name && (
          <View style={[styles.chip, { backgroundColor: BG, borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
            <Ionicons name="business-outline" size={s(11)} color={GRAY} />
            <Text style={[styles.chipText, { fontSize: s(10), color: GRAY, marginLeft: s(3) }]} numberOfLines={1}>{entry.facility_name}</Text>
          </View>
        )}
      </View>

      {/* Reason */}
      {!!entry.reason_for_visit && (
        <Text style={[styles.apptReason, { fontSize: s(12), marginBottom: s(8) }]} numberOfLines={2}>
          {entry.reason_for_visit}
        </Text>
      )}

      {/* Consultation footer */}
      <View style={[styles.consultRow, { gap: s(5) }]}>
        <Ionicons
          name={entry.consultation ? 'document-text-outline' : 'document-outline'}
          size={s(13)}
          color={entry.consultation ? BLUE : GRAY}
        />
        <Text style={[styles.consultText, { fontSize: s(11), color: entry.consultation ? BLUE : GRAY }]}>
          {entry.consultation ? `Consultation: ${consultLabel(entry.consultation)}` : 'No consultation'}
        </Text>
      </View>
    </View>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────

export default function DoctorMobileSchedule() {
  const { s, sh } = useScale();
  const insets = useSafeAreaInsets();
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

  return (
    <View style={styles.container}>
      {/* Navy Header */}
      <View style={[styles.header, { paddingTop: insets.top + s(12), paddingHorizontal: s(16), paddingBottom: s(16) }]}>
        <View style={{ flex: 1 }}>
          <Text style={[styles.headerTitle, { fontSize: s(20) }]}>My Schedule</Text>
          <Text style={[styles.headerSub, { fontSize: s(12), marginTop: s(2) }]}>Appointments & Consultations</Text>
        </View>
        <TouchableOpacity
          onPress={refetch}
          style={[styles.refreshBtn, { width: s(36), height: s(36), borderRadius: s(18) }]}
          activeOpacity={0.7}
        >
          <Ionicons name="refresh-outline" size={s(18)} color={WHITE} />
        </TouchableOpacity>
      </View>

      <ScrollView
        style={styles.scroll}
        contentContainerStyle={{ padding: s(16), paddingBottom: sh(80) }}
        showsVerticalScrollIndicator={false}
      >
        {loading ? (
          <View style={[styles.center, { paddingVertical: s(60) }]}>
            <ActivityIndicator size="large" color={BLUE} />
          </View>
        ) : error ? (
          <View style={[styles.center, { paddingVertical: s(60), gap: s(10) }]}>
            <Ionicons name="alert-circle-outline" size={s(48)} color={RED} />
            <Text style={[styles.errorText, { fontSize: s(14) }]}>{error}</Text>
            <TouchableOpacity
              style={[styles.retryBtn, { borderRadius: s(8), paddingHorizontal: s(20), paddingVertical: s(10) }]}
              onPress={refetch}
              activeOpacity={0.8}
            >
              <Text style={[styles.retryBtnText, { fontSize: s(14) }]}>Retry</Text>
            </TouchableOpacity>
          </View>
        ) : (
          <>
            {/* Stats */}
            <ScrollView
              horizontal
              showsHorizontalScrollIndicator={false}
              style={{ marginBottom: s(16) }}
              contentContainerStyle={{ gap: s(10) }}
            >
              <StatCard icon="calendar-outline"         iconBg="#EEF2FF" iconColor={BLUE}  label="Total"     value={stats.total}     s={s} />
              <StatCard icon="today-outline"            iconBg="#D1FAE5" iconColor={GREEN} label="Today"     value={stats.today}     s={s} />
              <StatCard icon="checkmark-circle-outline" iconBg="#D1FAE5" iconColor={GREEN} label="Completed" value={stats.completed} s={s} />
              <StatCard icon="time-outline"             iconBg="#EEF2FF" iconColor={BLUE}  label="Upcoming"  value={stats.upcoming}  s={s} />
            </ScrollView>

            {/* Date filter tabs */}
            <View style={[styles.dateTabs, { borderRadius: s(10), padding: s(4), marginBottom: s(12) }]}>
              {dateFilters.map(f => (
                <TouchableOpacity
                  key={f.key}
                  style={[
                    styles.dateTab,
                    { flex: 1, borderRadius: s(8), paddingVertical: s(8) },
                    dateFilter === f.key && styles.dateTabActive,
                  ]}
                  onPress={() => setDateFilter(f.key)}
                  activeOpacity={0.7}
                >
                  <Text style={[styles.dateTabText, { fontSize: s(12) }, dateFilter === f.key && styles.dateTabTextActive]}>
                    {f.label}
                  </Text>
                </TouchableOpacity>
              ))}
            </View>

            {/* Status pills */}
            <ScrollView
              horizontal
              showsHorizontalScrollIndicator={false}
              style={{ marginBottom: s(16) }}
              contentContainerStyle={{ gap: s(8) }}
            >
              {statusFilters.map(f => (
                <TouchableOpacity
                  key={f.key}
                  style={[
                    styles.pill,
                    { borderRadius: s(20), paddingHorizontal: s(12), paddingVertical: s(6) },
                    statusFilter === f.key && styles.pillActive,
                  ]}
                  onPress={() => setStatusFilter(f.key)}
                  activeOpacity={0.7}
                >
                  <Text style={[styles.pillText, { fontSize: s(12) }, statusFilter === f.key && styles.pillTextActive]}>
                    {f.label}
                  </Text>
                </TouchableOpacity>
              ))}
            </ScrollView>

            {/* Result count */}
            <Text style={[styles.resultCount, { fontSize: s(12), marginBottom: s(12) }]}>
              {filtered.length} appointment{filtered.length !== 1 ? 's' : ''}
            </Text>

            {/* Appointment groups */}
            {groups.length === 0 ? (
              <View style={[styles.center, { paddingVertical: s(48), gap: s(8) }]}>
                <Ionicons name="calendar-outline" size={s(52)} color={BORDER} />
                <Text style={[styles.emptyTitle, { fontSize: s(16) }]}>No appointments found</Text>
                <Text style={[styles.errorText, { fontSize: s(13) }]}>Try adjusting your filters</Text>
              </View>
            ) : (
              groups.map(g => (
                <View key={g.label} style={{ marginBottom: s(16) }}>
                  <Text style={[styles.groupLabel, { fontSize: s(11), marginBottom: s(8) }]}>{g.label}</Text>
                  {g.items.map(e => <AppCard key={e.appointment_id} entry={e} s={s} />)}
                </View>
              ))
            )}
          </>
        )}
      </ScrollView>
    </View>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: BG },
  scroll:    { flex: 1 },
  center:    { alignItems: 'center', justifyContent: 'center' },

  header:      { backgroundColor: NAVY, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-end' },
  headerTitle: { color: WHITE, fontWeight: '700' },
  headerSub:   { color: '#8BA3BE' },
  refreshBtn:  { backgroundColor: 'rgba(255,255,255,0.12)', alignItems: 'center', justifyContent: 'center' },

  statCard:   { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  statIconBg: { alignItems: 'center', justifyContent: 'center' },
  statValue:  { fontWeight: '700', color: TEXT },
  statLabel:  { color: GRAY },

  dateTabs:      { flexDirection: 'row', backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  dateTab:       { alignItems: 'center' },
  dateTabActive: { backgroundColor: BLUE },
  dateTabText:   { fontWeight: '500', color: GRAY },
  dateTabTextActive: { color: WHITE, fontWeight: '600' },

  pill:          { borderWidth: 1, borderColor: BORDER, backgroundColor: WHITE },
  pillActive:    { backgroundColor: '#EEF2FF', borderColor: BLUE },
  pillText:      { fontWeight: '500', color: GRAY },
  pillTextActive:{ color: BLUE, fontWeight: '600' },

  resultCount: { color: GRAY, fontWeight: '500' },
  groupLabel:  { fontWeight: '700', color: GRAY, letterSpacing: 0.8, textTransform: 'uppercase' },

  apptCard:     { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  apptCardToday:{ borderColor: BLUE },
  apptHeader:   { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  apptPatient:  { fontWeight: '700', color: TEXT, flex: 1, marginRight: 8 },
  apptTime:     { color: GRAY },
  badge:        { flexDirection: 'row', alignItems: 'center' },
  badgeText:    { fontWeight: '600' },
  metaRow:      { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center' },
  chip:         { flexDirection: 'row', alignItems: 'center' },
  chipText:     { fontWeight: '500' },
  apptReason:   { color: GRAY, lineHeight: 18 },
  consultRow:   { flexDirection: 'row', alignItems: 'center' },
  consultText:  { fontWeight: '500' },

  errorText:    { color: GRAY, textAlign: 'center' },
  retryBtn:     { backgroundColor: BLUE },
  retryBtnText: { color: WHITE, fontWeight: '600' },
  emptyTitle:   { fontWeight: '600', color: TEXT },
});
