import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { fetchPage, MAX_PAGE_SIZE } from '@/lib/api/paged';
import {
  cachedDoctorName, DOCTOR_NAME_FALLBACK, primeDoctorNames, resolveDoctorName, resolveDoctorNames,
} from '@/lib/doctors/names';
import { useCurrentUser } from './use-current-user';
import { loadActiveDoctors, type Doctor } from './use-doctors';
import { usePaged, type PagedState } from './use-paged';
import { useResource, type ResourceState } from './use-resource';

// ─── Shared loader for the patient's own clinical lists ──────────────────────────────────────────
// Medical records, lab results, imaging results and consultations are all read the same way: the canonical
// paged endpoint scoped to the signed-in patient, one page at a time, with the doctor shown by name. The
// three sibling hooks (use-lab-results, use-imaging-results, use-consultations) import this part.

/**
 * Rows fetched per request by a caller that passes no `pageSize`: the largest page the server allows (100).
 * Every list is paged, also with this default: a caller that shows the whole list must call `loadMore` while
 * `hasMore` is true, and a caller that shows how many rows there are must read `total` (the server's count),
 * never the number of rows loaded. A caller that shows only a few rows (a dashboard card) passes a small
 * `pageSize` instead of loading a full page.
 */
export const RECORD_LIST_DEFAULT_PAGE_SIZE = MAX_PAGE_SIZE;

export type RecordFilterValue = string | number | boolean | null | undefined;

/** What a row mapper needs besides the API row. */
export interface PatientRecordContext {
  /** The signed-in patient's name, for rows that carry none. */
  patientName: string;
  /** The active-doctor directory by system id (name and specialisation). Empty when it could not be loaded. */
  doctors:     Map<string, Doctor>;
}

/** The doctor fields every patient record view model carries. */
export interface RecordDoctorFields {
  doctor_system_id:      string;
  doctor_name:           string;
  doctor_specialization: string | null;
}

/** Text of an API field, trimmed; null when it is missing or blank. */
export function textOrNull(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  const text = String(value).trim();
  return text ? text : null;
}

/** An integer id from an API field, or null. */
export function idOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** One of the allowed upper-case enum values, or the fallback for anything else. */
export function enumOr<T extends string>(value: unknown, allowed: readonly T[], fallback: T): T {
  const key = typeof value === 'string' ? value.trim().toUpperCase() : '';
  return (allowed as readonly string[]).includes(key) ? (key as T) : fallback;
}

/**
 * The doctor of a row, by name: the name the API row carries, else the active directory, else a name already
 * looked up, else "Your doctor". Never the raw DOC id.
 */
export function recordDoctor(doctorSystemId: unknown, apiName: unknown, ctx: PatientRecordContext): RecordDoctorFields {
  const id     = textOrNull(doctorSystemId) ?? '';
  const given  = textOrNull(apiName);
  const listed = id ? ctx.doctors.get(id) : undefined;
  // A name from the API also serves the lists whose rows carry none (consultations).
  if (id && given) primeDoctorNames([{ doctor_system_id: id, full_name: given }]);
  return {
    doctor_system_id:      id,
    doctor_name:           given ?? (listed?.full_name || cachedDoctorName(id) || DOCTOR_NAME_FALLBACK),
    doctor_specialization: listed?.specialization ?? null,
  };
}

/** The patient shown on a row: the API's name when it sends one, else the signed-in patient. */
export function recordPatientName(apiName: unknown, ctx: PatientRecordContext): string {
  return textOrNull(apiName) ?? (ctx.patientName || 'Unknown Patient');
}

/** Loads the doctor directory for a mapper. A failed directory never hides the records: names fall back to lookups. */
export async function loadPatientRecordContext(patientName: string): Promise<PatientRecordContext> {
  const list = await loadActiveDoctors().catch(() => [] as Doctor[]);
  primeDoctorNames(list);
  const doctors = new Map<string, Doctor>();
  list.forEach(d => { if (d.doctor_system_id) doctors.set(d.doctor_system_id, d); });
  return { patientName, doctors };
}

function withResolvedDoctor<T extends RecordDoctorFields>(row: T): T {
  if (row.doctor_name !== DOCTOR_NAME_FALLBACK || !row.doctor_system_id) return row;
  const name = cachedDoctorName(row.doctor_system_id);
  return name ? { ...row, doctor_name: name } : row;
}

