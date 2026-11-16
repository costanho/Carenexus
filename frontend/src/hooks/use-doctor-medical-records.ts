import { useCallback, useMemo } from 'react';
import { withIdempotencyKey } from '@/lib/api/idempotency';
import { fetchPage, MAX_PAGE_SIZE } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import { api } from '@/lib/auth/auth.interceptor';
import type { MedicalRecordType } from '@/lib/validation/medical-record';
import { useCurrentUser } from './use-current-user';
import { doctorDisplayName } from './use-doctors';
import { usePaged } from './use-paged';
import { useResource } from './use-resource';

// Medical records of the signed-in doctor (API contract be-medical-records).
//   list    GET    /api/medical-records?doctorSystemId=<caller>&type=&patientSystemId=&q=&page=&size=
//   one     GET    /api/medical-records/{id}
//   create  POST   /api/medical-records            (Idempotency-Key)
//   update  PUT    /api/medical-records/{id}       (partial; the form sends all six editable fields)
//   delete  DELETE /api/medical-records/{id}       (soft delete)
// Patient names are not in the payload: they come from GET /api/doctors/patients/options.

// ─── API types (camelCase, as sent by the server) ────────────────────────────

export type RecordType = MedicalRecordType;
export type RecordSource = 'MANUAL' | 'CONSULTATION';

export interface IcdCodeDto {
  code:        string;
  description: string;
  /** True when the code is in the server's catalogue; false for a well-formed code outside it. */
  verified:    boolean;
}

export interface MedicalRecordDto {
  recordId:        number;
  patientSystemId: string;
  doctorSystemId:  string;
  doctorName:      string | null;
  consultationId:  number | null;
  recordType:      RecordType;
  title:           string;
  description:     string | null;
  treatmentPlan:   string | null;
  observations:    string | null;
  /** Legacy JSON text; new code reads `icdCodeList`. */
  icdCodes:        string | null;
  icdCodeList:     IcdCodeDto[];
  source:          RecordSource;
  isDeleted:       boolean;
  deletedAt:       string | null;
  /** Clinic wall-clock date-time; format with `@/lib/time/clinic`. */
  createdAt:       string;
  /** Only meaningful compared with `createdAt` ("Edited"). */
  updatedAt:       string;
}

export interface IcdCodeInput {
  code:         string;
  description?: string | null;
}

export interface CreateMedicalRecordBody {
  patientSystemId: string;
  consultationId?: number | null;
  recordType:      RecordType;
  title:           string;
  description?:    string | null;
  treatmentPlan?:  string | null;
  observations?:   string | null;
  icdCodes?:       IcdCodeInput[] | null;
}

export interface UpdateMedicalRecordBody {
  recordType?:    RecordType;
  title?:         string;
  /** A blank string clears the text; absent or null leaves it unchanged. */
  description?:   string | null;
  treatmentPlan?: string | null;
  observations?:  string | null;
  /** `[]` removes every code; a non-empty array replaces the whole list. */
  icdCodes?:      IcdCodeInput[] | null;
}

/** One row of GET /api/doctors/patients/options. */
export interface DoctorPatientOption {
  patientSystemId: string;
  firstName:       string;
  lastName:        string;
  fullName:        string;
}

/** A consultation the record can be linked to (the doctor's own consultations with the chosen patient). */
export interface RecordConsultationOption {
  consultationId: number;
  status:         string;
  /** Clinic wall-clock date-time. */
  createdAt:      string;
  /** Chief complaint, or the diagnosis when there is none; null when both are empty. */
  summary:        string | null;
}

// ─── View model (kept for the web and native screens) ────────────────────────

export interface IcdCode {
  code:        string;
  description: string;
  /** False marks a well-formed code that is not in the catalogue. Absent on rows read from the legacy text field. */
  verified?:   boolean;
}

export interface DoctorMedicalRecord {
  record_id:             number;
  patient_system_id:     string;
  /** The authoring doctor: the only doctor who may edit or delete the record. */
  doctor_system_id:      string;
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
  /** MANUAL (filed by a doctor) or CONSULTATION (created when a consultation was completed). */
  source:                RecordSource;
  /** Clinic wall-clock date-time. */
  created_at:            string;
  updated_at:            string;
  doctor_name:           string;
  doctor_specialization: string | null;
  doctor_avatar_url:     string | null;
  // from GET /api/doctors/patients/options
  patient_name:          string;
  patient_avatar_url:    string | null;
}

const DOCTOR_NAME_FALLBACK = 'Your doctor';

/** The name shown for a patient who is not in the doctor's patient options (the relationship has ended). */
export function patientNameFallback(patientSystemId: string): string {
  return patientSystemId ? `Patient ${patientSystemId}` : 'Unknown Patient';
}

