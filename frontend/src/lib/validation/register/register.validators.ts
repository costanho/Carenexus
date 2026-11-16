import type { PasswordStrength } from './register.types';

/** Regex used by both the Zod schema and any standalone phone checks. */
export const PHONE_REGEX = /^\+\d+\s\d[\d\s\-()+]{4,}$/;

/** The phone format the API accepts (ValidationPatterns.PHONE): 7 to 15 digits with optional separators. */
export const API_PHONE_REGEX = /^\+?(?:[ .()-]*\d){7,15}[ .()-]*$/;

/** Length of a string in UTF-8 bytes (the unit of the password limit). */
export function utf8ByteLength(value: string): number {
  let bytes = 0;
  for (const char of value) {
    const code = char.codePointAt(0) ?? 0;
    bytes += code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4;
  }
  return bytes;
}

/**
 * Returns the visual strength level of a password.
 * Level 0 = empty, 1 = weak, 2 = good, 3 = strong.
 */
export function getPasswordStrength(password: string): PasswordStrength {
  const level: PasswordStrength['level'] =
    !password            ? 0
    : password.length < 6  ? 1
    : password.length < 10 ? 2
    :                        3;

  const colors: Record<PasswordStrength['level'], string> = {
    0: 'transparent',
    1: '#B91C1C',
    2: '#4F46E5',
    3: '#047857',
  };

  const labels: Record<PasswordStrength['level'], string> = {
    0: '',
    1: 'Weak',
    2: 'Good',
    3: 'Strong',
  };

  return { level, color: colors[level], label: labels[level] };
}

/** Returns true when a full phone string (dial + local) satisfies the schema regex. */
export function isValidPhone(phone: string): boolean {
  return PHONE_REGEX.test(phone);
}
