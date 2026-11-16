import { useMemo } from 'react';
import {
  parseProfileList, useMyProfile,
  type UpdateUserProfile, type UserProfile,
} from './use-my-profile';

// The signed-in patient's profile in the snake_case view model the dashboard and native screens read.
// It is the same GET /api/profile as `useMyProfile`; the web profile screen edits through `updateProfile`.

export interface PatientProfile {
  user_id:             number;
  /** The account's system id (PAT-…). */
  system_id:           string;
  first_name:          string;
  last_name:           string;
  email:               string;
  phone:               string | null;
  role:                string;
  is_active:           number;
  avatar_url:          string | null;
  user_created_at:     string;
  user_updated_at:     string;
  /** Legacy numeric id, not returned by the API. Display patient_system_id instead. */
  patient_id:          number | null;
  patient_system_id:   string | null;
  date_of_birth:       string | null;
  gender:              'MALE' | 'FEMALE' | 'OTHER' | null;
  blood_type:          string | null;
  allergies:           string[];
  chronic_conditions:  string[];
  health_status:       'STABLE' | 'MONITOR' | 'CRITICAL' | null;
  patient_created_at:  string | null;
}

interface State {
  profile:  PatientProfile | null;
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
  /** The profile exactly as the API returns it (camelCase), for the edit forms. */
  raw:      UserProfile | null;
  /** True while `refetch` reloads a profile that is already on screen. */
  refreshing: boolean;
  /** PUT /api/profile with the changed fields only; resolves with the saved profile, rejects with the request error. */
  updateProfile: (changes: UpdateUserProfile) => Promise<UserProfile>;
}

export function toPatientProfile(p: UserProfile): PatientProfile {
  return {
    user_id:            p.userId,
    system_id:          p.systemId,
    first_name:         p.firstName,
    last_name:          p.lastName,
    email:              p.email ?? '',
    phone:              p.phone,
    role:               p.role,
    is_active:          p.isActive ? 1 : 0,
    avatar_url:         null,
    user_created_at:    p.createdAt,
    user_updated_at:    p.updatedAt,
    patient_id:         null,
    // A patient's public identifier is the account's own system id.
    patient_system_id:  p.patientSystemId ?? (p.role === 'PATIENT' && p.systemId ? p.systemId : null),
    date_of_birth:      p.dateOfBirth,
    gender:             p.gender,
    blood_type:         p.bloodType,
    allergies:          parseProfileList(p.allergies),
    chronic_conditions: parseProfileList(p.chronicConditions),
    health_status:      p.healthStatus,
    patient_created_at: p.createdAt || null,
  };
}

export function usePatientProfile(): State {
  const { profile: raw, loading, refreshing, error, refetch, updateProfile } = useMyProfile();
  const profile = useMemo(() => (raw ? toPatientProfile(raw) : null), [raw]);
  return { profile, loading, error, refetch, raw, refreshing, updateProfile };
}
