import type { StatusTone } from '@/components/web/ui';
import type {
  ConsultationDto, ImagingType, LabResultDto, LabTestType, MedicalRecordType,
} from '@/hooks/use-dependent-records';
import { formatClinicDate, formatClinicDateTime } from '@/lib/time/clinic';
import { formatDateOnly } from '@/lib/ui/date-only';

// Wording and small parsers shared by the caregiver record cards, detail views and dashboard cards.

/** The four record kinds of Documents & Results; the values are the `tab` URL param. */
export type RecordKind = 'labs' | 'imaging' | 'records' | 'consultations';

export const RECORD_KINDS: readonly RecordKind[] = ['labs', 'imaging', 'records', 'consultations'];

export const KIND_TEXT: Record<RecordKind, { tab: string; plural: string; singular: string; capitalPlural: string }> = {
  labs:          { tab: 'Lab results',     plural: 'lab results',     singular: 'lab result',     capitalPlural: 'Lab results' },
  imaging:       { tab: 'Imaging',         plural: 'imaging results', singular: 'imaging result', capitalPlural: 'Imaging results' },
  records:       { tab: 'Medical records', plural: 'medical records', singular: 'medical record', capitalPlural: 'Medical records' },
  consultations: { tab: 'Consultations',   plural: 'consultations',   singular: 'consultation',   capitalPlural: 'Consultations' },
};

/** The loved one's name for headings and sentences; never an id. */
export function dependentName(dependent: { patientName?: string | null } | null | undefined, fallback?: string | null): string {
  return dependent?.patientName?.trim() || fallback?.trim() || 'Your loved one';
}

