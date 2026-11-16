import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, colors, type DialogCloseReason, ErrorView, fontSize, FormActions, FormError, LoadingView, Modal,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import {
  type DoctorPrescription, getPrescriptionSchedule, type PrescriptionSchedulePlan, updatePrescriptionSchedule,
} from '@/hooks/use-doctor-prescriptions';
import { useResource } from '@/hooks/use-resource';
import type { ApiError } from '@/lib/api/errors';
import { formatClinicDateTime } from '@/lib/time/clinic';
import { confirmAction } from '@/lib/ui/dialogs';
import { scheduleTimesSchema } from '@/lib/validation/prescription';
import { ScheduleTimesField } from './schedule-times-field';
import { isStaleRowError, withFieldErrors } from './server-errors';

export interface EditScheduleDialogProps {
  /** The prescription whose dose times are edited; null keeps the dialog closed. */
  prescription: DoctorPrescription | null;
  onClose:      () => void;
  onSaved:      (plan: PrescriptionSchedulePlan) => void;
  /** The server says the schedule can no longer be changed (or the prescription is gone): the list needs a refresh. */
  onStale:      (error: ApiError) => void;
}

/** Replaces the daily dose times of a prescription (PUT /api/prescriptions/{id}/schedule). */
export function EditScheduleDialog({ prescription, ...rest }: EditScheduleDialogProps) {
  return prescription ? <OpenDialog key={prescription.prescription_id} prescription={prescription} {...rest} /> : null;
}

function sameTimes(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((t, i) => t === b[i]);
}

function OpenDialog({ prescription: rx, onClose, onSaved, onStale }: EditScheduleDialogProps & { prescription: DoctorPrescription }) {
  const plan = useResource(() => getPrescriptionSchedule(rx.prescription_id), [rx.prescription_id], {
    errorMessage: 'The dose times could not be loaded.',
  });
  // The doctor's edits; null until something is changed, so the field starts from the stored times.
  const [edited, setEdited] = useState<string[] | null>(null);
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  const stored = plan.data?.times ?? null;
  const times = edited ?? stored;

  const save = useAction((next: string[]) => updatePrescriptionSchedule(rx.prescription_id, next));
  const dirty = !!stored && !!edited && !sameTimes(stored, edited);

  const submit = async () => {
    if (!times) return;
    setFieldError(null);
    setFormError(null);
    const parsed = scheduleTimesSchema.safeParse({ times });
    if (!parsed.success) {
      setFieldError(parsed.error.issues[0]?.message ?? 'Check the dose times');
      return;
    }
    const result = await save.run(parsed.data.times);
    if (result.ok) { onSaved(result.data); return; }
    if (!result.error) return;
    if (isStaleRowError(result.error)) { onStale(result.error); return; }
    const error = withFieldErrors(result.error, ['times']);
    if (error.fieldErrors?.times) setFieldError(error.fieldErrors.times);
    else setFormError(error.message);
  };

  const close = async (_reason?: DialogCloseReason) => {
    if (save.pending) return;
    if (dirty && !(await confirmAction('Discard your changes?', 'The dose times have not been updated.', 'Discard', true))) return;
    onClose();
  };

  const ready = !plan.loading && !!stored;

  return (
    <Modal
      open
      title="Edit schedule times"
      size="md"
      onClose={close}
      dismissOnBackdrop={false}
      busy={save.pending}
      footer={ready ? (
        <FormActions
          submitLabel="Save times"
          onSubmit={() => { void submit(); }}
          onCancel={() => { void close(); }}
          submitting={save.pending}
          disabled={!dirty}
        />
      ) : undefined}
    >
      <View style={styles.summary}>
        <Text style={styles.medication}>{rx.medication_name}</Text>
        <Text style={styles.detail}>{[rx.dosage, rx.frequency].filter(Boolean).join(' · ')}</Text>
        <Text style={styles.detail}>For {rx.patient_name}</Text>
      </View>

      {plan.loading ? (
        <LoadingView label="Loading dose times…" compact />
      ) : !ready ? (
        <ErrorView message={plan.error ?? 'The dose times could not be loaded.'} onRetry={plan.refetch} compact />
      ) : (
        <>
          <Banner
            tone="info"
            text={'Today\'s remaining doses and all future doses follow the new times. Doses already taken or missed, '
              + 'and doses whose time has passed, keep their original times.'
              + (rx.effective_status === 'ON_HOLD' ? ' This prescription is on hold: doses are scheduled again when you resume it.' : '')}
          />
          <FormError message={formError} />
          <ScheduleTimesField
            value={times ?? []}
            onChange={next => { setEdited(next); setFieldError(null); }}
            error={fieldError}
            disabled={save.pending}
            hint="Up to 8 times a day, in 24-hour clinic time. With no times the patient gets no scheduled doses or dose reminders."
          />
          {plan.data?.updatedAt ? (
            <Text style={styles.detail}>Last changed {formatClinicDateTime(plan.data.updatedAt)}</Text>
          ) : null}
        </>
      )}
    </Modal>
  );
}

const styles = StyleSheet.create({
  summary:    { gap: 2, minWidth: 0 },
  medication: { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  detail:     { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
