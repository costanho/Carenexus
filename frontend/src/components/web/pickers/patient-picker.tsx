import { useMemo } from 'react';
import { type DoctorPatient, useDoctorPatients } from '@/hooks/use-doctor-patients';
import { type PickerOption, type PickerProps, PickerShell } from './picker-shell';

/**
 * Chooses one of the signed-in doctor's patients (GET /api/doctors/patients through `useDoctorPatients`).
 * The value is the patient's PAT-... system id.
 */
export function PatientPicker({ filter, label = 'Patient', ...rest }: PickerProps<DoctorPatient>) {
  const { patients, loading, error, refetch } = useDoctorPatients();

  const options = useMemo<PickerOption<DoctorPatient>[]>(() => (
    patients
      .filter(p => p.patient_system_id && (!filter || filter(p)))
      .map(p => ({ id: p.patient_system_id, label: p.full_name, item: p }))
      .sort((a, b) => a.label.localeCompare(b.label))
  ), [patients, filter]);

  return (
    <PickerShell
      {...rest}
      label={label}
      options={options}
      loading={loading}
      loadError={error}
      onRetry={refetch}
      emptyText="No patients yet"
      searchLabel="Search patients"
    />
  );
}