/** Codes from the legacy `icdCodes` text, for responses of a server that does not send `icdCodeList` yet. */
function parseLegacyIcdCodes(raw: unknown): IcdCode[] {
  if (typeof raw !== 'string' || !raw.trim()) return [];
  let val: unknown;
  try { val = JSON.parse(raw); } catch { return []; }
  if (!Array.isArray(val)) return [];
  return val
    .map((c: unknown): IcdCode | null => {
      if (typeof c === 'string') return { code: c, description: '' };
      if (c && typeof c === 'object' && (c as { code?: unknown }).code) {
        const row = c as { code: unknown; description?: unknown };
        return { code: String(row.code), description: String(row.description ?? '') };
      }
      return null;
    })
    .filter((c): c is IcdCode => c !== null);
}

function toIcdCodes(dto: MedicalRecordDto): IcdCode[] {
  if (!Array.isArray(dto.icdCodeList)) return parseLegacyIcdCodes(dto.icdCodes);
  return dto.icdCodeList
    .filter(c => c && typeof c.code === 'string' && c.code)
    .map(c => ({ code: c.code, description: c.description ?? '', verified: c.verified !== false }));
}

/** Maps an API record onto the view model. `patientNames` is the id-to-name map of the doctor's patients. */
export function toDoctorMedicalRecord(
  dto: MedicalRecordDto,
  patientNames?: ReadonlyMap<string, string> | null,
): DoctorMedicalRecord {
  const patientSystemId = dto.patientSystemId ?? '';
  return {
    record_id:             dto.recordId ?? 0,
    patient_system_id:     patientSystemId,
    doctor_system_id:      dto.doctorSystemId ?? '',
    patient_id:            0,
    doctor_id:             0,
    consultation_id:       dto.consultationId ?? null,
    record_type:           dto.recordType,
    title:                 dto.title ?? '',
    description:           dto.description ?? null,
    treatment_plan:        dto.treatmentPlan ?? null,
    observations:          dto.observations ?? null,
    icd_codes:             toIcdCodes(dto),
    source:                dto.source === 'CONSULTATION' ? 'CONSULTATION' : 'MANUAL',
    created_at:            dto.createdAt ? String(dto.createdAt) : '',
    updated_at:            dto.updatedAt ? String(dto.updatedAt) : '',
    doctor_name:           (dto.doctorName ?? '').trim() || DOCTOR_NAME_FALLBACK,
    doctor_specialization: null,
    doctor_avatar_url:     null,
    patient_name:          patientNames?.get(patientSystemId) ?? patientNameFallback(patientSystemId),
    patient_avatar_url:    null,
  };
}

/** True when the record was changed after it was filed. */
export function isRecordEdited(record: Pick<DoctorMedicalRecord, 'created_at' | 'updated_at'>): boolean {
  return !!record.updated_at && !!record.created_at && record.updated_at !== record.created_at;
}

// ─── API calls ───────────────────────────────────────────────────────────────

const RECORDS_URL = '/api/medical-records';

export interface RecordListQuery {
  doctorSystemId?:  string | null;
  patientSystemId?: string | null;
  type?:            RecordType | null;
  consultationId?:  number | null;
  /** Title or ICD code or ICD description, at most 100 characters. */
  q?:               string | null;
}

export function listMedicalRecords(query: RecordListQuery, page: number, size: number): Promise<PageResponse<MedicalRecordDto>> {
  return fetchPage<MedicalRecordDto>(RECORDS_URL, page, size, {
    doctorSystemId:  query.doctorSystemId,
    patientSystemId: query.patientSystemId,
    type:            query.type,
    consultationId:  query.consultationId,
    q:               query.q?.trim() || undefined,
  });
}

export async function getMedicalRecord(recordId: number): Promise<MedicalRecordDto> {
  const { data } = await api.get<MedicalRecordDto>(`${RECORDS_URL}/${recordId}`);
  return data;
}

/** Files a record. Send the same `idempotencyKey` when a submit is retried, so it cannot create two rows. */
export async function createMedicalRecord(body: CreateMedicalRecordBody, idempotencyKey: string): Promise<MedicalRecordDto> {
  const { data } = await api.post<MedicalRecordDto>(RECORDS_URL, body, withIdempotencyKey(idempotencyKey));
  return data;
}

export async function updateMedicalRecord(recordId: number, body: UpdateMedicalRecordBody): Promise<MedicalRecordDto> {
  const { data } = await api.put<MedicalRecordDto>(`${RECORDS_URL}/${recordId}`, body);
  return data;
}

/** Soft-deletes a record. A 404 means it was already deleted. */
export async function deleteMedicalRecord(recordId: number): Promise<void> {
  await api.delete(`${RECORDS_URL}/${recordId}`);
}

