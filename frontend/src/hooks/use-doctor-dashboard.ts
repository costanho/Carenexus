import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { type ApiScheduleEntry, type ScheduleEntry, toScheduleEntries } from './use-doctor-schedule';

export interface DoctorDashboard {
  first_name:              string;
  last_name:               string;
  specialization:          string | null;
  /** Number of distinct patients this doctor has appointments with (null if it could not be loaded). */
  my_patients:             number | null;
  total_appointments:      number;
  completed_consultations: number;
  pending_consultations:   number;
  /** Today's appointments, one entry per appointment (consultation rows merged in). */
  today:                   ScheduleEntry[];
  /** Future appointments that are still SCHEDULED / RESCHEDULED, soonest first. */
  upcoming:                ScheduleEntry[];
}

interface State {
  dashboard: DoctorDashboard | null;
  loading:   boolean;
  error:     string | null;
  refetch:   () => void;
}

const ACTIVE = new Set(['SCHEDULED', 'RESCHEDULED', 'IN_PROGRESS']);

// GET /api/doctors/schedule/dashboard (DoctorDashboardResponse) + GET /api/doctors/patients for the patient count.
export function useDoctorDashboard(): State {
  const [dashboard, setDashboard] = useState<DoctorDashboard | null>(null);
  const [loading,   setLoading]   = useState(true);
  const [error,     setError]     = useState<string | null>(null);
  const [tick,      setTick]      = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);

    Promise.all([
      api.get<any>('/api/doctors/schedule/dashboard'),
      api.get<any[]>('/api/doctors/patients').then(r => r.data).catch(() => null),
    ])
      .then(([{ data: d }, patients]) => {
        if (cancelled) return;
        const byTime = (a: ScheduleEntry, b: ScheduleEntry) =>
          new Date(a.scheduled_at).getTime() - new Date(b.scheduled_at).getTime();
        const now = Date.now();

        const today    = toScheduleEntries((d?.todaySchedule ?? []) as ApiScheduleEntry[]).sort(byTime);
        const upcoming = toScheduleEntries((d?.upcomingSchedule ?? []) as ApiScheduleEntry[])
          .filter(e => ACTIVE.has(e.status) && new Date(e.scheduled_at).getTime() >= now)
          .sort(byTime);

        setDashboard({
          first_name:              d?.firstName ?? '',
          last_name:               d?.lastName ?? '',
          specialization:          d?.specialization ?? null,
          my_patients:             Array.isArray(patients) ? patients.length : null,
          total_appointments:      d?.totalAppointments ?? 0,
          completed_consultations: d?.completedConsultations ?? 0,
          pending_consultations:   d?.pendingConsultations ?? 0,
          today,
          upcoming,
        });
        setError(null);
      })
      .catch(err => {
        if (!cancelled) setError(err?.response?.data?.message ?? 'Failed to load dashboard.');
      })
      .finally(() => { if (!cancelled) setLoading(false); });

    return () => { cancelled = true; };
  }, [tick]);

  return { dashboard, loading, error, refetch: () => setTick(t => t + 1) };
}

export function greetingForHour(date = new Date()): string {
  const h = date.getHours();
  if (h < 12) return 'Good morning';
  if (h < 18) return 'Good afternoon';
  return 'Good evening';
}
