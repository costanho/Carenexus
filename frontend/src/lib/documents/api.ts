import { apiError, apiErrorStatus, type ApiError, isNetworkError } from '@/lib/api/errors';
import { IDEMPOTENCY_HEADER } from '@/lib/api/idempotency';
import { DEFAULT_PAGE_SIZE, fetchPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import { api } from '@/lib/auth/auth.interceptor';
import {
  DOCUMENT_MAX_BYTES, type DocumentDto, type DocumentListQuery, FILE_TOO_LARGE_MESSAGE, type UploadDocumentInput,
} from './types';

// Typed client for /api/documents (api/be-documents.md). One function per endpoint. Every request goes through
// the shared axios instance, so the bearer token is added by its interceptor and never appears in a URL.

const BASE = '/api/documents';

/** A file transfer can take far longer than the 15 s the shared instance allows for JSON calls. */
const TRANSFER_TIMEOUT_MS = 5 * 60_000;

export interface UploadOptions {
  /** One key per chosen file: reused when that upload is retried, never reused for another file. */
  idempotencyKey: string;
  /** Called with the fraction sent so far, 0 to 1. */
  onProgress?: (fraction: number) => void;
  /** Aborts the request (the Cancel button of the upload dialog). */
  signal?: AbortSignal;
}

function fileNameOf(input: UploadDocumentInput): string {
  const given = input.fileName?.trim();
  if (given) return given;
  const own = (input.file as { name?: unknown }).name;
  return typeof own === 'string' && own.trim() ? own : 'document';
}

/** The multipart body of POST /api/documents: absent optional parts are left out, never sent as empty strings. */
export function buildUploadForm(input: UploadDocumentInput): FormData {
  const form = new FormData();
  form.append('patientSystemId', input.patientSystemId.trim());
  form.append('documentType', input.documentType);
  form.append('title', input.title.trim());
  const description = input.description?.trim();
  if (description) form.append('description', description);
  if (input.linkType) {
    form.append('linkType', input.linkType);
    if (typeof input.linkId === 'number' && Number.isInteger(input.linkId) && input.linkId >= 1) {
      form.append('linkId', String(input.linkId));
    }
  }
  form.append('file', input.file, fileNameOf(input));
  return form;
}

/**
 * POST /api/documents. Uploads one file into a patient's record and returns its metadata. 201 (stored) and 200
 * (the server replayed an earlier upload with the same key) are the same result for the caller.
 */
export async function uploadDocument(input: UploadDocumentInput, options: UploadOptions): Promise<DocumentDto> {
  const { data } = await api.post<DocumentDto>(BASE, buildUploadForm(input), {
    headers: {
      // The shared instance defaults to application/json, which would make axios serialise the form as JSON.
      // Naming multipart here keeps the FormData intact; in the browser axios then drops the header again so
      // the browser writes it with the boundary.
      'Content-Type': 'multipart/form-data',
      [IDEMPOTENCY_HEADER]: options.idempotencyKey,
    },
    timeout: TRANSFER_TIMEOUT_MS,
    signal: options.signal,
    onUploadProgress: event => {
      if (!options.onProgress) return;
      const total = event.total ?? input.file.size;
      if (total > 0) options.onProgress(Math.min(1, Math.max(0, event.loaded / total)));
    },
  });
  return data;
}

/** GET /api/documents/patient/{patientSystemId}: a patient's documents, newest first. */
export function listDocuments(
  patientSystemId: string,
  query: DocumentListQuery = {},
): Promise<PageResponse<DocumentDto>> {
  const linkId = query.linkType ? query.linkId : undefined; // the server refuses linkId without linkType
  return fetchPage<DocumentDto>(
    `${BASE}/patient/${encodeURIComponent(patientSystemId)}`,
    query.page ?? 0,
    query.size ?? DEFAULT_PAGE_SIZE,
    { type: query.type, linkType: query.linkType, linkId },
  );
}

/** GET /api/documents/{id}: metadata of one document. 404 when it does not exist or was deleted. */
export async function getDocument(documentId: number): Promise<DocumentDto> {
  const { data } = await api.get<DocumentDto>(`${BASE}/${documentId}`);
  return data;
}

/**
 * A failed blob request carries its JSON error envelope as a Blob. Parse it in place so `apiError` can read
 * the server's message and code, then rethrow the same error.
 */
async function rethrowWithParsedBody(e: unknown): Promise<never> {
  const response = (e as { response?: { data?: unknown } } | null)?.response;
  if (response && typeof Blob !== 'undefined' && response.data instanceof Blob) {
    try {
      const text = await response.data.text();
      response.data = text ? JSON.parse(text) : {};
    } catch {
      response.data = {};
    }
  }
  throw e;
}

/** GET /api/documents/{id}/download: the file's bytes. The only way to obtain a file. */
export async function downloadDocument(documentId: number): Promise<Blob> {
  try {
    const { data } = await api.get<Blob>(`${BASE}/${documentId}/download`, {
      responseType: 'blob',
      timeout: TRANSFER_TIMEOUT_MS,
    });
    return data;
  } catch (e) {
    return rethrowWithParsedBody(e);
  }
}

/**
 * DELETE /api/documents/{id}. Resolves on 204 and also on 404: a document that is already gone is what the
 * caller asked for, and the list is refreshed either way.
 */
export async function deleteDocument(documentId: number): Promise<void> {
  try {
    await api.delete(`${BASE}/${documentId}`);
  } catch (e) {
    if (apiErrorStatus(e) === 404) return;
    throw e;
  }
}

/** True when the request was aborted by the caller (the Cancel button), which is not a failure to report. */
export function isCancelledUpload(e: unknown): boolean {
  const err = e as { code?: unknown; name?: unknown } | null;
  return err?.code === 'ERR_CANCELED' || err?.name === 'CanceledError' || err?.name === 'AbortError';
}

/**
 * What to show for a failed upload. FILE_REJECTED and validation messages are the server's own sentences. An
 * over-size body is answered with 413, or the connection is closed before any answer arrives; both read as
 * "larger than 10 MB".
 */
export function uploadError(e: unknown, file?: { size: number } | null): ApiError {
  const failure = apiError(e, 'The document could not be uploaded. Please try again.');
  if (failure.status === 413) return { ...failure, message: FILE_TOO_LARGE_MESSAGE };
  if (failure.status === 0 && isNetworkError(e) && file && file.size > DOCUMENT_MAX_BYTES) {
    return { ...failure, message: FILE_TOO_LARGE_MESSAGE };
  }
  return failure;
}