export async function listDoctorPatientOptions(): Promise<DoctorPatientOption[]> {
  const { data } = await api.get<DoctorPatientOption[]>('/api/doctors/patients/options');
  return Array.isArray(data) ? data : [];
}

interface ConsultationRow {
  consultationId: number;
  status:         string;
  createdAt:      string;
  chiefComplaint: string | null;
  diagnosis:      string | null;
}

/**
 * The signed-in doctor's consultations with one patient, newest first (GET /api/consultations with
 * `doctorSystemId` = caller), for the optional "Consultation" choice of the record form.
 */
export async function listRecordConsultationOptions(
  patientSystemId: string,
  doctorSystemId: string,
): Promise<RecordConsultationOption[]> {
  const page = await fetchPage<ConsultationRow>('/api/consultations', 0, 50, { patientSystemId, doctorSystemId });
  return page.content
    .filter(c => c && typeof c.consultationId === 'number')
    .map(c => ({
      consultationId: c.consultationId,
      status:         c.status ?? '',
      createdAt:      c.createdAt ? String(c.createdAt) : '',
      summary:        (c.chiefComplaint ?? '').trim() || (c.diagnosis ?? '').trim() || null,
    }));
}

// ─── Hooks ───────────────────────────────────────────────────────────────────

export interface DoctorPatientNames {
  /** Patient system id to "First Last". Empty until loaded. */
  names:   ReadonlyMap<string, string>;
  loading: boolean;
  error:   string | null;
  refetch: () => void;
  /** The patient's name, or a neutral label carrying the id when the patient is not among the doctor's patients. */
  nameOf:  (patientSystemId: string | null | undefined) => string;
}

/** Names of the signed-in doctor's patients (GET /api/doctors/patients/options, at most 500 rows). */
export function useDoctorPatientNames(enabled = true): DoctorPatientNames {
  const { data, loading, error, refetch } = useResource(listDoctorPatientOptions, [], {
    enabled,
    errorMessage: 'Patient names could not be loaded.',
  });

  const names = useMemo(() => {
    const map = new Map<string, string>();
    for (const p of data ?? []) {
      if (!p?.patientSystemId) continue;
      const name = (p.fullName ?? '').trim() || `${p.firstName ?? ''} ${p.lastName ?? ''}`.trim();
      if (name) map.set(p.patientSystemId, name);
    }
    return map;
  }, [data]);

  const nameOf = useCallback((patientSystemId: string | null | undefined) => {
    const id = (patientSystemId ?? '').trim();
    return names.get(id) ?? patientNameFallback(id);
  }, [names]);

  return { names, loading, error, refetch, nameOf };
}

export interface DoctorRecordFilters {
  /** One record type; null or absent lists every type. */
  type?:            RecordType | null;
  /** Only records about this patient (the `patient` URL filter). */
  patientSystemId?: string | null;
  /** Search in titles and ICD codes. */
  q?:               string | null;
  /** Rows per request, 1 to 100. Default 100, so screens without a "Load more" control still show a long list. */
  pageSize?:        number;
  /** When false the list is not requested (patient names still are). Default true. */
  enabled?:         boolean;
}

interface State {
  records:  DoctorMedicalRecord[];
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
  /** HTTP status of the failure (0 = no response), or null. */
  errorStatus: number | null;
  /** True when the server has more records than are loaded. */
  hasMore:     boolean;
  /** Fetches the next page. */
  loadMore:    () => void;
  loadingMore: boolean;
  /** True while `refetch` reloads a list that is already on screen. */
  refreshing:  boolean;
  /** Number of records on the server for the current filters. */
  total:       number;
  /** System id of the signed-in doctor, or null until known. */
  doctorSystemId: string | null;
  /** Set when the patient names could not be loaded; records then show a neutral patient label. */
  patientNamesError: string | null;
  /** Names of the doctor's patients by system id (GET /api/doctors/patients/options). */
  patientNames:  ReadonlyMap<string, string>;
  /** A patient's name, or a neutral label carrying the id for a patient who is not among the doctor's patients. */
  patientNameOf: (patientSystemId: string | null | undefined) => string;
}

/**
 * Records the signed-in doctor authored, newest first, loaded page by page. Filtering, searching and ordering
 * are done by the server; a change of filter starts again from the first page.
 */
