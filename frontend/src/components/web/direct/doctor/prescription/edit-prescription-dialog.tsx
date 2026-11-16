import { zodResolver } from '@hookform/resolvers/zod';
import { useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, colors, DateField, type DialogCloseReason, fontSize, FormActions, FormError, Modal, TextField,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { type DoctorPrescription, type PrescriptionDto, updatePrescription } from '@/hooks/use-doctor-prescriptions';
import type { ApiError } from '@/lib/api/errors';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { clinicToday } from '@/lib/time/clinic';
import { confirmAction } from '@/lib/ui/dialogs';
import {
  EDIT_PRESCRIPTION_FIELDS, editPrescriptionSchema, type EditPrescriptionValues, PRESCRIPTION_LIMITS,
  toUpdatePrescriptionBody,
} from '@/lib/validation/prescription';
import { routeLabel } from './labels';
import { isStaleRowError, withFieldErrors } from './server-errors';

export interface EditPrescriptionDialogProps {
  /** The prescription to edit; null keeps the dialog closed. */
  prescription: DoctorPrescription | null;
  onClose:      () => void;
  onSaved:      (updated: PrescriptionDto) => void;
  /** The server says the prescription can no longer be changed (or is gone): the list needs a refresh. */
  onStale:      (error: ApiError) => void;
}

/** Edits what can change after issue: refills, expiry date and instructions. */
export function EditPrescriptionDialog({ prescription, ...rest }: EditPrescriptionDialogProps) {
  // Keyed by the row, so each opening starts from that prescription's stored values.
  return prescription ? <OpenDialog key={prescription.prescription_id} prescription={prescription} {...rest} /> : null;
}

function OpenDialog({ prescription: rx, onClose, onSaved, onStale }: EditPrescriptionDialogProps & { prescription: DoctorPrescription }) {
  const schema = useMemo(() => editPrescriptionSchema(rx.prescribed_date), [rx.prescribed_date]);
  const { control, handleSubmit, setError, watch, formState: { isDirty } } = useForm<EditPrescriptionValues>({
    resolver:      zodResolver(schema),
    defaultValues: {
      refillsAllowed:      String(rx.refills_allowed),
      expiryDate:          rx.expiry_date ?? '',
      specialInstructions: rx.special_instructions ?? '',
    },
  });
  const [formError, setFormError] = useState<string | null>(null);
  const save = useAction((values: EditPrescriptionValues) => updatePrescription(rx.prescription_id, toUpdatePrescriptionBody(values)));

  const submit = handleSubmit(async values => {
    setFormError(null);
    const result = await save.run(values);
    if (result.ok) { onSaved(result.data); return; }
    if (!result.error) return;
    if (isStaleRowError(result.error)) { onStale(result.error); return; }
    setFormError(applyFieldErrors(withFieldErrors(result.error, EDIT_PRESCRIPTION_FIELDS), setError, EDIT_PRESCRIPTION_FIELDS));
  });

  const close = async (_reason?: DialogCloseReason) => {
    if (save.pending) return;
    if (isDirty && !(await confirmAction('Discard your changes?', 'The prescription has not been updated.', 'Discard', true))) return;
    onClose();
  };

  const expiry = watch('expiryDate');
  const alreadyPast = !!expiry && /^\d{4}-\d{2}-\d{2}$/.test(expiry) && expiry < clinicToday();

  return (
    <Modal
      open
      title="Edit prescription"
      size="md"
      onClose={close}
      dismissOnBackdrop={false}
      busy={save.pending}
      footer={(
        <FormActions
          submitLabel="Save changes"
          onSubmit={submit}
          onCancel={() => { void close(); }}
          submitting={save.pending}
          disabled={!isDirty}
        />
      )}
    >
      <View style={styles.summary}>
        <Text style={styles.medication}>{rx.medication_name}</Text>
        <Text style={styles.detail}>
          {[rx.dosage, rx.frequency, routeLabel(rx.route), rx.quantity].filter(Boolean).join(' · ')}
        </Text>
        <Text style={styles.detail}>For {rx.patient_name}</Text>
      </View>
      <Banner
        tone="info"
        text={'Only the refills, the expiry date and the instructions can be changed. The medication, dosage, frequency, '
          + 'route and quantity are fixed once issued: to change the dose, cancel this prescription and issue a new one.'}
      />
      <FormError message={formError} />

      <TextField
        control={control} name="refillsAllowed" label="Refills allowed" required
        inputMode="numeric" maxLength={2} disabled={save.pending}
        hint={`A whole number from ${PRESCRIPTION_LIMITS.refillsMin} to ${PRESCRIPTION_LIMITS.refillsMax}.`}
      />
      <DateField
        control={control} name="expiryDate" label="Expiry date" min={rx.prescribed_date || undefined} disabled={save.pending}
        hint="The last day the prescription is valid. Leave empty for no expiry."
      />
      {alreadyPast ? (
        <Banner tone="warning" text="This date has already passed: the prescription will show as expired and can then no longer be changed." />
      ) : null}
      <TextField
        control={control} name="specialInstructions" label="Instructions" multiline rows={4}
        maxLength={PRESCRIPTION_LIMITS.specialInstructions} showCount disabled={save.pending}
        hint="Leave empty to remove the instructions."
      />
    </Modal>
  );
}

const styles = StyleSheet.create({
  summary:    { gap: 2, minWidth: 0 },
  medication: { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  detail:     { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
