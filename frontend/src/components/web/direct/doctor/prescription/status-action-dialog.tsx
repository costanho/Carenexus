import { StyleSheet, Text, View } from 'react-native';
import { colors, fontSize, FormActions, FormError, Modal } from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { type DoctorPrescription, type PrescriptionDto, setPrescriptionStatus } from '@/hooks/use-doctor-prescriptions';
import type { ApiError } from '@/lib/api/errors';
import type { StatusAction } from './labels';
import { isStaleRowError } from './server-errors';

export interface StatusActionDialogProps {
  /** The prescription and the change to confirm; null keeps the dialog closed. */
  request: { prescription: DoctorPrescription; action: StatusAction } | null;
  onClose: () => void;
  onDone:  (updated: PrescriptionDto, action: StatusAction) => void;
  /** The server says the prescription can no longer be changed (or is gone): the list needs a refresh. */
  onStale: (error: ApiError) => void;
}

/** Asks before holding, resuming, completing or cancelling a prescription, then makes the change. */
export function StatusActionDialog({ request, ...rest }: StatusActionDialogProps) {
  return request
    ? <OpenDialog key={`${request.prescription.prescription_id}-${request.action.target}`} request={request} {...rest} />
    : null;
}

function OpenDialog({ request, onClose, onDone, onStale }: StatusActionDialogProps & { request: NonNullable<StatusActionDialogProps['request']> }) {
  const { prescription: rx, action } = request;
  const change = useAction(() => setPrescriptionStatus(rx.prescription_id, action.target));

  const confirm = async () => {
    const result = await change.run();
    if (result.ok) { onDone(result.data, action); return; }
    if (result.error && isStaleRowError(result.error)) onStale(result.error);
  };

  return (
    <Modal
      open
      title={action.title}
      size="sm"
      onClose={() => { if (!change.pending) onClose(); }}
      busy={change.pending}
      footer={(
        <FormActions
          submitLabel={action.confirmLabel}
          onSubmit={() => { void confirm(); }}
          onCancel={onClose}
          cancelLabel="Keep as it is"
          submitting={change.pending}
          destructive={action.destructive}
        />
      )}
    >
      <View style={styles.summary}>
        <Text style={styles.medication}>{rx.medication_name}</Text>
        <Text style={styles.detail}>{[rx.dosage, rx.frequency].filter(Boolean).join(' · ')}</Text>
        <Text style={styles.detail}>For {rx.patient_name}</Text>
      </View>
      <Text style={styles.consequence}>{action.consequence}</Text>
      <FormError message={change.error} />
    </Modal>
  );
}

const styles = StyleSheet.create({
  summary:     { gap: 2, minWidth: 0 },
  medication:  { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  detail:      { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  consequence: { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },
});
