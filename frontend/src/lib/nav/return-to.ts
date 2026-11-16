import { Platform } from 'react-native';
import type { AppRole } from '@/lib/auth/auth.types';
import { buildSectionHref, parseActionUrl } from './action-url';

// A link to a dashboard section (from an email or a bookmark) opened while signed out goes through sign-in.
// The section is remembered for this browser tab and opened right after signing in.

const KEY = 'carenexus_return_to';

function tabStorage(): Storage | null {
  if (Platform.OS !== 'web' || typeof window === 'undefined') return null;
  try {
    return window.sessionStorage;
  } catch {
    return null; // storage blocked by the browser
  }
}

/**
 * Remembers the dashboard section in `url` (default: the current address), stored in its rebuilt form, never as
 * received. The newest dashboard link opened while signed out decides where sign-in goes: a link to a dashboard
 * home, or to a dashboard page that is not a section (a call, the booking page), forgets a section remembered
 * before, so sign-in does not reopen an older link.
 */
export function rememberReturnTo(url?: string): void {
  const storage = tabStorage();
  if (!storage) return;
  const parsed = parseActionUrl(url ?? `${window.location.pathname}${window.location.search}`);
  try {
    if (!parsed || (!parsed.section && Object.keys(parsed.params).length === 0)) {
      storage.removeItem(KEY); // the home needs no memory, and a page that is not a section cannot be reopened
      return;
    }
    storage.setItem(KEY, buildSectionHref(parsed.role, parsed.section, parsed.params));
  } catch {
    // storage full or blocked: signing in simply lands on the dashboard home
  }
}

/**
 * The remembered section link for a person who just signed in, or null. The memory is cleared either way, and
 * a link into another role's dashboard is dropped.
 */
export function consumeReturnTo(role: AppRole): string | null {
  const storage = tabStorage();
  if (!storage) return null;
  let stored: string | null = null;
  try {
    stored = storage.getItem(KEY);
    storage.removeItem(KEY);
  } catch {
    return null;
  }
  const parsed = parseActionUrl(stored);
  if (!parsed || parsed.role !== role) return null;
  return buildSectionHref(parsed.role, parsed.section, parsed.params);
}
