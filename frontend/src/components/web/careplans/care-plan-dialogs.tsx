import { zodResolver } from '@hookform/resolvers/zod';
import { useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text } from 'react-native';
import { colors, DateField, fontSize, FormActions, FormError, Modal, TextField } from '@/components/web/ui';
import { useReviewCarePlan, useSetCarePlanStatus } from '@/hooks/use-care-plans';
import type { ApiError } from '@/lib/api/errors';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { earliestNextReviewDate, isTerminalStatus } from '@/lib/careplans/rules';
import type { CarePlan, CarePlanStatus } from '@/lib/careplans/types';
import { formatDateOnly } from '@/lib/ui/date-only';
import {
  CARE_PLAN_LIMITS, reviewCarePlanSchema, type ReviewCarePlanFormValues, toReviewCarePlanRequest,
} from '@/lib/validation/care-plan';

/** The plan changed elsewhere (finished, removed) or the doctor is no longer allowed: reload instead of retrying. */
function isStale(error: ApiError): boolean {
  return error.status === 403 || error.status === 404 || error.status === 409;
}

interface DialogBase {
  plan:    CarePlan;
  /** Called with the plan as the server returned it. */
  onSaved: (plan: CarePlan) => void;
  onClose: () => void;
  /** The server refused because the plan changed elsewhere; the owner closes the dialog, reloads and shows the message. */
  onStale: (message: string) => void;
}

// ─── Mark reviewed ────────────────────────────────────────────────────────────

const REVIEW_FIELDS = ['nextReviewDate', 'note'] as const;

/**
 * "Mark reviewed" for the doctor who wrote the plan: records a review now, with an optional note, and sets the
 * next review date (empty = no further review). PATCH /api/care-plans/{id}/review.
 */
export function ReviewCarePlanDialog({ plan, onSaved, onClose, onStale }: DialogBase) {
  const earliest = useMemo(() => earliestNextReviewDate(plan), [plan]);
  const review = useReviewCarePlan();
  const [formError, setFormError] = useState<string | null>(null);

  const { control, handleSubmit, setError } = useForm<ReviewCarePlanFormValues>({
    resolver: zodResolver(reviewCarePlanSchema(earliest)),
    defaultValues: { nextReviewDate: '', note: '' },
  });

  const submit = handleSubmit(async values => {
    setFormError(null);
    const result = await review.run(plan.planId, toReviewCarePlanRequest(values));
    if (result.ok) { onSaved(result.data); return; }
    const failure = result.error;
    if (!failure) return;
    if (isStale(failure)) { onStale(failure.message); return; }
    setFormError(applyFieldErrors(failure, setError, REVIEW_FIELDS));
  });

  return (
    <Modal
      open
      title="Mark care plan reviewed"
      onClose={onClose}
      dismissOnBackdrop={false}
      busy={review.pending}
      footer={<FormActions submitLabel="Mark reviewed" submitIcon="checkmark-outline" onSubmit={submit} onCancel={onClose} submitting={review.pending} />}
    >
      <Text style={styles.text}>
        This records that you reviewed “{plan.title}” today
        {plan.reviewDate ? ` and replaces the review planned for ${formatDateOnly(plan.reviewDate)}.` : '.'}
      </Text>
      <DateField
        control={control}
        name="nextReviewDate"
        label="Next review date"
        min={earliest}
        hint="Optional. Leave empty if no further review is planned."
      />
      <TextField
        control={control}
        name="note"
        label="Review note"
        hint="Optional. The patient and permitted caregivers can read this note."
        multiline
        rows={4}
        maxLength={CARE_PLAN_LIMITS.note}
        showCount
      />
      <FormError message={formError} />
    </Modal>
  );
}

// ─── Status change ────────────────────────────────────────────────────────────

interface StatusCopy { title: string; body: string; submit: string }

const STATUS_COPY: Record<CarePlanStatus, StatusCopy> = {
  ACTIVE: {
    title:  'Resume care plan',
    body:   'The plan becomes active again. Goals, reviews and edits continue as before.',
    submit: 'Resume plan',
  },
  ON_HOLD: {
    title:  'Put care plan on hold',
    body:   'The plan stays visible to the patient and can be resumed at any time.',
    submit: 'Put on hold',
  },
  COMPLETED: {
    title:  'Complete care plan',
    body:   'This cannot be undone. A completed plan can no longer be edited, reviewed or reopened, and goal progress can no longer be updated.',
    submit: 'Complete plan',
  },
  DISCONTINUED: {
    title:  'Discontinue care plan',
    body:   'This cannot be undone. A discontinued plan can no longer be edited, reviewed or reopened, and goal progress can no longer be updated.',
    submit: 'Discontinue plan',
  },
};

/** Label of the button that moves a plan to `status`. */
export function statusActionLabel(status: CarePlanStatus): string {
  return STATUS_COPY[status].submit;
}

/**
 * Confirms a status change of a care plan before it is sent (PATCH /api/care-plans/{id}/status). Completing
 * and discontinuing are final, which the dialog says before the doctor confirms.
 */
export function CarePlanStatusDialog({ plan, status, onSaved, onClose, onStale }: DialogBase & { status: CarePlanStatus }) {
  const change = useSetCarePlanStatus();
  const copy = STATUS_COPY[status];

  const submit = async () => {
    const result = await change.run(plan.planId, status);
    if (result.ok) { onSaved(result.data); return; }
    if (result.error && isStale(result.error)) onStale(result.error.message);
  };

  return (
    <Modal
      open
      title={copy.title}
      size="sm"
      onClose={onClose}
      busy={change.pending}
      footer={(
        <FormActions
          submitLabel={copy.submit}
          onSubmit={submit}
          onCancel={onClose}
          submitting={change.pending}
          destructive={status === 'DISCONTINUED'}
        />
      )}
    >
      <Text style={styles.strong}>{plan.title}</Text>
      <Text style={styles.text}>{copy.body}</Text>
      {isTerminalStatus(status) && plan.reviewDate ? (
        <Text style={styles.text}>The review planned for {formatDateOnly(plan.reviewDate)} will no longer be due.</Text>
      ) : null}
      <FormError message={change.error} />
    </Modal>
  );
}

const styles = StyleSheet.create({
  text:   { fontSize: fontSize.md, color: colors.textMuted, lineHeight: 21 },
  strong: { fontSize: fontSize.md, fontWeight: '700', color: colors.text, lineHeight: 21 },
});
