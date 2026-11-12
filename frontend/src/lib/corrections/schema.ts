import { z } from 'zod';

// Form limits of correction requests, equal to the backend constraints (API contract be-corrections).

export const CORRECTION_TEXT_MAX = 2000;
export const CORRECTION_NOTE_MAX = 1000;

/** "Request a correction" form. */
export const correctionRequestSchema = z.object({
  requestedText: z
    .string()
    .trim()
    .min(1, 'Describe what should be corrected')
    .max(CORRECTION_TEXT_MAX, `Use at most ${CORRECTION_TEXT_MAX} characters`),
});
export type CorrectionRequestFormValues = z.infer<typeof correctionRequestSchema>;

/** Accept or reject form. The note is required for both decisions: it is what the requester reads. */
export const correctionResolveSchema = z.object({
  note: z
    .string()
    .trim()
    .min(1, 'Write a note for the person who asked')
    .max(CORRECTION_NOTE_MAX, `Use at most ${CORRECTION_NOTE_MAX} characters`),
});
export type CorrectionResolveFormValues = z.infer<typeof correctionResolveSchema>;
