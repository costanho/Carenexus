import { z } from 'zod';

// Limits of the medical record form. They mirror the backend request DTOs (CreateMedicalRecordRequest and
// UpdateMedicalRecordRequest, API contract be-medical-records sections 1.2 and 4), so a value the form accepts
// is a value the server accepts. The server stays the judge: its field errors are mapped back onto the form.

export const RECORD_TYPES = ['CONSULT', 'LAB', 'IMAGING', 'PRESCRIPTION', 'REFERRAL', 'DISCHARGE', 'NOTE', 'OPERATION'] as const;
export type MedicalRecordType = (typeof RECORD_TYPES)[number];

export const RECORD_TITLE_MAX = 200;
export const RECORD_TEXT_MAX = 10000;
export const RECORD_SEARCH_MAX = 100;

export const ICD_CODES_MAX = 20;
export const ICD_DESCRIPTION_MAX = 255;
export const ICD_QUERY_MAX = 100;

/** A well-formed ICD-10 code after trimming and upper-casing: a letter, a digit, a digit or letter, optionally a dot and 1 to 4 letters or digits. */
export const ICD_CODE_PATTERN = /^[A-Z][0-9][0-9A-Z](\.[0-9A-Z]{1,4})?$/;

/** System ids of people: PAT-00001, DOC-00002, ... */
export const SYSTEM_ID_PATTERN = /^(PAT|DOC|CGV|ADM)-\d{5,}$/;

/** The form in which the server stores a code: trimmed and upper case. */
export function normaliseIcdCode(code: string): string {
  return code.trim().toUpperCase();
}

export function isWellFormedIcdCode(code: string): boolean {
  return ICD_CODE_PATTERN.test(normaliseIcdCode(code));
}

export function isRecordType(value: unknown): value is MedicalRecordType {
  return typeof value === 'string' && (RECORD_TYPES as readonly string[]).includes(value);
}

const optionalText = (label: string) => z
  .string()
  .trim()
  .max(RECORD_TEXT_MAX, `${label} must be at most ${RECORD_TEXT_MAX} characters`);

export const icdCodeRowSchema = z.object({
  code: z
    .string()
    .trim()
    .min(1, 'Enter a code')
    .transform(normaliseIcdCode)
    .refine(code => ICD_CODE_PATTERN.test(code), 'Not a valid ICD-10 code (for example J06.9)'),
  description: z
    .string()
    .trim()
    .max(ICD_DESCRIPTION_MAX, `Description must be at most ${ICD_DESCRIPTION_MAX} characters`),
  /** Form-only: whether the code came from the catalogue. Never sent; the server computes it on every read. */
  verified: z.boolean().optional(),
});

const icdCodesSchema = z
  .array(icdCodeRowSchema)
  .max(ICD_CODES_MAX, `Add at most ${ICD_CODES_MAX} codes`)
  .refine(
    rows => new Set(rows.map(r => r.code)).size === rows.length,
    'The same code is listed twice',
  );

/** Fields a doctor may change on an existing record. */
const editableFields = {
  recordType: z
    .string()
    .min(1, 'Choose a record type')
    .refine(value => isRecordType(value), 'Choose a record type'),
  title: z
    .string()
    .trim()
    .min(1, 'Title is required')
    .max(RECORD_TITLE_MAX, `Title must be at most ${RECORD_TITLE_MAX} characters`),
  description:   optionalText('Description'),
  observations:  optionalText('Observations'),
  treatmentPlan: optionalText('Treatment plan'),
  icdCodes:      icdCodesSchema,
};

/** Schema of the "New record" form. `consultationId` is '' (none) or the id chosen in the consultation select. */
export const createMedicalRecordSchema = z.object({
  patientSystemId: z
    .string()
    .min(1, 'Choose a patient')
    .regex(SYSTEM_ID_PATTERN, 'Choose a patient'),
  consultationId: z
    .string()
    .refine(v => v === '' || /^[1-9]\d*$/.test(v), 'Choose a consultation from the list'),
  ...editableFields,
});

/** Schema of the "Edit record" form: patient and consultation cannot change after a record is filed. */
export const updateMedicalRecordSchema = z.object({
  patientSystemId: z.string(),
  consultationId:  z.string(),
  ...editableFields,
});

/** One ICD code row of the form. `verified` is false for a code typed by hand that the catalogue does not list. */
export interface IcdCodeRow {
  code:        string;
  description: string;
  verified?:   boolean;
}

/** Values of the record form (both modes share one shape so one component renders them). */
export interface MedicalRecordFormValues {
  patientSystemId: string;
  consultationId:  string;
  recordType:      string;
  title:           string;
  description:     string;
  observations:    string;
  treatmentPlan:   string;
  icdCodes:        IcdCodeRow[];
}

export const EMPTY_RECORD_FORM: MedicalRecordFormValues = {
  patientSystemId: '',
  consultationId:  '',
  recordType:      '',
  title:           '',
  description:     '',
  observations:    '',
  treatmentPlan:   '',
  icdCodes:        [],
};

/** Search box of the records list: trimmed, at most 100 characters (the `q` parameter). */
export function normaliseRecordSearch(text: string): string {
  return text.trim().slice(0, RECORD_SEARCH_MAX);
}
