import { type RefObject, useEffect } from 'react';
import { Platform } from 'react-native';

/**
 * Props that make a View a programmatic focus target on web (focusable by script, not in the Tab order), so
 * `useReveal` can move a screen reader to it.
 */
export const REVEAL_TARGET_PROPS = { tabIndex: -1 } as object;

/**
 * Brings the element a link pointed at (a prescription or a dose from a notification) into view and moves focus
 * to it. It runs each time `target` changes to a value other than null (pass the linked id once the element shows
 * its content), so re-renders and reloads of the same item do not scroll the page again. On web the ref of a
 * react-native-web View is the DOM element; on other platforms nothing happens. The scroll respects reduced motion.
 */
export function useReveal(
  ref: RefObject<unknown>,
  target: string | number | null,
  block: ScrollLogicalPosition = 'center',
): void {
  useEffect(() => {
    if (target === null || Platform.OS !== 'web' || typeof window === 'undefined') return;
    // Wait one frame so the element has its final layout (the content above it may just have rendered).
    const frame = window.requestAnimationFrame(() => {
      const node = ref.current as HTMLElement | null;
      if (!node || typeof node.scrollIntoView !== 'function') return;
      const reduceMotion = typeof window.matchMedia === 'function'
        && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      node.scrollIntoView({ block, behavior: reduceMotion ? 'auto' : 'smooth' });
      if (typeof node.focus === 'function') node.focus({ preventScroll: true });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [ref, target, block]);
}
