import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export interface Prescription {
  prescription_id:      number;
  patient_id:           number;
  doctor_id:            number;
  consultation_id:      number | null;
  medication_name:      string;
  generic_name:         string | null;
  dosage:               string;
  frequency:            string;
  route:                'ORAL' | 'TOPICAL' | 'INJECTION' | 'INHALED' | 'SUBLINGUAL' | 'RECTAL' | 'IV';
  quantity:             string | null;
  refills_allowed:      number;
  special_instructions: string | null;
  status:               'ACTIVE' | 'COMPLETED' | 'CANCELLED' | 'EXPIRED' | 'ON_HOLD';
  prescribed_date:      string;
  expiry_date:          string | null;
  created_at:           string;
  doctor_name:          string;
  doctor_specialization: string | null;
  doctor_avatar_url:    string | null;
  patient_name:         string;
}

interface State {
  prescriptions: Prescription[];
  loading:       boolean;
  error:         string | null;
  refetch:       () => void;
}

export function usePrescriptions(): State {
  const { user } = useCurrentUser();
  const [prescriptions, setPrescriptions] = useState<Prescription[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError]     = useState<string | null>(null);
  const [tick, setTick]       = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);

    Promise.all([
      api.get<any[]>(`/api/prescriptions/patient/${user.system_id}`),
      api.get<any[]>('/api/doctors/profile/list/active'),
    ])
      .then(([rxRes, doctorsRes]) => {
        const doctorMap = new Map<string, { name: string; specialization: string | null }>();
        doctorsRes.data.forEach((d: any) => {
          const systemId = d.doctorSystemId ?? d.doctor_system_id;
          if (!systemId) return;
          const name = `Dr. ${d.firstName ?? d.first_name ?? ''} ${d.lastName ?? d.last_name ?? ''}`.trim();
          doctorMap.set(systemId, { name, specialization: d.specialization ?? null });
        });

        const mapped: Prescription[] = rxRes.data.map((r: any) => {
          const doctorSystemId = r.doctorSystemId ?? '';
          const doctor = doctorMap.get(doctorSystemId);
          return {
            prescription_id:      r.prescriptionId      ?? 0,
            patient_id:           0,
            doctor_id:            0,
            consultation_id:      r.consultationId      ?? null,
            medication_name:      r.medicationName      ?? '',
            generic_name:         r.genericName         ?? null,
            dosage:               r.dosage              ?? '',
            frequency:            r.frequency           ?? '',
            route:                (r.route              ?? 'ORAL') as Prescription['route'],
            quantity:             r.quantity            ?? null,
            refills_allowed:      r.refillsAllowed      ?? 0,
            special_instructions: r.specialInstructions ?? null,
            status:               (r.status             ?? 'ACTIVE') as Prescription['status'],
            prescribed_date:      r.prescribedDate      ? String(r.prescribedDate) : '',
            expiry_date:          r.expiryDate          ? String(r.expiryDate) : null,
            created_at:           r.createdAt           ? String(r.createdAt) : '',
            doctor_name:          doctor?.name          ?? (doctorSystemId || 'Unknown Doctor'),
            doctor_specialization:doctor?.specialization ?? null,
            doctor_avatar_url:    null,
            patient_name:         `${user.first_name} ${user.last_name}`.trim() || 'Unknown Patient',
          };
        });

        mapped.sort((a, b) => new Date(b.prescribed_date).getTime() - new Date(a.prescribed_date).getTime());
        setPrescriptions(mapped);
        setError(null);
      })
      .catch(() => setError('Failed to load prescriptions.'))
      .finally(() => setLoading(false));
  }, [user?.system_id, tick]);

  return { prescriptions, loading, error, refetch: () => setTick(t => t + 1) };
}
