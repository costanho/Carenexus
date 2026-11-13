import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { Banner, Button, PagedList, SectionCard, space, UiThemeProvider } from '@/components/web/ui';
import { usePatientAiAlerts } from '@/hooks/use-ai-alerts';
import { useSectionNav } from '@/hooks/use-section-nav';
import { ALERT_DISCLAIMER } from '@/lib/ai/labels';
import { AlertCard } from './alert-card';
import { settleReviewedDetection, useAlertSourceNav } from './alert-list-support';
import { DetectionPanel } from './detection-panel';
import { RunAssessmentDialog } from './run-assessment-dialog';
import { ScreeningNotice } from './screening-notice';

export interface PatientAlertsPanelProps {
  /** The patient whose alerts are listed (a PAT system id). */
  patientSystemId: string;
  /** Shown in the Run assessment dialog and in button names; the alert rows carry it too. */
  patientName?:    string | null;
}

/** Alerts shown before "Load more" in the panel: it sits inside the patient detail, next to other cards. */
const PANEL_PAGE_SIZE = 5;

/**
 * The clinical alerts of one patient, for the doctor's patient detail: every alert of the patient (whoever it
 * is addressed to) with Acknowledge, the source link, the screening result panel and Run assessment. A doctor
 * who is not related to the patient gets the no-access state.
 */
export function PatientAlertsPanel({ patientSystemId, patientName }: PatientAlertsPanelProps) {
  const alerts = usePatientAiAlerts(patientSystemId, { pageSize: PANEL_PAGE_SIZE });
  const { role, hasSection, navigate } = useSectionNav();
  const { canOpenSource, openSource } = useAlertSourceNav();
  const [detectionId, setDetectionId] = useState<number | null>(null);
  const [running, setRunning] = useState(false);

  const name = patientName?.trim() || alerts.items[0]?.patientName?.trim() || null;
  const hasInbox = role === 'doctor' && hasSection('ai-alerts');
  // Run assessment is a doctor's action; an administrator reading the panel gets the list only.
  const canRun = role === 'doctor' && alerts.errorStatus !== 403;

  return (
    <UiThemeProvider role="doctor">
      <SectionCard
        title="Clinical alerts"
        subtitle="Raised by automated screening of this patient's results, readings and medication."
        action={(
          <>
            {hasInbox ? (
              <Button
                label="Open in Clinical Alerts"
                variant="ghost"
                size="sm"
                icon="open-outline"
                onPress={() => navigate('ai-alerts', { patient: patientSystemId })}
              />
            ) : null}
            {canRun ? (
              <Button
                label="Run assessment"
                variant="secondary"
                size="sm"
                icon="play-outline"
                onPress={() => setRunning(true)}
                accessibilityLabel={name ? `Run assessment for ${name}` : undefined}
              />
            ) : null}
          </>
        )}
      >
        <PagedList
          state={alerts}
          label="Clinical alerts"
          keyOf={alert => alert.alertId}
          header={(
            <View style={styles.header}>
              <ScreeningNotice text={alerts.items[0]?.disclaimer || ALERT_DISCLAIMER} />
              {alerts.acknowledgeError ? (
                <Banner tone="danger" text={alerts.acknowledgeError} onDismiss={alerts.clearAcknowledgeError} />
              ) : null}
            </View>
          )}
          empty={{
            icon:  'shield-checkmark-outline',
            title: 'No alerts for this patient',
            sub:   'Alerts appear here when screening flags a result, a reading, missed doses or a follow-up that is due.',
          }}
          noAccessMessage="Only doctors on this patient's care team can see their clinical alerts."
          renderItem={alert => (
            <AlertCard
              alert={alert}
              showPatient={false}
              acknowledging={alerts.isAcknowledging(alert.alertId)}
              onAcknowledge={row => { void alerts.acknowledge(row); }}
              onOpenDetection={row => setDetectionId(row.detectionId)}
              onOpenSource={openSource}
              canOpenSource={canOpenSource}
            />
          )}
        />
      </SectionCard>

      <DetectionPanel
        detectionId={detectionId}
        onClose={() => setDetectionId(null)}
        onReviewed={detection => { void settleReviewedDetection(alerts, detection); }}
        onStale={() => alerts.refetch()}
      />
      <RunAssessmentDialog
        open={running}
        onClose={() => setRunning(false)}
        patientSystemId={patientSystemId}
        patientName={name}
        lockPatient
        onDetectionCreated={() => alerts.refetch()}
        onDetectionReviewed={detection => { void settleReviewedDetection(alerts, detection); }}
      />
    </UiThemeProvider>
  );
}

const styles = StyleSheet.create({
  header: { gap: space.sm },
});
