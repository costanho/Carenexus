import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export type AccessLevel  = 'FULL_ACCESS' | 'VIEW_ONLY' | 'EDIT_ONLY' | 'CUSTOM';
export type HealthStatus = 'STABLE' | 'MONITOR' | 'CRITICAL' | string;
export type AccessStatus = 'ACTIVE' | 'EXPIRED' | 'REVOKED';

/** Video call session status (one session per VIDEO appointment). Defined here so the proxy hook has no call-web dependency. */
export type CallStatus =
  | 'PREPARING'
  | 'READY'
  | 'FAILED'
  | 'LIVE'
  | 'ENDED'
  | 'CANCELLED'
  | 'NEEDS_TIME_CONFIRMATION';

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
  // communication permissions: the flags are requests, effective only after the patient approves
  canJoinCalls:        boolean;
  messagingApprovedAt: string | null;
  callsApprovedAt:     string | null;
  messagingAllowed:    boolean;
  joinCallsAllowed:    boolean;
  pendingApproval:     boolean;
  accessStatus:        AccessStatus;
  // server-enriched patient fields
  patientName:          string | null;
  patientEmail:         string | null;
  patientHealthStatus:  HealthStatus | null;
  patientDateOfBirth:   string | null;
  patientGender:        string | null;
  patientBloodType:     string | null;
}

// ─── Shared helpers for caregiver screens ─────────────────────────────────────

export const NO_ACCESS_MESSAGE = "You don't have access to this information.";

/** True when the request was refused because the caregiver lacks permission (HTTP 403). */
export function isForbidden(err: unknown): boolean {
  return (err as any)?.response?.status === 403;
}

/** Maps an API error to a message safe to show the caregiver. 403 always maps to NO_ACCESS_MESSAGE. */
export function caregiverErrorMessage(err: unknown, fallback: string): string {
  if (isForbidden(err)) return NO_ACCESS_MESSAGE;
  return fallback;
}

/**
 * Maps an error from a caregiver SAVE (profile, health or access edit) to a message. Unlike reads, a refused
 * write carries a specific reason (e.g. "You can only reduce your own access..."), so the backend message is shown.
 */
export function caregiverSaveErrorMessage(err: unknown, fallback: string): string {
  const response = (err as any)?.response;
  if (!response) return 'Unable to reach the server. Please check your connection.';
  const message = response.data?.message;
  if (typeof message === 'string' && message.trim() && response.status < 500) return message;
  return isForbidden(err) ? NO_ACCESS_MESSAGE : fallback;
}

/** Parses a JSON-encoded string list (allergies, chronicConditions). Never throws. */
export function parseList(raw?: string | null): string[] {
  if (!raw) return [];
  try {
    const v = JSON.parse(raw);
    return Array.isArray(v) ? v.map(String) : [];
  } catch {
    return [];
  }
}

// ─── Dependents ───────────────────────────────────────────────────────────────

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
        setDependents(Array.isArray(data) ? data : []);
        setError(null);
      })
      .catch((err) => setError(caregiverErrorMessage(err, 'Failed to load loved ones.')))
      .finally(() => setLoading(false));
  }, [user?.system_id, tick]);

  return { dependents, loading, error, refetch: () => setTick(t => t + 1) };
}

// ─── Activity (appointments + consultations across all dependents) ────────────

export interface CaregiverAppointment {
  appointmentId:         number;
  patientSystemId:       string | null;
  patientName:           string | null;
  doctorSystemId:        string | null;
  scheduledAt:           string;
  durationMinutes:       number | null;
  type:                  string | null;
  status:                string;
  reasonForVisit:        string | null;
  /** Legacy field, always null. Use callJoinRoute. */
  videoConsultationLink: string | null;
  notes:                 string | null;
  callStatus:            CallStatus | null;
  callJoinRoute:         string | null;
  joinWindowOpensAt:     string | null;
  joinWindowClosesAt:    string | null;
}

export interface CaregiverConsultation {
  consultationId:  number;
  appointmentId:   number | null;
  patientSystemId: string | null;
  patientName:     string | null;
  doctorSystemId:  string | null;
  chiefComplaint:  string | null;
  diagnosis:       string | null;
  status:          string | null;
  startedAt:       string | null;
  completedAt:     string | null;
  createdAt:       string | null;
}

interface ActivityState {
  appointments:  CaregiverAppointment[];
  consultations: CaregiverConsultation[];
  loading:       boolean;
  error:         string | null;
  refetch:       () => void;
}

export function useCaregiverActivity(): ActivityState {
  const { user } = useCurrentUser();
  const [appointments,  setAppointments]  = useState<CaregiverAppointment[]>([]);
  const [consultations, setConsultations] = useState<CaregiverConsultation[]>([]);
  const [loading,       setLoading]       = useState(true);
  const [error,         setError]         = useState<string | null>(null);
  const [tick,          setTick]          = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);

    api.get<{ appointments?: CaregiverAppointment[]; consultations?: CaregiverConsultation[] }>(
      '/api/caregiver/dependents/activity',
    )
      .then(({ data }) => {
        setAppointments(data?.appointments ?? []);
        setConsultations(data?.consultations ?? []);
        setError(null);
      })
      .catch((err) => setError(caregiverErrorMessage(err, 'Failed to load appointments and activity.')))
      .finally(() => setLoading(false));
  }, [user?.system_id, tick]);

  return { appointments, consultations, loading, error, refetch: () => setTick(t => t + 1) };
}
