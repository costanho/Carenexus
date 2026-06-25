import { Ionicons } from '@expo/vector-icons';
import type { z } from 'zod';
import type { registerSchema } from './register.schema';

// ── Primitive types ────────────────────────────────────────────────────────────
export type Role         = 'patient' | 'doctor' | 'proxy';
export type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

// ── Form data type ─────────────────────────────────────────────────────────────
export type RegisterFormData = z.infer<typeof registerSchema>;

// ── Role config ────────────────────────────────────────────────────────────────
export type RoleConfig = {
  key:   Role;
  label: string;
  icon:  IoniconsName;
  color: string;
  bg:    string;
  desc?: string; // shown on web panel; optional so mobile can skip it
};

// ── Password strength ──────────────────────────────────────────────────────────
export type PasswordStrength = {
  level: 0 | 1 | 2 | 3;
  color: string;
  label: string;
};

// ── Shared colour palette ──────────────────────────────────────────────────────
export const COLORS = {
  TEAL:   '#0D9488',
  PURPLE: '#7C3AED',
  INDIGO: '#4F46E5',
  NAVY:   '#0D1B2E',
  WHITE:  '#FFFFFF',
  TEXT:   '#111827',
  GRAY:   '#6B7280',
  BORDER: '#E5E7EB',
  BG:     '#F9FAFB',
  RED:    '#EF4444',
  GREEN:  '#10B981',
} as const;

// ── Shared role definitions ────────────────────────────────────────────────────
export const REGISTER_ROLES: RoleConfig[] = [
  {
    key:   'patient',
    label: 'Patient',
    icon:  'person-outline',
    color: COLORS.TEAL,
    bg:    '#E6F4F1',
    desc:  'Manage your health records',
  },
  {
    key:   'doctor',
    label: 'Doctor',
    icon:  'medical-outline',
    color: COLORS.INDIGO,
    bg:    '#EEF2FF',
    desc:  'Manage your practice',
  },
  {
    key:   'proxy',
    label: 'Proxy / Carer',
    icon:  'people-outline',
    color: COLORS.PURPLE,
    bg:    '#F5F3FF',
    desc:  'Care for a loved one',
  },
];
