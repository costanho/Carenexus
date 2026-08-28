import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export type PrescriptionStatus = 'ACTIVE' | 'COMPLETED' | 'EXPIRED';
export type PrescriptionRoute  = 'ORAL' | 'TOPICAL' | 'IV' | 'IM' | 'SUBLINGUAL' | 'INHALED' | 'NASAL' | 'OPHTHALMIC' | string;

export interface DoctorPrescription {
  prescription_id:      number;
  patient_id:           number;
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
  // enriched by server
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

    api.get(`/api/doctors/user/${user.user_id}`)
      .then(({ data: doctor }) => {
        const doctor_id = doctor.doctor_id;
        return Promise.all([
          api.get<any[]>(`/api/appointments?doctor_id=${doctor_id}`),
          api.get<any[]>('/api/prescriptions'),
          api.get<any[]>('/api/patients'),
        ]);
      })
      .then(([apptRes, rxRes, patientsRes]) => {
        // patient user_ids seen by this doctor
        const seenIds = new Set<number>(apptRes.data.map((a: any) => a.patient_id));

        // avatar lookup: user_id → avatar_url
        const avatarMap = new Map<number, string | null>();
        patientsRes.data.forEach((p: any) => avatarMap.set(p.user_id, p.avatar_url ?? null));

        const merged: DoctorPrescription[] = rxRes.data
          .filter((r: any) => seenIds.has(r.patient_id))
          .map((r: any) => ({
            prescription_id:      r.prescription_id,
            patient_id:           r.patient_id,
            doctor_id:            r.doctor_id,
            consultation_id:      r.consultation_id ?? null,
            medication_name:      r.medication_name ?? '',
            generic_name:         r.generic_name    ?? null,
            dosage:               r.dosage          ?? '',
            frequency:            r.frequency       ?? '',
            route:                r.route           ?? 'ORAL',
            quantity:             r.quantity        ?? null,
            refills_allowed:      r.refills_allowed ?? 0,
            special_instructions: r.special_instructions ?? null,
            status:               r.status          as PrescriptionStatus,
            prescribed_date:      r.prescribed_date ?? '',
            expiry_date:          r.expiry_date     ?? null,
            created_at:           r.created_at      ?? '',
            doctor_name:           r.doctor_name           ?? 'Unknown Doctor',
            doctor_specialization: r.doctor_specialization ?? null,
            doctor_avatar_url:     r.doctor_avatar_url     ?? null,
            patient_name:          r.patient_name          ?? 'Unknown Patient',
            patient_avatar_url:    avatarMap.get(r.patient_id) ?? null,
          }));

        merged.sort((a, b) => new Date(b.prescribed_date).getTime() - new Date(a.prescribed_date).getTime());

        setPrescriptions(merged);
        setError(null);
      })
      .catch(() => setError('Failed to load prescriptions.'))
      .finally(() => setLoading(false));
  }, [user?.user_id, tick]);

  return { prescriptions, loading, error, refetch: () => setTick(t => t + 1) };
}
