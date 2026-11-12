import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { FormProvider, useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import { colors, fontSize, FormActions, FormError, InfoRow, Modal, space } from '@/components/web/ui';
import { useAssignReferralSpecialist } from '@/hooks/use-referrals';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { specialistSummary } from '@/lib/referrals/format';
import type { Referral } from '@/lib/referrals/types';
import {
  ASSIGN_SPECIALIST_FIELDS, assignSpecialistSchema, type AssignSpecialistValues, toAssignSpecialistBody,
} from '@/lib/validation/referral';
import { SpecialistFields } from './specialist-fields';

export interface AssignSpecialistDialogProps {
  referral: Referral;
  onClose:  () => void;
  onDone:   (updated: Referral, message: string) => void;
  /** The server refused because the referral changed meanwhile (409, 403, 404): reload what is on screen. */
  onStale:  () => void;
}

/**
 * Assign or replace the specialist of a pending referral: a doctor on the platform, or a specialist outside it
 * by name, organisation and contact. The server allows it only while the referral is pending; its refusal is
 * shown inside the dialog.
 */
export function AssignSpecialistDialog({ referral, onClose, onDone, onStale }: AssignSpecialistDialogProps) {
  const unassigned = referral.specialistType === 'UNASSIGNED';
  const form = useForm<AssignSpecialistValues>({
    resolver: zodResolver(assignSpecialistSchema),
    defaultValues: {
      specialistMode:                 referral.specialistType === 'EXTERNAL' ? 'EXTERNAL' : 'PLATFORM',
      specialistSystemId:             referral.specialistSystemId ?? '',
      externalSpecialistName:         referral.externalSpecialistName ?? '',
      externalSpecialistOrganisation: referral.externalSpecialistOrganisation ?? '',
      externalSpecialistContact:      referral.externalSpecialistContact ?? '',
    },
  });
  const { handleSubmit, setError } = form;
  const assign = useAssignReferralSpecialist();
  const [formError, setFormError] = useState<string | null>(null);

  const submit = handleSubmit(async values => {
    setFormError(null);
    const result = await assign.run(referral.referralId, toAssignSpecialistBody(values));
    if (result.ok) {
      onDone(result.data, `Specialist for ${result.data.patientName}'s referral: ${specialistSummary(result.data)}.`);
    } else if (result.error) {
      setFormError(applyFieldErrors(result.error, setError, ASSIGN_SPECIALIST_FIELDS));
      if (result.error.status === 409 || result.error.status === 403 || result.error.status === 404) onStale();
    }
  });

  return (
    <Modal
      open
      title={unassigned ? 'Assign specialist' : 'Change specialist'}
      onClose={onClose}
      dismissOnBackdrop={false}
      busy={assign.pending}
      footer={(
        <FormActions
          submitLabel={unassigned ? 'Assign specialist' : 'Save specialist'}
          onSubmit={submit}
          onCancel={onClose}
          submitting={assign.pending}
        />
      )}
    >
      <View style={styles.summary}>
        <InfoRow label="Patient" value={referral.patientName} />
        <InfoRow label="Current specialist" value={specialistSummary(referral)} />
      </View>
      <Text style={styles.note}>
        The specialist can be changed while the referral is pending. Once someone is assigned the referral cannot
        go back to unassigned.
      </Text>

      <FormError message={formError} />

      <FormProvider {...form}>
        <SpecialistFields
          allowNone={false}
          excludeDoctorId={referral.referringDoctorSystemId}
          disabled={assign.pending}
        />
      </FormProvider>
    </Modal>
  );
}

const styles = StyleSheet.create({
  summary: { minWidth: 0, paddingBottom: space.xs, borderBottomWidth: 1, borderBottomColor: colors.border },
  note:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
