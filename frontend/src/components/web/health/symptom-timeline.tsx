import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, Button, colors, fontSize, PagedList, radius, SelectField, space, StatusBadge, statusTone, useUiTheme,
} from '@/components/web/ui';
import { useDeleteSymptomLog, useSymptomLog, useSymptomLogs } from '@/hooks/use-symptom-logs';
import {
  symptomDurationLabel, SYMPTOM_SEVERITY_OPTIONS, symptomSeverityLabel, symptomSourceLabel,
} from '@/lib/health/catalog';
import type { SymptomLog, SymptomSeverity } from '@/lib/health/types';
import { formatClinicDateTime } from '@/lib/time/clinic';
import { confirmAction } from '@/lib/ui/dialogs';
import { HEALTH_NO_ACCESS_MESSAGE } from './vitals-tiles';

export interface SymptomTimelineProps {
  patientSystemId: string | null | undefined;
  /** Shorter rows (no source line) for a side panel or an overview. */
  compact?:        boolean;
  /** Logs per request. Default 20. */
  pageSize?:       number;
  /** When false nothing is requested. Default true. */
  enabled?:        boolean;
  /** Shows the severity filter above the list. */
  showFilter?:     boolean;
  /**
   * A symptom log (`symptomLogId`) to bring to the top and mark. Pass `params.id` only from a link that carries a
   * symptom id (the doctor `patients&tab=vitals&id=<symptomId>` link of be-health-tracking section 6); the
   * caregiver HEALTH_TRACKING link to the proxy health summary carries a metricId, which must not be passed.
   */
  focusSymptomId?: number | null;
  /** Shows a "Log symptoms" button in the empty state. */
  onLog?:          () => void;
  noAccessMessage?: string;
}

/**
 * A patient's symptom logs, newest first, as a timeline with "Load more". Severity is always written out. A log
 * the signed-in user wrote in the last 24 hours can be deleted (the server decides: `canDelete`).
 */
export function SymptomTimeline({
  patientSystemId, compact = false, pageSize, enabled = true, showFilter = false, focusSymptomId, onLog,
  noAccessMessage = HEALTH_NO_ACCESS_MESSAGE,
}: SymptomTimelineProps) {
  const [severity, setSeverity] = useState<SymptomSeverity | ''>('');
  const logs = useSymptomLogs(patientSystemId, { severity }, { enabled, pageSize });
  const focus = useSymptomLog(focusSymptomId, { enabled: enabled && !!patientSystemId });
  const remove = useDeleteSymptomLog();
  const [notice, setNotice] = useState<string | null>(null);

  const onDelete = async (log: SymptomLog) => {
    const when = formatClinicDateTime(log.loggedAt, 'an unknown time');
    const ok = await confirmAction(
      'Delete this symptom log?',
      `${log.symptoms.join(', ') || 'The symptoms'} logged on ${when} will be removed. This cannot be undone.`,
      'Delete',
      true,
    );
    if (!ok) return;
    setNotice(null);
    const result = await remove.remove(log);
    if (result.ok) setNotice('Symptom log deleted.');
  };

  // The linked log is marked in the list when it is on a loaded page, and shown above the list otherwise.
  // A 404 (also after a reload that follows a delete) means it no longer exists, whatever was loaded before.
  const focusGone = !!focusSymptomId && !focus.loading && focus.errorStatus === 404;
  const focusLog = !focusGone && focus.data && focus.data.patientSystemId === patientSystemId ? focus.data : null;
  const focusInList = !!focusLog && logs.items.some(item => item.symptomId === focusLog.symptomId);

  const header = (
    <>
      {showFilter ? (
        <View style={styles.filter}>
          <SelectField<SymptomSeverity>
            label="Severity"
            value={severity}
            onChange={setSeverity}
            options={SYMPTOM_SEVERITY_OPTIONS}
            placeholder="All severities"
            allowEmpty
          />
        </View>
      ) : null}
      {notice ? <Banner tone="success" text={notice} onDismiss={() => setNotice(null)} /> : null}
      {remove.error ? <Banner tone="danger" text={remove.error} onDismiss={remove.reset} /> : null}
      {focusGone && !notice ? <Banner tone="info" text="The symptom log you opened is no longer available." /> : null}
      {focusLog && !focusInList ? (
        <View style={styles.focus}>
          <Text style={styles.focusTitle}>Symptom log you opened</Text>
          <SymptomLogRow
            log={focusLog} compact={compact} marked
            onDelete={onDelete} deleting={remove.pendingId === focusLog.symptomId} busy={remove.pending}
          />
        </View>
      ) : null}
    </>
  );

  return (
    <PagedList
      state={logs}
      label="Symptom logs"
      keyOf={log => log.symptomId}
      header={header}
      noAccessMessage={noAccessMessage}
      empty={{
        icon:   'clipboard-outline',
        title:  severity ? `No ${symptomSeverityLabel(severity).toLowerCase()} symptoms logged` : 'No symptoms logged',
        sub:    severity ? 'Choose another severity to see other logs.' : 'Symptom logs appear here once they are added.',
        action: !severity && onLog ? { label: 'Log symptoms', onPress: onLog, icon: 'add' } : undefined,
      }}
      renderItem={log => (
        <SymptomLogRow
          log={log} compact={compact} marked={focusLog?.symptomId === log.symptomId}
          onDelete={onDelete} deleting={remove.pendingId === log.symptomId} busy={remove.pending}
        />
      )}
    />
  );
}

