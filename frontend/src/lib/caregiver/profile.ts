import { z } from 'zod';
import { clinicToday } from '@/lib/time/clinic';
import { dateOnlyParts, isValidDateOnly } from '@/lib/ui/date-only';
import { isValidPhone, LIST_ITEM_MAX, LIST_MAX_ITEMS, NAME_MAX } from './schemas';
import type { BloodType, DependentProfile, Gender, UpdateDependentProfileBody } from './types';

// The two editors of a loved one's profile (PUT /api/profile/user/{systemId}).
//
//   Personal details   names, phone, date of birth and gender: needs the "manage profile" permission.
//   Health profile     blood type, allergies and chronic conditions: also needs access to medical records,
//                      otherwise the server refuses the whole save.
//
// Each form starts from the values that were loaded, with "Not known" for a value that is not recorded (never a
// made-up default), and only the fields the caregiver changed are sent. Health status is never sent: only a
// doctor sets it. Names, the phone and the date of birth cannot be removed once recorded.

/** Parses a list stored as a JSON array string (allergies, chronicConditions). Never throws. */
export function parseList(raw?: string | null): string[] {
  if (!raw) return [];
  try {
    const value = JSON.parse(raw);
    return Array.isArray(value) ? value.map(String) : [];
  } catch {
    return [];
  }
}

const GENDERS: readonly Gender[] = ['MALE', 'FEMALE', 'OTHER'];
const BLOOD_TYPES: readonly BloodType[] = ['A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-'];

function knownGender(value: unknown): Gender | '' {
  const v = typeof value === 'string' ? value.trim().toUpperCase() : '';
  return (GENDERS as readonly string[]).includes(v) ? (v as Gender) : '';
}

function knownBloodType(value: unknown): BloodType | '' {
  const v = typeof value === 'string' ? value.trim().toUpperCase() : '';
  return (BLOOD_TYPES as readonly string[]).includes(v) ? (v as BloodType) : '';
}

const OLDEST_AGE_YEARS = 130;

// ─── Personal details ─────────────────────────────────────────────────────────

export interface PersonalDetailsValues {
  firstName:   string;
  lastName:    string;
  phone:       string;
  /** "YYYY-MM-DD" or '' when not recorded. */
  dateOfBirth: string;
  /** '' = not known. */
  gender:      Gender | '';
}

export const PERSONAL_FIELDS = ['firstName', 'lastName', 'phone', 'dateOfBirth', 'gender'] as const;

/** The personal-details form's starting values: exactly what was loaded. */
export function personalDefaults(profile: DependentProfile): PersonalDetailsValues {
  return {
    firstName:   profile.firstName ?? '',
    lastName:    profile.lastName ?? '',
    phone:       profile.phone ?? '',
    dateOfBirth: isValidDateOnly(profile.dateOfBirth) ? (profile.dateOfBirth as string) : '',
    gender:      knownGender(profile.gender),
  };
}

const requiredName = (label: string) => z.string()
  .trim()
  .min(1, `${label} is required`)
  .max(NAME_MAX, `${label} must be at most ${NAME_MAX} characters`);

/**
 * The personal-details schema for a profile with these loaded values. A phone number or date of birth that is
 * recorded cannot be removed (the server would keep it and report success), so emptying one is an error here.
 */
