import { z } from 'zod';
import { clinicToday } from '@/lib/time/clinic';
import { isValidDateOnly } from '@/lib/ui/date-only';

// Lab and imaging results: the enums and limits of the backend (LabResultController / ImagingResultController
// request DTOs) and the zod schemas of the doctor forms. Every limit here equals the server's Bean Validation
// constraint, so a value the form accepts is not rejected for its length or format.

// ─── Enums ────────────────────────────────────────────────────────────────────

export const RESULT_STATUSES = ['PENDING', 'REQUIRES_REVIEW', 'COMPLETED', 'CRITICAL', 'CANCELLED'] as const;
export type ResultStatus = (typeof RESULT_STATUSES)[number];

/** Statuses a create, edit or status change may send. CANCELLED is reached only by cancelling the result. */
export const WRITABLE_RESULT_STATUSES = ['PENDING', 'REQUIRES_REVIEW', 'COMPLETED', 'CRITICAL'] as const;
export type WritableResultStatus = (typeof WRITABLE_RESULT_STATUSES)[number];

/** Outcomes of a review (sign-off). */
export const REVIEW_STATUSES = ['COMPLETED', 'CRITICAL'] as const;
export type ReviewStatus = (typeof REVIEW_STATUSES)[number];

export const LAB_TEST_TYPES = ['BLOOD', 'URINE', 'STOOL', 'CULTURE', 'BIOPSY', 'GENETIC', 'SWAB', 'OTHER'] as const;
export type LabTestType = (typeof LAB_TEST_TYPES)[number];

export const IMAGING_TYPES = ['XRAY', 'MRI', 'CT_SCAN', 'ULTRASOUND', 'PET', 'MAMMOGRAPHY', 'DEXA', 'ECHO'] as const;
export type ImagingType = (typeof IMAGING_TYPES)[number];

/** Sub-test flags the record form offers. A stored result may carry any other upper-case flag. */
export const LAB_FLAGS = ['NORMAL', 'LOW', 'HIGH', 'CRITICAL'] as const;

export const RESULT_STATUS_LABELS: Record<ResultStatus, string> = {
  PENDING:         'Pending',
  REQUIRES_REVIEW: 'Requires review',
  COMPLETED:       'Completed',
  CRITICAL:        'Critical',
  CANCELLED:       'Cancelled',
};

export const LAB_TEST_TYPE_LABELS: Record<LabTestType, string> = {
  BLOOD:   'Blood',
  URINE:   'Urine',
  STOOL:   'Stool',
  CULTURE: 'Culture',
  BIOPSY:  'Biopsy',
  GENETIC: 'Genetic',
  SWAB:    'Swab',
  OTHER:   'Other',
};

export const IMAGING_TYPE_LABELS: Record<ImagingType, string> = {
  XRAY:        'X-ray',
  MRI:         'MRI',
  CT_SCAN:     'CT scan',
  ULTRASOUND:  'Ultrasound',
  PET:         'PET scan',
  MAMMOGRAPHY: 'Mammography',
  DEXA:        'DEXA (bone density)',
  ECHO:        'Echocardiogram',
};

function oneOf<T extends string>(list: readonly T[]) {
  return (value: unknown): value is T => typeof value === 'string' && (list as readonly string[]).includes(value);
}

export const isResultStatus         = oneOf(RESULT_STATUSES);
export const isWritableResultStatus = oneOf(WRITABLE_RESULT_STATUSES);
export const isReviewStatus         = oneOf(REVIEW_STATUSES);
export const isLabTestType          = oneOf(LAB_TEST_TYPES);
export const isImagingType          = oneOf(IMAGING_TYPES);

// ─── Limits (characters) ──────────────────────────────────────────────────────

export const LAB_LIMITS = {
  testName:       200,
  resultValue:    500,
  unit:           50,
  referenceRange: 100,
  /** Length of the serialised `resultData` JSON text. */
  resultData:     20000,
} as const;

/** Limits of one structured sub-test row. They keep a row readable; the server limits only the whole JSON text. */
export const SUB_TEST_LIMITS = {
  name:           100,
  value:          100,
  unit:           50,
  reference:      100,
  flag:           30,
  interpretation: 500,
} as const;

export const IMAGING_LIMITS = {
  bodyPart:          200,
  radiologistReport: 10000,
  findings:          5000,
  impression:        5000,
} as const;

export const REVIEW_NOTES_MAX = 2000;

/** `^(PAT|DOC|CGV|ADM)-\d{5,}$`, the server's system-id pattern. */
export const SYSTEM_ID_PATTERN = /^(PAT|DOC|CGV|ADM)-\d{5,}$/;

// ─── Shared field schemas ─────────────────────────────────────────────────────

const atMost = (max: number) => `Must be at most ${max} characters`;

const patientSystemId = z.string()
  .min(1, 'Choose a patient')
  .regex(SYSTEM_ID_PATTERN, 'Choose a patient from the list');

