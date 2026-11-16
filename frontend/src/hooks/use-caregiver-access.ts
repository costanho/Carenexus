import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { apiErrorMessage } from '@/hooks/use-appointments';
import { useCurrentUser } from './use-current-user';
import type { AccessLevel, CaregiverDependent } from './use-caregiver-dependents';

/**
 * A caregiver grant as the patient sees it (GET /api/patients/me/caregiver-access). Same backend DTO as the
 * caregiver's view, plus the caregiver's name and email.
 *
 * canMessageCareTeam / canJoinCalls are requests; messagingAllowed / joinCallsAllowed are what is in effect now
 * (the patient approved and the grant is active). pendingApproval: a request is waiting for the patient.
 */
export interface CaregiverAccess extends CaregiverDependent {
  caregiverName:  string | null;
  caregiverEmail: string | null;
}

export interface CaregiverPermissionFlags {
  canViewRecords?:          boolean;
  canViewAppointments?:     boolean;
  canViewMedications?:      boolean;
  canViewLabResults?:       boolean;
  canViewImaging?:          boolean;
  canViewConsultations?:    boolean;
  canScheduleAppointments?: boolean;
  canManageCarePlan?:       boolean;
  /** Turning this on (as the patient) approves messaging. */
  canMessageCareTeam?:      boolean;
  /** Turning this on (as the patient) approves joining video calls. */
  canJoinCalls?:            boolean;
}

export interface GrantCaregiverAccessBody extends CaregiverPermissionFlags {
  caregiverEmail: string;
  relationship:   string;
  accessLevel?:   AccessLevel;
  /** "YYYY-MM-DD" (end of that day) or null for no expiry. */
  expiresAt?:     string | null;
}

export interface UpdateCaregiverAccessBody extends CaregiverPermissionFlags {
  accessLevel?: AccessLevel;
  /** "YYYY-MM-DD"; "" removes the expiry; omit to leave unchanged. */
  expiresAt?:   string;
}

const BASE = '/api/patients/me/caregiver-access';

export async function grantCaregiverAccess(body: GrantCaregiverAccessBody): Promise<CaregiverAccess> {
  const { data } = await api.post<CaregiverAccess>(BASE, body);
  return data;
}

export async function updateCaregiverAccess(accessId: number, body: UpdateCaregiverAccessBody): Promise<CaregiverAccess> {
  const { data } = await api.patch<CaregiverAccess>(`${BASE}/${accessId}`, body);
  return data;
}

export async function revokeCaregiverAccess(accessId: number): Promise<void> {
  await api.delete(`${BASE}/${accessId}`);
}

/** Error text for this screen: the backend message for 4xx, a connection hint when there is no response. */
export function caregiverAccessErrorMessage(err: unknown, fallback: string): string {
  const response = (err as any)?.response;
  if (!response) return 'Unable to reach the server. Please check your connection.';
  if (response.status >= 500) return fallback;
  return apiErrorMessage(err, fallback);
}

interface State {
  grants:  CaregiverAccess[];
  loading: boolean;
  error:   string | null;
  refetch: () => void;
}

/** The signed-in patient's caregiver grants (active and expired, not revoked), newest first. */
export function useCaregiverAccess(): State {
  const { user } = useCurrentUser();
  const [grants,  setGrants]  = useState<CaregiverAccess[]>([]);
  const [loading, setLoading] = useState(true);
  const [error,   setError]   = useState<string | null>(null);
  const [tick,    setTick]    = useState(0);

  useEffect(() => {
    if (!user) return;
    let cancelled = false;
    setLoading(true);
    api.get<CaregiverAccess[]>(BASE)
      .then(({ data }) => {
        if (cancelled) return;
        setGrants(Array.isArray(data) ? data : []);
        setError(null);
      })
      .catch((err) => {
        if (!cancelled) setError(caregiverAccessErrorMessage(err, 'Failed to load your caregivers.'));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [user?.system_id, tick]);

  const refetch = useCallback(() => setTick(t => t + 1), []);
  return { grants, loading, error, refetch };
}