export function useDoctorMedicalRecords(filters: DoctorRecordFilters = {}): State {
  const { user, loading: userLoading, error: userError, refetch: refetchUser } = useCurrentUser();
  const doctorSystemId = user?.system_id ?? null;
  const type            = filters.type ?? null;
  const patientSystemId = (filters.patientSystemId ?? '').trim() || null;
  const q               = (filters.q ?? '').trim() || null;

  const paged = usePaged<MedicalRecordDto>(
    (page, size) => listMedicalRecords({ doctorSystemId, patientSystemId, type, q }, page, size),
    [doctorSystemId, patientSystemId, type, q],
    {
      enabled:      !!doctorSystemId && (filters.enabled ?? true),
      pageSize:     filters.pageSize ?? MAX_PAGE_SIZE,
      keyOf:        r => r.recordId,
      errorMessage: 'Failed to load medical records.',
    },
  );
  const patients = useDoctorPatientNames(!!doctorSystemId);

  // The doctor's own name as a fallback for rows whose `doctorName` is null (the account has no name).
  const ownName = user?.first_name ? doctorDisplayName(user.first_name, user.last_name) : '';
  const records = useMemo(
    () => paged.items.map(dto => {
      const record = toDoctorMedicalRecord(dto, patients.names);
      return dto.doctorName || !ownName || dto.doctorSystemId !== doctorSystemId ? record : { ...record, doctor_name: ownName };
    }),
    [paged.items, patients.names, ownName, doctorSystemId],
  );

  const refetchRecords = paged.refetch;
  const refetchNames = patients.refetch;
  const namesFailed = !!patients.error;
  const refetch = useCallback(() => {
    if (!doctorSystemId) { refetchUser(); return; }
    refetchRecords();
    if (namesFailed) refetchNames();
  }, [doctorSystemId, refetchUser, refetchRecords, refetchNames, namesFailed]);

  return {
    records,
    // Names are waited for on the first load only, so a row never flashes a placeholder name.
    loading:     userLoading || (!!doctorSystemId && (paged.loading || patients.loading)),
    error:       doctorSystemId ? paged.error : (userLoading ? null : userError ?? 'Failed to load medical records.'),
    refetch,
    errorStatus: paged.errorStatus,
    hasMore:     paged.hasMore,
    loadMore:    paged.loadMore,
    loadingMore: paged.loadingMore,
    refreshing:  paged.refreshing,
    total:       paged.total,
    doctorSystemId,
    patientNamesError: patients.error,
    patientNames:  patients.names,
    patientNameOf: patients.nameOf,
  };
}

export type RecordCounts = Record<RecordType | 'ALL', number>;

const COUNTED_TYPES: readonly RecordType[] = ['CONSULT', 'LAB', 'IMAGING', 'PRESCRIPTION', 'REFERRAL', 'DISCHARGE', 'NOTE', 'OPERATION'];

/**
 * How many records the signed-in doctor authored, in total and per type (optionally for one patient). There is
 * no statistics endpoint: each number is `totalElements` of the list asked for with `size=1`.
 */
export function useDoctorRecordCounts(patientSystemId?: string | null, enabled = true): {
  counts: RecordCounts | null; loading: boolean; error: string | null; refetch: () => void;
} {
  const { user } = useCurrentUser();
  const doctorSystemId = user?.system_id ?? null;
  const patient = (patientSystemId ?? '').trim() || null;

  const { data, loading, error, refetch } = useResource<RecordCounts>(
    async () => {
      const count = (type: RecordType | null) =>
        listMedicalRecords({ doctorSystemId, patientSystemId: patient, type }, 0, 1).then(p => p.totalElements);
      const [all, ...perType] = await Promise.all([count(null), ...COUNTED_TYPES.map(count)]);
      const counts = { ALL: all } as RecordCounts;
      COUNTED_TYPES.forEach((type, i) => { counts[type] = perType[i]; });
      return counts;
    },
    [doctorSystemId, patient],
    { enabled: enabled && !!doctorSystemId, errorMessage: 'Record counts could not be loaded.' },
  );

  return { counts: data, loading, error, refetch };
}

/** One record by id (deep links, the correction queue). 403 and 404 are reported through `errorStatus`. */
export function useMedicalRecord(recordId: number | null): {
  record: MedicalRecordDto | null; loading: boolean; error: string | null; errorStatus: number | null; refetch: () => void;
} {
  const { data, loading, error, errorStatus, refetch } = useResource(
    () => getMedicalRecord(recordId as number),
    [recordId],
    { enabled: recordId !== null, errorMessage: 'The record could not be loaded.' },
  );
  return { record: data, loading, error, errorStatus, refetch };
}

/** The doctor's own consultations with a patient, for the record form. Nothing is requested without a patient. */
export function useRecordConsultationOptions(patientSystemId: string | null | undefined): {
  options: RecordConsultationOption[]; loading: boolean; error: string | null; refetch: () => void;
} {
  const { user } = useCurrentUser();
  const doctorSystemId = user?.system_id ?? null;
  const patient = (patientSystemId ?? '').trim() || null;
  const { data, loading, error, refetch } = useResource(
    () => listRecordConsultationOptions(patient as string, doctorSystemId as string),
    [patient, doctorSystemId],
    { enabled: !!patient && !!doctorSystemId, errorMessage: 'Consultations could not be loaded.' },
  );
  return { options: data ?? [], loading, error, refetch };
}
