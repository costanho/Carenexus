import { Ionicons } from '@expo/vector-icons';
import { useEffect, useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { api } from '@/lib/auth/auth.interceptor';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const PURPLE = '#7C3AED';
const GREEN  = '#10B981';
const RED    = '#EF4444';

type ApptStatus = 'SCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW';
type ApptType   = 'IN_PERSON' | 'VIDEO' | 'PHONE';

interface Appointment {
  appointmentId:          number;
  patientSystemId:        string | null;
  patientName:            string | null;
  doctorSystemId:         string | null;
  scheduledAt:            string;
  durationMinutes:        number;
  type:                   ApptType;
  status:                 ApptStatus;
  reasonForVisit:         string | null;
  videoConsultationLink:  string | null;
  notes:                  string | null;
}

interface ActivityResponse {
  appointments:  Appointment[];
  consultations: unknown[];
}

function statusColor(s: ApptStatus) {
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

function ApptCard({ appt }: { appt: Appointment }) {
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
          <Text style={[styles.badgeText, { color: text }]}>{appt.status.replace('_', ' ')}</Text>
        </View>
      </View>

      <View style={styles.detailRow}>
        <View style={styles.detail}>
          <Ionicons name="calendar-outline" size={14} color={GRAY} />
          <Text style={styles.detailText}>{formatDate(appt.scheduledAt)}</Text>
        </View>
        <View style={styles.detail}>
          <Ionicons name="time-outline" size={14} color={GRAY} />
          <Text style={styles.detailText}>{formatTime(appt.scheduledAt)} · {appt.durationMinutes} min</Text>
        </View>
        <View style={styles.detail}>
          <Ionicons name={isVideo ? 'videocam-outline' : 'business-outline'} size={14} color={isVideo ? PURPLE : GRAY} />
          <Text style={[styles.detailText, isVideo && { color: PURPLE }]}>{isVideo ? 'Video Call' : 'In Person'}</Text>
        </View>
        {appt.doctorSystemId && (
          <View style={styles.detail}>
            <Ionicons name="person-outline" size={14} color={GRAY} />
            <Text style={styles.detailText}>{appt.doctorSystemId}</Text>
          </View>
        )}
      </View>

      {appt.notes && (
        <View style={styles.notes}>
          <Text style={styles.notesText}>{appt.notes}</Text>
        </View>
      )}

      {isUpcoming && isVideo && appt.videoConsultationLink && (
        <TouchableOpacity style={styles.joinBtn} activeOpacity={0.8}>
          <Ionicons name="videocam-outline" size={15} color={WHITE} />
          <Text style={styles.joinBtnText}>Join Video Call</Text>
        </TouchableOpacity>
      )}
    </View>
  );
}

type Filter = 'all' | 'SCHEDULED' | 'COMPLETED' | 'CANCELLED';

export default function PatientProxyAppointments() {
  const [appointments, setAppointments] = useState<Appointment[]>([]);
  const [loading, setLoading]           = useState(true);
  const [error,   setError]             = useState<string | null>(null);
  const [filter,  setFilter]            = useState<Filter>('all');

  useEffect(() => {
    setLoading(true);
    api.get<ActivityResponse>('/api/caregiver/dependents/activity')
      .then(({ data }) => {
        const sorted = [...(data.appointments ?? [])].sort(
          (a, b) => new Date(b.scheduledAt).getTime() - new Date(a.scheduledAt).getTime()
        );
        setAppointments(sorted);
        setError(null);
      })
      .catch(() => setError('Failed to load appointments.'))
      .finally(() => setLoading(false));
  }, []);

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
          <Text style={styles.pageTitle}>Appointments</Text>
          <Text style={styles.pageSub}>Manage appointments for your linked patients</Text>
        </View>
        <TouchableOpacity style={styles.primaryBtn} activeOpacity={0.8}>
          <Ionicons name="add" size={16} color={WHITE} />
          <Text style={styles.primaryBtnText}>Schedule Appointment</Text>
        </TouchableOpacity>
      </View>

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
          >
            <Text style={[styles.filterText, filter === f.key && styles.filterTextActive]}>{f.label}</Text>
          </TouchableOpacity>
        ))}
      </View>

      {/* List */}
      {loading ? (
        <ActivityIndicator size="large" color={PURPLE} style={{ marginTop: 60 }} />
      ) : error ? (
        <View style={styles.empty}>
          <Ionicons name="alert-circle-outline" size={40} color={RED} />
          <Text style={styles.emptyText}>{error}</Text>
        </View>
      ) : visible.length === 0 ? (
        <View style={styles.empty}>
          <Ionicons name="calendar-outline" size={40} color={BORDER} />
          <Text style={styles.emptyText}>No appointments found</Text>
        </View>
      ) : (
        visible.map(a => <ApptCard key={a.appointmentId} appt={a} />)
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

  primaryBtn:     { flexDirection: 'row', alignItems: 'center', gap: 6, backgroundColor: PURPLE, borderRadius: 10, paddingHorizontal: 16, paddingVertical: 10 },
  primaryBtnText: { fontSize: 13, color: WHITE, fontWeight: '600' },

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

  empty:     { alignItems: 'center', paddingVertical: 60, gap: 12 },
  emptyText: { fontSize: 14, color: GRAY },
});
