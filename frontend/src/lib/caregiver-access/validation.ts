import { z } from 'zod';
import { clinicToday } from '@/lib/time/clinic';
import { isValidDateOnly } from '@/lib/ui/date-only';

// Client-side rules for the caregiver access forms, mirroring the backend constraints
// (GrantCaregiverAccessRequest, UpdateDependentAccessRequest, ApproveAccessRequestRequest,
// DeclineAccessRequestRequest).

export const CAREGIVER_EMAIL_MAX = 150;
export const RELATIONSHIP_MAX = 80;
export const DECLINE_REASON_MAX = 500;

/**
 * The problem with an "access ends on" value, or null when it can be sent. Empty means no end date. A date is the
 * end of that clinic day, so today is still in the future; the comparison is with the clinic's calendar, not the
 * device's.
 */
export function expiryDateError(value: string | null | undefined, today: string = clinicToday()): string | null {
  const v = (value ?? '').trim();
  if (!v) return null;
  if (!isValidDateOnly(v)) return 'Enter a date in the format YYYY-MM-DD.';
  if (v < today) return 'The end date must be today or later.';
  return null;
}

const expiresAt = z.string().trim().superRefine((value, ctx) => {
  const problem = expiryDateError(value);
  if (problem) ctx.addIssue({ code: 'custom', message: problem });
});

/** The text fields of the "Grant access" form. The level and the permission switches are always valid. */
export const grantAccessSchema = z.object({
  caregiverEmail: z.string().trim()
    .min(1, 'Enter the email of their caregiver account.')
    .max(CAREGIVER_EMAIL_MAX, `Use at most ${CAREGIVER_EMAIL_MAX} characters.`)
    .email('Enter a valid email address.'),
  relationship: z.string().trim()
    .min(1, 'Enter how this person is related to you.')
    .max(RELATIONSHIP_MAX, `Use at most ${RELATIONSHIP_MAX} characters.`),
  expiresAt,
});

export type GrantAccessFormValues = z.infer<typeof grantAccessSchema>;

/** The problem with a decline reason, or null. The reason is optional. */
export function declineReasonError(value: string | null | undefined): string | null {
  return (value ?? '').trim().length > DECLINE_REASON_MAX
    ? `Use at most ${DECLINE_REASON_MAX} characters.`
    : null;
}
