import { z } from 'zod';
import { isValidDateOnly } from '@/lib/ui/date-only';

// Form rules of the doctor's consultation editor. They mirror the backend constraints of
// POST /api/consultations and PUT /api/consultations/{id}: every note field at most 10000 characters,
// duration 1 to 480 minutes, a follow-up date when a follow-up is required and not before today (clinic date).

export const CONSULTATION_TEXT_MAX = 10000;
export const CONSULTATION_DURATION_MIN = 1;
export const CONSULTATION_DURATION_MAX = 480;

/** The note fields, in the order the editor shows them. Names equal the backend JSON names. */
export const CONSULTATION_TEXT_FIELDS = [
  'chiefComplaint',
  'historyOfPresentIllness',
  'physicalExamination',
  'diagnosis',
  'treatmentPlan',
  'followUpInstructions',
  'clinicalNotes',
] as const;

export type ConsultationTextField = (typeof CONSULTATION_TEXT_FIELDS)[number];

export interface ConsultationFormValues {
  chiefComplaint:          string;
  historyOfPresentIllness: string;
  physicalExamination:     string;
  diagnosis:               string;
  treatmentPlan:           string;
  followUpInstructions:    string;
  clinicalNotes:           string;
  followUpRequired:        boolean;
  /** "YYYY-MM-DD", or '' when not chosen. Only meaningful while `followUpRequired` is on. */
  followUpDate:            string;
  /** Whole minutes typed by the doctor, or '' when not recorded. */
  durationMinutes:         string;
}

export type ConsultationFormField = keyof ConsultationFormValues;

export const CONSULTATION_FORM_FIELDS: readonly ConsultationFormField[] = [
  ...CONSULTATION_TEXT_FIELDS, 'followUpRequired', 'followUpDate', 'durationMinutes',
];

export const CONSULTATION_FIELD_LABELS: Record<ConsultationFormField, string> = {
  chiefComplaint:          'Chief complaint',
  historyOfPresentIllness: 'History of present illness',
  physicalExamination:     'Physical examination',
  diagnosis:               'Diagnosis',
  treatmentPlan:           'Treatment plan',
  followUpInstructions:    'Follow-up instructions',
  clinicalNotes:           'Clinical notes',
  followUpRequired:        'Follow-up required',
  followUpDate:            'Follow-up date',
  durationMinutes:         'Duration (minutes)',
};

/** The saved consultation, as far as the form needs it (a `ConsultationDto` fits). */
export interface ConsultationFormSource {
  chiefComplaint:          string | null;
  historyOfPresentIllness: string | null;
  physicalExamination:     string | null;
  diagnosis:               string | null;
  treatmentPlan:           string | null;
  followUpInstructions:    string | null;
  clinicalNotes:           string | null;
  followUpRequired:        boolean;
  followUpDate:            string | null;
  durationMinutes:         number | null;
}

/** What the server has stored, where it changes a rule. */
export interface ConsultationSchemaContext {
  /** Today's date at the clinic, "YYYY-MM-DD" (`clinicToday()`), never the device date. */
  today: string;
  /**
   * The follow-up date already saved on the consultation, or null. The server does not re-check a stored date
   * that has since passed, so an unchanged date is accepted even when it is before today.
   */
  storedFollowUpDate: string | null;
  /** The duration already saved. Once set it can be replaced but not removed. */
  storedDurationMinutes: number | null;
}

const WHOLE_NUMBER = /^\d{1,4}$/;

function noteField(field: ConsultationTextField) {
  return z.string().max(
    CONSULTATION_TEXT_MAX,
    `${CONSULTATION_FIELD_LABELS[field]} must be at most ${CONSULTATION_TEXT_MAX} characters`,
  );
}

