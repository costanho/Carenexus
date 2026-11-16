// Care-plan DTOs, exactly as the API sends and accepts them (`/api/care-plans`, module careplans).
//
// Dates: `startDate`, `targetDate`, `reviewDate`, goal `targetDate` and `nextReviewDate` are calendar dates
// "yyyy-MM-dd" (handle them with `@/lib/ui/date-only`). `createdAt`, `updatedAt`, `lastReviewedAt` and goal
// `updatedAt` are clinic wall-clock date-times without an offset (format them with `formatClinicDateTime`).
// Names are not in the payload: the doctor is resolved with `resolveDoctorName`, the patient from the doctor's
// patient options or the caregiver's loved-ones list.

export type CarePlanStatus = 'ACTIVE' | 'ON_HOLD' | 'COMPLETED' | 'DISCONTINUED';
export type CarePlanGoalStatus = 'OPEN' | 'IN_PROGRESS' | 'ACHIEVED' | 'DROPPED';

export const CARE_PLAN_STATUSES: readonly CarePlanStatus[] = ['ACTIVE', 'ON_HOLD', 'COMPLETED', 'DISCONTINUED'];
export const CARE_PLAN_GOAL_STATUSES: readonly CarePlanGoalStatus[] = ['OPEN', 'IN_PROGRESS', 'ACHIEVED', 'DROPPED'];

export interface CarePlanGoal {
  /** Stable across an edit of the plan when the client sends it back. */
  goalId:       number;
  description:  string;
  targetDate:   string | null;
  status:       CarePlanGoalStatus;
  progressNote: string | null;
  /** 0-based order, equal to the array index. */
  position:     number;
  /** Creation time, then the time of the last change to this goal. */
  updatedAt:    string;
}

export interface CarePlanIntervention {
  /** Not stable across an edit of the plan: never keep it, use the array index as a row key. */
  interventionId: number;
  description:    string;
  frequency:      string | null;
  responsible:    string | null;
  position:       number;
}

/** `CarePlanResponse`: returned by every care-plan endpoint, always with its goals and interventions. */
export interface CarePlan {
  planId:          number;
  patientSystemId: string;
  /** The authoring doctor; the only doctor who may change the plan. */
  doctorSystemId:  string;
  consultationId:  number | null;
  title:           string;
  description:     string | null;
  status:          CarePlanStatus;
  startDate:       string;
  targetDate:      string | null;
  /** The NEXT review date; null when no review is scheduled. Kept as stored after the plan becomes terminal. */
  reviewDate:      string | null;
  lastReviewedAt:  string | null;
  lastReviewNote:  string | null;
  goals:           CarePlanGoal[];
  interventions:   CarePlanIntervention[];
  createdAt:       string;
  /** Last change of the plan itself (edit, status, review); a goal progress update does not change it. */
  updatedAt:       string;
}

// ─── Requests ─────────────────────────────────────────────────────────────────

export interface CarePlanInterventionInput {
  description:  string;
  frequency?:   string | null;
  responsible?: string | null;
}

export interface CreateCarePlanGoalInput {
  description: string;
  targetDate?: string | null;
}

/** Body of POST /api/care-plans. The plan is created ACTIVE with every goal OPEN. */
export interface CreateCarePlanRequest {
  patientSystemId: string;
  consultationId?: number | null;
  title:           string;
  description?:    string | null;
  startDate:       string;
  targetDate?:     string | null;
  reviewDate?:     string | null;
  goals:           CreateCarePlanGoalInput[];
  interventions:   CarePlanInterventionInput[];
}

export interface UpdateCarePlanGoalInput extends CreateCarePlanGoalInput {
  /** Id of an existing goal of this plan (keeps its status and progress note); absent for a new goal. */
  goalId?: number;
}

/**
 * Body of PUT /api/care-plans/{id}: a full replace of the editable content. A goal that is left out is removed;
 * interventions are replaced as a whole; an absent or null optional field is cleared.
 */
export interface UpdateCarePlanRequest {
  title:         string;
  description:   string | null;
  startDate:     string;
  targetDate:    string | null;
  reviewDate:    string | null;
  goals:         UpdateCarePlanGoalInput[];
  interventions: CarePlanInterventionInput[];
}

/** Body of PATCH /api/care-plans/{id}/review. A null `nextReviewDate` means no further review is scheduled. */
export interface ReviewCarePlanRequest {
  nextReviewDate: string | null;
  note:           string | null;
}

/**
 * Body of PATCH /api/care-plans/{id}/goals/{goalId}. A field that is left out is unchanged; an empty
 * `progressNote` clears the note. At least one of the two must be sent.
 */
export interface GoalProgressRequest {
  status?:       CarePlanGoalStatus;
  progressNote?: string;
}

/** Query filters of the two list endpoints. */
export interface CarePlanListFilter {
  status?:          CarePlanStatus | null;
  /** Doctor list only: plans the doctor wrote for this patient. */
  patientSystemId?: string | null;
}

/** One row of GET /api/doctors/patients/options (id and name of the doctor's own patients). */
export interface CarePlanPatientOption {
  patientSystemId: string;
  firstName:       string;
  lastName:        string;
  fullName:        string;
}
