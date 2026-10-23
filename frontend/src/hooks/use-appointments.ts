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

    Promise.all([
      api.get<any[]>(`/api/appointments/patient/${user.system_id}`),
      api.get<any[]>('/api/doctors/profile/list/active'),
    ])
      .then(([apptRes, doctorsRes]) => {
        const doctorMap = new Map<string, { name: string; specialization: string | null }>();
        doctorsRes.data.forEach((d: any) => {
          const systemId = d.doctorSystemId ?? d.doctor_system_id;
          if (!systemId) return;
          const name = `Dr. ${d.firstName ?? d.first_name ?? ''} ${d.lastName ?? d.last_name ?? ''}`.trim();
          doctorMap.set(systemId, { name, specialization: d.specialization ?? null });
        });

        const mapped: Appointment[] = apptRes.data.map((a: any) => {
          const doctorSystemId = a.doctorSystemId ?? a.doctor_system_id ?? '';
          const doctor = doctorMap.get(doctorSystemId);
          return {
            appointment_id:          a.appointmentId          ?? 0,
            patient_id:              0,
            doctor_id:               0,
            facility_id:             a.facilityId             ?? null,
            scheduled_at:            a.scheduledAt            ? String(a.scheduledAt) : '',
            duration_minutes:        a.durationMinutes        ?? 30,
            type:                    (a.type                  ?? 'IN_PERSON') as Appointment['type'],
            status:                  (a.status                ?? 'SCHEDULED') as Appointment['status'],
            reason_for_visit:        a.reasonForVisit         ?? null,
            video_consultation_link: a.videoConsultationLink  ?? null,
            reminder_sent:           a.reminderSent           ? 1 : 0,
            notes:                   a.notes                  ?? null,
            created_at:              a.createdAt              ? String(a.createdAt) : '',
            updated_at:              a.updatedAt              ? String(a.updatedAt) : '',
            doctor_name:             doctor?.name             ?? (doctorSystemId || 'Unknown Doctor'),
            doctor_specialization:   doctor?.specialization   ?? null,
            doctor_avatar_url:       null,
            facility_name:           null,
          };
        });

        mapped.sort((a, b) => new Date(b.scheduled_at).getTime() - new Date(a.scheduled_at).getTime());
        setAppointments(mapped);
        setError(null);
      })
      .catch(() => setError('Failed to load appointments.'))
      .finally(() => setLoading(false));
  }, [user?.system_id, tick]);

  return { appointments, loading, error, refetch: () => setTick(t => t + 1) };
}
