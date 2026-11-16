import { z } from 'zod';
import { isValidDateOnly } from '@/lib/ui/date-only';

// Form rules for prescriptions. They mirror the backend constraints of POST /api/prescriptions,
// PUT /api/prescriptions/{id} and PUT /api/prescriptions/{id}/schedule, so a value the form accepts is a value
// the server accepts. Text is trimmed before it is checked, as the server trims before storing.

export const PRESCRIPTION_STATUSES = ['ACTIVE', 'ON_HOLD', 'COMPLETED', 'CANCELLED', 'EXPIRED'] as const;
export type PrescriptionStatus = (typeof PRESCRIPTION_STATUSES)[number];

export const PRESCRIPTION_ROUTES = ['ORAL', 'TOPICAL', 'INJECTION', 'INHALED', 'SUBLINGUAL', 'RECTAL', 'IV'] as const;
export type PrescriptionRoute = (typeof PRESCRIPTION_ROUTES)[number];

export const PRESCRIPTION_LIMITS = {
  medicationName:      200,
  genericName:         200,
  dosage:              100,
  frequency:           100,
  quantity:            50,
  specialInstructions: 2000,
  refillsMin:          0,
  refillsMax:          99,
  scheduleTimes:       8,
  search:              100,
} as const;

/** A 24-hour clinic time of day, zero padded: "08:00", "20:30". Same pattern as the backend's TIME_HH_MM. */
export const TIME_HH_MM = /^([01]\d|2[0-3]):[0-5]\d$/;

/** A patient system id ("PAT-00001"). */
export const PATIENT_SYSTEM_ID = /^PAT-\d{5,}$/;

export function isScheduleTime(value: string): boolean {
  return TIME_HH_MM.test(value);
}

/** Distinct times in ascending order, the form the server stores them in. Values that are not HH:mm are dropped. */
export function normaliseScheduleTimes(times: readonly string[]): string[] {
  return Array.from(new Set(times.map(t => t.trim()).filter(isScheduleTime))).sort();
}

const requiredText = (label: string, max: number) => z.string().trim()
  .min(1, `${label} is required`)
  .max(max, `${label} must be at most ${max} characters`);

const optionalText = (label: string, max: number) => z.string().trim()
  .max(max, `${label} must be at most ${max} characters`);

const refills = z.string().trim()
  .regex(/^\d{1,2}$/, `Enter a whole number from ${PRESCRIPTION_LIMITS.refillsMin} to ${PRESCRIPTION_LIMITS.refillsMax}`);

const scheduleTimes = z.array(z.string().regex(TIME_HH_MM, 'Each time must use the 24-hour HH:mm format'))
  .max(PRESCRIPTION_LIMITS.scheduleTimes, `Add at most ${PRESCRIPTION_LIMITS.scheduleTimes} times`);

const dateOrEmpty = z.string().trim()
  .refine(v => v === '' || isValidDateOnly(v), 'Enter a valid date');

/**
 * The "New prescription" form. `clinicToday` ("YYYY-MM-DD") is the day the prescription is issued on: the form never
 * sends a prescribed date, so the server uses the clinic's today and the expiry date may not be earlier than it.
 */
export function newPrescriptionSchema(clinicToday: string) {
  return z.object({
    patientSystemId:     z.string().trim().min(1, 'Choose a patient').regex(PATIENT_SYSTEM_ID, 'Choose a patient'),
    /** '' when the prescription is not linked to a consultation, otherwise the consultation id as digits. */
    consultationId:      z.string().trim().regex(/^(|[1-9]\d*)$/, 'Choose a consultation from the list'),
    medicationName:      requiredText('Medication name', PRESCRIPTION_LIMITS.medicationName),
    genericName:         optionalText('Generic name', PRESCRIPTION_LIMITS.genericName),
    dosage:              requiredText('Dosage', PRESCRIPTION_LIMITS.dosage),
    frequency:           requiredText('Frequency', PRESCRIPTION_LIMITS.frequency),
    route:               z.enum(PRESCRIPTION_ROUTES, { message: 'Choose a route' }),
    quantity:            optionalText('Quantity', PRESCRIPTION_LIMITS.quantity),
    refillsAllowed:      refills,
    specialInstructions: optionalText('Instructions', PRESCRIPTION_LIMITS.specialInstructions),
    expiryDate:          dateOrEmpty.refine(v => v === '' || v >= clinicToday, 'The expiry date cannot be in the past'),
    scheduleTimes,
  });
}

