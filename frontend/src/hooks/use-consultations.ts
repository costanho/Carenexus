import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export interface Consultation {
  consultation_id:           number;
  appointment_id:            number;
  patient_id:                number;
  doctor_id:                 number;
  chief_complaint:           string | null;
  history_of_present_illness:string | null;
  physical_examination:      string | null;
  clinical_notes:            string | null;
  diagnosis:                 string | null;
  treatment_plan:            string | null;
  follow_up_instructions:    string | null;
  follow_up_required:        number;
  follow_up_date:            string | null;
  duration_minutes:          number | null;
  status:                    'IN_PROGRESS' | 'COMPLETED' | 'DRAFT' | 'CANCELLED';
  started_at:                string | null;
  completed_at:              string | null;
  created_at:                string;
  doctor_name:               string;
  doctor_specialization:     string | null;
  doctor_avatar_url:         string | null;
  patient_name:              string;
  appointment_type:          'IN_PERSON' | 'VIDEO' | 'PHONE' | null;
  appointment_status:        string | null;
}

interface State {
  consultations: Consultation[];
  loading:       boolean;
  error:         string | null;
  refetch:       () => void;
}

export function useConsultations(): State {
  const { user } = useCurrentUser();
  const [consultations, setConsultations] = useState<Consultation[]>([]);
  const [loading, setLoading]             = useState(true);
  const [error, setError]                 = useState<string | null>(null);
  const [tick, setTick]                   = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);

    Promise.all([
      api.get<any[]>(`/api/consultations/patient/${user.system_id}`),
      api.get<any[]>('/api/doctors/profile/list/active'),
    ])
      .then(([consultRes, doctorsRes]) => {
        const doctorMap = new Map<string, { name: string; specialization: string | null }>();
        doctorsRes.data.forEach((d: any) => {
          const systemId = d.doctorSystemId ?? d.doctor_system_id;
          if (!systemId) return;
          const name = `Dr. ${d.firstName ?? d.first_name ?? ''} ${d.lastName ?? d.last_name ?? ''}`.trim();
          doctorMap.set(systemId, { name, specialization: d.specialization ?? null });
        });

        const mapped: Consultation[] = consultRes.data.map((r: any) => {
          const doctorSystemId = r.doctorSystemId ?? '';
          const doctor = doctorMap.get(doctorSystemId);
          return {
            consultation_id:            r.consultationId            ?? 0,
            appointment_id:             r.appointmentId             ?? 0,
            patient_id:                 0,
            doctor_id:                  0,
            chief_complaint:            r.chiefComplaint            ?? null,
            history_of_present_illness: r.historyOfPresentIllness   ?? null,
            physical_examination:       r.physicalExamination       ?? null,
            clinical_notes:             r.clinicalNotes             ?? null,
            diagnosis:                  r.diagnosis                 ?? null,
            treatment_plan:             r.treatmentPlan             ?? null,
            follow_up_instructions:     r.followUpInstructions      ?? null,
            follow_up_required:         r.followUpRequired          ? 1 : 0,
            follow_up_date:             r.followUpDate              ? String(r.followUpDate) : null,
            duration_minutes:           r.durationMinutes           ?? null,
            status:                     (r.status ?? 'DRAFT') as Consultation['status'],
            started_at:                 r.startedAt                 ? String(r.startedAt) : null,
            completed_at:               r.completedAt               ? String(r.completedAt) : null,
            created_at:                 r.createdAt                 ? String(r.createdAt) : '',
            doctor_name:                doctor?.name                ?? (doctorSystemId || 'Unknown Doctor'),
            doctor_specialization:      doctor?.specialization      ?? null,
            doctor_avatar_url:          null,
            patient_name:               `${user.first_name} ${user.last_name}`.trim() || 'Unknown Patient',
            appointment_type:           null,
            appointment_status:         null,
          };
        });

        mapped.sort((a, b) => {
          const aDate = a.completed_at ?? a.created_at;
          const bDate = b.completed_at ?? b.created_at;
          return new Date(bDate).getTime() - new Date(aDate).getTime();
        });

        setConsultations(mapped);
        setError(null);
      })
      .catch(() => setError('Failed to load consultations.'))
      .finally(() => setLoading(false));
  }, [user?.system_id, tick]);

  return { consultations, loading, error, refetch: () => setTick(t => t + 1) };
}
