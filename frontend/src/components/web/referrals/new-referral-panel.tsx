import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { Controller, FormProvider, useForm } from 'react-hook-form';
import { PatientPicker } from '@/components/web/pickers';
import {
  Banner, type DialogCloseReason, FormActions, FormError, SelectField, SidePanel, TextField,
} from '@/components/web/ui';
import { useCreateReferral } from '@/hooks/use-referrals';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import { REFERRAL_PRIORITY_OPTIONS } from '@/lib/referrals/format';
import { REFERRAL_LIMITS, type Referral, type ReferralPriority } from '@/lib/referrals/types';
import { confirmAction } from '@/lib/ui/dialogs';
import {
  CREATE_REFERRAL_DEFAULTS, CREATE_REFERRAL_FIELDS, type CreateReferralValues, createReferralSchema,
  toCreateReferralBody,
} from '@/lib/validation/referral';
import { SpecialistFields } from './specialist-fields';

export interface NewReferralPanelProps {
  open: boolean;
  /** Patient to start with (the `patient` URL param of a "Refer" link), or ''. The doctor can change it. */
  initialPatientId?: string;
  /** Consultation the referral is issued from (the `consultationId` URL param), or null. */
  consultationId?: number | null;
  /** The signed-in doctor: left out of the specialist list, because a referral cannot go to its own author. */
  selfSystemId?: string | null;
  onClose:   () => void;
  onCreated: (referral: Referral) => void;
}

/** The New referral form in a side panel. Mounted fresh for every open, so each open starts clean with its own idempotency key. */
export function NewReferralPanel({ open, ...rest }: NewReferralPanelProps) {
  return open ? <NewReferralForm {...rest} /> : null;
}

function NewReferralForm({
  initialPatientId = '', consultationId = null, selfSystemId, onClose, onCreated,
}: Omit<NewReferralPanelProps, 'open'>) {
  const form = useForm<CreateReferralValues>({
    resolver: zodResolver(createReferralSchema),
    defaultValues: { ...CREATE_REFERRAL_DEFAULTS, patientSystemId: initialPatientId },
  });
  const { control, handleSubmit, setError, formState: { isDirty } } = form;

  const create = useCreateReferral();
  const key = useIdempotencyKey();
  const [formError, setFormError] = useState<string | null>(null);
  // The consultation the "Refer" link came from. It belongs to the patient of that link.
  const [linkedConsultation, setLinkedConsultation] = useState<number | null>(consultationId);

  const submit = handleSubmit(async values => {
    setFormError(null);
    const result = await create.run(toCreateReferralBody(values, linkedConsultation), key.current());
    if (result.ok) {
      key.renew();
      onCreated(result.data);
    } else if (result.error) {
      setFormError(applyFieldErrors(result.error, setError, CREATE_REFERRAL_FIELDS));
    }
  });

  const close = async (_reason: DialogCloseReason) => {
    if (isDirty && !(await confirmAction('Discard this referral?', 'What you entered will be lost.', 'Discard', true))) return;
    onClose();
  };

  return (
    <SidePanel
      open
      title="New referral"
      onClose={close}
      size="lg"
      dismissOnBackdrop={false}
      busy={create.pending}
      footer={(
        <FormActions
          submitLabel="Create referral"
          submitIcon="send-outline"
          onSubmit={submit}
          onCancel={() => { void close('button'); }}
          submitting={create.pending}
        />
      )}
    >
      <FormProvider {...form}>
        <FormError message={formError} />

        <Controller
          control={control}
          name="patientSystemId"
          render={({ field, fieldState }) => (
            <PatientPicker
              value={field.value || null}
              onChange={id => {
                field.onChange(id ?? '');
                // The linked consultation belongs to the patient the form was opened for.
                if (initialPatientId && id !== initialPatientId) setLinkedConsultation(null);
              }}
              error={fieldState.error?.message}
              disabled={create.pending}
              required
              placeholder="Select a patient"
              hint="You can refer patients you are treating."
            />
          )}
        />

        {linkedConsultation != null ? (
          <Banner
            tone="info"
            icon="link-outline"
            text="This referral will be linked to the consultation it was started from."
            action={{ label: 'Remove link', onPress: () => setLinkedConsultation(null) }}
          />
        ) : null}

        <TextField<CreateReferralValues>
          label="Reason for referral"
          control={control}
          name="reason"
          multiline
          rows={3}
          maxLength={REFERRAL_LIMITS.reason}
          showCount
          disabled={create.pending}
          required
          hint="What the specialist is asked to assess or treat. The patient can read this."
        />

        <TextField<CreateReferralValues>
          label="Clinical summary"
          control={control}
          name="clinicalSummary"
          multiline
          rows={5}
          maxLength={REFERRAL_LIMITS.clinicalSummary}
          showCount
          disabled={create.pending}
          hint="Relevant history, findings, results and treatment so far. Optional."
        />

        <SelectField<ReferralPriority, CreateReferralValues>
          label="Priority"
          control={control}
          name="priority"
          options={REFERRAL_PRIORITY_OPTIONS}
          disabled={create.pending}
          required
        />

        <SpecialistFields allowNone excludeDoctorId={selfSystemId} disabled={create.pending} />
      </FormProvider>
    </SidePanel>
  );
}
