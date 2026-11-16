import { ROLE_HOME, isAppRole, type AppRole } from '@/lib/auth/auth.types';
import type { SectionParams, SectionParamsInput } from './sections';

/**
 * In-app links have the form `/dashboard/<role>?section=<key>[&patient=<PAT id>][&id=<resource id>][&tab=<tab>]`
 * (CONTRACTS 7.1). The backend puts them in `notification.actionUrl`; the dashboards build the same URLs when
 * a section is opened, so a link, a refresh and the Back button all land on the same screen.
 */
export interface ParsedActionUrl {
  role: AppRole;
  /** Section key from the URL; undefined means the role's home section. */
  section: string | undefined;
  /** Every other query param. */
  params: SectionParams;
}

function decode(value: string): string {
  try {
    return decodeURIComponent(value.replace(/\+/g, ' '));
  } catch {
    return value;
  }
}

/** Parses an in-app dashboard link. Returns null for anything else (external URLs, other routes, empty values). */
export function parseActionUrl(url: string | null | undefined): ParsedActionUrl | null {
  if (typeof url !== 'string') return null;
  let rest = url.trim();
  if (!rest) return null;

  // Accept an absolute link to this app (as used in emails) by dropping the origin; other schemes are not links.
  const origin = /^https?:\/\/[^/?#]+/i.exec(rest);
  if (origin) rest = rest.slice(origin[0].length) || '/';
  if (!rest.startsWith('/') || rest.startsWith('//')) return null;

  const hashAt = rest.indexOf('#');
  if (hashAt >= 0) rest = rest.slice(0, hashAt);
  const queryAt = rest.indexOf('?');
  const path  = (queryAt >= 0 ? rest.slice(0, queryAt) : rest).replace(/\/+$/, '');
  const query = queryAt >= 0 ? rest.slice(queryAt + 1) : '';

  const segments = path.split('/').filter(Boolean);
  if (segments.length !== 2 || segments[0] !== 'dashboard' || !isAppRole(segments[1])) return null;

  let section: string | undefined;
  const params: SectionParams = {};
  for (const pair of query.split('&')) {
    if (!pair) continue;
    const eq    = pair.indexOf('=');
    const name  = decode(eq >= 0 ? pair.slice(0, eq) : pair);
    const value = decode(eq >= 0 ? pair.slice(eq + 1) : '');
    if (!name || value === '') continue;
    if (name === 'section') section = value;
    else params[name] = value;
  }
  return { role: segments[1], section, params };
}

/** The URL of a section: `/dashboard/<role>` for the home section, otherwise with `?section=` and its params. */
export function buildSectionHref(
  role: AppRole,
  section: string | undefined,
  params?: SectionParamsInput,
  homeKey = 'dashboard',
): string {
  const parts: string[] = [];
  if (section && section !== homeKey) parts.push(`section=${encodeURIComponent(section)}`);
  if (params) {
    for (const name of Object.keys(params)) {
      const value = params[name];
      if (name === 'section' || value === undefined || value === null || value === '') continue;
      parts.push(`${encodeURIComponent(name)}=${encodeURIComponent(String(value))}`);
    }
  }
  return parts.length ? `${ROLE_HOME[role]}?${parts.join('&')}` : ROLE_HOME[role];
}

/** The part of section navigation that `followActionUrl` needs (satisfied by `useSectionNav()`). */
export interface ActionUrlNav {
  role: AppRole;
  navigate: (section: string, params?: SectionParamsInput) => void;
  /** Present when the caller knows its registry: lets a link to an unknown section be reported as not followed. */
  hasSection?: (section: string) => boolean;
  homeKey?: string;
}

/**
 * Opens the section an in-app link points at. Returns false (and does nothing) when the value is not a
 * dashboard link, belongs to another role's dashboard, or names a section this dashboard does not have;
 * such a notification is informational.
 */
export function followActionUrl(url: string | null | undefined, nav: ActionUrlNav): boolean {
  const parsed = parseActionUrl(url);
  if (!parsed || parsed.role !== nav.role) return false;
  const section = parsed.section ?? nav.homeKey ?? 'dashboard';
  if (nav.hasSection && !nav.hasSection(section)) return false;
  nav.navigate(section, parsed.params);
  return true;
}
