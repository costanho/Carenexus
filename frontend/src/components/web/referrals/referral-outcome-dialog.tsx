import { zodResolver } from '@hookform/resolvers/zod';
import { useMemo, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, colors, fontSize, FormActions, FormError, InfoRow, Modal, SelectField, space, TextField,
} from '@/components/web/ui';
import { useAcceptReferral, useCompleteReferral, useDeclineReferral } from '@/hooks/use-referrals';
import type { ApiError } from '@/lib/api/errors';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { hasAction, specialistName, specialistSummary } from '@/lib/referrals/format';
import type { Referral } from '@/lib/referrals/types';
import {
  OUTCOME_ACTIONS, OUTCOME_FIELDS, type OutcomeAction, outcomeSchema, outcomeTextLimit, type OutcomeValues,
} from '@/lib/validation/referral';

/** `accept`, `decline`, `complete`: the named specialist responds. `outcome`: recorded on the specialist's behalf. */
export type OutcomeDialogMode = 'accept' | 'decline' | 'complete' | 'outcome';

const MODE_ACTION: Record<Exclude<OutcomeDialogMode, 'outcome'>, OutcomeAction> = {
  accept: 'ACCEPT', decline: 'DECLINE', complete: 'COMPLETE',
};

const OUTCOME_LABEL: Record<OutcomeAction, string> = {
  ACCEPT:   'Accepted by the specialist',
  DECLINE:  'Declined by the specialist',
  COMPLETE: 'Specialist care completed',
};

const TITLE: Record<OutcomeDialogMode, string> = {
  accept:   'Accept referral',
  decline:  'Decline referral',
  complete: 'Mark referral as completed',
  outcome:  'Record outcome',
};

const SUBMIT: Record<OutcomeDialogMode, string> = {
  accept:   'Accept referral',
  decline:  'Decline referral',
  complete: 'Mark as completed',
  outcome:  'Record outcome',
};

/** What to tell the doctor after the write succeeded. */
function doneMessage(action: OutcomeAction, updated: Referral, onBehalf: boolean): string {
  const patient = updated.patientName;
  if (onBehalf) {
    if (action === 'ACCEPT') return `Recorded: the referral for ${patient} was accepted.`;
    if (action === 'DECLINE') return `Recorded: the referral for ${patient} was declined.`;
    return `Recorded: the referral for ${patient} is completed.`;
  }
  if (action === 'ACCEPT') return `Referral accepted. You now have access to ${patient}'s records.`;
  if (action === 'DECLINE') return `Referral for ${patient} declined.`;
  return `Referral for ${patient} marked as completed.`;
}

export interface ReferralOutcomeDialogProps {
  referral: Referral;
  mode:     OutcomeDialogMode;
  onClose:  () => void;
  /** The write succeeded: `updated` is the referral as the server now has it. */
  onDone:   (updated: Referral, message: string) => void;
  /** The server refused because the referral changed meanwhile (409, 403, 404): reload what is on screen. */
  onStale:  () => void;
}

/**
 * Accept, decline or complete a referral. The named specialist gets one dialog per action; a referring doctor
 * recording the outcome for a specialist outside the platform (or for an unassigned referral) chooses the
 * outcome and must add a note. The server's refusal (for example a 409 because the referral was cancelled
 * meanwhile) is shown inside the dialog.
 */
