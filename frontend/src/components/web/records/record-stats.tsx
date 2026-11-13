import { StyleSheet, Text, View } from 'react-native';
import {
  colors, decorative, fontSize, Icon, type IoniconsName, radius, space, STATUS_TONES, type StatusToneName,
} from '@/components/web/ui';

/** One figure of the summary row above a record list. */
export interface RecordStat {
  key:      string;
  label:    string;
  /** The count; null while it is loading or when it could not be counted (shown as a dash). */
  value:    number | null;
  icon:     IoniconsName;
  /** Light tones only: the tile is white, so the solid `critical` tone is not offered (use `danger`). */
  tone:     Exclude<StatusToneName, 'critical'>;
  /**
   * True when `value` is counted from the rows loaded so far and more rows remain on the server: the figure is a
   * lower bound and is shown as "12+".
   */
  atLeast?: boolean;
}

/** "12", "12+" for a lower bound, or a dash when the count is not known. */
function shownValue(stat: RecordStat): string {
  if (stat.value === null) return '–';
  return stat.atLeast ? `${stat.value}+` : String(stat.value);
}

/** What a screen reader says for a figure: "Total: 12", "Follow-ups: at least 3", "Pending: not available". */
function spokenValue(stat: RecordStat): string {
  if (stat.value === null) return `${stat.label}: not available`;
  return stat.atLeast ? `${stat.label}: at least ${stat.value}` : `${stat.label}: ${stat.value}`;
}

/**
 * The summary figures above a record list (Total, Completed, Pending, Critical ...), as tiles that wrap onto more
 * lines on a narrow window. Each figure is read as one phrase, and the colour of a tile always comes with its label
 * and icon.
 */
export function RecordStats({ label, stats }: {
  /** Names the group, for example "Lab results summary". */
  label: string;
  stats: RecordStat[];
}) {
  return (
    <View style={styles.row} role="list" aria-label={label}>
      {stats.map(stat => {
        const tone = STATUS_TONES[stat.tone];
        return (
          <View
            key={stat.key}
            role="listitem"
            aria-label={spokenValue(stat)}
            accessible
            accessibilityLabel={spokenValue(stat)}
            style={[styles.tile, { borderLeftColor: tone.fg }]}
          >
            <View style={[styles.iconWrap, { backgroundColor: tone.bg }]} {...decorative}>
              <Icon name={stat.icon} size={18} color={tone.fg} />
            </View>
            <View style={styles.texts} {...decorative}>
              <Text style={[styles.value, { color: tone.fg }]}>{shownValue(stat)}</Text>
              <Text style={styles.label} numberOfLines={2}>{stat.label}</Text>
            </View>
          </View>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  row:  { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, minWidth: 0 },
  tile: {
    flexGrow: 1, flexShrink: 1, flexBasis: 140, minWidth: 0,
    flexDirection: 'row', alignItems: 'center', gap: space.sm,
    paddingHorizontal: space.md, paddingVertical: space.sm,
    backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderLeftWidth: 4, borderRadius: radius.md,
  },
  iconWrap: { width: 34, height: 34, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  texts:    { flexShrink: 1, minWidth: 0 },
  value:    { fontSize: fontSize.xl, fontWeight: '700' },
  label:    { fontSize: fontSize.sm, color: colors.textMuted },
});
