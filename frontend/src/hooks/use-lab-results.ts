import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export interface LabResult {
  result_id:             number;
  patient_id:            number;
  doctor_id:             number;
  consultation_id:       number | null;
  test_name:             string;
  test_type:             'BLOOD' | 'URINE' | 'STOOL' | 'CULTURE' | 'BIOPSY' | 'GENETIC' | 'SWAB' | 'OTHER';
  result_value:          string | null;
  unit:                  string | null;
  reference_range:       string | null;
  result_data:           Record<string, unknown> | null;
  status:                'PENDING' | 'COMPLETED' | 'CRITICAL' | 'REQUIRES_REVIEW' | 'CANCELLED';
  is_critical:           number;
  test_date:             string;
  reviewed_by:           number | null;
  reviewed_at:           string | null;
  file_url:              string | null;
  created_at:            string;
  doctor_name:           string;
  doctor_specialization: string | null;
  doctor_avatar_url:     string | null;
  reviewer_name:         string | null;
  patient_name:          string;
}

interface State {
  results:  LabResult[];
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
}

export function useLabResults(): State {
  const { user } = useCurrentUser();
  const [results, setResults] = useState<LabResult[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError]     = useState<string | null>(null);
  const [tick, setTick]       = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);
    api.get<LabResult[]>(`/api/lab-results?patient_id=${user.user_id}`)
      .then(({ data }) => {
        setResults(data);
        setError(null);
      })
      .catch(() => setError('Failed to load lab results.'))
      .finally(() => setLoading(false));
  }, [user?.user_id, tick]);

  return { results, loading, error, refetch: () => setTick(t => t + 1) };
}
