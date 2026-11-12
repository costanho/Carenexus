import { zodResolver } from '@hookform/resolvers/zod';
import { useMemo, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { StyleSheet, View } from 'react-native';
import { PatientPicker } from '@/components/web/pickers';
import {
  Banner, DateField, type DialogCloseReason, FormActions, FormError, SelectField, type SelectOption, SidePanel,
  space, statusLabel, TextField,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { useCurrentUser } from '@/hooks/use-current-user';
import {
  createPrescription, listConsultationOptions, type PrescriptionConsultationOption, type PrescriptionDto,
} from '@/hooks/use-doctor-prescriptions';
import { useResource } from '@/hooks/use-resource';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import { clinicToday, formatClinicDate } from '@/lib/time/clinic';
import { confirmAction } from '@/lib/ui/dialogs';
import {
  type CreatePrescriptionBody, emptyNewPrescription, NEW_PRESCRIPTION_FIELDS, newPrescriptionSchema,
  type NewPrescriptionValues, PATIENT_SYSTEM_ID, PRESCRIPTION_LIMITS, toCreatePrescriptionBody,
} from '@/lib/validation/prescription';
import { ROUTE_OPTIONS } from './labels';
import { ScheduleTimesField } from './schedule-times-field';
import { withFieldErrors } from './server-errors';

export interface PrescriptionSavedInfo {
  differsFromForm: boolean;
  scheduleTimes:   string[];
}

export interface PrescriptionFormProps {
  open:    boolean;
  /** Called when the form is dismissed without issuing a prescription. */
  onClose: () => void;
  /**
   * Called with the stored prescription. `differsFromForm` is true when the server answered with the result of an
   * earlier attempt of this same form (same Idempotency-Key) whose details are not the ones now in the form.
   * `scheduleTimes` are the dose times that were sent (empty when the doctor gave none).
   */
  onSaved: (prescription: PrescriptionDto, info: PrescriptionSavedInfo) => void;
  /** Patient to start with (PAT id), for example the patient of the consultation the form was opened from. */
  patientSystemId?: string | null;
  /** Consultation to link, prefilled when the form is opened from a consultation. */
  consultationId?:  number | null;
}

/** "New prescription" in a side panel. Renders nothing while closed, so every opening starts from an empty form. */
export function PrescriptionForm(props: PrescriptionFormProps) {
  return props.open ? <OpenForm {...props} /> : null;
}

function consultationLabel(c: PrescriptionConsultationOption): string {
  const day = formatClinicDate(c.createdAt, `Consultation ${c.consultationId}`);
  const complaint = c.chiefComplaint ? ` · ${c.chiefComplaint.length > 60 ? `${c.chiefComplaint.slice(0, 57)}…` : c.chiefComplaint}` : '';
  return `${day} · ${statusLabel(c.status)}${complaint}`;
}

/** True when the stored prescription is not the one described by the request body (compared on what cannot be edited later). */
function differs(stored: PrescriptionDto, body: CreatePrescriptionBody): boolean {
  const same = (a: string | null | undefined, b: string | null | undefined) => (a ?? '').trim() === (b ?? '').trim();
  return !(
    same(stored.patientSystemId, body.patientSystemId)
    && same(stored.medicationName, body.medicationName)
    && same(stored.genericName, body.genericName)
    && same(stored.dosage, body.dosage)
    && same(stored.frequency, body.frequency)
    && same(stored.quantity, body.quantity)
    && stored.route === body.route
    && (stored.consultationId ?? null) === (body.consultationId ?? null)
  );
}

function OpenForm({ onClose, onSaved, patientSystemId, consultationId }: PrescriptionFormProps) {
  const { user } = useCurrentUser();
  const doctorSystemId = user?.system_id ?? '';

  const today = useMemo(() => clinicToday(), []);
  const schema = useMemo(() => newPrescriptionSchema(today), [today]);
  const seedPatient = patientSystemId && PATIENT_SYSTEM_ID.test(patientSystemId) ? patientSystemId : null;
  const seedConsultation = consultationId && Number.isInteger(consultationId) && consultationId > 0 ? consultationId : null;

  const { control, handleSubmit, setError, setValue, watch, formState: { isDirty } } = useForm<NewPrescriptionValues>({
    resolver:      zodResolver(schema),
    defaultValues: emptyNewPrescription({ patientSystemId: seedPatient, consultationId: seedConsultation }),
  });
  const [formError, setFormError] = useState<string | null>(null);

  const patient = watch('patientSystemId');
  const linkedConsultation = watch('consultationId');

  // The doctor's own consultations with the chosen patient, to link the prescription to one of them.
  const consultations = useResource(
    () => listConsultationOptions(patient, doctorSystemId),
    [patient, doctorSystemId],
    { enabled: !!patient && !!doctorSystemId, errorMessage: 'The consultations of this patient could not be loaded.' },
  );
  const consultationOptions = useMemo<SelectOption[]>(() => {
    const options = (consultations.data ?? []).map(c => ({ value: String(c.consultationId), label: consultationLabel(c) }));
    // A consultation handed over by the consultation screen stays selectable when the list failed or does not reach it.
    if (linkedConsultation && !options.some(o => o.value === linkedConsultation)) {
      options.unshift({ value: linkedConsultation, label: `Consultation ${linkedConsultation}` });
    }
    return options;
  }, [consultations.data, linkedConsultation]);

  const key = useIdempotencyKey();
  const save = useAction((body: CreatePrescriptionBody) => createPrescription(body, key.current()));

  const submit = handleSubmit(async values => {
    setFormError(null);
    const body = toCreatePrescriptionBody(values);
    const result = await save.run(body);
    if (result.ok) {
      key.renew();
      onSaved(result.data.prescription, {
        differsFromForm: result.data.replayed && differs(result.data.prescription, body),
        scheduleTimes:   body.scheduleTimes,
      });
      return;
    }
    const error = result.error;
    if (!error) return; // a second press while the first request is still running
    // The server refused the request, so nothing was stored under this key: the corrected form is a new request.
    // After a failure with an unknown outcome (no response, a server error, "still being processed") the key is
    // kept, so a retry returns the first attempt's prescription instead of issuing a second one.
    const refused = error.status >= 400 && error.status < 500
      && error.status !== 408 && error.status !== 429 && error.code !== 'IDEMPOTENCY_IN_PROGRESS';
    if (refused) key.renew();
    setFormError(applyFieldErrors(withFieldErrors(error, NEW_PRESCRIPTION_FIELDS), setError, NEW_PRESCRIPTION_FIELDS));
  });

  const close = async (_reason?: DialogCloseReason) => {
    if (save.pending) return;
    if (isDirty && !(await confirmAction('Discard this prescription?', 'What you entered has not been saved.', 'Discard', true))) return;
    onClose();
  };

  const noConsultations = !!patient && !consultations.loading && !consultations.error && consultationOptions.length === 0;
  const consultationPlaceholder = !patient ? 'Choose a patient first'
    : consultations.loading ? 'Loading…'
    : noConsultations ? 'No consultations with this patient'
    : 'Not linked to a consultation';

  return (
    <SidePanel
      open
      title="New prescription"
      size="lg"
      onClose={close}
      dismissOnBackdrop={false}
      busy={save.pending}
      footer={(
        <FormActions
          submitLabel="Issue prescription"
          submitIcon="medkit-outline"
          onSubmit={submit}
          onCancel={() => { void close(); }}
          submitting={save.pending}
        />
      )}
    >
      <FormError message={formError} />

      <Controller
        control={control}
        name="patientSystemId"
        render={({ field, fieldState }) => (
          <PatientPicker
            value={field.value || null}
            onChange={id => {
              field.onChange(id ?? '');
              // A consultation belongs to one patient: it cannot stay linked when the patient changes.
              if ((id ?? '') !== field.value) setValue('consultationId', '', { shouldDirty: true });
            }}
            required
            error={fieldState.error?.message}
            disabled={save.pending}
            hint="Only patients in your care are listed."
          />
        )}
      />

      <SelectField
        control={control}
        name="consultationId"
        label="Consultation"
        options={consultationOptions}
        placeholder={consultationPlaceholder}
        allowEmpty={!!patient && !consultations.loading && consultationOptions.length > 0}
        disabled={save.pending || !patient || consultations.loading || consultationOptions.length === 0}
        hint="Optional. Links the prescription to one of your consultations with this patient."
      />
      {consultations.error ? (
        <Banner tone="warning" text={consultations.error} action={{ label: 'Retry', onPress: consultations.refetch }} />
      ) : null}

      <View style={styles.row}>
        <View style={styles.cell}>
          <TextField
            control={control} name="medicationName" label="Medication name" required
            maxLength={PRESCRIPTION_LIMITS.medicationName} placeholder="For example Amlodipine" disabled={save.pending}
          />
        </View>
        <View style={styles.cell}>
          <TextField
            control={control} name="genericName" label="Generic name"
            maxLength={PRESCRIPTION_LIMITS.genericName} placeholder="For example amlodipine besylate" disabled={save.pending}
          />
        </View>
      </View>

      <View style={styles.row}>
        <View style={styles.cell}>
          <TextField
            control={control} name="dosage" label="Dosage" required
            maxLength={PRESCRIPTION_LIMITS.dosage} placeholder="For example 5 mg" disabled={save.pending}
          />
        </View>
        <View style={styles.cell}>
          <TextField
            control={control} name="frequency" label="Frequency" required
            maxLength={PRESCRIPTION_LIMITS.frequency} placeholder="For example Twice daily" disabled={save.pending}
          />
        </View>
      </View>

      <View style={styles.row}>
        <View style={styles.cell}>
          <SelectField control={control} name="route" label="Route" options={ROUTE_OPTIONS} required disabled={save.pending} />
        </View>
        <View style={styles.cell}>
          <TextField
            control={control} name="quantity" label="Quantity"
            maxLength={PRESCRIPTION_LIMITS.quantity} placeholder="For example 30 tablets" disabled={save.pending}
          />
        </View>
      </View>

      <View style={styles.row}>
        <View style={styles.cell}>
          <TextField
            control={control} name="refillsAllowed" label="Refills allowed" required
            inputMode="numeric" maxLength={2} disabled={save.pending}
            hint={`A whole number from ${PRESCRIPTION_LIMITS.refillsMin} to ${PRESCRIPTION_LIMITS.refillsMax}.`}
          />
        </View>
        <View style={styles.cell}>
          <DateField
            control={control} name="expiryDate" label="Expiry date" min={today} disabled={save.pending}
            hint="Optional. The last day the prescription is valid; leave empty for no expiry."
          />
        </View>
      </View>

      <TextField
        control={control} name="specialInstructions" label="Instructions" multiline rows={4}
        maxLength={PRESCRIPTION_LIMITS.specialInstructions} showCount disabled={save.pending}
        placeholder="For example: take in the morning with water"
      />

      <Controller
        control={control}
        name="scheduleTimes"
        render={({ field, fieldState }) => (
          <ScheduleTimesField
            value={field.value}
            onChange={field.onChange}
            onBlur={field.onBlur}
            error={fieldState.error?.message ?? fieldState.error?.root?.message}
            disabled={save.pending}
            hint={'Optional. The times of day the patient is reminded to take a dose. Without times, a schedule is worked '
              + 'out from the frequency when it reads like "once daily" or "three times a day"; otherwise no doses are '
              + 'scheduled until you set the times.'}
          />
        )}
      />
    </SidePanel>
  );
}

const styles = StyleSheet.create({
  // Two fields side by side on a wide panel, stacked when the panel is narrow.
  row:  { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  cell: { flexGrow: 1, flexShrink: 1, flexBasis: 220, minWidth: 0 },
});
