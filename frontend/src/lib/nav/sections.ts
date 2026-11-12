import type { Ionicons } from '@expo/vector-icons';
import type { ComponentProps, ReactElement } from 'react';
import type { AppRole } from '@/lib/auth/auth.types';

export type { AppRole } from '@/lib/auth/auth.types';

export type IoniconsName = ComponentProps<typeof Ionicons>['name'];

/** Accent colours of a role's dashboard. */
export interface RoleTheme {
  /** Buttons, links, active states. 4.5:1 or better on white and on `accentBg`. */
  accent:     string;
  /** Tint behind selected or unread items. */
  accentBg:   string;
  /** Text and icons drawn on top of `accent`. */
  accentText: string;
}

export const ROLE_THEME: Record<AppRole, RoleTheme> = {
  patient: { accent: '#0F766E', accentBg: '#E8F5F4', accentText: '#FFFFFF' },
  doctor:  { accent: '#4F46E5', accentBg: '#EEF2FF', accentText: '#FFFFFF' },
  proxy:   { accent: '#7C3AED', accentBg: '#F5F3FF', accentText: '#FFFFFF' },
  admin:   { accent: '#334155', accentBg: '#F1F5F9', accentText: '#FFFFFF' },
};

/** URL params of a section other than `section` itself. A param that is not in the URL is undefined. */
export type SectionParams = Record<string, string | undefined>;

/** Values accepted when writing params; undefined (or an empty string) removes the param from the URL. */
export type SectionParamsInput = Record<string, string | number | undefined>;

/** Sources of the count shown next to a sidebar item. */
export type SectionBadge = 'messages' | 'notifications' | 'alerts' | 'requests';

export interface SectionProps {
  role: AppRole;
  /** URL params other than `section` (for example `patient`, `id`, `tab`). */
  params: SectionParams;
  /** Opens another section of the same dashboard and adds a history entry, so Back returns here. */
  navigate: (section: string, params?: SectionParamsInput) => void;
  /** Opens Messages on this conversation. */
  openThread: (threadId: number) => void;
  theme: RoleTheme;
}

export interface SectionDef {
  /** Stable key used in the URL (`?section=<key>`) and by backend notification links. */
  key: string;
  label: string;
  icon: IoniconsName;
  /** Sidebar group title; omit for the home section and with `nav: false`. */
  group?: string;
  /** Default true. False = reachable by URL and in-app links but not listed in the sidebar. */
  nav?: boolean;
  badge?: SectionBadge;
  /** Patient shell only: show the right-hand summary column beside this section on wide screens. */
  rightSidebar?: boolean;
  /**
   * This entry is another name for the section with this key: it is listed in the sidebar, and opening it
   * (from the sidebar or by URL) opens the target section.
   */
  aliasOf?: string;
  /** Renders the section. Lazy components are wrapped here; the shell supplies Suspense and the error boundary. */
  render: (props: SectionProps) => ReactElement;
}

/** What each `<role>.sections.tsx` file exports. */
export interface SectionRegistry {
  role: AppRole;
  sections: SectionDef[];
  homeKey: string;
}

/**
 * URL params with a fixed meaning in every section (CONTRACTS 12.1). They are names to reuse, not a filter:
 * every query param other than `section` reaches the section in `SectionProps.params`.
 */
export const RESERVED_PARAMS = ['patient', 'id', 'tab', 'appointmentId', 'consultationId', 'date', 'thread', 'mode'] as const;

export function findSection(sections: readonly SectionDef[], key: string | undefined | null): SectionDef | undefined {
  if (!key) return undefined;
  return sections.find(s => s.key === key);
}

/**
 * The section a URL key stands for: aliases are followed to their target, and a missing or unknown key
 * resolves to the home section.
 */
export function resolveSection(registry: SectionRegistry, key: string | undefined | null): SectionDef {
  const home = findSection(registry.sections, registry.homeKey);
  if (!home) throw new Error(`Section registry for ${registry.role} has no home section "${registry.homeKey}"`);
  let def = findSection(registry.sections, key);
  // Follow aliases (bounded, so a mistaken cycle cannot loop forever).
  for (let hops = 0; def?.aliasOf && hops < 5; hops++) def = findSection(registry.sections, def.aliasOf);
  return def && !def.aliasOf ? def : home;
}

export interface SectionGroup {
  title: string;
  items: SectionDef[];
}

/**
 * Sidebar layout of a registry: the home entry first, then the groups in the order their first entry appears.
 * Entries with `nav: false` are left out; an entry without a group is listed under `ungroupedTitle`.
 */
export function navGroups(registry: SectionRegistry, ungroupedTitle = 'MORE'): { home: SectionDef; groups: SectionGroup[] } {
  const home = resolveSection(registry, registry.homeKey);
  const groups: SectionGroup[] = [];
  for (const def of registry.sections) {
    if (def.key === home.key || def.nav === false) continue;
    const title = def.group ?? ungroupedTitle;
    const group = groups.find(g => g.title === title);
    if (group) group.items.push(def);
    else groups.push({ title, items: [def] });
  }
  return { home, groups };
}

/** Accessible name of a sidebar item: the visible label, plus the unread count when there is one. */
export function navItemLabel(def: SectionDef, badge: number): string {
  return badge > 0 ? `${def.label}, ${badge} unread` : def.label;
}
