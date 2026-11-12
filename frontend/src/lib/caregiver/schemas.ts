import { z } from 'zod';
import { clinicToday } from '@/lib/time/clinic';
import { dateOnlyParts, isValidDateOnly } from '@/lib/ui/date-only';
import { dataFlagsOf, normaliseDataFlags } from './access';
import type { CreateAccessRequestBody, CreateDependentBody } from './types';

// Form schemas of the caregiver access flows. They mirror the backend constraints of
// POST /api/caregiver/dependents, POST /api/caregiver/access-requests, POST .../invite and
// PUT /api/profile/user/{systemId}, so a value the form accepts is a value the server accepts.

/** Same pattern as the backend's ValidationPatterns.PHONE: optional +, then 7 to 15 digits with separators. */
export const PHONE_PATTERN = /^\+?(?:[ .()-]*\d){7,15}[ .()-]*$/;

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export const NAME_MAX         = 100;
export const EMAIL_MAX        = 150;
export const PHONE_MAX        = 20;
export const RELATIONSHIP_MAX = 80;
export const MESSAGE_MAX      = 500;
export const PASSWORD_MIN     = 8;
export const PASSWORD_MAX     = 72;
/** Allergies and chronic conditions: at most 50 entries of at most 100 characters each. */
export const LIST_MAX_ITEMS   = 50;
export const LIST_ITEM_MAX    = 100;
const OLDEST_AGE_YEARS        = 130;

export function isValidEmail(value: string): boolean {
  const v = value.trim();
  return v.length <= EMAIL_MAX && EMAIL_PATTERN.test(v);
}

export function isValidPhone(value: string): boolean {
  const v = value.trim();
  return v.length <= PHONE_MAX && PHONE_PATTERN.test(v);
}

/** The backend's password rule: 8 to 72 characters with at least one letter and one digit. */
export function passwordError(value: string): string | null {
  if (value.length > PASSWORD_MAX) return `Password must be at most ${PASSWORD_MAX} characters`;
  if (value.length < PASSWORD_MIN || !/[A-Za-z]/.test(value) || !/\d/.test(value)) {
    return 'Password must be at least 8 characters and contain at least one letter and one number';
  }
  return null;
}

/** Checks a date of birth typed as "YYYY-MM-DD": a real date, not in the future, not more than 130 years ago. */
export function dateOfBirthError(value: string, today: string = clinicToday()): string | null {
  if (!value) return null;
  if (!isValidDateOnly(value)) return 'Enter a valid date';
  if (value > today) return 'Date of birth cannot be in the future';
  const born = dateOnlyParts(value);
  const now = dateOnlyParts(today);
  if (born && now && born.y < now.y - OLDEST_AGE_YEARS) return 'Please enter a valid date of birth';
  return null;
}

const requiredName = (label: string) => z.string()
  .trim()
  .min(1, `${label} is required`)
  .max(NAME_MAX, `${label} must be at most ${NAME_MAX} characters`);

const relationship = z.string()
  .trim()
  .min(1, 'Relationship is required')
  .max(RELATIONSHIP_MAX, `Relationship must be at most ${RELATIONSHIP_MAX} characters`);

const optionalPhone = z.string().trim().refine(v => v === '' || isValidPhone(v), 'Enter a valid phone number');

const accessLevel = z.enum(['FULL_ACCESS', 'VIEW_ONLY', 'EDIT_ONLY', 'CUSTOM']);

const entryList = (label: string) => z.array(z.string())
  .max(LIST_MAX_ITEMS, `${label} can have at most ${LIST_MAX_ITEMS} entries`);

const permissionFlags = {
  canViewRecords:          z.boolean(),
  canViewAppointments:     z.boolean(),
  canViewMedications:      z.boolean(),
  canViewLabResults:       z.boolean(),
  canViewImaging:          z.boolean(),
  canViewConsultations:    z.boolean(),
  canViewBilling:          z.boolean(),
  canScheduleAppointments: z.boolean(),
  canManageCarePlan:       z.boolean(),
  canManageBilling:        z.boolean(),
  canMessageCareTeam:      z.boolean(),
  canJoinCalls:            z.boolean(),
};

/** An optional end date of the access: today or later in clinic time (the access ends at the end of that day). */
const futureDay = z.string().refine(v => v === '' || isValidDateOnly(v), 'Enter a valid date')
  .refine(v => v === '' || !isValidDateOnly(v) || v >= clinicToday(), 'Access expiry must be in the future');

// ─── Create a new account for a loved one ─────────────────────────────────────

