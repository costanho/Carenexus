import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';

// Shape returned by GET /api/doctors/schedule (camelCase from Spring)
export interface ApiScheduleEntry {
  entryId:                string;
  type:                   'APPOINTMENT' | 'CONSULTATION';
  patientSystemId:        string;
  patientName:            string;
  doctorSystemId:         string;
  scheduledAt:            string;
  durationMinutes:        number;
  createdAt:              string;
  updatedAt:              string;
  appointmentId?:         number;
  appointmentType?:       string;
  appointmentStatus?:     string;
  reasonForVisit?:        string | null;
  videoConsultationLink?: string | null;
  reminderSent?:          boolean;
  notes?:                 string | null;
  consultationId?:        number;
  chiefComplaint?:        string | null;
  diagnosis?:             string | null;
  treatmentPlan?:         string | null;
  followUpRequired?:      boolean;
  followUpDate?:          string | null;
  consultationStatus?:    string;
  startedAt?:             string | null;
  completedAt?:           string | null;
}

export type AppointmentStatus  = 'SCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW' | 'RESCHEDULED' | 'IN_PROGRESS';
export type ConsultationStatus = 'DRAFT' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';

export interface ScheduleConsultation {
  consultation_id: number;
  status:          ConsultationStatus;
}

// Public interface — matches the field names components already use
export interface ScheduleEntry {
  appointment_id:          number;
  scheduled_at:            string;
  duration_minutes:        number;
  type:                    'IN_PERSON' | 'VIDEO' | 'PHONE';
  status:                  AppointmentStatus;
  patient_name:            string;
  patient_system_id:       string;
  reason_for_visit:        string | null;
  video_consultation_link: string | null;
  notes:                   string | null;
  facility_name:           string | null;
  created_at:              string;
  updated_at:              string;
  /** The consultation linked to this appointment, if one has been started. */
  consultation:            ScheduleConsultation | null;
}

function toScheduleEntry(e: ApiScheduleEntry, consultation: ScheduleConsultation | null): ScheduleEntry {
  return {
    appointment_id:          e.appointmentId ?? 0,
    scheduled_at:            e.scheduledAt,
    duration_minutes:        e.durationMinutes,
    type:                    (e.appointmentType as ScheduleEntry['type']) ?? 'IN_PERSON',
    status:                  (e.appointmentStatus ?? 'SCHEDULED') as AppointmentStatus,
    patient_name:            e.patientName,
    patient_system_id:       e.patientSystemId,
    reason_for_visit:        e.reasonForVisit ?? null,
    video_consultation_link: e.videoConsultationLink ?? null,
    notes:                   e.notes ?? null,
    facility_name:           null,
    created_at:              e.createdAt,
    updated_at:              e.updatedAt,
    consultation,
  };
}

/**
 * The backend returns appointments and consultations as separate rows
 * (type APPOINTMENT / CONSULTATION). Collapse them into one entry per
 * appointment, attaching the linked consultation (if any).
 */
export function toScheduleEntries(rows: ApiScheduleEntry[]): ScheduleEntry[] {
  const consults = new Map<number, ScheduleConsultation>();
  rows.forEach(e => {
    if (e.type === 'CONSULTATION' && e.appointmentId != null) {
      consults.set(e.appointmentId, {
        consultation_id: e.consultationId ?? 0,
        status:          (e.consultationStatus ?? 'DRAFT') as ConsultationStatus,
      });
    }
  });
  return rows
    .filter(e => e.type === 'APPOINTMENT')
    .map(e => toScheduleEntry(e, e.appointmentId != null ? consults.get(e.appointmentId) ?? null : null));
}

interface State {
  entries:  ScheduleEntry[];
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
}

export function useDoctorSchedule(): State {
  const [entries, setEntries] = useState<ScheduleEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error,   setError]   = useState<string | null>(null);
  const [tick,    setTick]    = useState(0);

  useEffect(() => {
    setLoading(true);
    api.get<ApiScheduleEntry[]>('/api/doctors/schedule')
      .then(({ data }) => {
        setEntries(toScheduleEntries(data));
        setError(null);
      })
      .catch(() => setError('Failed to load schedule.'))
      .finally(() => setLoading(false));
  }, [tick]);

  return { entries, loading, error, refetch: () => setTick(t => t + 1) };
}
