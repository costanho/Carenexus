import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export interface MedicalRecord {
  record_id:             number;
  patient_id:            number;
  doctor_id:             number;
  consultation_id:       number | null;
  record_type:           'CONSULT' | 'LAB' | 'IMAGING' | 'PRESCRIPTION' | 'REFERRAL' | 'DISCHARGE' | 'NOTE' | 'OPERATION';
  title:                 string;
  description:           string | null;
  treatment_plan:        string | null;
  observations:          string | null;
  icd_codes:             Array<{ code: string; description: string }> | null;
  is_deleted:            number;
  deleted_at:            string | null;
  created_at:            string;
  updated_at:            string;
  doctor_name:           string;
  doctor_specialization: string | null;
  doctor_avatar_url:     string | null;
  patient_name:          string;
}

interface State {
  records:  MedicalRecord[];
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
}

function parseIcdCodes(raw: any): Array<{ code: string; description: string }> | null {
  if (!raw) return null;
  if (Array.isArray(raw)) return raw;
  try { return JSON.parse(raw); } catch { return null; }
}

export function useMedicalRecords(): State {
  const { user } = useCurrentUser();
  const [records, setRecords] = useState<MedicalRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError]     = useState<string | null>(null);
  const [tick, setTick]       = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);

    Promise.all([
      api.get<any[]>(`/api/medical-records/patient/${user.system_id}`),
      api.get<any[]>('/api/doctors/profile/list/active'),
    ])
      .then(([recordsRes, doctorsRes]) => {
        const doctorMap = new Map<string, { name: string; specialization: string | null }>();
        doctorsRes.data.forEach((d: any) => {
          const systemId = d.doctorSystemId ?? d.doctor_system_id;
          if (!systemId) return;
          const name = `Dr. ${d.firstName ?? d.first_name ?? ''} ${d.lastName ?? d.last_name ?? ''}`.trim();
          doctorMap.set(systemId, { name, specialization: d.specialization ?? null });
        });

        const mapped: MedicalRecord[] = recordsRes.data.map((r: any) => {
          const doctorSystemId = r.doctorSystemId ?? '';
          const doctor = doctorMap.get(doctorSystemId);
          return {
            record_id:             r.recordId             ?? 0,
            patient_id:            0,
            doctor_id:             0,
            consultation_id:       r.consultationId       ?? null,
            record_type:           (r.recordType          ?? 'NOTE') as MedicalRecord['record_type'],
            title:                 r.title                ?? '',
            description:           r.description          ?? null,
            treatment_plan:        r.treatmentPlan        ?? null,
            observations:          r.observations         ?? null,
            icd_codes:             parseIcdCodes(r.icdCodes),
            is_deleted:            r.isDeleted            ? 1 : 0,
            deleted_at:            r.deletedAt            ? String(r.deletedAt) : null,
            created_at:            r.createdAt            ? String(r.createdAt) : '',
            updated_at:            r.updatedAt            ? String(r.updatedAt) : '',
            doctor_name:           doctor?.name           ?? (doctorSystemId || 'Unknown Doctor'),
            doctor_specialization: doctor?.specialization ?? null,
            doctor_avatar_url:     null,
            patient_name:          `${user.first_name} ${user.last_name}`.trim() || 'Unknown Patient',
          };
        });

        mapped.sort((a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime());
        setRecords(mapped);
        setError(null);
      })
      .catch(() => setError('Failed to load medical records.'))
      .finally(() => setLoading(false));
  }, [user?.system_id, tick]);

  return { records, loading, error, refetch: () => setTick(t => t + 1) };
}
