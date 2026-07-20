import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';

export interface Doctor {
  doctor_id:      number;
  user_id:        number;
  specialization: string | null;
  license_no:     string | null;
  bio:            string | null;
  is_active:      number;
  full_name:      string;
  first_name:     string;
  last_name:      string;
  email:          string;
  phone:          string | null;
  avatar_url:     string | null;
}

interface State {
  doctors: Doctor[];
  loading: boolean;
  error:   string | null;
}

export function useDoctors() {
  const [state, setState] = useState<State>({ doctors: [], loading: true, error: null });

  useEffect(() => {
    api.get<Doctor[]>('/api/doctors')
      .then(({ data }) => setState({ doctors: data, loading: false, error: null }))
      .catch(() => setState({ doctors: [], loading: false, error: 'Failed to load doctors.' }));
  }, []);

  return state;
}
