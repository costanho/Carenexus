import { clinicToday } from '@/lib/time/clinic';
import {
  CARE_PLAN_STATUSES,
  type CarePlan, type CarePlanGoal, type CarePlanGoalStatus, type CarePlanStatus,
} from './types';

// The care-plan rules the screens derive from the payload (who sees which control, what a status may become,
// when a review is due). The server enforces the same rules on every request; these only decide what to show.

export const PLAN_STATUS_LABEL: Record<CarePlanStatus, string> = {
  ACTIVE:       'Active',
  ON_HOLD:      'On hold',
  COMPLETED:    'Completed',
  DISCONTINUED: 'Discontinued',
};

export const GOAL_STATUS_LABEL: Record<CarePlanGoalStatus, string> = {
  OPEN:        'Open',
  IN_PROGRESS: 'In progress',
  ACHIEVED:    'Achieved',
  DROPPED:     'Dropped',
};

/** Reads a status from a URL parameter or a select; null for anything that is not a plan status. */
export function toCarePlanStatus(value: string | null | undefined): CarePlanStatus | null {
  const key = (value ?? '').trim().toUpperCase();
  return (CARE_PLAN_STATUSES as readonly string[]).includes(key) ? (key as CarePlanStatus) : null;
}

/** Reads a numeric id from a URL parameter (`id`, `consultationId`); null unless it is a positive whole number. */
export function toPositiveId(value: string | null | undefined): number | null {
  const raw = (value ?? '').trim();
  if (!/^\d{1,9}$/.test(raw)) return null;
  const id = Number(raw);
  return id > 0 ? id : null;
}

/** Reads a plan id from the `id` URL parameter. */
export const toPlanId = toPositiveId;

/** Reads a patient system id from the `patient` URL parameter; null unless it has the PAT-... form. */
export function toPatientSystemId(value: string | null | undefined): string | null {
  const raw = (value ?? '').trim();
  return /^PAT-\d{5,}$/.test(raw) ? raw : null;
}

/** A completed or discontinued plan is read-only for everyone. */
export function isTerminalStatus(status: CarePlanStatus): boolean {
  return status === 'COMPLETED' || status === 'DISCONTINUED';
}

export function isTerminalPlan(plan: Pick<CarePlan, 'status'>): boolean {
  return isTerminalStatus(plan.status);
}

/** The statuses the author doctor may move a plan to. Completed and discontinued plans cannot be changed. */
export function nextPlanStatuses(status: CarePlanStatus): CarePlanStatus[] {
  switch (status) {
    case 'ACTIVE':  return ['ON_HOLD', 'COMPLETED', 'DISCONTINUED'];
    case 'ON_HOLD': return ['ACTIVE', 'COMPLETED', 'DISCONTINUED'];
    default:        return [];
  }
}

/**
 * True when the plan's next review date has been reached, by the clinic's calendar (never the device clock).
 * Only plans that are still running can be due: a finished plan keeps its stored date.
 */
export function isReviewDue(plan: Pick<CarePlan, 'status' | 'reviewDate'>, today: string = clinicToday()): boolean {
  return !isTerminalPlan(plan) && !!plan.reviewDate && plan.reviewDate <= today;
}

/** The earliest next review date the server accepts: today at the clinic, and not before the plan starts. */
export function earliestNextReviewDate(plan: Pick<CarePlan, 'startDate'>, today: string = clinicToday()): string {
  return plan.startDate > today ? plan.startDate : today;
}

/** True when this doctor wrote the plan (only the author may edit it, change its status or record a review). */
export function isPlanAuthor(plan: Pick<CarePlan, 'doctorSystemId'>, doctorSystemId: string | null | undefined): boolean {
  return !!doctorSystemId && plan.doctorSystemId === doctorSystemId;
}

/** Edit, change status and mark reviewed are offered to the author while the plan is active or on hold. */
export function canChangePlan(plan: Pick<CarePlan, 'doctorSystemId' | 'status'>, doctorSystemId: string | null | undefined): boolean {
  return isPlanAuthor(plan, doctorSystemId) && !isTerminalPlan(plan);
}

/** Who is updating goal progress. A caregiver is passed only when the grant allows managing the care plan. */
export type GoalActor = 'patient' | 'caregiver' | 'doctor';

/**
 * Whether this person may update the goal. Nobody may on a finished plan; a dropped goal is read-only for the
 * patient and caregivers (only the author doctor drops a goal or brings it back).
 */
export function canUpdateGoal(
  plan: Pick<CarePlan, 'status'>,
  goal: Pick<CarePlanGoal, 'status'>,
  actor: GoalActor | null | undefined,
): boolean {
  if (!actor || isTerminalPlan(plan)) return false;
  return actor === 'doctor' || goal.status !== 'DROPPED';
}

/** The statuses this person may set on a goal. */
export function goalStatusChoices(actor: GoalActor): CarePlanGoalStatus[] {
  return actor === 'doctor' ? ['OPEN', 'IN_PROGRESS', 'ACHIEVED', 'DROPPED'] : ['OPEN', 'IN_PROGRESS', 'ACHIEVED'];
}

/** "2 of 5 goals achieved", counting only goals that are still part of the plan (not dropped). */
export function goalSummary(goals: readonly Pick<CarePlanGoal, 'status'>[]): string {
  const kept = goals.filter(g => g.status !== 'DROPPED');
  if (goals.length === 0) return 'No goals';
  if (kept.length === 0) return goals.length === 1 ? '1 goal, dropped' : `${goals.length} goals, all dropped`;
  const achieved = kept.filter(g => g.status === 'ACHIEVED').length;
  return `${achieved} of ${kept.length} ${kept.length === 1 ? 'goal' : 'goals'} achieved`;
}
