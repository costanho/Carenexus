import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export interface LabResult {
  result_id:             number;
  patient_id:            number;
  doctor_id:             number;
  consultation_id:       number | null;
  test_name:             string;
  test_type:             'BLOOD' | 'URINE' | 'STOOL' | 'CULTURE' | 'BIOPSY' | 'GENETIC' | 'SWAB' | 'OTHER';
  result_value:          string | null;
  unit:                  string | null;
  reference_range:       string | null;
  result_data:           Record<string, unknown> | null;
  status:                'PENDING' | 'COMPLETED' | 'CRITICAL' | 'REQUIRES_REVIEW' | 'CANCELLED';
  is_critical:           number;
  test_date:             string;
  reviewed_by:           number | null;
  reviewed_at:           string | null;
  file_url:              string | null;
  created_at:            string;
  doctor_name:           string;
  doctor_specialization: string | null;
  doctor_avatar_url:     string | null;
  reviewer_name:         string | null;
  patient_name:          string;
}

interface State {
  results:  LabResult[];
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
}

function parseResultData(raw: any): Record<string, unknown> | null {
  if (!raw) return null;
  if (typeof raw === 'object') return raw;
  try { return JSON.parse(raw); } catch { return null; }
}

export function useLabResults(): State {
  const { user } = useCurrentUser();
  const [results, setResults] = useState<LabResult[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError]     = useState<string | null>(null);
  const [tick, setTick]       = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);

    Promise.all([
      api.get<any[]>(`/api/lab-results/patient/${user.system_id}`),
      api.get<any[]>('/api/doctors/profile/list/active'),
    ])
      .then(([labRes, doctorsRes]) => {
        const doctorMap = new Map<string, { name: string; specialization: string | null }>();
        doctorsRes.data.forEach((d: any) => {
          const systemId = d.doctorSystemId ?? d.doctor_system_id;
          if (!systemId) return;
          const name = `Dr. ${d.firstName ?? d.first_name ?? ''} ${d.lastName ?? d.last_name ?? ''}`.trim();
          doctorMap.set(systemId, { name, specialization: d.specialization ?? null });
        });

        const mapped: LabResult[] = labRes.data.map((r: any) => {
          const doctorSystemId = r.doctorSystemId ?? '';
          const doctor = doctorMap.get(doctorSystemId);
          return {
            result_id:             r.resultId             ?? 0,
            patient_id:            0,
            doctor_id:             0,
            consultation_id:       r.consultationId       ?? null,
            test_name:             r.testName             ?? '',
            test_type:             (r.testType            ?? 'OTHER') as LabResult['test_type'],
            result_value:          r.resultValue          ?? null,
            unit:                  r.unit                 ?? null,
            reference_range:       r.referenceRange       ?? null,
            result_data:           parseResultData(r.resultData),
            status:                (r.status              ?? 'PENDING') as LabResult['status'],
            is_critical:           r.isCritical           ? 1 : 0,
            test_date:             r.testDate             ? String(r.testDate) : '',
            reviewed_by:           null,
            reviewed_at:           r.reviewedAt           ? String(r.reviewedAt) : null,
            file_url:              r.fileUrl              ?? null,
            created_at:            r.createdAt            ? String(r.createdAt) : '',
            doctor_name:           doctor?.name           ?? (doctorSystemId || 'Unknown Doctor'),
            doctor_specialization: doctor?.specialization ?? null,
            doctor_avatar_url:     null,
            reviewer_name:         r.reviewedBy           ?? null,
            patient_name:          `${user.first_name} ${user.last_name}`.trim() || 'Unknown Patient',
          };
        });

        mapped.sort((a, b) => new Date(b.test_date).getTime() - new Date(a.test_date).getTime());
        setResults(mapped);
        setError(null);
      })
      .catch(() => setError('Failed to load lab results.'))
      .finally(() => setLoading(false));
  }, [user?.system_id, tick]);

  return { results, loading, error, refetch: () => setTick(t => t + 1) };
}
