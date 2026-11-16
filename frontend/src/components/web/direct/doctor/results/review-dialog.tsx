import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  colors, fontSize, FormActions, FormError, Modal, SelectField, type SelectOption, space,
  TextField,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import type { ReviewResultBody } from '@/hooks/use-doctor-lab-results';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { confirmAction } from '@/lib/ui/dialogs';
import {
  REVIEW_NOTES_MAX, type ReviewResultFormValues, reviewResultFormSchema, type ReviewStatus,
} from '@/lib/validation/results';

// "Mark reviewed": signs a lab or imaging result off as Completed or Critical, with an optional note.

const OUTCOMES: SelectOption<ReviewStatus>[] = [
  { value: 'COMPLETED', label: 'Completed: reviewed, no urgent action' },
  { value: 'CRITICAL',  label: 'Critical: needs urgent attention' },
];

export interface ReviewResultDialogProps<T> {
  open:          boolean;
  /** What is being reviewed, for example "Full Blood Count (FBC) for Thomas Dube". */
  subject:       string;
  /** The outcome preselected when the dialog opens. */
  defaultStatus: ReviewStatus;
  /** Sends the review; resolves with the result as the server now has it. */
  review:        (body: ReviewResultBody) => Promise<T>;
  onReviewed:    (result: T) => void;
  onClose:       () => void;
}

export function ReviewResultDialog<T>(props: ReviewResultDialogProps<T>) {
  // Mounted per opening, so the note and any error of an earlier review never reappear.
  return props.open ? <ReviewForm {...props} /> : null;
}

function ReviewForm<T>({ subject, defaultStatus, review, onReviewed, onClose }: ReviewResultDialogProps<T>) {
  const { control, handleSubmit, setError, formState: { isDirty } } = useForm<ReviewResultFormValues>({
    resolver:      zodResolver(reviewResultFormSchema),
    defaultValues: { status: defaultStatus, reviewNotes: '' },
  });
  const [formError, setFormError] = useState<string | null>(null);
  const send = useAction((body: ReviewResultBody) => review(body), { errorMessage: 'The review could not be saved. Please try again.' });

  const submit = handleSubmit(async values => {
    setFormError(null);
    const body: ReviewResultBody = { status: values.status as ReviewStatus };
    if (values.reviewNotes) body.reviewNotes = values.reviewNotes;
    const result = await send.run(body);
    if (result.ok) onReviewed(result.data);
    else if (result.error) setFormError(applyFieldErrors(result.error, setError, ['status', 'reviewNotes']));
  });

  const close = async () => {
    // Escape also arrives when it dismisses the browser's select popup; what was entered is not thrown away silently.
    if (isDirty && !(await confirmAction('Discard this review?', 'What you entered will be lost.', 'Discard', true))) return;
    onClose();
  };

  return (
    <Modal
      open
      title="Mark reviewed"
      onClose={close}
      size="md"
      dismissOnBackdrop={false}
      busy={send.pending}
      footer={<FormActions submitLabel="Mark reviewed" submitIcon="checkmark-done-outline" onSubmit={submit} onCancel={close} submitting={send.pending} />}
    >
      <View style={styles.body}>
        <Text style={styles.subject}>{subject}</Text>
        <Text style={styles.note}>
          Your name and the time are saved as the sign-off. The patient is notified of a completed or critical result.
        </Text>
        <FormError message={formError} />
        <SelectField<ReviewStatus, ReviewResultFormValues>
          label="Outcome"
          control={control}
          name="status"
          options={OUTCOMES}
          required
        />
        <TextField
          label="Review note"
          control={control}
          name="reviewNotes"
          multiline
          rows={4}
          maxLength={REVIEW_NOTES_MAX}
          showCount
          hint="Optional. Saved with the result; everyone who can open the result can read it."
        />
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  body:    { gap: space.md },
  subject: { fontSize: fontSize.md, fontWeight: '600', color: colors.text, lineHeight: 20 },
  note:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
