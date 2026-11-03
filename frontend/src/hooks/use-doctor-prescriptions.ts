import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export type PrescriptionStatus = 'ACTIVE' | 'COMPLETED' | 'EXPIRED';
export type PrescriptionRoute  = 'ORAL' | 'TOPICAL' | 'IV' | 'IM' | 'SUBLINGUAL' | 'INHALED' | 'NASAL' | 'OPHTHALMIC' | string;

export interface DoctorPrescription {
  prescription_id:      number;
  patient_system_id:    string;
  /** @deprecated not exposed by the API; always 0. Use patient_system_id. */
  patient_id:           number;
  /** @deprecated not exposed by the API; always 0. */
  doctor_id:            number;
  consultation_id:      number | null;
  medication_name:      string;
  generic_name:         string | null;
  dosage:               string;
  frequency:            string;
  route:                PrescriptionRoute;
  quantity:             string | null;
  refills_allowed:      number;
  special_instructions: string | null;
  status:               PrescriptionStatus;
  prescribed_date:      string;
  expiry_date:          string | null;
  created_at:           string;
  // enriched client-side from the doctor's own profile / patient list
  doctor_name:           string;
  doctor_specialization: string | null;
  doctor_avatar_url:     string | null;
  patient_name:          string;
  // enriched client-side
  patient_avatar_url:    string | null;
}

interface State {
  prescriptions: DoctorPrescription[];
  loading:       boolean;
  error:         string | null;
  refetch:       () => void;
}

export function useDoctorPrescriptions(): State {
  const { user } = useCurrentUser();
  const [prescriptions, setPrescriptions] = useState<DoctorPrescription[]>([]);
  const [loading,       setLoading]       = useState(true);
  const [error,         setError]         = useState<string | null>(null);
  const [tick,          setTick]          = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);

    Promise.all([
        api.get<any[]>(`/api/prescriptions/doctor/${user.system_id}`),
        api.get<any[]>('/api/doctors/patients'),
      ])
      .then(([rxRes, patientsRes]) => {
        // patientSystemId → full name, from the doctor's own patient list
        const nameMap = new Map<string, string>();
        (patientsRes.data ?? []).forEach((p: any) => {
          if (!p.patientSystemId) return;
          const name = `${p.firstName ?? ''} ${p.lastName ?? ''}`.trim();
          if (name) nameMap.set(p.patientSystemId, name);
        });
        const doctorName = user.first_name ? `Dr. ${user.first_name} ${user.last_name}`.trim() : 'Unknown Doctor';

        const merged: DoctorPrescription[] = (rxRes.data ?? [])
          .map((r: any) => ({
            prescription_id:      r.prescriptionId ?? 0,
            patient_system_id:    r.patientSystemId ?? '',
            patient_id:           0,
            doctor_id:            0,
            consultation_id:      r.consultationId ?? null,
            medication_name:      r.medicationName ?? '',
            generic_name:         r.genericName    ?? null,
            dosage:               r.dosage         ?? '',
            frequency:            r.frequency      ?? '',
            route:                r.route          ?? 'ORAL',
            quantity:             r.quantity       ?? null,
            refills_allowed:      r.refillsAllowed ?? 0,
            special_instructions: r.specialInstructions ?? null,
            status:               r.status as PrescriptionStatus,
            prescribed_date:      r.prescribedDate ? String(r.prescribedDate) : '',
            expiry_date:          r.expiryDate     ? String(r.expiryDate)     : null,
            created_at:           r.createdAt      ? String(r.createdAt)      : '',
            doctor_name:           doctorName,
            doctor_specialization: null,
            doctor_avatar_url:     null,
            patient_name:          nameMap.get(r.patientSystemId) ?? 'Unknown Patient',
            patient_avatar_url:    null,
          }));

        merged.sort((a, b) => new Date(b.prescribed_date).getTime() - new Date(a.prescribed_date).getTime());

        setPrescriptions(merged);
        setError(null);
      })
      .catch(() => setError('Failed to load prescriptions.'))
      .finally(() => setLoading(false));
  }, [user?.system_id, tick]);

  return { prescriptions, loading, error, refetch: () => setTick(t => t + 1) };
}
