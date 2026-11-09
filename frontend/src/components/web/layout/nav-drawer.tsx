import { Ionicons } from '@expo/vector-icons';
import { type ReactNode, useEffect } from 'react';
import { Platform, Pressable, StyleSheet, TouchableOpacity, View } from 'react-native';

/**
 * Phone-width navigation for the web dashboards: the full sidebar slides over the page, with a backdrop,
 * a Close menu button and Escape to close. Below the breakpoint the dashboards show this instead of a
 * permanent icon rail, so the page keeps the whole screen width (same pattern as the patient dashboard).
 */
export function NavDrawer({ open, onClose, closeColor = '#111827', children }: {
  open:        boolean;
  onClose:     () => void;
  /** Colour of the close icon, to contrast with the sidebar background. */
  closeColor?: string;
  children:    ReactNode;
}) {
  useEffect(() => {
    if (!open || Platform.OS !== 'web' || typeof document === 'undefined') return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);

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
      <View style={styles.panel}>
        {children}
        <TouchableOpacity
          style={styles.closeBtn}
          onPress={onClose}
          activeOpacity={0.7}
          accessibilityRole="button"
          accessibilityLabel="Close menu"
        >
          <Ionicons name="close" size={22} color={closeColor} importantForAccessibility="no" />
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
