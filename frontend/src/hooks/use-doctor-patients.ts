import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export interface DoctorPatient {
  // Patient record
  patient_id:         number;
  user_id:            number;
  date_of_birth:      string | null;
  gender:             'MALE' | 'FEMALE' | 'OTHER' | null;
  blood_type:         string | null;
  allergies:          string[];
  chronic_conditions: string[];
  health_status:      'STABLE' | 'MONITOR' | 'CRITICAL' | null;
  patient_created_at: string;
  // Enriched from users table
  first_name:  string;
  last_name:   string;
  full_name:   string;
  email:       string | null;
  phone:       string | null;
  avatar_url:  string | null;
  // Computed from this doctor's appointments
  appointment_count:   number;
  last_appointment_at: string | null;
}

interface State {
  patients: DoctorPatient[];
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
}

export function useDoctorPatients(): State {
  const { user } = useCurrentUser();
  const [patients, setPatients] = useState<DoctorPatient[]>([]);
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
          api.get<any[]>(`/api/appointments?doctor_id=${doctor_id}`),
          api.get<any[]>('/api/patients'),
        ]);
      })
      .then(([apptRes, patientsRes]) => {
        // Build appointment stats per patient (patient_id = user_id in this schema)
        const countMap   = new Map<number, number>();
        const lastApptMap = new Map<number, string>();
        apptRes.data.forEach((a: any) => {
          const uid = a.patient_id;
          countMap.set(uid, (countMap.get(uid) ?? 0) + 1);
          const cur = lastApptMap.get(uid);
          if (!cur || a.scheduled_at > cur) lastApptMap.set(uid, a.scheduled_at);
        });

        // Only include patients this doctor has actually seen
        const seenUserIds = new Set(apptRes.data.map((a: any) => a.patient_id));

        const merged: DoctorPatient[] = patientsRes.data
          .filter((p: any) => seenUserIds.has(p.user_id))
          .map((p: any) => ({
            patient_id:          p.patient_id,
            user_id:             p.user_id,
            date_of_birth:       p.date_of_birth       ?? null,
            gender:              p.gender              ?? null,
            blood_type:          p.blood_type          ?? null,
            allergies:           Array.isArray(p.allergies)          ? p.allergies          : [],
            chronic_conditions:  Array.isArray(p.chronic_conditions) ? p.chronic_conditions : [],
            health_status:       p.health_status       ?? null,
            patient_created_at:  p.created_at          ?? '',
            first_name:          p.first_name          ?? '',
            last_name:           p.last_name           ?? '',
            full_name:           p.full_name           ?? `${p.first_name ?? ''} ${p.last_name ?? ''}`.trim(),
            email:               p.email               ?? null,
            phone:               p.phone               ?? null,
            avatar_url:          p.avatar_url          ?? null,
            appointment_count:   countMap.get(p.user_id)   ?? 0,
            last_appointment_at: lastApptMap.get(p.user_id) ?? null,
          }));

        // Sort by most recently seen
        merged.sort((a, b) => {
          if (!a.last_appointment_at) return  1;
          if (!b.last_appointment_at) return -1;
          return new Date(b.last_appointment_at).getTime() - new Date(a.last_appointment_at).getTime();
        });

        setPatients(merged);
        setError(null);
      })
      .catch(() => setError('Failed to load patients.'))
      .finally(() => setLoading(false));
  }, [user?.user_id, tick]);

  return { patients, loading, error, refetch: () => setTick(t => t + 1) };
}
