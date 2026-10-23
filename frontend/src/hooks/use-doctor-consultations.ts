import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export interface DoctorConsultation {
  consultation_id:            number;
  appointment_id:             number;
  patient_id:                 number;
  doctor_id:                  number;
  chief_complaint:            string | null;
  history_of_present_illness: string | null;
  physical_examination:       string | null;
  clinical_notes:             string | null;
  diagnosis:                  string | null;
  treatment_plan:             string | null;
  follow_up_instructions:     string | null;
  follow_up_required:         number;
  follow_up_date:             string | null;
  duration_minutes:           number | null;
  status:                     'DRAFT' | 'IN_PROGRESS' | 'COMPLETED';
  started_at:                 string | null;
  completed_at:               string | null;
  created_at:                 string;
  doctor_name:                string;
  doctor_specialization:      string | null;
  doctor_avatar_url:          string | null;
  patient_name:               string;
  appointment_type:           'IN_PERSON' | 'VIDEO' | 'PHONE' | null;
  appointment_status:         string | null;
}

interface State {
  consultations: DoctorConsultation[];
  loading:       boolean;
  error:         string | null;
  refetch:       () => void;
}

export function useDoctorConsultations(): State {
  const { user } = useCurrentUser();
  const [consultations, setConsultations] = useState<DoctorConsultation[]>([]);
  const [loading,       setLoading]       = useState(true);
  const [error,         setError]         = useState<string | null>(null);
  const [tick,          setTick]          = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);

    Promise.all([
      api.get<any[]>(`/api/consultations/doctor/${user.system_id}`),
      api.get<any[]>('/api/doctors/patients'),
    ])
      .then(([consultRes, patientsRes]) => {
        // Build patientSystemId → full name map from the doctor's patient list
        const nameMap = new Map<string, string>();
        patientsRes.data.forEach((p: any) => {
          const systemId = p.patientSystemId ?? p.patient_system_id;
          if (!systemId) return;
          const combined = `${p.firstName ?? p.first_name ?? ''} ${p.lastName ?? p.last_name ?? ''}`.trim();
          const name = p.fullName ?? p.full_name ?? combined ?? 'Unknown Patient';
          nameMap.set(systemId, name);
        });

        const mapped: DoctorConsultation[] = consultRes.data.map((r: any) => ({
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
          status:                     (r.status ?? 'DRAFT') as DoctorConsultation['status'],
          started_at:                 r.startedAt                 ? String(r.startedAt) : null,
          completed_at:               r.completedAt               ? String(r.completedAt) : null,
          created_at:                 r.createdAt                 ? String(r.createdAt) : '',
          doctor_name:                user.first_name ? `Dr. ${user.first_name} ${user.last_name}`.trim() : 'Unknown Doctor',
          doctor_specialization:      null,
          doctor_avatar_url:          null,
          patient_name:               nameMap.get(r.patientSystemId) ?? 'Unknown Patient',
          appointment_type:           null,
          appointment_status:         null,
        }));

        mapped.sort((a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime());
        setConsultations(mapped);
        setError(null);
      })
      .catch(() => setError('Failed to load consultations.'))
      .finally(() => setLoading(false));
  }, [user?.system_id, tick]);

  return { consultations, loading, error, refetch: () => setTick(t => t + 1) };
}
