import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export interface Prescription {
  prescription_id:      number;
  patient_id:           number;
  doctor_id:            number;
  consultation_id:      number | null;
  medication_name:      string;
  generic_name:         string | null;
  dosage:               string;
  frequency:            string;
  route:                'ORAL' | 'TOPICAL' | 'INJECTION' | 'INHALED' | 'SUBLINGUAL' | 'RECTAL' | 'IV';
  quantity:             string | null;
  refills_allowed:      number;
  special_instructions: string | null;
  status:               'ACTIVE' | 'COMPLETED' | 'CANCELLED' | 'EXPIRED' | 'ON_HOLD';
  prescribed_date:      string;
  expiry_date:          string | null;
  created_at:           string;
  doctor_name:          string;
  doctor_specialization: string | null;
  doctor_avatar_url:    string | null;
  patient_name:         string;
}

interface State {
  prescriptions: Prescription[];
  loading:       boolean;
  error:         string | null;
  refetch:       () => void;
}

export function usePrescriptions(): State {
  const { user } = useCurrentUser();
  const [prescriptions, setPrescriptions] = useState<Prescription[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError]     = useState<string | null>(null);
  const [tick, setTick]       = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);
    api.get<Prescription[]>(`/api/prescriptions?patient_id=${user.user_id}`)
      .then(({ data }) => {
        setPrescriptions(data);
        setError(null);
      })
      .catch(() => setError('Failed to load prescriptions.'))
      .finally(() => setLoading(false));
  }, [user?.user_id, tick]);

  return { prescriptions, loading, error, refetch: () => setTick(t => t + 1) };
}