export const createDependentSchema = z.object({
  firstName: requiredName('First name'),
  lastName:  requiredName('Last name'),
  /** Empty = no sign-in details: the caregiver manages the account until the person is invited. */
  email:     z.string().trim().refine(v => v === '' || isValidEmail(v), 'Please enter a valid email address'),
  /** Used only when an email is given. */
  password:  z.string(),
  phone:     optionalPhone,

  dateOfBirth:       z.string().superRefine((value, ctx) => {
    const problem = dateOfBirthError(value);
    if (problem) ctx.addIssue({ code: 'custom', message: problem });
  }),
  gender:            z.enum(['', 'MALE', 'FEMALE', 'OTHER']),
  bloodType:         z.enum(['', 'A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-']),
  allergies:         entryList('Allergies'),
  chronicConditions: entryList('Chronic conditions'),

  relationship,
  accessLevel,
  expiresOn: futureDay,
  guardianAttested: z.boolean().refine(v => v, "Confirm that you are this person's parent or legal guardian"),
  ...permissionFlags,
}).superRefine((values, ctx) => {
  // The password matters only when the person gets sign-in details.
  if (values.email !== '') {
    const problem = passwordError(values.password);
    if (problem) ctx.addIssue({ code: 'custom', path: ['password'], message: problem });
  }
});

export type CreateDependentFormValues = z.infer<typeof createDependentSchema>;

/** The fields of each step of the Add Loved One form, in the order they are shown. */
export const CREATE_DEPENDENT_STEPS: readonly (readonly (keyof CreateDependentFormValues)[])[] = [
  ['firstName', 'lastName', 'phone', 'email', 'password'],
  ['dateOfBirth', 'gender', 'bloodType', 'allergies', 'chronicConditions'],
  ['relationship', 'accessLevel', 'expiresOn', 'guardianAttested'],
];

// ─── Ask an existing patient for access ───────────────────────────────────────

export const accessRequestSchema = z.object({
  patientEmail: z.string().trim()
    .min(1, 'Patient email is required')
    .refine(isValidEmail, 'Please enter a valid email address'),
  relationship,
  accessLevel,
  message: z.string().trim().max(MESSAGE_MAX, `Message must be at most ${MESSAGE_MAX} characters`),
  ...permissionFlags,
});

export type AccessRequestFormValues = z.infer<typeof accessRequestSchema>;

// ─── Invite a managed dependent to sign in ────────────────────────────────────

export const inviteSchema = z.object({
  email: z.string().trim().min(1, 'Email is required').refine(isValidEmail, 'Please enter a valid email address'),
});

export type InviteFormValues = z.infer<typeof inviteSchema>;

// ─── Request bodies ───────────────────────────────────────────────────────────

/**
 * The POST /api/caregiver/dependents body for the form's values. Email and password go together or not at all;
 * blank optional values are left out; the flags are sent the way the level stores them. Health status is never
 * sent: a new patient starts as Stable and only a doctor changes it.
 */
export function toCreateDependentBody(values: CreateDependentFormValues): CreateDependentBody {
  const email = values.email.trim().toLowerCase();
  const phone = values.phone.trim();
  return {
    firstName: values.firstName.trim(),
    lastName:  values.lastName.trim(),
    ...(email ? { email, password: values.password } : {}),
    ...(phone ? { phone } : {}),
    guardianAttested: values.guardianAttested,
    ...(values.dateOfBirth ? { dateOfBirth: values.dateOfBirth } : {}),
    ...(values.gender ? { gender: values.gender } : {}),
    ...(values.bloodType ? { bloodType: values.bloodType } : {}),
    allergies:         values.allergies,
    chronicConditions: values.chronicConditions,
    relationship: values.relationship.trim(),
    accessLevel:  values.accessLevel,
    ...(values.expiresOn ? { expiresAt: values.expiresOn } : {}),
    ...normaliseDataFlags(values.accessLevel, dataFlagsOf(values)),
    canMessageCareTeam: values.canMessageCareTeam,
    canJoinCalls:       values.canJoinCalls,
  };
}

/** The POST /api/caregiver/access-requests body for the form's values. */
export function toAccessRequestBody(values: AccessRequestFormValues): CreateAccessRequestBody {
  const message = values.message.trim();
  return {
    patientEmail: values.patientEmail.trim().toLowerCase(),
    relationship: values.relationship.trim(),
    accessLevel:  values.accessLevel,
    ...normaliseDataFlags(values.accessLevel, dataFlagsOf(values)),
    canMessageCareTeam: values.canMessageCareTeam,
    canJoinCalls:       values.canJoinCalls,
    ...(message ? { message } : {}),
  };
}
