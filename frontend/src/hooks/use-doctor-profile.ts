import { useCallback, useMemo } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { doctorDisplayName } from './use-doctors';
import { syncStoredUserName } from './use-my-profile';
import { useResource } from './use-resource';

// The signed-in doctor's own profile (GET / PUT /api/doctors/profile, be-doctor-directory-patients contract).

export type VerificationStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUSPENDED';

/** `DoctorProfileResponse`, full view, as the API returns it. */
export interface DoctorProfileDto {
  doctorSystemId:         string;
  userId:                 number | null;
  firstName:              string;
  lastName:               string;
  /** Sign-in email. Read-only here: it changes only through the confirmed email-change flow. */
  email:                  string | null;
  phone:                  string | null;
  specialization:         string | null;
  /** Read-only for the doctor; an administrator corrects it. */
  licenseNo:              string | null;
  licensingBody:          string | null;
  /** ISO 3166-1 alpha-2. */
  licenseCountry:         string | null;
  verificationStatus:     VerificationStatus | null;
  bio:                    string | null;
  isActive:               boolean;
  role:                   string | null;
  totalPatients:          number | null;
  totalAppointments:      number | null;
  totalConsultations:     number | null;
  completedConsultations: number | null;
  totalPrescriptions:     number | null;
  totalMedicalRecords:    number | null;
  /** Server local date-times, display only. */
  doctorCreatedAt:        string | null;
  userCreatedAt:          string | null;
  userUpdatedAt:          string | null;
}

/** PUT /api/doctors/profile. Send only the fields that changed; never email, licenseNo or isActive. */
export interface UpdateDoctorProfileBody {
  firstName?:      string;
  lastName?:       string;
  phone?:          string;
  specialization?: string;
  /** '' clears the bio. */
  bio?:            string;
}

/** The snake_case view model the web and native doctor profile screens read. */
export interface DoctorProfile {
  doctor_system_id: string;
  user_id:          number;
  first_name:       string;
  last_name:        string;
  full_name:        string;
  email:            string;
  phone:            string | null;
  avatar_url:       string | null;
  specialization:   string;
  license_no:       string;
  bio:              string | null;
  is_active:        boolean;
  created_at:       string;
  /** Read-only verification details, set by an administrator. */
  verification_status: VerificationStatus | null;
  licensing_body:      string | null;
  license_country:     string | null;
  updated_at:          string;
}

export interface DoctorStats {
  appointments:  number;
  patients:      number;
  consultations: number;
  prescriptions: number;
}

interface State {
  profile:  DoctorProfile | null;
  stats:    DoctorStats;
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
  /** The profile exactly as the API returns it (camelCase), for the edit form. */
  raw:      DoctorProfileDto | null;
  /** True while `refetch` reloads a profile that is already on screen. */
  refreshing: boolean;
  /**
   * PUT /api/doctors/profile with the changed fields only; resolves with the saved profile, rejects with the
   * request error (409 `Phone number already registered` for a phone another account uses).
   */
  updateProfile: (changes: UpdateDoctorProfileBody) => Promise<DoctorProfileDto>;
}

const EMPTY_STATS: DoctorStats = { appointments: 0, patients: 0, consultations: 0, prescriptions: 0 };

const VERIFICATION: readonly VerificationStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED'];

function text(value: unknown): string {
  return value == null ? '' : String(value);
}

function textOrNull(value: unknown): string | null {
  const s = text(value).trim();
  return s ? s : null;
}

function count(value: unknown): number | null {
  const n = typeof value === 'number' ? value : Number.NaN;
  return Number.isFinite(n) ? n : null;
}

