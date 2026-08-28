import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import {
  Alert, Image, Linking, KeyboardAvoidingView, Platform, ScrollView,
  StyleSheet, Text, TouchableOpacity, useWindowDimensions, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useAppointments, type Appointment } from '@/hooks/use-appointments';
import { api } from '@/lib/auth/auth.interceptor';

const TEAL   = '#0D9488';
const INDIGO = '#4F46E5';
const GREEN  = '#10B981';
const AMBER  = '#F59E0B';
const RED    = '#EF4444';
const PURPLE = '#8B5CF6';
const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type FilterKey = 'ALL' | 'SCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW' | 'RESCHEDULED';

const STATUS_CONFIG: Record<string, { label: string; color: string; bg: string; icon: IoniconsName }> = {
  SCHEDULED:   { label: 'Upcoming',    color: TEAL,   bg: '#E6F4F1', icon: 'time-outline'           },
  COMPLETED:   { label: 'Completed',   color: GREEN,  bg: '#ECFDF5', icon: 'checkmark-circle-outline'},
  CANCELLED:   { label: 'Cancelled',   color: RED,    bg: '#FEF2F2', icon: 'close-circle-outline'    },
  NO_SHOW:     { label: 'No Show',     color: AMBER,  bg: '#FFFBEB', icon: 'alert-circle-outline'    },
  RESCHEDULED: { label: 'Rescheduled', color: PURPLE, bg: '#F5F3FF', icon: 'refresh-outline'         },
};

const TYPE_CONFIG: Record<string, { label: string; color: string; bg: string; icon: IoniconsName }> = {
  IN_PERSON: { label: 'In Person',  color: TEAL,   bg: '#E6F4F1', icon: 'business-outline' },
  VIDEO:     { label: 'Video Call', color: INDIGO, bg: '#EEF2FF', icon: 'videocam-outline'  },
  PHONE:     { label: 'Phone Call', color: GREEN,  bg: '#F0FDF4', icon: 'call-outline'      },
};

const FILTERS: { key: FilterKey; label: string }[] = [
  { key: 'ALL',         label: 'All'         },
  { key: 'SCHEDULED',   label: 'Upcoming'    },
  { key: 'COMPLETED',   label: 'Completed'   },
  { key: 'RESCHEDULED', label: 'Rescheduled' },
  { key: 'CANCELLED',   label: 'Cancelled'   },
  { key: 'NO_SHOW',     label: 'No Show'     },
];

function formatDate(iso: string) {
  const d = new Date(iso);
  return d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });
}
function formatTime(iso: string) {
  return new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
}

