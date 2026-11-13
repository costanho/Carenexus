// Design tokens for the web primitives (CONTRACTS 12.3). Every text/background pair listed here meets WCAG AA
// (4.5:1 for text, 3:1 for control borders); the measured ratio is noted next to each pair.

// The role colours have one home: the section registry types in lib/nav/sections.ts. They are re-exported here
// so screens can keep importing them from '@/components/web/ui'. Measured contrast of each accent:
//   patient teal   #0F766E:  5.47 on white, 4.90 on accentBg
//   doctor  indigo #4F46E5:  6.29 on white, 5.62 on accentBg
//   proxy   purple #7C3AED:  5.70 on white, 5.20 on accentBg
//   admin   slate  #334155: 10.35 on white, 9.45 on accentBg
import { ROLE_THEME, type RoleTheme } from '@/lib/nav/sections';

export { ROLE_THEME };
export type { AppRole, RoleTheme } from '@/lib/nav/sections';

export const DEFAULT_ROLE_THEME: RoleTheme = ROLE_THEME.patient;

export const colors = {
  text:         '#111827', // 16.98 on bg
  textMuted:    '#4B5563', //  7.56 on white, 6.87 on bgSubtle
  textSubtle:   '#5B6472', //  5.98 on white, 5.44 on bgSubtle
  placeholder:  '#6B7280', //  4.83 on white
  surface:      '#FFFFFF',
  bg:           '#F9FAFB',
  bgSubtle:     '#F3F4F6',
  border:       '#E5E7EB', // dividers and card outlines (decorative)
  borderInput:  '#6B7280', // input, select and switch outlines: 4.83 on white (3:1 needed)
  danger:       '#B91C1C', //  6.47 on white; white text on it 6.47
  dangerBg:     '#FEF2F2',
  disabledBg:   '#E5E7EB',
  disabledText: '#4B5563', //  6.10 on disabledBg
  overlay:      'rgba(17, 24, 39, 0.45)',
} as const;

export type StatusToneName = 'neutral' | 'info' | 'success' | 'warning' | 'danger' | 'critical';

export interface ToneColors {
  /** Text and icon colour. */
  fg:     string;
  bg:     string;
  border: string;
}

/** Status colours. A tone is always paired with a text label (and usually an icon), never used alone. */
export const STATUS_TONES: Record<StatusToneName, ToneColors> = {
  neutral:  { fg: '#374151', bg: '#F3F4F6', border: '#D1D5DB' }, // 9.37
  info:     { fg: '#1D4ED8', bg: '#EFF6FF', border: '#BFDBFE' }, // 6.16
  success:  { fg: '#047857', bg: '#ECFDF5', border: '#A7F3D0' }, // 5.21
  warning:  { fg: '#92400E', bg: '#FFFBEB', border: '#FDE68A' }, // 6.84
  danger:   { fg: '#B91C1C', bg: '#FEF2F2', border: '#FECACA' }, // 5.91
  critical: { fg: '#FFFFFF', bg: '#991B1B', border: '#7F1D1D' }, // 8.31 (solid, so it stands apart from danger)
};

export const space = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 } as const;

export const radius = { sm: 6, md: 8, lg: 12, pill: 999 } as const;

export const fontSize = { xs: 12, sm: 13, md: 14, lg: 16, xl: 18, title: 22 } as const;

/** Smallest height of anything pressable, so targets stay comfortable on touch screens. */
export const CONTROL_HEIGHT = 40;

/** Below this window width a screen is laid out as a phone: tighter padding, stacked rows. */
export const PHONE_BREAKPOINT = 640;

export const tokens = {
  ROLE_THEME, DEFAULT_ROLE_THEME, STATUS_TONES, colors, space, radius, fontSize, CONTROL_HEIGHT, PHONE_BREAKPOINT,
} as const;