export type NewPrescriptionValues = z.infer<ReturnType<typeof newPrescriptionSchema>>;

export const NEW_PRESCRIPTION_FIELDS = [
  'patientSystemId', 'consultationId', 'medicationName', 'genericName', 'dosage', 'frequency', 'route', 'quantity',
  'refillsAllowed', 'specialInstructions', 'expiryDate', 'scheduleTimes',
] as const satisfies readonly (keyof NewPrescriptionValues)[];

export function emptyNewPrescription(seed: { patientSystemId?: string | null; consultationId?: number | null } = {}): NewPrescriptionValues {
  return {
    patientSystemId:     seed.patientSystemId ?? '',
    consultationId:      seed.consultationId ? String(seed.consultationId) : '',
    medicationName:      '',
    genericName:         '',
    dosage:              '',
    frequency:           '',
    route:               'ORAL',
    quantity:            '',
    refillsAllowed:      '0',
    specialInstructions: '',
    expiryDate:          '',
    scheduleTimes:       [],
  };
}

/** Body of POST /api/prescriptions. `prescribedDate` is never sent: the server uses the clinic's today. */
export interface CreatePrescriptionBody {
  patientSystemId:      string;
  consultationId?:      number;
  medicationName:       string;
  genericName?:         string;
  dosage:               string;
  frequency:            string;
  route:                PrescriptionRoute;
  quantity?:            string;
  refillsAllowed:       number;
  specialInstructions?: string;
  expiryDate?:          string;
  scheduleTimes:        string[];
}

/** The request body for validated form values. Optional text that is blank is left out. */
export function toCreatePrescriptionBody(values: NewPrescriptionValues): CreatePrescriptionBody {
  const text = (v: string) => (v.trim() ? v.trim() : undefined);
  return {
    patientSystemId:     values.patientSystemId.trim(),
    consultationId:      values.consultationId ? Number(values.consultationId) : undefined,
    medicationName:      values.medicationName.trim(),
    genericName:         text(values.genericName),
    dosage:              values.dosage.trim(),
    frequency:           values.frequency.trim(),
    route:               values.route,
    quantity:            text(values.quantity),
    refillsAllowed:      Number(values.refillsAllowed),
    specialInstructions: text(values.specialInstructions),
    expiryDate:          text(values.expiryDate),
    scheduleTimes:       normaliseScheduleTimes(values.scheduleTimes),
  };
}

/**
 * The edit form: only refills, expiry date and instructions can change after issue. `prescribedDate` is the stored
 * day of issue; the expiry date may not be earlier than it.
 */
export function editPrescriptionSchema(prescribedDate: string) {
  return z.object({
    refillsAllowed:      refills,
    expiryDate:          dateOrEmpty.refine(
      v => v === '' || !prescribedDate || v >= prescribedDate,
      'The expiry date cannot be before the day the prescription was issued',
    ),
    specialInstructions: optionalText('Instructions', PRESCRIPTION_LIMITS.specialInstructions),
  });
}

export type EditPrescriptionValues = z.infer<ReturnType<typeof editPrescriptionSchema>>;

export const EDIT_PRESCRIPTION_FIELDS = ['refillsAllowed', 'expiryDate', 'specialInstructions'] as const satisfies
  readonly (keyof EditPrescriptionValues)[];

/** Body of PUT /api/prescriptions/{id}: the complete editable state, all three fields on every call. */
export interface UpdatePrescriptionBody {
  refillsAllowed:      number;
  /** null clears the expiry date. */
  expiryDate:          string | null;
  /** null clears the instructions. */
  specialInstructions: string | null;
}

export function toUpdatePrescriptionBody(values: EditPrescriptionValues): UpdatePrescriptionBody {
  return {
    refillsAllowed:      Number(values.refillsAllowed),
    expiryDate:          values.expiryDate.trim() || null,
    specialInstructions: values.specialInstructions.trim() || null,
  };
}

/** The "Edit schedule times" form (PUT /api/prescriptions/{id}/schedule). An empty list removes the schedule. */
export const scheduleTimesSchema = z.object({ times: scheduleTimes });

export type ScheduleTimesValues = z.infer<typeof scheduleTimesSchema>;
