import { zodResolver } from '@hookform/resolvers/zod';
import { type ReactNode, useCallback, useMemo, useRef, useState } from 'react';
import { type Control, Controller, type Resolver, useFieldArray, useForm, type UseFormSetError } from 'react-hook-form';
import { Platform, StyleSheet, Text, View } from 'react-native';
import { type PickerOption, PickerShell } from '@/components/web/pickers/picker-shell';
import {
  ariaLevel, Banner, Button, colors, DateField, fontSize, FormActions, FormError, IconButton, InfoRow, radius,
  SidePanel, space, StatusBadge, statusTone, TextField,
} from '@/components/web/ui';
import { type CarePlanPatientOptions, useCreateCarePlan, useUpdateCarePlan } from '@/hooks/use-care-plans';
import type { ApiError } from '@/lib/api/errors';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { GOAL_STATUS_LABEL } from '@/lib/careplans/rules';
import type { CarePlan, CarePlanGoalStatus, CarePlanPatientOption } from '@/lib/careplans/types';
import { clinicToday } from '@/lib/time/clinic';
import { confirmAction } from '@/lib/ui/dialogs';
import {
  CARE_PLAN_LIMITS, carePlanFormDefaults, type CarePlanFormValues, carePlanServerFields, createCarePlanSchema,
  editCarePlanSchema, EMPTY_GOAL_ROW, EMPTY_INTERVENTION_ROW, toCreateCarePlanRequest, toUpdateCarePlanRequest,
} from '@/lib/validation/care-plan';

export interface CarePlanFormProps {
  /** The plan to edit. Omit to create a new plan. */
  plan?: CarePlan | null;
  /** New plan: the patient to start with (the patient the list is filtered to, or the one a link named). */
  patientSystemId?: string | null;
  /** New plan: the consultation the plan is written from. The patient is then fixed to `patientSystemId`. */
  consultationId?: number | null;
  /** The doctor's patients, for the picker and for names (`useCarePlanPatientOptions`). */
  patients: CarePlanPatientOptions;
  /** Called with the saved plan as the server returned it. */
  onSaved: (plan: CarePlan, mode: 'create' | 'edit') => void;
  onClose: () => void;
  /** An edit was refused because the plan changed elsewhere (finished, removed); the owner reloads and shows the message. */
  onStale?: (message: string) => void;
}

type FormControl = Control<CarePlanFormValues>;

/**
 * A validation error raised by a service rule arrives as "<field>: <text>" without a field list. When the
 * sentence names a field of this form it is put on that field like any other field error.
 */
function withNamedField(error: ApiError, known: Record<string, unknown>): ApiError {
  if (error.fieldErrors || error.code !== 'VALIDATION_FAILED') return error;
  const match = /^([A-Za-z][\w.[\]]*): (.+)$/.exec(error.message);
  if (!match || !(match[1] in known)) return error;
  return { ...error, fieldErrors: { [match[1]]: match[2] } };
}

function RowHeader({ title, index, count, noun, onMove, onRemove, badge }: {
  title:    string;
  index:    number;
  count:    number;
  noun:     string;
  onMove:   (from: number, to: number) => void;
  onRemove: () => void;
  badge?:   ReactNode;
}) {
  const name = `${noun} ${index + 1}`;
  return (
    <View style={styles.rowHead}>
      <View style={styles.rowTitleWrap}>
        <Text style={styles.rowTitle}>{title}</Text>
        {badge}
      </View>
      <View style={styles.rowButtons}>
        {/* Both move buttons are always there (disabled at the ends), so moving a row to the top or the bottom
            never removes the button that has the keyboard focus. */}
        <IconButton
          label={`Move ${name} up`}
          icon="arrow-up"
          size="sm"
          disabled={index === 0}
          onPress={() => { if (index > 0) onMove(index, index - 1); }}
        />
        <IconButton
          label={`Move ${name} down`}
          icon="arrow-down"
          size="sm"
          disabled={index >= count - 1}
          onPress={() => { if (index < count - 1) onMove(index, index + 1); }}
        />
        <IconButton label={`Remove ${name}`} icon="trash-outline" size="sm" onPress={onRemove} />
      </View>
    </View>
  );
}

