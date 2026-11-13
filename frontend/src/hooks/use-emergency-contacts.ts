import { useCallback } from 'react';
import { withIdempotencyKey } from '@/lib/api/idempotency';
import { api } from '@/lib/auth/auth.interceptor';
import { type EmergencyContact, toEmergencyContact } from './use-my-profile';
import { useResource } from './use-resource';

// The signed-in patient's own emergency contacts (be-profile-settings contract, section 5):
// GET / POST /api/profile/emergency-contacts and PUT / DELETE /api/profile/emergency-contacts/{contactId}.

export type { EmergencyContact } from './use-my-profile';

/** A patient can keep at most this many contacts; the next create answers 409. */
export const MAX_EMERGENCY_CONTACTS = 10;

/** POST body. `patientSystemId` is left out: for a patient caller the server uses the caller's own id. */
export interface CreateEmergencyContact {
  name:          string;
  relationship?: string;
  phone:         string;
  email?:        string;
  isPrimary?:    boolean;
}

/** PUT body: every field optional, omitted = unchanged; '' clears `relationship` and `email`. */
export interface UpdateEmergencyContact {
  name?:         string;
  relationship?: string;
  phone?:        string;
  email?:        string;
  isPrimary?:    boolean;
}

/** The order the server lists contacts in: the primary contact first, then oldest first. */
export function sortEmergencyContacts(list: readonly EmergencyContact[]): EmergencyContact[] {
  return list.slice().sort((a, b) => Number(b.isPrimary) - Number(a.isPrimary) || a.contactId - b.contactId);
}

/**
 * Puts a created or updated contact into the list. A patient has at most one primary contact: when the saved
 * contact is primary the server demoted the previous one in the same request, so the list does the same.
 */
export function withSavedContact(list: readonly EmergencyContact[], saved: EmergencyContact): EmergencyContact[] {
  const others = list
    .filter(c => c.contactId !== saved.contactId)
    .map(c => (saved.isPrimary && c.isPrimary ? { ...c, isPrimary: false } : c));
  return sortEmergencyContacts([...others, saved]);
}

/** GET /api/profile/emergency-contacts: the signed-in patient's contacts (a plain array, never paged). */
export async function listMyEmergencyContacts(): Promise<EmergencyContact[]> {
  const { data } = await api.get('/api/profile/emergency-contacts');
  return sortEmergencyContacts(Array.isArray(data) ? data.map(toEmergencyContact) : []);
}

/**
 * POST /api/profile/emergency-contacts. Pass the form's idempotency key (one per form open, reused on retry)
 * so a repeated submit answers with the contact that was already created instead of adding a second one.
 */
export async function createEmergencyContact(body: CreateEmergencyContact, idempotencyKey: string): Promise<EmergencyContact> {
  const { data } = await api.post('/api/profile/emergency-contacts', body, withIdempotencyKey(idempotencyKey));
  return toEmergencyContact(data);
}

/** PUT /api/profile/emergency-contacts/{contactId}: partial update. */
export async function updateEmergencyContact(contactId: number, changes: UpdateEmergencyContact): Promise<EmergencyContact> {
  const { data } = await api.put(`/api/profile/emergency-contacts/${encodeURIComponent(String(contactId))}`, changes);
  return toEmergencyContact(data);
}

/** DELETE /api/profile/emergency-contacts/{contactId}: 204, the row is gone. */
export async function deleteEmergencyContact(contactId: number): Promise<void> {
  await api.delete(`/api/profile/emergency-contacts/${encodeURIComponent(String(contactId))}`);
}

export interface EmergencyContactsState {
  contacts:    EmergencyContact[];
  /** True until the first load has answered. */
  loading:     boolean;
  refreshing:  boolean;
  error:       string | null;
  errorStatus: number | null;
  refetch:     () => void;
  /** Each mutation resolves once the list on screen is updated and rejects with the request error. */
  create: (body: CreateEmergencyContact, idempotencyKey: string) => Promise<EmergencyContact>;
  update: (contactId: number, changes: UpdateEmergencyContact) => Promise<EmergencyContact>;
  remove: (contactId: number) => Promise<void>;
}

/** The signed-in patient's emergency contacts with create, update and delete. Only a PATIENT account has any. */
export function useEmergencyContacts(options: { enabled?: boolean } = {}): EmergencyContactsState {
  const { data, loading, refreshing, error, errorStatus, refetch, setData } = useResource<EmergencyContact[]>(
    listMyEmergencyContacts, [], { enabled: options.enabled ?? true, errorMessage: 'Failed to load emergency contacts.' },
  );

  const create = useCallback(async (body: CreateEmergencyContact, idempotencyKey: string) => {
    const saved = await createEmergencyContact(body, idempotencyKey);
    setData(prev => withSavedContact(prev ?? [], saved));
    return saved;
  }, [setData]);

  const update = useCallback(async (contactId: number, changes: UpdateEmergencyContact) => {
    const saved = await updateEmergencyContact(contactId, changes);
    setData(prev => withSavedContact(prev ?? [], saved));
    return saved;
  }, [setData]);

  const remove = useCallback(async (contactId: number) => {
    await deleteEmergencyContact(contactId);
    setData(prev => (prev ?? []).filter(c => c.contactId !== contactId));
  }, [setData]);

  return { contacts: data ?? [], loading, refreshing, error, errorStatus, refetch, create, update, remove };
}
