import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { apiError } from '@/lib/api/errors';
import { fetchPage } from '@/lib/api/paged';
import { isAfterClinicNow } from '@/lib/time/clinic';
import { type ApiScheduleEntry, type ScheduleEntry, toScheduleEntries } from './use-doctor-schedule';

export interface DoctorDashboard {
  first_name:              string;
  last_name:               string;
  specialization:          string | null;
  /** Number of patients related to this doctor (null if it could not be loaded). */
  my_patients:             number | null;
  total_appointments:      number;
  /** All active appointments that have not started yet, counted by the server (not just the listed ones). */
  upcoming_appointments:   number;
  /** Appointments that ended without an outcome and are still SCHEDULED or RESCHEDULED. */
  needs_outcome_count:     number;
  completed_consultations: number;
  pending_consultations:   number;
  /** Today's appointments (clinic day), one entry per appointment (consultation rows merged in). */
  today:                   ScheduleEntry[];
  /** The next appointments that are still SCHEDULED / RESCHEDULED, soonest first (the server sends at most ten). */
  upcoming:                ScheduleEntry[];
}

interface State {
  dashboard: DoctorDashboard | null;
  loading:   boolean;
  error:     string | null;
  refetch:   () => void;
}

const ACTIVE = new Set(['SCHEDULED', 'RESCHEDULED', 'IN_PROGRESS']);

function count(value: unknown, fallback = 0): number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : fallback;
}

// Clinic wall-clock strings ("YYYY-MM-DDTHH:mm:ss") sort in time order as text.
const byTime = (a: ScheduleEntry, b: ScheduleEntry) =>
  String(a.scheduled_at).localeCompare(String(b.scheduled_at)) || a.appointment_id - b.appointment_id;

// GET /api/doctors/schedule/dashboard (DoctorDashboardResponse). The patient count is `totalElements` of
// GET /api/doctors/patients/search?size=1, so no patient list is loaded to count it.
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
      fetchPage<unknown>('/api/doctors/patients/search', 0, 1).then(page => page.totalElements).catch(() => null),
    ])
      .then(([{ data: d }, patientCount]) => {
        if (cancelled) return;

        const today    = toScheduleEntries((d?.todaySchedule ?? []) as ApiScheduleEntry[]).sort(byTime);
        // "Upcoming" is decided in clinic time, like the server does, never with the device clock.
        const upcoming = toScheduleEntries((d?.upcomingSchedule ?? []) as ApiScheduleEntry[])
          .filter(e => ACTIVE.has(e.status) && isAfterClinicNow(e.scheduled_at))
          .sort(byTime);

        setDashboard({
          first_name:              d?.firstName ?? '',
          last_name:               d?.lastName ?? '',
          specialization:          d?.specialization ?? null,
          my_patients:             typeof patientCount === 'number' ? patientCount : null,
          total_appointments:      count(d?.totalAppointments),
          // The list stops at ten rows; the count does not.
          upcoming_appointments:   count(d?.upcomingAppointments, upcoming.length),
          needs_outcome_count:     count(d?.needsOutcomeCount),
          completed_consultations: count(d?.completedConsultations),
          pending_consultations:   count(d?.pendingConsultations),
          today,
          upcoming,
        });
        setError(null);
      })
      .catch(err => {
        if (!cancelled) setError(apiError(err, 'Failed to load dashboard.').message);
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
