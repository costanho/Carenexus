import { useEffect, useState } from 'react';
import { onSignedOut } from '@/lib/auth/auth.events';
import { getUser } from '@/lib/auth/auth.storage';
import { DOCTOR_NAME_FALLBACK, useDoctorName } from '@/lib/doctors/names';

/** Who is reading: a doctor or administrator, or the patient side (the patient or a caregiver). */
export type AlertViewer = 'clinician' | 'patient';

// The signed-in user's system id is read from the stored session once and shared by every row of a list.
let mine: Promise<string | null> | null = null;
onSignedOut(() => { mine = null; });

function loadMySystemId(): Promise<string | null> {
  if (!mine) {
    mine = getUser()
      .then(user => user?.system_id ?? null)
      .catch(() => null);
  }
  return mine;
}

/** The signed-in user's system id from the stored session, or null until it is read. */
export function useMySystemId(): string | null {
  const [id, setId] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    loadMySystemId().then(value => { if (!cancelled) setId(value); });
    return () => { cancelled = true; };
  }, []);
  return id;
}

/**
 * How to name the doctor behind a system id inside a sentence ("Acknowledged by ..."): "you" for the signed-in
 * doctor, the doctor's display name when the directory knows it, and a neutral phrase otherwise. Never the raw
 * system id. Null when there is no id.
 */
export function useClinicianName(systemId: string | null | undefined, viewer: AlertViewer): string | null {
  const me = useMySystemId();
  const id = (systemId ?? '').trim();
  const name = useDoctorName(id || null);
  if (!id) return null;
  if (viewer === 'clinician' && me && me === id) return 'you';
  if (name === DOCTOR_NAME_FALLBACK) return viewer === 'clinician' ? 'a doctor' : 'your doctor';
  return name;
}
