import { useCallback } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { type ActionResult, useAction } from './use-action';
import type { DoctorPatientDto, HealthStatus } from './use-doctor-patients';
import { useResource } from './use-resource';

// One patient of the signed-in doctor: the detail record, the emergency contacts and the health-status change
// (api/be-doctor-directory-patients.md 4.4 and 4.5, api/be-profile-settings.md 5.3).

// ─── API shapes ───────────────────────────────────────────────────────────────

/** `EmergencyContactResponse`. */
export interface EmergencyContactDto {
  contactId:       number;
  patientSystemId: string;
  name:            string;
  relationship:    string | null;
  phone:           string;
  email:           string | null;
  isPrimary:       boolean;
  /** Informational only: this person also cares for the patient. It grants nothing. */
  isCaregiver:     boolean;
  createdAt:       string;
}

/** Longest health-status note the server stores (after trimming). */
export const HEALTH_STATUS_NOTE_MAX = 500;

/** Body of PATCH /api/doctors/patients/{id}/health-status. `note` is sent only when one was typed. */
export interface UpdateHealthStatusBody {
  healthStatus: HealthStatus;
  note?:        string;
}

/** `DoctorPatientHealthStatusResponse`: the state after the call. */
export interface HealthStatusResult {
  patientSystemId:      string;
  healthStatus:         HealthStatus;
  previousHealthStatus: HealthStatus;
  /** The status itself changed in this call (false when only the note was replaced, or nothing happened). */
  changed:              boolean;
  healthStatusNote:              string | null;
  healthStatusUpdatedAt:         string | null;
  healthStatusUpdatedBySystemId: string | null;
  healthStatusUpdatedByName:     string | null;
}

// ─── Requests ─────────────────────────────────────────────────────────────────

/** GET /api/doctors/patients/{id}/detail. 403 when the doctor is not related to the patient (or the id is unknown). */
export async function getDoctorPatientDetail(patientSystemId: string): Promise<DoctorPatientDto> {
  const { data } = await api.get<DoctorPatientDto>(
    `/api/doctors/patients/${encodeURIComponent(patientSystemId)}/detail`,
  );
  return data;
}

/** GET /api/profile/emergency-contacts/patient/{id}: the patient's emergency contacts (`[]` when none). */
export async function getPatientEmergencyContacts(patientSystemId: string): Promise<EmergencyContactDto[]> {
  const { data } = await api.get<EmergencyContactDto[]>(
    `/api/profile/emergency-contacts/patient/${encodeURIComponent(patientSystemId)}`,
  );
  return Array.isArray(data) ? data : [];
}

/**
 * PATCH /api/doctors/patients/{id}/health-status. Every change between the three values is allowed; a change
 * into CRITICAL notifies the patient and the caregivers who may see their records, so ask for confirmation first.
 */
export async function updatePatientHealthStatus(
  patientSystemId: string,
  body: UpdateHealthStatusBody,
): Promise<HealthStatusResult> {
  const note = (body.note ?? '').trim();
  const payload: UpdateHealthStatusBody = note ? { healthStatus: body.healthStatus, note } : { healthStatus: body.healthStatus };
  const { data } = await api.patch<HealthStatusResult>(
    `/api/doctors/patients/${encodeURIComponent(patientSystemId)}/health-status`,
    payload,
  );
  return data;
}

// ─── Hooks ────────────────────────────────────────────────────────────────────

export interface DoctorPatientDetailState {
  /** The patient; null while loading, when nothing is selected and after a failed first load. */
  patient:     DoctorPatientDto | null;
  loading:     boolean;
  refreshing:  boolean;
  error:       string | null;
  /** 403 = the doctor is not (or no longer) related to this patient. */
  errorStatus: number | null;
  refetch:     () => void;
  /** Applies the answer of a health-status change to the loaded patient without another request. */
  applyHealthStatus: (result: HealthStatusResult) => void;
}

/** The detail of one of the doctor's patients. Pass null (nothing selected) to request nothing. */
export function useDoctorPatientDetail(patientSystemId: string | null | undefined): DoctorPatientDetailState {
  const id = patientSystemId ?? '';
  const { data, loading, refreshing, error, errorStatus, refetch, setData } = useResource(
    () => getDoctorPatientDetail(id),
    [id],
    { enabled: !!id, errorMessage: 'Failed to load this patient.' },
  );

  const applyHealthStatus = useCallback((result: HealthStatusResult) => {
    setData(prev => (prev && prev.patientSystemId === result.patientSystemId
      ? {
          ...prev,
          healthStatus:                  result.healthStatus,
          healthStatusNote:              result.healthStatusNote,
          healthStatusUpdatedAt:         result.healthStatusUpdatedAt,
          healthStatusUpdatedBySystemId: result.healthStatusUpdatedBySystemId,
          healthStatusUpdatedByName:     result.healthStatusUpdatedByName,
        }
      : prev));
  }, [setData]);

  return { patient: data, loading, refreshing, error, errorStatus, refetch, applyHealthStatus };
}

export interface PatientEmergencyContactsState {
  /** In server order: the primary contact first, then oldest first. */
  contacts:    EmergencyContactDto[];
  loading:     boolean;
  error:       string | null;
  errorStatus: number | null;
  refetch:     () => void;
}

/** A patient's emergency contacts, readable by a doctor related to the patient. */
export function usePatientEmergencyContacts(patientSystemId: string | null | undefined): PatientEmergencyContactsState {
  const id = patientSystemId ?? '';
  const { data, loading, error, errorStatus, refetch } = useResource(
    () => getPatientEmergencyContacts(id),
    [id],
    { enabled: !!id, errorMessage: 'Failed to load emergency contacts.' },
  );
  return { contacts: data ?? [], loading, error, errorStatus, refetch };
}

export interface UpdateHealthStatusState {
  /** Sends the change. Never rejects: check `result.ok`. A second call while one is running is ignored. */
  save:    (body: UpdateHealthStatusBody) => Promise<ActionResult<HealthStatusResult>>;
  pending: boolean;
  error:   string | null;
  reset:   () => void;
}

/** The health-status change of one patient, with a pending flag that blocks a double submit. */
export function useUpdateHealthStatus(patientSystemId: string): UpdateHealthStatusState {
  const action = useAction(
    (body: UpdateHealthStatusBody) => updatePatientHealthStatus(patientSystemId, body),
    { errorMessage: 'The health status could not be updated.' },
  );
  return { save: action.run, pending: action.pending, error: action.error, reset: action.reset };
}
