import { z } from 'zod';
import { METRIC_INFO, type MetricInfo } from '@/lib/health/catalog';
import {
  SYMPTOM_SEVERITIES,
  type HealthMetricType, type LogSymptomsRequest, type MetricReadingInput, type RecordHealthMetricsRequest,
  type SymptomSeverity,
} from '@/lib/health/types';
import { clinicNowLocal } from '@/lib/time/clinic';
import { isValidDateOnly } from '@/lib/ui/date-only';

// Client-side rules of the vitals and symptom forms. They mirror the backend constraints of
// POST /api/health-metrics and POST /api/symptom-logs (same limits, same wording where the server has one), so a
// mistake is caught before the request. The server stays the authority and its messages are shown as they come.

// ─── Vitals ───────────────────────────────────────────────────────────────────

/** The readings the form offers, in the order they are sent (the server's canonical type order). */
export const VITAL_FIELDS = [
  { name: 'systolic',    type: 'BP_SYSTOLIC' },
  { name: 'diastolic',   type: 'BP_DIASTOLIC' },
  { name: 'heartRate',   type: 'HEART_RATE' },
  { name: 'glucose',     type: 'GLUCOSE' },
  { name: 'weight',      type: 'WEIGHT' },
  { name: 'height',      type: 'HEIGHT' },
  { name: 'spo2',        type: 'SPO2' },
  { name: 'temperature', type: 'TEMPERATURE' },
] as const satisfies readonly { name: string; type: HealthMetricType }[];

export type VitalFieldName = typeof VITAL_FIELDS[number]['name'];

export const VITALS_NOTES_MAX = 500;

/**
 * Key of the form-level problem ("Enter at least one reading") in the validation errors. It is not a form field;
 * the form reads it with `vitalsFormError` and shows it above the buttons.
 */
export const VITALS_FORM_ERROR_KEY = 'readings';

/** The form-level message of a failed validation, or null when only fields are wrong. */
export function vitalsFormError(errors: object): string | null {
  const entry = (errors as Record<string, { message?: unknown } | undefined>)[VITALS_FORM_ERROR_KEY];
  return typeof entry?.message === 'string' && entry.message ? entry.message : null;
}

/** How far ahead of the clinic clock a measurement time may be (the server allows the same for device clocks). */
const FUTURE_TOLERANCE_MS = 5 * 60_000;

/** At most 7 integer digits and 3 decimals (column NUMERIC(10,3)). */
const DECIMAL = /^\d{1,7}(\.\d{1,3})?$/;
const TIME = /^([01]\d|2[0-3]):[0-5]\d$/;

/** What was typed, with a decimal comma read as a decimal point. */
function typed(raw: string): string {
  return raw.trim().replace(',', '.');
}

/** The number typed into a vitals field, or null when the field is empty or not a number. */
export function parseVitalValue(raw: string): number | null {
  const text = typed(raw);
  if (!text || !DECIMAL.test(text)) return null;
  const n = Number(text);
  return Number.isFinite(n) ? n : null;
}

/** "Heart rate must be between 20 and 300 bpm": the server's sentence for a value outside the plausible range. */
export function rangeMessage(info: MetricInfo): string {
  return `${info.label} must be between ${info.min} and ${info.max} ${info.unitLabel}`;
}

/** The message for one vitals field, or null when it is empty or acceptable. */
export function vitalValueError(type: HealthMetricType, raw: string): string | null {
  const text = typed(raw);
  if (!text) return null;
  if (!DECIMAL.test(text)) return 'Enter a number with at most 3 decimals';
  const info = METRIC_INFO[type];
  const n = Number(text);
  return n < info.min || n > info.max ? rangeMessage(info) : null;
}

/** The clinic wall-clock time of the measurement, or undefined when both fields are empty (the server then uses its clock). */
export function measuredAt(date: string, time: string): string | undefined {
  return date && time ? `${date}T${time}:00` : undefined;
}

