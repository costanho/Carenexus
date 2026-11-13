import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Button, Card, colors, fontSize, InfoRow, space, StatusBadge, statusTone,
} from '@/components/web/ui';
import type { ConsultationAppointment, ConsultationDto } from '@/hooks/use-doctor-consultations';
import {
  appointmentStatusText, appointmentTypeBadge, clinicDateTime, consultationStatusBadge,
} from './consultation-format';

export interface ConsultationHeaderProps {
  patientName:     string;
  patientSystemId: string;
  consultation:    ConsultationDto | null;
  appointment:     ConsultationAppointment | null;
  /** Opens the patient detail panel on its Overview. */
  onOpenPatient?:  () => void;
  /** Opens the patient detail panel on the History tab (earlier consultations, prescriptions, results, records). */
  onOpenHistory?:  () => void;
}

/** Who the consultation is for and which appointment it documents, with a link to the patient's details. */
export function ConsultationHeader({
  patientName, patientSystemId, consultation, appointment, onOpenPatient, onOpenHistory,
}: ConsultationHeaderProps) {
  const status = consultation ? consultationStatusBadge(consultation.status) : null;
  const type = appointmentTypeBadge(consultation?.appointmentType ?? appointment?.appointmentType);
  // The consultation carries the appointment's current status; the appointment row is the fallback.
  const appointmentStatus = consultation?.appointmentStatus ?? appointment?.appointmentStatus ?? null;
  const appointmentStatusLabel = appointmentStatusText(appointmentStatus);

  return (
    <Card style={styles.card}>
      <View style={styles.top}>
        <View style={styles.who}>
          <Text style={styles.name} accessibilityRole="header" {...ariaLevel(2)}>{patientName}</Text>
          {patientSystemId ? <Text style={styles.id}>Patient ID {patientSystemId}</Text> : null}
        </View>
        {onOpenPatient || onOpenHistory ? (
          <View style={styles.links}>
            {onOpenHistory ? (
              <Button
                label="Patient history"
                icon="time-outline"
                variant="secondary"
                size="sm"
                onPress={onOpenHistory}
                accessibilityLabel={`Patient history, ${patientName}`}
              />
            ) : null}
            {onOpenPatient ? (
              <Button
                label="View patient"
                icon="person-outline"
                variant="secondary"
                size="sm"
                onPress={onOpenPatient}
                accessibilityLabel={`View patient, ${patientName}`}
              />
            ) : null}
          </View>
        ) : null}
      </View>

      <View style={styles.badges}>
        {status ? <StatusBadge label={status.label} tone={status.tone} icon={status.icon} /> : null}
        {type ? <StatusBadge label={type.label} tone="neutral" icon={type.icon} /> : null}
        {appointmentStatusLabel ? (
          <StatusBadge label={`Appointment: ${appointmentStatusLabel}`} tone={statusTone('appointment', appointmentStatus)} />
        ) : null}
      </View>

      <View>
        {appointment?.scheduledAt ? <InfoRow label="Appointment" value={clinicDateTime(appointment.scheduledAt)} /> : null}
        {appointment?.reasonForVisit ? <InfoRow label="Reason for visit" value={appointment.reasonForVisit} /> : null}
        {consultation?.startedAt ? <InfoRow label="Started" value={clinicDateTime(consultation.startedAt)} /> : null}
        {consultation?.completedAt ? <InfoRow label="Completed" value={clinicDateTime(consultation.completedAt)} /> : null}
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  card:   { gap: space.md },
  top:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.md },
  who:    { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, gap: 2 },
  name:   { fontSize: fontSize.xl, fontWeight: '700', color: colors.text },
  id:     { fontSize: fontSize.sm, color: colors.textMuted },
  badges: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  links:  { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
});
