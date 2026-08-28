import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import {
  ActivityIndicator, ScrollView, StyleSheet, Text,
  TouchableOpacity, View, useWindowDimensions,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { type ScheduleEntry, useDoctorSchedule } from '@/hooks/use-doctor-schedule';

const NAVY   = '#0D1B2E';
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

function useScale() {
  const { width, height } = useWindowDimensions();
  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));
  return { s, sh };
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function statusConfig(st: AppStatus) {
  switch (st) {
    case 'SCHEDULED':   return { bg: '#EEF2FF', color: BLUE,   label: 'Scheduled',   icon: 'time-outline'              as IoniconsName };
    case 'COMPLETED':   return { bg: '#D1FAE5', color: GREEN,  label: 'Completed',   icon: 'checkmark-circle-outline'  as IoniconsName };
    case 'CANCELLED':   return { bg: '#FEE2E2', color: RED,    label: 'Cancelled',   icon: 'close-circle-outline'      as IoniconsName };
    case 'NO_SHOW':     return { bg: '#FEF3C7', color: ORANGE, label: 'No Show',     icon: 'alert-circle-outline'      as IoniconsName };
    case 'RESCHEDULED': return { bg: '#F5F3FF', color: PURPLE, label: 'Rescheduled', icon: 'refresh-circle-outline'   as IoniconsName };
    case 'IN_PROGRESS': return { bg: '#CCFBF1', color: TEAL,  label: 'In Progress', icon: 'sync-outline'              as IoniconsName };
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
  if (!c) return { label: 'No consultation', color: LGRAY, bg: BG2,      icon: 'document-outline'         as IoniconsName };
  switch (c.status) {
    case 'DRAFT':       return { label: 'Draft',       color: AMBER, bg: '#FEF3C7', icon: 'document-outline'         as IoniconsName };
    case 'IN_PROGRESS': return { label: 'In Progress', color: TEAL,  bg: '#CCFBF1', icon: 'sync-outline'              as IoniconsName };
    case 'COMPLETED':   return { label: 'Completed',   color: GREEN, bg: '#D1FAE5', icon: 'checkmark-circle-outline' as IoniconsName };
  }
}

function formatTime(iso: string) {
  return new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', hour12: true }).toUpperCase();
}

function formatDate(iso: string) {
  return new Date(iso).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });
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

function dayStart() { const d = new Date(); d.setHours(0,0,0,0); return d; }
function dayEnd()   { const d = new Date(); d.setHours(23,59,59,999); return d; }

// ─── Stat Card ────────────────────────────────────────────────────────────────

function StatCard({ icon, iconBg, iconColor, label, value, s }: {
  icon: IoniconsName; iconBg: string; iconColor: string;
  label: string; value: number; s: (n: number) => number;
}) {
  return (
    <View style={[styles.statCard, { width: s(110), borderRadius: s(12), padding: s(12) }]}>
      <View style={[styles.statIconBg, { backgroundColor: iconBg, width: s(34), height: s(34), borderRadius: s(9) }]}>
        <Ionicons name={icon} size={s(16)} color={iconColor} />
      </View>
      <Text style={[styles.statValue, { fontSize: s(20), marginTop: s(6) }]}>{value}</Text>
      <Text style={[styles.statLabel, { fontSize: s(10) }]}>{label}</Text>
    </View>
  );
}

// ─── Appointment Card ─────────────────────────────────────────────────────────

