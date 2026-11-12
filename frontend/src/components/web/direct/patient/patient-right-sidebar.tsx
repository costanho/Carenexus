import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { appointmentCallJoin, isUpcomingAppointment, useAppointments, type Appointment } from '@/hooks/use-appointments';
import { CallJoinButton } from '@/components/web/call/call-join-button';
import { decorative, Icon, type IoniconsName } from '@/components/web/ui';
import { ariaLevel } from './a11y';
import { CallTimePending } from './call-time-pending';
import { clinicNowLocal, formatClinicDate, formatClinicTime, toClinicLocal } from '@/lib/time/clinic';

const TEAL      = '#0F766E';
const TEAL_BG   = '#E8F5F4';
const GRAY      = '#6B7280';
// Secondary text on the tinted card backgrounds: GRAY measures only 4.33:1 on the teal tint and 4.39:1 on GRAY_LIGHT.
const MUTED     = '#4B5563';
const GRAY_LIGHT = '#F3F4F6';
const BORDER    = '#E5E7EB';
const WHITE     = '#FFFFFF';
const RED       = '#B91C1C';
// orange-700: 5.18:1 under white text and 4.88:1 as text on its tint (orange-500 measured 2.8:1).
const ORANGE    = '#C2410C';
const AMBER     = '#B45309';
const GREEN     = '#047857';

/** Minutes since the epoch of a clinic wall-clock "YYYY-MM-DDTHH:mm:ss", read as UTC so no device zone applies. */
function wallClockMinutes(local: string): number {
  return Date.parse(`${local.slice(0, 19)}Z`) / 60000;
}

/** Whole clinic calendar days from `fromDate` to `toDate` ("YYYY-MM-DD"). */
function daysBetween(fromDate: string, toDate: string): number {
  return Math.round((Date.parse(`${toDate}T00:00:00Z`) - Date.parse(`${fromDate}T00:00:00Z`)) / 86_400_000);
}

// `scheduled_at` is clinic wall-clock time (no offset): it is compared with the clinic's clock, never the device's,
// and the day labels follow the clinic calendar rather than a rolling 24 hours.
function urgencyScheme(appt: Appointment): { color: string; bg: string; label: string } {
  const at = toClinicLocal(appt.scheduled_at);
  if (!at) return { color: GREEN, bg: '#F0FDF4', label: 'Upcoming' };
  const now = clinicNowLocal();
  const minutes = wallClockMinutes(at) - wallClockMinutes(now);
  const days = daysBetween(now.slice(0, 10), at.slice(0, 10));
  if (minutes <= 60)   return { color: RED,    bg: '#FEF2F2', label: 'Starting soon!' };
  if (days <= 0)       return { color: ORANGE, bg: '#FFF7ED', label: 'Today'          };
  if (days === 1)      return { color: ORANGE, bg: '#FFF7ED', label: 'Tomorrow'       };
  if (days <= 3)       return { color: AMBER,  bg: '#FFFBEB', label: 'In a few days'  };
  if (days <= 7)       return { color: TEAL,   bg: TEAL_BG,  label: 'This week'       };
  return                      { color: GREEN,  bg: '#F0FDF4', label: 'Upcoming'        };
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
          <Text style={{ color: MUTED, fontSize: 13 }} role="status">Loading…</Text>
        </View>
      ) : error ? (
        <View style={[styles.apptInner, { backgroundColor: '#FEF2F2', alignItems: 'center', gap: 8, paddingVertical: 20 }]}>
          <Icon name="alert-circle-outline" size={28} color={RED} />
          <Text style={{ color: RED, fontSize: 13, textAlign: 'center' }} role="alert">{error}</Text>
          <TouchableOpacity onPress={refetch} activeOpacity={0.7} accessibilityRole="button" accessibilityLabel="Retry loading appointments">
            <Text style={{ color: RED, fontSize: 13, fontWeight: '700', textDecorationLine: 'underline' }}>Retry</Text>
          </TouchableOpacity>
        </View>
      ) : !next ? (
        <View style={[styles.apptInner, { backgroundColor: GRAY_LIGHT, alignItems: 'center', gap: 8, paddingVertical: 24 }]}>
          <Icon name="calendar-outline" size={32} color={BORDER} />
          <Text style={{ color: MUTED, fontSize: 13, textAlign: 'center' }}>No upcoming appointments</Text>
          <TouchableOpacity onPress={() => onNavigate?.('book-appointment')} activeOpacity={0.7} accessibilityRole="button">
            <Text style={{ color: TEAL, fontSize: 12, fontWeight: '600', textAlign: 'center' }}>Book an appointment <Text {...decorative}>→</Text></Text>
          </TouchableOpacity>
        </View>
      ) : (
        <View style={[styles.apptInner, { backgroundColor: scheme.bg, borderColor: scheme.color + '40', borderWidth: 1 }]}>
          {/* Urgency badge */}
          <View style={[styles.urgencyBadge, { backgroundColor: scheme.color }]}>
            <Icon name="time-outline" size={11} color={WHITE} />
            <Text style={styles.urgencyText}>{scheme.label}</Text>
          </View>

          {/* Date & time */}
          <View style={styles.apptTopRow}>
            <View style={[styles.apptIconBg, { backgroundColor: WHITE, borderColor: scheme.color + '30', borderWidth: 1 }]} {...decorative}>
              <Icon name="calendar-outline" size={24} color={scheme.color} />
            </View>
            <View>
              <Text style={styles.apptDate}>{formatClinicDate(next.scheduled_at)}</Text>
              <Text style={[styles.apptTime, { color: scheme.color }]}>{formatClinicTime(next.scheduled_at)}</Text>
            </View>
          </View>

          {/* Details */}
          <Text style={styles.apptTitle}>{next.reason_for_visit ?? 'Appointment'}</Text>
          <Text style={styles.apptSub}>
            with {next.doctor_name}{next.doctor_specialization ? ` (${next.doctor_specialization})` : ''}
          </Text>

          <View style={styles.apptMeta}>
            <Icon name={TYPE_ICON[next.type] ?? 'calendar-outline'} size={15} color={MUTED} />
            <Text style={styles.apptMetaText}>{TYPE_LABEL[next.type] ?? next.type}</Text>
          </View>
          <View style={styles.apptMeta}>
            <Icon name="time-outline" size={15} color={MUTED} />
            <Text style={styles.apptMetaText}>{next.duration_minutes} minutes</Text>
          </View>
          {next.facility_name && (
            <View style={styles.apptMeta}>
              <Icon name="location-outline" size={15} color={MUTED} />
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
              <Icon name={TYPE_ICON[next.type] ?? 'calendar-outline'} size={16} color={WHITE} />
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
              <Icon name="chatbubble-ellipses-outline" size={15} color={GRAY} />
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
            <Icon name={item.icon} size={18} color={GRAY} />
            <Text style={styles.quickLabel}>{item.label}</Text>
            <Icon name="chevron-forward" size={16} color={GRAY} />
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
    // A complementary landmark beside the section, so screen-reader users can skip it or jump to it.
    <View style={styles.sidebar} role="complementary" aria-label="Upcoming appointment and quick access">
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
    color: MUTED,
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
    color: MUTED,
    marginBottom: 4,
  },
  apptMeta: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  apptMetaText: {
    fontSize: 13,
    color: MUTED,
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
