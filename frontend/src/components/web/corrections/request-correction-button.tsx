import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text } from 'react-native';
import {
  Button, colors, type DialogCloseReason, fontSize, FormActions, FormError, Modal, StatusBadge, TextField,
} from '@/components/web/ui';
import { useCreateCorrectionRequest } from '@/hooks/use-correction-requests';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { correctionResourceLabel } from '@/lib/corrections/labels';
import { CORRECTION_TEXT_MAX, type CorrectionRequestFormValues, correctionRequestSchema } from '@/lib/corrections/schema';
import type { CorrectionRequest, CorrectionResourceType } from '@/lib/corrections/types';
import { confirmAction } from '@/lib/ui/dialogs';

export interface RequestCorrectionButtonProps {
  /** The patient the record belongs to: the signed-in patient, or the dependent a caregiver acts for. */
  patientSystemId: string;
  resourceType:    CorrectionResourceType;
  /** recordId, resultId, imagingId, prescriptionId or consultationId of the record. */
  resourceId:      number;
  /**
   * Whether the viewer may ask for a correction. Default true (a patient on their own record). The proxy area
   * passes `effectivePermission(dependent, 'canManageCarePlan')`; when false nothing is rendered.
   */
  canRequest?:     boolean;
  /** Called with the new request once it was filed. */
  onRequested?:    (request: CorrectionRequest) => void;
  /** Names the record for assistive technology, for example its title ("Full blood count"). */
  recordLabel?:    string;
  size?:           'sm' | 'md';
  testID?:         string;
}

/**
 * "Request a correction" on a record, lab result, imaging result, prescription or consultation: a button that
 * opens a short form. The request goes to the doctor who wrote the record (or to an administrator when that
 * doctor is no longer active); the record itself is not changed by asking.
 */
export function RequestCorrectionButton({
  patientSystemId, resourceType, resourceId, canRequest = true, onRequested, recordLabel, size = 'sm', testID,
}: RequestCorrectionButtonProps) {
  const [open, setOpen] = useState(false);
  // What happened to this record's request while the button was on screen.
  const [outcome, setOutcome] = useState<'sent' | 'exists' | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  // The server answered that an open request for this record already exists (filed earlier, or by someone else).
  const [exists, setExists] = useState(false);
  const create = useCreateCorrectionRequest();
  const kind = correctionResourceLabel(resourceType).toLowerCase();

  const { control, handleSubmit, reset, setError, formState: { isDirty } } = useForm<CorrectionRequestFormValues>({
    resolver:      zodResolver(correctionRequestSchema),
    defaultValues: { requestedText: '' },
  });

  if (!canRequest) return null;

  if (outcome) {
    return (
      <StatusBadge
        label={outcome === 'sent' ? 'Correction requested' : 'Correction request open'}
        tone="info"
        icon="create-outline"
      />
    );
  }

  const show = () => {
    reset({ requestedText: '' });
    setFormError(null);
    setExists(false);
    create.begin();
    setOpen(true);
  };

  const close = async (reason: DialogCloseReason | 'cancel') => {
    if (exists) {
      setOpen(false);
      setOutcome('exists');
      return;
    }
    // Escape also arrives when it was pressed for something else; typed text is not thrown away silently.
    if (isDirty && reason !== 'cancel') {
      const discard = await confirmAction('Discard this request?', 'What you typed will be lost.', 'Discard', true);
      if (!discard) return;
    }
    setOpen(false);
  };

  const onSubmit = handleSubmit(async values => {
    setFormError(null);
    const result = await create.submit({ patientSystemId, resourceType, resourceId, requestedText: values.requestedText });
    if (result.ok) {
      setOpen(false);
      setOutcome('sent');
      onRequested?.(result.data);
      return;
    }
    if (!result.error) return;
    // An open request for this record already exists: say so and stop offering the form.
    if (result.error.status === 409 && result.error.code !== 'IDEMPOTENCY_IN_PROGRESS') {
      setFormError(result.error.message);
      setExists(true);
      return;
    }
    setFormError(applyFieldErrors(result.error, setError, ['requestedText']));
  });

  return (
    <>
      <Button
        label="Request a correction"
        accessibilityLabel={recordLabel ? `Request a correction to ${recordLabel}` : `Request a correction to this ${kind}`}
        icon="create-outline"
        variant="secondary"
        size={size}
        onPress={show}
        testID={testID}
      />
      <Modal
        open={open}
        title="Request a correction"
        onClose={reason => { void close(reason); }}
        dismissOnBackdrop={false}
        busy={create.pending}
        footer={exists ? (
          <FormActions submitLabel="Close" onSubmit={() => { void close('cancel'); }} />
        ) : (
          <FormActions
            submitLabel="Send request"
            submitIcon="send-outline"
            onSubmit={() => { void onSubmit(); }}
            onCancel={() => { void close('cancel'); }}
            submitting={create.pending}
          />
        )}
      >
        {exists ? (
          <Text style={styles.intro}>
            {`A correction request for this ${kind} is already waiting for a review. You can follow it in your correction requests.`}
          </Text>
        ) : (
          <>
            <Text style={styles.intro}>
              {`Describe what is wrong with this ${kind}. The doctor who wrote it will review your request and reply with a note. `
                + 'Asking does not change the record.'}
            </Text>
            <TextField
              control={control}
              name="requestedText"
              label="What should be corrected?"
              placeholder="For example: the test date should be 3 November, not 30 October."
              multiline
              rows={5}
              maxLength={CORRECTION_TEXT_MAX}
              showCount
              required
              disabled={create.pending}
            />
          </>
        )}
        <FormError message={exists ? null : formError} />
      </Modal>
    </>
  );
}

const styles = StyleSheet.create({
  intro: { fontSize: fontSize.md, color: colors.textMuted, lineHeight: 21 },
});
