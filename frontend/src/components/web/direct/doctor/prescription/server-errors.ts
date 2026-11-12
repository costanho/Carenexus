import type { ApiError } from '@/lib/api/errors';

// Helpers for showing what the server said about a prescription request.

/**
 * Makes the server's validation errors usable by `applyFieldErrors`:
 * - indexed names ("scheduleTimes[2]", "times[0]") are folded onto their list field;
 * - a rule checked in the service arrives without an `errors` list, as the message "<field>: <text>"; when the field
 *   is one of `fields`, the text is put on it.
 */
export function withFieldErrors(error: ApiError, fields: readonly string[]): ApiError {
  if (error.fieldErrors) {
    const folded: Record<string, string> = {};
    for (const [name, message] of Object.entries(error.fieldErrors)) {
      const key = name.replace(/\[\d+\]$/, '');
      if (!(key in folded)) folded[key] = message;
    }
    return { ...error, fieldErrors: folded };
  }
  if (error.code !== 'VALIDATION_FAILED') return error;
  const match = /^([A-Za-z][A-Za-z0-9]*)(?:\[\d+\])?:\s*(.+)$/.exec(error.message);
  if (!match || !fields.includes(match[1])) return error;
  const text = match[2].charAt(0).toUpperCase() + match[2].slice(1);
  return { ...error, fieldErrors: { [match[1]]: text } };
}

/**
 * True when the server says the prescription is no longer what the screen shows: it is completed, cancelled or
 * expired (409), or it no longer exists (404). The list is refreshed instead of retrying.
 */
export function isStaleRowError(error: ApiError): boolean {
  return (error.status === 409 && error.code !== 'IDEMPOTENCY_IN_PROGRESS') || error.status === 404;
}
