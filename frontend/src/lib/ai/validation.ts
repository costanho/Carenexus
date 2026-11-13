import { z } from 'zod';

// Form schema of the detection review (POST /api/disease-detections/{detectionId}/review), mirroring
// ReviewDetectionRequest: `notes` is optional for both decisions and at most 2000 characters after trimming.

export const REVIEW_NOTES_MAX = 2000;

export const reviewDetectionSchema = z.object({
  notes: z.string().trim().max(REVIEW_NOTES_MAX, `Notes can be at most ${REVIEW_NOTES_MAX} characters`),
});

export type ReviewDetectionForm = z.infer<typeof reviewDetectionSchema>;
