import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import {
  ariaPressed, Banner, colors, CONTROL_HEIGHT, fontSize, FormActions, FormError, Icon, radius, SelectField, space,
  TagListField, TextField, useUiTheme,
} from '@/components/web/ui';
import { useLogSymptoms } from '@/hooks/use-symptom-logs';
import type { ApiError } from '@/lib/api/errors';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { COMMON_SYMPTOMS, SYMPTOM_SEVERITY_OPTIONS } from '@/lib/health/catalog';
import type { SymptomLog } from '@/lib/health/types';
import {
  SYMPTOM_LOG_DEFAULTS, SYMPTOM_NOTES_MAX, SYMPTOM_TEXT_MAX, symptomLogSchema, SYMPTOMS_MAX, symptomsToRequest,
  type SymptomLogValues,
} from '@/lib/validation/health';

export interface SymptomFormProps {
  /** The patient the symptoms belong to. Required for a doctor or caregiver; a patient may leave it out. */
  patientSystemId?: string | null;
  /** Called with the stored log after a successful save. */
  onLogged?:        (log: SymptomLog) => void;
  /** Shows a Cancel button that calls it. */
  onCancel?:        () => void;
  /** Reports whether a save is in flight, so a surrounding dialog can refuse to close meanwhile. */
  onBusyChange?:    (busy: boolean) => void;
  /** Reports whether anything was entered, so a surrounding dialog can ask before discarding it. */
  onDirtyChange?:   (dirty: boolean) => void;
}

const sameSymptom = (a: string, b: string) => a.trim().toLowerCase() === b.trim().toLowerCase();

/** Server field names to form fields; an error on one entry (`symptoms[2]`) is shown on the list. */
function serverFieldMap(error: ApiError): Record<string, keyof SymptomLogValues> {
  const map: Record<string, keyof SymptomLogValues> = {
    symptoms: 'symptoms', severity: 'severity', durationDays: 'durationDays', notes: 'notes',
  };
  for (const name of Object.keys(error.fieldErrors ?? {})) {
    if (/^symptoms\[\d+\]$/.test(name)) map[name] = 'symptoms';
  }
  return map;
}

/**
 * Logs a set of symptoms: quick picks for common ones plus free text, how severe they are, for how long, and
 * notes. Limits match the server (1 to 20 symptoms of at most 100 characters, 0 to 3650 days, 2000 characters
 * of notes).
 */
