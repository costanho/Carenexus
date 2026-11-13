import { zodResolver } from '@hookform/resolvers/zod';
import { useMemo, useRef, useState } from 'react';
import { Controller, type FieldErrors, type Path, useForm, type UseFormSetError } from 'react-hook-form';
import { PatientPicker } from '@/components/web/pickers';
import {
  Banner, type DialogCloseReason, FormActions, FormError, InfoRow, SelectField, type SelectOption, SidePanel,
  statusLabel, TextField,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import {
  createMedicalRecord, type CreateMedicalRecordBody, type DoctorMedicalRecord, type IcdCodeInput,
  type MedicalRecordDto, type RecordConsultationOption, type RecordType, updateMedicalRecord,
  type UpdateMedicalRecordBody, useRecordConsultationOptions,
} from '@/hooks/use-doctor-medical-records';
import type { ApiError } from '@/lib/api/errors';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import { formatClinicDate } from '@/lib/time/clinic';
import { confirmAction } from '@/lib/ui/dialogs';
import {
  createMedicalRecordSchema, EMPTY_RECORD_FORM, type IcdCodeRow, type MedicalRecordFormValues, RECORD_TEXT_MAX,
  RECORD_TITLE_MAX, SYSTEM_ID_PATTERN, updateMedicalRecordSchema,
} from '@/lib/validation/medical-record';
import { IcdCodeField } from './icd-code-field';
import { RECORD_TYPE_OPTIONS } from './record-meta';

export interface RecordFormProps {
  /** The record to edit; null or absent files a new record. */
  record?:   DoctorMedicalRecord | null;
  /** Preselects the patient of a new record (the `patient` URL filter). */
  initialPatientSystemId?: string | null;
  /** Called by Cancel, the Close button and Escape once any unsaved input was confirmed as discarded. */
  onClose:   () => void;
  /** Called with the saved record: `created` is true for a new record. */
  onSaved:   (record: MedicalRecordDto, created: boolean) => void;
  /** The record being edited no longer exists on the server (it was deleted meanwhile). */
  onMissing?: () => void;
}

const FIELD_LABEL: Record<string, string> = {
  patientSystemId: 'Patient',
  consultationId:  'Consultation',
  recordType:      'Record type',
  title:           'Title',
  description:     'Description',
  observations:    'Observations',
  treatmentPlan:   'Treatment plan',
  icdCodes:        'ICD-10 codes',
};

/** "is required" under the Title field reads "Title is required". */
function sentence(label: string, message: string): string {
  const text = message.trim();
  return /^[a-z]/.test(text) ? `${label} ${text}` : text;
}

/**
 * Puts the server's validation answer onto the form. Bean Validation failures arrive as `fieldErrors`
 * ("title", "icdCodes[1].code"); rules checked in the service arrive only as the message "<field>: <text>".
 * Returns what is left for the form-level error, or null when every message found its field.
 */
function applyRecordErrors(error: ApiError, setError: UseFormSetError<MedicalRecordFormValues>): string | null {
  const entries: [string, string][] = error.fieldErrors ? Object.entries(error.fieldErrors) : [];
  if (entries.length === 0 && error.code === 'VALIDATION_FAILED') {
    const parsed = /^([A-Za-z]+(?:\[\d+\])?(?:\.[A-Za-z]+)?): (.+)$/.exec(error.message);
    if (parsed) entries.push([parsed[1], parsed[2]]);
  }
  if (entries.length === 0) return error.message;

  let unmatched = 0;
  let first = true;
  for (const [field, message] of entries) {
    const row = /^icdCodes\[(\d+)\](?:\.(code|description))?$/.exec(field);
    let target: Path<MedicalRecordFormValues> | null = null;
    let text = message;
    if (row) {
      target = `icdCodes.${Number(row[1])}.${row[2] === 'description' ? 'description' : 'code'}` as Path<MedicalRecordFormValues>;
      text = sentence(row[2] === 'description' ? 'Description' : 'Code', message);
    } else if (field in FIELD_LABEL) {
      target = field as Path<MedicalRecordFormValues>;
      text = sentence(FIELD_LABEL[field], message);
    }
    if (!target) { unmatched += 1; continue; }
    setError(target, { type: 'server', message: text }, { shouldFocus: first });
    first = false;
  }
  return unmatched > 0 ? error.message : null;
}

function toIcdInputs(rows: IcdCodeRow[]): IcdCodeInput[] {
  return rows.map(row => ({ code: row.code, description: row.description.trim() || null }));
}

function toCreateBody(values: MedicalRecordFormValues): CreateMedicalRecordBody {
  return {
    patientSystemId: values.patientSystemId,
    consultationId:  values.consultationId ? Number(values.consultationId) : null,
    recordType:      values.recordType as RecordType,
    title:           values.title,
    description:     values.description || null,
    observations:    values.observations || null,
    treatmentPlan:   values.treatmentPlan || null,
    icdCodes:        toIcdInputs(values.icdCodes),
  };
}

/** All six editable fields are sent: a cleared optional text as '' (which clears it), the full code list. */
function toUpdateBody(values: MedicalRecordFormValues): UpdateMedicalRecordBody {
  return {
    recordType:    values.recordType as RecordType,
    title:         values.title,
    description:   values.description,
    observations:  values.observations,
    treatmentPlan: values.treatmentPlan,
    icdCodes:      toIcdInputs(values.icdCodes),
  };
}

function consultationLabel(option: RecordConsultationOption): string {
  const date = formatClinicDate(option.createdAt, 'Undated');
  const summary = option.summary ? (option.summary.length > 60 ? `${option.summary.slice(0, 57)}...` : option.summary) : 'Consultation';
  return `${date}: ${summary} (${statusLabel(option.status)})`;
}

function rowMessages(errors: FieldErrors<MedicalRecordFormValues>['icdCodes'], count: number): (string | null)[] {
  const out: (string | null)[] = [];
  for (let i = 0; i < count; i++) {
    const row = errors?.[i];
    out.push(row?.code?.message ?? row?.description?.message ?? null);
  }
  return out;
}

/**
 * The medical record form in a side panel. New record: patient, optional consultation, type, title, the three
 * note fields and ICD-10 codes. Editing keeps the patient and the consultation (they never change) and sends
 * every editable field. Limits equal the backend's; server messages land on their fields.
 */
export function RecordForm({ record, initialPatientSystemId, onClose, onSaved, onMissing }: RecordFormProps) {
  const editing = !!record;
  const idempotency = useIdempotencyKey();
  const [formError, setFormError] = useState<string | null>(null);
  // Escape that only closed the ICD suggestions must not also ask to close the panel.
  const escapeHandledAt = useRef(0);

  const defaults = useMemo<MedicalRecordFormValues>(() => (record
    ? {
        patientSystemId: record.patient_system_id,
        consultationId:  record.consultation_id ? String(record.consultation_id) : '',
        recordType:      record.record_type,
        title:           record.title,
        description:     record.description ?? '',
        observations:    record.observations ?? '',
        treatmentPlan:   record.treatment_plan ?? '',
        icdCodes:        record.icd_codes.map(c => ({ code: c.code, description: c.description, verified: c.verified })),
      }
    : {
        ...EMPTY_RECORD_FORM,
        patientSystemId: SYSTEM_ID_PATTERN.test(initialPatientSystemId ?? '') ? (initialPatientSystemId as string) : '',
      }),
  // The form is mounted once per open; later prop changes must not reset what was typed.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  []);

  const {
    control, handleSubmit, setError, clearErrors, setValue, watch, formState: { errors, isDirty },
  } = useForm<MedicalRecordFormValues>({
    resolver:      editing ? zodResolver(updateMedicalRecordSchema) : zodResolver(createMedicalRecordSchema),
    defaultValues: defaults,
  });

  const patientSystemId = watch('patientSystemId');
  const consultations = useRecordConsultationOptions(editing ? null : patientSystemId);
  const consultationOptions = useMemo<SelectOption[]>(
    () => consultations.options.map(o => ({ value: String(o.consultationId), label: consultationLabel(o) })),
    [consultations.options],
  );

  const save = useAction(
    (values: MedicalRecordFormValues) => (record
      ? updateMedicalRecord(record.record_id, toUpdateBody(values))
      : createMedicalRecord(toCreateBody(values), idempotency.current())),
    { errorMessage: 'The record could not be saved.' },
  );

  const close = async (reason: DialogCloseReason | 'cancel') => {
    if (reason === 'escape' && Date.now() - escapeHandledAt.current < 400) return;
    if (isDirty) {
      const discard = await confirmAction(
        editing ? 'Discard your changes?' : 'Discard this record?',
        'What you entered has not been saved.',
        'Discard',
        true,
      );
      if (!discard) return;
    }
    onClose();
  };

  const onSubmit = handleSubmit(async values => {
    setFormError(null);
    const result = await save.run(values);
    if (result.ok) {
      idempotency.renew();
      onSaved(result.data, !editing);
      return;
    }
    if (!result.error) return;
    if (editing && result.error.status === 404 && onMissing) { onMissing(); return; }
    setFormError(applyRecordErrors(result.error, setError));
  });

  const icdErrors = errors.icdCodes;
  const icdListError = icdErrors?.message ?? icdErrors?.root?.message ?? null;

  return (
    <SidePanel
      open
      size="lg"
      title={editing ? 'Edit medical record' : 'New medical record'}
      onClose={reason => { void close(reason); }}
      dismissOnBackdrop={false}
      busy={save.pending}
      footer={(
        <FormActions
          submitLabel={editing ? 'Save changes' : 'File record'}
          submitIcon={editing ? 'save-outline' : 'checkmark-outline'}
          onSubmit={() => { void onSubmit(); }}
          onCancel={() => { void close('cancel'); }}
          submitting={save.pending}
        />
      )}
    >
      {record ? (
        <>
          {record.source === 'CONSULTATION' ? (
            <Banner
              tone="info"
              text="This record was created automatically when the consultation was completed. Editing it does not change the consultation notes."
            />
          ) : null}
          <InfoRow label="Patient" value={record.patient_name} />
          <InfoRow label="Consultation" value={record.consultation_id ? 'Linked to a consultation' : 'Not linked to a consultation'} />
        </>
      ) : (
        <>
          <Controller
            control={control}
            name="patientSystemId"
            render={({ field, fieldState }) => (
              <PatientPicker
                value={field.value || null}
                onChange={id => {
                  field.onChange(id ?? '');
                  // A consultation belongs to one patient.
                  setValue('consultationId', '', { shouldDirty: true });
                }}
                required
                disabled={save.pending}
                error={fieldState.error?.message}
                hint="Only patients you have a care relationship with are listed."
              />
            )}
          />
          {consultations.error ? (
            <Banner tone="warning" text={consultations.error} action={{ label: 'Retry', onPress: consultations.refetch }} />
          ) : (
            <SelectField
              control={control}
              name="consultationId"
              label="Consultation"
              options={consultationOptions}
              allowEmpty
              placeholder={!patientSystemId ? 'Choose a patient first'
                : consultations.loading ? 'Loading…'
                  : consultationOptions.length === 0 ? 'No consultations with this patient'
                    : 'No consultation'}
              disabled={save.pending || !patientSystemId || consultations.loading || consultationOptions.length === 0}
              hint="Optional. Links the record to one of your consultations with this patient."
            />
          )}
        </>
      )}

      <SelectField control={control} name="recordType" label="Record type" options={RECORD_TYPE_OPTIONS} required disabled={save.pending} />
      <TextField
        control={control}
        name="title"
        label="Title"
        maxLength={RECORD_TITLE_MAX}
        showCount
        required
        disabled={save.pending}
      />
      <TextField
        control={control}
        name="description"
        label="Description"
        multiline
        rows={4}
        maxLength={RECORD_TEXT_MAX}
        disabled={save.pending}
      />
      <TextField
        control={control}
        name="observations"
        label="Observations"
        multiline
        rows={3}
        maxLength={RECORD_TEXT_MAX}
        disabled={save.pending}
      />
      <TextField
        control={control}
        name="treatmentPlan"
        label="Treatment plan"
        multiline
        rows={3}
        maxLength={RECORD_TEXT_MAX}
        disabled={save.pending}
      />
      <Controller
        control={control}
        name="icdCodes"
        render={({ field }) => (
          <IcdCodeField
            value={field.value ?? []}
            // Row messages are tied to positions; they are dropped as soon as the list changes.
            onChange={rows => { clearErrors('icdCodes'); field.onChange(rows); }}
            onBlur={field.onBlur}
            error={icdListError}
            rowErrors={rowMessages(icdErrors, (field.value ?? []).length)}
            disabled={save.pending}
            onEscapeConsumed={() => { escapeHandledAt.current = Date.now(); }}
          />
        )}
      />
      <FormError message={formError} />
    </SidePanel>
  );
}
