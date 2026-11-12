import { z } from 'zod';
import {
  type AssignSpecialistBody, type CreateReferralBody, REFERRAL_LIMITS, type ReferralAction,
} from '@/lib/referrals/types';

// Form schemas of the referral screens. They mirror the backend constraints of `api/be-referrals.md`
// (CreateReferralRequest, AssignReferralSpecialistRequest, ReferralOutcomeRequest, DeclineReferralRequest) so a
// form that passes here is not refused for a field rule on the server.

/** The backend system-id pattern (`ValidationPatterns.SYSTEM_ID`). */
export const SYSTEM_ID_PATTERN = /^(PAT|DOC|CGV|ADM)-\d{5,}$/;

const L = REFERRAL_LIMITS;

function atMost(max: number): string {
  return `Must be at most ${max} characters`;
}

/**
 * Who the referral goes to, as the form asks it:
 * NONE = decide later (unassigned), PLATFORM = a doctor on CareNexus, EXTERNAL = a specialist outside it.
 */
export const SPECIALIST_MODES = ['NONE', 'PLATFORM', 'EXTERNAL'] as const;
export type SpecialistMode = (typeof SPECIALIST_MODES)[number];

const specialistFields = {
  specialistSystemId:             z.string(),
  externalSpecialistName:         z.string().trim().max(L.externalSpecialist, atMost(L.externalSpecialist)),
  externalSpecialistOrganisation: z.string().trim().max(L.externalSpecialist, atMost(L.externalSpecialist)),
  externalSpecialistContact:      z.string().trim().max(L.externalSpecialist, atMost(L.externalSpecialist)),
};

interface SpecialistChoice {
  specialistMode:         SpecialistMode;
  specialistSystemId:     string;
  externalSpecialistName: string;
}

/** The rule shared by the create and assign forms: the chosen kind of specialist must be filled in. */
function checkSpecialist(value: SpecialistChoice, ctx: z.RefinementCtx): void {
  if (value.specialistMode === 'PLATFORM' && !SYSTEM_ID_PATTERN.test(value.specialistSystemId.trim())) {
    ctx.addIssue({ code: 'custom', path: ['specialistSystemId'], message: 'Choose a doctor' });
  }
  if (value.specialistMode === 'EXTERNAL' && !value.externalSpecialistName.trim()) {
    ctx.addIssue({ code: 'custom', path: ['externalSpecialistName'], message: "Enter the specialist's name" });
  }
}

// ─── New referral ─────────────────────────────────────────────────────────────

export const createReferralSchema = z
  .object({
    patientSystemId: z.string().trim().min(1, 'Choose a patient').regex(SYSTEM_ID_PATTERN, 'Choose a patient'),
    reason:          z.string().trim().min(1, 'Enter the reason for the referral').max(L.reason, atMost(L.reason)),
    clinicalSummary: z.string().trim().max(L.clinicalSummary, atMost(L.clinicalSummary)),
    priority:        z.enum(['ROUTINE', 'URGENT', 'EMERGENCY']),
    specialistMode:  z.enum(SPECIALIST_MODES),
    ...specialistFields,
  })
  .superRefine(checkSpecialist);

export type CreateReferralValues = z.infer<typeof createReferralSchema>;

export const CREATE_REFERRAL_DEFAULTS: CreateReferralValues = {
  patientSystemId: '',
  reason: '',
  clinicalSummary: '',
  priority: 'ROUTINE',
  specialistMode: 'NONE',
  specialistSystemId: '',
  externalSpecialistName: '',
  externalSpecialistOrganisation: '',
  externalSpecialistContact: '',
};

/** Backend field name to form field name, for `applyFieldErrors`. */
export const CREATE_REFERRAL_FIELDS = {
  patientSystemId: 'patientSystemId',
  reason: 'reason',
  clinicalSummary: 'clinicalSummary',
  priority: 'priority',
  specialistSystemId: 'specialistSystemId',
  externalSpecialistName: 'externalSpecialistName',
  externalSpecialistOrganisation: 'externalSpecialistOrganisation',
  externalSpecialistContact: 'externalSpecialistContact',
} as const;

function blankToNull(value: string): string | null {
  const text = value.trim();
  return text ? text : null;
}

