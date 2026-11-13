import { useMemo } from 'react';
import { type Doctor, useDoctors } from '@/hooks/use-doctors';
import { type PickerOption, type PickerProps, PickerShell } from './picker-shell';

/**
 * Chooses a doctor from the active directory (GET /api/doctors/profile/list/active through `useDoctors`).
 * The value is the doctor's DOC-... system id; the option shows the name and specialization, never the id.
 */
export function DoctorPicker({ filter, label = 'Doctor', ...rest }: PickerProps<Doctor>) {
  const { doctors, loading, error, refetch } = useDoctors();

  const options = useMemo<PickerOption<Doctor>[]>(() => (
    doctors
      .filter(d => d.doctor_system_id && (!filter || filter(d)))
      .map(d => {
        const name = d.full_name || 'Doctor (name not set)';
        return { id: d.doctor_system_id, label: d.specialization ? `${name}, ${d.specialization}` : name, item: d };
      })
      .sort((a, b) => a.label.localeCompare(b.label))
  ), [doctors, filter]);

  return (
    <PickerShell
      {...rest}
      label={label}
      options={options}
      loading={loading}
      loadError={error}
      onRetry={refetch}
      emptyText="No doctors available"
      searchLabel="Search doctors"
    />
  );
}
