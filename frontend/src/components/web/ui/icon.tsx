import { Ionicons } from '@expo/vector-icons';
import type { StyleProp, TextStyle } from 'react-native';

export type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

/**
 * A decorative icon. Icon fonts render a glyph as text, which screen readers and the accessible-name
 * calculation would otherwise pick up, so the glyph is always hidden from the accessibility tree. Meaning is
 * carried by the text next to the icon (or by the control's accessible name for icon-only buttons).
 */
export function Icon({ name, size = 16, color, style }: {
  name:   IoniconsName;
  size?:  number;
  color:  string;
  style?: StyleProp<TextStyle>;
}) {
  return (
    <Ionicons
      name={name}
      size={size}
      color={color}
      style={style}
      aria-hidden
      importantForAccessibility="no"
      accessibilityElementsHidden
    />
  );
}
