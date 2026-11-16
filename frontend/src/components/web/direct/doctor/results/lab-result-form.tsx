import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Controller, useFieldArray, useForm, useWatch } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import { PatientPicker } from '@/components/web/pickers';
import {
  ariaLevel, Button, colors, DateField, fontSize, FormActions, FormError, IconButton, InfoRow, radius, SelectField,
  type SelectOption, SidePanel, space, statusLabel, SwitchField, TextField,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import {
  buildLabResultData, type CreateLabResultBody, createLabResult, type DoctorLabResult, type LabSubTestInput,
  type UpdateLabResultBody, updateLabResult,
} from '@/hooks/use-doctor-lab-results';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import { clinicToday } from '@/lib/time/clinic';
import { confirmAction } from '@/lib/ui/dialogs';
import {
  EMPTY_SUB_TEST, LAB_FLAGS, LAB_LIMITS, LAB_TEST_TYPE_LABELS, LAB_TEST_TYPES, type LabResultFormValues,
  labResultFormSchema, type LabTestType, RESULT_STATUS_LABELS, SUB_TEST_LIMITS, WRITABLE_RESULT_STATUSES,
  type WritableResultStatus,
} from '@/lib/validation/results';
import { ConsultationSelect } from './consultation-select';
import { RESULT_DOCUMENT_TYPE, type ResultAttachmentSlot } from './shared';

// The doctor's "Record lab result" form, also used to edit a result the doctor recorded.

export interface LabResultFormProps {
  open:     boolean;
  /** The result being edited; null to record a new one. */
  editing:  DoctorLabResult | null;
  /** A new result starts with this patient (the `patient` URL param). */
  initialPatientSystemId?: string | null;
  /** A new result starts linked to this consultation (the `consultationId` URL param). */
  initialConsultationId?:  number | null;
  /** The documents upload control; without it the form has no attachment field. */
  attachmentSlot?: ResultAttachmentSlot;
  onClose:  () => void;
  /** Called with the result as the server stored it (its status can differ from the one chosen). */
  onSaved:  (result: DoctorLabResult, created: boolean) => void;
}

export function LabResultForm(props: LabResultFormProps) {
  // Mounted per opening: the fields, the attachment and the idempotency key all start fresh.
  return props.open ? <Form {...props} /> : null;
}

const TYPE_OPTIONS: SelectOption[] = LAB_TEST_TYPES.map(t => ({ value: t, label: LAB_TEST_TYPE_LABELS[t] }));

const STATUS_HINT: Record<WritableResultStatus, string> = {
  PENDING:         'Not final yet. Nobody is notified.',
  REQUIRES_REVIEW: 'Waiting for a doctor to sign it off. It appears in the review queue.',
  COMPLETED:       'Final. The patient is told that a new result is available.',
  CRITICAL:        'Final and urgent. The patient is alerted with high priority.',
};

/** Form fields the server can report an error on. */
const SERVER_FIELDS = [
  'patientSystemId', 'consultationId', 'testName', 'testType', 'resultValue', 'unit', 'referenceRange',
  'status', 'isCritical', 'testDate',
] as const;

function rowsOf(result: DoctorLabResult): LabResultFormValues['subTests'] {
  return Object.entries(result.result_data ?? {}).map(([name, sub]) => ({
    name,
    value:          String(sub.value ?? ''),
    unit:           sub.unit ?? '',
    reference:      sub.reference ?? '',
    flag:           sub.flag ?? '',
    interpretation: sub.interpretation ?? '',
  }));
}

function initialValues(editing: DoctorLabResult | null, patient: string | null, consultation: number | null): LabResultFormValues {
  if (editing) {
    return {
      patientSystemId: editing.patient_system_id,
      consultationId:  editing.consultation_id != null ? String(editing.consultation_id) : '',
      testName:        editing.test_name,
      testType:        editing.test_type,
      resultValue:     editing.result_value ?? '',
      unit:            editing.unit ?? '',
      referenceRange:  editing.reference_range ?? '',
      subTests:        rowsOf(editing),
      status:          editing.status,
      isCritical:      !!editing.is_critical,
      testDate:        editing.test_date,
    };
  }
  return {
    patientSystemId: patient ?? '',
    consultationId:  consultation != null ? String(consultation) : '',
    testName: '', testType: '', resultValue: '', unit: '', referenceRange: '',
    subTests: [],
    status: 'PENDING',
    isCritical: false,
    testDate: clinicToday(),
  };
}

/** A critical status always carries the critical flag (the server rejects the opposite). */
function criticalOf(values: LabResultFormValues): boolean {
  return values.status === 'CRITICAL' ? true : values.isCritical;
}

function createBody(values: LabResultFormValues, resultData: string, documentId: number | null): CreateLabResultBody {
  const body: CreateLabResultBody = {
    patientSystemId: values.patientSystemId,
    testName:        values.testName,
    testType:        values.testType as LabTestType,
    status:          values.status as WritableResultStatus,
    isCritical:      criticalOf(values),
    testDate:        values.testDate,
  };
  if (values.consultationId) body.consultationId = Number(values.consultationId);
  if (values.resultValue) body.resultValue = values.resultValue;
  if (values.unit) body.unit = values.unit;
  if (values.referenceRange) body.referenceRange = values.referenceRange;
  if (resultData) body.resultData = resultData;
  if (documentId != null) body.documentId = documentId;
  return body;
}

/** Only what changed is sent: a field left out stays as it is, an optional text sent as '' is cleared. */
function updateBody(
  values: LabResultFormValues, editing: DoctorLabResult, resultData: string, documentId: number | null,
): UpdateLabResultBody {
  const body: UpdateLabResultBody = {};
  if (values.testName !== editing.test_name) body.testName = values.testName;
  if (values.testType !== editing.test_type) body.testType = values.testType as LabTestType;
  if (values.resultValue !== (editing.result_value ?? '')) body.resultValue = values.resultValue;
  if (values.unit !== (editing.unit ?? '')) body.unit = values.unit;
  if (values.referenceRange !== (editing.reference_range ?? '')) body.referenceRange = values.referenceRange;
  // Compared with the same serialisation of the stored rows, so re-ordering by the database is not a change.
  if (resultData !== buildLabResultData(rowsOf(editing), editing.result_data_raw)) body.resultData = resultData;
  if (values.status !== editing.status) body.status = values.status as WritableResultStatus;
  if (criticalOf(values) !== !!editing.is_critical) body.isCritical = criticalOf(values);
  if (values.testDate !== editing.test_date) body.testDate = values.testDate;
  // An attachment cannot be removed through the result, only replaced.
  if (documentId != null && documentId !== editing.document_id) body.documentId = documentId;
  return body;
}

function Form({
  editing, initialPatientSystemId = null, initialConsultationId = null, attachmentSlot, onClose, onSaved,
}: LabResultFormProps) {
  const defaults = useMemo(
    () => initialValues(editing, initialPatientSystemId, initialConsultationId),
    // The form is mounted once per opening; its starting values do not follow later prop changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );
  const { control, handleSubmit, setError, setValue, formState: { isDirty } } = useForm<LabResultFormValues>({
    resolver:      zodResolver(labResultFormSchema),
    defaultValues: defaults,
  });
  const subTests = useFieldArray({ control, name: 'subTests' });
  const status   = useWatch({ control, name: 'status' });
  const patient  = useWatch({ control, name: 'patientSystemId' });

  const key = useIdempotencyKey();
  const [formError, setFormError] = useState<string | null>(null);
  const [documentId, setDocumentId] = useState<number | null>(editing?.document_id ?? null);
  const attachmentChanged = documentId !== (editing?.document_id ?? null);

  const save = useAction(
    (body: CreateLabResultBody | UpdateLabResultBody) => (editing
      ? updateLabResult(editing.result_id, body as UpdateLabResultBody)
      : createLabResult(body as CreateLabResultBody, key.current())),
    { errorMessage: 'The result could not be saved. Please try again.' },
  );
  const pending = save.pending;

  // Status and the critical flag move together: Critical switches the flag on, leaving Critical switches it off.
  const previousStatus = useRef(status);
  useEffect(() => {
    if (previousStatus.current === status) return;
    if (status === 'CRITICAL') setValue('isCritical', true, { shouldDirty: true });
    else if (previousStatus.current === 'CRITICAL') setValue('isCritical', false, { shouldDirty: true });
    previousStatus.current = status;
  }, [status, setValue]);

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

  const flagOptions = useMemo<SelectOption[]>(() => {
    const known: string[] = [...LAB_FLAGS];
    // A flag the stored result already uses stays selectable, so an edit does not have to change it.
    Object.values(editing?.result_data ?? {}).forEach(sub => {
      if (sub.flag && !known.includes(sub.flag)) known.push(sub.flag);
    });
    return known.map(flag => ({ value: flag, label: statusLabel(flag) }));
  }, [editing]);

  const submit = handleSubmit(async values => {
    setFormError(null);
    const rows: LabSubTestInput[] = values.subTests;
    const resultData = buildLabResultData(rows, editing?.result_data_raw);
    if (resultData.length > LAB_LIMITS.resultData) {
      setFormError(`The sub-tests are too long to save together (${resultData.length} of ${LAB_LIMITS.resultData} characters). Shorten or remove some rows.`);
      return;
    }
    let body: CreateLabResultBody | UpdateLabResultBody;
    if (editing) {
      body = updateBody(values, editing, resultData, documentId);
      if (Object.keys(body).length === 0) { onClose(); return; }
    } else {
      body = createBody(values, resultData, documentId);
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

  const statusHint = STATUS_HINT[status as WritableResultStatus];

  return (
    <SidePanel
      open
      title={editing ? 'Edit lab result' : 'Record lab result'}
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
            <InfoRow label="Patient" value={editing.patient_name} />
            <InfoRow
              label="Consultation"
              value={editing.consultation_id != null ? `Consultation #${editing.consultation_id}` : null}
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
          <View style={styles.cellWide}>
            <TextField
              label="Test name"
              control={control}
              name="testName"
              required
              maxLength={LAB_LIMITS.testName}
              placeholder="For example Full Blood Count (FBC)"
              disabled={pending}
            />
          </View>
          <View style={styles.cell}>
            <SelectField
              label="Test type"
              control={control}
              name="testType"
              options={TYPE_OPTIONS}
              placeholder="Select a test type"
              required
              disabled={pending}
            />
          </View>
        </View>

        <View style={styles.row}>
          <View style={styles.cell}>
            <DateField
              label="Test date"
              control={control}
              name="testDate"
              max={clinicToday()}
              required
              hint="The day the sample was taken. Not in the future."
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
              hint={statusHint}
              disabled={pending}
            />
          </View>
        </View>

        <View style={styles.group}>
          <Text style={styles.groupTitle} accessibilityRole="header" {...ariaLevel(3)}>Headline result</Text>
          <Text style={styles.groupNote}>For a test with one value. Use the sub-tests below for a panel.</Text>
          <View style={styles.row}>
            <View style={styles.cellWide}>
              <TextField label="Value" control={control} name="resultValue" maxLength={LAB_LIMITS.resultValue} disabled={pending} />
            </View>
            <View style={styles.cell}>
              <TextField label="Unit" control={control} name="unit" maxLength={LAB_LIMITS.unit} placeholder="For example mmol/L" disabled={pending} />
            </View>
            <View style={styles.cell}>
              <TextField
                label="Reference range"
                control={control}
                name="referenceRange"
                maxLength={LAB_LIMITS.referenceRange}
                placeholder="For example 3.9-5.6"
                disabled={pending}
              />
            </View>
          </View>
        </View>

        <View style={styles.group}>
          <Text style={styles.groupTitle} accessibilityRole="header" {...ariaLevel(3)}>Sub-tests</Text>
          <Text style={styles.groupNote}>
            One row per measured parameter. A row without a flag is shown without one, never as normal.
          </Text>
          {subTests.fields.map((field, index) => (
            <View key={field.id} style={styles.subTest} role="group" aria-label={`Sub-test ${index + 1}`}>
              <View style={styles.subTestHead}>
                <Text style={styles.subTestTitle}>{`Sub-test ${index + 1}`}</Text>
                <IconButton
                  label={`Remove sub-test ${index + 1}`}
                  icon="trash-outline"
                  size="sm"
                  onPress={() => subTests.remove(index)}
                  disabled={pending}
                />
              </View>
              <View style={styles.row}>
                <View style={styles.cellWide}>
                  <TextField
                    label="Name"
                    control={control}
                    name={`subTests.${index}.name`}
                    required
                    maxLength={SUB_TEST_LIMITS.name}
                    placeholder="For example Haemoglobin"
                    disabled={pending}
                  />
                </View>
                <View style={styles.cell}>
                  <TextField
                    label="Value"
                    control={control}
                    name={`subTests.${index}.value`}
                    required
                    maxLength={SUB_TEST_LIMITS.value}
                    disabled={pending}
                  />
                </View>
                <View style={styles.cell}>
                  <TextField label="Unit" control={control} name={`subTests.${index}.unit`} maxLength={SUB_TEST_LIMITS.unit} disabled={pending} />
                </View>
              </View>
              <View style={styles.row}>
                <View style={styles.cell}>
                  <TextField
                    label="Reference range"
                    control={control}
                    name={`subTests.${index}.reference`}
                    maxLength={SUB_TEST_LIMITS.reference}
                    disabled={pending}
                  />
                </View>
                <View style={styles.cell}>
                  <SelectField
                    label="Flag"
                    control={control}
                    name={`subTests.${index}.flag`}
                    options={flagOptions}
                    placeholder="No flag"
                    allowEmpty
                    disabled={pending}
                  />
                </View>
              </View>
              <TextField
                label="Interpretation"
                control={control}
                name={`subTests.${index}.interpretation`}
                maxLength={SUB_TEST_LIMITS.interpretation}
                multiline
                rows={2}
                disabled={pending}
              />
            </View>
          ))}
          <Button
            label="Add sub-test"
            icon="add"
            variant="secondary"
            size="sm"
            onPress={() => subTests.append({ ...EMPTY_SUB_TEST })}
            disabled={pending}
            style={styles.addRow}
          />
        </View>

        <SwitchField
          label="Critical result"
          control={control}
          name="isCritical"
          disabled={pending || status === 'CRITICAL'}
          description={status === 'CRITICAL'
            ? 'A result with the status Critical is always flagged critical.'
            : 'Flags the result for urgent attention and alerts the patient. A completed result that is flagged critical is saved with the status Critical.'}
        />

        {attachmentSlot ? (
          <View style={styles.group}>
            {attachmentSlot({
              kind:            'LAB',
              documentType:    RESULT_DOCUMENT_TYPE.LAB,
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
  cell:     { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0 },
  cellWide: { flexGrow: 2, flexShrink: 1, flexBasis: 240, minWidth: 0 },

  group:      { gap: space.sm, minWidth: 0 },
  groupTitle: { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  groupNote:  { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  subTest: {
    gap: space.md, padding: space.md, minWidth: 0,
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.bg,
  },
  subTestHead:  { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  subTestTitle: { fontSize: fontSize.sm, fontWeight: '700', color: colors.textMuted },
  addRow:       { alignSelf: 'flex-start' },
});
