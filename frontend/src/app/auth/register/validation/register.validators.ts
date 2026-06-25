import type { PasswordStrength } from './register.types';

/** Regex used by both the Zod schema and any standalone phone checks. */
export const PHONE_REGEX = /^\+\d+\s\d[\d\s\-()+]{4,}$/;

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
    1: '#EF4444',
    2: '#4F46E5',
    3: '#10B981',
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
