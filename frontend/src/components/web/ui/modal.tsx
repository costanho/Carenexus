import type { ReactNode } from 'react';
import { Modal as NativeModal, Pressable, ScrollView, StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import { ariaLevel, decorative, IS_WEB, webOnly } from './a11y';
import { IconButton } from './button';
import { colors, fontSize, PHONE_BREAKPOINT, radius, space } from './tokens';

// Dialogs. Both are built on React Native's Modal, which react-native-web renders in a portal at the end of
// <body> (so no parent can clip it or paint over it) with role="dialog" and aria-modal. It moves focus into the
// dialog when it opens, keeps Tab inside it, calls onRequestClose on Escape and puts focus back on the control
// that opened the dialog when it closes. The role theme reaches the content through React context as usual.
// A dialog that holds a form passes `dismissOnBackdrop={false}` and `busy={submitting}`.

export type DialogSize = 'sm' | 'md' | 'lg';

/** What asked the dialog to close: the Close button, the Escape key (Back on Android), or a click outside it. */
export type DialogCloseReason = 'button' | 'escape' | 'backdrop';

export interface DialogProps {
  open:     boolean;
  /** The dialog's heading and its accessible name. */
  title:    string;
  /**
   * Called by the Close button, the Escape key and (unless `dismissOnBackdrop` is false) a click on the backdrop,
   * never while `busy`. The reason lets a form with unsaved input ask before discarding it, for example on
   * 'escape', which also arrives when Escape was pressed to dismiss a native select or date popup.
   */
  onClose:  (reason: DialogCloseReason) => void;
  children: ReactNode;
  /** Buttons pinned under the scrolling content (usually `FormActions`). */
  footer?:  ReactNode;
  size?:    DialogSize;
  /**
   * Whether a click outside the dialog closes it. Default true. Dialogs that hold a form pass false, so a stray
   * click cannot throw away what was typed.
   */
  dismissOnBackdrop?: boolean;
  /**
   * True while the dialog's submit is in flight: the backdrop and Escape do nothing and the Close button is
   * disabled, so the dialog cannot be dismissed under a running request.
   */
  busy?:    boolean;
}

/** `onClose` per reason; every way out is switched off while `busy`. */
function closers({ onClose, busy = false, dismissOnBackdrop = true }: Pick<DialogProps, 'onClose' | 'busy' | 'dismissOnBackdrop'>) {
  return {
    button:   () => { if (!busy) onClose('button'); },
    escape:   () => { if (!busy) onClose('escape'); },
    backdrop: !busy && dismissOnBackdrop ? () => onClose('backdrop') : undefined,
  };
}

const MODAL_WIDTH: Record<DialogSize, number> = { sm: 400, md: 560, lg: 760 };
const PANEL_WIDTH: Record<DialogSize, number> = { sm: 360, md: 480, lg: 640 };

function Backdrop({ onClose }: { onClose?: () => void }) {
  // Pointer-only convenience: keyboard and screen-reader users close with Escape or the Close button.
  // On web it is a plain element with a click handler, so it can never take focus (the focus trap focuses the
  // first focusable element in the dialog, which must be a real control). Without `onClose` it only dims the page.
  if (IS_WEB || !onClose) {
    return <View style={styles.backdrop} {...decorative} {...(onClose ? webOnly({ onClick: onClose }) : null)} />;
  }
  return <Pressable style={styles.backdrop} onPress={onClose} accessible={false} {...decorative} />;
}

function Header({ title, onClose, busy }: { title: string; onClose: () => void; busy: boolean }) {
  return (
    <View style={styles.header}>
      <Text style={styles.title} accessibilityRole="header" {...ariaLevel(2)}>{title}</Text>
      <IconButton label="Close" icon="close" onPress={onClose} disabled={busy} />
    </View>
  );
}

/** A centred dialog for a short task or a confirmation. On a phone it takes the full width with a small margin. */
export function Modal({ open, title, onClose, children, footer, size = 'md', dismissOnBackdrop = true, busy = false }: DialogProps) {
  const { width, height } = useWindowDimensions();
  const phone = width < PHONE_BREAKPOINT;
  if (!open) return null;
  const close = closers({ onClose, busy, dismissOnBackdrop });
  return (
    <NativeModal visible transparent animationType="fade" onRequestClose={close.escape} aria-label={title}>
      <View style={[styles.overlay, styles.centered, { padding: phone ? space.md : space.xl }]}>
        <Backdrop onClose={close.backdrop} />
        <View style={[styles.surface, styles.modal, { maxWidth: MODAL_WIDTH[size], maxHeight: Math.max(240, height - (phone ? 24 : 48)) }]}>
          <Header title={title} onClose={close.button} busy={busy} />
          <ScrollView style={styles.bodyScroll} contentContainerStyle={styles.body} keyboardShouldPersistTaps="handled">
            {children}
          </ScrollView>
          {footer ? <View style={styles.footer}>{footer}</View> : null}
        </View>
      </View>
    </NativeModal>
  );
}

/**
 * A full-height panel on the right edge for details or a longer form (pass `dismissOnBackdrop={false}` for a
 * form). On a phone it covers the whole window.
 */
export function SidePanel({ open, title, onClose, children, footer, size = 'md', dismissOnBackdrop = true, busy = false }: DialogProps) {
  const { width } = useWindowDimensions();
  const phone = width < PHONE_BREAKPOINT;
  if (!open) return null;
  const close = closers({ onClose, busy, dismissOnBackdrop });
  return (
    <NativeModal visible transparent animationType="fade" onRequestClose={close.escape} aria-label={title}>
      <View style={[styles.overlay, styles.right]}>
        <Backdrop onClose={close.backdrop} />
        <View style={[styles.surface, styles.panel, phone ? styles.panelPhone : { maxWidth: PANEL_WIDTH[size] }]}>
          <Header title={title} onClose={close.button} busy={busy} />
          <ScrollView style={styles.bodyScroll} contentContainerStyle={styles.body} keyboardShouldPersistTaps="handled">
            {children}
          </ScrollView>
          {footer ? <View style={styles.footer}>{footer}</View> : null}
        </View>
      </View>
    </NativeModal>
  );
}

const styles = StyleSheet.create({
  overlay:  { flex: 1 },
  centered: { alignItems: 'center', justifyContent: 'center' },
  right:    { flexDirection: 'row', justifyContent: 'flex-end' },
  backdrop: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: colors.overlay },

  surface: {
    backgroundColor: colors.surface,
    boxShadow: '0 8px 24px rgba(0, 0, 0, 0.18)',
  },
  modal:      { width: '100%', borderRadius: radius.lg, overflow: 'hidden' },
  panel:      { width: '100%', height: '100%' },
  panelPhone: { maxWidth: '100%' },

  header: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space.md,
    paddingLeft: space.lg, paddingRight: space.sm, paddingVertical: space.sm,
    borderBottomWidth: 1, borderBottomColor: colors.border,
  },
  title:      { flex: 1, minWidth: 0, fontSize: fontSize.xl, fontWeight: '700', color: colors.text },
  bodyScroll: { flexGrow: 1, flexShrink: 1 },
  body:       { padding: space.lg, gap: space.md },
  footer:     { paddingHorizontal: space.lg, paddingVertical: space.md, borderTopWidth: 1, borderTopColor: colors.border },
});
