import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export type LabTestType   = 'BLOOD' | 'SWAB' | 'URINE' | 'CULTURE' | string;
export type LabStatus     = 'COMPLETED' | 'REQUIRES_REVIEW';
export type LabFlag       = 'NORMAL' | 'LOW' | 'HIGH';

export interface LabSubTest {
  value:          number | string;
  unit:           string;
  reference:      string;
  flag:           LabFlag;
  interpretation?: string;
}

export interface DoctorLabResult {
  result_id:       number;
  patient_id:      number;
  doctor_id:       number;
  consultation_id: number | null;
  test_name:       string;
  test_type:       LabTestType;
  result_value:    string | null;
  unit:            string | null;
  reference_range: string | null;
  result_data:     Record<string, LabSubTest> | null;
  status:          LabStatus;
  is_critical:     number;
  test_date:       string;
  reviewed_by:     number | null;
  reviewed_at:     string | null;
  file_url:        string | null;
  created_at:      string;
  // server-enriched
  doctor_name:           string;
  doctor_specialization: string | null;
  doctor_avatar_url:     string | null;
  patient_name:          string;
  // client-enriched
  patient_avatar_url:    string | null;
}

interface State {
  results:  DoctorLabResult[];
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
}

export function useDoctorLabResults(): State {
  const { user } = useCurrentUser();
  const [results,  setResults]  = useState<DoctorLabResult[]>([]);
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
          api.get<any[]>('/api/lab-results'),
          api.get<any[]>('/api/patients'),
        ]);
      })
      .then(([apptRes, labRes, patientsRes]) => {
        const seenIds  = new Set<number>(apptRes.data.map((a: any) => a.patient_id));
        const avatarMap = new Map<number, string | null>();
        patientsRes.data.forEach((p: any) => avatarMap.set(p.user_id, p.avatar_url ?? null));

        const merged: DoctorLabResult[] = labRes.data
          .filter((r: any) => seenIds.has(r.patient_id))
          .map((r: any) => ({
            result_id:       r.result_id,
            patient_id:      r.patient_id,
            doctor_id:       r.doctor_id,
            consultation_id: r.consultation_id ?? null,
            test_name:       r.test_name       ?? '',
            test_type:       r.test_type       ?? 'BLOOD',
            result_value:    r.result_value    ?? null,
            unit:            r.unit            ?? null,
            reference_range: r.reference_range ?? null,
            result_data:     r.result_data     ?? null,
            status:          r.status          as LabStatus,
            is_critical:     r.is_critical     ?? 0,
            test_date:       r.test_date       ?? '',
            reviewed_by:     r.reviewed_by     ?? null,
            reviewed_at:     r.reviewed_at     ?? null,
            file_url:        r.file_url        ?? null,
            created_at:      r.created_at      ?? '',
            doctor_name:           r.doctor_name           ?? 'Unknown Doctor',
            doctor_specialization: r.doctor_specialization ?? null,
            doctor_avatar_url:     r.doctor_avatar_url     ?? null,
            patient_name:          r.patient_name          ?? 'Unknown Patient',
            patient_avatar_url:    avatarMap.get(r.patient_id) ?? null,
          }));

        merged.sort((a, b) => new Date(b.test_date).getTime() - new Date(a.test_date).getTime());

        setResults(merged);
        setError(null);
      })
      .catch(() => setError('Failed to load lab results.'))
      .finally(() => setLoading(false));
  }, [user?.user_id, tick]);

  return { results, loading, error, refetch: () => setTick(t => t + 1) };
}