function SymptomLogRow({ log, compact, marked = false, onDelete, deleting, busy }: {
  log:      SymptomLog;
  compact:  boolean;
  /** The log a link pointed at: outlined and labelled. */
  marked?:  boolean;
  onDelete: (log: SymptomLog) => void;
  deleting: boolean;
  busy:     boolean;
}) {
  const { accent } = useUiTheme();
  const when = formatClinicDateTime(log.loggedAt, 'Time not recorded');
  const duration = symptomDurationLabel(log.durationDays);
  const by = log.loggedByName ? `Logged by ${log.loggedByName}` : null;
  return (
    <View style={styles.row}>
      <View style={styles.rail}>
        <View style={[styles.dot, { borderColor: accent }]} />
        <View style={styles.line} />
      </View>
      <View style={[styles.card, marked && { borderColor: accent, borderWidth: 2 }]}>
        <View style={styles.head}>
          <Text style={styles.when}>{when}</Text>
          <StatusBadge label={symptomSeverityLabel(log.severity)} tone={statusTone('severity', log.severity)} />
        </View>
        {marked ? <Text style={[styles.markedText, { color: accent }]}>Opened from a link</Text> : null}
        <Text style={styles.symptoms}>
          {log.symptoms.length > 0 ? log.symptoms.join(', ') : 'Symptoms not listed'}
        </Text>
        {duration ? <Text style={styles.meta}>{duration}</Text> : null}
        {log.notes ? <Text style={styles.notes}>{log.notes}</Text> : null}
        {by || !compact ? (
          <Text style={styles.meta}>
            {[by, compact ? null : symptomSourceLabel(log.source)].filter(Boolean).join(' · ')}
          </Text>
        ) : null}
        {log.canDelete ? (
          <Button
            label="Delete"
            accessibilityLabel={`Delete symptom log from ${when}`}
            icon="trash-outline"
            variant="ghost"
            size="sm"
            onPress={() => onDelete(log)}
            loading={deleting}
            disabled={busy && !deleting}
          />
        ) : null}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  filter: { maxWidth: 280 },

  focus:      { gap: space.xs },
  focusTitle: { fontSize: fontSize.sm, fontWeight: '700', color: colors.text },

  row:  { flexDirection: 'row', gap: space.sm, minWidth: 0 },
  rail: { width: 14, alignItems: 'center', paddingTop: space.md },
  dot:  { width: 12, height: 12, borderRadius: radius.pill, borderWidth: 3, backgroundColor: colors.surface },
  line: { flex: 1, width: 2, marginTop: space.xs, backgroundColor: colors.border },
  card: {
    flex: 1, minWidth: 0, gap: space.xs, padding: space.md,
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.surface,
  },
  head:       { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  when:       { flexShrink: 1, fontSize: fontSize.sm, fontWeight: '600', color: colors.textMuted },
  markedText: { fontSize: fontSize.xs, fontWeight: '700' },
  symptoms:   { fontSize: fontSize.md, fontWeight: '600', color: colors.text, lineHeight: 20 },
  notes:      { fontSize: fontSize.sm, color: colors.text, lineHeight: 19 },
  meta:       { fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 17 },
});
