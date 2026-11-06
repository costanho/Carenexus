import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export type LabTestType   = 'BLOOD' | 'SWAB' | 'URINE' | 'CULTURE' | string;
export type LabStatus     = 'COMPLETED' | 'REQUIRES_REVIEW';
export type LabFlag       = 'NORMAL' | 'LOW' | 'HIGH';

export interface LabSubTest {
  value:          number | string;
  unit:           string;
  reference:      string;
  flag:           LabFlag;
  interpretation?: string;
}

export interface DoctorLabResult {
  result_id:       number;
  patient_system_id: string;
  /** @deprecated not exposed by the API; always 0. Use patient_system_id. */
  patient_id:      number;
  /** @deprecated not exposed by the API; always 0. */
  doctor_id:       number;
  consultation_id: number | null;
  test_name:       string;
  test_type:       LabTestType;
  result_value:    string | null;
  unit:            string | null;
  reference_range: string | null;
  result_data:     Record<string, LabSubTest> | null;
  status:          LabStatus;
  is_critical:     number;
  test_date:       string;
  reviewed_by:     number | null;
  reviewed_at:     string | null;
  file_url:        string | null;
  created_at:      string;
  // enriched client-side from the doctor's own profile / patient list
  doctor_name:           string;
  doctor_specialization: string | null;
  doctor_avatar_url:     string | null;
  patient_name:          string;
  // client-enriched
  patient_avatar_url:    string | null;
}

interface State {
  results:  DoctorLabResult[];
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
}

const FLAGS: LabFlag[] = ['NORMAL', 'LOW', 'HIGH'];

/** resultData arrives as a JSON string; keep only well-formed sub-test objects. */
function parseResultData(raw: unknown): Record<string, LabSubTest> | null {
  if (!raw) return null;
  let obj: unknown = raw;
  if (typeof raw === 'string') {
    try { obj = JSON.parse(raw); } catch { return null; }
  }
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return null;
  const out: Record<string, LabSubTest> = {};
  Object.entries(obj as Record<string, any>).forEach(([key, v]) => {
    if (!v || typeof v !== 'object') return;
    const flag = String(v.flag ?? 'NORMAL').toUpperCase() as LabFlag;
    out[key] = {
      value:          v.value ?? '',
      unit:           v.unit ?? '',
      reference:      v.reference ?? v.referenceRange ?? '',
      flag:           FLAGS.includes(flag) ? flag : 'NORMAL',
      interpretation: v.interpretation ?? undefined,
    };
  });
  return Object.keys(out).length ? out : null;
}

export function useDoctorLabResults(): State {
  const { user } = useCurrentUser();
  const [results,  setResults]  = useState<DoctorLabResult[]>([]);
  const [loading,  setLoading]  = useState(true);
  const [error,    setError]    = useState<string | null>(null);
  const [tick,     setTick]     = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);

    Promise.all([
        api.get<any[]>(`/api/lab-results/doctor/${user.system_id}`),
        api.get<any[]>('/api/doctors/patients'),
      ])
      .then(([labRes, patientsRes]) => {
        // patientSystemId → full name, from the doctor's own patient list
        const nameMap = new Map<string, string>();
        (patientsRes.data ?? []).forEach((p: any) => {
          if (!p.patientSystemId) return;
          const name = `${p.firstName ?? ''} ${p.lastName ?? ''}`.trim();
          if (name) nameMap.set(p.patientSystemId, name);
        });
        const doctorName = user.first_name ? `Dr. ${user.first_name} ${user.last_name}`.trim() : 'Unknown Doctor';

        const merged: DoctorLabResult[] = (labRes.data ?? [])
          .map((r: any) => ({
            result_id:       r.resultId ?? 0,
            patient_system_id: r.patientSystemId ?? '',
            patient_id:      0,
            doctor_id:       0,
            consultation_id: r.consultationId ?? null,
            test_name:       r.testName       ?? '',
            test_type:       r.testType       ?? 'BLOOD',
            result_value:    r.resultValue    ?? null,
            unit:            r.unit           ?? null,
            reference_range: r.referenceRange ?? null,
            result_data:     parseResultData(r.resultData),
            status:          r.status as LabStatus,
            is_critical:     r.isCritical ? 1 : 0,
            test_date:       r.testDate   ? String(r.testDate)   : '',
            reviewed_by:     null,
            reviewed_at:     r.reviewedAt ? String(r.reviewedAt) : null,
            file_url:        r.fileUrl    ?? null,
            created_at:      r.createdAt  ? String(r.createdAt)  : '',
            doctor_name:           doctorName,
            doctor_specialization: null,
            doctor_avatar_url:     null,
            patient_name:          nameMap.get(r.patientSystemId) ?? 'Unknown Patient',
            patient_avatar_url:    null,
          }));

        merged.sort((a, b) => new Date(b.test_date).getTime() - new Date(a.test_date).getTime());

        setResults(merged);
        setError(null);
      })
      .catch(() => setError('Failed to load lab results.'))
      .finally(() => setLoading(false));
  }, [user?.system_id, tick]);

  return { results, loading, error, refetch: () => setTick(t => t + 1) };
}