/** A `DoctorProfileResponse` as the screens use it. */
export function toDoctorProfileDto(raw: any): DoctorProfileDto {
  const status = text(raw?.verificationStatus).trim().toUpperCase();
  return {
    doctorSystemId:         text(raw?.doctorSystemId),
    userId:                 count(raw?.userId),
    firstName:              text(raw?.firstName),
    lastName:               text(raw?.lastName),
    email:                  textOrNull(raw?.email),
    phone:                  textOrNull(raw?.phone),
    specialization:         textOrNull(raw?.specialization),
    licenseNo:              textOrNull(raw?.licenseNo),
    licensingBody:          textOrNull(raw?.licensingBody),
    licenseCountry:         textOrNull(raw?.licenseCountry),
    verificationStatus:     (VERIFICATION as readonly string[]).includes(status) ? (status as VerificationStatus) : null,
    bio:                    textOrNull(raw?.bio),
    // The full view reports the clinician row's own flag; a response without it is treated as active, as before.
    isActive:               raw?.isActive !== false,
    role:                   textOrNull(raw?.role),
    totalPatients:          count(raw?.totalPatients),
    totalAppointments:      count(raw?.totalAppointments),
    totalConsultations:     count(raw?.totalConsultations),
    completedConsultations: count(raw?.completedConsultations),
    totalPrescriptions:     count(raw?.totalPrescriptions),
    totalMedicalRecords:    count(raw?.totalMedicalRecords),
    doctorCreatedAt:        textOrNull(raw?.doctorCreatedAt),
    userCreatedAt:          textOrNull(raw?.userCreatedAt),
    userUpdatedAt:          textOrNull(raw?.userUpdatedAt),
  };
}

export function toDoctorProfile(d: DoctorProfileDto): DoctorProfile {
  return {
    doctor_system_id:    d.doctorSystemId,
    user_id:             d.userId ?? 0,
    first_name:          d.firstName,
    last_name:           d.lastName,
    full_name:           doctorDisplayName(d.firstName, d.lastName),
    email:               d.email ?? '',
    phone:               d.phone,
    avatar_url:          null,
    specialization:      d.specialization ?? '',
    license_no:          d.licenseNo ?? '',
    bio:                 d.bio,
    is_active:           d.isActive,
    created_at:          d.doctorCreatedAt ?? d.userCreatedAt ?? '',
    verification_status: d.verificationStatus,
    licensing_body:      d.licensingBody,
    license_country:     d.licenseCountry,
    updated_at:          d.userUpdatedAt ?? '',
  };
}

function toStats(d: DoctorProfileDto): DoctorStats {
  return {
    appointments:  d.totalAppointments  ?? 0,
    patients:      d.totalPatients      ?? 0,
    consultations: d.totalConsultations ?? 0,
    prescriptions: d.totalPrescriptions ?? 0,
  };
}

/** GET /api/doctors/profile: the signed-in doctor's own profile (full view). */
export async function fetchDoctorProfile(): Promise<DoctorProfileDto> {
  const { data } = await api.get('/api/doctors/profile');
  return toDoctorProfileDto(data);
}

/** PUT /api/doctors/profile: partial update; answers the full profile after the change. */
export async function updateDoctorProfile(changes: UpdateDoctorProfileBody): Promise<DoctorProfileDto> {
  const { data } = await api.put('/api/doctors/profile', changes);
  return toDoctorProfileDto(data);
}

export function useDoctorProfile(): State {
  const { data, loading, refreshing, error, refetch, setData } = useResource<DoctorProfileDto>(
    fetchDoctorProfile, [], { errorMessage: 'Failed to load profile.' },
  );

  const updateProfile = useCallback(async (changes: UpdateDoctorProfileBody): Promise<DoctorProfileDto> => {
    if (Object.keys(changes).length === 0 && data) return data;
    const next = await updateDoctorProfile(changes);
    setData(next);
    if (changes.firstName !== undefined || changes.lastName !== undefined) {
      await syncStoredUserName(next.firstName, next.lastName);
    }
    return next;
  }, [data, setData]);

  const profile = useMemo(() => (data ? toDoctorProfile(data) : null), [data]);
  const stats   = useMemo(() => (data ? toStats(data) : EMPTY_STATS), [data]);

  return { profile, stats, loading, error, refetch, raw: data, refreshing, updateProfile };
}
