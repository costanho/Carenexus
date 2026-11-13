import { StyleSheet, Text, View } from 'react-native';
import { Button, colors, fontSize, LoadingView, radius, space, STATUS_TONES } from '@/components/web/ui';
import type { MedicationAdherenceState } from '@/hooks/use-medication-schedule';
import { formatDateOnly } from '@/lib/ui/date-only';

export interface AdherenceSummaryProps {
  /** The result of `useMedicationAdherence`. */
  state: MedicationAdherenceState;
  /** Length of the window in days, for the heading while the numbers load. Default 30. */
  days?: number;
}

function Figure({ value, label, color }: { value: number; label: string; color: string }) {
  return (
    <View style={styles.figure}>
      <Text style={[styles.figureValue, { color }]}>{value}</Text>
      <Text style={styles.figureLabel}>{label}</Text>
    </View>
  );
}

/**
 * How many scheduled doses were taken and missed over the last days. The percentage counts taken against
 * taken + missed; doses that are still due are listed but not judged. Renders nothing when the server refuses
 * the numbers (the schedule next to it explains the missing access).
 */
export function AdherenceSummary({ state, days = 30 }: AdherenceSummaryProps) {
  const { adherence, loading, error, errorStatus, refetch } = state;
  if (errorStatus === 403) return null;

  const window = adherence?.days ?? days;
  const title = `Last ${window} days`;

  if (loading) {
    return (
      <View style={styles.box}>
        <Text style={styles.title}>{title}</Text>
        <LoadingView compact label="Loading the adherence summary" />
      </View>
    );
  }
  if (!adherence) {
    if (!error) return null;
    return (
      <View style={styles.box} accessibilityRole="alert">
        <Text style={styles.title}>{title}</Text>
        <Text style={styles.note}>{error}</Text>
        <Button label="Retry" accessibilityLabel="Retry loading the adherence summary" variant="secondary" size="sm" icon="refresh-outline" onPress={refetch} />
      </View>
    );
  }

  const rate = adherence.adherenceRate;
  const range = adherence.from && adherence.to
    ? `${formatDateOnly(adherence.from, { day: 'numeric', month: 'short' })} to ${formatDateOnly(adherence.to)}`
    : null;
  const headline = rate === null ? 'No data yet' : `${rate}% of doses taken`;
  return (
    <View style={styles.box} role="group" aria-label={`Doses taken, ${title.toLowerCase()}`}>
      <View style={styles.head}>
        <View style={styles.headText}>
          <Text style={styles.title}>{title}</Text>
          {range ? <Text style={styles.note}>{range}</Text> : null}
        </View>
        <Text style={styles.headline}>{headline}</Text>
      </View>
      {rate !== null ? (
        <View style={styles.track} aria-hidden>
          <View style={[styles.fill, { width: `${Math.min(100, Math.max(0, rate))}%` }]} />
        </View>
      ) : (
        <Text style={styles.note}>The percentage appears once a scheduled dose has been taken or missed.</Text>
      )}
      <View style={styles.figures}>
        <Figure value={adherence.taken}   label="Taken"     color={STATUS_TONES.success.fg} />
        <Figure value={adherence.missed}  label="Missed"    color={STATUS_TONES.danger.fg} />
        <Figure value={adherence.pending} label="Still due" color={STATUS_TONES.info.fg} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  box:         { gap: space.sm, padding: space.md, borderRadius: radius.md, backgroundColor: colors.bgSubtle, minWidth: 0 },
  head:        { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'baseline', justifyContent: 'space-between', gap: space.sm },
  headText:    { flexShrink: 1, minWidth: 0, gap: 2 },
  title:       { fontSize: fontSize.sm, fontWeight: '700', color: colors.text },
  note:        { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  headline:    { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  track:       { height: 8, borderRadius: radius.pill, backgroundColor: colors.border, overflow: 'hidden' },
  fill:        { height: 8, borderRadius: radius.pill, backgroundColor: STATUS_TONES.success.fg },
  figures:     { flexDirection: 'row', flexWrap: 'wrap', columnGap: space.xl, rowGap: space.sm },
  figure:      { flexDirection: 'row', alignItems: 'baseline', gap: space.xs },
  figureValue: { fontSize: fontSize.lg, fontWeight: '700' },
  figureLabel: { fontSize: fontSize.sm, color: colors.textMuted },
});
