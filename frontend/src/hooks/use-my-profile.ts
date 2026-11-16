import { useCallback } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { getUser, saveUser } from '@/lib/auth/auth.storage';
import { type ResourceState, useResource } from './use-resource';

// The signed-in account's own profile (GET / PUT /api/profile), for every role. The patient and caregiver
// profile screens are built on it; `usePatientProfile` keeps its older snake_case view model on top of it.
// Types are the camelCase shapes of the API (be-profile-settings contract, section 11).

export type Gender       = 'MALE' | 'FEMALE' | 'OTHER';
export type BloodType    = 'A+' | 'A-' | 'B+' | 'B-' | 'AB+' | 'AB-' | 'O+' | 'O-';
export type HealthStatus = 'STABLE' | 'MONITOR' | 'CRITICAL';
export type AccountRole  = 'PATIENT' | 'DOCTOR' | 'CAREGIVER' | 'ADMIN';

export const GENDERS: readonly Gender[] = ['MALE', 'FEMALE', 'OTHER'];
export const BLOOD_TYPES: readonly BloodType[] = ['A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-'];

export interface EmergencyContact {
  contactId:       number;
  patientSystemId: string;
  name:            string;
  relationship:    string | null;
  phone:           string;
  email:           string | null;
  isPrimary:       boolean;
  /** Informational only; it grants nothing and the web form does not offer it. */
  isCaregiver:     boolean;
  /** Clinic wall-clock date-time. */
  createdAt:       string;
}

export interface UserProfile {
  userId:            number;
  /** The account's system id (PAT-…, DOC-…, CGV-…, ADM-…). */
  systemId:          string;
  firstName:         string;
  lastName:          string;
  phone:             string | null;
  /** Sign-in email. Read-only here: it changes only through the confirmed email-change flow. */
  email:             string | null;
  role:              AccountRole;
  isActive:          boolean;
  patientSystemId:   string | null;
  /** Date only, "YYYY-MM-DD". Never pass it through `new Date()`; use `@/lib/ui/date-only`. */
  dateOfBirth:       string | null;
  gender:            Gender | null;
  bloodType:         BloodType | null;
  /** A STRING holding a JSON array of strings; read it with `parseProfileList`. */
  allergies:         string | null;
  /** Same encoding as `allergies`. */
  chronicConditions: string | null;
  /** Set by the care team; never editable through the profile. */
  healthStatus:      HealthStatus | null;
  emergencyContacts: EmergencyContact[] | null;
  /** Caregiver accounts only: how the caregiver described themselves at registration; null when not recorded. */
  relationship:      string | null;
  /** Clinic wall-clock date-times. */
  createdAt:         string;
  updatedAt:         string;
}

/** PUT /api/profile. Send only the fields that changed. `healthStatus` and `email` are refused by the server. */
export interface UpdateUserProfile {
  firstName?:         string;
  lastName?:          string;
  phone?:             string;
  dateOfBirth?:       string;
  /** '' clears. */
  gender?:            Gender | '';
  /** '' clears ("Not known"). */
  bloodType?:         BloodType | '';
  /** `JSON.stringify(string[])`; "[]" clears. */
  allergies?:         string;
  /** `JSON.stringify(string[])`; "[]" clears. */
  chronicConditions?: string;
}

/** Reads the JSON-array string of `allergies` / `chronicConditions`. Never throws; anything else is an empty list. */
export function parseProfileList(raw: unknown): string[] {
  if (Array.isArray(raw)) return raw.map(String).map(s => s.trim()).filter(Boolean);
  if (typeof raw !== 'string' || !raw.trim()) return [];
  try {
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.map(String).map(s => s.trim()).filter(Boolean) : [];
  } catch {
    return [];
  }
}

function text(value: unknown): string {
  return value == null ? '' : String(value);
}