export interface PatientRecordPagesConfig<D, T> {
  /** The canonical paged endpoint, for example `/api/lab-results`. */
  url:           string;
  /** Server-side filters (type, status, ...). `patientSystemId` is added by the hook. */
  filters?:      Record<string, RecordFilterValue>;
  /** Builds the view model of a row; null leaves the row out of the list. */
  map:           (row: D, ctx: PatientRecordContext) => T | null;
  keyOf:         (row: T) => string | number;
  errorMessage:  string;
  pageSize?:     number;
  /** When false nothing is requested. Default true. */
  enabled?:      boolean;
}

/**
 * The signed-in patient's rows of one clinical list, page by page. The list waits for the account, restarts when
 * a filter changes, and fills in doctor names that had to be looked up without holding the rows back.
 */
export function usePatientRecordPages<D, T extends RecordDoctorFields>(
  config: PatientRecordPagesConfig<D, T>,
): PagedState<T> {
  const { user, loading: userLoading, error: userError, refetch: refetchUser } = useCurrentUser();
  const patientId   = user?.system_id ?? null;
  const patientName = user ? `${user.first_name} ${user.last_name}`.trim() : '';

  const filters   = config.filters ?? {};
  const filterKey = Object.keys(filters).sort().map(name => `${name}=${String(filters[name] ?? '')}`).join('&');

  const [nameTick, setNameTick] = useState(0);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);

  const paged = usePaged<T>(
    async (page, size) => {
      const [raw, ctx] = await Promise.all([
        fetchPage<D>(config.url, page, size, { ...filters, patientSystemId: patientId }),
        loadPatientRecordContext(patientName),
      ]);
      const mapped = {
        ...raw,
        content: raw.content.map(row => config.map(row, ctx)).filter((row): row is T => row !== null),
      };
      // Doctors neither the row nor the directory names (deactivated, or the directory failed) are looked up by
      // id. The rows are shown at once; the names fill in when the lookups settle. resolveDoctorNames never rejects.
      const missing = Array.from(new Set(
        mapped.content
          .filter(row => row.doctor_name === DOCTOR_NAME_FALLBACK && row.doctor_system_id)
          .map(row => row.doctor_system_id),
      ));
      if (missing.length > 0) {
        void resolveDoctorNames(missing).then(found => {
          if (!alive.current) return;
          if (Array.from(found.values()).some(name => name !== DOCTOR_NAME_FALLBACK)) setNameTick(t => t + 1);
        });
      }
      return mapped;
    },
    [config.url, patientId, filterKey],
    {
      pageSize:     config.pageSize ?? RECORD_LIST_DEFAULT_PAGE_SIZE,
      enabled:      (config.enabled ?? true) && !!patientId,
      errorMessage: config.errorMessage,
      keyOf:        config.keyOf,
    },
  );

  const items = useMemo(() => {
    let changed = false;
    const next = paged.items.map(row => {
      const named = withResolvedDoctor(row);
      if (named !== row) changed = true;
      return named;
    });
    return changed ? next : paged.items;
    // nameTick: a lookup finished, so the name cache may now know a doctor it did not know before.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [paged.items, nameTick]);

  if (!patientId) {
    // Without a signed-in user there is no list to load: Retry loads the account again, and the list follows.
    const accountError = userLoading ? null : (userError ?? 'Could not load your account.');
    const waiting = (config.enabled ?? true) && !accountError;
    return {
      ...paged, items: [], loading: waiting, loadingMore: false, refreshing: false,
      error: accountError, errorStatus: accountError ? 0 : null, hasMore: false, total: 0,
      refetch: refetchUser,
    };
  }
  return { ...paged, items };
}

export interface PatientRecordByIdConfig<D, T> {
  /** The collection endpoint; the id is appended (`/api/lab-results` + `/87`). */
  url:          string;
  /** The row to load; null loads nothing. */
  id:           number | null;
  map:          (row: D, ctx: PatientRecordContext) => T;
  errorMessage: string;
}

/**
 * One row by id (a deep link from a notification, or a linked record). `errorStatus` tells a row that no longer
 * exists (404) from one the caller may not read (403).
 */
export function usePatientRecordById<D, T extends RecordDoctorFields>(
  config: PatientRecordByIdConfig<D, T>,
): ResourceState<T> {
  const { user, loading: userLoading, error: userError, refetch: refetchUser } = useCurrentUser();
  const patientId   = user?.system_id ?? null;
  const patientName = user ? `${user.first_name} ${user.last_name}`.trim() : '';
  const id = config.id;

  const state = useResource<T>(
    async () => {
      const [{ data }, ctx] = await Promise.all([
        api.get<D>(`${config.url}/${encodeURIComponent(String(id))}`),
        loadPatientRecordContext(patientName),
      ]);
      const row = config.map(data, ctx);
      if (row.doctor_name === DOCTOR_NAME_FALLBACK && row.doctor_system_id) {
        // One lookup for one row; it never rejects and never yields the id.
        return { ...row, doctor_name: await resolveDoctorName(row.doctor_system_id) };
      }
      return row;
    },
    [config.url, id, patientId],
    { enabled: id !== null && !!patientId, errorMessage: config.errorMessage },
  );

  if (id !== null && !patientId) {
    // Nothing can be requested before the account is known: report that wait, or its failure, instead of "no row".
    const accountError = userLoading ? null : (userError ?? 'Could not load your account.');
    return {
      ...state, data: null, loading: !accountError, refreshing: false,
      error: accountError, errorStatus: accountError ? 0 : null, refetch: refetchUser,
    };
  }
  return state;
}

/**
 * How many rows of a list match a filter, read from `totalElements` of a one-row page (the API has no count
 * endpoints). Null until the count is known; a failed count stays null.
 */
export function usePatientRecordCount(
  url: string,
  filters: Record<string, RecordFilterValue>,
  options: { enabled?: boolean } = {},
): { count: number | null; refetch: () => void } {
  const { user } = useCurrentUser();
  const patientId = user?.system_id ?? null;
  const filterKey = Object.keys(filters).sort().map(name => `${name}=${String(filters[name] ?? '')}`).join('&');

  const { data, refetch } = useResource<number>(
    async () => (await fetchPage<unknown>(url, 0, 1, { ...filters, patientSystemId: patientId })).totalElements,
    [url, patientId, filterKey],
    { enabled: (options.enabled ?? true) && !!patientId },
  );
  return { count: data, refetch };
}

/** Counts by name, from `usePatientRecordCounts`. A count that is not known (loading, or its request failed) is null. */
export type RecordCounts<K extends string> = Record<K, number | null>;

/**
 * Several counts of one list at once (the stat cards and the counts on the filter chips of a record screen): one
 * one-row page per named filter set, requested together, each read from `totalElements`. A failed request leaves
 * only its own count null; the others still show.
 */
export function usePatientRecordCounts<K extends string>(
  url: string,
  sets: Record<K, Record<string, RecordFilterValue>>,
  options: { enabled?: boolean } = {},
): { counts: RecordCounts<K>; refetch: () => void } {
  const { user } = useCurrentUser();
  const patientId = user?.system_id ?? null;
  const names = Object.keys(sets) as K[];
  const setsKey = names.slice().sort().map(name => {
    const filters = sets[name];
    return `${name}:${Object.keys(filters).sort().map(f => `${f}=${String(filters[f] ?? '')}`).join('&')}`;
  }).join('|');

  const { data, refetch } = useResource<Partial<Record<K, number>>>(
    async () => {
      const settled = await Promise.allSettled(
        names.map(name => fetchPage<unknown>(url, 0, 1, { ...sets[name], patientSystemId: patientId })),
      );
      const out: Partial<Record<K, number>> = {};
      settled.forEach((result, i) => {
        if (result.status === 'fulfilled') out[names[i]] = result.value.totalElements;
      });
      return out;
    },
    [url, patientId, setsKey],
    { enabled: (options.enabled ?? true) && !!patientId },
  );

  const counts = {} as RecordCounts<K>;
  names.forEach(name => { counts[name] = data?.[name] ?? null; });
  return { counts, refetch };
}

/** The sum of counts that must all be known; null while any of them is not. */
export function sumCounts(...values: (number | null)[]): number | null {
  return values.every((v): v is number => v !== null) ? values.reduce((a, b) => a + b, 0) : null;
}

// ─── Medical records ─────────────────────────────────────────────────────────────────────────────

export type MedicalRecordType =
  | 'CONSULT' | 'LAB' | 'IMAGING' | 'PRESCRIPTION' | 'REFERRAL' | 'DISCHARGE' | 'NOTE' | 'OPERATION';
export type MedicalRecordSource = 'MANUAL' | 'CONSULTATION';

export const MEDICAL_RECORD_TYPES: readonly MedicalRecordType[] = [
  'CONSULT', 'LAB', 'IMAGING', 'PRESCRIPTION', 'REFERRAL', 'DISCHARGE', 'NOTE', 'OPERATION',
];

/** `MedicalRecordResponse` as the API sends it (GET /api/medical-records and /api/medical-records/{id}). */
export interface MedicalRecordDto {
  recordId:        number;
  patientSystemId: string;
  doctorSystemId:  string;
  doctorName:      string | null;
  consultationId:  number | null;
  recordType:      MedicalRecordType;
  title:           string;
  description:     string | null;
  treatmentPlan:   string | null;
  observations:    string | null;
  /** Legacy JSON text; read `icdCodeList`. */
  icdCodes:        string | null;
  icdCodeList:     { code: string; description: string; verified: boolean }[];
  source:          MedicalRecordSource;
  isDeleted:       boolean;
  deletedAt:       string | null;
  createdAt:       string;
  updatedAt:       string;
}

export interface MedicalRecord {
  record_id:             number;
  patient_id:            number;
  doctor_id:             number;
  consultation_id:       number | null;
  record_type:           MedicalRecordType;
  title:                 string;
  description:           string | null;
  treatment_plan:        string | null;
  observations:          string | null;
  /** `verified` is false for a well-formed code outside the bundled catalogue; absent on rows without the flag. */
  icd_codes:             Array<{ code: string; description: string; verified?: boolean }> | null;
  is_deleted:            number;
  deleted_at:            string | null;
  /** Clinic wall-clock date-time. */
  created_at:            string;
  updated_at:            string;
  doctor_system_id:      string;
  doctor_name:           string;
  doctor_specialization: string | null;
  doctor_avatar_url:     string | null;
  patient_name:          string;
  /** CONSULTATION = created automatically when a consultation was completed. */
  source:                MedicalRecordSource;
  /** True when the record was changed after it was filed. */
  edited:                boolean;
}

type IcdCode = { code: string; description: string; verified?: boolean };

function toIcdCode(raw: unknown): IcdCode | null {
  if (typeof raw === 'string') {
    const code = raw.trim();
    return code ? { code, description: '' } : null;
  }
  if (!raw || typeof raw !== 'object') return null;
  const item = raw as { code?: unknown; description?: unknown; verified?: unknown };
  const code = textOrNull(item.code);
  if (!code) return null;
  const out: IcdCode = { code, description: textOrNull(item.description) ?? '' };
  if (typeof item.verified === 'boolean') out.verified = item.verified;
  return out;
}

/** The record's ICD codes: the typed list when the API sends it, else the legacy JSON text. Null when there are none. */
function readIcdCodes(list: unknown, legacy: unknown): IcdCode[] | null {
  let source: unknown = list;
  if (!Array.isArray(source)) {
    if (Array.isArray(legacy)) source = legacy;
    else if (typeof legacy === 'string' && legacy.trim()) {
      try { source = JSON.parse(legacy); } catch { source = null; }
    }
  }
  if (!Array.isArray(source)) return null;
  const codes = source.map(toIcdCode).filter((c): c is IcdCode => c !== null);
  return codes.length > 0 ? codes : null;
}

export function toMedicalRecord(r: MedicalRecordDto, ctx: PatientRecordContext): MedicalRecord {
  const createdAt = textOrNull(r.createdAt) ?? '';
  const updatedAt = textOrNull(r.updatedAt) ?? '';
  return {
    record_id:         r.recordId ?? 0,
    patient_id:        0,
    doctor_id:         0,
    consultation_id:   idOrNull(r.consultationId),
    record_type:       enumOr(r.recordType, MEDICAL_RECORD_TYPES, 'NOTE'),
    title:             r.title ?? '',
    description:       textOrNull(r.description),
    treatment_plan:    textOrNull(r.treatmentPlan),
    observations:      textOrNull(r.observations),
    icd_codes:         readIcdCodes(r.icdCodeList, r.icdCodes),
    is_deleted:        r.isDeleted ? 1 : 0,
    deleted_at:        textOrNull(r.deletedAt),
    created_at:        createdAt,
    updated_at:        updatedAt,
    ...recordDoctor(r.doctorSystemId, r.doctorName, ctx),
    doctor_avatar_url: null,
    patient_name:      recordPatientName(null, ctx),
    source:            enumOr<MedicalRecordSource>(r.source, ['MANUAL', 'CONSULTATION'], 'MANUAL'),
    edited:            !!createdAt && !!updatedAt && createdAt !== updatedAt,
  };
}

export interface MedicalRecordsOptions {
  /** Only records of this type (filtered by the server). */
  type?:     MedicalRecordType;
  /** Rows per request. Default `RECORD_LIST_DEFAULT_PAGE_SIZE` (100); later rows come with `loadMore`. */
  pageSize?: number;
  /** When false nothing is requested. Default true. */
  enabled?:  boolean;
}

interface State {
  records:     MedicalRecord[];
  loading:     boolean;
  error:       string | null;
  refetch:     () => void;
  /** True when the server has more records than are loaded. */
  hasMore:     boolean;
  /** Fetches the next page and appends it to `records`. */
  loadMore:    () => void;
  loadingMore: boolean;
  refreshing:  boolean;
  /** Number of records on the server for the current filter. */
  total:       number;
  errorStatus: number | null;
  /** The same list in the shape `PagedList` renders. */
  paged:       PagedState<MedicalRecord>;
}

const RECORDS_URL = '/api/medical-records';
const recordKey = (row: MedicalRecord) => row.record_id;

/** The signed-in patient's medical records, newest first (GET /api/medical-records). */
export function useMedicalRecords(options: MedicalRecordsOptions = {}): State {
  const paged = usePatientRecordPages<MedicalRecordDto, MedicalRecord>({
    url:          RECORDS_URL,
    filters:      { type: options.type },
    map:          toMedicalRecord,
    keyOf:        recordKey,
    errorMessage: 'Failed to load medical records.',
    pageSize:     options.pageSize,
    enabled:      options.enabled,
  });
  return {
    records: paged.items, loading: paged.loading, error: paged.error, refetch: paged.refetch,
    hasMore: paged.hasMore, loadMore: paged.loadMore, loadingMore: paged.loadingMore,
    refreshing: paged.refreshing, total: paged.total, errorStatus: paged.errorStatus, paged,
  };
}

/** One medical record by id (GET /api/medical-records/{id}); `null` loads nothing. */
export function useMedicalRecordById(recordId: number | null): ResourceState<MedicalRecord> {
  return usePatientRecordById<MedicalRecordDto, MedicalRecord>({
    url: RECORDS_URL, id: recordId, map: toMedicalRecord, errorMessage: 'Failed to load the medical record.',
  });
}

/** The counts shown above the patient's medical records: the stat cards and the count on each type filter. */
export interface MedicalRecordSummary {
  total:       number | null;
  /** Consultation and operation records. */
  clinical:    number | null;
  /** Lab and imaging records. */
  diagnostics: number | null;
  /** Prescription, referral, note and discharge records. */
  documents:   number | null;
  byType:      Record<MedicalRecordType, number | null>;
  /** Counts again (after a refresh). */
  refetch:     () => void;
}

const RECORD_SUMMARY_SETS: Record<string, Record<string, RecordFilterValue>> = {
  total: {},
  ...Object.fromEntries(MEDICAL_RECORD_TYPES.map(t => [`type:${t}`, { type: t }])),
};

/**
 * How many medical records the patient has in total and per type, each read from `totalElements` of a one-row
 * page of GET /api/medical-records (`type=<T>&size=1`; the API has no count endpoint). The groups are sums of
 * their types. A count that is still loading, or whose request failed, is null.
 */
export function useMedicalRecordSummary(options: { enabled?: boolean } = {}): MedicalRecordSummary {
  const { counts, refetch } = usePatientRecordCounts(RECORDS_URL, RECORD_SUMMARY_SETS, options);
  const byType = Object.fromEntries(
    MEDICAL_RECORD_TYPES.map(t => [t, counts[`type:${t}`]]),
  ) as Record<MedicalRecordType, number | null>;
  return {
    total:       counts.total,
    clinical:    sumCounts(byType.CONSULT, byType.OPERATION),
    diagnostics: sumCounts(byType.LAB, byType.IMAGING),
    documents:   sumCounts(byType.PRESCRIPTION, byType.REFERRAL, byType.NOTE, byType.DISCHARGE),
    byType,
    refetch,
  };
}
