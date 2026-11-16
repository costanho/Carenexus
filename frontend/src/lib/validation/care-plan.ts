import { z } from 'zod';
import type {
  CarePlan, CarePlanGoalStatus, CreateCarePlanRequest, GoalProgressRequest, ReviewCarePlanRequest,
  UpdateCarePlanRequest,
} from '@/lib/careplans/types';
import { isValidDateOnly } from '@/lib/ui/date-only';

// Form schemas for care plans, mirroring the backend constraints of /api/care-plans so that a form which passes
// here is accepted by the server: lengths, required fields, list sizes and the date rules (target, review and
// goal target dates are never before the start date; a next review date is today at the clinic or later).

export const CARE_PLAN_LIMITS = {
  title:            255,
  description:      4000,
  itemDescription:  500,
  frequency:        100,
  responsible:      100,
  note:             1000,
  maxGoals:         20,
  maxInterventions: 20,
} as const;

const SYSTEM_ID = /^(PAT|DOC|CGV|ADM)-\d{5,}$/;

/** An optional calendar date as the date input gives it: '' or "yyyy-MM-dd". */
const optionalDate = z.string().trim().refine(v => v === '' || isValidDateOnly(v), 'Enter a valid date');

const goalRow = z.object({
  /** Present for a goal that already exists on the plan: sent back so the goal keeps its status and note. */
  goalId:      z.number().int().positive().optional(),
  description: z.string().trim()
    .min(1, 'Describe the goal')
    .max(CARE_PLAN_LIMITS.itemDescription, `Must be at most ${CARE_PLAN_LIMITS.itemDescription} characters`),
  targetDate:  optionalDate,
});

const interventionRow = z.object({
  description: z.string().trim()
    .min(1, 'Describe the intervention')
    .max(CARE_PLAN_LIMITS.itemDescription, `Must be at most ${CARE_PLAN_LIMITS.itemDescription} characters`),
  frequency:   z.string().trim().max(CARE_PLAN_LIMITS.frequency, `Must be at most ${CARE_PLAN_LIMITS.frequency} characters`),
  responsible: z.string().trim().max(CARE_PLAN_LIMITS.responsible, `Must be at most ${CARE_PLAN_LIMITS.responsible} characters`),
});

const planFields = {
  title: z.string().trim()
    .min(1, 'Title is required')
    .max(CARE_PLAN_LIMITS.title, `Must be at most ${CARE_PLAN_LIMITS.title} characters`),
  description: z.string().trim()
    .max(CARE_PLAN_LIMITS.description, `Must be at most ${CARE_PLAN_LIMITS.description} characters`),
  startDate: z.string().trim()
    .min(1, 'Start date is required')
    .refine(v => v === '' || isValidDateOnly(v), 'Enter a valid date'),
  targetDate: optionalDate,
  reviewDate: optionalDate,
  goals: z.array(goalRow).max(CARE_PLAN_LIMITS.maxGoals, `A care plan can have at most ${CARE_PLAN_LIMITS.maxGoals} goals`),
  interventions: z.array(interventionRow)
    .max(CARE_PLAN_LIMITS.maxInterventions, `A care plan can have at most ${CARE_PLAN_LIMITS.maxInterventions} interventions`),
};

interface DatedPlan {
  startDate:  string;
  targetDate: string;
  reviewDate: string;
  goals:      { targetDate: string }[];
}

/** The cross-field date rules: nothing is planned before the plan starts. Dates compare as "yyyy-MM-dd" text. */
function checkDates(values: DatedPlan, ctx: z.RefinementCtx): void {
  const start = values.startDate;
  if (!isValidDateOnly(start)) return;
  if (isValidDateOnly(values.targetDate) && values.targetDate < start) {
    ctx.addIssue({ code: 'custom', path: ['targetDate'], message: 'Must not be before the start date' });
  }
  if (isValidDateOnly(values.reviewDate) && values.reviewDate < start) {
    ctx.addIssue({ code: 'custom', path: ['reviewDate'], message: 'Must not be before the start date' });
  }
  values.goals.forEach((goal, index) => {
    if (isValidDateOnly(goal.targetDate) && goal.targetDate < start) {
      ctx.addIssue({ code: 'custom', path: ['goals', index, 'targetDate'], message: 'Must not be before the start date' });
    }
  });
}

