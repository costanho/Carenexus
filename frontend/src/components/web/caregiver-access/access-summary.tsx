import { StyleSheet, Text, View } from 'react-native';
import { colors, fontSize, Icon, radius, space, STATUS_TONES } from '@/components/web/ui';
import { DATA_FLAG_OPTIONS, dataFlagsUnderLevel } from '@/lib/caregiver-access/permissions';
import type { DataFlags } from '@/lib/caregiver-access/types';

type FlagSource = { [K in keyof DataFlags]?: boolean | null };

/**
 * What a level and its stored flags give, permission by permission, with the Full access / View only rule
 * applied (a Full access grant shows every permission as allowed even when a stored flag is still false).
 * Each entry says "Allowed" or "Not allowed" in words and with an icon, never by colour alone.
 */
export function AccessSummary({ level, flags, label }: {
  level:  string | null | undefined;
  flags:  FlagSource;
  /** Names the list for assistive technology, for example "What Sarah Davis can see and do". */
  label?: string;
}) {
  const effective = dataFlagsUnderLevel(level, flags);
  const allowedTone = STATUS_TONES.success;
  return (
    <View style={styles.list} role="list" aria-label={label}>
      {DATA_FLAG_OPTIONS.map(option => {
        const allowed = effective[option.key];
        return (
          <View
            key={option.key}
            role="listitem"
            accessible
            accessibilityLabel={`${option.label}: ${allowed ? 'allowed' : 'not allowed'}`}
            style={[styles.item, allowed ? { backgroundColor: allowedTone.bg, borderColor: allowedTone.border } : styles.itemOff]}
          >
            <Icon
              name={allowed ? 'checkmark-circle' : 'close-circle-outline'}
              size={14}
              color={allowed ? allowedTone.fg : colors.textSubtle}
            />
            <View style={styles.itemText}>
              <Text style={[styles.itemLabel, !allowed && styles.itemLabelOff]}>{option.label}</Text>
              <Text style={[styles.itemState, { color: allowed ? allowedTone.fg : colors.textSubtle }]}>
                {allowed ? 'Allowed' : 'Not allowed'}
              </Text>
            </View>
          </View>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  list: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, minWidth: 0 },
  item: {
    flexGrow: 1, flexShrink: 1, flexBasis: 150, minWidth: 0, flexDirection: 'row', alignItems: 'flex-start', gap: space.xs,
    paddingHorizontal: space.sm, paddingVertical: space.xs, borderWidth: 1, borderRadius: radius.md,
  },
  itemOff:      { backgroundColor: colors.bgSubtle, borderColor: colors.border },
  itemText:     { flex: 1, minWidth: 0 },
  itemLabel:    { fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  itemLabelOff: { color: colors.textMuted },
  itemState:    { fontSize: fontSize.xs, fontWeight: '600' },
});
