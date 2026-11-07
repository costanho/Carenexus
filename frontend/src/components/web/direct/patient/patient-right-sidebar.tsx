import { Ionicons } from '@expo/vector-icons';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { appointmentCallJoin, isUpcomingAppointment, useAppointments, type Appointment } from '@/hooks/use-appointments';
import { CallJoinButton } from '@/components/web/call/call-join-button';
import { ariaLevel } from './a11y';
import { CallTimePending } from './call-time-pending';

const TEAL      = '#0D9488';
const TEAL_BG   = '#E8F5F4';
const GRAY      = '#6B7280';
const GRAY_LIGHT = '#F3F4F6';
const BORDER    = '#E5E7EB';
const WHITE     = '#FFFFFF';
const RED       = '#EF4444';
const ORANGE    = '#F97316';
const AMBER     = '#F59E0B';
const GREEN     = '#10B981';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

// Urgency thresholds in milliseconds
const HOUR  = 60 * 60 * 1000;
const DAY   = 24 * HOUR;

function urgencyScheme(appt: Appointment): { color: string; bg: string; label: string } {
  const diff = new Date(appt.scheduled_at).getTime() - Date.now();
  if (diff <= HOUR)       return { color: RED,    bg: '#FEF2F2', label: 'Starting soon!' };
  if (diff <= DAY)        return { color: ORANGE, bg: '#FFF7ED', label: 'Tomorrow'       };
  if (diff <= 3 * DAY)    return { color: AMBER,  bg: '#FFFBEB', label: 'In a few days'  };
  if (diff <= 7 * DAY)    return { color: TEAL,   bg: TEAL_BG,  label: 'This week'       };
  return                         { color: GREEN,  bg: '#F0FDF4', label: 'Upcoming'        };
}

const TYPE_ICON: Record<string, IoniconsName> = {
  IN_PERSON: 'business-outline',
  VIDEO:     'videocam-outline',
  PHONE:     'call-outline',
};
const TYPE_LABEL: Record<string, string> = {
  IN_PERSON: 'In-Person Visit',
  VIDEO:     'Video Consultation',
  PHONE:     'Phone Call',
};

function formatApptDate(iso: string) {
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}
function formatApptTime(iso: string) {
  return new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', hour12: true }).toUpperCase();
}

// ── Upcoming Appointment ────────────────────────────────────────────

