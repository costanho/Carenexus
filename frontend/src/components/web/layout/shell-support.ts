import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useWindowDimensions, type NativeScrollEvent, type NativeSyntheticEvent, type ScrollView } from 'react-native';
import { type CurrentUser, useCurrentUser } from '@/hooks/use-current-user';
import { useUnreadCounts } from '@/hooks/use-notifications';
import type { SectionNav } from '@/hooks/use-section-nav';
import { onSignedOut } from '@/lib/auth/auth.events';
import type { AppRole } from '@/lib/auth/auth.types';
import type { AppNotification } from '@/lib/communications/types';
import { followActionUrl } from '@/lib/nav/action-url';
import type { SectionBadge } from '@/lib/nav/sections';

// Opening a section adds a history entry, and the router mounts a fresh copy of the dashboard for it (the
// previous copy stays underneath for Back). The helpers here carry the small pieces of shell state across
// that hand-over, so the sidebar does not jump, flash "..." or lose keyboard focus on every navigation.

// ─── Breakpoints and drawer ───────────────────────────────────────────────────

export const PHONE_MAX = 768;
export const RAIL_MAX  = 1024;

export interface ShellLayout {
  width: number;
  /** Phones: the sidebar is a drawer behind the menu button. */
  isSmall: boolean;
  /** Below desktop width the permanent sidebar is an icon rail. */
  isCollapsed: boolean;
  isLarge: boolean;
  drawerOpen: boolean;
  openDrawer: () => void;
  closeDrawer: () => void;
}

export function useShellLayout(): ShellLayout {
  const { width } = useWindowDimensions();
  const isSmall = width < PHONE_MAX;
  const [drawerOpen, setDrawerOpen] = useState(false);
  const openDrawer  = useCallback(() => setDrawerOpen(true), []);
  const closeDrawer = useCallback(() => setDrawerOpen(false), []);
  // Wider than a phone the drawer is not shown; close it so it does not pop open again on the way back.
  useEffect(() => { if (!isSmall) setDrawerOpen(false); }, [isSmall]);
  return {
    width,
    isSmall,
    isCollapsed: width < RAIL_MAX,
    isLarge: width >= RAIL_MAX,
    drawerOpen: isSmall && drawerOpen,
    openDrawer,
    closeDrawer,
  };
}

// ─── Signed-in user ───────────────────────────────────────────────────────────

let lastUser: CurrentUser | null = null;
onSignedOut(() => { lastUser = null; });

/**
 * `useCurrentUser` for the shell chrome: while a freshly mounted copy is still reading the stored user it shows
 * the one the previous copy had, so names in the sidebar and top bar do not blink. Cleared on sign-out.
 */
export function useShellUser(): ReturnType<typeof useCurrentUser> {
  const state = useCurrentUser();
  if (state.user) lastUser = state.user;
  if (state.user || !state.loading || !lastUser) return state;
  return { ...state, user: lastUser, loading: false };
}

// ─── Sidebar badges ───────────────────────────────────────────────────────────

/**
 * Count for each badge source a registry entry can name. The top-bar bell polls the unread counts; this only
 * reads them. `alerts` and `requests` have no data source yet and stay at 0 (no badge is drawn for 0).
 */
export function useNavBadges(): Record<SectionBadge, number> {
  const { unreadCount, unreadMessageCount } = useUnreadCounts(false);
  return useMemo(
    () => ({ messages: unreadMessageCount, notifications: unreadCount, alerts: 0, requests: 0 }),
    [unreadCount, unreadMessageCount],
  );
}

// ─── Sidebar scroll position ──────────────────────────────────────────────────

const sidebarScroll = new Map<string, number>();
onSignedOut(() => sidebarScroll.clear());

/** Keeps the sidebar's scroll offset per role, so a navigation does not send a scrolled sidebar back to the top. */
export function useSidebarScrollMemory(id: string) {
  const ref = useRef<ScrollView>(null);

  useEffect(() => {
    const y = sidebarScroll.get(id) ?? 0;
    if (y > 0) ref.current?.scrollTo({ y, animated: false });
  }, [id]);

  const onScroll = useCallback((e: NativeSyntheticEvent<NativeScrollEvent>) => {
    sidebarScroll.set(id, e.nativeEvent.contentOffset.y);
  }, [id]);

  return { ref, onScroll, scrollEventThrottle: 64 } as const;
}

// ─── Keyboard focus across a navigation ───────────────────────────────────────

// What takes keyboard focus in the freshly mounted copy (the same sidebar item, the menu button after a drawer
// navigation, otherwise the section itself) lives in lib/nav/nav-focus.ts, next to the navigation that uses it.
export { MENU_FOCUS_KEY, rememberNavFocus, useNavFocusRestore } from '@/lib/nav/nav-focus';

// ─── Notifications ────────────────────────────────────────────────────────────

export interface NotificationActions {
  /** MESSAGE notifications: opens Messages on the conversation. */
  onOpenThread: (threadId: number) => void;
  /** Patient only: caregiver approval requests open the caregiver access section. */
  onOpenCaregiverAccess?: () => void;
  /** Every other notification follows its `actionUrl`; one without a usable link is informational. */
  onActivated: (item: AppNotification) => void;
  /** Follows an in-app link directly (for components that hand over the `actionUrl` instead of the item). */
  onNavigate: (actionUrl: string | null | undefined) => boolean;
}

function handledByCallbacks(item: AppNotification, role: AppRole): boolean {
  if (item.type === 'MESSAGE' && item.threadId != null) return true;       // onOpenThread
  return role === 'patient' && item.target === 'CAREGIVER_ACCESS';         // onOpenCaregiverAccess
}

/**
 * Click handling for the notification list and bell. Message notifications keep the thread callback; the rest
 * go through `followActionUrl`, so a new notification type deep-links without a callback of its own.
 */
export function useNotificationActions(nav: SectionNav): NotificationActions {
  const { role, navigate, openThread, hasSection, homeKey } = nav;
  return useMemo<NotificationActions>(() => {
    const onNavigate = (actionUrl: string | null | undefined) =>
      followActionUrl(actionUrl, { role, navigate, hasSection, homeKey });
    return {
      onOpenThread: openThread,
      onOpenCaregiverAccess: role === 'patient' && hasSection('my-proxies') ? () => navigate('my-proxies') : undefined,
      onActivated: (item: AppNotification) => {
        if (!handledByCallbacks(item, role)) onNavigate(item.actionUrl);
      },
      onNavigate,
    };
  }, [role, navigate, openThread, hasSection, homeKey]);
}

/** What a top bar hands to its notification bell. */
export interface BellProps {
  theme: { accent: string; accentBg: string };
  /** MESSAGE notifications: opens Messages on the conversation. */
  onOpenThread: (threadId: number) => void;
  /** Patient only: caregiver approval requests open the caregiver access section. */
  onOpenCaregiverAccess?: () => void;
  /**
   * Every other notification: follows its in-app link (`NotificationActions.onNavigate`). The bell calls it
   * from the release in which the notification components open every notification type through its
   * `actionUrl`; a bell without that prop ignores it, and only the full-page list follows links.
   */
  onNavigate?: (actionUrl: string | null | undefined) => boolean;
}

/**
 * Builds the bell's props as one object. The top bars spread it into `NotificationBell`, so the link handler
 * reaches the bell as soon as it accepts `onNavigate`, with no change needed in the bars or the shells.
 */
export function bellProps(props: BellProps): BellProps {
  return props;
}
