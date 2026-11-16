import { useRouter } from 'expo-router';
import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { BookingForm } from '@/components/web/appointments/booking-form';
import {
  ariaLevel, Button, Card, colors, ErrorView, fontSize, Icon, InfoRow, LoadingView, Screen, space, useUiTheme,
} from '@/components/web/ui';
import { applyAppointmentUpdate } from '@/hooks/use-appointments';
import { useCurrentUser } from '@/hooks/use-current-user';
import { APPOINTMENT_TYPE_LABEL } from '@/lib/appointments/rules';
import type { AppointmentDto } from '@/lib/appointments/types';
import { useDoctorName } from '@/lib/doctors/names';
import type { SectionProps } from '@/lib/nav/sections';
import { formatClinicDateTime } from '@/lib/time/clinic';

// The doctor reschedule panel imports the calendar from this file; it now lives with the shared booking components.
export { CalendarPicker } from '@/components/web/appointments/calendar-picker';

type Props = Partial<Pick<SectionProps, 'navigate' | 'params' | 'theme'>> & {
  /** Leaves the booking screen (the dashboard passes "go to the home section"). */
  onBack?: () => void;
};

function BookedCard({ appointment, onBack, onViewAppointments, onBookAnother }: {
  appointment: AppointmentDto;
  onBack: () => void;
  onViewAppointments: () => void;
  onBookAnother: () => void;
}) {
  const { accent } = useUiTheme();
  const doctorName = useDoctorName(appointment.doctorSystemId, appointment.doctorName);
  return (
    <Card style={styles.booked}>
      <Icon name="checkmark-circle" size={56} color={accent} />
      <Text style={styles.bookedTitle} accessibilityRole="header" {...ariaLevel(2)}>Appointment Booked!</Text>
      <Text style={styles.bookedText} role="status">
        Your appointment with {doctorName} has been scheduled. You can view or manage it under My Appointments.
      </Text>
      <View style={styles.bookedDetails}>
        <InfoRow label="When" value={formatClinicDateTime(appointment.scheduledAt)} />
        <InfoRow label="Type" value={`${APPOINTMENT_TYPE_LABEL[appointment.type] ?? appointment.type}, ${appointment.durationMinutes} minutes`} />
        {appointment.type === 'IN_PERSON' && appointment.facilityName ? (
          <InfoRow
            label="Location"
            value={appointment.facilityAddress ? `${appointment.facilityName}, ${appointment.facilityAddress}` : appointment.facilityName}
          />
        ) : null}
        {appointment.type === 'PHONE' && appointment.contactPhone ? (
          <InfoRow label="Phone call" value={`The doctor will call you on ${appointment.contactPhone}.`} />
        ) : null}
      </View>
      <View style={styles.bookedActions}>
        <Button label="Back to Dashboard" onPress={onBack} />
        <Button label="View my appointments" variant="secondary" onPress={onViewAppointments} />
        <Button label="Book another appointment" variant="ghost" onPress={onBookAnother} />
      </View>
    </Card>
  );
}

/**
 * Patient > Book Appointment. The signed-in patient books for themselves with the shared booking form:
 * doctor, date and one of the doctor's free times, then the details of the visit.
 */
export function PatientWebAppointment({ onBack, navigate, params, theme }: Props) {
  const router = useRouter();
  const { user, loading, error, refetch } = useCurrentUser();
  const [booked, setBooked] = useState<AppointmentDto | null>(null);

  const goBack = onBack ?? (() => {
    if (router.canGoBack()) router.back();
    else router.replace('/dashboard/patient' as any);
  });
  const viewAppointments = (appointmentId: number) => {
    if (navigate) navigate('my-appointments', { id: appointmentId });
    else router.push(`/dashboard/patient?section=my-appointments&id=${appointmentId}` as any);
  };

  function handleBooked(appointment: AppointmentDto) {
    // Every panel that lists appointments (right sidebar, dashboard, My Appointments) shows the new one.
    applyAppointmentUpdate(appointment, { created: true });
    setBooked(appointment);
  }

  return (
    <Screen
      title="Book Appointment"
      subtitle="Schedule a visit with your healthcare provider"
      theme={theme}
      role="patient"
    >
      <View style={styles.column}>
        {loading ? (
          <LoadingView label="Loading your account…" />
        ) : !user?.system_id ? (
          <ErrorView message={error ?? 'We could not load your account.'} onRetry={refetch} />
        ) : booked ? (
          <BookedCard
            appointment={booked}
            onBack={goBack}
            onViewAppointments={() => viewAppointments(booked.appointmentId)}
            onBookAnother={() => setBooked(null)}
          />
        ) : (
          <BookingForm
            patientSystemId={user.system_id}
            initialDate={params?.date}
            onBooked={handleBooked}
            theme={theme}
          />
        )}
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  column: { width: '100%', maxWidth: 860, minWidth: 0, gap: space.lg },

  booked:        { alignItems: 'center', gap: space.md, paddingVertical: space.xxl },
  bookedTitle:   { fontSize: fontSize.title, fontWeight: '700', color: colors.text, textAlign: 'center' },
  bookedText:    { fontSize: fontSize.md, color: colors.textMuted, textAlign: 'center', lineHeight: 21, maxWidth: 520 },
  bookedDetails: { alignSelf: 'stretch', maxWidth: 520, width: '100%', marginHorizontal: 'auto' },
  bookedActions: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'center', gap: space.sm },
});

export default PatientWebAppointment;
