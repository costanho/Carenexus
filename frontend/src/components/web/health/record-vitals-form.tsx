import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, colors, DateField, fontSize, FormActions, FormError, space, TextField, TimeField,
} from '@/components/web/ui';
import { useRecordHealthMetrics } from '@/hooks/use-health-metrics';
import type { ApiError } from '@/lib/api/errors';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { METRIC_INFO } from '@/lib/health/catalog';
import type { RecordHealthMetricsResponse } from '@/lib/health/types';
import { CLINIC_ZONE_LABEL, clinicToday } from '@/lib/time/clinic';
import {
  RECORD_VITALS_DEFAULTS, recordVitalsSchema, VITAL_FIELDS, VITALS_NOTES_MAX, vitalsFormError, vitalsToRequest,
  type RecordVitalsValues, type VitalFieldName,
} from '@/lib/validation/health';

export interface RecordVitalsFormProps {
  /** The patient the readings belong to. Required for a doctor or caregiver; a patient may leave it out. */
  patientSystemId?: string | null;
  /** Called with the stored readings after a successful save. */
  onRecorded?:      (result: RecordHealthMetricsResponse) => void;
  /** Shows a Cancel button that calls it. */
  onCancel?:        () => void;
  /** Reports whether a save is in flight, so a surrounding dialog can refuse to close meanwhile. */
  onBusyChange?:    (busy: boolean) => void;
  /** Reports whether anything was typed, so a surrounding dialog can ask before discarding it. */
  onDirtyChange?:   (dirty: boolean) => void;
}

/** Fields outside the blood-pressure pair, in form order. */
const OTHER_VITALS = VITAL_FIELDS.filter(f => f.type !== 'BP_SYSTOLIC' && f.type !== 'BP_DIASTOLIC');

function fieldLabel(type: (typeof VITAL_FIELDS)[number]['type']): string {
  const info = METRIC_INFO[type];
  return `${info.label} (${info.unitLabel})`;
}

/**
 * Where each server field error belongs: `readings[2].value` goes to the input that produced the third reading,
 * a reading's notes or time to the shared notes and time fields.
 */
function serverFieldMap(error: ApiError, fields: readonly VitalFieldName[]): Record<string, keyof RecordVitalsValues> {
  const map: Record<string, keyof RecordVitalsValues> = {};
  for (const name of Object.keys(error.fieldErrors ?? {})) {
    const match = /^readings\[(\d+)\]\.(\w+)$/.exec(name);
    if (!match) continue;
    const target = fields[Number(match[1])];
    if (match[2] === 'notes') map[name] = 'notes';
    else if (match[2] === 'recordedAt') map[name] = 'measuredTime';
    else if (target) map[name] = target;
  }
  return map;
}

/**
 * Records one measurement session: blood pressure (both values or neither), heart rate, blood glucose, weight,
 * height, oxygen saturation and temperature, each with its unit. Any subset can be filled in; at least one
 * reading is required. Values are checked against the ranges the server accepts before the request is sent.
 */
