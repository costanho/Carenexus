import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';

export interface Doctor {
  doctor_id:        number;
  doctor_system_id: string;
  user_id:          number;
  specialization:   string | null;
  license_no:       string | null;
  bio:              string | null;
  is_active:        number;
  full_name:        string;
  first_name:       string;
  last_name:        string;
  email:            string;
  phone:            string | null;
  avatar_url:       string | null;
}

interface State {
  doctors: Doctor[];
  loading: boolean;
  error:   string | null;
}

export function useDoctors() {
  const [state, setState] = useState<State>({ doctors: [], loading: true, error: null });

  useEffect(() => {
    api.get<any[]>('/api/doctors/profile/list/active')
      .then(({ data }) => {
        const doctors: Doctor[] = data.map((d: any) => {
          const firstName = d.firstName ?? d.first_name ?? '';
          const lastName  = d.lastName  ?? d.last_name  ?? '';
          return {
            doctor_id:        d.userId          ?? d.user_id          ?? 0,
            doctor_system_id: d.doctorSystemId  ?? d.doctor_system_id ?? '',
            user_id:          d.userId          ?? d.user_id          ?? 0,
            specialization: d.specialization             ?? null,
            license_no:     d.licenseNo    ?? d.license_no ?? null,
            bio:            d.bio                        ?? null,
            is_active:      d.isActive     ? 1 : 0,
            full_name:      `Dr. ${firstName} ${lastName}`.trim(),
            first_name:     firstName,
            last_name:      lastName,
            email:          d.email                      ?? '',
            phone:          d.phone                      ?? null,
            avatar_url:     null,
          };
        });
        setState({ doctors, loading: false, error: null });
      })
      .catch((err) => {
        console.error('useDoctors error:', err?.response?.status, err?.response?.data ?? err?.message);
        setState({ doctors: [], loading: false, error: 'Failed to load doctors.' });
      });
  }, []);

  return state;
}
