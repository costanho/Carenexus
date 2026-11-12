import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { doctorDisplayName } from '@/hooks/use-doctors';

// Doctor display names by system id (CONTRACTS 12.4). Clinical rows carry only the doctor's DOC-... id; the
// name comes from GET /api/doctors/profile/public/{id}. A doctor the directory does not list (deactivated,
// pending) or a failed lookup shows a neutral label, never the raw id.

export const DOCTOR_NAME_FALLBACK = 'Your doctor';

/** A failed lookup is retried after this long, so one bad request does not pin the fallback for the session. */
const RETRY_AFTER_MS = 60_000;

const names    = new Map<string, string>();           // resolved names
const failedAt = new Map<string, number>();           // id -> time of the last failed lookup
const inFlight = new Map<string, Promise<string>>();

function normalise(systemId: string | null | undefined): string {
  return (systemId ?? '').trim();
}

/** The name already known for this id, or null. Never triggers a request. */
export function cachedDoctorName(systemId: string | null | undefined): string | null {
  return names.get(normalise(systemId)) ?? null;
}

/**
 * Seeds the cache from rows that already carry names (the active-doctor directory, API responses with a
 * doctor name), so those ids are never looked up one by one.
 */
export function primeDoctorNames(
  doctors: readonly { doctor_system_id?: string | null; full_name?: string | null }[],
): void {
  for (const d of doctors) {
    const id = normalise(d.doctor_system_id);
    const name = (d.full_name ?? '').trim();
    if (id && name) names.set(id, name);
  }
}

/**
 * The doctor's display name ("Dr. First Last"). Resolves to "Your doctor" when the id is empty, the doctor is
 * not listed or the lookup fails. Never rejects and never resolves to the system id.
 */
export function resolveDoctorName(systemId: string | null | undefined): Promise<string> {
  const id = normalise(systemId);
  if (!id) return Promise.resolve(DOCTOR_NAME_FALLBACK);

  const known = names.get(id);
  if (known) return Promise.resolve(known);

  const failed = failedAt.get(id);
  if (failed !== undefined && Date.now() - failed < RETRY_AFTER_MS) return Promise.resolve(DOCTOR_NAME_FALLBACK);

  const pending = inFlight.get(id);
  if (pending) return pending;

  const request = api
    .get<{ firstName?: string | null; lastName?: string | null }>(`/api/doctors/profile/public/${encodeURIComponent(id)}`)
    .then(({ data }) => {
      const name = doctorDisplayName(data?.firstName, data?.lastName);
      if (!name) {
        failedAt.set(id, Date.now());
        return DOCTOR_NAME_FALLBACK;
      }
      names.set(id, name);
      failedAt.delete(id);
      return name;
    })
    .catch(() => {
      failedAt.set(id, Date.now());
      return DOCTOR_NAME_FALLBACK;
    })
    .finally(() => { inFlight.delete(id); });
  inFlight.set(id, request);
  return request;
}

/** Resolves several ids at once (duplicates and blanks are ignored). The map has an entry for every given id. */
export async function resolveDoctorNames(
  systemIds: readonly (string | null | undefined)[],
): Promise<Map<string, string>> {
  const ids = Array.from(new Set(systemIds.map(normalise).filter(Boolean)));
  const resolved = await Promise.all(ids.map(resolveDoctorName));
  return new Map(ids.map((id, i) => [id, resolved[i]] as const));
}

/**
 * The doctor's name for rendering. Shows `preferred` (a name the API row already carries) when given, the cached
 * name when known, and "Your doctor" until the lookup settles or when it fails.
 */
export function useDoctorName(systemId: string | null | undefined, preferred?: string | null): string {
  const id = normalise(systemId);
  const given = (preferred ?? '').trim();
  const [resolved, setResolved] = useState<{ id: string; name: string } | null>(null);

  useEffect(() => {
    if (!id || given) return;
    let cancelled = false;
    resolveDoctorName(id).then(name => { if (!cancelled) setResolved({ id, name }); });
    return () => { cancelled = true; };
  }, [id, given]);

  if (given) return given;
  if (resolved && resolved.id === id) return resolved.name;
  return names.get(id) ?? DOCTOR_NAME_FALLBACK;
}
