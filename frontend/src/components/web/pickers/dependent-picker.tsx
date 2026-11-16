import { useMemo } from 'react';
import { type CaregiverDependent, useCaregiverDependents } from '@/hooks/use-caregiver-dependents';
import { isGrantActive } from '@/lib/caregiver/permissions';
import { type PickerOption, type PickerProps, PickerShell } from './picker-shell';

export interface DependentPickerProps extends PickerProps<CaregiverDependent> {
  /** Also list loved ones whose access has expired or was revoked (for screens that explain the lost access). */
  includeInactive?: boolean;
}

/**
 * Chooses one of the signed-in caregiver's loved ones (GET /api/caregiver/dependents through
 * `useCaregiverDependents`). The value is the loved one's PAT-... system id. Only grants that are still active
 * are listed unless `includeInactive` is set; use `filter` with `hasCaregiverPermission` to narrow the list to
 * people the caregiver may act for.
 */
export function DependentPicker({ filter, label = 'Loved one', includeInactive = false, ...rest }: DependentPickerProps) {
  const { dependents, loading, error, refetch } = useCaregiverDependents();

  const options = useMemo<PickerOption<CaregiverDependent>[]>(() => (
    dependents
      .filter(d => d.patientSystemId && (includeInactive || isGrantActive(d)) && (!filter || filter(d)))
      .map(d => {
        const name = d.patientName?.trim() || 'Name not set';
        const relationship = d.relationship?.trim();
        const ended = isGrantActive(d) ? '' : ' (access ended)';
        return { id: d.patientSystemId, label: `${relationship ? `${name}, ${relationship}` : name}${ended}`, item: d };
      })
      .sort((a, b) => a.label.localeCompare(b.label))
  ), [dependents, filter, includeInactive]);

  return (
    <PickerShell
      {...rest}
      label={label}
      options={options}
      loading={loading}
      loadError={error}
      onRetry={refetch}
      emptyText="No loved ones linked yet"
      searchLabel="Search loved ones"
    />
  );
}
