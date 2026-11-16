import { useEffect, useRef } from 'react';
import { Platform } from 'react-native';

// Opening a section mounts a fresh copy of the dashboard, so the control that was pressed is gone and the
// browser would drop keyboard focus to the top of the page. This module remembers what started the
// navigation and hands focus to the matching element of the new copy:
//   - a sidebar item      -> the same item in the new sidebar (the person keeps their place in the list);
//   - the phone drawer    -> the menu button (the drawer is closed in the new copy);
//   - anything else       -> the section container, so reading and tabbing continue at the new section
//                            (top-bar profile button, notification bell, buttons inside a screen).
// Web only; the Back and Forward buttons are left to the browser.

/** Focus target of a navigation started from the phone drawer: the top bar's menu button. */
export const MENU_FOCUS_KEY = '@menu';
const CONTENT_FOCUS_KEY = '@content';

/** A target is honoured only by a copy that mounts this soon after the navigation. */
const FOCUS_WINDOW_MS = 1500;
/** An explicit target counts for a navigation that starts within this time (same press handler). */
const SAME_PRESS_MS = 250;

let pending: { target: string; at: number } | null = null;

type Focusable = { focus?: (options?: { preventScroll?: boolean }) => void } | null;

/**
 * Call just before navigating from a control that has a counterpart in the next copy of the dashboard:
 * a sidebar item (its section key) or the phone drawer (`MENU_FOCUS_KEY`). A later call in the same press
 * replaces an earlier one, so the drawer wins over the sidebar item inside it.
 */
export function rememberNavFocus(target: string): void {
  pending = { target, at: Date.now() };
}

/**
 * Called by section navigation when it adds or replaces a history entry. Keeps a target remembered by the
 * control that was just pressed; otherwise the new section itself takes focus.
 */
export function noteSectionNavigation(): void {
  const now = Date.now();
  if (pending && now - pending.at <= SAME_PRESS_MS) pending.at = now;
  else pending = { target: CONTENT_FOCUS_KEY, at: now };
}

/** Called when a navigation turned out to be a no-op (already there): nothing mounts, focus stays put. */
export function forgetNavFocus(): void {
  pending = null;
}

function take(target: string | null): boolean {
  if (!pending || (target !== null && pending.target !== target)) return false;
  const fresh = Date.now() - pending.at < FOCUS_WINDOW_MS;
  pending = null;
  return fresh;
}

/**
 * Ref for a sidebar item (pass its section key) or the menu button (pass `MENU_FOCUS_KEY`). Right after a
 * navigation that remembered this target, the newly mounted element takes keyboard focus.
 */
export function useNavFocusRestore(target: string) {
  const ref = useRef<unknown>(null);
  useEffect(() => {
    if (Platform.OS !== 'web') return;
    const el = ref.current as Focusable;
    // Only claim the target when the element is really there to take it.
    if (el?.focus && take(target)) el.focus({ preventScroll: true });
  }, [target]);
  return ref;
}

/**
 * Ref for the section container (focusable from script only). After a navigation whose target was not
 * claimed by a sidebar item or the menu button, the container takes focus, so a keyboard or screen-reader
 * user continues at the new section instead of the top of the page.
 */
export function useSectionFocusRestore() {
  const ref = useRef<unknown>(null);
  useEffect(() => {
    if (Platform.OS !== 'web' || !pending) return;
    // After every effect of this mount has run, so a sidebar item or the menu button claims its target first
    // wherever it sits in the tree.
    const timer = setTimeout(() => {
      const el = ref.current as Focusable;
      if (el?.focus && take(null)) el.focus({ preventScroll: true });
    }, 0);
    return () => clearTimeout(timer);
  }, []);
  return ref;
}
