import { Ionicons } from '@expo/vector-icons';
import { type ReactNode, useEffect, useRef } from 'react';
import { Platform, Pressable, StyleSheet, TouchableOpacity, View } from 'react-native';

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

function focusablesIn(root: HTMLElement): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE))
    .filter(el => el.getAttribute('aria-disabled') !== 'true' && el.getClientRects().length > 0);
}

/**
 * Phone-width navigation for the web dashboards (patient, doctor, caregiver and admin): the full sidebar
 * slides over the page, with a backdrop, a Close menu button and Escape to close. Below the breakpoint the
 * dashboards show this instead of a permanent icon rail, so the page keeps the whole screen width.
 *
 * Web keyboard behaviour matches the notification panel: focus moves into the drawer when it opens and
 * stays there (Tab wraps), and when it closes focus goes back to whatever opened it (the menu button).
 */
export function NavDrawer({ open, onClose, closeColor = '#111827', children }: {
  open:        boolean;
  onClose:     () => void;
  /** Colour of the close icon, to contrast with the sidebar background. */
  closeColor?: string;
  children:    ReactNode;
}) {
  const panelRef = useRef<View>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!open || Platform.OS !== 'web' || typeof document === 'undefined') return;
    const opener = document.activeElement as HTMLElement | null;
    const panel = panelRef.current as unknown as HTMLElement | null;
    (panel && focusablesIn(panel)[0])?.focus?.();

    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        onCloseRef.current();
        return;
      }
      if (e.key !== 'Tab' || !panel) return;
      const items = focusablesIn(panel);
      if (items.length === 0) { e.preventDefault(); return; }
      const first = items[0];
      const last  = items[items.length - 1];
      const active = document.activeElement as HTMLElement | null;
      const inside = !!active && panel.contains(active);
      if (e.shiftKey && (!inside || active === first)) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && (!inside || active === last)) {
        e.preventDefault();
        first.focus();
      }
    };
    // Focus that lands behind the backdrop (e.g. a click on the page) is sent back into the drawer.
    const onFocusIn = (e: FocusEvent) => {
      if (panel && e.target instanceof Node && !panel.contains(e.target)) focusablesIn(panel)[0]?.focus();
    };
    document.addEventListener('keydown', onKey);
    document.addEventListener('focusin', onFocusIn);
    return () => {
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('focusin', onFocusIn);
      // Back to the menu button once the drawer is gone, if it is still on the page. A navigation from the
      // drawer replaces the whole dashboard; the new copy then gives focus to its own menu button.
      if (opener && opener.isConnected) setTimeout(() => { if (opener.isConnected) opener.focus?.(); }, 0);
    };
  }, [open]);

  if (!open) return null;
  return (
    <View style={styles.overlay} role="dialog" aria-modal aria-label="Navigation" accessibilityViewIsModal>
      <Pressable
        style={styles.backdrop}
        onPress={onClose}
        accessible={false}
        focusable={false}
        importantForAccessibility="no"
        aria-hidden
      />
      <View ref={panelRef} style={styles.panel}>
        {children}
        <TouchableOpacity
          style={styles.closeBtn}
          onPress={onClose}
          activeOpacity={0.7}
          accessibilityRole="button"
          accessibilityLabel="Close menu"
        >
          <Ionicons aria-hidden name="close" size={22} color={closeColor} importantForAccessibility="no" />
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  overlay:  { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, zIndex: 100, flexDirection: 'row' },
  backdrop: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(17, 24, 39, 0.45)' },
  panel: {
    height: '100%', maxWidth: '85%',
    shadowColor: '#000', shadowOffset: { width: 2, height: 0 }, shadowOpacity: 0.2, shadowRadius: 8, elevation: 10,
  },
  closeBtn: { position: 'absolute', top: 10, right: 8, padding: 6, borderRadius: 8 },
});
