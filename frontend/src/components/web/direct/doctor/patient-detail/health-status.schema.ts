import { z } from 'zod';
import { HEALTH_STATUS_NOTE_MAX } from '@/hooks/use-doctor-patient-detail';

// Mirrors UpdateHealthStatusRequest (api/be-doctor-directory-patients.md 4.5): the status is one of the three
// values, the note is optional and at most 500 characters after trimming. The messages are the server's own.

export const healthStatusSchema = z.object({
  healthStatus: z.enum(['STABLE', 'MONITOR', 'CRITICAL'], { error: 'Health status is required' }),
  note: z.string().trim().max(HEALTH_STATUS_NOTE_MAX, `Note must be at most ${HEALTH_STATUS_NOTE_MAX} characters`),
});

export type HealthStatusFormValues = z.infer<typeof healthStatusSchema>;