export function ReferralOutcomeDialog({ referral, mode, onClose, onDone, onStale }: ReferralOutcomeDialogProps) {
  const onBehalf = referral.outcomeNoteRequired;
  const choices = useMemo<OutcomeAction[]>(
    () => (mode === 'outcome' ? OUTCOME_ACTIONS.filter(a => hasAction(referral, a)) : [MODE_ACTION[mode]]),
    [mode, referral],
  );
  const schema = useMemo(() => outcomeSchema(onBehalf), [onBehalf]);
  const { control, handleSubmit, setError } = useForm<OutcomeValues>({
    resolver: zodResolver(schema),
    defaultValues: { action: choices[0] ?? 'ACCEPT', text: '' },
  });
  const action = useWatch({ control, name: 'action' });

  const accept = useAcceptReferral();
  const decline = useDeclineReferral();
  const complete = useCompleteReferral();
  const pending = accept.pending || decline.pending || complete.pending;
  const [formError, setFormError] = useState<string | null>(null);

  const fail = (error: ApiError | null) => {
    if (!error) return;
    setFormError(applyFieldErrors(error, setError, OUTCOME_FIELDS));
    if (error.status === 409 || error.status === 403 || error.status === 404) onStale();
  };

  const submit = handleSubmit(async values => {
    setFormError(null);
    const result = values.action === 'ACCEPT' ? await accept.run(referral.referralId, values.text)
      : values.action === 'DECLINE' ? await decline.run(referral.referralId, values.text)
        : await complete.run(referral.referralId, values.text);
    if (result.ok) onDone(result.data, doneMessage(values.action, result.data, onBehalf));
    else fail(result.error);
  });

  const isDecline = action === 'DECLINE';
  const textRequired = isDecline || onBehalf;
  const textLabel = isDecline
    ? (onBehalf ? 'Reason the specialist gave for declining' : 'Reason for declining')
    : (onBehalf ? 'Outcome note' : 'Note');
  const textHint = isDecline
    ? 'The referring doctor and the patient can read this.'
    : onBehalf
      ? 'What the specialist reported, for the record. The patient can read this.'
      : 'Optional. The referring doctor and the patient can read this.';
  const specialist = specialistName(referral);

  return (
    <Modal
      open
      title={TITLE[mode]}
      onClose={onClose}
      dismissOnBackdrop={false}
      busy={pending}
      footer={(
        <FormActions
          submitLabel={SUBMIT[mode]}
          onSubmit={submit}
          onCancel={onClose}
          submitting={pending}
          destructive={mode === 'decline'}
          disabled={choices.length === 0}
        />
      )}
    >
      <View style={styles.summary}>
        <InfoRow label="Patient" value={referral.patientName} />
        <InfoRow label={mode === 'outcome' ? 'Specialist' : 'Referred by'} value={mode === 'outcome' ? specialistSummary(referral) : referral.referringDoctorName} />
        <InfoRow label="Reason"><Text style={styles.reason} numberOfLines={4}>{referral.reason}</Text></InfoRow>
      </View>

      {mode === 'outcome' ? (
        <Banner
          tone="info"
          text={specialist
            ? `You are recording this on behalf of ${specialist}. It is saved as recorded by you.`
            : 'No specialist is assigned. You are recording this on the specialist\'s behalf, and it is saved as recorded by you.'}
        />
      ) : null}
      {mode === 'accept' && !onBehalf ? (
        <Banner tone="info" text={`Accepting adds you to ${referral.patientName}'s care team, so you can open their records.`} />
      ) : null}

      <FormError message={formError} />

      {mode === 'outcome' && choices.length > 1 ? (
        <SelectField<OutcomeAction, OutcomeValues>
          label="Outcome"
          control={control}
          name="action"
          options={choices.map(value => ({ value, label: OUTCOME_LABEL[value] }))}
          disabled={pending}
          required
        />
      ) : mode === 'outcome' && choices.length === 1 ? (
        <InfoRow label="Outcome" value={OUTCOME_LABEL[choices[0]]} />
      ) : null}

      <TextField<OutcomeValues>
        label={textLabel}
        control={control}
        name="text"
        multiline
        rows={4}
        maxLength={outcomeTextLimit(action)}
        showCount
        disabled={pending}
        required={textRequired}
        hint={textHint}
      />
    </Modal>
  );
}

const styles = StyleSheet.create({
  summary: { minWidth: 0, paddingBottom: space.xs, borderBottomWidth: 1, borderBottomColor: colors.border },
  reason:  { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
});
