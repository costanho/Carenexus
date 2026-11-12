import { Ionicons } from '@expo/vector-icons';
import { useEffect, useRef, useState } from 'react';
import { Platform, StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';
import { useUnreadCounts } from '@/hooks/use-notifications';
import { DEFAULT_THEME, type MessagesTheme } from '../messages/ui';
import { NotificationList } from './notification-list';

interface Props {
  theme?:       MessagesTheme;
  /** Open the messages section on this thread. */
  onOpenThread: (threadId: number) => void;
  /** Patient only: open the caregiver access screen from an approval request. */
  onOpenCaregiverAccess?: () => void;
  /**
   * Follows the in-app link of every other notification (the dashboards pass `followActionUrl`). Returning
   * false means the link was not followed (another dashboard's link, an unknown section): the panel stays open.
   */
  onNavigate?: (actionUrl: string | null | undefined) => boolean | void;
  /** Optional dark style (e.g. on a dark navbar). */
  iconColor?:   string;
}

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

function focusablesIn(root: HTMLElement): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE))
    .filter(el => el.getAttribute('aria-disabled') !== 'true' && el.getClientRects().length > 0);
}

/**
 * Top-navbar bell: unread badge (polled every 30 s while active) and a dropdown preview that renders and opens
 * notifications exactly like the full-page list.
 */
export function NotificationBell({ theme = DEFAULT_THEME, onOpenThread, onOpenCaregiverAccess, onNavigate, iconColor = '#374151' }: Props) {
  const { width } = useWindowDimensions();
  const { unreadCount } = useUnreadCounts();
  const [open, setOpen] = useState(false);
  const narrow = width < 520;
  const bellRef  = useRef<any>(null);
  const panelRef = useRef<any>(null);
  /** Return focus to the bell when the panel closes (not when a notification opened a conversation). */
  const refocusBell = useRef(false);

  function close(returnFocus: boolean) {
    refocusBell.current = returnFocus;
    setOpen(false);
  }

  // Web: focus moves into the panel on open and stays there (Tab wraps); Escape closes it and
  // focus goes back to the bell.
  useEffect(() => {
    if (Platform.OS !== 'web' || typeof document === 'undefined') return;
    if (!open) {
      if (refocusBell.current) {
        refocusBell.current = false;
        bellRef.current?.focus?.();
      }
      return;
    }
    const panel = panelRef.current as HTMLElement | null;
    panel?.focus?.();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        close(true);
        return;
      }
      if (e.key !== 'Tab' || !panel) return;
      const items = focusablesIn(panel);
      const active = document.activeElement as HTMLElement | null;
      if (items.length === 0) {
        e.preventDefault();
        panel.focus();
        return;
      }
      const first = items[0];
      const last  = items[items.length - 1];
      const inside = !!active && panel.contains(active) && active !== panel;
      if (e.shiftKey && (!inside || active === first)) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && (!inside || active === last)) {
        e.preventDefault();
        first.focus();
      }
    };
    // Anything else that takes focus while the panel is open (e.g. a click outside) is sent back.
    const onFocusIn = (e: FocusEvent) => {
      if (panel && e.target instanceof Node && !panel.contains(e.target)) panel.focus();
    };
    document.addEventListener('keydown', onKey);
    document.addEventListener('focusin', onFocusIn);
    return () => {
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('focusin', onFocusIn);
    };
  }, [open]);

  // The panel closes when a notification opens something. An informational one (no link) is only marked
  // read, so the panel stays open and the person can carry on reading.
  function openThread(threadId: number) {
    close(false); // Messages takes focus for the conversation
    onOpenThread(threadId);
  }

  function openCaregiverAccess() {
    close(true);
    onOpenCaregiverAccess?.();
  }

  function navigate(actionUrl: string | null | undefined): boolean | void {
    const followed = onNavigate?.(actionUrl);
    // The new section takes focus when it mounts; the bell has it until then, and keeps it when the link
    // points at the section that is already open.
    if (followed !== false) close(true);
    return followed;
  }

  const label = unreadCount > 0 ? `Notifications, ${unreadCount} unread` : 'Notifications';

  return (
    <View style={styles.anchor}>
      <TouchableOpacity
        ref={bellRef}
        style={styles.bell}
        onPress={() => (open ? close(true) : setOpen(true))}
        activeOpacity={0.7}
        accessibilityRole="button"
        accessibilityLabel={label}
        accessibilityState={{ expanded: open }}
        aria-expanded={open}
      >
        <Ionicons aria-hidden name={unreadCount > 0 ? 'notifications' : 'notifications-outline'} size={21} color={iconColor} importantForAccessibility="no" />
        {unreadCount > 0 && (
          <View aria-hidden style={styles.badge} importantForAccessibility="no-hide-descendants">
            <Text style={styles.badgeText}>{unreadCount > 99 ? '99+' : unreadCount}</Text>
          </View>
        )}
      </TouchableOpacity>

      {open && (
        <>
          {/* Click-away layer (pointer only; keyboard users have Escape and the Close button). */}
          <TouchableOpacity
            style={styles.backdrop}
            onPress={() => close(true)}
            accessible={false}
            focusable={false}
            importantForAccessibility="no"
            aria-hidden
            activeOpacity={1}
          />
          <View
            ref={panelRef}
            style={[styles.panel, narrow ? styles.panelNarrow : styles.panelWide]}
            role="dialog"
            aria-modal
            aria-label="Notifications"
            accessibilityViewIsModal
            {...({ tabIndex: -1 } as object)}
          >
            <NotificationList
              compact
              theme={theme}
              onOpenThread={openThread}
              onOpenCaregiverAccess={onOpenCaregiverAccess ? openCaregiverAccess : undefined}
              onNavigate={onNavigate ? navigate : undefined}
              onClose={() => close(true)}
            />
          </View>
        </>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  anchor: { position: 'relative', zIndex: 50 },
  bell:   { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center' },
  badge: {
    position: 'absolute', top: 3, right: 2,
    minWidth: 17, height: 17, borderRadius: 9, paddingHorizontal: 4,
    backgroundColor: '#DC2626', alignItems: 'center', justifyContent: 'center',
    borderWidth: 1.5, borderColor: '#FFFFFF',
  },
  badgeText: { color: '#FFFFFF', fontSize: 9, fontWeight: '700' },
  backdrop: {
    position: 'fixed' as any, top: 0, left: 0, right: 0, bottom: 0,
    backgroundColor: 'transparent', cursor: 'default' as any,
  },
  panel: {
    position: 'absolute', top: 46,
    backgroundColor: '#FFFFFF', borderRadius: 12, borderWidth: 1, borderColor: '#E5E7EB',
    shadowColor: '#000', shadowOffset: { width: 0, height: 6 }, shadowOpacity: 0.12, shadowRadius: 18, elevation: 12,
    overflow: 'hidden',
    outlineStyle: 'none' as any, // the panel itself only takes focus to announce the dialog
  },
  panelWide:   { right: 0, width: 380 },
  panelNarrow: { position: 'fixed' as any, top: 70, left: 12, right: 12 },
});