export function SymptomForm({ patientSystemId, onLogged, onCancel, onBusyChange, onDirtyChange }: SymptomFormProps) {
  const { accent, accentBg } = useUiTheme();
  const { control, handleSubmit, setError, setValue, reset, watch, formState: { isDirty, isSubmitted } } = useForm<SymptomLogValues>({
    resolver:      zodResolver(symptomLogSchema),
    defaultValues: SYMPTOM_LOG_DEFAULTS,
  });
  const { log, pending } = useLogSymptoms();
  const [formError, setFormError] = useState<string | null>(null);

  const symptoms = useWatch({ control, name: 'symptoms' }) ?? [];
  const severity = useWatch({ control, name: 'severity' });
  const full = symptoms.length >= SYMPTOMS_MAX;

  useEffect(() => { onBusyChange?.(pending); }, [pending, onBusyChange]);
  useEffect(() => { onDirtyChange?.(isDirty); }, [isDirty, onDirtyChange]);
  useEffect(() => {
    const subscription = watch(() => setFormError(null));
    return () => subscription.unsubscribe();
  }, [watch]);

  const toggle = (symptom: string) => {
    const chosen = symptoms.some(s => sameSymptom(s, symptom));
    if (!chosen && full) return;
    const next = chosen ? symptoms.filter(s => !sameSymptom(s, symptom)) : [...symptoms, symptom];
    setValue('symptoms', next, { shouldDirty: true, shouldTouch: true, shouldValidate: isSubmitted });
  };

  const submit = handleSubmit(async values => {
    setFormError(null);
    const result = await log(symptomsToRequest(values, patientSystemId));
    if (result.ok) {
      reset(SYMPTOM_LOG_DEFAULTS);
      onLogged?.(result.data);
      return;
    }
    if (result.error) setFormError(applyFieldErrors(result.error, setError, serverFieldMap(result.error)));
  });

  return (
    <View style={styles.form}>
      <View style={styles.group}>
        <Text style={styles.groupTitle}>Common symptoms</Text>
        <View style={styles.chips} role="group" aria-label="Common symptoms">
          {COMMON_SYMPTOMS.map(symptom => {
            const chosen = symptoms.some(s => sameSymptom(s, symptom));
            const off = pending || (!chosen && full);
            return (
              <Pressable
                key={symptom}
                accessibilityRole="button"
                accessibilityLabel={symptom}
                accessibilityState={{ selected: chosen, disabled: off }}
                {...ariaPressed(chosen)}
                disabled={off}
                onPress={() => toggle(symptom)}
                style={[
                  styles.chip,
                  chosen && { borderColor: accent, backgroundColor: accentBg },
                  off && !chosen && styles.chipOff,
                ]}
              >
                {/* The tick shows the choice without relying on the tint. */}
                {chosen ? <Icon name="checkmark" size={14} color={accent} /> : null}
                <Text style={[styles.chipText, chosen && { color: accent, fontWeight: '700' }]}>{symptom}</Text>
              </Pressable>
            );
          })}
        </View>
      </View>

      <TagListField
        control={control}
        name="symptoms"
        label="Symptoms to log"
        required
        addLabel="Add"
        placeholder="Type another symptom"
        maxItems={SYMPTOMS_MAX}
        maxLength={SYMPTOM_TEXT_MAX}
        hint={`Pick from the list above or type your own and press Enter. Up to ${SYMPTOMS_MAX} symptoms.`}
        disabled={pending}
      />

      <View style={styles.row}>
        <View style={styles.cell}>
          <SelectField
            control={control}
            name="severity"
            label="Severity"
            required
            placeholder="Select severity"
            options={SYMPTOM_SEVERITY_OPTIONS}
            disabled={pending}
          />
        </View>
        <View style={styles.cell}>
          <TextField
            control={control}
            name="durationDays"
            label="Duration (days)"
            inputMode="numeric"
            maxLength={4}
            hint="Optional. Enter 0 if they started today."
            disabled={pending}
            autoComplete="off"
          />
        </View>
      </View>

      {severity === 'SEVERE' || severity === 'CRITICAL' ? (
        <Banner
          tone="warning"
          text="Doctors on the care team are notified about severe and critical symptoms. This is not an emergency service: if urgent help is needed, call your local emergency number."
        />
      ) : null}

      <TextField
        control={control}
        name="notes"
        label="Notes"
        multiline
        rows={4}
        maxLength={SYMPTOM_NOTES_MAX}
        showCount
        hint="Optional: when they started, what makes them better or worse."
        disabled={pending}
      />

      <FormError message={formError} />
      <FormActions submitLabel="Save symptoms" submitIcon="checkmark" onSubmit={submit} onCancel={onCancel} submitting={pending} />
    </View>
  );
}

const styles = StyleSheet.create({
  form:       { gap: space.lg, minWidth: 0 },
  group:      { gap: space.sm, minWidth: 0 },
  groupTitle: { fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  chips:      { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  chip: {
    minHeight: CONTROL_HEIGHT, flexDirection: 'row', alignItems: 'center', gap: space.xs, maxWidth: '100%',
    paddingHorizontal: space.md, paddingVertical: space.xs,
    borderWidth: 1, borderColor: colors.borderInput, borderRadius: radius.pill, backgroundColor: colors.surface,
  },
  chipOff:    { backgroundColor: colors.bgSubtle },
  chipText:   { flexShrink: 1, fontSize: fontSize.sm, fontWeight: '500', color: colors.text },
  row:        { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  cell:       { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0 },
});
