import { useMemo, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, Button, colors, DateField, EmptyView, ErrorView, fontSize, Icon, IconButton, LoadingView, NoAccessView,
  radius, SelectField, space,
} from '@/components/web/ui';
import { useDeleteHealthMetric, useHealthMetricHistory } from '@/hooks/use-health-metrics';
import {
  entryView, familyInfo, METRIC_FAMILIES, metricSourceLabel, toReadingSessions,
  type MetricFamily, type ReadingEntry, type ReadingSession,
} from '@/lib/health/catalog';
import { clinicToday, formatClinicDateTime } from '@/lib/time/clinic';
import { confirmAction } from '@/lib/ui/dialogs';
import { isValidDateOnly } from '@/lib/ui/date-only';
import { HEALTH_NO_ACCESS_MESSAGE, MetricSeverityBadge } from './vitals-tiles';

export interface VitalsHistoryProps {
  patientSystemId: string | null | undefined;
  /** Readings per request. Default 20. */
  pageSize?:       number;
  /** When false nothing is requested. Default true. */
  enabled?:        boolean;
  /** Shows the measurement and date filters above the list. Default true. */
  showFilter?:     boolean;
  /** Shows a "Record vitals" button in the empty state. */
  onRecord?:       () => void;
  noAccessMessage?: string;
}

/**
 * Every stored reading, newest first, loaded page by page. Readings taken together are one entry and a
 * blood-pressure pair is one line. A reading entered by hand can be deleted by the patient or by whoever
 * recorded it (the server decides: `canDelete`); deleting one blood-pressure value removes the pair.
 */