function UpcomingAppointment({ onNavigate, onMessageDoctor }: {
  onNavigate?: (key: string) => void;
  onMessageDoctor?: (doctorSystemId: string, doctorName: string) => void;
}) {
  const onViewAll = () => onNavigate?.('my-appointments');
  const { appointments, loading, error, refetch } = useAppointments();

  const next = appointments
    .filter(isUpcomingAppointment)
    .sort((a, b) => new Date(a.scheduled_at).getTime() - new Date(b.scheduled_at).getTime())[0] ?? null;

  const scheme = next ? urgencyScheme(next) : { color: TEAL, bg: TEAL_BG, label: '' };

  return (
    <View style={styles.card}>
      <View style={styles.cardHeader}>
        <Text style={styles.cardTitle} accessibilityRole="header" {...ariaLevel(2)}>Upcoming Appointment</Text>
        <TouchableOpacity onPress={onViewAll} activeOpacity={0.7} accessibilityRole="link">
          <Text style={[styles.link, { color: scheme.color }]}>View all</Text>
        </TouchableOpacity>
      </View>

      {loading ? (
        <View style={[styles.apptInner, { backgroundColor: TEAL_BG, alignItems: 'center', paddingVertical: 24 }]}>
          <Text style={{ color: GRAY, fontSize: 13 }}>Loading…</Text>
        </View>
      ) : error ? (
        <View style={[styles.apptInner, { backgroundColor: '#FEF2F2', alignItems: 'center', gap: 8, paddingVertical: 20 }]}>
          <Ionicons name="alert-circle-outline" size={28} color={RED} />
          <Text style={{ color: RED, fontSize: 13, textAlign: 'center' }}>{error}</Text>
          <TouchableOpacity onPress={refetch} activeOpacity={0.7} accessibilityRole="button" accessibilityLabel="Retry loading appointments">
            <Text style={{ color: RED, fontSize: 13, fontWeight: '700', textDecorationLine: 'underline' }}>Retry</Text>
          </TouchableOpacity>
        </View>
      ) : !next ? (
        <View style={[styles.apptInner, { backgroundColor: GRAY_LIGHT, alignItems: 'center', gap: 8, paddingVertical: 24 }]}>
          <Ionicons name="calendar-outline" size={32} color={BORDER} />
          <Text style={{ color: GRAY, fontSize: 13, textAlign: 'center' }}>No upcoming appointments</Text>
          <TouchableOpacity onPress={() => onNavigate?.('book-appointment')} activeOpacity={0.7} accessibilityRole="button">
            <Text style={{ color: TEAL, fontSize: 12, fontWeight: '600', textAlign: 'center' }}>Book an appointment →</Text>
          </TouchableOpacity>
        </View>
      ) : (
        <View style={[styles.apptInner, { backgroundColor: scheme.bg, borderColor: scheme.color + '40', borderWidth: 1 }]}>
          {/* Urgency badge */}
          <View style={[styles.urgencyBadge, { backgroundColor: scheme.color }]}>
            <Ionicons name="time-outline" size={11} color={WHITE} />
            <Text style={styles.urgencyText}>{scheme.label}</Text>
          </View>

          {/* Date & time */}
          <View style={styles.apptTopRow}>
            <View style={[styles.apptIconBg, { backgroundColor: WHITE, borderColor: scheme.color + '30', borderWidth: 1 }]}>
              <Ionicons name="calendar-outline" size={24} color={scheme.color} />
            </View>
            <View>
              <Text style={styles.apptDate}>{formatApptDate(next.scheduled_at)}</Text>
              <Text style={[styles.apptTime, { color: scheme.color }]}>{formatApptTime(next.scheduled_at)}</Text>
            </View>
          </View>

          {/* Details */}
          <Text style={styles.apptTitle}>{next.reason_for_visit ?? 'Appointment'}</Text>
          <Text style={styles.apptSub}>
            with {next.doctor_name}{next.doctor_specialization ? ` (${next.doctor_specialization})` : ''}
          </Text>

          <View style={styles.apptMeta}>
            <Ionicons name={TYPE_ICON[next.type] ?? 'calendar-outline'} size={15} color={GRAY} />
            <Text style={styles.apptMetaText}>{TYPE_LABEL[next.type] ?? next.type}</Text>
          </View>
          <View style={styles.apptMeta}>
            <Ionicons name="time-outline" size={15} color={GRAY} />
            <Text style={styles.apptMetaText}>{next.duration_minutes} minutes</Text>
          </View>
          {next.facility_name && (
            <View style={styles.apptMeta}>
              <Ionicons name="location-outline" size={15} color={GRAY} />
              <Text style={styles.apptMetaText}>{next.facility_name}</Text>
            </View>
          )}

          {/* Actions. A video call whose time still needs the clinic's confirmation shows a note, not a button. */}
          {next.call_status === 'NEEDS_TIME_CONFIRMATION' && appointmentCallJoin(next) ? (
            <CallTimePending style={{ marginTop: 8 }} />
          ) : appointmentCallJoin(next) ? (
            <CallJoinButton
              info={appointmentCallJoin(next)}
              withName={next.doctor_name}
              style={[styles.joinBtn, { backgroundColor: scheme.color }]}
              textStyle={styles.joinBtnText}
              icon="videocam"
              iconSize={16}
              iconColor={WHITE}
            />
          ) : (
            <TouchableOpacity
              style={[styles.joinBtn, { backgroundColor: scheme.color }]}
              onPress={() => onNavigate?.('my-appointments')}
              activeOpacity={0.85}
              accessibilityRole="button"
            >
              <Ionicons name={TYPE_ICON[next.type] ?? 'calendar-outline'} size={16} color={WHITE} />
              <Text style={styles.joinBtnText}>View Details</Text>
            </TouchableOpacity>
          )}
          <TouchableOpacity
            style={[styles.rescheduleBtn, { borderColor: scheme.color }]}
            onPress={() => onNavigate?.('my-appointments')}
            activeOpacity={0.7}
            accessibilityRole="button"
            accessibilityHint="Opens My Appointments, where you can reschedule or cancel"
          >
            <Text style={[styles.rescheduleBtnText, { color: scheme.color }]}>Reschedule or Cancel</Text>
          </TouchableOpacity>
          {onMessageDoctor && next.doctor_system_id ? (
            <TouchableOpacity
              style={styles.messageBtn}
              onPress={() => onMessageDoctor(next.doctor_system_id!, next.doctor_name)}
              activeOpacity={0.7}
              accessibilityRole="button"
              accessibilityLabel={`Message ${next.doctor_name}`}
            >
              <Ionicons name="chatbubble-ellipses-outline" size={15} color={GRAY} importantForAccessibility="no" />
              {/* The doctor's name is already on the card; the accessible name carries it. */}
              <Text style={styles.messageBtnText} numberOfLines={1}>Message</Text>
            </TouchableOpacity>
          ) : null}
        </View>
      )}
    </View>
  );
}

// ── Quick Access ────────────────────────────────────────────────────

type QuickItem = { key: string; label: string; icon: IoniconsName };

