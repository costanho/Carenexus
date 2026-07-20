import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export interface Consultation {
  consultation_id:           number;
  appointment_id:            number;
  patient_id:                number;
  doctor_id:                 number;
  chief_complaint:           string | null;
  history_of_present_illness:string | null;
  physical_examination:      string | null;
  clinical_notes:            string | null;
  diagnosis:                 string | null;
  treatment_plan:            string | null;
  follow_up_instructions:    string | null;
  follow_up_required:        number;
  follow_up_date:            string | null;
  duration_minutes:          number | null;
  status:                    'IN_PROGRESS' | 'COMPLETED' | 'DRAFT' | 'CANCELLED';
  started_at:                string | null;
  completed_at:              string | null;
  created_at:                string;
  doctor_name:               string;
  doctor_specialization:     string | null;
  doctor_avatar_url:         string | null;
  patient_name:              string;
  appointment_type:          'IN_PERSON' | 'VIDEO' | 'PHONE' | null;
  appointment_status:        string | null;
}

interface State {
  consultations: Consultation[];
  loading:       boolean;
  error:         string | null;
  refetch:       () => void;
}

export function useConsultations(): State {
  const { user } = useCurrentUser();
  const [consultations, setConsultations] = useState<Consultation[]>([]);
  const [loading, setLoading]             = useState(true);
  const [error, setError]                 = useState<string | null>(null);
  const [tick, setTick]                   = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);
    api.get<Consultation[]>(`/api/consultations?patient_id=${user.user_id}`)
      .then(({ data }) => {
        const sorted = [...data].sort((a, b) => {
          const aDate = a.completed_at ?? a.created_at;
          const bDate = b.completed_at ?? b.created_at;
          return new Date(bDate).getTime() - new Date(aDate).getTime();
        });
        setConsultations(sorted);
        setError(null);
      })
      .catch(() => setError('Failed to load consultations.'))
      .finally(() => setLoading(false));
  }, [user?.user_id, tick]);

  return { consultations, loading, error, refetch: () => setTick(t => t + 1) };
}
