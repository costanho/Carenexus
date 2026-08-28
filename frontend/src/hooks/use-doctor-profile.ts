import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export interface DoctorProfile {
  doctor_id:      number;
  user_id:        number;
  first_name:     string;
  last_name:      string;
  full_name:      string;
  email:          string;
  phone:          string | null;
  avatar_url:     string | null;
  specialization: string;
  license_no:     string;
  bio:            string | null;
  is_active:      number;
  created_at:     string;
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
  const [profile,  setProfile]  = useState<DoctorProfile | null>(null);
  const [stats,    setStats]    = useState<DoctorStats>(EMPTY_STATS);
  const [loading,  setLoading]  = useState(true);
  const [error,    setError]    = useState<string | null>(null);
  const [tick,     setTick]     = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);

    api.get(`/api/doctors/user/${user.user_id}`)
      .then(({ data: doctor }) => {
        const doctor_id = doctor.doctor_id;
        return Promise.all([
          Promise.resolve(doctor),
          api.get<any[]>(`/api/appointments?doctor_id=${doctor_id}`),
          api.get<any[]>(`/api/consultations?doctor_id=${doctor_id}`),
          api.get<any[]>(`/api/prescriptions?doctor_id=${doctor_id}`),
        ]);
      })
      .then(([doctor, apptRes, consultRes, rxRes]) => {
        const uniquePatients = new Set<number>(apptRes.data.map((a: any) => a.patient_id));

        setProfile({
          doctor_id:      doctor.doctor_id,
          user_id:        doctor.user_id,
          first_name:     doctor.first_name  ?? '',
          last_name:      doctor.last_name   ?? '',
          full_name:      doctor.full_name   ?? `Dr. ${doctor.first_name} ${doctor.last_name}`,
          email:          doctor.email       ?? '',
          phone:          doctor.phone       ?? null,
          avatar_url:     doctor.avatar_url  ?? null,
          specialization: doctor.specialization ?? '',
          license_no:     doctor.license_no  ?? '',
          bio:            doctor.bio         ?? null,
          is_active:      doctor.is_active   ?? 1,
          created_at:     doctor.created_at  ?? '',
        });

        setStats({
          appointments:  apptRes.data.length,
          patients:      uniquePatients.size,
          consultations: consultRes.data.length,
          prescriptions: rxRes.data.length,
        });

        setError(null);
      })
      .catch(() => setError('Failed to load profile.'))
      .finally(() => setLoading(false));
  }, [user?.user_id, tick]);

  return { profile, stats, loading, error, refetch: () => setTick(t => t + 1) };
}
