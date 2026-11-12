import { Controller, useFormContext, useWatch } from 'react-hook-form';
import { StyleSheet, View } from 'react-native';
import { DoctorPicker } from '@/components/web/pickers';
import { SelectField, space, TextField } from '@/components/web/ui';
import { REFERRAL_LIMITS } from '@/lib/referrals/types';
import type { SpecialistMode } from '@/lib/validation/referral';

/** The form fields this block reads and writes. The new-referral and assign-specialist forms both have them. */
export interface SpecialistFormValues {
  specialistMode:                 SpecialistMode;
  specialistSystemId:             string;
  externalSpecialistName:         string;
  externalSpecialistOrganisation: string;
  externalSpecialistContact:      string;
}

const MODE_LABEL: Record<SpecialistMode, string> = {
  NONE:     'Decide later (leave unassigned)',
  PLATFORM: 'A doctor on CareNexus',
  EXTERNAL: 'A specialist outside CareNexus',
};

/**
 * Who a referral goes to: a doctor on the platform (chosen from the active directory) or a specialist outside
 * it (name, organisation, contact), and on a new referral also "decide later". Must be rendered inside a
 * react-hook-form `FormProvider` whose values include `SpecialistFormValues`.
 */
export function SpecialistFields({ allowNone, excludeDoctorId, disabled = false }: {
  /** Offer "Decide later". True on a new referral; false when assigning, because a specialist cannot be cleared. */
  allowNone: boolean;
  /** The referring doctor: a referral cannot be addressed to its own author. */
  excludeDoctorId?: string | null;
  disabled?: boolean;
}) {
  const { control } = useFormContext<SpecialistFormValues>();
  const mode = useWatch({ control, name: 'specialistMode' });
  const modes: SpecialistMode[] = allowNone ? ['NONE', 'PLATFORM', 'EXTERNAL'] : ['PLATFORM', 'EXTERNAL'];

  return (
    <View style={styles.wrap}>
      <SelectField<SpecialistMode, SpecialistFormValues>
        label="Refer to"
        control={control}
        name="specialistMode"
        options={modes.map(value => ({ value, label: MODE_LABEL[value] }))}
        disabled={disabled}
        required
        hint={mode === 'NONE' ? 'You can assign a specialist later, while the referral is pending.' : undefined}
      />

      {mode === 'PLATFORM' ? (
        <Controller
          control={control}
          name="specialistSystemId"
          render={({ field, fieldState }) => (
            <DoctorPicker
              label="Specialist"
              value={field.value || null}
              onChange={id => field.onChange(id ?? '')}
              filter={excludeDoctorId ? d => d.doctor_system_id !== excludeDoctorId : undefined}
              error={fieldState.error?.message}
              disabled={disabled}
              required
              placeholder="Select a doctor"
              hint="The doctor is notified and can accept or decline the referral."
            />
          )}
        />
      ) : null}

      {mode === 'EXTERNAL' ? (
        <>
          <TextField<SpecialistFormValues>
            label="Specialist's name"
            control={control}
            name="externalSpecialistName"
            maxLength={REFERRAL_LIMITS.externalSpecialist}
            disabled={disabled}
            required
            autoCapitalize="words"
          />
          <TextField<SpecialistFormValues>
            label="Hospital or practice"
            control={control}
            name="externalSpecialistOrganisation"
            maxLength={REFERRAL_LIMITS.externalSpecialist}
            disabled={disabled}
          />
          <TextField<SpecialistFormValues>
            label="Phone or email"
            control={control}
            name="externalSpecialistContact"
            maxLength={REFERRAL_LIMITS.externalSpecialist}
            disabled={disabled}
            hint="A specialist outside CareNexus is not notified here. You record their response for them."
          />
        </>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: space.md, minWidth: 0 },
});
