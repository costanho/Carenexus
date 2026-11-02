import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export type AccessLevel  = 'FULL_ACCESS' | 'VIEW_ONLY' | 'EDIT_ONLY' | 'CUSTOM';
export type HealthStatus = 'STABLE' | 'MONITOR' | 'CRITICAL' | string;

export interface CaregiverDependent {
  accessId:                 number;
  caregiverSystemId:        string;
  patientSystemId:          string;
  relationship:             string;
  accessLevel:              AccessLevel;
  canViewRecords:           boolean;
  canViewAppointments:      boolean;
  canViewMedications:       boolean;
  canViewLabResults:        boolean;
  canViewImaging:           boolean;
  canMessageCareTeam:       boolean;
  canViewConsultations:     boolean;
  canScheduleAppointments:  boolean;
  canManageCarePlan:        boolean;
  authorizedBySystemId:     string | null;
  authorizedDate:           string;
  expiresAt:                string | null;
  isActive:                 boolean;
  revokedAt:                string | null;
  // server-enriched patient fields
  patientName:          string | null;
  patientEmail:         string | null;
  patientHealthStatus:  HealthStatus | null;
  patientDateOfBirth:   string | null;
  patientGender:        string | null;
  patientBloodType:     string | null;
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

    api.get<CaregiverDependent[]>('/api/caregiver/dependents')
      .then(({ data }) => {
        setDependents(data);
        setError(null);
      })
      .catch(() => setError('Failed to load loved ones.'))
      .finally(() => setLoading(false));
  }, [user?.system_id, tick]);

  return { dependents, loading, error, refetch: () => setTick(t => t + 1) };
}
