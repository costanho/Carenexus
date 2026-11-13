import { StyleSheet, Text, View } from 'react-native';
import { BookingForm } from '@/components/web/appointments';
import {
  Banner, colors, EmptyView, ErrorView, fontSize, LoadingView, type RoleTheme, SectionCard, space,
} from '@/components/web/ui';
import type { AppointmentDto } from '@/lib/appointments/types';
import type { CaregiverDependent } from '@/lib/caregiver/types';
import { DependentChooser } from './dependent-chooser';
import { canSchedule, dependentName } from './scheduling';

export interface ProxyBookingPanelProps {
  dependents: readonly CaregiverDependent[];
  loading:    boolean;
  error:      string | null;
  onRetry:    () => void;
  /** PAT id of the loved one the form books for, or '' while none is chosen. */
  patientSystemId: string;
  onChoose:   (patientSystemId: string) => void;
  /** Clinic day ("yyyy-MM-dd") to start the calendar on (the `date` URL param). */
  initialDate?: string | null;
  onBooked:   (appointment: AppointmentDto, dependent: CaregiverDependent) => void;
  /** Opens Add Loved One when the caregiver has nobody linked yet. */
  onAddDependent?: () => void;
  theme?:     RoleTheme;
}

/**
 * Booking on behalf of a loved one: choose the person (only those whose grant allows scheduling can be chosen),
 * then the shared booking form with that person as the patient. The form is keyed by the patient, so choosing
 * someone else starts a fresh form with its own idempotency key.
 */
export function ProxyBookingPanel({
  dependents, loading, error, onRetry, patientSystemId, onChoose, initialDate, onBooked, onAddDependent, theme,
}: ProxyBookingPanelProps) {
  const chosen = dependents.find(d => d.patientSystemId === patientSystemId) ?? null;
  const bookable = dependents.filter(d => canSchedule(d));
  const chosenAllowed = canSchedule(chosen);

  if (loading && dependents.length === 0) return <LoadingView label="Loading your loved ones…" />;
  if (error && dependents.length === 0) return <ErrorView message={error} onRetry={onRetry} />;
  if (dependents.length === 0) {
    return (
      <EmptyView
        icon="people-outline"
        title="No loved ones linked yet"
        sub="Link a loved one first. You can book for them once their access allows scheduling appointments."
        action={onAddDependent ? { label: 'Add loved one', icon: 'person-add-outline', onPress: onAddDependent } : undefined}
      />
    );
  }

  return (
    <View style={styles.panel}>
      {error ? <Banner tone="danger" text={error} action={{ label: 'Retry', onPress: onRetry }} /> : null}

      <SectionCard
        title="Book For"
        subtitle={bookable.length > 0
          ? 'Choose who the appointment is for. The appointment appears on their own list too.'
          : undefined}
      >
        {bookable.length === 0 ? (
          <Banner
            tone="info"
            text="None of your loved ones' access allows scheduling appointments. They can change your access from their My Proxies page."
          />
        ) : null}
        <DependentChooser dependents={dependents} value={chosenAllowed ? patientSystemId : ''} onChange={onChoose} />
        {chosen && !chosenAllowed ? (
          <Text style={styles.note}>
            You can no longer book for {dependentName(chosen)}. Choose someone else.
          </Text>
        ) : null}
      </SectionCard>

      {chosen && chosenAllowed ? (
        <BookingForm
          key={chosen.patientSystemId}
          patientSystemId={chosen.patientSystemId}
          patientName={dependentName(chosen)}
          initialDate={initialDate ?? null}
          onBooked={appointment => onBooked(appointment, chosen)}
          theme={theme}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  panel: { gap: space.lg, minWidth: 0 },
  note:  { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
