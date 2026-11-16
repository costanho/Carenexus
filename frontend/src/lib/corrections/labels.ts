import type { AppRole } from '@/lib/auth/auth.types';
import type { SectionParamsInput } from '@/lib/nav/sections';
import type { CorrectionRequest, CorrectionResourceType, CorrectionStatus } from './types';

// Words and links for correction requests. The response carries no clinical content of the record (no title or
// value), so a row is labelled from the kind of record and linked to the section that shows it.

const RESOURCE_LABEL: Record<CorrectionResourceType, string> = {
  MEDICAL_RECORD: 'Medical record',
  LAB_RESULT:     'Lab result',
  IMAGING_RESULT: 'Imaging result',
  PRESCRIPTION:   'Prescription',
  CONSULTATION:   'Consultation',
};

/** "Lab result", "Prescription", ... An unknown type reads "Record". */
export function correctionResourceLabel(type: CorrectionResourceType | string | null | undefined): string {
  return RESOURCE_LABEL[type as CorrectionResourceType] ?? 'Record';
}

const RESOURCE_PHRASE: Record<CorrectionResourceType, string> = {
  MEDICAL_RECORD: 'a medical record',
  LAB_RESULT:     'a lab result',
  IMAGING_RESULT: 'an imaging result',
  PRESCRIPTION:   'a prescription',
  CONSULTATION:   'a consultation',
};

/** The record kind inside a sentence: "a lab result", "an imaging result". An unknown type reads "a record". */
export function correctionResourcePhrase(type: CorrectionResourceType | string | null | undefined): string {
  return RESOURCE_PHRASE[type as CorrectionResourceType] ?? 'a record';
}

const STATUS_LABEL: Record<CorrectionStatus, string> = {
  OPEN:     'Open',
  ACCEPTED: 'Accepted',
  REJECTED: 'Rejected',
};

export function correctionStatusLabel(status: CorrectionStatus | string | null | undefined): string {
  return STATUS_LABEL[status as CorrectionStatus] ?? 'Unknown';
}

/** "Patient" or "Caregiver". */
export function correctionRequesterLabel(role: string | null | undefined): string {
  return role === 'CAREGIVER' ? 'Caregiver' : 'Patient';
}

/**
 * A correction timestamp (a UTC instant) in the viewer's own locale and time zone, for example
 * "10 Nov 2026, 10:15". Empty for a missing or unreadable value.
 */
export function formatCorrectionInstant(value: string | null | undefined): string {
  if (!value) return '';
  const at = new Date(value);
  if (Number.isNaN(at.getTime())) return '';
  return at.toLocaleString(undefined, { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/** True when the request is open and this doctor is the one assigned to review it. */
export function canDoctorResolve(request: CorrectionRequest, doctorSystemId: string | null | undefined): boolean {
  return request.status === 'OPEN' && !!doctorSystemId && request.assignedDoctorSystemId === doctorSystemId;
}

export interface CorrectionRecordLink {
  section: string;
  params:  SectionParamsInput;
}

const PATIENT_SECTION: Record<CorrectionResourceType, string> = {
  MEDICAL_RECORD: 'medical-records',
  LAB_RESULT:     'lab-results',
  IMAGING_RESULT: 'imaging-results',
  PRESCRIPTION:   'prescriptions',
  CONSULTATION:   'consultation-history',
};

const DOCTOR_SECTION: Record<CorrectionResourceType, string> = {
  MEDICAL_RECORD: 'medical-records',
  LAB_RESULT:     'labs',
  IMAGING_RESULT: 'imaging',
  PRESCRIPTION:   'prescriptions',
  CONSULTATION:   'consultations',
};

const PROXY_TAB: Partial<Record<CorrectionResourceType, string>> = {
  MEDICAL_RECORD: 'records',
  LAB_RESULT:     'labs',
  IMAGING_RESULT: 'imaging',
  CONSULTATION:   'consultations',
};

/**
 * Where the record a request is about is shown in the viewer's dashboard: the section key and its URL params
 * (`id` = the record, `patient` for doctor and proxy). Null for a role without clinical sections (admin) or an
 * unknown record type.
 */
export function correctionRecordLink(
  role: AppRole,
  request: Pick<CorrectionRequest, 'resourceType' | 'resourceId' | 'patientSystemId'>,
): CorrectionRecordLink | null {
  const { resourceType, resourceId, patientSystemId } = request;
  if (!(resourceType in RESOURCE_LABEL)) return null;
  switch (role) {
    case 'patient':
      return { section: PATIENT_SECTION[resourceType], params: { id: resourceId } };
    case 'doctor':
      return { section: DOCTOR_SECTION[resourceType], params: { patient: patientSystemId, id: resourceId } };
    case 'proxy':
      return resourceType === 'PRESCRIPTION'
        ? { section: 'medications', params: { patient: patientSystemId, id: resourceId } }
        : { section: 'documents-results', params: { tab: PROXY_TAB[resourceType], patient: patientSystemId, id: resourceId } };
    default:
      return null;
  }
}