const quickItems: QuickItem[] = [
  { key: 'messages',        label: 'Message your care team', icon: 'chatbubbles-outline' },
  { key: 'medical-records', label: 'Medical Records', icon: 'folder-open-outline' },
  { key: 'lab-results',     label: 'Lab Results',     icon: 'flask-outline' },
  { key: 'imaging-results', label: 'Imaging Results', icon: 'image-outline' },
  { key: 'prescriptions',   label: 'Prescriptions',   icon: 'medkit-outline' },
];

function QuickAccess({ onNavigate }: { onNavigate?: (key: string) => void }) {
  return (
    <View style={styles.card}>
      <Text style={[styles.cardTitle, { marginBottom: 8 }]} accessibilityRole="header" {...ariaLevel(2)}>Quick Access</Text>
      {quickItems.map((item, i) => (
        <View key={item.key}>
          <TouchableOpacity
            style={styles.quickRow}
            activeOpacity={0.7}
            onPress={() => onNavigate?.(item.key)}
            accessibilityRole="button"
            accessibilityLabel={item.label}
          >
            <Ionicons name={item.icon} size={18} color={GRAY} />
            <Text style={styles.quickLabel}>{item.label}</Text>
            <Ionicons name="chevron-forward" size={16} color={GRAY} />
          </TouchableOpacity>
          {i < quickItems.length - 1 && <View style={styles.rowDivider} />}
        </View>
      ))}
    </View>
  );
}

// ── Main export ─────────────────────────────────────────────────────

export function PatientRightSidebar({ onNavigate, onMessageDoctor }: {
  onNavigate?: (key: string) => void;
  /** Opens a private conversation with this doctor in Messages. */
  onMessageDoctor?: (doctorSystemId: string, doctorName: string) => void;
}) {
  return (
    <View style={styles.sidebar}>
      <ScrollView
        showsVerticalScrollIndicator={false}
        contentContainerStyle={styles.scroll}
      >
        <UpcomingAppointment onNavigate={onNavigate} onMessageDoctor={onMessageDoctor} />
        <QuickAccess onNavigate={onNavigate} />
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  sidebar: {
    width: 300,
    backgroundColor: GRAY_LIGHT,
    borderLeftWidth: 1,
    borderLeftColor: BORDER,
    height: '100%',
  },
  scroll: {
    padding: 16,
    gap: 16,
  },

  // Card shell
  card: {
    backgroundColor: WHITE,
    borderRadius: 14,
    padding: 16,
    borderWidth: 1,
    borderColor: BORDER,
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  cardTitle: {
    fontSize: 15,
    fontWeight: '700',
    color: '#111827',
  },
  link: {
    fontSize: 13,
    color: TEAL,
    fontWeight: '500',
  },

  // Appointment
  apptInner: {
    borderRadius: 10,
    padding: 14,
    gap: 6,
  },
  urgencyBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    alignSelf: 'flex-start',
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 20,
    marginBottom: 4,
  },
  urgencyText: {
    fontSize: 10,
    fontWeight: '700',
    color: WHITE,
    textTransform: 'uppercase',
    letterSpacing: 0.4,
  },
  apptTopRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    marginBottom: 4,
  },
  apptIconBg: {
    width: 44,
    height: 44,
    borderRadius: 10,
    backgroundColor: WHITE,
    alignItems: 'center',
    justifyContent: 'center',
  },
  apptDate: {
    fontSize: 12,
    color: GRAY,
  },
  apptTime: {
    fontSize: 22,
    fontWeight: '700',
    color: '#111827',
  },
  apptTitle: {
    fontSize: 14,
    fontWeight: '700',
    color: '#111827',
  },
  apptSub: {
    fontSize: 13,
    color: GRAY,
    marginBottom: 4,
  },
  apptMeta: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  apptMetaText: {
    fontSize: 13,
    color: GRAY,
  },
  joinBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    backgroundColor: TEAL,
    borderRadius: 8,
    paddingVertical: 12,
    marginTop: 8,
  },
  joinBtnText: {
    color: WHITE,
    fontWeight: '600',
    fontSize: 14,
  },
  rescheduleBtn: {
    borderRadius: 8,
    borderWidth: 1,
    borderColor: TEAL,
    paddingVertical: 11,
    alignItems: 'center',
    marginTop: 8,
  },
  rescheduleBtnText: {
    color: TEAL,
    fontWeight: '500',
    fontSize: 14,
  },

  messageBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: BORDER,
    backgroundColor: WHITE,
    paddingVertical: 10,
    marginTop: 8,
  },
  messageBtnText: {
    color: '#374151',
    fontWeight: '500',
    fontSize: 13,
    flexShrink: 1,
  },

  rowDivider: {
    height: 1,
    backgroundColor: BORDER,
  },

  // Quick access
  quickRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 12,
    gap: 12,
  },
  quickLabel: {
    flex: 1,
    fontSize: 14,
    color: '#111827',
  },

});