/** The fields of the chosen kind of specialist only, so the server never receives both kinds at once. */
function specialistBody(values: {
  specialistMode: SpecialistMode;
  specialistSystemId: string;
  externalSpecialistName: string;
  externalSpecialistOrganisation: string;
  externalSpecialistContact: string;
}): Pick<CreateReferralBody, 'specialistSystemId' | 'externalSpecialistName' | 'externalSpecialistOrganisation' | 'externalSpecialistContact'> {
  if (values.specialistMode === 'PLATFORM') return { specialistSystemId: values.specialistSystemId.trim() };
  if (values.specialistMode === 'EXTERNAL') {
    return {
      externalSpecialistName:         values.externalSpecialistName.trim(),
      externalSpecialistOrganisation: blankToNull(values.externalSpecialistOrganisation),
      externalSpecialistContact:      blankToNull(values.externalSpecialistContact),
    };
  }
  return {};
}

/** The request body of POST /api/referrals for validated form values. */
export function toCreateReferralBody(values: CreateReferralValues, consultationId: number | null): CreateReferralBody {
  return {
    patientSystemId: values.patientSystemId.trim(),
    reason:          values.reason.trim(),
    clinicalSummary: blankToNull(values.clinicalSummary),
    priority:        values.priority,
    ...specialistBody(values),
    ...(consultationId != null ? { consultationId } : {}),
  };
}

// ─── Assign specialist ────────────────────────────────────────────────────────

export const assignSpecialistSchema = z
  .object({
    // The specialist cannot be cleared once set, so "decide later" is not offered here.
    specialistMode: z.enum(['PLATFORM', 'EXTERNAL']),
    ...specialistFields,
  })
  .superRefine(checkSpecialist);

export type AssignSpecialistValues = z.infer<typeof assignSpecialistSchema>;

export const ASSIGN_SPECIALIST_FIELDS = {
  specialistSystemId: 'specialistSystemId',
  externalSpecialistName: 'externalSpecialistName',
  externalSpecialistOrganisation: 'externalSpecialistOrganisation',
  externalSpecialistContact: 'externalSpecialistContact',
} as const;

/** The request body of PATCH /api/referrals/{id}/specialist: exactly one kind of specialist. */
export function toAssignSpecialistBody(values: AssignSpecialistValues): AssignSpecialistBody {
  if (values.specialistMode === 'PLATFORM') return { specialistSystemId: values.specialistSystemId.trim() };
  return {
    externalSpecialistName:         values.externalSpecialistName.trim(),
    externalSpecialistOrganisation: blankToNull(values.externalSpecialistOrganisation),
    externalSpecialistContact:      blankToNull(values.externalSpecialistContact),
  };
}

// ─── Accept, decline, complete ────────────────────────────────────────────────

/** The outcomes a responder can record. */
export const OUTCOME_ACTIONS = ['ACCEPT', 'DECLINE', 'COMPLETE'] as const satisfies readonly ReferralAction[];
export type OutcomeAction = (typeof OUTCOME_ACTIONS)[number];

/** Longest text the server stores for an outcome: the decline reason is shorter than an outcome note. */
export function outcomeTextLimit(action: OutcomeAction): number {
  return action === 'DECLINE' ? L.declineReason : L.outcomeNote;
}

/**
 * One schema for the three outcome dialogs. `text` is the decline reason (always required) or the note of an
 * accept or complete (required only when it is recorded on the specialist's behalf).
 */
export function outcomeSchema(noteRequired: boolean) {
  return z
    .object({
      action: z.enum(OUTCOME_ACTIONS),
      text:   z.string().trim(),
    })
    .superRefine((value, ctx) => {
      const max = outcomeTextLimit(value.action);
      if (value.text.length > max) {
        ctx.addIssue({ code: 'custom', path: ['text'], message: atMost(max) });
        return;
      }
      if (value.text) return;
      if (value.action === 'DECLINE') {
        ctx.addIssue({ code: 'custom', path: ['text'], message: 'Enter the reason for declining' });
      } else if (noteRequired) {
        ctx.addIssue({ code: 'custom', path: ['text'], message: "A note is required when you record the outcome on the specialist's behalf" });
      }
    });
}

export type OutcomeValues = z.infer<ReturnType<typeof outcomeSchema>>;

/** Backend field names of the outcome bodies (`note`, `reason`) both land on the form's single text field. */
export const OUTCOME_FIELDS = { note: 'text', reason: 'text' } as const;
