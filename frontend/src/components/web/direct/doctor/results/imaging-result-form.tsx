import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { StyleSheet, View } from 'react-native';
import { PatientPicker } from '@/components/web/pickers';
import {
  DateField, FormActions, FormError, InfoRow, SelectField, type SelectOption, SidePanel, space, TextField,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import {
  type CreateImagingResultBody, createImagingResult, type ImagingResultDto, type UpdateImagingResultBody,
  updateImagingResult,
} from '@/hooks/use-doctor-imaging-results';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import { clinicToday } from '@/lib/time/clinic';
import { confirmAction } from '@/lib/ui/dialogs';
import {
  IMAGING_LIMITS, IMAGING_TYPE_LABELS, IMAGING_TYPES, type ImagingResultFormValues, imagingResultFormSchema,
  type ImagingType, RESULT_STATUS_LABELS, WRITABLE_RESULT_STATUSES, type WritableResultStatus,
} from '@/lib/validation/results';
import { ConsultationSelect } from './consultation-select';
import { RESULT_DOCUMENT_TYPE, type ResultAttachmentSlot } from './shared';

// The doctor's "Record imaging result" form, also used to edit a result the doctor recorded.

export interface ImagingResultFormProps {
  open:     boolean;
  /** The result being edited; null to record a new one. */
  editing:  ImagingResultDto | null;
  /** A new result starts with this patient (the `patient` URL param). */
  initialPatientSystemId?: string | null;
  /** A new result starts linked to this consultation (the `consultationId` URL param). */
  initialConsultationId?:  number | null;
  /** The documents upload control; without it the form has no attachment field. */
  attachmentSlot?: ResultAttachmentSlot;
  onClose:  () => void;
  /** Called with the result as the server stored it. */
  onSaved:  (result: ImagingResultDto, created: boolean) => void;
}

export function ImagingResultForm(props: ImagingResultFormProps) {
  // Mounted per opening: the fields, the attachment and the idempotency key all start fresh.
  return props.open ? <Form {...props} /> : null;
}

const TYPE_OPTIONS: SelectOption[] = IMAGING_TYPES.map(t => ({ value: t, label: IMAGING_TYPE_LABELS[t] }));

const STATUS_HINT: Record<WritableResultStatus, string> = {
  PENDING:         'Not final yet. Nobody is notified.',
  REQUIRES_REVIEW: 'Waiting for a doctor to sign it off. It appears in the review queue.',
  COMPLETED:       'Final. The patient is told that a new result is available.',
  CRITICAL:        'Final and urgent. The patient is alerted with high priority.',
};

/** Form fields the server can report an error on. */
const SERVER_FIELDS = [
  'patientSystemId', 'consultationId', 'imagingType', 'bodyPart', 'findings', 'impression', 'radiologistReport',
  'status', 'imageDate',
] as const;

function initialValues(editing: ImagingResultDto | null, patient: string | null, consultation: number | null): ImagingResultFormValues {
  if (editing) {
    return {
      patientSystemId:   editing.patientSystemId,
      consultationId:    editing.consultationId != null ? String(editing.consultationId) : '',
      imagingType:       editing.imagingType,
      bodyPart:          editing.bodyPart ?? '',
      findings:          editing.findings ?? '',
      impression:        editing.impression ?? '',
      radiologistReport: editing.radiologistReport ?? '',
      status:            editing.status,
      imageDate:         editing.imageDate,
    };
  }
  return {
    patientSystemId: patient ?? '',
    consultationId:  consultation != null ? String(consultation) : '',
    imagingType: '', bodyPart: '', findings: '', impression: '', radiologistReport: '',
    status: 'PENDING',
    imageDate: clinicToday(),
  };
}

function createBody(values: ImagingResultFormValues, documentId: number | null): CreateImagingResultBody {
  const body: CreateImagingResultBody = {
    patientSystemId: values.patientSystemId,
    imagingType:     values.imagingType as ImagingType,
    status:          values.status as WritableResultStatus,
    imageDate:       values.imageDate,
  };
  if (values.consultationId) body.consultationId = Number(values.consultationId);
  if (values.bodyPart) body.bodyPart = values.bodyPart;
  if (values.findings) body.findings = values.findings;
  if (values.impression) body.impression = values.impression;
  if (values.radiologistReport) body.radiologistReport = values.radiologistReport;
  if (documentId != null) body.documentId = documentId;
  return body;
}

/** Only what changed is sent: a field left out stays as it is, an optional text sent as '' is cleared. */
function updateBody(values: ImagingResultFormValues, editing: ImagingResultDto, documentId: number | null): UpdateImagingResultBody {
  const body: UpdateImagingResultBody = {};
  if (values.imagingType !== editing.imagingType) body.imagingType = values.imagingType as ImagingType;
  if (values.bodyPart !== (editing.bodyPart ?? '')) body.bodyPart = values.bodyPart;
  if (values.findings !== (editing.findings ?? '')) body.findings = values.findings;
  if (values.impression !== (editing.impression ?? '')) body.impression = values.impression;
  if (values.radiologistReport !== (editing.radiologistReport ?? '')) body.radiologistReport = values.radiologistReport;
  if (values.status !== editing.status) body.status = values.status as WritableResultStatus;
  if (values.imageDate !== editing.imageDate) body.imageDate = values.imageDate;
  // An attachment cannot be removed through the result, only replaced.
  if (documentId != null && documentId !== editing.documentId) body.documentId = documentId;
  return body;
}

function Form({
  editing, initialPatientSystemId = null, initialConsultationId = null, attachmentSlot, onClose, onSaved,
}: ImagingResultFormProps) {
  const defaults = useMemo(
    () => initialValues(editing, initialPatientSystemId, initialConsultationId),
    // The form is mounted once per opening; its starting values do not follow later prop changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );
  const { control, handleSubmit, setError, setValue, formState: { isDirty } } = useForm<ImagingResultFormValues>({
    resolver:      zodResolver(imagingResultFormSchema),
    defaultValues: defaults,
  });
  const status  = useWatch({ control, name: 'status' });
  const patient = useWatch({ control, name: 'patientSystemId' });

  const key = useIdempotencyKey();
  const [formError, setFormError] = useState<string | null>(null);
  const [documentId, setDocumentId] = useState<number | null>(editing?.documentId ?? null);
  const attachmentChanged = documentId !== (editing?.documentId ?? null);

  const save = useAction(
    (body: CreateImagingResultBody | UpdateImagingResultBody) => (editing
      ? updateImagingResult(editing.imagingId, body as UpdateImagingResultBody)
      : createImagingResult(body as CreateImagingResultBody, key.current())),
    { errorMessage: 'The result could not be saved. Please try again.' },
  );
  const pending = save.pending;

  // Another patient has other consultations, and a file uploaded for the first patient cannot be attached.
  const previousPatient = useRef(patient);
  useEffect(() => {
    if (previousPatient.current === patient) return;
    previousPatient.current = patient;
    if (editing) return;
    setValue('consultationId', '', { shouldDirty: true });
    setDocumentId(null);
  }, [patient, editing, setValue]);

  const statusOptions = useMemo<SelectOption[]>(() => (
    WRITABLE_RESULT_STATUSES
      // A recorded result cannot go back to pending.
      .filter(s => s !== 'PENDING' || !editing || editing.status === 'PENDING')
      .map(s => ({ value: s, label: RESULT_STATUS_LABELS[s] }))
  ), [editing]);

  const submit = handleSubmit(async values => {
    setFormError(null);
    let body: CreateImagingResultBody | UpdateImagingResultBody;
    if (editing) {
      body = updateBody(values, editing, documentId);
      if (Object.keys(body).length === 0) { onClose(); return; }
    } else {
      body = createBody(values, documentId);
    }
    const result = await save.run(body);
    if (result.ok) {
      key.renew();
      onSaved(result.data, !editing);
    } else if (result.error) {
      setFormError(applyFieldErrors(result.error, setError, SERVER_FIELDS));
    }
  });

  const close = async () => {
    if ((isDirty || attachmentChanged)
      && !(await confirmAction('Discard this result?', 'What you entered will be lost.', 'Discard', true))) return;
    onClose();
  };

  return (
    <SidePanel
      open
      title={editing ? 'Edit imaging result' : 'Record imaging result'}
      onClose={close}
      size="lg"
      dismissOnBackdrop={false}
      busy={pending}
      footer={(
        <View style={styles.footer}>
          <FormError message={formError} />
          <FormActions
            submitLabel={editing ? 'Save changes' : 'Record result'}
            submitIcon="save-outline"
            onSubmit={submit}
            onCancel={close}
            submitting={pending}
          />
        </View>
      )}
    >
      <View style={styles.form}>
        {editing ? (
          <View>
            <InfoRow label="Patient" value={editing.patientName} emptyText="Unknown patient" />
            <InfoRow
              label="Consultation"
              value={editing.consultationId != null ? `Consultation #${editing.consultationId}` : null}
              emptyText="Not linked to a consultation"
            />
          </View>
        ) : (
          <>
            <Controller
              control={control}
              name="patientSystemId"
              render={({ field, fieldState }) => (
                <PatientPicker
                  value={field.value || null}
                  onChange={id => field.onChange(id ?? '')}
                  required
                  disabled={pending}
                  error={fieldState.error?.message}
                />
              )}
            />
            <Controller
              control={control}
              name="consultationId"
              render={({ field, fieldState }) => (
                <ConsultationSelect
                  patientSystemId={patient || null}
                  value={field.value}
                  onChange={field.onChange}
                  onBlur={field.onBlur}
                  disabled={pending}
                  error={fieldState.error?.message}
                />
              )}
            />
          </>
        )}

        <View style={styles.row}>
          <View style={styles.cell}>
            <SelectField
              label="Imaging type"
              control={control}
              name="imagingType"
              options={TYPE_OPTIONS}
              placeholder="Select an imaging type"
              required
              disabled={pending}
            />
          </View>
          <View style={styles.cell}>
            <TextField
              label="Body part"
              control={control}
              name="bodyPart"
              maxLength={IMAGING_LIMITS.bodyPart}
              placeholder="For example Chest"
              disabled={pending}
            />
          </View>
        </View>

        <View style={styles.row}>
          <View style={styles.cell}>
            <DateField
              label="Image date"
              control={control}
              name="imageDate"
              max={clinicToday()}
              required
              hint="The day the image was made. Not in the future."
              disabled={pending}
            />
          </View>
          <View style={styles.cell}>
            <SelectField
              label="Status"
              control={control}
              name="status"
              options={statusOptions}
              required
              hint={STATUS_HINT[status as WritableResultStatus]}
              disabled={pending}
            />
          </View>
        </View>

        <TextField
          label="Findings"
          control={control}
          name="findings"
          multiline
          rows={4}
          maxLength={IMAGING_LIMITS.findings}
          showCount
          disabled={pending}
        />
        <TextField
          label="Impression"
          control={control}
          name="impression"
          multiline
          rows={3}
          maxLength={IMAGING_LIMITS.impression}
          showCount
          disabled={pending}
        />
        <TextField
          label="Radiologist report"
          control={control}
          name="radiologistReport"
          multiline
          rows={6}
          maxLength={IMAGING_LIMITS.radiologistReport}
          showCount
          disabled={pending}
        />

        {attachmentSlot ? (
          <View>
            {attachmentSlot({
              kind:            'IMAGING',
              documentType:    RESULT_DOCUMENT_TYPE.IMAGING,
              patientSystemId: patient || null,
              documentId,
              onChange:        setDocumentId,
              disabled:        pending,
            })}
          </View>
        ) : null}
      </View>
    </SidePanel>
  );
}

const styles = StyleSheet.create({
  form:   { gap: space.lg },
  footer: { gap: space.sm },
  row:    { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  cell:   { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0 },
});
