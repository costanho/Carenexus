// ARIA props that react-native-web 0.21 renders but React Native's TypeScript types do not list.
// react-native-web ignores accessibilityState for these, so the aria-* prop is set alongside it.

/** Toggle-button state for a selectable card that stays role "button". */
export function ariaPressed(pressed: boolean): object {
  return { 'aria-pressed': pressed };
}

/** Heading level for a Text with accessibilityRole="header" (react-native-web defaults to h1). */
export function ariaLevel(level: 1 | 2 | 3 | 4 | 5 | 6): object {
  return { 'aria-level': level };
}

/** Hides decorative content (icon glyphs, avatar initials) so it does not prefix a card's accessible name. */
export const ariaHidden: object = { 'aria-hidden': true };
