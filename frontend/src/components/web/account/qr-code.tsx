import { useMemo } from 'react';
import { StyleSheet, View } from 'react-native';
import { colors, IS_WEB, radius, space } from '@/components/web/ui';
import { buildQrMatrix } from '@/lib/account/qr';

/** Light modules around the code that scanners need to find it. */
const QUIET_ZONE = 4;

/**
 * A QR code drawn in the page as one SVG path (always dark on white, whatever the theme). Renders nothing when
 * the value cannot be encoded or outside a browser; the caller always shows the manual key beside it.
 */
export function QrCode({ value, label, size = 208 }: {
  value: string;
  /** What the code is for, read by screen readers. */
  label: string;
  size?: number;
}) {
  const matrix = useMemo(() => buildQrMatrix(value), [value]);
  if (!IS_WEB || !matrix) return null;
  const side = matrix.size + QUIET_ZONE * 2;
  return (
    <View style={styles.frame}>
      <svg
        role="img"
        aria-label={label}
        width={size}
        height={size}
        viewBox={`0 0 ${side} ${side}`}
        shapeRendering="crispEdges"
        style={{ display: 'block', maxWidth: '100%', height: 'auto' }}
      >
        <rect width={side} height={side} fill="#FFFFFF" />
        <path d={matrix.path} transform={`translate(${QUIET_ZONE} ${QUIET_ZONE})`} fill="#000000" />
      </svg>
    </View>
  );
}

const styles = StyleSheet.create({
  frame: {
    alignSelf: 'flex-start', maxWidth: '100%', padding: space.sm,
    backgroundColor: '#FFFFFF', borderWidth: 1, borderColor: colors.border, borderRadius: radius.md,
  },
});
