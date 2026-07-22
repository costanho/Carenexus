import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export interface ImagingResult {
  imaging_id:            number;
  patient_id:            number;
  doctor_id:             number;
  consultation_id:       number | null;
  imaging_type:          'XRAY' | 'MRI' | 'CT_SCAN' | 'ULTRASOUND' | 'PET' | 'MAMMOGRAPHY' | 'DEXA' | 'ECHO';
  body_part:             string | null;
  radiologist_report:    string | null;
  findings:              string | null;
  impression:            string | null;
  file_url:              string | null;
  thumbnail_url:         string | null;
  status:                'PENDING' | 'COMPLETED' | 'REQUIRES_REVIEW' | 'CRITICAL';
  image_date:            string;
  reviewed_by:           number | null;
  reviewed_at:           string | null;
  created_at:            string;
  doctor_name:           string;
  doctor_specialization: string | null;
  doctor_avatar_url:     string | null;
  reviewer_name:         string | null;
  patient_name:          string;
}

interface State {
  results:  ImagingResult[];
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
}

export function useImagingResults(): State {
  const { user } = useCurrentUser();
  const [results, setResults] = useState<ImagingResult[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError]     = useState<string | null>(null);
  const [tick, setTick]       = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);
    api.get<ImagingResult[]>(`/api/imaging-results?patient_id=${user.user_id}`)
      .then(({ data }) => {
        setResults(data);
        setError(null);
      })
      .catch(() => setError('Failed to load imaging results.'))
      .finally(() => setLoading(false));
  }, [user?.user_id, tick]);

  return { results, loading, error, refetch: () => setTick(t => t + 1) };
}
