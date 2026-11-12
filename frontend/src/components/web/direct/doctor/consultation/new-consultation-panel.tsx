import { StyleSheet, Text, View } from 'react-native';
import {
  Button, colors, EmptyView, ErrorView, fontSize, LoadingView, NoAccessView, radius, SidePanel, space, StatusBadge,
} from '@/components/web/ui';
import {
  patientNameFallback, RECENT_APPOINTMENT_DAYS, useAppointmentsAwaitingConsultation,
} from '@/hooks/use-doctor-consultations';
import { appointmentTypeBadge, clinicDateTime } from './consultation-format';

export interface NewConsultationPanelProps {
  open:    boolean;
  onClose: () => void;
  /** Opens the editor on the chosen appointment. */
  onSelect: (appointmentId: number) => void;
  /** Only this patient's appointments (the list's patient filter). */
  patientSystemId?: string | null;
  /** Name lookup from the doctor's patient list. */
  nameOf: (patientSystemId: string | null | undefined) => string | null;
}

/**
 * Chooses the appointment to document. A consultation always belongs to an appointment that has started, so
 * the panel lists the doctor's started appointments of the last week that have no consultation yet.
 */
export function NewConsultationPanel({ open, onClose, onSelect, patientSystemId, nameOf }: NewConsultationPanelProps) {
  const awaiting = useAppointmentsAwaitingConsultation({ enabled: open, patientSystemId });

  let body;
  if (awaiting.loading) {
    body = <LoadingView label="Loading appointments…" />;
  } else if (awaiting.error) {
    body = awaiting.errorStatus === 403
      ? <NoAccessView message="You don't have access to this schedule." compact />
      : <ErrorView message={awaiting.error} onRetry={awaiting.refetch} compact />;
  } else if (awaiting.appointments.length === 0) {
    body = (
      <EmptyView
        icon="calendar-outline"
        title="No appointments are waiting for a consultation"
        sub={`Your appointments of the last ${RECENT_APPOINTMENT_DAYS} days appear here once they have started, until a consultation is opened for them.`}
      />
    );
  } else {
    body = (
      <View style={styles.list} role="list" aria-label="Appointments without a consultation">
        {awaiting.appointments.map(a => {
          const name = nameOf(a.patientSystemId) ?? a.patientName ?? patientNameFallback(a.patientSystemId);
          const type = appointmentTypeBadge(a.appointmentType);
          return (
            <View key={a.appointmentId} role="listitem" style={styles.row}>
              <View style={styles.rowText}>
                <Text style={styles.name}>{name}</Text>
                <Text style={styles.meta}>{clinicDateTime(a.scheduledAt)}</Text>
                {a.reasonForVisit ? <Text style={styles.meta} numberOfLines={2}>{a.reasonForVisit}</Text> : null}
                {type ? <StatusBadge label={type.label} tone="neutral" icon={type.icon} /> : null}
              </View>
              <Button
                label="Open"
                size="sm"
                onPress={() => onSelect(a.appointmentId)}
                accessibilityLabel={`Open a consultation for ${name}, ${clinicDateTime(a.scheduledAt)}`}
              />
            </View>
          );
        })}
        {awaiting.truncated ? (
          <Text style={styles.meta}>Only your most recent appointments were checked.</Text>
        ) : null}
      </View>
    );
  }

  return (
    <SidePanel open={open} title="New consultation" onClose={onClose}>
      <Text style={styles.intro}>
        Choose the appointment to document. You can start the consultation or write the notes as a draft on the next screen.
      </Text>
      {body}
    </SidePanel>
  );
}

const styles = StyleSheet.create({
  intro:   { fontSize: fontSize.md, color: colors.textMuted, lineHeight: 20 },
  list:    { gap: space.sm },
  row:     {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.md,
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, padding: space.md,
  },
  rowText: { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0, gap: space.xs },
  name:    { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  meta:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