export function RecordVitalsForm({ patientSystemId, onRecorded, onCancel, onBusyChange, onDirtyChange }: RecordVitalsFormProps) {
  const { control, handleSubmit, setError, reset, watch, formState: { isDirty } } = useForm<RecordVitalsValues>({
    resolver:      zodResolver(recordVitalsSchema),
    defaultValues: RECORD_VITALS_DEFAULTS,
  });
  const { record, pending } = useRecordHealthMetrics();
  const [formError, setFormError] = useState<string | null>(null);

  useEffect(() => { onBusyChange?.(pending); }, [pending, onBusyChange]);
  useEffect(() => { onDirtyChange?.(isDirty); }, [isDirty, onDirtyChange]);
  // A form-level message describes the values that were submitted; it goes away once they are edited.
  useEffect(() => {
    const subscription = watch(() => setFormError(null));
    return () => subscription.unsubscribe();
  }, [watch]);

  const submit = handleSubmit(
    async values => {
      setFormError(null);
      const { body, fields } = vitalsToRequest(values, patientSystemId);
      const result = await record(body);
      if (result.ok) {
        reset(RECORD_VITALS_DEFAULTS);
        onRecorded?.(result.data);
        return;
      }
      const error = result.error;
      if (!error) return; // ignored double submit
      if (error.message === 'recordedAt cannot be in the future') {
        setError('measuredTime', { type: 'server', message: 'The time of the measurement cannot be in the future' }, { shouldFocus: true });
        return;
      }
      setFormError(applyFieldErrors(error, setError, serverFieldMap(error, fields)));
    },
    errors => setFormError(vitalsFormError(errors)),
  );

  const today = clinicToday();

  return (
    <View style={styles.form}>
      <Text style={styles.intro}>
        Fill in the readings you took. You can leave the others empty.
      </Text>

      <View style={styles.group} role="group" aria-label="Blood pressure">
        <Text style={styles.groupTitle} accessibilityRole="header" {...ariaLevel(3)}>Blood pressure</Text>
        <View style={styles.row}>
          <View style={styles.cell}>
            <TextField
              control={control} name="systolic" label={fieldLabel('BP_SYSTOLIC')} inputMode="decimal"
              placeholder="120" maxLength={11} disabled={pending} autoComplete="off"
            />
          </View>
          <View style={styles.cell}>
            <TextField
              control={control} name="diastolic" label={fieldLabel('BP_DIASTOLIC')} inputMode="decimal"
              placeholder="80" maxLength={11} disabled={pending} autoComplete="off"
            />
          </View>
        </View>
        <Text style={styles.note}>Enter both values of one measurement, or leave both empty.</Text>
      </View>

      <View style={styles.group} role="group" aria-label="Other readings">
        <Text style={styles.groupTitle} accessibilityRole="header" {...ariaLevel(3)}>Other readings</Text>
        <View style={styles.row}>
          {OTHER_VITALS.map(field => (
            <View key={field.name} style={styles.cell}>
              <TextField
                control={control} name={field.name} label={fieldLabel(field.type)} inputMode="decimal"
                hint={`${METRIC_INFO[field.type].min} to ${METRIC_INFO[field.type].max}`}
                maxLength={11} disabled={pending} autoComplete="off"
              />
            </View>
          ))}
        </View>
      </View>

      <View style={styles.group} role="group" aria-label="When the readings were taken">
        <Text style={styles.groupTitle} accessibilityRole="header" {...ariaLevel(3)}>When were they taken?</Text>
        <View style={styles.row}>
          <View style={styles.cell}>
            <DateField control={control} name="measuredDate" label="Date" max={today} disabled={pending} />
          </View>
          <View style={styles.cell}>
            <TimeField control={control} name="measuredTime" label={`Time (${CLINIC_ZONE_LABEL})`} disabled={pending} />
          </View>
        </View>
        <Text style={styles.note}>
          {`Leave both empty if you took the readings just now. Times are clinic time (${CLINIC_ZONE_LABEL}).`}
        </Text>
      </View>

      <TextField
        control={control} name="notes" label="Notes" multiline rows={3} maxLength={VITALS_NOTES_MAX} showCount
        hint="Optional, for example: after exercise, before breakfast." disabled={pending}
      />

      <FormError message={formError} />
      <FormActions submitLabel="Save readings" submitIcon="checkmark" onSubmit={submit} onCancel={onCancel} submitting={pending} />
    </View>
  );
}

const styles = StyleSheet.create({
  form:       { gap: space.lg, minWidth: 0 },
  intro:      { fontSize: fontSize.md, color: colors.textMuted, lineHeight: 20 },
  group:      { gap: space.sm, minWidth: 0 },
  groupTitle: { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  row:        { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  // Two inputs side by side from about 400 px of form width; one per row below that.
  cell:       { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0 },
  note:       { fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 17 },
});
