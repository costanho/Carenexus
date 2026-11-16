import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { Banner, Button, colors, fontSize, FormError, SectionCard, space } from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import {
  type ConsultationAppointment, type ConsultationDto, createConsultation,
} from '@/hooks/use-doctor-consultations';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import { isAfterClinicNow } from '@/lib/time/clinic';
import { clinicDateTime } from './consultation-format';

type OpenAs = 'IN_PROGRESS' | 'DRAFT';

export interface ConsultationStartProps {
  appointment:     ConsultationAppointment;
  /** The signed-in doctor. */
  doctorSystemId:  string;
  /** Earlier consultations of this appointment that were cancelled (newest first). */
  cancelled:       ConsultationDto[];
  onCreated:       (consultation: ConsultationDto) => void;
  /**
   * The server refused the create with 409 (usually: a consultation already exists). Reloads the appointment
   * and resolves true when an existing consultation was found and opened.
   */
  onConflict:      () => Promise<boolean>;
  /** Loads the appointment again (for example once its start time has passed). */
  onRefresh:       () => void;
  onOpenCancelled: (consultationId: number) => void;
}

/** Why no consultation can be opened for the appointment right now; null when one can. */
function blockedReason(appointment: ConsultationAppointment, doctorSystemId: string): { text: string; retry: boolean } | null {
  if (appointment.doctorSystemId && appointment.doctorSystemId !== doctorSystemId) {
    return { text: 'Only the doctor of this appointment can open a consultation for it.', retry: false };
  }
  if (appointment.appointmentStatus === 'CANCELLED') {
    return { text: 'This appointment was cancelled, so a consultation cannot be opened for it.', retry: false };
  }
  if (appointment.appointmentStatus === 'NO_SHOW') {
    return { text: 'This appointment was marked as missed, so a consultation cannot be opened for it.', retry: false };
  }
  if (appointment.scheduledAt && isAfterClinicNow(appointment.scheduledAt)) {
    return {
      text: `A consultation can be opened once the appointment has started (${clinicDateTime(appointment.scheduledAt)}).`,
      retry: true,
    };
  }
  return null;
}

/** Shown when the appointment has no consultation yet: opens one, in progress or as a draft. */
export function ConsultationStart({
  appointment, doctorSystemId, cancelled, onCreated, onConflict, onRefresh, onOpenCancelled,
}: ConsultationStartProps) {
  // One key per way of opening, kept for retries of the same submit and renewed after a success.
  const startKey = useIdempotencyKey();
  const draftKey = useIdempotencyKey();
  const [intent, setIntent] = useState<OpenAs | null>(null);
  const [error, setError] = useState<string | null>(null);

  const create = useAction((status: OpenAs) => createConsultation(
    { appointmentId: appointment.appointmentId, status },
    (status === 'IN_PROGRESS' ? startKey : draftKey).current(),
  ), { errorMessage: 'The consultation could not be opened. Please try again.' });

  const open = async (status: OpenAs) => {
    setError(null);
    setIntent(status);
    const result = await create.run(status);
    if (result.ok) {
      startKey.renew();
      draftKey.renew();
      onCreated(result.data);
      return;
    }
    if (!result.error) return; // a second press while the first request is running
    if (result.error.status === 409 && await onConflict()) return; // the existing consultation was opened
    setError(result.error.message);
  };

  const blocked = blockedReason(appointment, doctorSystemId);

  return (
    <SectionCard
      title="No consultation yet"
      subtitle="Open a consultation to record the chief complaint, examination, diagnosis and plan for this appointment."
    >
      {blocked ? (
        <Banner
          tone="info"
          text={blocked.text}
          action={blocked.retry ? { label: 'Check again', onPress: onRefresh } : undefined}
        />
      ) : (
        <>
          <FormError message={error} />
          <View style={styles.actions}>
            <Button
              label="Start consultation"
              icon="play-outline"
              onPress={() => { void open('IN_PROGRESS'); }}
              loading={create.pending && intent === 'IN_PROGRESS'}
              disabled={create.pending && intent !== 'IN_PROGRESS'}
            />
            <Button
              label="Write notes as a draft"
              icon="document-outline"
              variant="secondary"
              onPress={() => { void open('DRAFT'); }}
              loading={create.pending && intent === 'DRAFT'}
              disabled={create.pending && intent !== 'DRAFT'}
            />
          </View>
          <Text style={styles.note}>
            Start consultation records the start time now. A draft has no start time and can be completed later.
          </Text>
        </>
      )}

      {cancelled.length > 0 ? (
        <View style={styles.cancelled}>
          <Text style={styles.note}>
            {cancelled.length === 1
              ? 'An earlier consultation for this appointment was cancelled.'
              : `${cancelled.length} earlier consultations for this appointment were cancelled.`}
          </Text>
          <View style={styles.actions}>
            {cancelled.map(c => (
              <Button
                key={c.consultationId}
                label={`View cancelled consultation of ${clinicDateTime(c.createdAt, 'an earlier date')}`}
                variant="ghost"
                size="sm"
                onPress={() => onOpenCancelled(c.consultationId)}
              />
            ))}
          </View>
        </View>
      ) : null}
    </SectionCard>
  );
}

const styles = StyleSheet.create({
  actions:   { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  note:      { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  cancelled: { gap: space.sm, borderTopWidth: 1, borderTopColor: colors.border, paddingTop: space.md },
});