/** A positive integer from a URL param, or null. */
export function parseId(value: string | null | undefined): number | null {
  if (!value || !/^\d+$/.test(value)) return null;
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

const LAB_TYPE_LABEL: Record<LabTestType, string> = {
  BLOOD: 'Blood test', URINE: 'Urine test', STOOL: 'Stool test', CULTURE: 'Culture', BIOPSY: 'Biopsy',
  GENETIC: 'Genetic test', SWAB: 'Swab', OTHER: 'Other test',
};

const IMAGING_TYPE_LABEL: Record<ImagingType, string> = {
  XRAY: 'X-ray', MRI: 'MRI', CT_SCAN: 'CT scan', ULTRASOUND: 'Ultrasound', PET: 'PET scan',
  MAMMOGRAPHY: 'Mammography', DEXA: 'DEXA scan', ECHO: 'Echocardiogram',
};

const RECORD_TYPE_LABEL: Record<MedicalRecordType, string> = {
  CONSULT: 'Consultation', LAB: 'Lab', IMAGING: 'Imaging', PRESCRIPTION: 'Prescription', REFERRAL: 'Referral',
  DISCHARGE: 'Discharge summary', NOTE: 'Clinical note', OPERATION: 'Operation',
};

export const RECORD_TYPES = Object.keys(RECORD_TYPE_LABEL) as MedicalRecordType[];

const APPOINTMENT_TYPE_LABEL: Record<string, string> = {
  IN_PERSON: 'In-person visit', VIDEO: 'Video call', PHONE: 'Phone call',
};

/** "CT_SCAN" becomes "Ct scan": the fallback for a value this file has no wording for. */
function humanise(value: string | null | undefined): string {
  const words = (value ?? '').trim().replace(/[_\s]+/g, ' ').toLowerCase();
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : '';
}

export function labTypeLabel(type: string | null | undefined): string {
  return LAB_TYPE_LABEL[type as LabTestType] ?? humanise(type);
}

export function imagingTypeLabel(type: string | null | undefined): string {
  return IMAGING_TYPE_LABEL[type as ImagingType] ?? humanise(type);
}

export function recordTypeLabel(type: string | null | undefined): string {
  return RECORD_TYPE_LABEL[type as MedicalRecordType] ?? humanise(type);
}

export function appointmentTypeLabel(type: string | null | undefined): string {
  return APPOINTMENT_TYPE_LABEL[type ?? ''] ?? humanise(type);
}

/** A date-only value ("2026-11-09") as "9 Nov 2026". */
export function formatDay(value: string | null | undefined, fallback = 'Date not recorded'): string {
  return formatDateOnly(value, undefined, fallback);
}

/** A clinic date-time as "Tue 10 Nov 2026, 09:41 CAT". */
export function formatMoment(value: string | null | undefined, fallback = 'Time not recorded'): string {
  return formatClinicDateTime(value, fallback);
}

/** When a consultation took place: completion time, else start, else creation. */
export function consultationWhen(c: Pick<ConsultationDto, 'completedAt' | 'startedAt' | 'createdAt'>): string | null {
  return c.completedAt ?? c.startedAt ?? c.createdAt ?? null;
}

/** The calendar day of a consultation in clinic time, "Tue 10 Nov 2026". */
export function consultationDay(c: Pick<ConsultationDto, 'completedAt' | 'startedAt' | 'createdAt'>): string {
  return formatClinicDate(consultationWhen(c), 'Date not recorded');
}

// ─── Lab sub-tests ────────────────────────────────────────────────────────────

export interface LabSubTest {
  key:            string;
  name:           string;
  /** Value with its unit, or "" when none was recorded. */
  value:          string;
  reference:      string;
  /** Upper-case flag (NORMAL, LOW, HIGH, CRITICAL, ...) or null. */
  flag:           string | null;
  interpretation: string | null;
}

function text(value: unknown): string {
  return typeof value === 'string' || typeof value === 'number' ? String(value).trim() : '';
}

/**
 * The sub-tests of a lab result. `resultData` is JSON text of an object keyed by sub-test
 * (`{ haemoglobin: { value, unit, reference, flag, interpretation } }`); anything else gives an empty list.
 */
export function parseLabSubTests(resultData: string | null | undefined): LabSubTest[] {
  if (!resultData) return [];
  let parsed: unknown;
  try { parsed = JSON.parse(resultData); } catch { return []; }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return [];

  return Object.entries(parsed as Record<string, unknown>).map(([key, raw]) => {
    const sub = raw && typeof raw === 'object' && !Array.isArray(raw) ? (raw as Record<string, unknown>) : { value: raw };
    const value = text(sub.value);
    const unit = text(sub.unit);
    const flag = text(sub.flag).toUpperCase();
    return {
      key,
      name: humanise(key) || key,
      value: [value, unit].filter(Boolean).join(' '),
      // Older rows call the reference range `referenceRange`.
      reference: text(sub.reference) || text(sub.referenceRange),
      flag: flag || null,
      interpretation: text(sub.interpretation) || null,
    };
  });
}

/** Colour and meaning of a sub-test flag. Low and high are both "outside the range". */
export function labFlagTone(flag: string | null): StatusTone {
  switch (flag) {
    case 'NORMAL':   return 'success';
    case 'LOW':
    case 'HIGH':
    case 'ABNORMAL': return 'warning';
    case 'CRITICAL': return 'critical';
    default:         return 'neutral';
  }
}

/** One line that sums up a lab result on its card: the headline value, or how many of its values carry a flag. */
export function labHeadline(result: Pick<LabResultDto, 'resultValue' | 'unit' | 'referenceRange' | 'resultData'>): string | null {
  const value = (result.resultValue ?? '').trim();
  if (value) {
    const withUnit = [value, (result.unit ?? '').trim()].filter(Boolean).join(' ');
    const range = (result.referenceRange ?? '').trim();
    return range ? `${withUnit} (reference ${range})` : withUnit;
  }
  const subTests = parseLabSubTests(result.resultData);
  if (subTests.length === 0) return null;
  const flagged = subTests.filter(s => s.flag !== null && s.flag !== 'NORMAL').length;
  const values = `${subTests.length} ${subTests.length === 1 ? 'value' : 'values'}`;
  if (flagged === 0) return `${values} recorded`;
  return `${flagged} of ${values} flagged`;
}
