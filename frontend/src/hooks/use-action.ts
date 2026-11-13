import { useCallback, useEffect, useRef, useState } from 'react';
import { apiError, type ApiError } from '@/lib/api/errors';

// Runs one mutation (save, cancel, pay, upload) with a pending flag and an error to show (CONTRACTS 12.4).
// A second `run` while the first is still in flight is ignored, so a double click cannot submit twice.

export type ActionResult<R> =
  | { ok: true;  data: R }
  /** `error` is null when the call was ignored because another one is still running. */
  | { ok: false; error: ApiError | null };

export interface ActionOptions {
  /** Shown when the request fails without a message from the server. */
  errorMessage?: string;
}

export interface ActionState<A extends unknown[], R> {
  /** Runs the action. Never rejects: check `result.ok`. */
  run:     (...args: A) => Promise<ActionResult<R>>;
  /** True while a run is in flight; disable the submit control with it. */
  pending: boolean;
  /** The sentence to show after a failed run, cleared when the next run starts. */
  error:   string | null;
  /** The whole failure (status, code, fieldErrors) of the last run, or null. */
  failure: ApiError | null;
  /** Clears `error` and `failure` (for example when the form is edited or the dialog is reopened). */
  reset:   () => void;
}

/**
 * ```ts
 * const save = useAction((values: FormValues) => createReferral(values, key.current()));
 * const onSubmit = handleSubmit(async values => {
 *   const result = await save.run(values);
 *   if (result.ok) { key.renew(); onSaved(result.data); }
 *   else if (result.error) setFormError(applyFieldErrors(result.error, setError, FIELDS));
 * });
 * ```
 */
export function useAction<A extends unknown[], R>(
  fn: (...args: A) => Promise<R>,
  options: ActionOptions = {},
): ActionState<A, R> {
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<ApiError | null>(null);

  const fnRef = useRef(fn);
  fnRef.current = fn;
  const fallbackRef = useRef(options.errorMessage);
  fallbackRef.current = options.errorMessage;

  // The ref (not the state) is the double-submit guard: two clicks in one frame both see pending === false.
  const running = useRef(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  const run = useCallback(async (...args: A): Promise<ActionResult<R>> => {
    if (running.current) return { ok: false, error: null };
    running.current = true;
    setPending(true);
    setFailure(null);
    try {
      const data = await fnRef.current(...args);
      return { ok: true, data };
    } catch (e) {
      const error = apiError(e, fallbackRef.current);
      if (mounted.current) setFailure(error);
      return { ok: false, error };
    } finally {
      running.current = false;
      if (mounted.current) setPending(false);
    }
  }, []);

  const reset = useCallback(() => setFailure(null), []);

  return { run, pending, error: failure?.message ?? null, failure, reset };
}
