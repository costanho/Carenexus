import { Ionicons } from '@expo/vector-icons';
import { useEffect, useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { api } from '@/lib/auth/auth.interceptor';
import { useCaregiverDependents, type CaregiverDependent } from '@/hooks/use-caregiver-dependents';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const PURPLE = '#7C3AED';
const GREEN  = '#10B981';
const ORANGE = '#F59E0B';
const RED    = '#EF4444';

type ApptStatus = 'SCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW';
type ApptType   = 'IN_PERSON' | 'VIDEO';

interface Appointment {
  appointment_id:          number;
  patient_id:              number;
  doctor_id:               number;
  scheduled_at:            string;
  duration_minutes:        number;
  type:                    ApptType;
  status:                  ApptStatus;
  reason_for_visit:        string;
  video_consultation_link: string | null;
  notes:                   string | null;
  doctor_name?:            string;
  patient_name?:           string;
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

function ApptCard({ appt, depMap }: { appt: Appointment; depMap: Map<number, CaregiverDependent> }) {
  const { bg, text } = statusColor(appt.status);
  const dep = depMap.get(appt.patient_id);
  const patientName = dep?.patient_name ?? appt.patient_name ?? `Patient #${appt.patient_id}`;
  const isVideo = appt.type === 'VIDEO';
  const isUpcoming = appt.status === 'SCHEDULED';

  return (
    <View style={[styles.card, isUpcoming && styles.cardUpcoming]}>
      {/* Header row */}
      <View style={styles.cardHead}>
        <View style={{ flex: 1 }}>
          <Text style={styles.cardTitle}>{appt.reason_for_visit}</Text>
          <Text style={styles.cardSub}>{patientName}</Text>
        </View>
        <View style={[styles.badge, { backgroundColor: bg }]}>
          <Text style={[styles.badgeText, { color: text }]}>{appt.status.replace('_', ' ')}</Text>
        </View>
      </View>

      {/* Details grid */}
      <View style={styles.detailRow}>
        <View style={styles.detail}>
          <Ionicons name="calendar-outline" size={14} color={GRAY} />
          <Text style={styles.detailText}>{formatDate(appt.scheduled_at)}</Text>
        </View>
        <View style={styles.detail}>
          <Ionicons name="time-outline" size={14} color={GRAY} />
          <Text style={styles.detailText}>{formatTime(appt.scheduled_at)} · {appt.duration_minutes} min</Text>
        </View>
        <View style={styles.detail}>
          <Ionicons name={isVideo ? 'videocam-outline' : 'business-outline'} size={14} color={isVideo ? PURPLE : GRAY} />
          <Text style={[styles.detailText, isVideo && { color: PURPLE }]}>{isVideo ? 'Video Call' : 'In Person'}</Text>
        </View>
        {appt.doctor_name && (
          <View style={styles.detail}>
            <Ionicons name="person-outline" size={14} color={GRAY} />
            <Text style={styles.detailText}>{appt.doctor_name}</Text>
          </View>
        )}
      </View>

      {appt.notes && (
        <View style={styles.notes}>
          <Text style={styles.notesText}>{appt.notes}</Text>
        </View>
      )}

      {isUpcoming && isVideo && appt.video_consultation_link && (
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
  const { dependents, loading: depsLoading } = useCaregiverDependents();
  const [appointments, setAppointments] = useState<Appointment[]>([]);
  const [loading, setLoading]           = useState(false);
  const [filter,  setFilter]            = useState<Filter>('all');

  const depMap = new Map(dependents.map(d => [d.patient_id, d]));

  useEffect(() => {
    if (dependents.length === 0) return;
    setLoading(true);
    Promise.all(
      dependents.map(d =>
        api.get<Appointment[]>(`/api/appointments?patient_id=${d.patient_id}`)
           .then(r => r.data)
      )
    )
      .then(results => {
        const all = results.flat().sort(
          (a, b) => new Date(b.scheduled_at).getTime() - new Date(a.scheduled_at).getTime()
        );
        setAppointments(all);
      })
      .finally(() => setLoading(false));
  }, [dependents.length]);

  const filters: { key: Filter; label: string }[] = [
    { key: 'all',       label: 'All' },
    { key: 'SCHEDULED', label: 'Upcoming' },
    { key: 'COMPLETED', label: 'Completed' },
    { key: 'CANCELLED', label: 'Cancelled' },
  ];

  const visible = filter === 'all' ? appointments : appointments.filter(a => a.status === filter);
  const upcoming   = appointments.filter(a => a.status === 'SCHEDULED').length;
  const completed  = appointments.filter(a => a.status === 'COMPLETED').length;

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
      {depsLoading || loading ? (
        <ActivityIndicator size="large" color={PURPLE} style={{ marginTop: 60 }} />
      ) : visible.length === 0 ? (
        <View style={styles.empty}>
          <Ionicons name="calendar-outline" size={40} color={BORDER} />
          <Text style={styles.emptyText}>No appointments found</Text>
        </View>
      ) : (
        visible.map(a => <ApptCard key={a.appointment_id} appt={a} depMap={depMap} />)
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
