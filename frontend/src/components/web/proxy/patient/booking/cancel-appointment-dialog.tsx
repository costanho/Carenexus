import { useEffect } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { colors, fontSize, FormActions, FormError, InfoRow, Modal, space } from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import type { ApiError } from '@/lib/api/errors';
import { deleteAppointment } from '@/lib/appointments/api';
import type { CaregiverAppointment } from '@/lib/caregiver/types';
import { formatClinicDateTime } from '@/lib/time/clinic';

export interface CancelAppointmentDialogProps {
  /** The appointment to cancel; null closes the dialog. */
  appointment: CaregiverAppointment | null;
  /** The loved one's name, for the sentence. */
  patientName: string;
  onClose:     () => void;
  /** The appointment was cancelled (or already was: a repeated cancel is a no-op on the server). */
  onCancelled: (appointment: CaregiverAppointment) => void;
  /** The server refused with 403: the caregiver's scheduling permission was removed meanwhile. */
  onForbidden: (appointment: CaregiverAppointment, failure: ApiError) => void;
}

/**
 * Confirmation before DELETE /api/appointments/{id}. Other refusals (for example a 409 because the consultation
 * was already completed) are shown in the dialog with the server's reason.
 */
export function CancelAppointmentDialog({
  appointment, patientName, onClose, onCancelled, onForbidden,
}: CancelAppointmentDialogProps) {
  const cancel = useAction(
    (appointmentId: number) => deleteAppointment(appointmentId),
    { errorMessage: 'Failed to cancel the appointment. Please try again.' },
  );
  const { reset } = cancel;

  // Every opening starts without the previous appointment's error.
  const openId = appointment?.appointmentId ?? null;
  useEffect(() => { reset(); }, [openId, reset]);

  if (!appointment) return null;
  const target = appointment;
  const when = formatClinicDateTime(target.scheduledAt, 'the booked time');
  const doctor = target.doctorName?.trim() || 'the doctor';

  async function confirm() {
    const result = await cancel.run(target.appointmentId);
    if (result.ok) {
      onCancelled(target);
      return;
    }
    if (result.error?.status === 403) onForbidden(target, result.error);
  }

  return (
    <Modal
      open
      title="Cancel appointment"
      onClose={onClose}
      busy={cancel.pending}
      size="sm"
      footer={(
        <FormActions
          submitLabel="Cancel appointment"
          submitIcon="close-circle-outline"
          onSubmit={confirm}
          onCancel={onClose}
          cancelLabel="Keep appointment"
          submitting={cancel.pending}
          destructive
        />
      )}
    >
      <View style={styles.body}>
        <Text style={styles.text}>
          Cancel {patientName}'s appointment with {doctor} on {when}? This cannot be undone; you can book a new
          appointment afterwards.
        </Text>
        {target.reasonForVisit ? <InfoRow label="Reason for visit" value={target.reasonForVisit} /> : null}
        <FormError message={cancel.failure && cancel.failure.status !== 403 ? cancel.error : null} />
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  body: { gap: space.md },
  text: { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },
});
