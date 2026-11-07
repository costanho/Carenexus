import { Ionicons } from '@expo/vector-icons';
import { Image, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import {
  apiErrorMessage, appointmentCallJoin, cancelAppointment, isFutureSlot, isUpcomingAppointment,
  rescheduleAppointment, useAppointments, type Appointment,
} from '@/hooks/use-appointments';
import { useEffect, useState } from 'react';
import { confirmAction, showMessage } from '@/lib/ui/dialogs';
import { CallJoinButton } from '@/components/web/call/call-join-button';
import { doctorInitial } from '@/hooks/use-doctors';
import { CallTimePending } from './call-time-pending';
import { CalendarPicker, TimePicker } from './patient-web-appointment';

const TEAL   = '#0D9488';
const INDIGO = '#4F46E5';
const GREEN  = '#10B981';
const AMBER  = '#F59E0B';
const RED    = '#EF4444';
const PURPLE = '#8B5CF6';
const SLATE  = '#374151';
const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const CARD   = '#FFFFFF';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type FilterKey = 'ALL' | 'SCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW' | 'RESCHEDULED';

const STATUS_CONFIG: Record<string, { label: string; color: string; bg: string; icon: IoniconsName }> = {
  SCHEDULED:   { label: 'Upcoming',    color: TEAL,   bg: '#E6F4F1', icon: 'time-outline'          },
  COMPLETED:   { label: 'Completed',   color: GREEN,  bg: '#ECFDF5', icon: 'checkmark-circle-outline'},
  CANCELLED:   { label: 'Cancelled',   color: RED,    bg: '#FEF2F2', icon: 'close-circle-outline'   },
  NO_SHOW:     { label: 'No Show',     color: AMBER,  bg: '#FFFBEB', icon: 'alert-circle-outline'   },
  RESCHEDULED: { label: 'Rescheduled', color: PURPLE, bg: '#F5F3FF', icon: 'refresh-outline'        },
};

const TYPE_CONFIG: Record<string, { label: string; color: string; bg: string; icon: IoniconsName }> = {
  IN_PERSON: { label: 'In Person',  color: TEAL,   bg: '#E6F4F1', icon: 'business-outline'  },
  VIDEO:     { label: 'Video Call', color: INDIGO, bg: '#EEF2FF', icon: 'videocam-outline'   },
  PHONE:     { label: 'Phone Call', color: GREEN,  bg: '#F0FDF4', icon: 'call-outline'       },
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
  return d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
}

function formatTime(iso: string) {
  const d = new Date(iso);
  return d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
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

function ReschedulePanel({ appt, onDone, onClose }: { appt: Appointment; onDone: () => void; onClose: () => void }) {
  const [date, setDate]     = useState('');
  const [time, setTime]     = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError]   = useState('');
  const slotInPast = !!date && !!time && !isFutureSlot(date, time);

  async function handleSave() {
    setError('');
    if (!date || !time) { setError('Please choose a new date and time.'); return; }
    if (!isFutureSlot(date, time)) { setError('Please choose a date and time in the future.'); return; }
    setSaving(true);
    try {
      await rescheduleAppointment(appt.appointment_id, date, time);
      onDone();
    } catch (e: any) {
      setError(apiErrorMessage(e, 'Failed to reschedule appointment. Please try again.'));
    } finally {
      setSaving(false);
    }
  }

  return (
    <View style={styles.reschedulePanel}>
      <Text style={styles.rescheduleTitle}>Choose a new date and time</Text>
      <View style={styles.reschedulePickers}>
        <View style={styles.reschedulePickerCol}>
          <CalendarPicker value={date} onChange={setDate} />
        </View>
        <View style={styles.reschedulePickerCol}>
          <TimePicker value={time} onChange={setTime} />
        </View>
      </View>
      {(!!error || slotInPast) && (
        <View style={styles.inlineError}>
          <Ionicons name="alert-circle-outline" size={14} color={RED} />
          <Text style={styles.inlineErrorText}>{error || 'This time has already passed. Please choose a later time.'}</Text>
        </View>
      )}
      <View style={styles.rescheduleActions}>
        <TouchableOpacity
          style={styles.rescheduleCancelBtn}
          onPress={onClose}
          disabled={saving}
          activeOpacity={0.8}
          accessibilityRole="button"
        >
          <Text style={styles.rescheduleCancelText}>Keep current time</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.rescheduleSaveBtn, saving && { opacity: 0.6 }]}
          onPress={handleSave}
          disabled={saving}
          activeOpacity={0.85}
          accessibilityRole="button"
          accessibilityState={{ disabled: saving }}
        >
          <Text style={styles.rescheduleSaveText}>{saving ? 'Saving…' : 'Confirm new time'}</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const RESCHEDULED_NOTICE = 'Your appointment has been moved to the new time.';

function AppointmentCard({ appt, notice, onRescheduled, onMessageDoctor }: {
  appt: Appointment;
  /** Confirmation for this card, owned by the list so it survives the refresh after a change. */
  notice: string;
  /** Shows (id) or clears (null) the reschedule confirmation. */
  onRescheduled: (appointmentId: number | null) => void;
  onMessageDoctor?: (doctorSystemId: string, doctorName: string) => void;
}) {
  const [expanded, setExpanded]       = useState(false);
  const [cancelling, setCancelling]   = useState(false);
  const [rescheduling, setRescheduling] = useState(false);
  const status = STATUS_CONFIG[appt.status] ?? STATUS_CONFIG.SCHEDULED;
  const type   = TYPE_CONFIG[appt.type]     ?? TYPE_CONFIG.IN_PERSON;
  // Only appointments that are still ahead of us can be changed.
  const canChange = isUpcomingAppointment(appt);

  async function handleCancel() {
    const ok = await confirmAction(
      'Cancel appointment',
      `Cancel your appointment with ${appt.doctor_name} on ${formatDate(appt.scheduled_at)} at ${formatTime(appt.scheduled_at)}?`,
      'Cancel appointment',
      true,
    );
    if (!ok) return;
    setCancelling(true);
    try {
      // Refreshes the shared appointment list, so every panel shows the cancellation.
      await cancelAppointment(appt.appointment_id);
    } catch (e: any) {
      showMessage('Could not cancel appointment', apiErrorMessage(e, 'Failed to cancel appointment. Please try again.'));
    } finally {
      setCancelling(false);
    }
  }

  function handleRescheduled() {
    setRescheduling(false);
    onRescheduled(appt.appointment_id);
  }

  return (
    <View style={styles.card}>
      {/* Top row */}
      <View style={styles.cardHeader}>
        <View style={[styles.typeIconBg, { backgroundColor: type.bg }]}>
          <Ionicons name={type.icon} size={20} color={type.color} />
        </View>

        <View style={styles.cardHeaderText}>
          <Text style={styles.doctorName}>{appt.doctor_name}</Text>
          {appt.doctor_specialization && (
            <Text style={styles.doctorSpec}>{appt.doctor_specialization}</Text>
          )}
        </View>

        {appt.doctor_avatar_url ? (
          <Image source={{ uri: appt.doctor_avatar_url }} style={styles.avatar} />
        ) : (
          <View style={[styles.avatar, styles.avatarFallback]} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
            <Text style={styles.avatarInitial}>{doctorInitial(appt.doctor_name)}</Text>
          </View>
        )}
      </View>

      {/* Meta row */}
      <View style={styles.metaRow}>
        <View style={styles.metaItem}>
          <Ionicons name="calendar-outline" size={13} color={GRAY} />
          <Text style={styles.metaText}>{formatDate(appt.scheduled_at)}</Text>
        </View>
        <View style={styles.metaDot} />
        <View style={styles.metaItem}>
          <Ionicons name="time-outline" size={13} color={GRAY} />
          <Text style={styles.metaText}>{formatTime(appt.scheduled_at)}</Text>
        </View>
        <View style={styles.metaDot} />
        <View style={styles.metaItem}>
          <Ionicons name="hourglass-outline" size={13} color={GRAY} />
          <Text style={styles.metaText}>{appt.duration_minutes} min</Text>
        </View>
        {appt.facility_name && (
          <>
            <View style={styles.metaDot} />
            <View style={styles.metaItem}>
              <Ionicons name="location-outline" size={13} color={GRAY} />
              <Text style={styles.metaText} numberOfLines={1}>{appt.facility_name}</Text>
            </View>
          </>
        )}
      </View>

      {/* Badges */}
      <View style={styles.badgeRow}>
        <View style={[styles.badge, { backgroundColor: status.bg }]}>
          <Ionicons name={status.icon} size={12} color={status.color} />
          <Text style={[styles.badgeText, { color: status.color }]}>{status.label}</Text>
        </View>
        <View style={[styles.badge, { backgroundColor: type.bg }]}>
          <Ionicons name={type.icon} size={12} color={type.color} />
          <Text style={[styles.badgeText, { color: type.color }]}>{type.label}</Text>
        </View>
      </View>

      {/* Reason */}
      {appt.reason_for_visit && (
        <View style={styles.reasonBox}>
          <Ionicons name="document-text-outline" size={13} color={GRAY} />
          <Text style={styles.reasonText} numberOfLines={expanded ? undefined : 2}>
            {appt.reason_for_visit}
          </Text>
        </View>
      )}

      {/* Video call: a time still awaiting the clinic's confirmation is a note, not a button. */}
      {appt.call_status === 'NEEDS_TIME_CONFIRMATION' ? (
        appointmentCallJoin(appt) && canChange ? <CallTimePending style={{ marginBottom: 8 }} /> : null
      ) : (
        <CallJoinButton
          info={appointmentCallJoin(appt)}
          withName={appt.doctor_name}
          style={styles.videoBtn}
          textStyle={styles.videoBtnText}
          iconSize={15}
          iconColor={INDIGO}
        />
      )}

      {/* Expanded: notes */}
      {expanded && (
        <View style={styles.expandedSection}>
          {appt.notes && (
            <View style={styles.notesBox}>
              <Text style={styles.notesLabel}>Notes</Text>
              <Text style={styles.notesText}>{appt.notes}</Text>
            </View>
          )}
        </View>
      )}

      {!!notice && (
        <View style={styles.noticeBox} accessibilityLiveRegion="polite" aria-live="polite">
          <Ionicons name="checkmark-circle-outline" size={14} color={GREEN} />
          <Text style={styles.noticeText}>{notice}</Text>
        </View>
      )}

      {/* Reschedule / cancel — only for appointments that have not happened yet */}
      {canChange && rescheduling && (
        <ReschedulePanel appt={appt} onDone={handleRescheduled} onClose={() => setRescheduling(false)} />
      )}
      {canChange && !rescheduling && (
        <View style={styles.actionRow}>
          <TouchableOpacity
            style={[styles.rescheduleBtn, cancelling && { opacity: 0.6 }]}
            onPress={() => { if (notice) onRescheduled(null); setRescheduling(true); }}
            disabled={cancelling}
            activeOpacity={0.8}
            accessibilityRole="button"
          >
            <Ionicons name="calendar-outline" size={15} color={TEAL} />
            <Text style={styles.rescheduleBtnText}>Reschedule</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.cancelBtn, cancelling && { opacity: 0.6 }]}
            onPress={handleCancel}
            disabled={cancelling}
            activeOpacity={0.8}
            accessibilityRole="button"
            accessibilityState={{ disabled: cancelling }}
          >
            <Ionicons name="close-circle-outline" size={15} color={RED} />
            <Text style={styles.cancelBtnText}>{cancelling ? 'Cancelling…' : 'Cancel appointment'}</Text>
          </TouchableOpacity>
        </View>
      )}

      {onMessageDoctor && appt.doctor_system_id ? (
        <TouchableOpacity
          style={styles.messageBtn}
          onPress={() => onMessageDoctor(appt.doctor_system_id!, appt.doctor_name)}
          activeOpacity={0.8}
          accessibilityRole="button"
          accessibilityLabel={`Message ${appt.doctor_name}`}
        >
          <Ionicons name="chatbubble-ellipses-outline" size={15} color={SLATE} importantForAccessibility="no" />
          {/* The doctor's name is already on the card; the accessible name carries it. */}
          <Text style={styles.messageBtnText}>Message</Text>
        </TouchableOpacity>
      ) : null}

      {/* Expand toggle */}
      <TouchableOpacity
        style={styles.expandToggle}
        onPress={() => setExpanded(e => !e)}
        activeOpacity={0.7}
        accessibilityRole="button"
        accessibilityState={{ expanded }}
      >
        <Text style={styles.expandToggleText}>{expanded ? 'Show less' : 'Show details'}</Text>
        <Ionicons name={expanded ? 'chevron-up' : 'chevron-down'} size={13} color={TEAL} />
      </TouchableOpacity>
    </View>
  );
}

