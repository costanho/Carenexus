import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import { Linking, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useCaregiverActivity, type CaregiverAppointment } from '@/hooks/use-caregiver-dependents';
import { useDoctors } from '@/hooks/use-doctors';
import { showMessage } from '@/lib/ui/dialogs';
import { EmptyState, ErrorState, LoadingState } from './proxy-states';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const PURPLE = '#7C3AED';
const GREEN  = '#10B981';
const RED    = '#EF4444';

type Appointment = CaregiverAppointment;

function statusColor(s: string) {
  if (s === 'SCHEDULED')  return { bg: '#EDE9FE', text: PURPLE };
  if (s === 'COMPLETED')  return { bg: '#D1FAE5', text: GREEN };
  if (s === 'CANCELLED')  return { bg: '#FEE2E2', text: RED };
  return { bg: '#F3F4F6', text: GRAY };
}

function formatDate(iso: string) {
  const d = new Date(iso);
  return d.toLocaleDateString('en-ZW', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
}
function formatTime(iso: string) {
  return new Date(iso).toLocaleTimeString('en-ZW', { hour: '2-digit', minute: '2-digit' });
}

async function openVideoLink(url: string) {
  try {
    const supported = await Linking.canOpenURL(url);
    if (!supported) throw new Error('unsupported');
    await Linking.openURL(url);
  } catch {
    showMessage('Could not open video link', 'The video link for this appointment could not be opened. Please contact the clinic.');
  }
}

function ApptCard({ appt, doctorName }: { appt: Appointment; doctorName: string | null }) {
  const { bg, text } = statusColor(appt.status);
  const isVideo    = appt.type === 'VIDEO';
  const isUpcoming = appt.status === 'SCHEDULED';

  return (
    <View style={[styles.card, isUpcoming && styles.cardUpcoming]}>
      <View style={styles.cardHead}>
        <View style={{ flex: 1 }}>
          <Text style={styles.cardTitle}>{appt.reasonForVisit ?? 'Appointment'}</Text>
          <Text style={styles.cardSub}>{appt.patientName ?? appt.patientSystemId ?? '—'}</Text>
        </View>
        <View style={[styles.badge, { backgroundColor: bg }]}>
          <Text style={[styles.badgeText, { color: text }]}>{appt.status.replace(/_/g, ' ')}</Text>
        </View>
      </View>

      <View style={styles.detailRow}>
        <View style={styles.detail}>
          <Ionicons name="calendar-outline" size={14} color={GRAY} />
          <Text style={styles.detailText}>{formatDate(appt.scheduledAt)}</Text>
        </View>
        <View style={styles.detail}>
          <Ionicons name="time-outline" size={14} color={GRAY} />
          <Text style={styles.detailText}>
            {formatTime(appt.scheduledAt)}{appt.durationMinutes ? ` · ${appt.durationMinutes} min` : ''}
          </Text>
        </View>
        <View style={styles.detail}>
          <Ionicons name={isVideo ? 'videocam-outline' : appt.type === 'PHONE' ? 'call-outline' : 'business-outline'} size={14} color={isVideo ? PURPLE : GRAY} />
          <Text style={[styles.detailText, isVideo && { color: PURPLE }]}>
            {isVideo ? 'Video Call' : appt.type === 'PHONE' ? 'Phone Call' : 'In Person'}
          </Text>
        </View>
        {doctorName && (
          <View style={styles.detail}>
            <Ionicons name="person-outline" size={14} color={GRAY} />
            <Text style={styles.detailText}>{doctorName}</Text>
          </View>
        )}
      </View>

      {appt.notes && (
        <View style={styles.notes}>
          <Text style={styles.notesText}>{appt.notes}</Text>
        </View>
      )}

      {isUpcoming && isVideo && appt.videoConsultationLink && (
        <TouchableOpacity
          style={styles.joinBtn}
          activeOpacity={0.8}
          onPress={() => openVideoLink(appt.videoConsultationLink!)}
          accessibilityRole="link"
          accessibilityLabel="Open video call link"
          accessibilityHint="Opens the video link provided by the clinic"
        >
          <Ionicons name="videocam-outline" size={15} color={WHITE} importantForAccessibility="no" />
          <Text style={styles.joinBtnText}>Open Video Link</Text>
        </TouchableOpacity>
      )}
    </View>
  );
}

type Filter = 'all' | 'SCHEDULED' | 'COMPLETED' | 'CANCELLED';

export default function PatientProxyAppointments() {
  const { appointments: raw, loading, error, refetch } = useCaregiverActivity();
  const { doctors } = useDoctors();
  const [filter,  setFilter] = useState<Filter>('all');

  const appointments = useMemo(
    () => [...raw].sort((a, b) => new Date(b.scheduledAt).getTime() - new Date(a.scheduledAt).getTime()),
    [raw],
  );
  const doctorNames = useMemo(
    () => new Map(doctors.map(d => [d.doctor_system_id, d.full_name])),
    [doctors],
  );

  const filters: { key: Filter; label: string }[] = [
    { key: 'all',       label: 'All' },
    { key: 'SCHEDULED', label: 'Upcoming' },
    { key: 'COMPLETED', label: 'Completed' },
    { key: 'CANCELLED', label: 'Cancelled' },
  ];

  const visible   = filter === 'all' ? appointments : appointments.filter(a => a.status === filter);
  const upcoming  = appointments.filter(a => a.status === 'SCHEDULED').length;
  const completed = appointments.filter(a => a.status === 'COMPLETED').length;

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>
      {/* Page header */}
      <View style={styles.pageHeader}>
        <View>
          <Text style={styles.pageTitle} accessibilityRole="header">Appointments</Text>
          <Text style={styles.pageSub}>Appointments for your linked patients</Text>
        </View>
      </View>

      {loading ? (
        <LoadingState label="Loading appointments" />
      ) : error ? (
        <ErrorState message={error} onRetry={refetch} />
      ) : (
        <>
          {/* Stat pills */}
          <View style={styles.statsRow}>
            {[
              { label: 'Total',    value: appointments.length, bg: '#F5F3FF', color: PURPLE },
              { label: 'Upcoming', value: upcoming,            bg: '#EDE9FE', color: PURPLE },
              { label: 'Done',     value: completed,           bg: '#D1FAE5', color: GREEN  },
            ].map(s => (
              <View key={s.label} style={[styles.statPill, { backgroundColor: s.bg }]}>
                <Text style={[styles.statVal, { color: s.color }]}>{s.value}</Text>
                <Text style={[styles.statLabel, { color: s.color }]}>{s.label}</Text>
              </View>
            ))}
          </View>

          {/* Filter tabs */}
          <View style={styles.filterRow}>
            {filters.map(f => (
              <TouchableOpacity
                key={f.key}
                style={[styles.filterTab, filter === f.key && styles.filterTabActive]}
                activeOpacity={0.7}
                onPress={() => setFilter(f.key)}
                accessibilityRole="button"
                accessibilityState={{ selected: filter === f.key }}
              >
                <Text style={[styles.filterText, filter === f.key && styles.filterTextActive]}>{f.label}</Text>
              </TouchableOpacity>
            ))}
          </View>

          {visible.length === 0 ? (
            <EmptyState icon="calendar-outline" title="No appointments found" />
          ) : (
            visible.map(a => (
              <ApptCard
                key={a.appointmentId}
                appt={a}
                doctorName={a.doctorSystemId ? doctorNames.get(a.doctorSystemId) ?? null : null}
              />
            ))
          )}
        </>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll:     { flex: 1, backgroundColor: BG },
  container:  { padding: 24, paddingBottom: 48, gap: 16 },

  pageHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 4 },
  pageTitle:  { fontSize: 22, fontWeight: '700', color: TEXT },
  pageSub:    { fontSize: 13, color: GRAY, marginTop: 3 },


  statsRow: { flexDirection: 'row', gap: 12 },
  statPill: { borderRadius: 12, paddingHorizontal: 20, paddingVertical: 12, alignItems: 'center' },
  statVal:  { fontSize: 24, fontWeight: '700' },
  statLabel:{ fontSize: 12, fontWeight: '500', marginTop: 2 },

  filterRow:        { flexDirection: 'row', gap: 8, backgroundColor: WHITE, borderRadius: 10, padding: 4, borderWidth: 1, borderColor: BORDER, alignSelf: 'flex-start' },
  filterTab:        { paddingHorizontal: 16, paddingVertical: 7, borderRadius: 8 },
  filterTabActive:  { backgroundColor: PURPLE },
  filterText:       { fontSize: 13, color: GRAY, fontWeight: '500' },
  filterTextActive: { color: WHITE, fontWeight: '600' },

  card:         { backgroundColor: WHITE, borderRadius: 14, borderWidth: 1, borderColor: BORDER, padding: 18, gap: 12 },
  cardUpcoming: { borderLeftWidth: 3, borderLeftColor: PURPLE },
  cardHead:     { flexDirection: 'row', alignItems: 'flex-start', gap: 12 },
  cardTitle:    { fontSize: 15, fontWeight: '700', color: TEXT },
  cardSub:      { fontSize: 12, color: GRAY, marginTop: 3 },

  badge:      { borderRadius: 20, paddingHorizontal: 10, paddingVertical: 3 },
  badgeText:  { fontSize: 11, fontWeight: '600' },

  detailRow:  { flexDirection: 'row', flexWrap: 'wrap', gap: 12 },
  detail:     { flexDirection: 'row', alignItems: 'center', gap: 5 },
  detailText: { fontSize: 13, color: GRAY },

  notes:     { backgroundColor: BG, borderRadius: 8, padding: 10 },
  notesText: { fontSize: 12, color: GRAY, lineHeight: 18 },

  joinBtn:     { flexDirection: 'row', alignItems: 'center', gap: 6, backgroundColor: PURPLE, borderRadius: 10, paddingHorizontal: 16, paddingVertical: 10, alignSelf: 'flex-start' },
  joinBtnText: { fontSize: 13, color: WHITE, fontWeight: '600' },

});
