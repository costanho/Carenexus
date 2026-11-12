import { useCallback, useEffect, useRef, useState } from 'react';
import { apiError, type ApiError, apiErrorStatus } from '@/lib/api/errors';
import {
  deleteDocument, getDocument, isCancelledUpload, listDocuments, uploadDocument, uploadError,
} from '@/lib/documents/api';
import { saveDocument } from '@/lib/documents/download';
import type {
  DocumentDto, DocumentLinkType, DocumentType, UploadDocumentInput,
} from '@/lib/documents/types';
import { type PagedState, usePaged } from './use-paged';
import { type ResourceState, useResource } from './use-resource';

// Documents of a patient (api/be-documents.md): the paged list with type and link filters, one document,
// the documents attached to an item, upload with progress, authenticated download and delete.

export interface DocumentFilters {
  type?:     DocumentType | null;
  linkType?: DocumentLinkType | null;
  /** Only together with `linkType`. */
  linkId?:   number | null;
}

export interface DocumentsOptions {
  /** When false nothing is requested (for example while a panel is gated off). Default true. */
  enabled?:  boolean;
  /** Rows per request. Default 20. */
  pageSize?: number;
}

/**
 * A patient's documents, newest first, page by page. Nothing is requested until `patientSystemId` is known.
 * Changing the patient or a filter starts again from the first page; `refetch` reloads after an upload or delete.
 */
export function useDocuments(
  patientSystemId: string | null | undefined,
  filters: DocumentFilters = {},
  options: DocumentsOptions = {},
): PagedState<DocumentDto> {
  const patient  = patientSystemId?.trim() || null;
  const type     = filters.type ?? undefined;
  const linkType = filters.linkType ?? undefined;
  const linkId   = linkType && typeof filters.linkId === 'number' ? filters.linkId : undefined;

  return usePaged<DocumentDto>(
    (page, size) => listDocuments(patient ?? '', { type, linkType, linkId, page, size }),
    [patient, type, linkType, linkId],
    {
      enabled: !!patient && (options.enabled ?? true),
      pageSize: options.pageSize,
      keyOf: doc => doc.documentId,
      errorMessage: 'The documents could not be loaded. Please try again.',
    },
  );
}

/** Metadata of one document (the notification deep link). `errorStatus` 404 means it was deleted. */
export function useDocument(
  documentId: number | null | undefined,
  options: { enabled?: boolean } = {},
): ResourceState<DocumentDto> {
  const id = typeof documentId === 'number' && Number.isInteger(documentId) && documentId > 0 ? documentId : null;
  return useResource<DocumentDto>(
    () => getDocument(id ?? 0),
    [id],
    { enabled: id !== null && (options.enabled ?? true), errorMessage: 'The document could not be loaded. Please try again.' },
  );
}

/** The most attachments one item is expected to carry; the list endpoint's largest page. */
const LINKED_PAGE_SIZE = 100;

export interface LinkedDocumentsOptions {
  /**
   * A document the item references by id (a lab or imaging result's own `documentId`). It is read with
   * GET /api/documents/{id} and listed first; a reference to a deleted document (404) is skipped.
   */
  documentId?: number | null;
  /** When false nothing is requested. Default true. */
  enabled?:    boolean;
}

/**
 * The documents attached to one item (a result, a record, a consultation, a claim):
 * GET /api/documents/patient/{id}?linkType=&linkId=&size=100, plus the item's own document reference when given.
 */
export function useLinkedDocuments(
  patientSystemId: string | null | undefined,
  linkType: DocumentLinkType,
  linkId: number | null | undefined,
  options: LinkedDocumentsOptions = {},
): ResourceState<DocumentDto[]> {
  const patient = patientSystemId?.trim() || null;
  const link    = typeof linkId === 'number' && Number.isInteger(linkId) && linkId > 0 ? linkId : null;
  const ownId   = typeof options.documentId === 'number' && options.documentId > 0 ? options.documentId : null;

  return useResource<DocumentDto[]>(
    async () => {
      const [linked, own] = await Promise.all([
        link !== null && patient
          ? listDocuments(patient, { linkType, linkId: link, size: LINKED_PAGE_SIZE }).then(page => page.content)
          : Promise.resolve<DocumentDto[]>([]),
        ownId !== null
          ? getDocument(ownId).catch(e => {
              if (apiErrorStatus(e) === 404) return null; // the referenced document was deleted
              throw e;
            })
          : Promise.resolve(null),
      ]);
      if (!own || linked.some(doc => doc.documentId === own.documentId)) return linked;
      return [own, ...linked];
    },
    [patient, linkType, link, ownId],
    {
      enabled: (options.enabled ?? true) && ((link !== null && !!patient) || ownId !== null),
      errorMessage: 'The attachments could not be loaded. Please try again.',
    },
  );
}

// ─── Upload ───────────────────────────────────────────────────────────────────

export type UploadResult =
  | { ok: true; data: DocumentDto }
  /** `error` is null when the upload was cancelled, or ignored because another one is still running. */
  | { ok: false; error: ApiError | null; cancelled: boolean };

