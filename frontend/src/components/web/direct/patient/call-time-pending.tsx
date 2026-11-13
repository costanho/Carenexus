import { type StyleProp, StyleSheet, Text, View, type ViewStyle } from 'react-native';
import { Icon } from '@/components/web/ui';

const AMBER   = '#B45309';
const AMBER_L = '#FFFBEB';
const AMBER_B = '#FDE68A';

/**
 * Shown on a patient's VIDEO appointment while its call is NEEDS_TIME_CONFIRMATION: an older
 * appointment whose time the clinic must confirm before joining is enabled. It is information,
 * not an action, so it is not a button (CONTRACT.md 11.3).
 */
export function CallTimePending({ style }: { style?: StyleProp<ViewStyle> }) {
  return (
    <View style={[styles.box, style]}>
      <Icon name="time-outline" size={15} color={AMBER} />
      <Text style={styles.text}>
        Video call not open yet. The clinic needs to confirm this appointment time first.
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  box:  { flexDirection: 'row', alignItems: 'flex-start', gap: 8, backgroundColor: AMBER_L, borderRadius: 10, borderWidth: 1, borderColor: AMBER_B, padding: 12 },
  text: { flex: 1, fontSize: 12, lineHeight: 17, color: AMBER },
});