function AppCard({ entry, s }: { entry: ScheduleEntry; s: (n: number) => number }) {
  const sc  = statusConfig(entry.status);
  const tc  = typeConfig(entry.type);
  const cc  = consultConfig(entry.consultation);
  const ac  = avatarColor(entry.patient_name);
  const tod = isToday(entry.scheduled_at);

  return (
    <View style={[
      styles.apptCard,
      { borderRadius: s(14), marginBottom: s(12), borderLeftWidth: s(4), borderLeftColor: tc.accent },
    ]}>
      {/* Today strip */}
      {tod && (
        <View style={[styles.todayBanner, { paddingHorizontal: s(12), paddingVertical: s(5) }]}>
          <Ionicons name="radio-button-on" size={s(9)} color={BLUE} />
          <Text style={[styles.todayText, { fontSize: s(10) }]}>Today</Text>
        </View>
      )}

      {/* Header: avatar + patient + status */}
      <View style={[styles.cardHeader, { padding: s(14), gap: s(10) }]}>
        <View style={[styles.avatar, { width: s(42), height: s(42), borderRadius: s(21), backgroundColor: ac }]}>
          <Text style={[styles.avatarText, { fontSize: s(14) }]}>{initials(entry.patient_name)}</Text>
        </View>
        <View style={{ flex: 1 }}>
          <Text style={[styles.patientName, { fontSize: s(14) }]} numberOfLines={1}>{entry.patient_name}</Text>
          <Text style={[styles.apptId, { fontSize: s(10), marginTop: s(2) }]}>Appt #{entry.appointment_id}</Text>
        </View>
        <View style={[styles.badge, { backgroundColor: sc.bg, borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
          <Ionicons name={sc.icon} size={s(10)} color={sc.color} />
          <Text style={[styles.badgeText, { fontSize: s(10), color: sc.color, marginLeft: s(3) }]}>{sc.label}</Text>
        </View>
      </View>

      {/* DateTime + type chips */}
      <View style={[styles.chipsRow, { paddingHorizontal: s(14), paddingBottom: s(10), gap: s(6) }]}>
        <View style={[styles.chip, { backgroundColor: BG, borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
          <Ionicons name="calendar-outline" size={s(10)} color={GRAY} />
          <Text style={[styles.chipText, { fontSize: s(10), color: GRAY, marginLeft: s(3) }]}>{formatDate(entry.scheduled_at)}</Text>
        </View>
        <View style={[styles.chip, { backgroundColor: BG, borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
          <Ionicons name="time-outline" size={s(10)} color={GRAY} />
          <Text style={[styles.chipText, { fontSize: s(10), color: GRAY, marginLeft: s(3) }]}>{formatTime(entry.scheduled_at)} · {entry.duration_minutes} min</Text>
        </View>
        <View style={[styles.chip, { backgroundColor: tc.bg, borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
          <Ionicons name={tc.icon} size={s(10)} color={tc.color} />
          <Text style={[styles.chipText, { fontSize: s(10), color: tc.color, marginLeft: s(3) }]}>{tc.label}</Text>
        </View>
      </View>

      {/* Reason */}
      {!!entry.reason_for_visit && (
        <View style={[styles.reasonBox, { marginHorizontal: s(14), marginBottom: s(10), borderRadius: s(8), padding: s(10) }]}>
          <View style={[styles.reasonLabelRow, { gap: s(5), marginBottom: s(4) }]}>
            <Ionicons name="clipboard-outline" size={s(12)} color={BLUE} />
            <Text style={[styles.reasonLabel, { fontSize: s(10) }]}>Reason for Visit</Text>
          </View>
          <Text style={[styles.reasonText, { fontSize: s(12), lineHeight: s(18) }]} numberOfLines={2}>
            {entry.reason_for_visit}
          </Text>
        </View>
      )}

      {/* Facility */}
      {!!entry.facility_name && (
        <View style={[styles.chipsRow, { paddingHorizontal: s(14), paddingBottom: s(10), gap: s(5) }]}>
          <Ionicons name="business-outline" size={s(11)} color={GRAY} />
          <Text style={[styles.chipText, { fontSize: s(11), color: GRAY }]}>{entry.facility_name}</Text>
        </View>
      )}

      {/* Footer: consultation + video */}
      <View style={[styles.cardFooter, { paddingHorizontal: s(14), paddingBottom: s(12), paddingTop: s(8), gap: s(8) }]}>
        <View style={[styles.chip, { backgroundColor: cc.bg, borderRadius: s(8), paddingHorizontal: s(8), paddingVertical: s(4) }]}>
          <Ionicons name={cc.icon} size={s(11)} color={cc.color} />
          <Text style={[styles.chipText, { fontSize: s(10), color: cc.color, marginLeft: s(3) }]}>{cc.label}</Text>
        </View>
        {entry.type === 'VIDEO' && !!entry.video_consultation_link && (
          <TouchableOpacity
            style={[styles.videoBtn, { borderRadius: s(8), paddingHorizontal: s(10), paddingVertical: s(5), gap: s(4) }]}
            activeOpacity={0.8}
          >
            <Ionicons name="videocam-outline" size={s(12)} color={BLUE} />
            <Text style={[styles.videoBtnText, { fontSize: s(11) }]}>Join Call</Text>
          </TouchableOpacity>
        )}
      </View>
    </View>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────

export default function DoctorMobileAppointment() {
  const { s, sh } = useScale();
  const insets = useSafeAreaInsets();
  const { entries, loading, error, refetch } = useDoctorSchedule();

  const [dateFilter,   setDateFilter]   = useState<DateFilter>('all');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');

  const now    = useMemo(() => new Date(), []);
  const todayS = useMemo(() => dayStart(), []);
  const todayE = useMemo(() => dayEnd(),   []);
  const weekE  = useMemo(() => { const d = dayEnd(); d.setDate(d.getDate() + 7); return d; }, []);

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
      .sort((a, b) => new Date(b.scheduled_at).getTime() - new Date(a.scheduled_at).getTime());
  }, [entries, dateFilter, statusFilter, now, todayS, todayE, weekE]);

  const dateFilters: { key: DateFilter; label: string }[] = [
    { key: 'all',      label: 'All'      },
    { key: 'today',    label: 'Today'    },
    { key: 'week',     label: 'Week'     },
    { key: 'upcoming', label: 'Upcoming' },
    { key: 'past',     label: 'Past'     },
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
          <Text style={[styles.headerTitle, { fontSize: s(20) }]}>Appointments</Text>
          <Text style={[styles.headerSub, { fontSize: s(12), marginTop: s(2) }]}>Your full appointment history</Text>
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
            {/* Stats horizontal scroll */}
            <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ marginBottom: s(16) }} contentContainerStyle={{ gap: s(10) }}>
              <StatCard icon="calendar-number-outline"  iconBg="#EEF2FF" iconColor={BLUE}   label="Total"       value={stats.total}      s={s} />
              <StatCard icon="time-outline"             iconBg="#EEF2FF" iconColor={BLUE}   label="Scheduled"   value={stats.scheduled}  s={s} />
              <StatCard icon="sync-outline"             iconBg="#CCFBF1" iconColor={TEAL}   label="In Progress" value={stats.inProgress} s={s} />
              <StatCard icon="checkmark-circle-outline" iconBg="#D1FAE5" iconColor={GREEN}  label="Completed"   value={stats.completed}  s={s} />
              <StatCard icon="close-circle-outline"     iconBg="#FEE2E2" iconColor={RED}    label="Cancelled"   value={stats.cancelled}  s={s} />
              <StatCard icon="alert-circle-outline"     iconBg="#FEF3C7" iconColor={ORANGE} label="No Show"     value={stats.noShow}     s={s} />
            </ScrollView>

            {/* Date filter tabs */}
            <View style={[styles.dateTabs, { borderRadius: s(10), padding: s(4), marginBottom: s(12) }]}>
              {dateFilters.map(f => (
                <TouchableOpacity
                  key={f.key}
                  style={[
                    styles.dateTab,
                    { flex: 1, borderRadius: s(8), paddingVertical: s(7) },
                    dateFilter === f.key && styles.dateTabActive,
                  ]}
                  onPress={() => setDateFilter(f.key)}
                  activeOpacity={0.7}
                >
                  <Text style={[styles.dateTabText, { fontSize: s(11) }, dateFilter === f.key && styles.dateTabTextActive]}>
                    {f.label}
                  </Text>
                </TouchableOpacity>
              ))}
            </View>

            {/* Status pills */}
            <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ marginBottom: s(16) }} contentContainerStyle={{ gap: s(8) }}>
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

            {/* Count */}
            <Text style={[styles.resultCount, { fontSize: s(12), marginBottom: s(12) }]}>
              {filtered.length} appointment{filtered.length !== 1 ? 's' : ''}
            </Text>

            {/* Cards */}
            {filtered.length === 0 ? (
              <View style={[styles.center, { paddingVertical: s(48), gap: s(8) }]}>
                <Ionicons name="calendar-outline" size={s(52)} color={BORDER} />
                <Text style={[styles.emptyTitle, { fontSize: s(16) }]}>No appointments found</Text>
                <Text style={[styles.errorText, { fontSize: s(13) }]}>Try adjusting your filters</Text>
              </View>
            ) : (
              filtered.map(e => <AppCard key={e.appointment_id} entry={e} s={s} />)
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

  apptCard:     { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  todayBanner:  { flexDirection: 'row', alignItems: 'center', gap: 5, backgroundColor: '#EEF2FF' },
  todayText:    { color: BLUE, fontWeight: '700' },
  cardHeader:   { flexDirection: 'row', alignItems: 'center' },
  avatar:       { alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  avatarText:   { color: WHITE, fontWeight: '700' },
  patientName:  { fontWeight: '700', color: TEXT },
  apptId:       { color: LGRAY, fontWeight: '500' },
  badge:        { flexDirection: 'row', alignItems: 'center' },
  badgeText:    { fontWeight: '600' },
  chipsRow:     { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center' },
  chip:         { flexDirection: 'row', alignItems: 'center' },
  chipText:     { fontWeight: '500' },
  reasonBox:    { backgroundColor: BG, borderWidth: 1, borderColor: BORDER },
  reasonLabelRow: { flexDirection: 'row', alignItems: 'center' },
  reasonLabel:  { fontWeight: '700', color: BLUE, textTransform: 'uppercase', letterSpacing: 0.4 },
  reasonText:   { color: TEXT, lineHeight: 18 },
  cardFooter:   { flexDirection: 'row', alignItems: 'center', borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: BORDER },
  videoBtn:     { flexDirection: 'row', alignItems: 'center', borderWidth: 1, borderColor: BLUE },
  videoBtnText: { color: BLUE, fontWeight: '500' },

  errorText:    { color: GRAY, textAlign: 'center' },
  retryBtn:     { backgroundColor: BLUE },
  retryBtnText: { color: WHITE, fontWeight: '600' },
  emptyTitle:   { fontWeight: '600', color: TEXT },
});