/** The schema of the editor form. Build it when validating, so `today` is current. */
export function consultationFormSchema(context: ConsultationSchemaContext) {
  return z
    .object({
      chiefComplaint:          noteField('chiefComplaint'),
      historyOfPresentIllness: noteField('historyOfPresentIllness'),
      physicalExamination:     noteField('physicalExamination'),
      diagnosis:               noteField('diagnosis'),
      treatmentPlan:           noteField('treatmentPlan'),
      followUpInstructions:    noteField('followUpInstructions'),
      clinicalNotes:           noteField('clinicalNotes'),
      followUpRequired:        z.boolean(),
      followUpDate:            z.string(),
      durationMinutes:         z.string(),
    })
    .superRefine((values, ctx) => {
      const duration = values.durationMinutes.trim();
      if (duration === '') {
        if (context.storedDurationMinutes !== null) {
          ctx.addIssue({
            code: 'custom', path: ['durationMinutes'],
            message: `A saved duration cannot be removed. Enter ${CONSULTATION_DURATION_MIN} to ${CONSULTATION_DURATION_MAX} minutes.`,
          });
        }
      } else {
        const minutes = WHOLE_NUMBER.test(duration) ? Number(duration) : NaN;
        if (!(minutes >= CONSULTATION_DURATION_MIN && minutes <= CONSULTATION_DURATION_MAX)) {
          ctx.addIssue({
            code: 'custom', path: ['durationMinutes'],
            message: `Duration must be a whole number between ${CONSULTATION_DURATION_MIN} and ${CONSULTATION_DURATION_MAX} minutes`,
          });
        }
      }

      if (!values.followUpRequired) return; // the date is ignored and cleared by the server
      const date = values.followUpDate.trim();
      if (date === '') {
        ctx.addIssue({ code: 'custom', path: ['followUpDate'], message: 'Choose a follow-up date, or turn off Follow-up required' });
      } else if (!isValidDateOnly(date)) {
        ctx.addIssue({ code: 'custom', path: ['followUpDate'], message: 'Enter a valid date' });
      } else if (date !== context.storedFollowUpDate && date < context.today) {
        ctx.addIssue({ code: 'custom', path: ['followUpDate'], message: 'Follow-up date cannot be in the past' });
      }
    });
}

/** Form values for a saved consultation (`null` becomes an empty field). */
export function consultationFormValues(source: ConsultationFormSource): ConsultationFormValues {
  return {
    chiefComplaint:          source.chiefComplaint ?? '',
    historyOfPresentIllness: source.historyOfPresentIllness ?? '',
    physicalExamination:     source.physicalExamination ?? '',
    diagnosis:               source.diagnosis ?? '',
    treatmentPlan:           source.treatmentPlan ?? '',
    followUpInstructions:    source.followUpInstructions ?? '',
    clinicalNotes:           source.clinicalNotes ?? '',
    followUpRequired:        source.followUpRequired === true,
    followUpDate:            source.followUpRequired && source.followUpDate ? source.followUpDate.slice(0, 10) : '',
    durationMinutes:         source.durationMinutes == null ? '' : String(source.durationMinutes),
  };
}

/**
 * True when saving `a` would store the same consultation as `b`. The server trims the notes and ignores the
 * follow-up date while no follow-up is required, so those differences do not count as a change.
 */
export function sameConsultationValues(a: ConsultationFormValues, b: ConsultationFormValues): boolean {
  for (const field of CONSULTATION_TEXT_FIELDS) {
    if (a[field].trim() !== b[field].trim()) return false;
  }
  if (a.followUpRequired !== b.followUpRequired) return false;
  if (a.followUpRequired && a.followUpDate.trim() !== b.followUpDate.trim()) return false;
  return a.durationMinutes.trim() === b.durationMinutes.trim();
}

/** Body of PUT /api/consultations/{id} (a partial update: an absent field is left unchanged). */
export interface ConsultationUpdatePayload {
  chiefComplaint?:          string;
  historyOfPresentIllness?: string;
  physicalExamination?:     string;
  clinicalNotes?:           string;
  diagnosis?:               string;
  treatmentPlan?:           string;
  followUpInstructions?:    string;
  followUpRequired?:        boolean;
  followUpDate?:            string | null;
  durationMinutes?:         number;
}