const PAGE_SIZE = 5;

export function PatientWebAppointmentStatus({ onMessageDoctor }: {
  /** Opens a private conversation with the appointment's doctor in Messages. */
  onMessageDoctor?: (doctorSystemId: string, doctorName: string) => void;
} = {}) {
  const { appointments, loading, error, refetch } = useAppointments();
  const [filter, setFilter] = useState<FilterKey>('ALL');
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE);
  // Kept here, not in the card, so it survives the list refresh and a card leaving the filter.
  const [rescheduledId, setRescheduledId] = useState<number | null>(null);

  useEffect(() => { setVisibleCount(PAGE_SIZE); }, [filter]);

  const now = Date.now();
  const sorted   = [...(filter === 'ALL' ? appointments : appointments.filter(a => a.status === filter))]
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
  const noticeOnCard = rescheduledId != null && visible.some(a => a.appointment_id === rescheduledId);

  const total      = appointments.length;
  const upcoming   = appointments.filter(isUpcomingAppointment).length;
  const completed  = appointments.filter(a => a.status === 'COMPLETED').length;
  const cancelled  = appointments.filter(a => a.status === 'CANCELLED' || a.status === 'NO_SHOW').length;

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
      {/* Page header */}
      <View style={styles.pageHeader}>
        <View>
          <Text style={styles.pageTitle} accessibilityRole="header">My Appointments</Text>
          <Text style={styles.pageSub}>Track and manage all your healthcare appointments</Text>
        </View>
        <TouchableOpacity style={styles.refreshBtn} onPress={refetch} activeOpacity={0.7} accessibilityRole="button" accessibilityLabel="Refresh appointments">
          <Ionicons name="refresh-outline" size={16} color={TEAL} />
          <Text style={styles.refreshBtnText}>Refresh</Text>
        </TouchableOpacity>
      </View>

      {/* Stat cards */}
      <View style={styles.statsRow}>
        <StatCard value={total}     label="Total"     color={INDIGO} icon="calendar-outline"           />
        <StatCard value={upcoming}  label="Upcoming"  color={TEAL}   icon="time-outline"               />
        <StatCard value={completed} label="Completed" color={GREEN}  icon="checkmark-circle-outline"   />
        <StatCard value={cancelled} label="Cancelled" color={RED}    icon="close-circle-outline"       />
      </View>

      {/* Filter tabs */}
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.filterScroll} contentContainerStyle={styles.filterRow}>
        {FILTERS.map(f => {
          const on = filter === f.key;
          return (
            <TouchableOpacity
              key={f.key}
              style={[styles.filterTab, on && styles.filterTabActive]}
              onPress={() => setFilter(f.key)}
              activeOpacity={0.7}
              accessibilityRole="tab"
              accessibilityState={{ selected: on }}
            >
              <Text style={[styles.filterTabText, on && styles.filterTabTextActive]}>{f.label}</Text>
              {on && f.key !== 'ALL' && (
                <View style={styles.filterCount}>
                  <Text style={styles.filterCountText}>
                    {appointments.filter(a => a.status === f.key).length}
                  </Text>
                </View>
              )}
            </TouchableOpacity>
          );
        })}
      </ScrollView>

      {/* Reschedule confirmation when the moved card is not on screen (filtered out or paged away) */}
      {rescheduledId != null && !noticeOnCard && !loading && (
        <View style={[styles.noticeBox, { marginBottom: 16 }]} accessibilityLiveRegion="polite" aria-live="polite">
          <Ionicons name="checkmark-circle-outline" size={14} color={GREEN} />
          <Text style={styles.noticeText}>{RESCHEDULED_NOTICE}</Text>
        </View>
      )}

      {/* Content */}
      {loading ? (
        <View style={styles.centerState}>
          <Ionicons name="hourglass-outline" size={40} color={BORDER} />
          <Text style={styles.centerStateText}>Loading appointments…</Text>
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
          <Ionicons name="calendar-outline" size={48} color={BORDER} />
          <Text style={styles.centerStateText}>
            {filter === 'ALL' ? 'No appointments found.' : `No ${FILTERS.find(f => f.key === filter)?.label.toLowerCase()} appointments.`}
          </Text>
        </View>
      ) : (
        <View style={styles.cardList}>
          {visible.map(appt => (
            <AppointmentCard
              key={appt.appointment_id}
              appt={appt}
              notice={appt.appointment_id === rescheduledId ? RESCHEDULED_NOTICE : ''}
              onRescheduled={setRescheduledId}
              onMessageDoctor={onMessageDoctor}
            />
          ))}
          {hasMore && (
            <TouchableOpacity
              style={styles.showMoreBtn}
              onPress={() => setVisibleCount(c => c + PAGE_SIZE)}
              activeOpacity={0.8}
            >
              <Ionicons name="chevron-down-circle-outline" size={18} color={TEAL} />
              <Text style={styles.showMoreText}>Show {remaining} more appointment{remaining !== 1 ? 's' : ''}</Text>
            </TouchableOpacity>
          )}
        </View>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen:        { flex: 1, backgroundColor: BG },
  scrollContent: { padding: 32, maxWidth: 760, alignSelf: 'center', width: '100%', paddingBottom: 48 },

  pageHeader:     { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 24 },
  pageTitle:      { fontSize: 22, fontWeight: '800', color: TEXT },
  pageSub:        { fontSize: 13, color: GRAY, marginTop: 3 },
  refreshBtn:     { flexDirection: 'row', alignItems: 'center', gap: 6, paddingHorizontal: 14, paddingVertical: 8, borderRadius: 10, borderWidth: 1.5, borderColor: TEAL, backgroundColor: '#E6F4F1' },
  refreshBtnText: { fontSize: 13, fontWeight: '600', color: TEAL },

  statsRow: { flexDirection: 'row', gap: 12, marginBottom: 24 },
  statCard: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 12, backgroundColor: CARD, borderRadius: 14, padding: 16, borderLeftWidth: 4, shadowColor: '#000', shadowOpacity: 0.04, shadowRadius: 8, elevation: 2 } as any,
  statIconBg:  { width: 36, height: 36, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  statValue:   { fontSize: 22, fontWeight: '800' },
  statLabel:   { fontSize: 11, color: GRAY, fontWeight: '500', marginTop: 1 },

  filterScroll: { marginBottom: 20 },
  filterRow:    { flexDirection: 'row', gap: 8, paddingBottom: 4 },
  filterTab:    { flexDirection: 'row', alignItems: 'center', gap: 6, paddingHorizontal: 16, paddingVertical: 8, borderRadius: 20, borderWidth: 1.5, borderColor: BORDER, backgroundColor: CARD },
  filterTabActive: { borderColor: TEAL, backgroundColor: '#E6F4F1' },
  filterTabText:   { fontSize: 13, fontWeight: '500', color: GRAY },
  filterTabTextActive: { color: TEAL, fontWeight: '700' },
  filterCount:     { backgroundColor: TEAL, borderRadius: 10, paddingHorizontal: 6, paddingVertical: 1 },
  filterCountText: { fontSize: 10, fontWeight: '700', color: WHITE },

  cardList: { gap: 14 },
  showMoreBtn:  { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8, paddingVertical: 14, borderRadius: 12, borderWidth: 1.5, borderColor: TEAL, backgroundColor: '#E6F4F1' },
  showMoreText: { fontSize: 14, fontWeight: '700', color: TEAL },

  card: { backgroundColor: CARD, borderRadius: 16, padding: 18, borderWidth: 1, borderColor: BORDER, shadowColor: '#000', shadowOpacity: 0.04, shadowRadius: 10, elevation: 2 } as any,

  cardHeader:     { flexDirection: 'row', alignItems: 'center', gap: 12, marginBottom: 12 },
  typeIconBg:     { width: 40, height: 40, borderRadius: 12, alignItems: 'center', justifyContent: 'center' },
  cardHeaderText: { flex: 1 },
  doctorName:     { fontSize: 15, fontWeight: '700', color: TEXT },
  doctorSpec:     { fontSize: 12, color: GRAY, marginTop: 2 },
  avatar:         { width: 40, height: 40, borderRadius: 20 },
  avatarFallback: { backgroundColor: '#E6F4F1', alignItems: 'center', justifyContent: 'center' },
  avatarInitial:  { fontSize: 16, fontWeight: '700', color: TEAL },

  metaRow:   { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: 6, marginBottom: 12 },
  metaItem:  { flexDirection: 'row', alignItems: 'center', gap: 4 },
  metaText:  { fontSize: 12, color: GRAY },
  metaDot:   { width: 3, height: 3, borderRadius: 2, backgroundColor: BORDER },

  badgeRow:  { flexDirection: 'row', gap: 8, marginBottom: 12 },
  badge:     { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 10, paddingVertical: 4, borderRadius: 20 },
  badgeText: { fontSize: 11, fontWeight: '700' },

  reasonBox:  { flexDirection: 'row', alignItems: 'flex-start', gap: 8, backgroundColor: BG, borderRadius: 10, padding: 12, marginBottom: 8 },
  reasonText: { flex: 1, fontSize: 13, color: TEXT, lineHeight: 18 },

  expandedSection: { gap: 10, marginBottom: 8 },
  notesBox:        { backgroundColor: '#FFFBEB', borderRadius: 10, padding: 12 },
  notesLabel:      { fontSize: 11, fontWeight: '700', color: AMBER, marginBottom: 4, textTransform: 'uppercase', letterSpacing: 0.5 },
  notesText:       { fontSize: 13, color: TEXT, lineHeight: 18 },
  videoBtn:        { flexDirection: 'row', alignItems: 'center', gap: 8, backgroundColor: '#EEF2FF', borderRadius: 10, padding: 12, borderWidth: 1, borderColor: '#C7D2FE' },
  videoBtnText:    { flex: 1, fontSize: 13, fontWeight: '600', color: INDIGO },

  actionRow:        { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 8 },
  rescheduleBtn:    { flex: 1, minWidth: 160, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6, paddingVertical: 10, borderRadius: 10, borderWidth: 1.5, borderColor: TEAL, backgroundColor: '#E6F4F1' },
  rescheduleBtnText:{ fontSize: 13, fontWeight: '600', color: TEAL },
  cancelBtn:        { flex: 1, minWidth: 160, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6, paddingVertical: 10, borderRadius: 10, borderWidth: 1.5, borderColor: RED, backgroundColor: '#FEF2F2' },
  cancelBtnText:    { fontSize: 13, fontWeight: '600', color: RED },

  reschedulePanel:      { borderWidth: 1, borderColor: BORDER, borderRadius: 12, padding: 14, gap: 12, marginBottom: 8, backgroundColor: BG },
  rescheduleTitle:      { fontSize: 14, fontWeight: '700', color: TEXT },
  reschedulePickers:    { flexDirection: 'row', flexWrap: 'wrap', gap: 14 },
  reschedulePickerCol:  { flex: 1, minWidth: 260, backgroundColor: CARD, borderRadius: 12, borderWidth: 1, borderColor: BORDER, padding: 14 },
  rescheduleActions:    { flexDirection: 'row', flexWrap: 'wrap', gap: 8, justifyContent: 'flex-end' },
  rescheduleCancelBtn:  { paddingHorizontal: 16, paddingVertical: 10, borderRadius: 10, borderWidth: 1.5, borderColor: BORDER, backgroundColor: CARD },
  rescheduleCancelText: { fontSize: 13, fontWeight: '600', color: GRAY },
  rescheduleSaveBtn:    { paddingHorizontal: 16, paddingVertical: 10, borderRadius: 10, backgroundColor: TEAL },
  rescheduleSaveText:   { fontSize: 13, fontWeight: '700', color: WHITE },
  inlineError:          { flexDirection: 'row', alignItems: 'center', gap: 6, backgroundColor: '#FEF2F2', borderRadius: 8, padding: 10 },
  inlineErrorText:      { flex: 1, fontSize: 12, color: RED },
  noticeBox:            { flexDirection: 'row', alignItems: 'center', gap: 6, backgroundColor: '#ECFDF5', borderRadius: 8, padding: 10, marginBottom: 8 },
  noticeText:           { flex: 1, fontSize: 12, color: '#047857' },
  messageBtn:       { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6, paddingVertical: 10, borderRadius: 10, borderWidth: 1.5, borderColor: BORDER, backgroundColor: CARD, marginBottom: 8 },
  messageBtnText:   { fontSize: 13, fontWeight: '600', color: SLATE },
  expandToggle:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 4, paddingTop: 10, borderTopWidth: 1, borderTopColor: BORDER, marginTop: 4 },
  expandToggleText: { fontSize: 12, fontWeight: '600', color: TEAL },

  centerState:     { alignItems: 'center', justifyContent: 'center', paddingVertical: 64, gap: 12 },
  centerStateText: { fontSize: 14, color: GRAY, textAlign: 'center' },
  retryBtn:        { backgroundColor: TEAL, borderRadius: 10, paddingHorizontal: 20, paddingVertical: 10, marginTop: 4 },
  retryBtnText:    { fontSize: 13, fontWeight: '700', color: WHITE },
});

export default PatientWebAppointmentStatus;
