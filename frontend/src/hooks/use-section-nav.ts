import { type Href, useLocalSearchParams, usePathname, useRouter } from 'expo-router';
import { createContext, useCallback, useContext, useEffect, useMemo, useRef } from 'react';
import { isAppRole, type AppRole } from '@/lib/auth/auth.types';
import { buildSectionHref } from '@/lib/nav/action-url';
import { forgetNavFocus, noteSectionNavigation } from '@/lib/nav/nav-focus';
import {
  findSection, resolveSection,
  type SectionDef, type SectionParams, type SectionParamsInput, type SectionRegistry,
} from '@/lib/nav/sections';

/**
 * Section navigation of a role dashboard. The active section and its params live in the URL
 * (`/dashboard/<role>?section=<key>&<param>=<value>`), so a refresh, the Back button and links all work.
 */
export interface SectionNav {
  role: AppRole;
  /** Key of the section being shown: validated against the registry, aliases followed, unknown -> home. */
  section: string;
  /** Registry entry of the active section; null only when the hook is used outside a dashboard shell. */
  def: SectionDef | null;
  homeKey: string;
  /** URL params other than `section`. The object keeps its identity until a param changes. */
  params: SectionParams;
  /** Opens a section and adds a history entry (Back returns to the current one). Unknown keys open home. */
  navigate: (section: string, params?: SectionParamsInput) => void;
  /** Like `navigate`, but replaces the current history entry. */
  replace: (section: string, params?: SectionParamsInput) => void;
  /**
   * Updates params of the current section in place (no history entry, the section stays mounted). Use it for
   * in-screen state that should survive a refresh: selected tab, selected patient, opened row. A value of
   * undefined removes the param.
   */
  setParams: (params: SectionParamsInput) => void;
  /** Opens Messages on a conversation. */
  openThread: (threadId: number) => void;
  /** True when the dashboard has this section (aliases included). */
  hasSection: (section: string) => boolean;
  /** URL of a section of this dashboard, for links. */
  href: (section: string, params?: SectionParamsInput) => string;
}

/** Provided by each dashboard shell so screens can call `useSectionNav()` without knowing their registry. */
export const SectionNavContext = createContext<SectionNav | null>(null);

function first(value: string | string[] | null | undefined): string | undefined {
  const v = Array.isArray(value) ? value[0] : value;
  return v == null || v === '' ? undefined : String(v);
}

/** A URL param that belongs to the section: not `section` itself and not one of the router's internal params. */
function isSectionParam(name: string): boolean {
  return name !== 'section' && !name.startsWith('__');
}

function roleFromPath(pathname: string): AppRole {
  const parts = pathname.split('/').filter(Boolean);
  return parts[0] === 'dashboard' && isAppRole(parts[1]) ? parts[1] : 'patient';
}

/**
 * Reads and writes the active section and its params.
 *
 * - A dashboard shell passes its registry: the hook validates `section` against it and returns the value the
 *   shell then shares through `SectionNavContext`.
 * - Screens call it without arguments and get the shell's value.
 */
export function useSectionNav(registry?: SectionRegistry): SectionNav {
  const inherited = useContext(SectionNavContext);
  const router    = useRouter();
  const raw       = useLocalSearchParams() as Record<string, string | string[] | null | undefined>;
  const pathname  = usePathname();

  const role    = registry?.role ?? inherited?.role ?? roleFromPath(pathname);
  const homeKey = registry?.homeKey ?? inherited?.homeKey ?? 'dashboard';
  const requested = first(raw.section);

  // Params keep their identity while their content is unchanged, so effects that depend on them do not re-run.
  const paramsKey = Object.keys(raw)
    .filter(name => isSectionParam(name) && first(raw[name]) !== undefined)
    .sort()
    .map(name => `${name}=${first(raw[name])}`)
    .join('&');
  const params = useMemo<SectionParams>(() => {
    const out: SectionParams = {};
    for (const name of Object.keys(raw)) {
      if (!isSectionParam(name)) continue;
      const value = first(raw[name]);
      if (value !== undefined) out[name] = value;
    }
    return out;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [paramsKey]);

  const def = useMemo(
    () => (registry ? resolveSection(registry, requested) : null),
    [registry, requested],
  );
  const section = def?.key ?? requested ?? homeKey;

  // Latest values for the stable callbacks below.
  const current = useRef({ registry, section, params, role, homeKey });
  current.current = { registry, section, params, role, homeKey };

  const targetHref = useCallback((to: string, toParams?: SectionParamsInput): string => {
    const c = current.current;
    // With a registry, aliases resolve to their section and unknown keys to home, exactly as a URL would.
    const key = c.registry ? resolveSection(c.registry, to).key : to;
    return buildSectionHref(c.role, key, toParams, c.homeKey);
  }, []);

  const navigate = useCallback((to: string, toParams?: SectionParamsInput) => {
    const c = current.current;
    const next = targetHref(to, toParams);
    // Already there: do not stack a duplicate history entry (nothing mounts, so focus stays where it is).
    if (next === buildSectionHref(c.role, c.section, c.params, c.homeKey)) { forgetNavFocus(); return; }
    // The router mounts a fresh copy of the dashboard for the new entry; this decides what takes focus in it.
    noteSectionNavigation();
    router.push(next as Href);
  }, [router, targetHref]);

  const replace = useCallback((to: string, toParams?: SectionParamsInput) => {
    const c = current.current;
    const next = targetHref(to, toParams);
    if (next === buildSectionHref(c.role, c.section, c.params, c.homeKey)) { forgetNavFocus(); return; }
    noteSectionNavigation();
    router.replace(next as Href);
  }, [router, targetHref]);

  const setParams = useCallback((patch: SectionParamsInput) => {
    const c = current.current;
    const next: Record<string, string | undefined> = {};
    let changed = false;
    for (const name of Object.keys(patch)) {
      if (!isSectionParam(name)) continue; // changing section is navigation, not a param update
      const value = patch[name];
      const normalised = value === undefined || value === null || value === '' ? undefined : String(value);
      next[name] = normalised;
      if (c.params[name] !== normalised) changed = true;
    }
    if (changed) router.setParams(next as never);
  }, [router]);

  const openThread = useCallback((threadId: number) => {
    // Already in Messages: hand the conversation over in place instead of stacking another copy of the screen.
    if (current.current.section === 'messages') setParams({ thread: threadId });
    else navigate('messages', { thread: threadId });
  }, [navigate, setParams]);

  // Keep the address bar in step with what is shown: an alias or an unknown key in the URL is rewritten
  // (in place, no history entry) to the section that was actually opened.
  const canonical = def ? (def.key === homeKey ? undefined : def.key) : requested;
  useEffect(() => {
    if (registry && requested !== canonical) router.setParams({ section: canonical } as never);
  }, [registry, requested, canonical, router]);

  const hasSection = useCallback((key: string) => {
    const c = current.current;
    return c.registry ? !!findSection(c.registry.sections, key) : true;
  }, []);

  const own = useMemo<SectionNav>(
    () => ({ role, section, def, homeKey, params, navigate, replace, setParams, openThread, hasSection, href: targetHref }),
    [role, section, def, homeKey, params, navigate, replace, setParams, openThread, hasSection, targetHref],
  );

  return registry || !inherited ? own : inherited;
}
