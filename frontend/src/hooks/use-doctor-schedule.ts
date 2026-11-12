import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export interface ScheduleEntry {
  appointmentId:          number;
  patientSystemId:        string;
  patientName:            string | null;
  doctorSystemId:         string;
  scheduledAt:            string;
  durationMinutes:        number;
  type:                   'IN_PERSON' | 'VIDEO' | 'PHONE';
  status:                 'SCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW' | 'RESCHEDULED' | 'IN_PROGRESS';
  reasonForVisit:         string | null;
  videoConsultationLink:  string | null;
  notes:                  string | null;
  consultation?: {
    status: 'DRAFT' | 'IN_PROGRESS' | 'COMPLETED';
  };
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
    if (!user?.system_id) return;
    setLoading(true);
    Promise.all([
      api.get<ScheduleEntry[]>(`/api/doctors/appointments/${user.system_id}`),
      api.get<any[]>(`/api/doctors/patients/${user.system_id}`),
    ])
      .then(([apptRes, patientsRes]) => {
        const nameMap = new Map<string, string>();
        patientsRes.data.forEach((p: any) => {
          const sysId = p.patientSystemId ?? p.patient_system_id;
          if (!sysId) return;
          const name = `${p.firstName ?? p.first_name ?? ''} ${p.lastName ?? p.last_name ?? ''}`.trim();
          if (name) nameMap.set(sysId, name);
        });

        const sorted = [...apptRes.data]
          .map(e => ({
            ...e,
            patientName: e.patientName || nameMap.get(e.patientSystemId) || e.patientSystemId,
          }))
          .sort((a, b) => new Date(b.scheduledAt).getTime() - new Date(a.scheduledAt).getTime());
        setEntries(sorted);
        setError(null);
      })
      .catch(() => setError('Failed to load appointments.'))
      .finally(() => setLoading(false));
  }, [user?.system_id, tick]);

  return { entries, loading, error, refetch: () => setTick(t => t + 1) };
}
