import type { ApiErrorBody } from './types';

// One place that turns a failed request into something a screen can show (CONTRACTS 12.4).
// Works on the axios error shape (`response.status`, `response.data`, `response.headers`) without importing axios,
// so it is safe to call with anything a `catch` receives.

export interface ApiError {
  /**
   * HTTP status, or 0 when there was no HTTP response: the server could not be reached (offline, timeout, CORS;
   * see `isNetworkError`) or the failure happened in the app before or after the request.
   */
  status:       number;
  /** Backend error code, for example VALIDATION_FAILED or RATE_LIMITED. */
  code?:        string;
  /** One sentence that is safe to show to the user. */
  message:      string;
  /** Field name to message, from the backend `errors` list (first message per field). */
  fieldErrors?: Record<string, string>;
  /** Seconds to wait before retrying, from the Retry-After header of a 429. */
  retryAfterSeconds?: number;
}

export const GENERIC_ERROR_MESSAGE = 'Something went wrong. Please try again.';
export const NETWORK_ERROR_MESSAGE = 'Unable to reach the server. Check your connection and try again.';

function responseOf(e: unknown): { status?: unknown; data?: unknown; headers?: unknown } | null {
  if (!e || typeof e !== 'object') return null;
  const response = (e as { response?: unknown }).response;
  return response && typeof response === 'object' ? response : null;
}

const TRANSPORT_CODES = new Set(['ERR_NETWORK', 'ECONNABORTED', 'ETIMEDOUT']);

/**
 * True when a request was sent and no response came back: offline, DNS, CORS, a timeout. False for an HTTP error
 * response, for a request the app cancelled, and for anything thrown by the app's own code (a failed client-side
 * check, a mapping error), which have nothing to do with the connection.
 */
export function isNetworkError(e: unknown): boolean {
  if (!e || typeof e !== 'object' || responseOf(e)) return false;
  const err = e as { code?: unknown; message?: unknown; request?: unknown; isAxiosError?: unknown };
  if (err.code === 'ERR_CANCELED') return false;
  if (typeof err.code === 'string' && TRANSPORT_CODES.has(err.code)) return true;
  if (err.message === 'Network Error') return true;
  return err.isAxiosError === true && err.request != null;
}

function bodyOf(data: unknown): ApiErrorBody {
  return data && typeof data === 'object' ? (data as ApiErrorBody) : {};
}

function headerValue(headers: unknown, name: string): string | null {
  if (!headers || typeof headers !== 'object') return null;
  const bag = headers as { get?: (n: string) => unknown } & Record<string, unknown>;
  const viaGet = typeof bag.get === 'function' ? bag.get(name) : undefined;
  const raw = viaGet ?? bag[name] ?? bag[name.toLowerCase()];
  return raw == null ? null : String(raw);
}

/** Seconds from a Retry-After header (delta-seconds or an HTTP date), or undefined when absent or unusable. */
function retryAfterSeconds(headers: unknown): number | undefined {
  const raw = headerValue(headers, 'Retry-After');
  if (!raw) return undefined;
  const asNumber = Number(raw);
  if (Number.isFinite(asNumber)) return asNumber > 0 ? Math.ceil(asNumber) : undefined;
  const at = Date.parse(raw);
  if (Number.isNaN(at)) return undefined;
  const seconds = Math.ceil((at - Date.now()) / 1000);
  return seconds > 0 ? seconds : undefined;
}

/** "Too many attempts. Try again in N minutes." (N rounded up; "in a few minutes" when the server gave no wait time). */
export function rateLimitMessage(retryAfter?: number): string {
  if (!retryAfter || retryAfter <= 0) return 'Too many attempts. Try again in a few minutes.';
  const minutes = Math.max(1, Math.ceil(retryAfter / 60));
  return `Too many attempts. Try again in ${minutes} ${minutes === 1 ? 'minute' : 'minutes'}.`;
}

function fieldErrorsOf(body: ApiErrorBody): Record<string, string> | undefined {
  if (!Array.isArray(body.errors) || body.errors.length === 0) return undefined;
  const out: Record<string, string> = {};
  for (const item of body.errors) {
    const field = typeof item?.field === 'string' ? item.field.trim() : '';
    const message = typeof item?.message === 'string' ? item.message.trim() : '';
    if (field && message && !(field in out)) out[field] = message;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

/**
 * Normalises anything thrown by a request into `{ status, code?, message, fieldErrors? }`.
 * The message is the backend's sentence when it sent one, the rate-limit wording for a 429, a connection
 * message when the request got no response (`isNetworkError`), and `fallback` otherwise, including for errors
 * that are not HTTP failures at all (thrown by a client-side check or while mapping a response).
 */
export function apiError(e: unknown, fallback: string = GENERIC_ERROR_MESSAGE): ApiError {
  const response = responseOf(e);
  if (!response) return { status: 0, message: isNetworkError(e) ? NETWORK_ERROR_MESSAGE : fallback };

  const status = typeof response.status === 'number' ? response.status : 0;
  const body = bodyOf(response.data);
  const code = typeof body.code === 'string' && body.code.trim() ? body.code.trim() : undefined;

  if (status === 429) {
    const wait = retryAfterSeconds(response.headers);
    return { status, code: code ?? 'RATE_LIMITED', message: rateLimitMessage(wait), retryAfterSeconds: wait };
  }

  const serverMessage = typeof body.message === 'string' ? body.message.trim() : '';
  return {
    status,
    code,
    message: serverMessage || fallback,
    fieldErrors: fieldErrorsOf(body),
  };
}

/**
 * The sentence to show for a failed request: the backend's `message` when present, the rate-limit wording for a
 * 429, otherwise `fallback` (also used when the server could not be reached, so callers keep their own wording).
 */
export function apiErrorMessage(e: unknown, fallback: string): string {
  const response = responseOf(e);
  if (!response) return fallback;
  if (response.status === 429) return rateLimitMessage(retryAfterSeconds(response.headers));
  const message = bodyOf(response.data).message;
  return typeof message === 'string' && message.trim() ? message : fallback;
}

/** HTTP status of a failed request, or 0 when there was no response. */
export function apiErrorStatus(e: unknown): number {
  const status = responseOf(e)?.status;
  return typeof status === 'number' ? status : 0;
}

/** True when the request was refused for lack of permission (403). */
export function isForbiddenError(e: unknown): boolean {
  return apiErrorStatus(e) === 403;
}

/** True when the resource does not exist, or the caller may not know that it exists (404). */
export function isNotFoundError(e: unknown): boolean {
  return apiErrorStatus(e) === 404;
}
