import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export interface IcdCode {
  code: string;
  description: string;
}

export type RecordType = 'CONSULT' | 'LAB' | 'PRESCRIPTION' | 'REFERRAL' | 'NOTE' | 'IMAGING';

export interface DoctorMedicalRecord {
  record_id:             number;
  patient_id:            number;
  doctor_id:             number;
  consultation_id:       number | null;
  record_type:           RecordType;
  title:                 string;
  description:           string | null;
  treatment_plan:        string | null;
  observations:          string | null;
  icd_codes:             IcdCode[];
  created_at:            string;
  updated_at:            string;
  // enriched by server
  doctor_name:           string;
  doctor_specialization: string | null;
  doctor_avatar_url:     string | null;
  patient_name:          string;
  // enriched client-side
  patient_avatar_url:    string | null;
}

interface State {
  records:  DoctorMedicalRecord[];
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
}

export function useDoctorMedicalRecords(): State {
  const { user } = useCurrentUser();
  const [records,  setRecords]  = useState<DoctorMedicalRecord[]>([]);
  const [loading,  setLoading]  = useState(true);
  const [error,    setError]    = useState<string | null>(null);
  const [tick,     setTick]     = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);

    api.get(`/api/doctors/user/${user.user_id}`)
      .then(({ data: doctor }) => {
        const doctor_id = doctor.doctor_id;
        return Promise.all([
          api.get<any[]>(`/api/appointments?doctor_id=${doctor_id}`),
          api.get<any[]>('/api/medical-records'),
          api.get<any[]>('/api/patients'),
        ]);
      })
      .then(([apptRes, recordsRes, patientsRes]) => {
        // build set of patient user_ids seen by this doctor
        const seenIds = new Set<number>(apptRes.data.map((a: any) => a.patient_id));

        // avatar lookup: user_id → avatar_url
        const avatarMap = new Map<number, string | null>();
        patientsRes.data.forEach((p: any) => avatarMap.set(p.user_id, p.avatar_url ?? null));

        const merged: DoctorMedicalRecord[] = recordsRes.data
          .filter((r: any) => seenIds.has(r.patient_id))
          .map((r: any) => ({
            record_id:             r.record_id,
            patient_id:            r.patient_id,
            doctor_id:             r.doctor_id,
            consultation_id:       r.consultation_id ?? null,
            record_type:           r.record_type as RecordType,
            title:                 r.title ?? '',
            description:           r.description ?? null,
            treatment_plan:        r.treatment_plan ?? null,
            observations:          r.observations ?? null,
            icd_codes:             Array.isArray(r.icd_codes) ? r.icd_codes : [],
            created_at:            r.created_at ?? '',
            updated_at:            r.updated_at ?? '',
            doctor_name:           r.doctor_name ?? 'Unknown Doctor',
            doctor_specialization: r.doctor_specialization ?? null,
            doctor_avatar_url:     r.doctor_avatar_url ?? null,
            patient_name:          r.patient_name ?? 'Unknown Patient',
            patient_avatar_url:    avatarMap.get(r.patient_id) ?? null,
          }));

        // already sorted desc by server; re-sort just in case
        merged.sort((a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime());

        setRecords(merged);
        setError(null);
      })
      .catch(() => setError('Failed to load medical records.'))
      .finally(() => setLoading(false));
  }, [user?.user_id, tick]);

  return { records, loading, error, refetch: () => setTick(t => t + 1) };
}
