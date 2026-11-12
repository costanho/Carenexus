// Correction requests: a patient (or a caregiver who may manage the care plan) asks for a clinical record to be
// corrected; the authoring doctor or an administrator accepts or rejects the request with a note.
// Types follow the API contract be-corrections (camelCase, straight from the server).

export type CorrectionResourceType = 'MEDICAL_RECORD' | 'LAB_RESULT' | 'IMAGING_RESULT' | 'PRESCRIPTION' | 'CONSULTATION';
export type CorrectionStatus = 'OPEN' | 'ACCEPTED' | 'REJECTED';
export type CorrectionDecision = 'ACCEPTED' | 'REJECTED';
export type CorrectionRequesterRole = 'PATIENT' | 'CAREGIVER';

export const CORRECTION_RESOURCE_TYPES: readonly CorrectionResourceType[] =
  ['MEDICAL_RECORD', 'LAB_RESULT', 'IMAGING_RESULT', 'PRESCRIPTION', 'CONSULTATION'];
export const CORRECTION_STATUSES: readonly CorrectionStatus[] = ['OPEN', 'ACCEPTED', 'REJECTED'];

export interface CorrectionRequest {
  requestId:              number;
  patientSystemId:        string;
  patientName:            string;
  requestedBySystemId:    string;
  requestedByName:        string;
  requestedByRole:        CorrectionRequesterRole;
  resourceType:           CorrectionResourceType;
  /** Id of the record: recordId, resultId, imagingId, prescriptionId or consultationId. */
  resourceId:             number;
  requestedText:          string;
  status:                 CorrectionStatus;
  /** Doctor who must review it; null while it waits for an administrator to assign one. */
  assignedDoctorSystemId: string | null;
  assignedDoctorName:     string | null;
  resolverSystemId:       string | null;
  resolverName:           string | null;
  resolutionNote:         string | null;
  /** UTC instants (ISO-8601 with Z); shown in the viewer's locale, not in clinic time. */
  createdAt:              string;
  updatedAt:              string;
  resolvedAt:             string | null;
}

export interface CreateCorrectionRequestBody {
  patientSystemId: string;
  resourceType:    CorrectionResourceType;
  resourceId:      number;
  requestedText:   string;
}

export interface ResolveCorrectionRequestBody {
  decision: CorrectionDecision;
  /** Required for both decisions; shown to the requester. */
  note:     string;
}

/** One row of GET /api/admin/correction-requests/{id}/assignable-doctors. */
export interface AssignableDoctor {
  doctorSystemId:  string;
  doctorName:      string;
  specialization:  string | null;
  currentAssignee: boolean;
}
