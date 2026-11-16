import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';

export interface DoctorPatient {
  // Patient record
  patient_system_id:  string;
  /** @deprecated numeric ids are not exposed by the API; always 0. Use patient_system_id. */
  patient_id:         number;
  /** @deprecated numeric ids are not exposed by the API; always 0. */
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

/** Accepts a JSON array string, a plain array, or a comma-separated string. */
export function toStringList(raw: unknown): string[] {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw.map(String).filter(Boolean);
  if (typeof raw !== 'string') return [];
  const trimmed = raw.trim();
  if (!trimmed) return [];
  if (trimmed.startsWith('[')) {
    try {
      const parsed = JSON.parse(trimmed);
      return Array.isArray(parsed) ? parsed.map(String).filter(Boolean) : [];
    } catch {
      return [];
    }
  }
  return trimmed.split(',').map(x => x.trim()).filter(Boolean);
}

// GET /api/doctors/patients → DoctorPatientResponse[] (camelCase)
function toDoctorPatient(p: any): DoctorPatient {
  const first = p.firstName ?? '';
  const last  = p.lastName  ?? '';
  return {
    patient_system_id:   p.patientSystemId ?? '',
    patient_id:          0,
    user_id:             0,
    date_of_birth:       p.dateOfBirth ? String(p.dateOfBirth) : null,
    gender:              p.gender       ?? null,
    blood_type:          p.bloodType    ?? null,
    allergies:           toStringList(p.allergies),
    chronic_conditions:  toStringList(p.chronicConditions),
    health_status:       p.healthStatus ?? null,
    patient_created_at:  p.patientCreatedAt ? String(p.patientCreatedAt) : '',
    first_name:          first,
    last_name:           last,
    full_name:           `${first} ${last}`.trim() || 'Unknown Patient',
    email:               p.email ?? null,
    phone:               p.phone ?? null,
    avatar_url:          null,
    appointment_count:   p.totalAppointments ?? 0,
    last_appointment_at: p.lastAppointmentDate ? String(p.lastAppointmentDate) : null,
  };
}

export function useDoctorPatients(): State {
  const [patients, setPatients] = useState<DoctorPatient[]>([]);
  const [loading,  setLoading]  = useState(true);
  const [error,    setError]    = useState<string | null>(null);
  const [tick,     setTick]     = useState(0);

  useEffect(() => {
    setLoading(true);
    api.get<any[]>('/api/doctors/patients')
      .then(({ data }) => {
        const merged = (data ?? []).map(toDoctorPatient);
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
  }, [tick]);

  return { patients, loading, error, refetch: () => setTick(t => t + 1) };
}