/**
 * Moves a row and keeps the keyboard focus on the move button the user pressed, now in the row's new place.
 * React moves the row's DOM node when it goes down, and a browser drops the focus of a moved node; the button
 * is found again by its name (which carries the new position). At an end, where that button is disabled, the
 * row's other move button gets the focus instead.
 */
function useRowMover(move: (from: number, to: number) => void, noun: string) {
  const sectionRef = useRef<View>(null);
  const onMove = useCallback((from: number, to: number) => {
    move(from, to);
    if (Platform.OS !== 'web' || typeof document === 'undefined') return;
    const pressed = to > from ? 'down' : 'up';
    const other = pressed === 'down' ? 'up' : 'down';
    setTimeout(() => {
      const section = sectionRef.current as unknown as HTMLElement | null;
      if (!section?.querySelector) return;
      const find = (dir: string) =>
        section.querySelector<HTMLElement>(`[aria-label="Move ${noun} ${to + 1} ${dir}"]`);
      const usable = (el: HTMLElement | null) => !!el && el.getAttribute('aria-disabled') !== 'true';
      const same = find(pressed);
      const target = usable(same) ? same : usable(find(other)) ? find(other) : same;
      target?.focus?.();
    }, 0);
  }, [move, noun]);
  return { sectionRef, onMove };
}

function GoalRows({ control, startDate, goalStatus, editing, listError }: {
  control:    FormControl;
  startDate:  string;
  /** Current status of the goals that already exist on the plan, by goal id. */
  goalStatus: Map<number, CarePlanGoalStatus>;
  editing:    boolean;
  listError?: string;
}) {
  const { fields, append, remove, move } = useFieldArray({ control, name: 'goals' });
  const { sectionRef, onMove } = useRowMover(move, 'goal');
  const full = fields.length >= CARE_PLAN_LIMITS.maxGoals;
  return (
    <View ref={sectionRef} style={styles.section}>
      <Text style={styles.sectionTitle} accessibilityRole="header" {...ariaLevel(3)}>Goals</Text>
      <Text style={styles.hint}>
        What the patient is working towards, in the order they should be shown.
        {editing ? ' A goal you remove is deleted with its progress when you save.' : ''}
      </Text>
      {fields.map((field, index) => {
        const status = field.goalId ? goalStatus.get(field.goalId) : undefined;
        return (
          <View key={field.id} style={styles.row} role="group" aria-label={`Goal ${index + 1}`}>
            <RowHeader
              title={`Goal ${index + 1}`}
              index={index}
              count={fields.length}
              noun="goal"
              onMove={onMove}
              onRemove={() => remove(index)}
              badge={status ? <StatusBadge label={GOAL_STATUS_LABEL[status]} tone={statusTone('goal', status)} /> : undefined}
            />
            <TextField
              control={control}
              name={`goals.${index}.description`}
              label="Goal"
              required
              multiline
              rows={2}
              maxLength={CARE_PLAN_LIMITS.itemDescription}
              showCount
            />
            <DateField
              control={control}
              name={`goals.${index}.targetDate`}
              label="Target date"
              hint="Optional"
              min={startDate || undefined}
            />
          </View>
        );
      })}
      {listError ? <FormError message={listError} /> : null}
      {full ? (
        <Text style={styles.hint}>A care plan can have at most {CARE_PLAN_LIMITS.maxGoals} goals.</Text>
      ) : (
        <Button label="Add goal" icon="add" variant="secondary" size="sm" onPress={() => append({ ...EMPTY_GOAL_ROW })} />
      )}
    </View>
  );
}

