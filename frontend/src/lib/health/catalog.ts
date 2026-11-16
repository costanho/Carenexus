import type { IoniconsName } from '@/lib/nav/sections';
import type {
  HealthMetric, HealthMetricType, LatestHealthMetrics, MetricSeverity, MetricSource, SymptomSeverity, SymptomSource,
} from './types';

// What the web knows about each metric type: how to name it, which unit to show and which values the server
// accepts. The plausible ranges mirror the backend table (they reject typing mistakes); they are NOT the clinical
// reference ranges. Whether a reading is abnormal always comes from the server's `severity` field.

export interface MetricInfo {
  type:      HealthMetricType;
  /** The label the server uses in its messages ("Heart rate must be between 20 and 300 bpm"). */
  label:     string;
  /** The ASCII unit the API stores and accepts. */
  unit:      string;
  /** The unit as shown on screen (°C, kg/m²). */
  unitLabel: string;
  /** Plausible range accepted by the server, inclusive. */
  min:       number;
  max:       number;
  /** False for BMI, which is derived from weight and height and cannot be entered. */
  manual:    boolean;
  icon:      IoniconsName;
}

export const METRIC_INFO: Record<HealthMetricType, MetricInfo> = {
  BP_SYSTOLIC:      { type: 'BP_SYSTOLIC',      label: 'Systolic pressure',  unit: 'mmHg',        unitLabel: 'mmHg',        min: 50, max: 300,    manual: true,  icon: 'heart-outline' },
  BP_DIASTOLIC:     { type: 'BP_DIASTOLIC',     label: 'Diastolic pressure', unit: 'mmHg',        unitLabel: 'mmHg',        min: 20, max: 200,    manual: true,  icon: 'heart-outline' },
  HEART_RATE:       { type: 'HEART_RATE',       label: 'Heart rate',         unit: 'bpm',         unitLabel: 'bpm',         min: 20, max: 300,    manual: true,  icon: 'pulse-outline' },
  GLUCOSE:          { type: 'GLUCOSE',          label: 'Blood glucose',      unit: 'mmol/L',      unitLabel: 'mmol/L',      min: 1,  max: 50,     manual: true,  icon: 'water-outline' },
  WEIGHT:           { type: 'WEIGHT',           label: 'Weight',             unit: 'kg',          unitLabel: 'kg',          min: 1,  max: 500,    manual: true,  icon: 'scale-outline' },
  HEIGHT:           { type: 'HEIGHT',           label: 'Height',             unit: 'cm',          unitLabel: 'cm',          min: 30, max: 275,    manual: true,  icon: 'resize-outline' },
  BMI:              { type: 'BMI',              label: 'BMI',                unit: 'kg/m2',       unitLabel: 'kg/m²',       min: 5,  max: 150,    manual: false, icon: 'body-outline' },
  SPO2:             { type: 'SPO2',             label: 'Oxygen saturation',  unit: '%',           unitLabel: '%',           min: 50, max: 100,    manual: true,  icon: 'fitness-outline' },
  TEMPERATURE:      { type: 'TEMPERATURE',      label: 'Temperature',        unit: 'C',           unitLabel: '°C',          min: 25, max: 45,     manual: true,  icon: 'thermometer-outline' },
  RESPIRATORY_RATE: { type: 'RESPIRATORY_RATE', label: 'Respiratory rate',   unit: 'breaths/min', unitLabel: 'breaths/min', min: 4,  max: 80,     manual: true,  icon: 'swap-vertical-outline' },
  STEPS:            { type: 'STEPS',            label: 'Steps',              unit: 'steps',       unitLabel: 'steps',       min: 0,  max: 200000, manual: true,  icon: 'walk-outline' },
};

/** The catalogue entry of a type; a type this build does not know gets a readable fallback instead of a crash. */
export function metricInfo(type: string): MetricInfo {
  const known = METRIC_INFO[type as HealthMetricType];
  if (known) return known;
  const words = type.replace(/_/g, ' ').toLowerCase();
  return {
    type: type as HealthMetricType, label: words.charAt(0).toUpperCase() + words.slice(1),
    unit: '', unitLabel: '', min: 0, max: 9_999_999, manual: false, icon: 'analytics-outline',
  };
}

const NUMBER = new Intl.NumberFormat('en-GB', { maximumFractionDigits: 3 });

/** "148", "71.5", "12,345": at most three decimals, no trailing zeros. A dash when the value is not a number. */
export function formatMetricValue(value: number | string | null | undefined): string {
  const n = typeof value === 'string' ? Number(value) : value;
  return typeof n === 'number' && Number.isFinite(n) ? NUMBER.format(n) : '—';
}

