import { z } from 'zod';
import type { DoctorProfileDto, UpdateDoctorProfileBody } from '@/hooks/use-doctor-profile';
import type { CreateEmergencyContact, UpdateEmergencyContact } from '@/hooks/use-emergency-contacts';
import type { BloodType, EmergencyContact, Gender, UpdateUserProfile, UserProfile } from '@/hooks/use-my-profile';
import { clinicToday } from '@/lib/time/clinic';
import { isValidDateOnly } from '@/lib/ui/date-only';

// Form schemas of the profile screens. They mirror the backend constraints of the be-profile-settings and
// be-doctor-directory-patients contracts (limits, patterns and the wording of the server's own messages), so a
// value the form accepts is a value the API accepts. Each form also has a "changes" builder: the profile
// endpoints are partial updates and the screens send only the fields that differ from what is stored.

// ─── Limits and patterns ──────────────────────────────────────────────────────

export const PROFILE_LIMITS = {
  firstName:      100,
  lastName:       100,
  phone:          20,
  specialization: 150,
  bio:            2000,
  /** Allergies and chronic conditions: entries per list, characters per entry, characters of the JSON string. */
  listEntries:    50,
  listEntry:      100,
  listJson:       6000,
  contactName:    150,
  relationship:   80,
  email:          150,
} as const;

/** `ValidationPatterns.PHONE`: optional leading +, 7 to 15 digits; spaces, dashes, dots and round brackets between them. */
export const PHONE_PATTERN = /^\+?(?:[ .()-]*\d){7,15}[ .()-]*$/;

/** The email rule of the emergency-contact DTOs. */
export const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

const GENDER_VALUES = ['', 'MALE', 'FEMALE', 'OTHER'] as const;
const BLOOD_TYPE_VALUES = ['', 'A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-'] as const;

// ─── Phone helpers ────────────────────────────────────────────────────────────

/**
 * True when no number was entered. The shared phone input reports the country dial code alone ("+263") while
 * its number box is empty, so a bare dial code counts as blank.
 */
export function isBlankPhone(value: string | null | undefined): boolean {
  const v = (value ?? '').trim();
  return v === '' || /^\+\d{1,4}$/.test(v);
}

function phoneKey(value: string | null | undefined): string {
  return isBlankPhone(value) ? '' : (value ?? '').replace(/[^\d+]/g, '');
}

/** True when two phone strings are the same number, however they are spaced or punctuated. */
export function samePhone(a: string | null | undefined, b: string | null | undefined): boolean {
  return phoneKey(a) === phoneKey(b);
}

// ─── Field builders ───────────────────────────────────────────────────────────

function nameField(label: string, max: number) {
  return z.string()
    .refine(v => v.trim().length > 0, `${label} is required`)
    .refine(v => v.trim().length <= max, `${label} must be at most ${max} characters`);
}

/** `blankMessage` is the error for an empty value; null allows the field to stay empty. */
function phoneField(blankMessage: string | null) {
  return z.string().superRefine((value, ctx) => {
    if (isBlankPhone(value)) {
      if (blankMessage) ctx.addIssue({ code: 'custom', message: blankMessage });
      return;
    }
    const v = value.trim();
    if (v.length > PROFILE_LIMITS.phone) {
      ctx.addIssue({ code: 'custom', message: `Phone number must be at most ${PROFILE_LIMITS.phone} characters` });
    } else if (!PHONE_PATTERN.test(v)) {
      ctx.addIssue({ code: 'custom', message: 'Enter a valid phone number' });
    }
  });
}

/** The account phone cannot be removed once saved; an account that never had one may leave it empty. */
function accountPhoneField(hasPhone: boolean) {
  return phoneField(hasPhone ? 'Enter a phone number. A saved phone number cannot be removed.' : null);
}

/** Entries as they are stored: trimmed, blanks dropped. */
function cleanList(list: readonly string[]): string[] {
  return list.map(s => s.trim()).filter(Boolean);
}

