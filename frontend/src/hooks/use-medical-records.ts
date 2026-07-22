import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export interface MedicalRecord {
  record_id:             number;
  patient_id:            number;
  doctor_id:             number;
  consultation_id:       number | null;
  record_type:           'CONSULT' | 'LAB' | 'IMAGING' | 'PRESCRIPTION' | 'REFERRAL' | 'DISCHARGE' | 'NOTE' | 'OPERATION';
  title:                 string;
  description:           string | null;
  treatment_plan:        string | null;
  observations:          string | null;
  icd_codes:             Array<{ code: string; description: string }> | null;
  is_deleted:            number;
  deleted_at:            string | null;
  created_at:            string;
  updated_at:            string;
  doctor_name:           string;
  doctor_specialization: string | null;
  doctor_avatar_url:     string | null;
  patient_name:          string;
}

interface State {
  records:  MedicalRecord[];
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
}

export function useMedicalRecords(): State {
  const { user } = useCurrentUser();
  const [records, setRecords] = useState<MedicalRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError]     = useState<string | null>(null);
  const [tick, setTick]       = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);
    api.get<MedicalRecord[]>(`/api/medical-records?patient_id=${user.user_id}`)
      .then(({ data }) => {
        setRecords(data);
        setError(null);
      })
      .catch(() => setError('Failed to load medical records.'))
      .finally(() => setLoading(false));
  }, [user?.user_id, tick]);

  return { records, loading, error, refetch: () => setTick(t => t + 1) };
}