export function VitalsHistory({
  patientSystemId, pageSize, enabled = true, showFilter = true, onRecord, noAccessMessage = HEALTH_NO_ACCESS_MESSAGE,
}: VitalsHistoryProps) {
  const [family, setFamily] = useState<MetricFamily | ''>('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [notice, setNotice] = useState<string | null>(null);

  // The same rule as the server (`from must not be after to`), checked before asking.
  const rangeError = isValidDateOnly(from) && isValidDateOnly(to) && from > to ? 'The end date cannot be before the start date' : null;
  const types = familyInfo(family)?.types;
  const history = useHealthMetricHistory(
    patientSystemId,
    { types, from: isValidDateOnly(from) ? from : undefined, to: isValidDateOnly(to) ? to : undefined },
    { enabled: enabled && !rangeError, pageSize },
  );
  const remove = useDeleteHealthMetric();

  const sessions = useMemo(() => toReadingSessions(history.items), [history.items]);
  const filtered = !!family || !!from || !!to;

  const onDelete = async (entry: ReadingEntry) => {
    const v = entryView(entry);
    // A blood-pressure value whose partner is on a page not loaded yet shows on its own, but the server still
    // deletes both values of the pair, so the person is told so.
    const pair = entry.kind === 'bloodPressure' || isPairedPressureValue(entry);
    const what = `${v.label} ${v.valueText} ${v.unitLabel}`.trim();
    const when = formatClinicDateTime(v.recordedAt, 'an unknown time');
    const ok = await confirmAction(
      'Delete this reading?',
      `${what}, recorded ${when}, will be removed.`
        + (pair ? ' Both the systolic and the diastolic value are deleted.' : '')
        + ' This cannot be undone.',
      'Delete',
      true,
    );
    if (!ok) return;
    setNotice(null);
    const patient = entry.kind === 'bloodPressure' ? entry.systolic.patientSystemId : entry.metric.patientSystemId;
    const result = await remove.remove({ metricId: v.metricId, patientSystemId: patient });
    if (result.ok) setNotice(pair ? 'Blood pressure reading deleted.' : `${v.label} reading deleted.`);
  };

  const today = clinicToday();
  const clearFilters = () => { setFamily(''); setFrom(''); setTo(''); };

  let body;
  if (rangeError) {
    body = null;
  } else if (history.loading) {
    body = <LoadingView label="Loading readings" compact />;
  } else if (history.error && history.items.length === 0) {
    body = history.errorStatus === 403
      ? <NoAccessView message={noAccessMessage} compact />
      : <ErrorView message={history.error} onRetry={history.refetch} compact />;
  } else if (sessions.length === 0) {
    body = filtered ? (
      <EmptyView
        icon="search-outline"
        title="No readings match these filters"
        sub="Change the measurement or the dates to see other readings."
        action={{ label: 'Clear filters', onPress: clearFilters }}
        compact
      />
    ) : (
      <EmptyView
        icon="pulse-outline"
        title="No readings yet"
        sub="Readings appear here once they are recorded."
        action={onRecord ? { label: 'Record vitals', onPress: onRecord, icon: 'add' } : undefined}
        compact
      />
    );
  } else {
    const shown = history.items.length;
    const count = history.total > shown
      ? `Showing ${shown} of ${history.total} readings`
      : `${shown} ${shown === 1 ? 'reading' : 'readings'}`;
    body = (
      <>
        {history.error ? <Banner tone="danger" text={history.error} action={{ label: 'Retry', onPress: history.refetch }} /> : null}
        <View style={styles.list} role="list" aria-label="Readings" aria-busy={history.refreshing || history.loadingMore}>
          {sessions.map(session => (
            <View key={session.key} role="listitem" style={styles.session}>
              <SessionCard session={session} onDelete={onDelete} pendingId={remove.pendingId} busy={remove.pending} />
            </View>
          ))}
        </View>
        <View style={styles.footer}>
          <Text style={styles.count} role="status" aria-live="polite">{count}</Text>
          {history.hasMore ? (
            <Button label="Load more" variant="secondary" icon="chevron-down" onPress={history.loadMore} loading={history.loadingMore} />
          ) : null}
        </View>
      </>
    );
  }

  return (
    <View style={styles.wrap}>
      {showFilter ? (
        <View style={styles.filters}>
          <View style={styles.filterWide}>
            <SelectField<MetricFamily>
              label="Measurement"
              value={family}
              onChange={setFamily}
              options={METRIC_FAMILIES.map(f => ({ value: f.key, label: f.label }))}
              placeholder="All measurements"
              allowEmpty
            />
          </View>
          <View style={styles.filterDate}>
            <DateField label="From" value={from} onChange={setFrom} max={to || today} />
          </View>
          <View style={styles.filterDate}>
            <DateField label="To" value={to} onChange={setTo} min={from || undefined} max={today} error={rangeError} />
          </View>
        </View>
      ) : null}
      {notice ? <Banner tone="success" text={notice} onDismiss={() => setNotice(null)} /> : null}
      {remove.error ? <Banner tone="danger" text={remove.error} onDismiss={remove.reset} /> : null}
      {body}
    </View>
  );
}

/** One half of a blood-pressure pair shown alone because its partner has not been loaded yet. */
function isPairedPressureValue(entry: ReadingEntry): boolean {
  return entry.kind === 'single'
    && (entry.metric.type === 'BP_SYSTOLIC' || entry.metric.type === 'BP_DIASTOLIC')
    && !!entry.metric.readingGroupId;
}

function SessionCard({ session, onDelete, pendingId, busy }: {
  session:   ReadingSession;
  onDelete:  (entry: ReadingEntry) => void;
  pendingId: number | null;
  busy:      boolean;
}) {
  const when = formatClinicDateTime(session.recordedAt, 'Time not recorded');
  const meta = [
    session.recordedByName ? `Recorded by ${session.recordedByName}` : null,
    session.source !== 'MANUAL' ? metricSourceLabel(session.source) : null,
  ].filter(Boolean).join(' · ');
  return (
    <View style={styles.card}>
      <Text style={styles.when}>{when}</Text>
      {session.entries.map(entry => {
        const v = entryView(entry);
        const ids = entry.kind === 'bloodPressure' ? [entry.systolic.metricId, entry.diastolic.metricId] : [entry.metric.metricId];
        const deleting = pendingId !== null && ids.includes(pendingId);
        return (
          <View key={entry.key} style={styles.entry}>
            <Icon name={v.icon} size={16} color={colors.textMuted} />
            <View style={styles.entryText}>
              <Text style={styles.entryLabel}>{v.label}</Text>
              <View style={styles.entryValueRow}>
                <Text style={styles.entryValue}>{v.valueText}</Text>
                {v.unitLabel ? <Text style={styles.entryUnit}>{v.unitLabel}</Text> : null}
                <MetricSeverityBadge severity={v.severity} />
              </View>
            </View>
            {v.canDelete ? (
              <IconButton
                label={`Delete ${v.label.toLowerCase()} reading ${v.valueText} ${v.unitLabel} from ${when}`}
                icon="trash-outline"
                size="sm"
                onPress={() => onDelete(entry)}
                loading={deleting}
                disabled={busy && !deleting}
              />
            ) : null}
          </View>
        );
      })}
      {session.notes.map(note => <Text key={note} style={styles.notes}>{note}</Text>)}
      {meta ? <Text style={styles.meta}>{meta}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:       { gap: space.md, minWidth: 0 },
  filters:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.md },
  filterWide: { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, maxWidth: 320 },
  filterDate: { flexGrow: 1, flexShrink: 1, flexBasis: 150, minWidth: 0, maxWidth: 220 },

  list:    { gap: space.sm, minWidth: 0 },
  session: { minWidth: 0 },
  footer:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  count:   { fontSize: fontSize.sm, color: colors.textMuted },

  card: {
    gap: space.sm, padding: space.md, minWidth: 0,
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.surface,
  },
  when:          { fontSize: fontSize.sm, fontWeight: '600', color: colors.textMuted },
  entry:         { flexDirection: 'row', alignItems: 'center', gap: space.sm, minWidth: 0 },
  entryText:     { flex: 1, minWidth: 0, gap: 2 },
  entryLabel:    { fontSize: fontSize.sm, color: colors.textMuted },
  entryValueRow: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', columnGap: space.xs, rowGap: space.xs },
  entryValue:    { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  entryUnit:     { fontSize: fontSize.sm, color: colors.textMuted, marginRight: space.xs },
  notes:         { fontSize: fontSize.sm, color: colors.text, lineHeight: 19 },
  meta:          { fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 17 },
});