function listField(plural: string, singular: string) {
  return z.array(z.string()).superRefine((list, ctx) => {
    const entries = cleanList(list);
    if (entries.length > PROFILE_LIMITS.listEntries) {
      ctx.addIssue({ code: 'custom', message: `${plural} can have at most ${PROFILE_LIMITS.listEntries} entries` });
    } else if (entries.some(e => e.length > PROFILE_LIMITS.listEntry)) {
      ctx.addIssue({ code: 'custom', message: `Each ${singular} must be 1 to ${PROFILE_LIMITS.listEntry} characters` });
    } else if (JSON.stringify(entries).length > PROFILE_LIMITS.listJson) {
      ctx.addIssue({ code: 'custom', message: `${plural} must be at most ${PROFILE_LIMITS.listJson} characters` });
    }
  });
}

function sameList(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((v, i) => v === b[i]);
}

function parseList(raw: string | null): string[] {
  if (!raw) return [];
  try {
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? cleanList(parsed.map(String)) : [];
  } catch {
    return [];
  }
}

// ─── Account details: first name, last name, phone (caregiver; also part of the patient and doctor forms) ────

export interface AccountDetailsForm {
  firstName: string;
  lastName:  string;
  phone:     string;
}

export function accountDetailsSchema(original: { hasPhone: boolean }) {
  return z.object({
    firstName: nameField('First name', PROFILE_LIMITS.firstName),
    lastName:  nameField('Last name', PROFILE_LIMITS.lastName),
    phone:     accountPhoneField(original.hasPhone),
  });
}

type NamePhone = { firstName: string; lastName: string; phone: string | null };

function namePhoneChanges(original: NamePhone, values: AccountDetailsForm): { firstName?: string; lastName?: string; phone?: string } {
  const changes: { firstName?: string; lastName?: string; phone?: string } = {};
  const firstName = values.firstName.trim();
  const lastName  = values.lastName.trim();
  if (firstName && firstName !== original.firstName) changes.firstName = firstName;
  if (lastName && lastName !== original.lastName) changes.lastName = lastName;
  // A blank phone means "unchanged" to the server, so it is never sent.
  if (!isBlankPhone(values.phone) && !samePhone(values.phone, original.phone)) changes.phone = values.phone.trim();
  return changes;
}

export function accountDetailsDefaults(profile: NamePhone): AccountDetailsForm {
  return { firstName: profile.firstName, lastName: profile.lastName, phone: profile.phone ?? '' };
}

/** The fields of PUT /api/profile that differ from the stored account details. */
export function accountDetailsChanges(profile: NamePhone, values: AccountDetailsForm): UpdateUserProfile {
  return namePhoneChanges(profile, values);
}

// ─── Patient: personal details ────────────────────────────────────────────────

export interface PersonalDetailsForm extends AccountDetailsForm {
  /** "YYYY-MM-DD", or '' when not recorded. */
  dateOfBirth: string;
  /** '' = not recorded. */
  gender:      Gender | '';
}

export function personalDetailsSchema(original: { hasPhone: boolean; hasDateOfBirth: boolean }) {
  return z.object({
    firstName:   nameField('First name', PROFILE_LIMITS.firstName),
    lastName:    nameField('Last name', PROFILE_LIMITS.lastName),
    phone:       accountPhoneField(original.hasPhone),
    dateOfBirth: z.string().superRefine((value, ctx) => {
      if (value === '') {
        // The server keeps a stored date when none is sent, so removing it is not possible.
        if (original.hasDateOfBirth) ctx.addIssue({ code: 'custom', message: 'Enter a date of birth. A saved date of birth cannot be removed.' });
        return;
      }
      if (!isValidDateOnly(value)) ctx.addIssue({ code: 'custom', message: 'Enter a valid date' });
      // "In the past" is judged against today's date at the clinic, as the server does.
      else if (value >= clinicToday()) ctx.addIssue({ code: 'custom', message: 'Date of birth must be in the past' });
    }),
    gender:      z.enum(GENDER_VALUES, { error: 'Gender must be MALE, FEMALE or OTHER' }),
  });
}

