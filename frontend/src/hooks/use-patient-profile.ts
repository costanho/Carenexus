import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';

interface UserData {
  user_id:    number;
  first_name: string;
  last_name:  string;
  email:      string;
  phone:      string | null;
  role:       string;
  is_active:  number;
  avatar_url: string | null;
  created_at: string;
  updated_at: string;
}

interface PatientData {
  patient_id:          number;
  user_id:             number;
  date_of_birth:       string | null;
  gender:              'MALE' | 'FEMALE' | 'OTHER' | null;
  blood_type:          string | null;
  allergies:           string[];
  chronic_conditions:  string[];
  health_status:       'STABLE' | 'MONITOR' | 'CRITICAL';
  created_at:          string;
}

export interface PatientProfile {
  user_id:             number;
  first_name:          string;
  last_name:           string;
  email:               string;
  phone:               string | null;
  role:                string;
  is_active:           number;
  avatar_url:          string | null;
  user_created_at:     string;
  user_updated_at:     string;
  patient_id:          number | null;
  date_of_birth:       string | null;
  gender:              'MALE' | 'FEMALE' | 'OTHER' | null;
  blood_type:          string | null;
  allergies:           string[];
  chronic_conditions:  string[];
  health_status:       'STABLE' | 'MONITOR' | 'CRITICAL' | null;
  patient_created_at:  string | null;
}

interface State {
  profile:  PatientProfile | null;
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
}

export function usePatientProfile(): State {
  const [profile,  setProfile]  = useState<PatientProfile | null>(null);
  const [loading,  setLoading]  = useState(true);
  const [error,    setError]    = useState<string | null>(null);
  const [tick,     setTick]     = useState(0);

  useEffect(() => {
    setLoading(true);
    Promise.all([
      api.get<UserData>('/api/users/me'),
      api.get<PatientData>('/api/patients/me').catch(() => ({ data: null })),
    ])
      .then(([{ data: user }, { data: patient }]) => {
        setProfile({
          user_id:            user.user_id,
          first_name:         user.first_name,
          last_name:          user.last_name,
          email:              user.email,
          phone:              user.phone,
          role:               user.role,
          is_active:          user.is_active,
          avatar_url:         user.avatar_url,
          user_created_at:    user.created_at,
          user_updated_at:    user.updated_at,
          patient_id:         patient?.patient_id    ?? null,
          date_of_birth:      patient?.date_of_birth ?? null,
          gender:             patient?.gender         ?? null,
          blood_type:         patient?.blood_type     ?? null,
          allergies:          patient?.allergies       ?? [],
          chronic_conditions: patient?.chronic_conditions ?? [],
          health_status:      patient?.health_status  ?? null,
          patient_created_at: patient?.created_at     ?? null,
        });
        setError(null);
      })
      .catch(() => setError('Failed to load profile.'))
      .finally(() => setLoading(false));
  }, [tick]);

  return { profile, loading, error, refetch: () => setTick(t => t + 1) };
}
