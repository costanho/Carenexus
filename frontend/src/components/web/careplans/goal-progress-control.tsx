import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  Button, colors, fontSize, FormActions, FormError, radius, SelectField, space, STATUS_TONES, TextField,
} from '@/components/web/ui';
import { useGoalProgress } from '@/hooks/use-care-plans';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { GOAL_STATUS_LABEL, type GoalActor, goalStatusChoices } from '@/lib/careplans/rules';
import type { CarePlan, CarePlanGoal, CarePlanGoalStatus } from '@/lib/careplans/types';
import {
  CARE_PLAN_LIMITS, type GoalProgressFormValues, goalProgressSchema, toGoalProgressRequest,
} from '@/lib/validation/care-plan';

export interface GoalProgressControlProps {
  planId:  number;
  goal:    CarePlanGoal;
  /**
   * Who is updating. The patient and a caregiver choose between Open, In progress and Achieved; the doctor who
   * wrote the plan can also drop a goal and bring a dropped goal back. Render the control only for someone who
   * may update this goal (`canUpdateGoal`).
   */
  actor:   GoalActor;
  /** Called with the whole plan as the server returned it after the goal changed. */
  onSaved: (plan: CarePlan) => void;
  /**
   * The server refused because the plan or the goal changed elsewhere, or access ended (403, 404, 409). The
   * owner reloads the plan and shows the message.
   */
  onStale?: (message: string) => void;
}

const FIELDS = ['status', 'progressNote'] as const;

/**
 * "Update progress" for one goal of a care plan: a status and a progress note, saved with
 * PATCH /api/care-plans/{id}/goals/{goalId}. Only what changed is sent; clearing the note removes it.
 */
export function GoalProgressControl({ planId, goal, actor, onSaved, onStale }: GoalProgressControlProps) {
  const [open, setOpen] = useState(false);
  const [saved, setSaved] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const save = useGoalProgress();

  const { control, handleSubmit, reset, setError } = useForm<GoalProgressFormValues>({
    resolver: zodResolver(goalProgressSchema),
    defaultValues: { status: goal.status, progressNote: goal.progressNote ?? '' },
  });
  const values = useWatch({ control });
  const changed = toGoalProgressRequest(
    { status: (values.status ?? goal.status) as CarePlanGoalStatus, progressNote: values.progressNote ?? '' },
    goal,
  ) !== null;

  // A doctor reopening a dropped goal still sees "Dropped" as the current choice.
  const choices = goalStatusChoices(actor);
  const statuses = choices.includes(goal.status) ? choices : [...choices, goal.status];
  const options = statuses.map(s => ({ value: s, label: GOAL_STATUS_LABEL[s] }));

  const start = () => {
    reset({ status: goal.status, progressNote: goal.progressNote ?? '' });
    setFormError(null);
    setSaved(false);
    save.reset();
    setOpen(true);
  };

  const submit = handleSubmit(async form => {
    setFormError(null);
    const request = toGoalProgressRequest(form, goal);
    if (!request) { setOpen(false); return; }
    const result = await save.run(planId, goal.goalId, request);
    if (result.ok) {
      setOpen(false);
      setSaved(true);
      onSaved(result.data);
      return;
    }
    const failure = result.error;
    if (!failure) return;
    if (onStale && (failure.status === 403 || failure.status === 404 || failure.status === 409)) {
      setOpen(false);
      onStale(failure.message);
      return;
    }
    setFormError(applyFieldErrors(failure, setError, FIELDS));
  });

  if (!open) {
    return (
      <View style={styles.closed}>
        <Button
          label="Update progress"
          icon="create-outline"
          variant="secondary"
          size="sm"
          onPress={start}
          accessibilityLabel={`Update progress, ${goal.description}`}
        />
        {saved ? <Text style={styles.saved} role="status" aria-live="polite">Progress saved</Text> : null}
      </View>
    );
  }

  return (
    <View style={styles.editor} role="group" aria-label={`Update progress, ${goal.description}`}>
      <SelectField control={control} name="status" label="Goal status" options={options} required />
      <TextField
        control={control}
        name="progressNote"
        label="Progress note"
        hint="Optional. How it is going, in a sentence or two."
        multiline
        rows={3}
        maxLength={CARE_PLAN_LIMITS.note}
        showCount
      />
      <FormError message={formError} />
      <FormActions
        submitLabel="Save progress"
        onSubmit={submit}
        onCancel={() => setOpen(false)}
        submitting={save.pending}
        disabled={!changed}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  closed: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, marginTop: space.xs },
  saved:  { fontSize: fontSize.sm, fontWeight: '600', color: STATUS_TONES.success.fg },
  editor: {
    gap: space.md, marginTop: space.xs, padding: space.md,
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.bg,
  },
});
