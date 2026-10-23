import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';

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
  patient_system_id:   string | null;
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

function parseStringArray(raw: any): string[] {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw;
  try { return JSON.parse(raw); } catch { return []; }
}

export function usePatientProfile(): State {
  const [profile,  setProfile]  = useState<PatientProfile | null>(null);
  const [loading,  setLoading]  = useState(true);
  const [error,    setError]    = useState<string | null>(null);
  const [tick,     setTick]     = useState(0);

  useEffect(() => {
    setLoading(true);

    api.get<any>('/api/profile')
      .then(({ data: d }) => {
        setProfile({
          user_id:            d.userId            ?? 0,
          first_name:         d.firstName         ?? '',
          last_name:          d.lastName          ?? '',
          email:              d.email             ?? '',
          phone:              d.phone             ?? null,
          role:               d.role              ?? '',
          is_active:          d.isActive          ? 1 : 0,
          avatar_url:         null,
          user_created_at:    d.createdAt         ? String(d.createdAt) : '',
          user_updated_at:    d.updatedAt         ? String(d.updatedAt) : '',
          patient_id:         null,
          patient_system_id:  d.patientSystemId   ?? null,
          date_of_birth:      d.dateOfBirth       ? String(d.dateOfBirth) : null,
          gender:             (d.gender           ?? null) as PatientProfile['gender'],
          blood_type:         d.bloodType         ?? null,
          allergies:          parseStringArray(d.allergies),
          chronic_conditions: parseStringArray(d.chronicConditions),
          health_status:      (d.healthStatus     ?? null) as PatientProfile['health_status'],
          patient_created_at: d.createdAt         ? String(d.createdAt) : null,
        });
        setError(null);
      })
      .catch(() => setError('Failed to load profile.'))
      .finally(() => setLoading(false));
  }, [tick]);

  return { profile, loading, error, refetch: () => setTick(t => t + 1) };
}