export const recordVitalsSchema = z
  .object({
    systolic:     z.string(),
    diastolic:    z.string(),
    heartRate:    z.string(),
    glucose:      z.string(),
    weight:       z.string(),
    height:       z.string(),
    spo2:         z.string(),
    temperature:  z.string(),
    /** "YYYY-MM-DD" at the clinic, or '' for now. */
    measuredDate: z.string(),
    /** "HH:mm" at the clinic, or '' for now. */
    measuredTime: z.string(),
    notes:        z.string().max(VITALS_NOTES_MAX, `Notes can be at most ${VITALS_NOTES_MAX} characters`),
  })
  .superRefine((values, ctx) => {
    const issue = (path: string, message: string) => ctx.addIssue({ code: 'custom', path: [path], message });

    let entered = 0;
    let valid = true;
    for (const field of VITAL_FIELDS) {
      const raw = values[field.name];
      if (typed(raw)) entered += 1;
      const message = vitalValueError(field.type, raw);
      if (message) { issue(field.name, message); valid = false; }
    }
    // Reported on the form as a whole (no single field is the wrong one), under the name the API uses for the list.
    if (entered === 0) issue(VITALS_FORM_ERROR_KEY, 'Enter at least one reading');

    const hasSystolic = typed(values.systolic) !== '';
    const hasDiastolic = typed(values.diastolic) !== '';
    if (hasSystolic !== hasDiastolic) {
      issue(hasSystolic ? 'diastolic' : 'systolic', 'Blood pressure needs both systolic and diastolic readings');
    } else if (hasSystolic && valid) {
      const systolic = parseVitalValue(values.systolic);
      const diastolic = parseVitalValue(values.diastolic);
      if (systolic !== null && diastolic !== null && systolic <= diastolic) {
        issue('systolic', 'Systolic pressure must be higher than diastolic pressure');
      }
    }

    const { measuredDate: date, measuredTime: time } = values;
    if (date && !isValidDateOnly(date)) issue('measuredDate', 'Enter a valid date');
    if (time && !TIME.test(time)) issue('measuredTime', 'Enter a valid time');
    if (date && !time) issue('measuredTime', 'Enter the time as well, or clear the date');
    if (time && !date) issue('measuredDate', 'Enter the date as well, or clear the time');
    if (date && time && isValidDateOnly(date) && TIME.test(time)) {
      const latest = clinicNowLocal(new Date(Date.now() + FUTURE_TOLERANCE_MS));
      if (`${date}T${time}:00` > latest) issue('measuredTime', 'The time of the measurement cannot be in the future');
    }
  });

export type RecordVitalsValues = z.infer<typeof recordVitalsSchema>;

export const RECORD_VITALS_DEFAULTS: RecordVitalsValues = {
  systolic: '', diastolic: '', heartRate: '', glucose: '', weight: '', height: '', spo2: '', temperature: '',
  measuredDate: '', measuredTime: '', notes: '',
};

/**
 * The request for validated form values, plus the form field behind each reading (by index), so a server error
 * on `readings[2].value` can be shown under the right input. The note and the time are sent with every reading:
 * each stored row then explains itself, and the blood-pressure pair carries the same time as the API requires.
 */
export function vitalsToRequest(
  values: RecordVitalsValues,
  patientSystemId?: string | null,
): { body: RecordHealthMetricsRequest; fields: VitalFieldName[] } {
  const recordedAt = measuredAt(values.measuredDate, values.measuredTime);
  const notes = values.notes.trim();
  const readings: MetricReadingInput[] = [];
  const fields: VitalFieldName[] = [];
  for (const field of VITAL_FIELDS) {
    const value = parseVitalValue(values[field.name]);
    if (value === null) continue;
    readings.push({
      type: field.type,
      value,
      unit: METRIC_INFO[field.type].unit,
      ...(recordedAt ? { recordedAt } : null),
      ...(notes ? { notes } : null),
    });
    fields.push(field.name);
  }
  return { body: { ...(patientSystemId ? { patientSystemId } : null), readings }, fields };
}

// ─── Symptoms ─────────────────────────────────────────────────────────────────

export const SYMPTOMS_MAX = 20;
export const SYMPTOM_TEXT_MAX = 100;
export const SYMPTOM_NOTES_MAX = 2000;
export const SYMPTOM_DURATION_MAX_DAYS = 3650;

export const symptomLogSchema = z.object({
  symptoms: z
    .array(
      z.string().trim()
        .min(1, 'A symptom cannot be blank')
        .max(SYMPTOM_TEXT_MAX, `A symptom can be at most ${SYMPTOM_TEXT_MAX} characters`),
    )
    .min(1, 'Choose or add at least one symptom')
    .max(SYMPTOMS_MAX, `You can log at most ${SYMPTOMS_MAX} symptoms at once`),
  /** One of MILD, MODERATE, SEVERE, CRITICAL; '' until chosen. */
  severity: z.string().refine(
    value => (SYMPTOM_SEVERITIES as readonly string[]).includes(value),
    'Choose how severe the symptoms are',
  ),
  /** Whole days, 0 to 3650, or '' when unknown. */
  durationDays: z.string().refine(value => {
    const text = value.trim();
    if (!text) return true;
    return /^\d{1,4}$/.test(text) && Number(text) <= SYMPTOM_DURATION_MAX_DAYS;
  }, `Enter a whole number of days from 0 to ${SYMPTOM_DURATION_MAX_DAYS}`),
  notes: z.string().max(SYMPTOM_NOTES_MAX, `Notes can be at most ${SYMPTOM_NOTES_MAX} characters`),
});

export type SymptomLogValues = z.infer<typeof symptomLogSchema>;

export const SYMPTOM_LOG_DEFAULTS: SymptomLogValues = { symptoms: [], severity: '', durationDays: '', notes: '' };

/** The request for validated form values. */
export function symptomsToRequest(values: SymptomLogValues, patientSystemId?: string | null): LogSymptomsRequest {
  const duration = values.durationDays.trim();
  const notes = values.notes.trim();
  return {
    ...(patientSystemId ? { patientSystemId } : null),
    symptoms: values.symptoms.map(s => s.trim()).filter(Boolean),
    severity: values.severity as SymptomSeverity,
    ...(duration ? { durationDays: Number(duration) } : null),
    ...(notes ? { notes } : null),
  };
}
