import { type DependencyList, useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { apiError } from '@/lib/api/errors';
import { DEFAULT_PAGE_SIZE, fetchPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import {
  effectivePermission, isGrantActive, type CaregiverFlag, type CaregiverGrant,
} from '@/lib/caregiver/permissions';

// Clinical records of a caregiver's loved ones (lab results, imaging, medical records, consultations,
// prescriptions), read per loved one from the canonical paged endpoints.
//
// A caregiver must always name the patient (`patientSystemId`), so one request is made per loved one. Whether a
// request is made at all is decided with the shared permission rule (`effectivePermission` / `isGrantActive`):
// a loved one whose grant does not include the permission is reported as "not shared" without asking the server.
// The server still decides every request: a 403 is reported as "denied".

// ─── API shapes (camelCase, straight from the API) ────────────────────────────

export type ResultStatus = 'PENDING' | 'REQUIRES_REVIEW' | 'COMPLETED' | 'CRITICAL' | 'CANCELLED';
export type LabTestType = 'BLOOD' | 'URINE' | 'STOOL' | 'CULTURE' | 'BIOPSY' | 'GENETIC' | 'SWAB' | 'OTHER';
export type ImagingType = 'XRAY' | 'MRI' | 'CT_SCAN' | 'ULTRASOUND' | 'PET' | 'MAMMOGRAPHY' | 'DEXA' | 'ECHO';

export interface LabResultDto {
  resultId:        number;
  patientSystemId: string;
  patientName:     string | null;
  doctorSystemId:  string;
  doctorName:      string | null;
  consultationId:  number | null;
  testName:        string;
  testType:        LabTestType;
  resultValue:     string | null;
  unit:            string | null;
  referenceRange:  string | null;
  /** JSON text of an object keyed by sub-test. */
  resultData:      string | null;
  status:          ResultStatus;
  isCritical:      boolean;
  /** Date only, YYYY-MM-DD. */
  testDate:        string;
  reviewedBy:      string | null;
  reviewedByName:  string | null;
  /** Clinic wall-clock date-time. */
  reviewedAt:      string | null;
  reviewNotes:     string | null;
  documentId:      number | null;
  fileUrl:         null;
  createdAt:       string;
}

export interface ImagingResultDto {
  imagingId:         number;
  patientSystemId:   string;
  patientName:       string | null;
  doctorSystemId:    string;
  doctorName:        string | null;
  consultationId:    number | null;
  imagingType:       ImagingType;
  bodyPart:          string | null;
  radiologistReport: string | null;
  findings:          string | null;
  impression:        string | null;
  status:            ResultStatus;
  isCritical:        boolean;
  /** Date only, YYYY-MM-DD. */
  imageDate:         string;
  reviewedBy:        string | null;
  reviewedByName:    string | null;
  reviewedAt:        string | null;
  reviewNotes:       string | null;
  documentId:        number | null;
  fileUrl:           null;
  thumbnailUrl:      null;
  createdAt:         string;
}

export type MedicalRecordType =
  | 'CONSULT' | 'LAB' | 'IMAGING' | 'PRESCRIPTION' | 'REFERRAL' | 'DISCHARGE' | 'NOTE' | 'OPERATION';
export type MedicalRecordSource = 'MANUAL' | 'CONSULTATION';

export interface IcdCodeDto {
  code:        string;
  description: string;
  verified:    boolean;
}

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
  icdCodeList:     IcdCodeDto[];
  source:          MedicalRecordSource;
  isDeleted:       boolean;
  deletedAt:       string | null;
  /** Clinic wall-clock date-time. */
  createdAt:       string;
  /** Only meaningful as `updatedAt !== createdAt` (edited). */
  updatedAt:       string;
}

export type ConsultationStatus = 'DRAFT' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';

export interface ConsultationDto {
  consultationId:          number;
  appointmentId:           number;
  appointmentType:         'IN_PERSON' | 'VIDEO' | 'PHONE' | null;
  appointmentStatus:       'SCHEDULED' | 'RESCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW' | null;
  patientSystemId:         string;
  /** Always null; names come from the caregiver's own list of loved ones. */
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
  startedAt:               string | null;
  completedAt:             string | null;
  createdAt:               string;
  /** The consultation's medical record; opening it needs the records permission. */
  medicalRecordId:         number | null;
}

export type PrescriptionStatus = 'ACTIVE' | 'ON_HOLD' | 'COMPLETED' | 'CANCELLED' | 'EXPIRED';
export type PrescriptionRoute = 'ORAL' | 'TOPICAL' | 'INJECTION' | 'INHALED' | 'SUBLINGUAL' | 'RECTAL' | 'IV';

export interface PrescriptionDto {
  prescriptionId:      number;
  patientSystemId:     string;
  doctorSystemId:      string;
  doctorName:          string | null;
  consultationId:      number | null;
  medicationName:      string;
  genericName:         string | null;
  dosage:              string;
  frequency:           string;
  route:               PrescriptionRoute;
  quantity:            string | null;
  refillsAllowed:      number;
  specialInstructions: string | null;
  status:              PrescriptionStatus;
  /** EXPIRED once the expiry date has passed, otherwise equal to `status`. Badges and filters use this. */
  effectiveStatus:     PrescriptionStatus;
  prescribedDate:      string;
  expiryDate:          string | null;
  createdAt:           string;
}

// ─── API calls ────────────────────────────────────────────────────────────────

export interface ResultListFilter {
  status?:   ResultStatus;
  /** Only results flagged critical. */
  critical?: boolean;
}

/** GET /api/lab-results?patientSystemId= (newest test date first; cancelled results are left out). */
export function listDependentLabResults(
  patientSystemId: string, filter: ResultListFilter, page: number, size: number,
): Promise<PageResponse<LabResultDto>> {
  return fetchPage<LabResultDto>('/api/lab-results', page, size, {
    patientSystemId, status: filter.status, critical: filter.critical ? true : undefined,
  });
}

/** GET /api/lab-results/{id} */
export async function getDependentLabResult(resultId: number): Promise<LabResultDto> {
  const { data } = await api.get<LabResultDto>(`/api/lab-results/${resultId}`);
  return data;
}

/** GET /api/imaging-results?patientSystemId= (newest image date first; cancelled results are left out). */
export function listDependentImagingResults(
  patientSystemId: string, filter: ResultListFilter, page: number, size: number,
): Promise<PageResponse<ImagingResultDto>> {
  return fetchPage<ImagingResultDto>('/api/imaging-results', page, size, {
    patientSystemId, status: filter.status, critical: filter.critical ? true : undefined,
  });
}

/** GET /api/imaging-results/{id} */
export async function getDependentImagingResult(imagingId: number): Promise<ImagingResultDto> {
  const { data } = await api.get<ImagingResultDto>(`/api/imaging-results/${imagingId}`);
  return data;
}

/** GET /api/medical-records?patientSystemId= (newest first). */
export function listDependentMedicalRecords(
  patientSystemId: string, filter: { type?: MedicalRecordType }, page: number, size: number,
): Promise<PageResponse<MedicalRecordDto>> {
  return fetchPage<MedicalRecordDto>('/api/medical-records', page, size, { patientSystemId, type: filter.type });
}

/** GET /api/medical-records/{id} */
export async function getDependentMedicalRecord(recordId: number): Promise<MedicalRecordDto> {
  const { data } = await api.get<MedicalRecordDto>(`/api/medical-records/${recordId}`);
  return data;
}

/**
 * GET /api/consultations?patientSystemId=&status=COMPLETED (newest first). Caregivers also receive cancelled
 * consultations from the API; the caregiver screens list completed ones only.
 */
export function listDependentConsultations(
  patientSystemId: string, page: number, size: number,
): Promise<PageResponse<ConsultationDto>> {
  return fetchPage<ConsultationDto>('/api/consultations', page, size, { patientSystemId, status: 'COMPLETED' });
}

/** GET /api/consultations/{id} */
export async function getDependentConsultation(consultationId: number): Promise<ConsultationDto> {
  const { data } = await api.get<ConsultationDto>(`/api/consultations/${consultationId}`);
  return data;
}

/** GET /api/prescriptions?patientSystemId= (newest first). `status` matches the effective status. */
export function listDependentPrescriptions(
  patientSystemId: string, filter: { status?: PrescriptionStatus }, page: number, size: number,
): Promise<PageResponse<PrescriptionDto>> {
  return fetchPage<PrescriptionDto>('/api/prescriptions', page, size, { patientSystemId, status: filter.status });
}

// ─── Per-loved-one paged loading ──────────────────────────────────────────────

/** The fields of a loved one the hook reads; `CaregiverDependent` satisfies it. */
export interface DependentRef extends CaregiverGrant {
  patientSystemId: string;
}

/**
 * - `loading`: the first page is being fetched.
 * - `ready`: rows are loaded (possibly none).
 * - `not-shared`: the grant does not include this permission; nothing was requested.
 * - `ended`: the grant is no longer active (expired or revoked); nothing was requested.
 * - `denied`: the server refused the request (403).
 * - `failed`: the request failed for another reason; `error` says why and `retry` asks again.
 */
export type DependentRecordsStatus = 'loading' | 'ready' | 'not-shared' | 'ended' | 'denied' | 'failed';

export interface DependentRecordsGroup<T, D extends DependentRef = DependentRef> {
  dependent:       D;
  patientSystemId: string;
  status:          DependentRecordsStatus;
  /** Rows loaded so far, in server order (newest first). */
  items:           T[];
  /** Rows on the server for this loved one and filter. */
  total:           number;
  hasMore:         boolean;
  loadingMore:     boolean;
  /** `failed`: why the first page could not be loaded. `ready`: why the last "Load more" failed. */
  error:           string | null;
}

export interface DependentRecordsState<T, D extends DependentRef = DependentRef> {
  /** One entry per loved one passed in, in the same order. */
  groups:   DependentRecordsGroup<T, D>[];
  /** True while any loved one's first page is loading. */
  loading:  boolean;
  /** Rows on the server across the loved ones that were loaded. */
  total:    number;
  /** Loads everything again from the first page. */
  refetch:  () => void;
  /** Loads one loved one's first page again (after a failure). */
  retry:    (patientSystemId: string) => void;
  /** Fetches one loved one's next page. */
  loadMore: (patientSystemId: string) => void;
}

export interface DependentRecordsOptions<T> {
  /** Rows per request and loved one, 1 to 100. Default 20. */
  pageSize?:     number;
  /** Shown when a request fails without a message from the server. */
  errorMessage?: string;
  /** Row identity, so a row that shifts onto the next page between two requests is not listed twice. */
  keyOf?:        (item: T) => string | number;
}

interface Slot<T> {
  phase:       'loading' | 'ready' | 'denied' | 'failed';
  items:       T[];
  nextPage:    number;
  totalPages:  number;
  total:       number;
  loadingMore: boolean;
  error:       string | null;
}

interface Inner<T> {
  /** The filter these slots were loaded for. */
  filterKey: string;
  slots:     Record<string, Slot<T>>;
}

function loadingSlot<T>(): Slot<T> {
  return { phase: 'loading', items: [], nextPage: 0, totalPages: 0, total: 0, loadingMore: false, error: null };
}

function refusedSlot<T>(): Slot<T> {
  return { phase: 'denied', items: [], nextPage: 0, totalPages: 0, total: 0, loadingMore: false, error: null };
}

function appendUnique<T>(existing: T[], incoming: T[], keyOf?: (item: T) => string | number): T[] {
  if (!keyOf) return existing.concat(incoming);
  const seen = new Set(existing.map(keyOf));
  const out = existing.slice();
  for (const item of incoming) {
    const key = keyOf(item);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(item);
  }
  return out;
}

type Access = 'allowed' | 'not-shared' | 'ended';

function accessOf(dependent: DependentRef, flag: CaregiverFlag): Access {
  if (!isGrantActive(dependent)) return 'ended';
  return effectivePermission(dependent, flag) ? 'allowed' : 'not-shared';
}

const ID_SEPARATOR = '|';

/**
 * Loads one kind of record for each loved one the caregiver may see it for, a page at a time.
 *
 * ```ts
 * const labs = useDependentRecords(dependents, 'canViewLabResults',
 *   (patient, page, size) => listDependentLabResults(patient, { status }, page, size), [status]);
 * ```
 *
 * - `flag` is the permission the record needs; it is evaluated with `effectivePermission`, so a FULL_ACCESS grant
 *   counts and an expired one does not.
 * - `deps` are the filters `fetchPageFor` closes over (primitive values). A change loads every loved one again
 *   from the first page. A change in the list of loved ones only loads the ones that are new.
 * - The first pages are requested together and settle together (`Promise.allSettled`): one loved one's failure or
 *   refusal never hides another's rows.
 */
export function useDependentRecords<T, D extends DependentRef = DependentRef>(
  dependents: readonly D[],
  flag: CaregiverFlag,
  fetchPageFor: (patientSystemId: string, page: number, size: number) => Promise<PageResponse<T>>,
  deps: DependencyList,
  options: DependentRecordsOptions<T> = {},
): DependentRecordsState<T, D> {
  const pageSize = Math.min(100, Math.max(1, Math.floor(options.pageSize ?? DEFAULT_PAGE_SIZE)));

  const access = dependents.map(d => accessOf(d, flag));
  const idsKey = dependents
    .filter((_, i) => access[i] === 'allowed')
    .map(d => d.patientSystemId)
    .join(ID_SEPARATOR);
  const filterKey = JSON.stringify([flag, pageSize, ...deps]);

  const [state, setState] = useState<Inner<T>>(() => ({ filterKey, slots: {} }));
  const [tick, setTick] = useState(0);

  const fetchRef = useRef(fetchPageFor);
  fetchRef.current = fetchPageFor;
  const keyOfRef = useRef(options.keyOf);
  keyOfRef.current = options.keyOf;
  const fallbackRef = useRef(options.errorMessage);
  fallbackRef.current = options.errorMessage;
  const stateRef = useRef(state);
  stateRef.current = state;

  // Bumped whenever everything is loaded again; a response from an older epoch is dropped.
  const epoch = useRef(0);
  // Latest request number per loved one; an older response for the same loved one is dropped.
  const sequence = useRef<Record<string, number>>({});
  const busy = useRef<Set<string>>(new Set());
  const lastLoad = useRef<{ filterKey: string; tick: number } | null>(null);

  const patchSlot = useCallback((id: string, change: (slot: Slot<T>) => Slot<T>) => {
    setState(prev => (prev.slots[id] ? { ...prev, slots: { ...prev.slots, [id]: change(prev.slots[id]) } } : prev));
  }, []);

  const loadFirstPages = useCallback((ids: string[]) => {
    const startedIn = epoch.current;
    const tokens = ids.map(id => {
      sequence.current[id] = (sequence.current[id] ?? 0) + 1;
      busy.current.add(id);
      return sequence.current[id];
    });
    Promise.allSettled(ids.map(id => fetchRef.current(id, 0, pageSize))).then(settled => {
      if (startedIn !== epoch.current) return;
      const loaded: Record<string, Slot<T>> = {};
      settled.forEach((result, i) => {
        const id = ids[i];
        if (sequence.current[id] !== tokens[i]) return;
        busy.current.delete(id);
        if (result.status === 'fulfilled') {
          const page = result.value;
          loaded[id] = {
            phase: 'ready',
            items: appendUnique<T>([], page.content ?? [], keyOfRef.current),
            nextPage: 1, totalPages: page.totalPages ?? 0, total: page.totalElements ?? 0,
            loadingMore: false, error: null,
          };
        } else {
          const failure = apiError(result.reason, fallbackRef.current);
          loaded[id] = failure.status === 403
            ? refusedSlot<T>()
            : { ...loadingSlot<T>(), phase: 'failed', error: failure.message };
        }
      });
      setState(prev => {
        const slots = { ...prev.slots };
        // A loved one who left the list while the request ran has no slot any more and gets none back.
        for (const id of Object.keys(loaded)) if (slots[id]) slots[id] = loaded[id];
        return { ...prev, slots };
      });
    });
  }, [pageSize]);

  useEffect(() => {
    const ids = idsKey ? idsKey.split(ID_SEPARATOR) : [];
    const previous = lastLoad.current;
    const startOver = previous === null || previous.filterKey !== filterKey || previous.tick !== tick;
    lastLoad.current = { filterKey, tick };

    if (startOver) {
      epoch.current += 1;
      busy.current.clear();
      const slots: Record<string, Slot<T>> = {};
      for (const id of ids) slots[id] = loadingSlot<T>();
      setState({ filterKey, slots });
      if (ids.length > 0) loadFirstPages(ids);
      return;
    }

    // Same filter, another set of loved ones: keep what is loaded, load the new ones, forget the removed ones.
    const current = stateRef.current.slots;
    const added = ids.filter(id => !current[id]);
    const removed = Object.keys(current).filter(id => !ids.includes(id));
    if (added.length === 0 && removed.length === 0) return;
    for (const id of removed) {
      sequence.current[id] = (sequence.current[id] ?? 0) + 1;
      busy.current.delete(id);
    }
    setState(prev => {
      const slots = { ...prev.slots };
      for (const id of removed) delete slots[id];
      for (const id of added) slots[id] = loadingSlot<T>();
      return { ...prev, slots };
    });
    if (added.length > 0) loadFirstPages(added);
  }, [filterKey, idsKey, tick, loadFirstPages]);

  const refetch = useCallback(() => setTick(t => t + 1), []);

  const retry = useCallback((id: string) => {
    if (busy.current.has(id) || !stateRef.current.slots[id]) return;
    patchSlot(id, () => loadingSlot<T>());
    loadFirstPages([id]);
  }, [loadFirstPages, patchSlot]);

  const loadMore = useCallback((id: string) => {
    const slot = stateRef.current.slots[id];
    if (!slot || slot.phase !== 'ready' || busy.current.has(id) || slot.nextPage >= slot.totalPages) return;
    const startedIn = epoch.current;
    sequence.current[id] = (sequence.current[id] ?? 0) + 1;
    const token = sequence.current[id];
    const stale = () => startedIn !== epoch.current || sequence.current[id] !== token;
    const pageToLoad = slot.nextPage;
    busy.current.add(id);
    patchSlot(id, s => ({ ...s, loadingMore: true, error: null }));
    fetchRef.current(id, pageToLoad, pageSize)
      .then(page => {
        if (stale()) return;
        patchSlot(id, s => ({
          ...s,
          items: appendUnique(s.items, page.content ?? [], keyOfRef.current),
          loadingMore: false,
          nextPage: pageToLoad + 1,
          totalPages: page.totalPages ?? s.totalPages,
          total: page.totalElements ?? s.total,
        }));
      })
      .catch(e => {
        if (stale()) return;
        const failure = apiError(e, fallbackRef.current);
        // Access was withdrawn since the first page: nothing of this loved one stays on screen.
        // Otherwise the rows stay and pressing "Load more" again retries the same page.
        patchSlot(id, s => (failure.status === 403 ? refusedSlot<T>() : { ...s, loadingMore: false, error: failure.message }));
      })
      .finally(() => { if (!stale()) busy.current.delete(id); });
  }, [pageSize, patchSlot]);

  // Until the effect has reset the state, slots loaded for another filter are stale: report "loading" instead.
  const owns = state.filterKey === filterKey;
  const groups = dependents.map((dependent, i): DependentRecordsGroup<T, D> => {
    const base = {
      dependent, patientSystemId: dependent.patientSystemId,
      items: [] as T[], total: 0, hasMore: false, loadingMore: false, error: null,
    };
    if (access[i] !== 'allowed') return { ...base, status: access[i] === 'ended' ? 'ended' : 'not-shared' };
    const slot = owns ? state.slots[dependent.patientSystemId] : undefined;
    if (!slot || slot.phase === 'loading') return { ...base, status: 'loading' };
    if (slot.phase === 'denied') return { ...base, status: 'denied' };
    if (slot.phase === 'failed') return { ...base, status: 'failed', error: slot.error };
    return {
      ...base, status: 'ready',
      items: slot.items, total: slot.total, hasMore: slot.nextPage < slot.totalPages,
      loadingMore: slot.loadingMore, error: slot.error,
    };
  });

  return {
    groups,
    loading: groups.some(g => g.status === 'loading'),
    total:   groups.reduce((sum, g) => sum + g.total, 0),
    refetch,
    retry,
    loadMore,
  };
}