function InterventionRows({ control, listError }: { control: FormControl; listError?: string }) {
  const { fields, append, remove, move } = useFieldArray({ control, name: 'interventions' });
  const { sectionRef, onMove } = useRowMover(move, 'intervention');
  const full = fields.length >= CARE_PLAN_LIMITS.maxInterventions;
  return (
    <View ref={sectionRef} style={styles.section}>
      <Text style={styles.sectionTitle} accessibilityRole="header" {...ariaLevel(3)}>Interventions</Text>
      <Text style={styles.hint}>What will be done, how often and by whom.</Text>
      {fields.map((field, index) => (
        <View key={field.id} style={styles.row} role="group" aria-label={`Intervention ${index + 1}`}>
          <RowHeader
            title={`Intervention ${index + 1}`}
            index={index}
            count={fields.length}
            noun="intervention"
            onMove={onMove}
            onRemove={() => remove(index)}
          />
          <TextField
            control={control}
            name={`interventions.${index}.description`}
            label="Intervention"
            required
            multiline
            rows={2}
            maxLength={CARE_PLAN_LIMITS.itemDescription}
            showCount
          />
          <View style={styles.pair}>
            <View style={styles.half}>
              <TextField
                control={control}
                name={`interventions.${index}.frequency`}
                label="Frequency"
                hint="Optional, for example Daily"
                maxLength={CARE_PLAN_LIMITS.frequency}
              />
            </View>
            <View style={styles.half}>
              <TextField
                control={control}
                name={`interventions.${index}.responsible`}
                label="Responsible"
                hint="Optional, for example Patient"
                maxLength={CARE_PLAN_LIMITS.responsible}
              />
            </View>
          </View>
        </View>
      ))}
      {listError ? <FormError message={listError} /> : null}
      {full ? (
        <Text style={styles.hint}>A care plan can have at most {CARE_PLAN_LIMITS.maxInterventions} interventions.</Text>
      ) : (
        <Button
          label="Add intervention"
          icon="add"
          variant="secondary"
          size="sm"
          onPress={() => append({ ...EMPTY_INTERVENTION_ROW })}
        />
      )}
    </View>
  );
}

/**
 * The doctor's care-plan form, in a side panel: title, description, start, target and review dates, and row
 * editors for goals and interventions, validated like the backend. Without `plan` it creates a plan
 * (POST /api/care-plans); with `plan` it replaces that plan's content (PUT /api/care-plans/{id}), keeping the
 * progress of the goals that stay.
 *
 * Mount it only while the form is open: each mount is one "form open" with its own Idempotency-Key, which every
 * retry of the create repeats, so a create whose response was lost cannot produce a second plan.
 */
