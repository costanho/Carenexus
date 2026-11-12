import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, Button, colors, fontSize, FormActions, FormError, Modal, radius, SelectField, type SelectOption, space,
  StatusBadge, statusTone, TextField,
} from '@/components/web/ui';
import {
  HEALTH_STATUS_NOTE_MAX, type HealthStatusResult, useUpdateHealthStatus,
} from '@/hooks/use-doctor-patient-detail';
import { type DoctorPatientDto, type HealthStatus, patientFullName } from '@/hooks/use-doctor-patients';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { formatClinicDateTime } from '@/lib/time/clinic';
import { HEALTH_STATUS_LABEL, healthStatusLabel } from './format';
import { type HealthStatusFormValues, healthStatusSchema } from './health-status.schema';

const STATUS_OPTIONS: readonly SelectOption<HealthStatus>[] = [
  { value: 'STABLE',   label: HEALTH_STATUS_LABEL.STABLE },
  { value: 'MONITOR',  label: HEALTH_STATUS_LABEL.MONITOR },
  { value: 'CRITICAL', label: HEALTH_STATUS_LABEL.CRITICAL },
];

const FIELDS = ['healthStatus', 'note'] as const;

/**
 * The patient's clinical health status: what it is now (with the note, time and author of the last change) and
 * the form that changes it. Only a doctor related to the patient reaches this control; the server checks again.
 * A change into Critical notifies the patient's circle, so it is confirmed first.
 */
export function HealthStatusControl({ patient, onUpdated }: {
  /** The loaded patient detail. */
  patient:   DoctorPatientDto;
  /** Called with the server's answer after every successful save. */
  onUpdated: (result: HealthStatusResult) => void;
}) {
  const name        = patientFullName(patient);
  const current     = patient.healthStatus;
  const currentNote = (patient.healthStatusNote ?? '').trim();

  const { control, handleSubmit, reset, setError, watch } = useForm<HealthStatusFormValues>({
    resolver:      zodResolver(healthStatusSchema),
    defaultValues: { healthStatus: current, note: '' },
  });
  const update = useUpdateHealthStatus(patient.patientSystemId);
  const [formError, setFormError] = useState<string | null>(null);
  const [saved, setSaved]         = useState<string | null>(null);
  /** The values waiting for the "Mark as critical?" confirmation. */
  const [pendingCritical, setPendingCritical] = useState<HealthStatusFormValues | null>(null);

  // The status shown above the form changed (saved here, or the detail was reloaded): start the form from it.
  useEffect(() => {
    reset({ healthStatus: current, note: '' });
  }, [current, reset]);

  const selected  = watch('healthStatus');
  const noteTyped = (watch('note') ?? '').trim().length > 0;
  // The same status without a note stores nothing on the server, so there is nothing to submit.
  const nothingToSave = selected === current && !noteTyped;

  async function send(values: HealthStatusFormValues) {
    setFormError(null);
    setSaved(null);
    const result = await update.save({ healthStatus: values.healthStatus, note: values.note });
    if (result.ok) {
      const after = result.data;
      setPendingCritical(null);
      onUpdated(after);
      reset({ healthStatus: after.healthStatus, note: '' });
      setSaved(after.changed
        ? `Health status changed from ${healthStatusLabel(after.previousHealthStatus)} to ${healthStatusLabel(after.healthStatus)}.`
        : 'Note saved.');
    } else if (result.error) {
      // The confirmation closes so the message is read next to the form it belongs to.
      setPendingCritical(null);
      setFormError(applyFieldErrors(result.error, setError, FIELDS));
    }
  }

  const submit = handleSubmit(values => {
    if (values.healthStatus === 'CRITICAL' && current !== 'CRITICAL') {
      setFormError(null);
      setSaved(null);
      setPendingCritical(values);
      return;
    }
    return send(values);
  });

  const updatedAt = formatClinicDateTime(patient.healthStatusUpdatedAt);
  const noteHint = currentNote && selected !== current && !noteTyped
    ? 'Optional. Saving a new status without a note removes the current note.'
    : 'Optional. For the care team only: it is never sent to the patient or their caregivers.';

  return (
    <View style={styles.wrap}>
      <View style={styles.currentRow}>
        <StatusBadge label={healthStatusLabel(current)} tone={statusTone('health', current)} />
        {updatedAt ? (
          <Text style={styles.meta}>
            Updated {updatedAt}{patient.healthStatusUpdatedByName ? ` by ${patient.healthStatusUpdatedByName}` : ''}
          </Text>
        ) : null}
      </View>

      {currentNote ? (
        <View style={styles.note}>
          <Text style={styles.noteLabel}>Current note</Text>
          <Text style={styles.noteText}>{currentNote}</Text>
        </View>
      ) : null}

      {saved ? <Banner tone="success" text={saved} onDismiss={() => setSaved(null)} /> : null}

      <SelectField<HealthStatus, HealthStatusFormValues>
        label="Set health status"
        control={control}
        name="healthStatus"
        options={STATUS_OPTIONS}
        required
        disabled={update.pending}
      />
      <TextField<HealthStatusFormValues>
        label="Note"
        control={control}
        name="note"
        multiline
        rows={3}
        maxLength={HEALTH_STATUS_NOTE_MAX}
        showCount
        hint={noteHint}
        placeholder="Reason for the change, what to watch for"
        disabled={update.pending}
      />
      <FormError message={formError} />
      <Button
        label={selected === current ? 'Save note' : 'Update status'}
        icon="save-outline"
        onPress={() => { void submit(); }}
        loading={update.pending && !pendingCritical}
        disabled={nothingToSave}
        accessibilityLabel={selected === current
          ? `Save note about ${name}`
          : `Update status of ${name} to ${healthStatusLabel(selected)}`}
      />

      <Modal
        open={pendingCritical !== null}
        title="Mark as critical?"
        size="sm"
        onClose={() => setPendingCritical(null)}
        dismissOnBackdrop={false}
        busy={update.pending}
        footer={(
          <FormActions
            submitLabel="Mark as critical"
            destructive
            onSubmit={() => { if (pendingCritical) void send(pendingCritical); }}
            onCancel={() => setPendingCritical(null)}
            submitting={update.pending}
          />
        )}
      >
        <Text style={styles.confirmText}>
          {name}&apos;s health status will change from {healthStatusLabel(current)} to Critical.
        </Text>
        <Text style={styles.confirmText}>
          The patient, and caregivers who can view their records, are told that the care team updated the health
          status and are asked to contact the care team. Your note is not included in that message.
        </Text>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:        { gap: space.md, minWidth: 0 },
  currentRow:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
  meta:        { flexShrink: 1, minWidth: 0, fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  note:        { gap: 2, padding: space.md, borderRadius: radius.md, backgroundColor: colors.bgSubtle },
  noteLabel:   { fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted },
  noteText:    { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  confirmText: { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },
});