export function personalDetailsDefaults(profile: UserProfile): PersonalDetailsForm {
  return {
    ...accountDetailsDefaults(profile),
    dateOfBirth: profile.dateOfBirth ?? '',
    gender:      profile.gender ?? '',
  };
}

/** The fields of PUT /api/profile that differ from the stored personal details. */
export function personalDetailsChanges(profile: UserProfile, values: PersonalDetailsForm): UpdateUserProfile {
  const changes: UpdateUserProfile = namePhoneChanges(profile, values);
  if (values.dateOfBirth && values.dateOfBirth !== (profile.dateOfBirth ?? '')) changes.dateOfBirth = values.dateOfBirth;
  // '' clears the stored gender.
  if (values.gender !== (profile.gender ?? '')) changes.gender = values.gender;
  return changes;
}

// ─── Patient: medical profile ─────────────────────────────────────────────────

export interface MedicalProfileForm {
  /** '' = not known. */
  bloodType:         BloodType | '';
  allergies:         string[];
  chronicConditions: string[];
}

export const medicalProfileSchema = z.object({
  bloodType:         z.enum(BLOOD_TYPE_VALUES, { error: 'Blood type must be one of A+, A-, B+, B-, AB+, AB-, O+, O-' }),
  allergies:         listField('Allergies', 'allergy'),
  chronicConditions: listField('Chronic conditions', 'chronic condition'),
});

export function medicalProfileDefaults(profile: UserProfile): MedicalProfileForm {
  return {
    bloodType:         profile.bloodType ?? '',
    allergies:         parseList(profile.allergies),
    chronicConditions: parseList(profile.chronicConditions),
  };
}

/** The fields of PUT /api/profile that differ from the stored medical profile. Lists travel as JSON-array strings. */
export function medicalProfileChanges(profile: UserProfile, values: MedicalProfileForm): UpdateUserProfile {
  const changes: UpdateUserProfile = {};
  // '' clears the stored blood type ("Not known").
  if (values.bloodType !== (profile.bloodType ?? '')) changes.bloodType = values.bloodType;
  const allergies = cleanList(values.allergies);
  if (!sameList(allergies, parseList(profile.allergies))) changes.allergies = JSON.stringify(allergies);
  const conditions = cleanList(values.chronicConditions);
  if (!sameList(conditions, parseList(profile.chronicConditions))) changes.chronicConditions = JSON.stringify(conditions);
  return changes;
}

// ─── Doctor profile ───────────────────────────────────────────────────────────

export interface DoctorProfileForm extends AccountDetailsForm {
  specialization: string;
  bio:            string;
}

export function doctorProfileSchema(original: { hasPhone: boolean; hasSpecialization: boolean }) {
  return z.object({
    firstName:      nameField('First name', PROFILE_LIMITS.firstName),
    lastName:       nameField('Last name', PROFILE_LIMITS.lastName),
    phone:          accountPhoneField(original.hasPhone),
    specialization: z.string()
      // The server keeps a stored specialization when a blank one is sent, so it cannot be removed.
      .refine(v => !original.hasSpecialization || v.trim().length > 0, 'Enter a specialization. A saved specialization cannot be removed.')
      .refine(v => v.trim().length <= PROFILE_LIMITS.specialization, `Specialization must be at most ${PROFILE_LIMITS.specialization} characters`),
    bio:            z.string()
      .refine(v => v.trim().length <= PROFILE_LIMITS.bio, `Bio must be at most ${PROFILE_LIMITS.bio} characters`),
  });
}

type DoctorEditable = Pick<DoctorProfileDto, 'firstName' | 'lastName' | 'phone' | 'specialization' | 'bio'>;