// ─── Families: what a person picks in a filter or a chart ─────────────────────

/** A measurement as people think of it: blood pressure is one family made of two stored types. */
export type MetricFamily =
  | 'BLOOD_PRESSURE' | 'HEART_RATE' | 'GLUCOSE' | 'WEIGHT' | 'HEIGHT' | 'SPO2' | 'TEMPERATURE' | 'RESPIRATORY_RATE' | 'STEPS';

export interface FamilyInfo {
  key:       MetricFamily;
  label:     string;
  /** The stored types of the family, in the order they are charted and requested. */
  types:     readonly HealthMetricType[];
  unitLabel: string;
  icon:      IoniconsName;
}

/** BMI is absent on purpose: it has no stored rows, so there is nothing to filter or chart. */
export const METRIC_FAMILIES: readonly FamilyInfo[] = [
  { key: 'BLOOD_PRESSURE',   label: 'Blood pressure',    types: ['BP_SYSTOLIC', 'BP_DIASTOLIC'], unitLabel: 'mmHg',        icon: 'heart-outline' },
  { key: 'HEART_RATE',       label: 'Heart rate',        types: ['HEART_RATE'],                  unitLabel: 'bpm',         icon: 'pulse-outline' },
  { key: 'GLUCOSE',          label: 'Blood glucose',     types: ['GLUCOSE'],                     unitLabel: 'mmol/L',      icon: 'water-outline' },
  { key: 'WEIGHT',           label: 'Weight',            types: ['WEIGHT'],                      unitLabel: 'kg',          icon: 'scale-outline' },
  { key: 'HEIGHT',           label: 'Height',            types: ['HEIGHT'],                      unitLabel: 'cm',          icon: 'resize-outline' },
  { key: 'SPO2',             label: 'Oxygen saturation', types: ['SPO2'],                        unitLabel: '%',           icon: 'fitness-outline' },
  { key: 'TEMPERATURE',      label: 'Temperature',       types: ['TEMPERATURE'],                 unitLabel: '°C',          icon: 'thermometer-outline' },
  { key: 'RESPIRATORY_RATE', label: 'Respiratory rate',  types: ['RESPIRATORY_RATE'],            unitLabel: 'breaths/min', icon: 'swap-vertical-outline' },
  { key: 'STEPS',            label: 'Steps',             types: ['STEPS'],                       unitLabel: 'steps',       icon: 'walk-outline' },
];

export function familyInfo(key: string | null | undefined): FamilyInfo | undefined {
  return METRIC_FAMILIES.find(f => f.key === key);
}

// ─── Severity ─────────────────────────────────────────────────────────────────

const SEVERITY_RANK: Record<MetricSeverity, number> = { NORMAL: 0, ABNORMAL: 1, CRITICAL: 2 };

/** The severity of a reading, falling back to `isAbnormal` for a row without the field. */
export function severityOf(metric: Pick<HealthMetric, 'severity' | 'isAbnormal'>): MetricSeverity {
  if (metric.severity in SEVERITY_RANK) return metric.severity;
  return metric.isAbnormal ? 'ABNORMAL' : 'NORMAL';
}

export function worstSeverity(a: MetricSeverity, b: MetricSeverity): MetricSeverity {
  return SEVERITY_RANK[b] > SEVERITY_RANK[a] ? b : a;
}

/**
 * The badge of a reading: "Abnormal" or "Critical" in words. A normal reading has no badge, because several
 * types (weight, height, steps) have no reference range and "Normal" would claim a check that never happened.
 */
export function metricSeverityBadge(severity: MetricSeverity): { label: string; tone: 'warning' | 'critical' } | null {
  if (severity === 'CRITICAL') return { label: 'Critical', tone: 'critical' };
  if (severity === 'ABNORMAL') return { label: 'Abnormal', tone: 'warning' };
  return null;
}

const METRIC_SOURCE_LABEL: Record<MetricSource, string> = {
  MANUAL: 'Entered by hand', WEARABLE: 'From a wearable', DEVICE: 'From a device', INTEGRATION: 'From a connected service',
};

export function metricSourceLabel(source: string): string {
  return METRIC_SOURCE_LABEL[source as MetricSource] ?? 'From another source';
}

// ─── Entries: blood pressure shown as one reading ─────────────────────────────

/** One line of a list or one tile: a single reading, or a systolic and diastolic pair stored together. */
export type ReadingEntry =
  | { kind: 'single'; key: string; metric: HealthMetric }
  | { kind: 'bloodPressure'; key: string; systolic: HealthMetric; diastolic: HealthMetric };

