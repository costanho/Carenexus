import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export interface IcdCode {
  code: string;
  description: string;
}

export type RecordType = 'CONSULT' | 'LAB' | 'PRESCRIPTION' | 'REFERRAL' | 'NOTE' | 'IMAGING';

export interface DoctorMedicalRecord {
  record_id:             number;
  patient_system_id:     string;
  /** @deprecated not exposed by the API; always 0. Use patient_system_id. */
  patient_id:            number;
  /** @deprecated not exposed by the API; always 0. */
  doctor_id:             number;
  consultation_id:       number | null;
  record_type:           RecordType;
  title:                 string;
  description:           string | null;
  treatment_plan:        string | null;
  observations:          string | null;
  icd_codes:             IcdCode[];
  created_at:            string;
  updated_at:            string;
  // enriched client-side from the doctor's own profile / patient list
  doctor_name:           string;
  doctor_specialization: string | null;
  doctor_avatar_url:     string | null;
  patient_name:          string;
  // enriched client-side
  patient_avatar_url:    string | null;
}

interface State {
  records:  DoctorMedicalRecord[];
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
}

/** icdCodes arrives as a JSON string: an array of {code, description}, of codes, or a comma list. */
function parseIcdCodes(raw: unknown): IcdCode[] {
  if (!raw) return [];
  let val: unknown = raw;
  if (typeof raw === 'string') {
    const t = raw.trim();
    if (!t) return [];
    try { val = JSON.parse(t); } catch { val = t.split(',').map(x => x.trim()).filter(Boolean); }
  }
  if (!Array.isArray(val)) return [];
  return val
    .map((c: any): IcdCode | null => {
      if (typeof c === 'string') return { code: c, description: '' };
      if (c && typeof c === 'object' && c.code) return { code: String(c.code), description: String(c.description ?? '') };
      return null;
    })
    .filter((c): c is IcdCode => c !== null);
}

export function useDoctorMedicalRecords(): State {
  const { user } = useCurrentUser();
  const [records,  setRecords]  = useState<DoctorMedicalRecord[]>([]);
  const [loading,  setLoading]  = useState(true);
  const [error,    setError]    = useState<string | null>(null);
  const [tick,     setTick]     = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);

    Promise.all([
        api.get<any[]>(`/api/medical-records/doctor/${user.system_id}`),
        api.get<any[]>('/api/doctors/patients'),
      ])
      .then(([recordsRes, patientsRes]) => {
        // patientSystemId → full name, from the doctor's own patient list
        const nameMap = new Map<string, string>();
        (patientsRes.data ?? []).forEach((p: any) => {
          if (!p.patientSystemId) return;
          const name = `${p.firstName ?? ''} ${p.lastName ?? ''}`.trim();
          if (name) nameMap.set(p.patientSystemId, name);
        });
        const doctorName = user.first_name ? `Dr. ${user.first_name} ${user.last_name}`.trim() : 'Unknown Doctor';

        const merged: DoctorMedicalRecord[] = (recordsRes.data ?? [])
          .map((r: any) => ({
            record_id:             r.recordId ?? 0,
            patient_system_id:     r.patientSystemId ?? '',
            patient_id:            0,
            doctor_id:             0,
            consultation_id:       r.consultationId ?? null,
            record_type:           r.recordType as RecordType,
            title:                 r.title ?? '',
            description:           r.description ?? null,
            treatment_plan:        r.treatmentPlan ?? null,
            observations:          r.observations ?? null,
            icd_codes:             parseIcdCodes(r.icdCodes),
            created_at:            r.createdAt ? String(r.createdAt) : '',
            updated_at:            r.updatedAt ? String(r.updatedAt) : '',
            doctor_name:           doctorName,
            doctor_specialization: null,
            doctor_avatar_url:     null,
            patient_name:          nameMap.get(r.patientSystemId) ?? 'Unknown Patient',
            patient_avatar_url:    null,
          }));

        // already sorted desc by server; re-sort just in case
        merged.sort((a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime());

        setRecords(merged);
        setError(null);
      })
      .catch(() => setError('Failed to load medical records.'))
      .finally(() => setLoading(false));
  }, [user?.system_id, tick]);

  return { records, loading, error, refetch: () => setTick(t => t + 1) };
}
