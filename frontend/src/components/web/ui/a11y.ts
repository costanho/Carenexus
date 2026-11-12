import { Platform } from 'react-native';

// Accessibility helpers for the web primitives. react-native-web 0.21 renders these attributes and handlers,
// but React Native's TypeScript types do not list them, so they are passed as spread objects.

export { ariaHidden, ariaLevel, ariaPressed } from '@/components/web/direct/patient/a11y';

export const IS_WEB = Platform.OS === 'web';

/** Attributes and handlers that only exist on web (aria-describedby, aria-invalid, onKeyDown, ...). */
export function webOnly(props: Record<string, unknown>): object {
  return IS_WEB ? props : {};
}

/** Marks an element and everything inside it as decorative on every platform. */
export const decorative: object = {
  'aria-hidden': true,
  importantForAccessibility: 'no-hide-descendants',
  accessibilityElementsHidden: true,
};

/** Joins the ids that describe a control ("hint-id error-id"), or undefined when there are none. */
export function describedBy(...ids: (string | false | null | undefined)[]): string | undefined {
  const list = ids.filter((id): id is string => typeof id === 'string' && id.length > 0);
  return list.length > 0 ? list.join(' ') : undefined;
}

/**
 * The accessible name for a control with a visible label. The name must start with the visible words
 * (WCAG 2.5.3), so extra context is appended after the label rather than replacing it.
 */
export function accessibleName(visibleLabel: string, extra?: string | null): string {
  const label = visibleLabel.trim();
  const more = (extra ?? '').trim();
  if (!more) return label;
  if (!label) return more;
  if (more.toLowerCase().startsWith(label.toLowerCase())) return more;
  return `${label}, ${more}`;
}

/** The DOM key of a keyboard event passed to a react-native-web `onKeyDown` handler. */
export interface WebKeyEvent {
  key: string;
  preventDefault: () => void;
  stopPropagation: () => void;
  currentTarget: unknown;
}