export interface EntryView {
  label:      string;
  /** "148/92" or "76". */
  valueText:  string;
  unitLabel:  string;
  severity:   MetricSeverity;
  recordedAt: string;
  icon:       IoniconsName;
  /** The reading to address in a delete; deleting one half of a pair removes both. */
  metricId:   number;
  canDelete:  boolean;
}

export function entryView(entry: ReadingEntry): EntryView {
  if (entry.kind === 'bloodPressure') {
    const { systolic, diastolic } = entry;
    return {
      label:      'Blood pressure',
      valueText:  `${formatMetricValue(systolic.value)}/${formatMetricValue(diastolic.value)}`,
      unitLabel:  METRIC_INFO.BP_SYSTOLIC.unitLabel,
      severity:   worstSeverity(severityOf(systolic), severityOf(diastolic)),
      recordedAt: systolic.recordedAt,
      icon:       METRIC_INFO.BP_SYSTOLIC.icon,
      metricId:   systolic.metricId,
      canDelete:  systolic.canDelete && diastolic.canDelete,
    };
  }
  const info = metricInfo(entry.metric.type);
  return {
    label:      info.label,
    valueText:  formatMetricValue(entry.metric.value),
    unitLabel:  info.unitLabel || entry.metric.unit,
    severity:   severityOf(entry.metric),
    recordedAt: entry.metric.recordedAt,
    icon:       info.icon,
    metricId:   entry.metric.metricId,
    canDelete:  entry.metric.canDelete,
  };
}

/**
 * Turns readings into entries, joining a systolic and a diastolic reading that share a `readingGroupId` into one
 * blood-pressure entry (at the position of whichever comes first). A half without its partner (the other half is
 * on a page not loaded yet, was filtered out, or is a legacy row) stays a single entry with its own label.
 */
export function toReadingEntries(metrics: readonly HealthMetric[]): ReadingEntry[] {
  const partnerOf = new Map<string, HealthMetric>();
  for (const m of metrics) {
    if (!m.readingGroupId || (m.type !== 'BP_SYSTOLIC' && m.type !== 'BP_DIASTOLIC')) continue;
    partnerOf.set(`${m.readingGroupId}:${m.type}`, m);
  }
  const used = new Set<number>();
  const out: ReadingEntry[] = [];
  for (const m of metrics) {
    if (used.has(m.metricId)) continue;
    used.add(m.metricId);
    if (m.readingGroupId && (m.type === 'BP_SYSTOLIC' || m.type === 'BP_DIASTOLIC')) {
      const other = partnerOf.get(`${m.readingGroupId}:${m.type === 'BP_SYSTOLIC' ? 'BP_DIASTOLIC' : 'BP_SYSTOLIC'}`);
      if (other && !used.has(other.metricId)) {
        used.add(other.metricId);
        const systolic = m.type === 'BP_SYSTOLIC' ? m : other;
        const diastolic = m.type === 'BP_SYSTOLIC' ? other : m;
        out.push({ kind: 'bloodPressure', key: `bp-${systolic.metricId}`, systolic, diastolic });
        continue;
      }
    }
    out.push({ kind: 'single', key: `m-${m.metricId}`, metric: m });
  }
  return out;
}

/** Readings taken together: one request, one time. */
export interface ReadingSession {
  key:                string;
  recordedAt:         string;
  recordedBySystemId: string | null;
  recordedByName:     string | null;
  source:             string;
  /** The distinct notes of the session's readings, in order. */
  notes:              string[];
  entries:            ReadingEntry[];
  /** Number of stored readings in the session (a blood-pressure entry counts two). */
  readingCount:       number;
}

/**
 * Groups readings into measurement sessions (same `readingGroupId` and the same time), keeping the order of the
 * first reading of each session. A reading without a group id is a session of its own.
 */
export function toReadingSessions(metrics: readonly HealthMetric[]): ReadingSession[] {
  const order: string[] = [];
  const groups = new Map<string, HealthMetric[]>();
  for (const m of metrics) {
    const key = m.readingGroupId ? `${m.readingGroupId}@${m.recordedAt}` : `m-${m.metricId}`;
    const rows = groups.get(key);
    if (rows) rows.push(m);
    else { groups.set(key, [m]); order.push(key); }
  }
  return order.map(key => {
    const rows = groups.get(key) ?? [];
    // Within a session show the readings in the catalogue order (systolic before diastolic, then the rest).
    const sorted = rows.slice().sort((a, b) => typeRank(a.type) - typeRank(b.type) || a.metricId - b.metricId);
    const notes: string[] = [];
    for (const m of sorted) {
      const note = (m.notes ?? '').trim();
      if (note && !notes.includes(note)) notes.push(note);
    }
    const first = sorted[0];
    return {
      key,
      recordedAt:         first.recordedAt,
      recordedBySystemId: first.recordedBySystemId,
      recordedByName:     first.recordedByName,
      source:             first.source,
      notes,
      entries:            toReadingEntries(sorted),
      readingCount:       rows.length,
    };
  });
}

