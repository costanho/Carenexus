import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export type AccessLevel  = 'FULL_ACCESS' | 'VIEW_ONLY' | 'EDIT_ONLY' | 'CUSTOM';
export type HealthStatus = 'STABLE' | 'MONITOR' | 'CRITICAL' | string;

export interface CaregiverDependent {
  access_id:                 number;
  caregiver_id:              number;
  patient_id:                number;
  relationship:              string;
  access_level:              AccessLevel;
  can_view_records:          number;
  can_view_appointments:     number;
  can_view_medications:      number;
  can_view_lab_results:      number;
  can_view_imaging:          number;
  can_message_care_team:     number;
  can_view_consultations:    number;
  can_schedule_appointments: number;
  can_manage_care_plan:      number;
  authorized_by:             number | null;
  authorized_date:           string;
  expires_at:                string | null;
  is_active:                 number;
  revoked_at:                string | null;
  // server-enriched
  patient_name:          string | null;
  patient_email:         string | null;
  patient_avatar:        string | null;
  patient_health_status: HealthStatus | null;
  patient_date_of_birth: string | null;
  patient_gender:        string | null;
  patient_blood_type:    string | null;
  caregiver_name:        string | null;
  caregiver_email:       string | null;
  caregiver_avatar:      string | null;
  authorized_by_name:    string | null;
}

interface State {
  dependents: CaregiverDependent[];
  loading:    boolean;
  error:      string | null;
  refetch:    () => void;
}

export function useCaregiverDependents(): State {
  const { user } = useCurrentUser();
  const [dependents, setDependents] = useState<CaregiverDependent[]>([]);
  const [loading,    setLoading]    = useState(true);
  const [error,      setError]      = useState<string | null>(null);
  const [tick,       setTick]       = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);

    // Step 1: resolve caregiver_id for the logged-in user
    api.get<{ caregiver_id: number }>(`/api/caregivers/user/${user.user_id}`)
      .then(({ data: caregiver }) => {
        // Step 2: fetch all active dependent_access rows for this caregiver_id
        // Each row is server-enriched with patient_name, patient_avatar,
        // patient_health_status, patient_gender, patient_date_of_birth, etc.
        return api.get<CaregiverDependent[]>(
          `/api/dependent-access?caregiver_id=${caregiver.caregiver_id}&is_active=1`
        );
      })
      .then(({ data }) => {
        setDependents(data);
        setError(null);
      })
      .catch(() => setError('Failed to load loved ones.'))
      .finally(() => setLoading(false));
  }, [user?.user_id, tick]);

  return { dependents, loading, error, refetch: () => setTick(t => t + 1) };
}
