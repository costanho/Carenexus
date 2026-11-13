import { z } from 'zod';
import { EMAIL_MAX_LENGTH, EMAIL_REGEX } from '../auth-schemas';
import { API_PHONE_REGEX, PHONE_REGEX, utf8ByteLength } from './register.validators';

// Registration form of the native app. Its behaviour is unchanged; the web screen uses webRegisterSchema below.
export const registerSchema = z
  .object({
    firstName:       z.string().min(1, 'First name is required'),
    lastName:        z.string().min(1, 'Last name is required'),
    email:           z.string().min(1, 'Email is required').email('Enter a valid email address'),
    phone:           z.string()
                       .min(1, 'Phone number is required')
                       .regex(PHONE_REGEX, 'Enter a valid phone number'),
    password:        z.string()
                       .min(8, 'Minimum 8 characters')
                       .regex(/[A-Za-z]/, 'Include at least one letter')
                       .regex(/\d/, 'Include at least one number'),
    confirmPassword: z.string().min(1, 'Please confirm your password'),
    agreed:          z.literal(true, { error: 'You must accept the terms to continue' }),
  })
  .refine((d) => d.password === d.confirmPassword, {
    message: 'Passwords do not match',
    path:    ['confirmPassword'],
  });

// ── Web registration (mirrors RegisterRequest of POST /api/auth/register) ─────

/** Column and request limits of the API, shared with the inputs' maxLength. */
export const REGISTER_LIMITS = {
  firstName:      100,
  lastName:       100,
  email:          EMAIL_MAX_LENGTH,
  phone:          20,
  /** bcrypt reads at most 72 bytes; the server refuses longer passwords. */
  passwordBytes:  72,
  licenseNo:      50,
  specialization: 150,
  licensingBody:  150,
} as const;

const requiredText = (label: string, max: number) => z.string()
  .trim()
  .min(1, `${label} is required`)
  .max(max, `${label} must be at most ${max} characters`);

/** Optional free text: trimmed, '' when left empty. */
const optionalText = (label: string, max: number) => z.string()
  .trim()
  .max(max, `${label} must be at most ${max} characters`);

const accountFields = {
  firstName: requiredText('First name', REGISTER_LIMITS.firstName),
  lastName:  requiredText('Last name', REGISTER_LIMITS.lastName),
  email:     z.string()
               .trim()
               .min(1, 'Email is required')
               .max(REGISTER_LIMITS.email, `Email must be at most ${REGISTER_LIMITS.email} characters`)
               .regex(EMAIL_REGEX, 'Enter a valid email address')
               .transform(value => value.toLowerCase()),
  // The form asks for a phone number (the API would accept none). The value is "<dial code> <number>".
  phone:     z.string()
               .trim()
               .min(1, 'Phone number is required')
               .regex(PHONE_REGEX, 'Enter a valid phone number')
               .regex(API_PHONE_REGEX, 'Enter a valid phone number')
               .max(REGISTER_LIMITS.phone, `Phone number must be at most ${REGISTER_LIMITS.phone} characters`),
  // Sent exactly as typed (never trimmed).
  password:  z.string()
               .min(8, 'Minimum 8 characters')
               .regex(/\p{L}/u, 'Include at least one letter')
               .regex(/\d/, 'Include at least one number')
               .refine(value => utf8ByteLength(value) <= REGISTER_LIMITS.passwordBytes, 'Password must be at most 72 characters'),
  confirmPassword: z.string().min(1, 'Please confirm your password'),
  agreed:    z.literal(true, { error: 'You must accept the terms to continue' }),
};

// Optional for every role on the server and in this form.
const licenceExtras = {
  licensingBody:  optionalText('Licensing body', REGISTER_LIMITS.licensingBody),
  /** ISO 3166-1 alpha-2 code, or '' when not given. */
  licenseCountry: z.string().trim().toUpperCase()
                    .refine(value => value === '' || /^[A-Z]{2}$/.test(value), 'Choose the country of the licence'),
};

const passwordsMatch = {
  message: 'Passwords do not match',
  path:    ['confirmPassword'],
};

/**
 * Patient and proxy registration. The clinician fields are part of the form's values for every role (the form
 * keeps one shape) but are not asked for here and not sent.
 */
export const webRegisterSchema = z
  .object({
    ...accountFields,
    licenseNo:      optionalText('Licence number', REGISTER_LIMITS.licenseNo),
    specialization: optionalText('Specialization', REGISTER_LIMITS.specialization),
    ...licenceExtras,
  })
  .refine(d => d.password === d.confirmPassword, passwordsMatch);

/**
 * Doctor registration: the licence number and the specialization are required by the web form (an
 * administrator cannot approve a clinician without a licence number); licensing body and country are optional.
 */
export const webDoctorRegisterSchema = z
  .object({
    ...accountFields,
    licenseNo:      requiredText('Licence number', REGISTER_LIMITS.licenseNo),
    specialization: requiredText('Specialization', REGISTER_LIMITS.specialization),
    ...licenceExtras,
  })
  .refine(d => d.password === d.confirmPassword, passwordsMatch);

/** The schema the web registration form validates with for the selected role. */
export function webRegisterSchemaFor(role: 'patient' | 'doctor' | 'proxy') {
  return role === 'doctor' ? webDoctorRegisterSchema : webRegisterSchema;
}