/**
 * The save request for validated form values.
 * - Every note is sent; a blank one clears the stored text.
 * - The follow-up date is sent only when it differs from the stored one: the server rejects a date before today
 *   when it is in the body, but keeps a stored date that has since passed.
 * - The duration is sent only when one is entered (it cannot be cleared).
 */
export function consultationUpdateBody(
  values: ConsultationFormValues,
  stored: ConsultationFormSource,
): ConsultationUpdatePayload {
  const body: ConsultationUpdatePayload = { followUpRequired: values.followUpRequired };
  for (const field of CONSULTATION_TEXT_FIELDS) body[field] = values[field];

  if (values.followUpRequired) {
    const date = values.followUpDate.trim();
    const storedDate = stored.followUpRequired && stored.followUpDate ? stored.followUpDate.slice(0, 10) : null;
    if (date && date !== storedDate) body.followUpDate = date;
  }

  const duration = values.durationMinutes.trim();
  if (duration !== '') body.durationMinutes = Number(duration);
  return body;
}

export interface ConsultationFieldIssue {
  field:   ConsultationFormField;
  message: string;
}

/** What is still missing before the consultation can be completed (the server requires both). */
export function completionIssues(values: Pick<ConsultationFormValues, 'chiefComplaint' | 'diagnosis'>): ConsultationFieldIssue[] {
  const issues: ConsultationFieldIssue[] = [];
  if (!values.chiefComplaint.trim()) {
    issues.push({ field: 'chiefComplaint', message: 'Chief complaint is required to complete the consultation' });
  }
  if (!values.diagnosis.trim()) {
    issues.push({ field: 'diagnosis', message: 'Diagnosis is required to complete the consultation' });
  }
  return issues;
}

/** One sentence for the form-level message when completion is blocked. */
export function completionBlockedMessage(issues: readonly ConsultationFieldIssue[]): string | null {
  if (issues.length === 0) return null;
  if (issues.length > 1) return 'Add a chief complaint and a diagnosis before completing the consultation.';
  return issues[0].field === 'chiefComplaint'
    ? 'Add a chief complaint before completing the consultation.'
    : 'Add a diagnosis before completing the consultation.';
}

function isFormField(name: string): name is ConsultationFormField {
  return (CONSULTATION_FORM_FIELDS as readonly string[]).includes(name);
}

/** A server validation text as a sentence under its field: "must be between 1 and 480" gets the field's label in front. */
export function serverFieldMessage(field: ConsultationFormField, text: string): string {
  const message = text.trim();
  if (!message) return `${CONSULTATION_FIELD_LABELS[field]} is not valid`;
  const startsLower = message[0] === message[0].toLowerCase() && message[0] !== message[0].toUpperCase();
  const label = field === 'durationMinutes' ? 'Duration' : CONSULTATION_FIELD_LABELS[field];
  return startsLower ? `${label} ${message}` : message;
}

/**
 * Reads a service-level validation message of the form "<field>: <text>" (sent without an `errors` list).
 * Null when the message does not name a field of the form.
 */
export function fieldIssueFromMessage(message: string | null | undefined): ConsultationFieldIssue | null {
  const match = /^([A-Za-z][A-Za-z0-9]*):\s*(.+)$/.exec((message ?? '').trim());
  if (!match || !isFormField(match[1])) return null;
  return { field: match[1], message: serverFieldMessage(match[1], match[2]) };
}

/**
 * Splits the server's field errors into those that belong to a field of the form and the rest.
 * `fieldErrors` is the map from `apiError(e).fieldErrors`.
 */
export function consultationFieldIssues(
  fieldErrors: Record<string, string> | undefined,
): { issues: ConsultationFieldIssue[]; unmatched: number } {
  const issues: ConsultationFieldIssue[] = [];
  let unmatched = 0;
  for (const [name, text] of Object.entries(fieldErrors ?? {})) {
    const field = name.split('.').pop() ?? name;
    if (isFormField(field)) issues.push({ field, message: serverFieldMessage(field, text) });
    else unmatched += 1;
  }
  return { issues, unmatched };
}