function textOrNull(value: unknown): string | null {
  const s = text(value).trim();
  return s ? s : null;
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T | null {
  const s = text(value).trim().toUpperCase();
  return (allowed as readonly string[]).includes(s) ? (s as T) : null;
}

/** The list fields arrive as a JSON-array string; an actual array (older responses) is re-encoded the same way. */
function listString(value: unknown): string | null {
  if (typeof value === 'string') return value;
  return Array.isArray(value) ? JSON.stringify(value.map(String)) : null;
}

const ROLES: readonly AccountRole[] = ['PATIENT', 'DOCTOR', 'CAREGIVER', 'ADMIN'];
const HEALTH: readonly HealthStatus[] = ['STABLE', 'MONITOR', 'CRITICAL'];

/** An `EmergencyContactResponse` as the screens use it. */
export function toEmergencyContact(raw: any): EmergencyContact {
  return {
    contactId:       Number(raw?.contactId ?? 0),
    patientSystemId: text(raw?.patientSystemId),
    name:            text(raw?.name),
    relationship:    textOrNull(raw?.relationship),
    phone:           text(raw?.phone),
    email:           textOrNull(raw?.email),
    isPrimary:       raw?.isPrimary === true,
    isCaregiver:     raw?.isCaregiver === true,
    createdAt:       text(raw?.createdAt),
  };
}

/** A `UserProfileResponse` as the screens use it. `fallbackSystemId` covers a response without `systemId`. */
export function toUserProfile(raw: any, fallbackSystemId?: string | null): UserProfile {
  const role = oneOf(raw?.role, ROLES) ?? 'PATIENT';
  const patientSystemId = textOrNull(raw?.patientSystemId);
  return {
    userId:            Number(raw?.userId ?? 0),
    systemId:          textOrNull(raw?.systemId) ?? patientSystemId ?? textOrNull(fallbackSystemId) ?? '',
    firstName:         text(raw?.firstName),
    lastName:          text(raw?.lastName),
    phone:             textOrNull(raw?.phone),
    email:             textOrNull(raw?.email),
    role,
    isActive:          raw?.isActive === true,
    patientSystemId,
    dateOfBirth:       textOrNull(raw?.dateOfBirth),
    gender:            oneOf(raw?.gender, GENDERS),
    bloodType:         oneOf(raw?.bloodType, BLOOD_TYPES),
    allergies:         listString(raw?.allergies),
    chronicConditions: listString(raw?.chronicConditions),
    healthStatus:      oneOf(raw?.healthStatus, HEALTH),
    emergencyContacts: Array.isArray(raw?.emergencyContacts) ? raw.emergencyContacts.map(toEmergencyContact) : null,
    relationship:      textOrNull(raw?.relationship),
    createdAt:         text(raw?.createdAt),
    updatedAt:         text(raw?.updatedAt),
  };
}

async function storedSystemId(): Promise<string | null> {
  try {
    return (await getUser())?.system_id ?? null;
  } catch {
    return null;
  }
}

/** GET /api/profile: the caller's own profile. */
export async function fetchMyProfile(): Promise<UserProfile> {
  const [{ data }, systemId] = await Promise.all([api.get('/api/profile'), storedSystemId()]);
  return toUserProfile(data, systemId);
}

/** PUT /api/profile: partial update of the caller's own profile; answers the profile after the change. */
export async function updateMyProfile(changes: UpdateUserProfile): Promise<UserProfile> {
  const [{ data }, systemId] = await Promise.all([api.put('/api/profile', changes), storedSystemId()]);
  return toUserProfile(data, systemId);
}

/**
 * Keeps the name stored with the session in step with a saved profile, so the dashboard header shows the new
 * name the next time it reads the signed-in user (the stored name is "first last", as the server sends it).
 * Never throws: a profile that was saved stays saved even when the session copy cannot be written.
 */
export async function syncStoredUserName(firstName: string, lastName: string): Promise<void> {
  const name = `${firstName} ${lastName}`.replace(/\s+/g, ' ').trim();
  if (!name) return;
  try {
    const stored = await getUser();
    if (stored && stored.name !== name) await saveUser({ ...stored, name });
  } catch {
    // The stored copy is refreshed at the next sign-in or token refresh.
  }
}

export interface MyProfileState {
  profile:     UserProfile | null;
  /** True until the first load has answered. */
  loading:     boolean;
  /** True while `refetch` reloads a profile that is already on screen. */
  refreshing:  boolean;
  error:       string | null;
  errorStatus: number | null;
  refetch:     () => void;
  /**
   * Saves the changed fields and puts the server's answer on screen. Rejects with the request error (use it
   * inside `useAction`, or read it with `apiError`). An empty change set resolves with the current profile
   * without a request.
   */
  updateProfile: (changes: UpdateUserProfile) => Promise<UserProfile>;
  setProfile:    ResourceState<UserProfile>['setData'];
}

export function useMyProfile(): MyProfileState {
  const { data, loading, refreshing, error, errorStatus, refetch, setData } = useResource<UserProfile>(
    fetchMyProfile, [], { errorMessage: 'Failed to load profile.' },
  );

  const updateProfile = useCallback(async (changes: UpdateUserProfile): Promise<UserProfile> => {
    if (Object.keys(changes).length === 0 && data) return data;
    const next = await updateMyProfile(changes);
    setData(next);
    if (changes.firstName !== undefined || changes.lastName !== undefined) {
      await syncStoredUserName(next.firstName, next.lastName);
    }
    return next;
  }, [data, setData]);

  return { profile: data, loading, refreshing, error, errorStatus, refetch, updateProfile, setProfile: setData };
}
