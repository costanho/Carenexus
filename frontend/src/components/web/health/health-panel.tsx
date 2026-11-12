import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import {
  Banner, Button, EmptyView, ErrorView, LoadingView, NoAccessView, type RoleTheme, SectionCard, space, UiThemeProvider,
} from '@/components/web/ui';
import { useLatestHealthMetrics } from '@/hooks/use-health-metrics';
import { type HealthNotice, loggedNotice, recordedNotice, RecordVitalsDialog, SymptomDialog } from './health-dialogs';
import { SymptomTimeline } from './symptom-timeline';
import { TrendChart } from './trend-chart';
import { VitalsHistory } from './vitals-history';
import { HEALTH_NO_ACCESS_MESSAGE, VitalsTiles } from './vitals-tiles';

export interface HealthPanelProps {
  /** The patient whose vitals and symptoms are shown (`PAT-…`). */
  patientSystemId: string | null | undefined;
  /**
   * Shows "Record vitals" and "Log symptoms". Doctor: true for a patient in their care. Caregiver:
   * `effectivePermission(dependent, 'canManageCarePlan')`. The server still decides on every save.
   */
  canRecord?:      boolean;
  /** A shorter panel for a side panel or a summary page: small tiles, a short chart, the latest symptoms, no readings history. */
  compact?:        boolean;
  /** The dashboard's accent colours (`SectionProps.theme`). */
  theme?:          RoleTheme;
  /**
   * False when the viewer is known to lack the read permission (caregiver:
   * `effectivePermission(dependent, 'canViewRecords')`): the no-access state is shown and nothing is requested.
   * Default true; a 403 from the server shows the same state.
   */
  canView?:        boolean;
  /** Used in dialog titles ("Record vitals for Mary Davis"). */
  patientName?:    string | null;
  /**
   * A symptom log (`symptomLogId`) to bring to the top of the timeline. Only the doctor link
   * `patients&tab=vitals&id=<symptomId>` (be-health-tracking section 6) carries a symptom id in `params.id`.
   * The caregiver HEALTH_TRACKING link to the proxy health summary carries `id=<metricId>`, a health reading,
   * which must not be passed here: it would open an unrelated symptom log or report a missing one.
   */
  focusSymptomId?: number | null;
  /** Shown instead of the default sentences of the no-access state. */
  noAccessMessage?: string;
  noAccessSub?:     string;
}

/**
 * A patient's vitals and symptoms for doctor and caregiver views: latest readings, the trend chart, the symptom
 * timeline and (unless compact) the readings history, with entry dialogs when `canRecord`. Without read access
 * (known up front through `canView`, or answered by the server with 403) the whole panel is the no-access state.
 */
export function HealthPanel({
  patientSystemId, canRecord = false, compact = false, theme, canView = true, patientName, focusSymptomId,
  noAccessMessage = HEALTH_NO_ACCESS_MESSAGE,
  noAccessSub = 'The patient decides who can see their health readings.',
}: HealthPanelProps) {
  return (
    <UiThemeProvider theme={theme}>
      {!patientSystemId ? (
        <EmptyView icon="person-outline" title="No patient selected" sub="Choose a patient to see their vitals and symptoms." compact={compact} />
      ) : !canView ? (
        <NoAccessView message={noAccessMessage} sub={noAccessSub} compact={compact} />
      ) : (
        // Keyed by patient, so a dialog or a notice of one patient never carries over to the next.
        <PanelBody
          key={patientSystemId}
          patientSystemId={patientSystemId}
          canRecord={canRecord}
          compact={compact}
          patientName={patientName}
          focusSymptomId={focusSymptomId}
          noAccessMessage={noAccessMessage}
          noAccessSub={noAccessSub}
        />
      )}
    </UiThemeProvider>
  );
}

function PanelBody({ patientSystemId, canRecord, compact, patientName, focusSymptomId, noAccessMessage, noAccessSub }: {
  patientSystemId: string;
  canRecord:       boolean;
  compact:         boolean;
  patientName?:    string | null;
  focusSymptomId?: number | null;
  noAccessMessage: string;
  noAccessSub:     string;
}) {
  const latest = useLatestHealthMetrics(patientSystemId);
  const [dialog, setDialog] = useState<'vitals' | 'symptoms' | null>(null);
  const [notice, setNotice] = useState<HealthNotice | null>(null);

  // The latest readings are asked first; their answer tells whether the viewer may read at all, so a viewer
  // without access gets one refusal instead of one per section.
  if (latest.loading) return <LoadingView label="Loading vitals and symptoms" compact={compact} />;
  if (!latest.data) {
    if (latest.errorStatus === 403) return <NoAccessView message={noAccessMessage} sub={noAccessSub} compact={compact} />;
    return <ErrorView message={latest.error ?? 'Could not load the latest readings.'} onRetry={latest.refetch} compact={compact} />;
  }

  const openVitals = () => { setNotice(null); setDialog('vitals'); };
  const openSymptoms = () => { setNotice(null); setDialog('symptoms'); };

  return (
    <View style={styles.panel}>
      {notice ? <Banner tone={notice.tone} text={notice.text} onDismiss={() => setNotice(null)} /> : null}

      <SectionCard
        title="Latest readings"
        action={canRecord ? <Button label="Record vitals" icon="add" size="sm" onPress={openVitals} /> : undefined}
      >
        <VitalsTiles
          state={latest}
          compact={compact}
          onRecord={canRecord ? openVitals : undefined}
          emptySub="Readings appear here once they are recorded for this patient."
          noAccessMessage={noAccessMessage}
        />
      </SectionCard>

      <SectionCard title="Trends">
        <TrendChart patientSystemId={patientSystemId} compact={compact} noAccessMessage={noAccessMessage} />
      </SectionCard>

      <SectionCard
        title="Symptoms"
        action={canRecord ? <Button label="Log symptoms" icon="add" size="sm" variant="secondary" onPress={openSymptoms} /> : undefined}
      >
        <SymptomTimeline
          patientSystemId={patientSystemId}
          compact={compact}
          pageSize={compact ? 3 : 10}
          showFilter={!compact}
          focusSymptomId={focusSymptomId}
          onLog={canRecord ? openSymptoms : undefined}
          noAccessMessage={noAccessMessage}
        />
      </SectionCard>

      {compact ? null : (
        <SectionCard title="Readings history">
          <VitalsHistory
            patientSystemId={patientSystemId}
            pageSize={10}
            onRecord={canRecord ? openVitals : undefined}
            noAccessMessage={noAccessMessage}
          />
        </SectionCard>
      )}

      {canRecord ? (
        <>
          <RecordVitalsDialog
            open={dialog === 'vitals'}
            onClose={() => setDialog(null)}
            patientSystemId={patientSystemId}
            patientName={patientName}
            onRecorded={result => setNotice(recordedNotice(result))}
          />
          <SymptomDialog
            open={dialog === 'symptoms'}
            onClose={() => setDialog(null)}
            patientSystemId={patientSystemId}
            patientName={patientName}
            onLogged={log => setNotice(loggedNotice(log))}
          />
        </>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  panel: { gap: space.lg, minWidth: 0 },
});
