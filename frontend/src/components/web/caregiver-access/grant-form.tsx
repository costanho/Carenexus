import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, View } from 'react-native';
import {
  DateField, FormActions, FormError, SectionCard, space, TextField,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { grantCaregiverAccess } from '@/hooks/use-caregiver-access';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import {
  dataFlagsToSend, NO_COMMUNICATION_FLAGS, NO_DATA_FLAGS,
} from '@/lib/caregiver-access/permissions';
import type {
  AccessLevel, CaregiverAccess, CommunicationFlags, DataFlags, GrantCaregiverAccessBody,
} from '@/lib/caregiver-access/types';
import {
  CAREGIVER_EMAIL_MAX, grantAccessSchema, RELATIONSHIP_MAX, type GrantAccessFormValues,
} from '@/lib/caregiver-access/validation';
import { clinicToday } from '@/lib/time/clinic';
import { CommunicationEditor, PermissionEditor } from './permission-editor';

const FIELDS = ['caregiverEmail', 'relationship', 'expiresAt'] as const;

/** A new grant starts as View only with the two permissions most caregivers need. */
const INITIAL_FLAGS: DataFlags = { ...NO_DATA_FLAGS, canViewRecords: true, canViewAppointments: true };

/**
 * Gives a caregiver account access by email: relationship, access level and permissions (billing included),
 * messaging and video calls, and an optional end date. The same idempotency key is sent when a failed submit is
 * retried, so a retry cannot create a second grant.
 */
export function GrantForm({ online, onCancel, onGranted }: {
  online:    boolean;
  onCancel:  () => void;
  onGranted: (grant: CaregiverAccess) => void;
}) {
  const { control, handleSubmit, setError } = useForm<GrantAccessFormValues>({
    resolver:      zodResolver(grantAccessSchema),
    defaultValues: { caregiverEmail: '', relationship: '', expiresAt: '' },
  });
  const [level, setLevel] = useState<AccessLevel>('VIEW_ONLY');
  const [flags, setFlags] = useState<DataFlags>(INITIAL_FLAGS);
  const [communication, setCommunication] = useState<CommunicationFlags>(NO_COMMUNICATION_FLAGS);
  const [formError, setFormError] = useState<string | null>(null);

  const key = useIdempotencyKey();
  const grant = useAction(
    (body: GrantCaregiverAccessBody) => grantCaregiverAccess(body, key.current()),
    { errorMessage: 'Could not grant access. Please try again.' },
  );

  const submit = handleSubmit(async values => {
    setFormError(null);
    const result = await grant.run({
      caregiverEmail: values.caregiverEmail.toLowerCase(),
      relationship:   values.relationship,
      accessLevel:    level,
      expiresAt:      values.expiresAt || null,
      ...dataFlagsToSend(level, flags),
      ...communication,
    });
    if (result.ok) {
      key.renew();
      onGranted(result.data);
      return;
    }
    if (!result.error) return;
    // A request the server refused was not stored, so the corrected form is a new request with a new key. The
    // key is kept only while the outcome is unknown (no response, a server fault, the first call still running).
    const refused = result.error.status >= 400 && result.error.status < 500;
    if (refused && result.error.code !== 'IDEMPOTENCY_IN_PROGRESS') key.renew();
    if (result.error.status === 404) {
      // "No caregiver account found for this email": the problem is the address that was typed.
      setError('caregiverEmail', { type: 'server', message: result.error.message }, { shouldFocus: true });
      return;
    }
    setFormError(
      result.error.status >= 500
        ? 'Could not grant access. Please try again.'
        : applyFieldErrors(result.error, setError, FIELDS),
    );
  });

  const busy = grant.pending;
  return (
    <SectionCard
      title="Grant access"
      subtitle="The person needs a CareNexus caregiver account. Enter the email they signed up with."
    >
      <View style={styles.fields}>
        <TextField
          control={control} name="caregiverEmail" label="Caregiver email" required
          placeholder="name@example.com" maxLength={CAREGIVER_EMAIL_MAX}
          inputMode="email" autoComplete="email" autoCapitalize="none" disabled={busy}
        />
        <TextField
          control={control} name="relationship" label="Relationship" required
          placeholder="For example daughter, husband, neighbour" maxLength={RELATIONSHIP_MAX} disabled={busy}
        />
      </View>

      <PermissionEditor
        level={level}
        flags={flags}
        onChange={(nextLevel, nextFlags) => { setLevel(nextLevel); setFlags(nextFlags); }}
        disabled={busy}
      />
      <CommunicationEditor value={communication} onChange={setCommunication} disabled={busy} />

      <DateField
        control={control} name="expiresAt" label="Access ends on (optional)"
        hint="Access ends at the end of that day. Leave empty for no end date."
        min={clinicToday()} disabled={busy}
      />

      <FormError message={formError} />
      <FormActions
        submitLabel="Grant access"
        submitIcon="person-add-outline"
        onSubmit={submit}
        onCancel={onCancel}
        submitting={busy}
        disabled={!online}
      />
    </SectionCard>
  );
}

const styles = StyleSheet.create({
  fields: { gap: space.md },
});