/** New plan: the patient is required. */
export const createCarePlanSchema = z.object({
  patientSystemId: z.string().trim().min(1, 'Choose a patient').regex(SYSTEM_ID, 'Choose a patient'),
  ...planFields,
}).superRefine(checkDates);

/** Editing a plan: the patient cannot be changed, so the field is carried but not checked. */
export const editCarePlanSchema = z.object({
  patientSystemId: z.string(),
  ...planFields,
}).superRefine(checkDates);

export type CarePlanFormValues = z.infer<typeof createCarePlanSchema>;
export type CarePlanGoalRow = CarePlanFormValues['goals'][number];
export type CarePlanInterventionRow = CarePlanFormValues['interventions'][number];

export const EMPTY_GOAL_ROW: CarePlanGoalRow = { description: '', targetDate: '' };
export const EMPTY_INTERVENTION_ROW: CarePlanInterventionRow = { description: '', frequency: '', responsible: '' };

/**
 * Starting values of the form: the plan being edited, or an empty plan that starts today at the clinic for
 * `patientSystemId` (the patient the screen is filtered to, when there is one).
 */
export function carePlanFormDefaults(
  plan: CarePlan | null | undefined,
  fallback: { patientSystemId?: string | null; startDate: string },
): CarePlanFormValues {
  if (!plan) {
    return {
      patientSystemId: fallback.patientSystemId ?? '',
      title: '', description: '', startDate: fallback.startDate, targetDate: '', reviewDate: '',
      goals: [], interventions: [],
    };
  }
  return {
    patientSystemId: plan.patientSystemId,
    title:           plan.title,
    description:     plan.description ?? '',
    startDate:       plan.startDate,
    targetDate:      plan.targetDate ?? '',
    reviewDate:      plan.reviewDate ?? '',
    goals: [...plan.goals]
      .sort((a, b) => a.position - b.position)
      .map(g => ({ goalId: g.goalId, description: g.description, targetDate: g.targetDate ?? '' })),
    interventions: [...plan.interventions]
      .sort((a, b) => a.position - b.position)
      .map(i => ({ description: i.description, frequency: i.frequency ?? '', responsible: i.responsible ?? '' })),
  };
}

const orNull = (value: string): string | null => (value.trim() === '' ? null : value.trim());

/** Body of POST /api/care-plans from validated form values. The row order is the display order. */
export function toCreateCarePlanRequest(values: CarePlanFormValues, consultationId?: number | null): CreateCarePlanRequest {
  return {
    patientSystemId: values.patientSystemId.trim(),
    ...(consultationId ? { consultationId } : {}),
    title:       values.title.trim(),
    description: orNull(values.description),
    startDate:   values.startDate,
    targetDate:  orNull(values.targetDate),
    reviewDate:  orNull(values.reviewDate),
    goals: values.goals.map(g => ({ description: g.description.trim(), targetDate: orNull(g.targetDate) })),
    interventions: values.interventions.map(i => ({
      description: i.description.trim(), frequency: orNull(i.frequency), responsible: orNull(i.responsible),
    })),
  };
}

/**
 * Body of PUT /api/care-plans/{id} from validated form values. Existing goals go back with their `goalId` so
 * they keep their status and progress note; a goal removed from the form is removed from the plan.
 */
export function toUpdateCarePlanRequest(values: CarePlanFormValues): UpdateCarePlanRequest {
  return {
    title:       values.title.trim(),
    description: orNull(values.description),
    startDate:   values.startDate,
    targetDate:  orNull(values.targetDate),
    reviewDate:  orNull(values.reviewDate),
    goals: values.goals.map(g => ({
      ...(g.goalId ? { goalId: g.goalId } : {}),
      description: g.description.trim(),
      targetDate:  orNull(g.targetDate),
    })),
    interventions: values.interventions.map(i => ({
      description: i.description.trim(), frequency: orNull(i.frequency), responsible: orNull(i.responsible),
    })),
  };
}