export function doctorProfileDefaults(profile: DoctorEditable): DoctorProfileForm {
  return {
    ...accountDetailsDefaults(profile),
    specialization: profile.specialization ?? '',
    bio:            profile.bio ?? '',
  };
}

/** The fields of PUT /api/doctors/profile that differ from the stored profile. Email and licence are never sent. */
export function doctorProfileChanges(profile: DoctorEditable, values: DoctorProfileForm): UpdateDoctorProfileBody {
  const changes: UpdateDoctorProfileBody = namePhoneChanges(profile, values);
  const specialization = values.specialization.trim();
  if (specialization && specialization !== (profile.specialization ?? '')) changes.specialization = specialization;
  // '' clears the stored bio.
  const bio = values.bio.trim();
  if (bio !== (profile.bio ?? '')) changes.bio = bio;
  return changes;
}

// ─── Emergency contact ────────────────────────────────────────────────────────

export interface EmergencyContactForm {
  name:         string;
  relationship: string;
  phone:        string;
  email:        string;
  isPrimary:    boolean;
}

export const emergencyContactSchema = z.object({
  name: z.string()
    .refine(v => v.trim().length > 0, 'Contact name is required')
    .refine(v => v.trim().length <= PROFILE_LIMITS.contactName, `Contact name must be at most ${PROFILE_LIMITS.contactName} characters`),
  relationship: z.string()
    .refine(v => v.trim().length <= PROFILE_LIMITS.relationship, `Relationship must be at most ${PROFILE_LIMITS.relationship} characters`),
  phone: phoneField('Phone number is required'),
  email: z.string().superRefine((value, ctx) => {
    const v = value.trim();
    if (!v) return;
    if (v.length > PROFILE_LIMITS.email) ctx.addIssue({ code: 'custom', message: `Email must be at most ${PROFILE_LIMITS.email} characters` });
    else if (!EMAIL_PATTERN.test(v)) ctx.addIssue({ code: 'custom', message: 'Enter a valid email address' });
  }),
  isPrimary: z.boolean(),
});

export const EMPTY_EMERGENCY_CONTACT: EmergencyContactForm = {
  name: '', relationship: '', phone: '', email: '', isPrimary: false,
};

export function emergencyContactDefaults(contact: EmergencyContact | null): EmergencyContactForm {
  if (!contact) return { ...EMPTY_EMERGENCY_CONTACT };
  return {
    name:         contact.name,
    relationship: contact.relationship ?? '',
    phone:        contact.phone,
    email:        contact.email ?? '',
    isPrimary:    contact.isPrimary,
  };
}

/** Body of POST /api/profile/emergency-contacts. Blank optional fields are left out (the server stores null). */
export function emergencyContactBody(values: EmergencyContactForm): CreateEmergencyContact {
  const body: CreateEmergencyContact = { name: values.name.trim(), phone: values.phone.trim(), isPrimary: values.isPrimary };
  const relationship = values.relationship.trim();
  const email = values.email.trim();
  if (relationship) body.relationship = relationship;
  if (email) body.email = email;
  return body;
}

/** The fields of PUT /api/profile/emergency-contacts/{id} that differ from the stored contact; '' clears relationship and email. */
export function emergencyContactChanges(contact: EmergencyContact, values: EmergencyContactForm): UpdateEmergencyContact {
  const changes: UpdateEmergencyContact = {};
  const name = values.name.trim();
  if (name && name !== contact.name) changes.name = name;
  const relationship = values.relationship.trim();
  if (relationship !== (contact.relationship ?? '')) changes.relationship = relationship;
  if (!isBlankPhone(values.phone) && !samePhone(values.phone, contact.phone)) changes.phone = values.phone.trim();
  const email = values.email.trim();
  if (email !== (contact.email ?? '')) changes.email = email;
  if (values.isPrimary !== contact.isPrimary) changes.isPrimary = values.isPrimary;
  return changes;
}