function AppointmentCard({ appt, s, onRefetch }: { appt: Appointment; s: (n: number) => number; onRefetch: () => void }) {
  const [expanded, setExpanded]     = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const status    = STATUS_CONFIG[appt.status] ?? STATUS_CONFIG.SCHEDULED;
  const type      = TYPE_CONFIG[appt.type]     ?? TYPE_CONFIG.IN_PERSON;
  const canCancel = appt.status === 'SCHEDULED' || appt.status === 'RESCHEDULED';

  async function handleCancel() {
    Alert.alert(
      'Cancel Appointment',
      `Cancel your appointment with ${appt.doctor_name} on ${formatDate(appt.scheduled_at)}? This cannot be undone.`,
      [
        { text: 'Keep it', style: 'cancel' },
        {
          text: 'Yes, delete it',
          style: 'destructive',
          onPress: async () => {
            setCancelling(true);
            try {
              await api.delete(`/api/appointments/${appt.appointment_id}`);
              onRefetch();
            } catch {
              Alert.alert('Error', 'Failed to cancel appointment. Please try again.');
            } finally {
              setCancelling(false);
            }
          },
        },
      ]
    );
  }

  return (
    <View style={[styles.card, { borderRadius: s(16), marginBottom: s(12) }]}>
      {/* Status stripe */}
      <View style={[styles.cardStripe, { backgroundColor: status.color, borderTopLeftRadius: s(16), borderTopRightRadius: s(16) }]} />

      <View style={{ padding: s(14) }}>
        {/* Header */}
        <View style={[styles.cardHeader, { gap: s(10), marginBottom: s(10) }]}>
          <View style={[styles.typeIconBg, { width: s(38), height: s(38), borderRadius: s(11), backgroundColor: type.bg }]}>
            <Ionicons name={type.icon} size={s(18)} color={type.color} />
          </View>

          <View style={{ flex: 1 }}>
            <Text style={[styles.doctorName, { fontSize: s(14) }]}>{appt.doctor_name}</Text>
            {appt.doctor_specialization && (
              <Text style={[styles.doctorSpec, { fontSize: s(11) }]}>{appt.doctor_specialization}</Text>
            )}
          </View>

          {appt.doctor_avatar_url ? (
            <Image source={{ uri: appt.doctor_avatar_url }} style={[styles.avatar, { width: s(36), height: s(36), borderRadius: s(18) }]} />
          ) : (
            <View style={[styles.avatar, styles.avatarFallback, { width: s(36), height: s(36), borderRadius: s(18) }]}>
              <Text style={[styles.avatarInitial, { fontSize: s(14) }]}>{appt.doctor_name[4] ?? 'D'}</Text>
            </View>
          )}
        </View>

        {/* Badges */}
        <View style={[styles.badgeRow, { gap: s(6), marginBottom: s(10) }]}>
          <View style={[styles.badge, { backgroundColor: status.bg, borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
            <Ionicons name={status.icon} size={s(11)} color={status.color} />
            <Text style={[styles.badgeText, { fontSize: s(10), color: status.color }]}>{status.label}</Text>
          </View>
          <View style={[styles.badge, { backgroundColor: type.bg, borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
            <Ionicons name={type.icon} size={s(11)} color={type.color} />
            <Text style={[styles.badgeText, { fontSize: s(10), color: type.color }]}>{type.label}</Text>
          </View>
        </View>

        {/* Meta */}
        <View style={[styles.metaGrid, { gap: s(8), marginBottom: s(10) }]}>
          <View style={[styles.metaChip, { borderRadius: s(8), paddingHorizontal: s(10), paddingVertical: s(6), gap: s(5) }]}>
            <Ionicons name="calendar-outline" size={s(13)} color={TEAL} />
            <Text style={[styles.metaText, { fontSize: s(11) }]}>{formatDate(appt.scheduled_at)}</Text>
          </View>
          <View style={[styles.metaChip, { borderRadius: s(8), paddingHorizontal: s(10), paddingVertical: s(6), gap: s(5) }]}>
            <Ionicons name="time-outline" size={s(13)} color={TEAL} />
            <Text style={[styles.metaText, { fontSize: s(11) }]}>{formatTime(appt.scheduled_at)} · {appt.duration_minutes} min</Text>
          </View>
          {appt.facility_name && (
            <View style={[styles.metaChip, { borderRadius: s(8), paddingHorizontal: s(10), paddingVertical: s(6), gap: s(5) }]}>
              <Ionicons name="location-outline" size={s(13)} color={TEAL} />
              <Text style={[styles.metaText, { fontSize: s(11) }]} numberOfLines={1}>{appt.facility_name}</Text>
            </View>
          )}
        </View>

        {/* Reason */}
        {appt.reason_for_visit && (
          <View style={[styles.reasonBox, { borderRadius: s(10), padding: s(10), gap: s(6), marginBottom: s(8) }]}>
            <Ionicons name="document-text-outline" size={s(13)} color={GRAY} />
            <Text style={[styles.reasonText, { fontSize: s(12) }]} numberOfLines={expanded ? undefined : 2}>
              {appt.reason_for_visit}
            </Text>
          </View>
        )}

        {/* Expanded content */}
        {expanded && (
          <View style={{ gap: s(8), marginBottom: s(8) }}>
            {appt.notes && (
              <View style={[styles.notesBox, { borderRadius: s(10), padding: s(10) }]}>
                <Text style={[styles.notesLabel, { fontSize: s(10) }]}>NOTES</Text>
                <Text style={[styles.notesText, { fontSize: s(12) }]}>{appt.notes}</Text>
              </View>
            )}
            {appt.video_consultation_link && (
              <TouchableOpacity
                style={[styles.videoBtn, { borderRadius: s(10), padding: s(10), gap: s(8) }]}
                onPress={() => Linking.openURL(appt.video_consultation_link!)}
                activeOpacity={0.8}
              >
                <Ionicons name="videocam-outline" size={s(15)} color={INDIGO} />
                <Text style={[styles.videoBtnText, { fontSize: s(12), flex: 1 }]}>Join Video Consultation</Text>
                <Ionicons name="open-outline" size={s(13)} color={INDIGO} />
              </TouchableOpacity>
            )}
          </View>
        )}

        {/* Cancel button — always visible for cancellable appointments */}
        {canCancel && (
          <TouchableOpacity
            style={[styles.cancelBtn, { borderRadius: s(10), paddingVertical: s(10), gap: s(6), marginBottom: s(8) }, cancelling && { opacity: 0.6 }]}
            onPress={handleCancel}
            disabled={cancelling}
            activeOpacity={0.8}
          >
            <Ionicons name="trash-outline" size={s(15)} color={RED} />
            <Text style={[styles.cancelBtnText, { fontSize: s(13) }]}>{cancelling ? 'Cancelling…' : 'Cancel & Delete'}</Text>
          </TouchableOpacity>
        )}

        {/* Expand toggle */}
        <TouchableOpacity
          style={[styles.expandToggle, { paddingTop: s(8), gap: s(4) }]}
          onPress={() => setExpanded(e => !e)}
          activeOpacity={0.7}
        >
          <Text style={[styles.expandToggleText, { fontSize: s(12) }]}>{expanded ? 'Show less' : 'Show details'}</Text>
          <Ionicons name={expanded ? 'chevron-up' : 'chevron-down'} size={s(13)} color={TEAL} />
        </TouchableOpacity>
      </View>
    </View>
  );
}

export function PatientMobileAppointmentStatus({
  onBack,
  onBook,
}: {
  onBack?: () => void;
  onBook?: () => void;
}) {
  const router  = useRouter();
  const insets  = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  const { appointments, loading, error, refetch } = useAppointments();
  const [filter, setFilter]           = useState<FilterKey>('ALL');
  const [visibleCount, setVisibleCount] = useState(5);

  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));

  useEffect(() => { setVisibleCount(5); }, [filter]);

  const now = Date.now();
  const sorted    = [...(filter === 'ALL' ? appointments : appointments.filter(a => a.status === filter))]
    .sort((a, b) => {
      const ta = new Date(a.scheduled_at).getTime();
      const tb = new Date(b.scheduled_at).getTime();
      const aFuture = ta >= now;
      const bFuture = tb >= now;
      if (aFuture && bFuture) return ta - tb;   // both upcoming: soonest first
      if (!aFuture && !bFuture) return tb - ta;  // both past: most recent first
      return aFuture ? -1 : 1;                   // upcoming before past
    });
  const filtered  = sorted;
  const visible   = sorted.slice(0, visibleCount);
  const hasMore   = visibleCount < sorted.length;
  const remaining = sorted.length - visibleCount;
  const upcoming  = appointments.filter(a => a.status === 'SCHEDULED' || a.status === 'RESCHEDULED').length;
  const completed = appointments.filter(a => a.status === 'COMPLETED').length;
  const cancelled = appointments.filter(a => a.status === 'CANCELLED' || a.status === 'NO_SHOW').length;

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
          <Text style={[styles.headerTitle, { fontSize: s(18) }]}>My Appointments</Text>
          <Text style={[styles.headerSub,   { fontSize: s(11) }]}>Your healthcare visit history</Text>
        </View>
        <TouchableOpacity
          style={[styles.headerRefresh, { width: s(34), height: s(34), borderRadius: s(17) }]}
          onPress={refetch}
          activeOpacity={0.7}
        >
          <Ionicons name="refresh-outline" size={s(17)} color={TEAL} />
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.headerBookBtn, { borderRadius: s(10), paddingHorizontal: s(10), paddingVertical: s(7), gap: s(5) }]}
          onPress={() => onBook ? onBook() : router.push('/dashboard/patient/appointments/book' as any)}
          activeOpacity={0.8}
        >
          <Ionicons name="add-outline" size={s(15)} color={TEAL} />
          <Text style={[styles.headerBookText, { fontSize: s(11) }]}>Book</Text>
        </TouchableOpacity>
      </View>

      {/* Stats strip */}
      <View style={[styles.statsStrip, { paddingHorizontal: s(16), paddingVertical: s(14), gap: s(10) }]}>
        {[
          { value: appointments.length, label: 'Total',     color: INDIGO },
          { value: upcoming,            label: 'Upcoming',  color: TEAL   },
          { value: completed,           label: 'Completed', color: GREEN  },
          { value: cancelled,           label: 'Cancelled', color: RED    },
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
          const count = f.key === 'ALL' ? appointments.length : appointments.filter(a => a.status === f.key).length;
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
            <Text style={[styles.centerText, { fontSize: s(14) }]}>Loading appointments…</Text>
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
            <Ionicons name="calendar-outline" size={s(48)} color={BORDER} />
            <Text style={[styles.centerText, { fontSize: s(14) }]}>
              {filter === 'ALL'
                ? 'No appointments found.'
                : `No ${FILTERS.find(f => f.key === filter)?.label.toLowerCase()} appointments.`}
            </Text>
          </View>
        ) : (
          <>
            {visible.map(appt => <AppointmentCard key={appt.appointment_id} appt={appt} s={s} onRefetch={refetch} />)}
            {hasMore && (
              <TouchableOpacity
                style={[styles.showMoreBtn, { borderRadius: s(12), paddingVertical: s(14), gap: s(8), marginBottom: s(4) }]}
                onPress={() => setVisibleCount(c => c + 5)}
                activeOpacity={0.8}
              >
                <Ionicons name="chevron-down-circle-outline" size={s(18)} color={TEAL} />
                <Text style={[styles.showMoreText, { fontSize: s(13) }]}>
                  Show {remaining} more appointment{remaining !== 1 ? 's' : ''}
                </Text>
              </TouchableOpacity>
            )}
          </>
        )}
      </ScrollView>

      {/* FAB — Book New Appointment */}
      <TouchableOpacity
        style={[styles.fab, {
          bottom:        insets.bottom + s(20),
          right:         s(20),
          width:         s(56),
          height:        s(56),
          borderRadius:  s(28),
          gap:           s(6),
        }]}
        onPress={() => onBook ? onBook() : router.push('/dashboard/patient/appointments/book' as any)}
        activeOpacity={0.85}
      >
        <Ionicons name="add" size={s(26)} color={WHITE} />
      </TouchableOpacity>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: BG },

  header:          { backgroundColor: TEAL, flexDirection: 'row', alignItems: 'center' },
  headerBack:      { backgroundColor: 'rgba(255,255,255,0.2)', alignItems: 'center', justifyContent: 'center' },
  headerBookBtn:   { flexDirection: 'row', alignItems: 'center', backgroundColor: WHITE },
  headerBookText:  { fontWeight: '700', color: TEAL },
  headerTitle:     { fontWeight: '800', color: WHITE },
  headerSub:     { color: 'rgba(255,255,255,0.8)', marginTop: 2 },
  headerRefresh: { backgroundColor: WHITE, alignItems: 'center', justifyContent: 'center' },

  statsStrip: { flexDirection: 'row', backgroundColor: WHITE, borderBottomWidth: 1, borderBottomColor: BORDER },
  statItem:   { flex: 1, alignItems: 'center', backgroundColor: BG },
  statValue:  { fontWeight: '800' },
  statLabel:  { color: GRAY, fontWeight: '500', marginTop: 2 },

  filterScroll: { flexGrow: 0, backgroundColor: WHITE, borderBottomWidth: 1, borderBottomColor: BORDER },
  filterPill:      { flexDirection: 'row', alignItems: 'center', borderWidth: 1.5, borderColor: BORDER, backgroundColor: WHITE },
  filterPillActive:{ borderColor: TEAL, backgroundColor: '#E6F4F1' },
  filterPillText:      { fontWeight: '500', color: GRAY },
  filterPillTextActive:{ fontWeight: '700', color: TEAL },
  filterPillCount: { paddingVertical: 1 },

  scroll: { flex: 1 },

  card:       { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER, overflow: 'hidden', shadowColor: '#000', shadowOpacity: 0.04, shadowRadius: 8, elevation: 2 } as any,
  cardStripe: { height: 4 },
  cardHeader: { flexDirection: 'row', alignItems: 'center' },
  typeIconBg: { alignItems: 'center', justifyContent: 'center' },
  doctorName: { fontWeight: '700', color: TEXT },
  doctorSpec: { color: GRAY, marginTop: 2 },
  avatar:     {},
  avatarFallback: { backgroundColor: '#E6F4F1', alignItems: 'center', justifyContent: 'center' },
  avatarInitial:  { fontWeight: '700', color: TEAL },

  badgeRow:  { flexDirection: 'row' },
  badge:     { flexDirection: 'row', alignItems: 'center' },
  badgeText: { fontWeight: '700' },

  metaGrid:  { flexDirection: 'row', flexWrap: 'wrap' },
  metaChip:  { flexDirection: 'row', alignItems: 'center', backgroundColor: BG, borderWidth: 1, borderColor: BORDER },
  metaText:  { color: TEXT, fontWeight: '500' },

  reasonBox:  { flexDirection: 'row', alignItems: 'flex-start', backgroundColor: BG, borderWidth: 1, borderColor: BORDER },
  reasonText: { flex: 1, color: TEXT, lineHeight: 18 },

  notesBox:   { backgroundColor: '#FFFBEB' },
  notesLabel: { fontWeight: '700', color: AMBER, marginBottom: 4, letterSpacing: 0.5 },
  notesText:  { color: TEXT, lineHeight: 18 },
  videoBtn:   { flexDirection: 'row', alignItems: 'center', backgroundColor: '#EEF2FF', borderWidth: 1, borderColor: '#C7D2FE' },
  videoBtnText:{ fontWeight: '600', color: INDIGO },

  cancelBtn:        { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', borderWidth: 1.5, borderColor: RED, backgroundColor: '#FEF2F2' },
  cancelBtnText:    { fontWeight: '600', color: RED },
  expandToggle:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', borderTopWidth: 1, borderTopColor: BORDER },
  expandToggleText: { fontWeight: '600', color: TEAL },

  centerState: { alignItems: 'center', justifyContent: 'center', paddingVertical: 60, gap: 12 },
  centerText:  { color: GRAY, textAlign: 'center' },
  retryBtn:     { backgroundColor: TEAL },
  retryBtnText: { fontWeight: '700', color: WHITE },
  showMoreBtn:  { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', borderWidth: 1.5, borderColor: TEAL, backgroundColor: '#E6F4F1' },
  showMoreText: { fontWeight: '700', color: TEAL },

  fab: {
    position: 'absolute',
    backgroundColor: TEAL,
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.25,
    shadowRadius: 8,
    elevation: 8,
  },
});

export default PatientMobileAppointmentStatus;