/** Form path of a care-plan field ("goals.0.description"), as react-hook-form names it. */
export type CarePlanFormPath =
  | 'patientSystemId' | 'title' | 'description' | 'startDate' | 'targetDate' | 'reviewDate' | 'goals' | 'interventions'
  | `goals.${number}.description` | `goals.${number}.targetDate`
  | `interventions.${number}.description` | `interventions.${number}.frequency` | `interventions.${number}.responsible`;

/**
 * Backend field names ("goals[0].description") to form paths, for `applyFieldErrors`. An error on a goal's id
 * (a goal that no longer belongs to the plan) is shown on that goal's description.
 */
export function carePlanServerFields(goalCount: number, interventionCount: number): Record<string, CarePlanFormPath> {
  const map: Record<string, CarePlanFormPath> = {
    patientSystemId: 'patientSystemId', title: 'title', description: 'description',
    startDate: 'startDate', targetDate: 'targetDate', reviewDate: 'reviewDate',
    goals: 'goals', interventions: 'interventions',
  };
  for (let i = 0; i < goalCount; i++) {
    map[`goals[${i}]`]             = `goals.${i}.description`;
    map[`goals[${i}].goalId`]      = `goals.${i}.description`;
    map[`goals[${i}].description`] = `goals.${i}.description`;
    map[`goals[${i}].targetDate`]  = `goals.${i}.targetDate`;
  }
  for (let i = 0; i < interventionCount; i++) {
    map[`interventions[${i}]`]             = `interventions.${i}.description`;
    map[`interventions[${i}].description`] = `interventions.${i}.description`;
    map[`interventions[${i}].frequency`]   = `interventions.${i}.frequency`;
    map[`interventions[${i}].responsible`] = `interventions.${i}.responsible`;
  }
  return map;
}

// ─── Mark reviewed ────────────────────────────────────────────────────────────

/**
 * The "mark reviewed" form. The next review date is optional (empty = no further review); when given it must
 * be `earliest` or later, where `earliest` is today at the clinic or the plan's start date, whichever is later.
 */
export function reviewCarePlanSchema(earliest: string) {
  return z.object({
    nextReviewDate: optionalDate.refine(
      v => v === '' || !isValidDateOnly(v) || v >= earliest,
      'Choose today or a later date, on or after the start of the plan',
    ),
    note: z.string().trim().max(CARE_PLAN_LIMITS.note, `Must be at most ${CARE_PLAN_LIMITS.note} characters`),
  });
}

export type ReviewCarePlanFormValues = z.infer<ReturnType<typeof reviewCarePlanSchema>>;

export function toReviewCarePlanRequest(values: ReviewCarePlanFormValues): ReviewCarePlanRequest {
  return { nextReviewDate: orNull(values.nextReviewDate), note: orNull(values.note) };
}

// ─── Goal progress ────────────────────────────────────────────────────────────

export const goalProgressSchema = z.object({
  status:       z.enum(['OPEN', 'IN_PROGRESS', 'ACHIEVED', 'DROPPED']),
  progressNote: z.string().trim().max(CARE_PLAN_LIMITS.note, `Must be at most ${CARE_PLAN_LIMITS.note} characters`),
});

export type GoalProgressFormValues = z.infer<typeof goalProgressSchema>;

/**
 * The fields that differ from the goal as it is now, or null when nothing changed (the server refuses an empty
 * update). An emptied note is sent as '' so the server clears it.
 */
export function toGoalProgressRequest(
  values: GoalProgressFormValues,
  current: { status: CarePlanGoalStatus; progressNote: string | null },
): GoalProgressRequest | null {
  const request: GoalProgressRequest = {};
  if (values.status !== current.status) request.status = values.status;
  const note = values.progressNote.trim();
  if (note !== (current.progressNote ?? '').trim()) request.progressNote = note;
  return request.status === undefined && request.progressNote === undefined ? null : request;
}
