import { zodResolver } from '@hookform/resolvers/zod';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import { DoctorPicker } from '@/components/web/pickers';
import {
  Banner, colors, fontSize, FormActions, FormError, InfoRow, SelectField, space, SwitchField, TextField,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import type { Doctor } from '@/hooks/use-doctors';
import { useResource } from '@/hooks/use-resource';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import { assignCareTeamMember, getCareTeamFacilityOptions } from '@/lib/careteam/api';
import { careTeamMemberName, careTeamRoleOptions, primaryMembers } from '@/lib/careteam/format';
import {
  ADD_CARE_TEAM_MEMBER_FIELDS, addCareTeamMemberSchema, emptyAddCareTeamMemberValues, toAssignCareTeamMemberBody,
  type AddCareTeamMemberValues, type CareTeamProviderKind,
} from '@/lib/careteam/schema';
import { CARE_TEAM_LIMITS, type AssignCareTeamMemberBody, type CareTeamMember } from '@/lib/careteam/types';

const KIND_OPTIONS: { value: CareTeamProviderKind; label: string }[] = [
  { value: 'DOCTOR_ACCOUNT', label: 'A doctor on CareNexus' },
  { value: 'EXTERNAL',       label: 'Another provider (nurse, lab, pharmacy, therapist or a doctor elsewhere)' },
];

export interface AddCareTeamMemberFormProps {
  /** The patient's PAT-... id (never OWN_CARE_TEAM: patients cannot add members). */
  patientSystemId: string;
  /** Shown in the introduction ("Adding to Thomas Dube's care team"). */
  patientName?: string | null;
  /**
   * The current care team. Doctors who already have a stored membership are left out of the picker (the server
   * answers 409 for them) and the primary switch names the member it would replace.
   */
  members?: readonly CareTeamMember[];
  /**
   * A doctor listed only through appointments who is being turned into a stored member: the form is fixed to
   * this doctor and asks only for the role, the facility and the primary flag.
   */
  fixedProvider?: CareTeamMember | null;
  /** Sends the request. Default: POST /api/patients/{patientSystemId}/care-team. Pass `useCareTeam().assign` to reload that list too. */
  assign?: (body: AssignCareTeamMemberBody, idempotencyKey: string) => Promise<CareTeamMember>;
  /** Called with the created member. */
  onAdded: (member: CareTeamMember) => void;
  onCancel?: () => void;
  /** The server answered 409: the team changed since it was loaded, so the caller should reload it. */
  onConflict?: () => void;
  /** Reports whether a submit is in flight (a dialog passes it to `busy`). */
  onSubmittingChange?: (submitting: boolean) => void;
  /** Reports whether anything was typed or chosen (a dialog asks before discarding it). */
  onDirtyChange?: (dirty: boolean) => void;
}

/**
 * Adds a care-team member: a doctor from the directory, or an external provider by name with an optional phone
 * number and organisation; the role (a CareNexus doctor can only be Doctor or Specialist, an external provider
 * any of the six), an optional facility and the primary flag. Server field errors land on their fields; any
 * other refusal, including "already on the care team" (409), is shown above the buttons.
 */
export function AddCareTeamMemberForm({
  patientSystemId, patientName, members = [], fixedProvider = null, assign,
  onAdded, onCancel, onConflict, onSubmittingChange, onDirtyChange,
}: AddCareTeamMemberFormProps) {
  const fixedId = fixedProvider?.providerSystemId ?? null;

  const { control, handleSubmit, setError, setValue, watch, formState: { isDirty, isSubmitted } } = useForm<AddCareTeamMemberValues>({
    resolver:      zodResolver(addCareTeamMemberSchema),
    defaultValues: emptyAddCareTeamMemberValues({ providerSystemId: fixedId }),
  });
  const kind: CareTeamProviderKind = fixedId ? 'DOCTOR_ACCOUNT' : watch('kind');
  const accountProvider = kind === 'DOCTOR_ACCOUNT';

  const [formError, setFormError] = useState<string | null>(null);

  // The roles on offer depend on the kind (a CareNexus doctor can only be Doctor or Specialist). When the kind
  // changes, the role starts again: Doctor for a CareNexus doctor, an explicit choice for an external provider,
  // so a pharmacy or a lab is never added as "Doctor" by leaving the default in place.
  const lastAccountProvider = useRef(accountProvider);
  useEffect(() => {
    if (lastAccountProvider.current === accountProvider) return;
    lastAccountProvider.current = accountProvider;
    setValue('role', accountProvider ? 'DOCTOR' : '', { shouldDirty: true, shouldValidate: isSubmitted });
  }, [accountProvider, isSubmitted, setValue]);

  const facilities = useResource(getCareTeamFacilityOptions, []);
  const facilityOptions = useMemo(
    () => (facilities.data ?? []).map(f => ({ value: String(f.facilityId), label: f.name })),
    [facilities.data],
  );

  const assignedDoctorIds = useMemo(() => {
    const ids = new Set<string>();
    for (const m of members) if (m.source === 'ASSIGNED' && m.providerSystemId) ids.add(m.providerSystemId);
    return ids;
  }, [members]);
  const notOnTeam = useCallback((d: Doctor) => !assignedDoctorIds.has(d.doctor_system_id), [assignedDoctorIds]);

  const currentPrimary = primaryMembers(members)[0] ?? null;

  // One idempotency key per attempt at the same member: a retry of the same values repeats the key, so the
  // server returns the member it already created; changed values are a new request and get a new key.
  const key = useIdempotencyKey();
  const lastSent = useRef<string | null>(null);

  const send = useCallback(
    (body: AssignCareTeamMemberBody, idempotencyKey: string) =>
      (assign ? assign(body, idempotencyKey) : assignCareTeamMember(patientSystemId, body, idempotencyKey)),
    [assign, patientSystemId],
  );
  const save = useAction(send, { errorMessage: 'We could not add this member. Please try again.' });

  const submittingRef = useRef(onSubmittingChange);
  submittingRef.current = onSubmittingChange;
  useEffect(() => { submittingRef.current?.(save.pending); }, [save.pending]);

  const dirtyRef = useRef(onDirtyChange);
  dirtyRef.current = onDirtyChange;
  useEffect(() => { dirtyRef.current?.(isDirty); }, [isDirty]);

  const submit = handleSubmit(async values => {
    setFormError(null);
    const body = toAssignCareTeamMemberBody(fixedId ? { ...values, kind: 'DOCTOR_ACCOUNT', providerSystemId: fixedId } : values);
    const signature = JSON.stringify(body);
    if (lastSent.current !== null && lastSent.current !== signature) key.renew();
    lastSent.current = signature;

    const result = await save.run(body, key.current());
    if (result.ok) {
      key.renew();
      lastSent.current = null;
      onAdded(result.data);
      return;
    }
    if (!result.error) return; // a second press while the first submit is still running
    setFormError(applyFieldErrors(result.error, setError, ADD_CARE_TEAM_MEMBER_FIELDS));
    if (result.error.status === 409 && result.error.code !== 'IDEMPOTENCY_IN_PROGRESS') onConflict?.();
  });

  const submitting = save.pending;
  const roleOptions = careTeamRoleOptions(accountProvider);
  const who = (patientName ?? '').trim();

  return (
    <View style={styles.form}>
      <Text style={styles.intro}>
        {who ? `Adding to ${who}'s care team.` : "Adding to this patient's care team."}
        {' '}The patient is told when a member is added.
      </Text>

      {fixedProvider ? (
        <InfoRow label="Doctor" value={careTeamMemberName(fixedProvider)} />
      ) : (
        <SelectField
          label="Who are you adding?"
          control={control}
          name="kind"
          options={KIND_OPTIONS}
          required
          disabled={submitting}
        />
      )}

      {accountProvider && !fixedProvider ? (
        <Controller
          control={control}
          name="providerSystemId"
          render={({ field, fieldState }) => (
            <DoctorPicker
              label="Doctor"
              value={field.value || null}
              onChange={id => field.onChange(id ?? '')}
              filter={notOnTeam}
              required
              disabled={submitting}
              error={fieldState.error?.message}
              hint="Doctors who are already on this care team are not listed."
            />
          )}
        />
      ) : null}

      {!accountProvider ? (
        <>
          <TextField
            label="Provider name"
            control={control}
            name="providerName"
            required
            disabled={submitting}
            maxLength={CARE_TEAM_LIMITS.providerName}
            placeholder="For example Greenwood Pharmacy"
            autoCapitalize="words"
            onSubmit={submit}
          />
          <TextField
            label="Phone number"
            control={control}
            name="providerPhone"
            disabled={submitting}
            maxLength={CARE_TEAM_LIMITS.providerPhone}
            inputMode="tel"
            autoComplete="off"
            placeholder="+263 242 700 123"
            hint="Optional. 7 to 15 digits; spaces, dots, dashes and brackets are allowed."
            onSubmit={submit}
          />
          <TextField
            label="Organisation"
            control={control}
            name="providerOrganisation"
            disabled={submitting}
            maxLength={CARE_TEAM_LIMITS.providerOrganisation}
            hint="Optional. The group or practice the provider belongs to."
            onSubmit={submit}
          />
        </>
      ) : null}

      <SelectField
        label="Role"
        control={control}
        name="role"
        options={roleOptions}
        placeholder="Select a role"
        required
        disabled={submitting}
        hint={accountProvider
          ? "A CareNexus doctor can be added as Doctor or Specialist, and can see this patient's records from then on."
          : "Listed for reference only: a provider without a CareNexus account cannot see the patient's records."}
      />

      {facilities.loading ? (
        <SelectField label="Facility" value="" onChange={() => {}} options={[]} placeholder="Loading facilities…" disabled />
      ) : facilityOptions.length > 0 ? (
        <SelectField
          label="Facility"
          control={control}
          name="facilityId"
          options={facilityOptions}
          placeholder="No facility"
          allowEmpty
          disabled={submitting}
          hint="Optional. Where this member sees the patient."
        />
      ) : facilities.error ? (
        <Banner
          tone="info"
          text="The list of facilities could not be loaded. You can still add the member without a facility."
          action={{ label: 'Retry', onPress: facilities.refetch }}
        />
      ) : null}

      <SwitchField
        label="Primary member"
        control={control}
        name="isPrimary"
        disabled={submitting}
        description={currentPrimary
          ? `The main contact for this patient's care. Replaces ${careTeamMemberName(currentPrimary)} as primary.`
          : "The main contact for this patient's care. A care team has one primary member."}
      />

      <FormError message={formError} />
      <FormActions
        submitLabel="Add member"
        submitIcon="person-add-outline"
        onSubmit={submit}
        onCancel={onCancel}
        submitting={submitting}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  form:  { gap: space.md, minWidth: 0 },
  intro: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