const TYPE_ORDER = Object.keys(METRIC_INFO);

function typeRank(type: string): number {
  const index = TYPE_ORDER.indexOf(type);
  return index < 0 ? TYPE_ORDER.length : index;
}

// ─── Tiles: the latest reading of each type ───────────────────────────────────

export interface VitalTile {
  key:        string;
  label:      string;
  valueText:  string;
  unitLabel:  string;
  severity:   MetricSeverity;
  recordedAt: string;
  icon:       IoniconsName;
  /** A line under the value, for example where a derived value comes from. */
  caption?:   string;
}

/**
 * The tiles of a "latest readings" answer. Blood pressure is one tile only when the latest systolic and diastolic
 * readings were stored together; otherwise each half is its own tile with its own time. The derived BMI sits
 * after height.
 */
export function latestTiles(latest: LatestHealthMetrics | null | undefined): VitalTile[] {
  if (!latest) return [];
  const stored = latest.metrics.filter(m => !(m.type === 'BMI' && latest.bmi));
  const tiles: VitalTile[] = toReadingEntries(stored).map(entry => {
    const v = entryView(entry);
    return { key: entry.key, label: v.label, valueText: v.valueText, unitLabel: v.unitLabel, severity: v.severity, recordedAt: v.recordedAt, icon: v.icon };
  });
  if (latest.bmi) {
    const bmi: VitalTile = {
      key:        'bmi',
      label:      METRIC_INFO.BMI.label,
      valueText:  formatMetricValue(latest.bmi.value),
      unitLabel:  METRIC_INFO.BMI.unitLabel,
      severity:   severityOf(latest.bmi),
      recordedAt: latest.bmi.recordedAt,
      icon:       METRIC_INFO.BMI.icon,
      caption:    'Calculated from weight and height',
    };
    // After the last weight or height tile, so the three read together.
    let at = -1;
    stored.forEach(m => {
      if (m.type !== 'WEIGHT' && m.type !== 'HEIGHT') return;
      const index = tiles.findIndex(t => t.key === `m-${m.metricId}`);
      if (index > at) at = index;
    });
    tiles.splice(at < 0 ? tiles.length : at + 1, 0, bmi);
  }
  return tiles;
}

// ─── Symptoms ─────────────────────────────────────────────────────────────────

export const SYMPTOM_SEVERITY_LABEL: Record<SymptomSeverity, string> = {
  MILD: 'Mild', MODERATE: 'Moderate', SEVERE: 'Severe', CRITICAL: 'Critical',
};

export const SYMPTOM_SEVERITY_OPTIONS: readonly { value: SymptomSeverity; label: string }[] = [
  { value: 'MILD', label: 'Mild' },
  { value: 'MODERATE', label: 'Moderate' },
  { value: 'SEVERE', label: 'Severe' },
  { value: 'CRITICAL', label: 'Critical' },
];

export function symptomSeverityLabel(severity: string): string {
  return SYMPTOM_SEVERITY_LABEL[severity as SymptomSeverity] ?? 'Unknown';
}

const SYMPTOM_SOURCE_LABEL: Record<SymptomSource, string> = {
  PATIENT_REPORTED: 'Reported by the patient or a caregiver',
  DOCTOR_ENTERED:   'Entered by a doctor',
  WEARABLE:         'From a wearable',
  INTEGRATION:      'From a connected service',
};

export function symptomSourceLabel(source: string): string {
  return SYMPTOM_SOURCE_LABEL[source as SymptomSource] ?? 'From another source';
}

/** "Started today", "For 1 day", "For 14 days"; null when the duration was not given. */
export function symptomDurationLabel(durationDays: number | null | undefined): string | null {
  if (durationDays === null || durationDays === undefined || !Number.isFinite(durationDays)) return null;
  if (durationDays <= 0) return 'Started today';
  return `For ${durationDays} ${durationDays === 1 ? 'day' : 'days'}`;
}

/** Quick picks of the symptom form. Anything else is typed as free text. */
export const COMMON_SYMPTOMS: readonly string[] = [
  'Headache', 'Fever', 'Cough', 'Fatigue', 'Nausea', 'Vomiting', 'Dizziness', 'Shortness of breath',
  'Chest pain', 'Sore throat', 'Body aches', 'Abdominal pain', 'Diarrhoea', 'Loss of appetite', 'Trouble sleeping', 'Rash',
];
