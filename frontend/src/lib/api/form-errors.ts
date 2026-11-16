import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';
import type { ApiError } from './errors';

/**
 * Puts the server's field errors onto a react-hook-form form (CONTRACTS 12.4).
 *
 * `fields` lists the form's field names; pass a map when a backend field name differs from the form's
 * (`{ scheduledAt: 'scheduled_date' }`). Nested backend names such as "items[0].quantity" are matched on
 * their last segment too. Returns the message to show in `FormError`: null when every server error landed on a
 * field, otherwise the error's own sentence.
 */
export function applyFieldErrors<T extends FieldValues>(
  error: ApiError,
  setError: UseFormSetError<T>,
  fields: readonly Path<T>[] | Partial<Record<string, Path<T>>>,
): string | null {
  const serverErrors = error.fieldErrors;
  if (!serverErrors) return error.message;

  const lookup = (name: string): Path<T> | undefined => {
    if (Array.isArray(fields)) return (fields as readonly Path<T>[]).find(f => f === name);
    return (fields as Partial<Record<string, Path<T>>>)[name];
  };

  let unmatched = 0;
  let first = true;
  for (const [name, message] of Object.entries(serverErrors)) {
    const target = lookup(name) ?? lookup(name.split('.').pop() ?? name);
    if (!target) { unmatched += 1; continue; }
    setError(target, { type: 'server', message }, { shouldFocus: first });
    first = false;
  }
  return unmatched > 0 ? error.message : null;
}