export function personalDetailsSchema(loaded: PersonalDetailsValues) {
  return z.object({
    firstName: requiredName('First name'),
    lastName:  requiredName('Last name'),
    phone: z.string().trim().superRefine((value, ctx) => {
      if (value === '') {
        if (loaded.phone.trim() !== '') {
          ctx.addIssue({ code: 'custom', message: 'A phone number cannot be removed. Enter a new number or keep the current one.' });
        }
        return;
      }
      if (!isValidPhone(value)) ctx.addIssue({ code: 'custom', message: 'Enter a valid phone number' });
    }),
    dateOfBirth: z.string().superRefine((value, ctx) => {
      if (value === '') {
        if (loaded.dateOfBirth !== '') {
          ctx.addIssue({ code: 'custom', message: 'A date of birth cannot be removed. Enter the correct date instead.' });
        }
        return;
      }
      if (!isValidDateOnly(value)) { ctx.addIssue({ code: 'custom', message: 'Enter a valid date' }); return; }
      const today = clinicToday();
      if (value >= today) { ctx.addIssue({ code: 'custom', message: 'Date of birth must be in the past' }); return; }
      const born = dateOnlyParts(value);
      const now = dateOnlyParts(today);
      if (born && now && born.y < now.y - OLDEST_AGE_YEARS) {
        ctx.addIssue({ code: 'custom', message: 'Please enter a valid date of birth' });
      }
    }),
    gender: z.enum(['', 'MALE', 'FEMALE', 'OTHER']),
  });
}

/** Only what differs from the loaded profile. An empty object means nothing changed. */
export function personalChanges(values: PersonalDetailsValues, profile: DependentProfile): UpdateDependentProfileBody {
  const loaded = personalDefaults(profile);
  const body: UpdateDependentProfileBody = {};
  const firstName = values.firstName.trim();
  const lastName  = values.lastName.trim();
  const phone     = values.phone.trim();
  if (firstName !== loaded.firstName.trim()) body.firstName = firstName;
  if (lastName !== loaded.lastName.trim())   body.lastName = lastName;
  if (phone !== '' && phone !== loaded.phone.trim()) body.phone = phone;
  if (values.dateOfBirth !== '' && values.dateOfBirth !== loaded.dateOfBirth) body.dateOfBirth = values.dateOfBirth;
  // '' clears a recorded gender ("Not known").
  if (values.gender !== loaded.gender) body.gender = values.gender;
  return body;
}

// ─── Health profile ───────────────────────────────────────────────────────────

export interface HealthProfileValues {
  /** '' = not known. */
  bloodType:         BloodType | '';
  allergies:         string[];
  chronicConditions: string[];
}

export const HEALTH_FIELDS = ['bloodType', 'allergies', 'chronicConditions'] as const;

/** The health form's starting values: exactly what was loaded. */
export function healthDefaults(profile: DependentProfile): HealthProfileValues {
  return {
    bloodType:         knownBloodType(profile.bloodType),
    allergies:         parseList(profile.allergies),
    chronicConditions: parseList(profile.chronicConditions),
  };
}

const entryList = (label: string, singular: string) => z.array(z.string().trim())
  .max(LIST_MAX_ITEMS, `${label} can have at most ${LIST_MAX_ITEMS} entries`)
  .refine(
    list => list.every(item => item.length >= 1 && item.length <= LIST_ITEM_MAX),
    `Each ${singular} must be 1 to ${LIST_ITEM_MAX} characters`,
  );

export const healthProfileSchema = z.object({
  bloodType:         z.enum(['', 'A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-']),
  allergies:         entryList('Allergies', 'allergy'),
  chronicConditions: entryList('Chronic conditions', 'chronic condition'),
});

function sameList(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((item, i) => item.trim() === b[i].trim());
}

/** Only what differs from the loaded profile. An empty object means nothing changed. */
export function healthChanges(values: HealthProfileValues, profile: DependentProfile): UpdateDependentProfileBody {
  const loaded = healthDefaults(profile);
  const body: UpdateDependentProfileBody = {};
  // '' clears a recorded blood type ("Not known").
  if (values.bloodType !== loaded.bloodType) body.bloodType = values.bloodType;
  if (!sameList(values.allergies, loaded.allergies)) {
    body.allergies = JSON.stringify(values.allergies.map(item => item.trim()));
  }
  if (!sameList(values.chronicConditions, loaded.chronicConditions)) {
    body.chronicConditions = JSON.stringify(values.chronicConditions.map(item => item.trim()));
  }
  return body;
}
