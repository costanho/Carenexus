import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export interface Appointment {
  appointment_id:          number;
  patient_id:              number;
  doctor_id:               number;
  facility_id:             number | null;
  scheduled_at:            string;
  duration_minutes:        number;
  type:                    'IN_PERSON' | 'VIDEO' | 'PHONE';
  status:                  'SCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW' | 'RESCHEDULED';
  reason_for_visit:        string | null;
  video_consultation_link: string | null;
  reminder_sent:           number;
  notes:                   string | null;
  created_at:              string;
  updated_at:              string;
  doctor_name:             string;
  doctor_specialization:   string | null;
  doctor_avatar_url:       string | null;
  facility_name:           string | null;
}

interface State {
  appointments: Appointment[];
  loading:      boolean;
  error:        string | null;
  refetch:      () => void;
}

export function useAppointments(): State {
  const { user } = useCurrentUser();
  const [appointments, setAppointments] = useState<Appointment[]>([]);
  const [loading, setLoading]           = useState(true);
  const [error, setError]               = useState<string | null>(null);
  const [tick, setTick]                 = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);
    api.get<Appointment[]>(`/api/appointments?patient_id=${user.user_id}`)
      .then(({ data }) => {
        const sorted = [...data].sort(
          (a, b) => new Date(b.scheduled_at).getTime() - new Date(a.scheduled_at).getTime()
        );
        setAppointments(sorted);
        setError(null);
      })
      .catch(() => setError('Failed to load appointments.'))
      .finally(() => setLoading(false));
  }, [user?.user_id, tick]);

  return { appointments, loading, error, refetch: () => setTick(t => t + 1) };
}
