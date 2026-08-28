import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export interface ScheduleAppointment {
  appointment_id:          number;
  patient_id:              number;
  doctor_id:               number;
  facility_id:             number | null;
  scheduled_at:            string;
  duration_minutes:        number;
  type:                    'IN_PERSON' | 'VIDEO' | 'PHONE';
  status:                  'SCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW' | 'RESCHEDULED' | 'IN_PROGRESS';
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

export interface ScheduleConsultation {
  consultation_id:            number;
  appointment_id:             number;
  patient_id:                 number;
  doctor_id:                  number;
  chief_complaint:            string | null;
  diagnosis:                  string | null;
  treatment_plan:             string | null;
  follow_up_required:         number;
  follow_up_date:             string | null;
  duration_minutes:           number | null;
  status:                     'DRAFT' | 'COMPLETED' | 'IN_PROGRESS';
  started_at:                 string | null;
  completed_at:               string | null;
  created_at:                 string;
  doctor_name:                string;
  patient_name:               string;
}

export interface ScheduleEntry extends ScheduleAppointment {
  patient_name:   string;
  consultation:   ScheduleConsultation | null;
}

interface State {
  entries:  ScheduleEntry[];
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
}

export function useDoctorSchedule(): State {
  const { user } = useCurrentUser();
  const [entries,  setEntries]  = useState<ScheduleEntry[]>([]);
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
          api.get<ScheduleAppointment[]>(`/api/appointments?doctor_id=${doctor_id}`),
          api.get<ScheduleConsultation[]>(`/api/consultations?doctor_id=${doctor_id}`),
          api.get<{ users: { user_id: number; first_name: string; last_name: string }[] }>('/api/users/me')
            .then(() => api.get(`/api/patients`)),
        ]).then(([apptRes, consultRes, patientsRes]) => {
          const consultMap = new Map<number, ScheduleConsultation>();
          consultRes.data.forEach(c => consultMap.set(c.appointment_id, c));

          const patientMap = new Map<number, string>();
          (patientsRes.data as any[]).forEach((p: any) => {
            patientMap.set(p.user_id, `${p.first_name} ${p.last_name}`);
          });

          const merged: ScheduleEntry[] = apptRes.data.map(appt => ({
            ...appt,
            patient_name: patientMap.get(appt.patient_id) ?? `Patient #${appt.patient_id}`,
            consultation: consultMap.get(appt.appointment_id) ?? null,
          }));

          merged.sort((a, b) => new Date(b.scheduled_at).getTime() - new Date(a.scheduled_at).getTime());
          setEntries(merged);
          setError(null);
        });
      })
      .catch(() => setError('Failed to load schedule.'))
      .finally(() => setLoading(false));
  }, [user?.user_id, tick]);

  return { entries, loading, error, refetch: () => setTick(t => t + 1) };
}