export function CarePlanForm({ plan, patientSystemId, consultationId, patients, onSaved, onClose, onStale }: CarePlanFormProps) {
  const editing = !!plan;
  const create = useCreateCarePlan();
  const update = useUpdateCarePlan();
  const pending = create.pending || update.pending;
  const [formError, setFormError] = useState<string | null>(null);
  // Set once the save succeeded: the owner closes the form, and until it does a second press must not submit again.
  const [done, setDone] = useState(false);

  const defaults = useMemo(
    () => carePlanFormDefaults(plan, { patientSystemId, startDate: clinicToday() }),
    // The form keeps the values it was opened with; a later refresh of the plan must not overwrite typing.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );
  const { control, handleSubmit, setError, watch, formState } = useForm<CarePlanFormValues>({
    resolver: zodResolver(editing ? editCarePlanSchema : createCarePlanSchema) as Resolver<CarePlanFormValues>,
    defaultValues: defaults,
  });
  const startDate = watch('startDate');

  const goalStatus = useMemo(
    () => new Map((plan?.goals ?? []).map(g => [g.goalId, g.status] as const)),
    [plan],
  );

  // A new plan is linked to a consultation only when the consultation's patient is known; the patient is then
  // fixed to them. Without a patient the consultation is ignored, so a plan for another patient is never sent
  // with a consultation that is not theirs.
  const linkedConsultation = !editing && consultationId && patientSystemId ? consultationId : null;
  // The patient cannot change on an existing plan, nor on a plan written from a consultation of that patient.
  const fixedPatient = editing ? plan.patientSystemId : linkedConsultation ? patientSystemId ?? null : null;
  const pickerOptions = useMemo<PickerOption<CarePlanPatientOption>[]>(
    () => patients.options.map(o => ({ id: o.patientSystemId, label: o.fullName || o.patientSystemId, item: o })),
    [patients.options],
  );

  const submit = handleSubmit(async values => {
    setFormError(null);
    const result = editing
      ? await update.run(plan.planId, toUpdateCarePlanRequest(values))
      : await create.run(toCreateCarePlanRequest(values, linkedConsultation));
    if (result.ok) {
      setDone(true);
      onSaved(result.data, editing ? 'edit' : 'create');
      return;
    }

    const failure = result.error;
    if (!failure) return;
    if (failure.code === 'IDEMPOTENCY_IN_PROGRESS') {
      setFormError('This care plan is still being saved. Wait a moment, then save again.');
      return;
    }
    if (editing && onStale && (failure.status === 403 || failure.status === 404 || failure.status === 409)) {
      onStale(failure.message);
      return;
    }
    const fields = carePlanServerFields(values.goals.length, values.interventions.length);
    setFormError(applyFieldErrors(
      withNamedField(failure, fields),
      setError as UseFormSetError<CarePlanFormValues>,
      fields,
    ));
  });

  const close = async () => {
    if (pending) return;
    if (formState.isDirty && !done) {
      const discard = await confirmAction(
        'Discard changes?',
        editing ? 'Your changes to this care plan have not been saved.' : 'This care plan has not been saved.',
        'Discard',
        true,
      );
      if (!discard) return;
    }
    onClose();
  };

  const errors = formState.errors;
  return (
    <SidePanel
      open
      title={editing ? 'Edit care plan' : 'New care plan'}
      size="lg"
      onClose={close}
      dismissOnBackdrop={false}
      busy={pending}
      footer={(
        <FormActions
          submitLabel={editing ? 'Save changes' : 'Create care plan'}
          onSubmit={submit}
          onCancel={close}
          submitting={pending}
          disabled={done}
        />
      )}
    >
      {fixedPatient ? (
        <InfoRow label="Patient" value={patients.nameOf(fixedPatient) ?? `Patient ${fixedPatient}`} />
      ) : (
        <Controller
          control={control}
          name="patientSystemId"
          render={({ field, fieldState }) => (
            <PickerShell<CarePlanPatientOption>
              label="Patient"
              value={field.value || null}
              onChange={id => field.onChange(id ?? '')}
              options={pickerOptions}
              loading={patients.loading}
              loadError={patients.error}
              onRetry={patients.refetch}
              emptyText="No patients yet"
              searchLabel="Search patients"
              required
              error={fieldState.error?.message}
              hint="Only patients in your care are listed."
            />
          )}
        />
      )}
      {linkedConsultation ? (
        <Banner tone="info" text="This care plan will be linked to the consultation it is written from." />
      ) : null}
      {editing && plan.consultationId ? (
        <Text style={styles.hint}>This care plan is linked to the consultation it was written from.</Text>
      ) : null}

      <TextField control={control} name="title" label="Title" required maxLength={CARE_PLAN_LIMITS.title} />
      <TextField
        control={control}
        name="description"
        label="Description"
        hint="Optional. What the plan is for and what was agreed."
        multiline
        rows={4}
        maxLength={CARE_PLAN_LIMITS.description}
        showCount
      />

      <View style={styles.pair}>
        <View style={styles.third}>
          <DateField control={control} name="startDate" label="Start date" required />
        </View>
        <View style={styles.third}>
          <DateField control={control} name="targetDate" label="Target date" hint="Optional. The planned end." min={startDate || undefined} />
        </View>
        <View style={styles.third}>
          <DateField control={control} name="reviewDate" label="Next review date" hint="Optional. When to review the plan." min={startDate || undefined} />
        </View>
      </View>

      <GoalRows
        control={control}
        startDate={startDate}
        goalStatus={goalStatus}
        editing={editing}
        listError={errors.goals?.message ?? errors.goals?.root?.message}
      />
      <InterventionRows
        control={control}
        listError={errors.interventions?.message ?? errors.interventions?.root?.message}
      />

      <FormError message={formError} />
    </SidePanel>
  );
}

const styles = StyleSheet.create({
  section:      { gap: space.sm, paddingTop: space.md, borderTopWidth: 1, borderTopColor: colors.border },
  sectionTitle: { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  hint:         { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  row: {
    gap: space.sm, padding: space.md, minWidth: 0,
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.bg,
  },
  rowHead:      { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  rowTitleWrap: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, flexShrink: 1, minWidth: 0 },
  rowTitle:     { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  rowButtons:   { flexDirection: 'row', alignItems: 'center', gap: space.xs },
  pair:         { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  half:         { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0 },
  third:        { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0 },
});
