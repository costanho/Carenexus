import type { PagedState } from './use-paged';
import type { ResourceState } from './use-resource';
import {
  enumOr, idOrNull, recordDoctor, recordPatientName, textOrNull,
  usePatientRecordById, usePatientRecordPages, type PatientRecordContext,
} from './use-medical-records';

export type ConsultationStatus = 'IN_PROGRESS' | 'COMPLETED' | 'DRAFT' | 'CANCELLED';
export type ConsultationAppointmentType = 'IN_PERSON' | 'VIDEO' | 'PHONE';

const CONSULTATION_STATUSES: readonly ConsultationStatus[] = ['DRAFT', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'];
const APPOINTMENT_TYPES: readonly ConsultationAppointmentType[] = ['IN_PERSON', 'VIDEO', 'PHONE'];

/** `ConsultationResponse` as the API sends it (GET /api/consultations and /api/consultations/{id}). */
export interface ConsultationDto {
  consultationId:          number;
  appointmentId:           number;
  appointmentType:         ConsultationAppointmentType | null;
  appointmentStatus:       'SCHEDULED' | 'RESCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW' | null;
  patientSystemId:         string;
  /** Always null; kept by the API for compatibility. */
  patientName:             string | null;
  doctorSystemId:          string;
  chiefComplaint:          string | null;
  historyOfPresentIllness: string | null;
  physicalExamination:     string | null;
  clinicalNotes:           string | null;
  diagnosis:               string | null;
  treatmentPlan:           string | null;
  followUpInstructions:    string | null;
  followUpRequired:        boolean;
  /** Date only, YYYY-MM-DD. */
  followUpDate:            string | null;
  durationMinutes:         number | null;
  status:                  ConsultationStatus;
  /** Clinic wall-clock date-times. */
  startedAt:               string | null;
  completedAt:             string | null;
  createdAt:               string;
  /** The CONSULT medical record created from this consultation, or null. */
  medicalRecordId:         number | null;
}

export interface Consultation {
  consultation_id:           number;
  appointment_id:            number;
  patient_id:                number;
  doctor_id:                 number;
  chief_complaint:           string | null;
  history_of_present_illness:string | null;
  physical_examination:      string | null;
  clinical_notes:            string | null;
  diagnosis:                 string | null;
  treatment_plan:            string | null;
  follow_up_instructions:    string | null;
  follow_up_required:        number;
  /** Date only, YYYY-MM-DD. */
  follow_up_date:            string | null;
  duration_minutes:          number | null;
  status:                    ConsultationStatus;
  /** Clinic wall-clock date-times. */
  started_at:                string | null;
  completed_at:              string | null;
  created_at:                string;
  doctor_system_id:          string;
  doctor_name:               string;
  doctor_specialization:     string | null;
  doctor_avatar_url:         string | null;
  patient_name:              string;
  appointment_type:          ConsultationAppointmentType | null;
  appointment_status:        string | null;
  /** The medical record filed for this consultation; opening it is authorised separately (403 or 404 possible). */
  medical_record_id:         number | null;
}

/**
 * Patients and caregivers see a consultation only once the doctor has finalised it (COMPLETED). The backend
 * already hides drafts and in-progress notes from them; cancelled ones were never recorded.
 */
export function isPatientVisibleConsultation(c: Pick<Consultation, 'status'>): boolean {
  return c.status === 'COMPLETED';
}

export function toConsultation(r: ConsultationDto, ctx: PatientRecordContext): Consultation {
  const appointmentType = textOrNull(r.appointmentType);
  return {
    consultation_id:            r.consultationId ?? 0,
    appointment_id:             r.appointmentId ?? 0,
    patient_id:                 0,
    doctor_id:                  0,
    chief_complaint:            textOrNull(r.chiefComplaint),
    history_of_present_illness: textOrNull(r.historyOfPresentIllness),
    physical_examination:       textOrNull(r.physicalExamination),
    clinical_notes:             textOrNull(r.clinicalNotes),
    diagnosis:                  textOrNull(r.diagnosis),
    treatment_plan:             textOrNull(r.treatmentPlan),
    follow_up_instructions:     textOrNull(r.followUpInstructions),
    follow_up_required:         r.followUpRequired ? 1 : 0,
    follow_up_date:             textOrNull(r.followUpDate),
    duration_minutes:           idOrNull(r.durationMinutes),
    status:                     enumOr(r.status, CONSULTATION_STATUSES, 'DRAFT'),
    started_at:                 textOrNull(r.startedAt),
    completed_at:               textOrNull(r.completedAt),
    created_at:                 textOrNull(r.createdAt) ?? '',
    // The API sends no doctor name on consultations: the directory or a lookup by id supplies it.
    ...recordDoctor(r.doctorSystemId, null, ctx),
    doctor_avatar_url:          null,
    patient_name:               recordPatientName(r.patientName, ctx),
    appointment_type:           appointmentType ? enumOr(appointmentType, APPOINTMENT_TYPES, 'IN_PERSON') : null,
    appointment_status:         textOrNull(r.appointmentStatus),
    medical_record_id:          idOrNull(r.medicalRecordId),
  };
}

export interface ConsultationsOptions {
  /** Rows per request. Default `RECORD_LIST_DEFAULT_PAGE_SIZE` (100); later rows come with `loadMore`. */
  pageSize?: number;
  /** When false nothing is requested. Default true. */
  enabled?:  boolean;
}

interface State {
  consultations: Consultation[];
  loading:       boolean;
  error:         string | null;
  refetch:       () => void;
  /** True when the server has more consultations than are loaded. */
  hasMore:       boolean;
  /** Fetches the next page and appends it to `consultations`. */
  loadMore:      () => void;
  loadingMore:   boolean;
  refreshing:    boolean;
  /** Number of completed consultations on the server. */
  total:         number;
  errorStatus:   number | null;
  /** The same list in the shape `PagedList` renders. */
  paged:         PagedState<Consultation>;
}

const CONSULTATIONS_URL = '/api/consultations';
const consultationKey = (row: Consultation) => row.consultation_id;

/**
 * The signed-in patient's finalised consultations, newest first
 * (GET /api/consultations?patientSystemId=<caller>&status=COMPLETED). The API would also return cancelled
 * consultations to a patient; they are not part of the history, so the list asks for completed ones only.
 */
export function useConsultations(options: ConsultationsOptions = {}): State {
  const paged = usePatientRecordPages<ConsultationDto, Consultation>({
    url:          CONSULTATIONS_URL,
    filters:      { status: 'COMPLETED' },
    map:          toConsultation,
    keyOf:        consultationKey,
    errorMessage: 'Failed to load consultations.',
    pageSize:     options.pageSize,
    enabled:      options.enabled,
  });
  return {
    consultations: paged.items, loading: paged.loading, error: paged.error, refetch: paged.refetch,
    hasMore: paged.hasMore, loadMore: paged.loadMore, loadingMore: paged.loadingMore,
    refreshing: paged.refreshing, total: paged.total, errorStatus: paged.errorStatus, paged,
  };
}

/**
 * One consultation by id (GET /api/consultations/{id}); `null` loads nothing. For a patient an unfinished
 * consultation answers 404.
 */
export function useConsultationById(consultationId: number | null): ResourceState<Consultation> {
  return usePatientRecordById<ConsultationDto, Consultation>({
    url: CONSULTATIONS_URL, id: consultationId, map: toConsultation, errorMessage: 'Failed to load the consultation.',
  });
}