export interface UploadDocumentState {
  /**
   * Sends one file. `idempotencyKey` belongs to the chosen file: pass the same key when retrying that file and a
   * new one for another file. Never rejects: check `result.ok`.
   */
  upload:   (input: UploadDocumentInput, idempotencyKey: string) => Promise<UploadResult>;
  /** True while a file is being sent. */
  pending:  boolean;
  /** Fraction sent so far, 0 to 1. Reaches 1 before the server has finished storing the file. */
  progress: number;
  /** The sentence to show after a failed upload, cleared when the next one starts. */
  error:    string | null;
  /** The whole failure (status, code, fieldErrors), or null. */
  failure:  ApiError | null;
  /** Stops the upload that is in flight. The same key can be sent again afterwards. */
  cancel:   () => void;
  /** Clears `error` and `failure`. */
  reset:    () => void;
}

/** Uploads one document at a time with a progress fraction and a Cancel. */
export function useUploadDocument(): UploadDocumentState {
  const [pending, setPending]   = useState(false);
  const [progress, setProgress] = useState(0);
  const [failure, setFailure]   = useState<ApiError | null>(null);

  const running    = useRef(false);
  const controller = useRef<AbortController | null>(null);
  const mounted    = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      controller.current?.abort(); // leaving the screen stops a transfer nobody is waiting for
    };
  }, []);

  const upload = useCallback(async (input: UploadDocumentInput, idempotencyKey: string): Promise<UploadResult> => {
    if (running.current) return { ok: false, error: null, cancelled: false };
    running.current = true;
    const abort = typeof AbortController !== 'undefined' ? new AbortController() : null;
    controller.current = abort;
    setPending(true);
    setProgress(0);
    setFailure(null);
    try {
      const data = await uploadDocument(input, {
        idempotencyKey,
        signal: abort?.signal,
        onProgress: fraction => { if (mounted.current) setProgress(fraction); },
      });
      return { ok: true, data };
    } catch (e) {
      if (isCancelledUpload(e)) return { ok: false, error: null, cancelled: true };
      const error = uploadError(e, input.file);
      if (mounted.current) setFailure(error);
      return { ok: false, error, cancelled: false };
    } finally {
      running.current = false;
      controller.current = null;
      if (mounted.current) setPending(false);
    }
  }, []);

  const cancel = useCallback(() => controller.current?.abort(), []);
  const reset  = useCallback(() => setFailure(null), []);

  return { upload, pending, progress, error: failure?.message ?? null, failure, cancel, reset };
}

// ─── Download and delete ──────────────────────────────────────────────────────

/** A failed download or delete, with the document it was about. */
export interface DocumentActionFailure {
  document: Pick<DocumentDto, 'documentId' | 'title'>;
  error:    ApiError;
}

export interface DownloadDocumentState {
  /** Fetches the file with the session's token and opens the browser's save prompt. Resolves true when saved. */
  download:   (doc: Pick<DocumentDto, 'documentId' | 'title' | 'originalFilename'>) => Promise<boolean>;
  /** True while this document is being fetched. */
  isDownloading: (documentId: number) => boolean;
  failure:    DocumentActionFailure | null;
  reset:      () => void;
}

/** Authenticated download. Several documents can be fetched at once; a second press on one in flight is ignored. */
export function useDownloadDocument(): DownloadDocumentState {
  const [active, setActive]   = useState<ReadonlySet<number>>(() => new Set());
  const [failure, setFailure] = useState<DocumentActionFailure | null>(null);
  const inFlight = useRef(new Set<number>());
  const mounted  = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  const download = useCallback(async (doc: Pick<DocumentDto, 'documentId' | 'title' | 'originalFilename'>) => {
    if (inFlight.current.has(doc.documentId)) return false;
    inFlight.current.add(doc.documentId);
    setActive(new Set(inFlight.current));
    setFailure(prev => (prev?.document.documentId === doc.documentId ? null : prev));
    try {
      await saveDocument(doc);
      return true;
    } catch (e) {
      const error = apiError(e, 'The file could not be downloaded. Please try again.');
      if (mounted.current) setFailure({ document: { documentId: doc.documentId, title: doc.title }, error });
      return false;
    } finally {
      inFlight.current.delete(doc.documentId);
      if (mounted.current) setActive(new Set(inFlight.current));
    }
  }, []);

  const isDownloading = useCallback((documentId: number) => active.has(documentId), [active]);
  const reset = useCallback(() => setFailure(null), []);

  return { download, isDownloading, failure, reset };
}

export interface DeleteDocumentState {
  /** Deletes the document. Resolves true when it is gone (also when it had already been deleted). */
  remove:    (doc: Pick<DocumentDto, 'documentId' | 'title'>) => Promise<boolean>;
  /** The document being deleted, or null. */
  pendingId: number | null;
  failure:   DocumentActionFailure | null;
  reset:     () => void;
}

/** Soft delete of one document at a time. The caller confirms first and refreshes its list afterwards. */
export function useDeleteDocument(): DeleteDocumentState {
  const [pendingId, setPendingId] = useState<number | null>(null);
  const [failure, setFailure]     = useState<DocumentActionFailure | null>(null);
  const running = useRef(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  const remove = useCallback(async (doc: Pick<DocumentDto, 'documentId' | 'title'>) => {
    if (running.current) return false;
    running.current = true;
    setPendingId(doc.documentId);
    setFailure(null);
    try {
      await deleteDocument(doc.documentId);
      return true;
    } catch (e) {
      const error = apiError(e, 'The document could not be deleted. Please try again.');
      if (mounted.current) setFailure({ document: { documentId: doc.documentId, title: doc.title }, error });
      return false;
    } finally {
      running.current = false;
      if (mounted.current) setPendingId(null);
    }
  }, []);

  const reset = useCallback(() => setFailure(null), []);

  return { remove, pendingId, failure, reset };
}
