import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export interface DoctorConsultation {
  consultation_id:            number;
  appointment_id:             number;
  patient_id:                 number;
  doctor_id:                  number;
  chief_complaint:            string | null;
  history_of_present_illness: string | null;
  physical_examination:       string | null;
  clinical_notes:             string | null;
  diagnosis:                  string | null;
  treatment_plan:             string | null;
  follow_up_instructions:     string | null;
  follow_up_required:         number;
  follow_up_date:             string | null;
  duration_minutes:           number | null;
  status:                     'DRAFT' | 'IN_PROGRESS' | 'COMPLETED';
  started_at:                 string | null;
  completed_at:               string | null;
  created_at:                 string;
  // Enriched by mock server
  doctor_name:                string;
  doctor_specialization:      string | null;
  doctor_avatar_url:          string | null;
  patient_name:               string;
  appointment_type:           'IN_PERSON' | 'VIDEO' | 'PHONE' | null;
  appointment_status:         string | null;
}

interface State {
  consultations: DoctorConsultation[];
  loading:       boolean;
  error:         string | null;
  refetch:       () => void;
}

export function useDoctorConsultations(): State {
  const { user } = useCurrentUser();
  const [consultations, setConsultations] = useState<DoctorConsultation[]>([]);
  const [loading,       setLoading]       = useState(true);
  const [error,         setError]         = useState<string | null>(null);
  const [tick,          setTick]          = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);

    api.get(`/api/doctors/user/${user.user_id}`)
      .then(({ data: doctor }) => {
        return api.get<DoctorConsultation[]>(`/api/consultations?doctor_id=${doctor.doctor_id}`);
      })
      .then(({ data }) => {
        const sorted = [...data].sort(
          (a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime()
        );
        setConsultations(sorted);
        setError(null);
      })
      .catch(() => setError('Failed to load consultations.'))
      .finally(() => setLoading(false));
  }, [user?.user_id, tick]);

  return { consultations, loading, error, refetch: () => setTick(t => t + 1) };
}