/** '' (no consultation) or the id of a consultation chosen from the list. */
const consultationId = z.string().regex(/^(|[1-9]\d{0,9})$/, 'Choose a consultation from the list');

// The guards are wrapped in functions that return a plain boolean, so the schemas keep `string` as the field
// type: a select starts empty ('') and the form values must be able to hold that.
const writableStatus = z.string().refine((value): boolean => isWritableResultStatus(value), 'Choose a status');

/** A calendar day "YYYY-MM-DD" that is not after today at the clinic. */
function pastOrToday(label: string) {
  return z.string()
    .min(1, `${label} is required`)
    .refine((value): boolean => isValidDateOnly(value), 'Enter a valid date')
    .refine(value => !isValidDateOnly(value) || value <= clinicToday(), `${label} must not be in the future`);
}

// ─── Lab result form ──────────────────────────────────────────────────────────

export const labSubTestSchema = z.object({
  name:           z.string().trim().min(1, 'Name is required').max(SUB_TEST_LIMITS.name, atMost(SUB_TEST_LIMITS.name)),
  value:          z.string().trim().min(1, 'Value is required').max(SUB_TEST_LIMITS.value, atMost(SUB_TEST_LIMITS.value)),
  unit:           z.string().trim().max(SUB_TEST_LIMITS.unit, atMost(SUB_TEST_LIMITS.unit)),
  reference:      z.string().trim().max(SUB_TEST_LIMITS.reference, atMost(SUB_TEST_LIMITS.reference)),
  /** '' (no flag) or an upper-case flag. */
  flag:           z.string().trim().max(SUB_TEST_LIMITS.flag, atMost(SUB_TEST_LIMITS.flag)),
  interpretation: z.string().trim().max(SUB_TEST_LIMITS.interpretation, atMost(SUB_TEST_LIMITS.interpretation)),
});

export type LabSubTestFormValues = z.infer<typeof labSubTestSchema>;

export const EMPTY_SUB_TEST: LabSubTestFormValues = {
  name: '', value: '', unit: '', reference: '', flag: '', interpretation: '',
};

export const labResultFormSchema = z.object({
  patientSystemId,
  consultationId,
  testName:       z.string().trim().min(1, 'Test name is required').max(LAB_LIMITS.testName, atMost(LAB_LIMITS.testName)),
  testType:       z.string().refine((value): boolean => isLabTestType(value), 'Choose a test type'),
  resultValue:    z.string().trim().max(LAB_LIMITS.resultValue, atMost(LAB_LIMITS.resultValue)),
  unit:           z.string().trim().max(LAB_LIMITS.unit, atMost(LAB_LIMITS.unit)),
  referenceRange: z.string().trim().max(LAB_LIMITS.referenceRange, atMost(LAB_LIMITS.referenceRange)),
  subTests:       z.array(labSubTestSchema),
  status:         writableStatus,
  isCritical:     z.boolean(),
  testDate:       pastOrToday('Test date'),
}).superRefine((values, ctx) => {
  // Sub-test names become the keys of the stored JSON object, so each must be unique.
  const seen = new Set<string>();
  values.subTests.forEach((row, index) => {
    const key = row.name.trim().toLowerCase();
    if (!key) return;
    if (seen.has(key)) {
      ctx.addIssue({ code: 'custom', message: 'This name is already used by another row', path: ['subTests', index, 'name'] });
    }
    seen.add(key);
  });
});

export type LabResultFormValues = z.infer<typeof labResultFormSchema>;

// ─── Imaging result form ──────────────────────────────────────────────────────

export const imagingResultFormSchema = z.object({
  patientSystemId,
  consultationId,
  imagingType:       z.string().refine((value): boolean => isImagingType(value), 'Choose an imaging type'),
  bodyPart:          z.string().trim().max(IMAGING_LIMITS.bodyPart, atMost(IMAGING_LIMITS.bodyPart)),
  findings:          z.string().trim().max(IMAGING_LIMITS.findings, atMost(IMAGING_LIMITS.findings)),
  impression:        z.string().trim().max(IMAGING_LIMITS.impression, atMost(IMAGING_LIMITS.impression)),
  radiologistReport: z.string().trim().max(IMAGING_LIMITS.radiologistReport, atMost(IMAGING_LIMITS.radiologistReport)),
  status:            writableStatus,
  imageDate:         pastOrToday('Image date'),
});

export type ImagingResultFormValues = z.infer<typeof imagingResultFormSchema>;

// ─── Review (sign-off) form ───────────────────────────────────────────────────

export const reviewResultFormSchema = z.object({
  status:      z.string().refine((value): boolean => isReviewStatus(value), 'Choose the outcome of the review'),
  reviewNotes: z.string().trim().max(REVIEW_NOTES_MAX, atMost(REVIEW_NOTES_MAX)),
});

export type ReviewResultFormValues = z.infer<typeof reviewResultFormSchema>;
