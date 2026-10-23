import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export interface DoctorProfile {
  doctor_system_id: string;
  user_id:          number;
  first_name:       string;
  last_name:        string;
  full_name:        string;
  email:            string;
  phone:            string | null;
  avatar_url:       string | null;
  specialization:   string;
  license_no:       string;
  bio:              string | null;
  is_active:        boolean;
  created_at:       string;
}

export interface DoctorStats {
  appointments:  number;
  patients:      number;
  consultations: number;
  prescriptions: number;
}

interface State {
  profile:  DoctorProfile | null;
  stats:    DoctorStats;
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
}

const EMPTY_STATS: DoctorStats = { appointments: 0, patients: 0, consultations: 0, prescriptions: 0 };

export function useDoctorProfile(): State {
  const { user } = useCurrentUser();
  const [profile, setProfile] = useState<DoctorProfile | null>(null);
  const [stats,   setStats]   = useState<DoctorStats>(EMPTY_STATS);
  const [loading, setLoading] = useState(true);
  const [error,   setError]   = useState<string | null>(null);
  const [tick,    setTick]    = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);

    api.get('/api/doctors/profile')
      .then(({ data: d }) => {
        setProfile({
          doctor_system_id: d.doctorSystemId    ?? '',
          user_id:          d.userId            ?? user.user_id,
          first_name:       d.firstName         ?? user.first_name,
          last_name:        d.lastName          ?? user.last_name,
          full_name:        `Dr. ${d.firstName ?? ''} ${d.lastName ?? ''}`.trim(),
          email:            d.email             ?? user.email,
          phone:            d.phone             ?? null,
          avatar_url:       null,
          specialization:   d.specialization    ?? '',
          license_no:       d.licenseNo         ?? '',
          bio:              d.bio               ?? null,
          is_active:        d.isActive          ?? true,
          created_at:       d.doctorCreatedAt   ?? '',
        });
        setStats({
          appointments:  d.totalAppointments  ?? 0,
          patients:      d.totalPatients      ?? 0,
          consultations: d.totalConsultations ?? 0,
          prescriptions: d.totalPrescriptions ?? 0,
        });
        setError(null);
      })
      .catch(() => setError('Failed to load profile.'))
      .finally(() => setLoading(false));
  }, [user?.system_id, tick]);

  return { profile, stats, loading, error, refetch: () => setTick(t => t + 1) };
}
