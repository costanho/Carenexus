import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, colors, EmptyView, ErrorView, fontSize, Icon, LoadingView, NoAccessView, radius, space, StatusBadge,
  useUiTheme,
} from '@/components/web/ui';
import type { ResourceState } from '@/hooks/use-resource';
import { latestTiles, metricSeverityBadge, type VitalTile } from '@/lib/health/catalog';
import type { LatestHealthMetrics, MetricSeverity } from '@/lib/health/types';
import { formatClinicDateTime } from '@/lib/time/clinic';

export const HEALTH_NO_ACCESS_MESSAGE = "You don't have access to this person's health readings.";

/** "Abnormal" or "Critical" in words (with an icon); nothing for a normal reading. */
export function MetricSeverityBadge({ severity }: { severity: MetricSeverity }) {
  const badge = metricSeverityBadge(severity);
  return badge ? <StatusBadge label={badge.label} tone={badge.tone} /> : null;
}

export interface VitalsTilesProps {
  /** The result of `useLatestHealthMetrics`. */
  state:    ResourceState<LatestHealthMetrics>;
  /** Smaller tiles for a dashboard card or a side panel. */
  compact?: boolean;
  /** Shows a "Record vitals" button in the empty state. */
  onRecord?: () => void;
  /** Sentence under "No readings yet". */
  emptySub?: string;
  /** Shown instead of the default sentence when the server answers 403. */
  noAccessMessage?: string;
}

/**
 * The latest reading of each type as tiles. A reading outside its reference range carries the word "Abnormal" or
 * "Critical" (decided by the server), never colour alone. Renders its own loading, failed, no-access and empty
 * states.
 */
export function VitalsTiles({ state, compact = false, onRecord, emptySub, noAccessMessage = HEALTH_NO_ACCESS_MESSAGE }: VitalsTilesProps) {
  const { data, loading, error, errorStatus, refetch } = state;

  if (loading) return <LoadingView label="Loading the latest readings" compact />;
  if (!data) {
    if (errorStatus === 403) return <NoAccessView message={noAccessMessage} compact />;
    if (error) return <ErrorView message={error} onRetry={refetch} compact />;
    // Not asked yet (no patient chosen, or the query is switched off): nothing to show.
    return null;
  }

  const tiles = latestTiles(data);
  return (
    <View style={styles.wrap}>
      {error ? <Banner tone="danger" text={error} action={{ label: 'Retry', onPress: refetch }} /> : null}
      {tiles.length === 0 ? (
        <EmptyView
          icon="pulse-outline"
          title="No readings yet"
          sub={emptySub ?? 'Blood pressure, heart rate and other readings appear here once they are recorded.'}
          action={onRecord ? { label: 'Record vitals', onPress: onRecord, icon: 'add' } : undefined}
          compact
        />
      ) : (
        <View style={styles.grid} role="list" aria-label="Latest readings">
          {tiles.map(tile => <Tile key={tile.key} tile={tile} compact={compact} />)}
        </View>
      )}
    </View>
  );
}

function Tile({ tile, compact }: { tile: VitalTile; compact: boolean }) {
  const { accent } = useUiTheme();
  return (
    <View role="listitem" style={[styles.tile, compact && styles.tileCompact]}>
      <View style={styles.tileHead}>
        <Icon name={tile.icon} size={compact ? 14 : 16} color={accent} />
        <Text style={styles.label}>{tile.label}</Text>
      </View>
      <View style={styles.valueRow}>
        <Text style={[styles.value, compact && styles.valueCompact]}>{tile.valueText}</Text>
        {tile.unitLabel ? <Text style={styles.unit}>{tile.unitLabel}</Text> : null}
      </View>
      <MetricSeverityBadge severity={tile.severity} />
      {tile.caption && !compact ? <Text style={styles.meta}>{tile.caption}</Text> : null}
      <Text style={styles.meta}>{formatClinicDateTime(tile.recordedAt, 'Time not recorded')}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: space.md, minWidth: 0 },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: space.md, minWidth: 0 },
  // Two tiles side by side fit a 375 px window; wider cards show more per row.
  tile: {
    flexGrow: 1, flexShrink: 1, flexBasis: 150, minWidth: 0, maxWidth: 280, gap: space.xs,
    padding: space.md, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.surface,
  },
  tileCompact:  { flexBasis: 132, padding: space.sm },
  tileHead:     { flexDirection: 'row', alignItems: 'center', gap: space.xs },
  label:        { flex: 1, minWidth: 0, fontSize: fontSize.sm, fontWeight: '600', color: colors.textMuted },
  valueRow:     { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'baseline', columnGap: space.xs },
  value:        { fontSize: fontSize.title, fontWeight: '700', color: colors.text },
  valueCompact: { fontSize: fontSize.xl },
  unit:         { fontSize: fontSize.sm, color: colors.textMuted },
  meta:         { fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 17 },
});
