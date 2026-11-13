import { useMemo } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Button, Card, colors, fontSize, LoadingView, PagedList, space, StatusBadge,
} from '@/components/web/ui';
import { usePatientAiAlerts } from '@/hooks/use-ai-alerts';
import { useDetection, usePatientDetections } from '@/hooks/use-disease-detections';
import { alertTypeIcon, alertTypeLabel, priorityLabel, priorityTone, SCREENING_DISCLAIMER } from '@/lib/ai/labels';
import type { AiAlert, DiseaseDetection } from '@/lib/ai/types';
import { formatClinicDateTime } from '@/lib/time/clinic';
import { useClinicianName } from './clinician-name';
import { DetectionDetail } from './detection-detail';
import { ScreeningNotice } from './screening-notice';

export interface HealthAlertsListProps {
  /** The patient whose alerts are listed: the signed-in patient, or the loved one a caregiver is looking at. */
  patientSystemId: string;
  /** Shown when the server refuses the list (a caregiver without the records permission, or ended access). */
  noAccessMessage?: string;
  /** Heading level of each alert's title. Default 2 (directly under the screen title). */
  headingLevel?: 2 | 3;
}

/** Enough to cover a patient's confirmed screening results in one request; anything beyond is loaded by id. */
const DETECTION_PAGE_SIZE = 100;

/**
 * The confirmed screening result behind an alert. The result usually comes with the patient's detection list;
 * one that the list did not carry is loaded by id. A result that cannot be loaded leaves the alert's own text
 * standing, with a retry.
 */
function ConfirmedResult({ detectionId, known, listSettled, headingLevel }: {
  detectionId:  number;
  known:        DiseaseDetection | undefined;
  /** False while the detection list is still loading, so the single lookup does not race it. */
  listSettled:  boolean;
  headingLevel: 3 | 4;
}) {
  const single = useDetection(detectionId, { enabled: !known && listSettled });
  const detection = known ?? single.data;

  if (detection) {
    return (
      <View style={styles.result}>
        <DetectionDetail detection={detection} viewer="patient" headingLevel={headingLevel} />
      </View>
    );
  }
  if (!listSettled || single.loading) return <LoadingView compact label="Loading screening result" />;
  // 404: the result is not (or no longer) shared with this reader. The alert text above is all there is to show.
  if (!single.error || single.errorStatus === 404) return null;
  return (
    <View style={styles.resultError} accessibilityRole="alert">
      <Text style={styles.meta}>The details of this screening result could not be loaded.</Text>
      <Button label="Retry" icon="refresh-outline" variant="secondary" size="sm" onPress={single.refetch} />
    </View>
  );
}

function HealthAlertCard({ alert, detection, detectionsSettled, headingLevel }: {
  alert:             AiAlert;
  detection:         DiseaseDetection | undefined;
  detectionsSettled: boolean;
  headingLevel:      2 | 3;
}) {
  const seenBy = useClinicianName(alert.acknowledgedBySystemId, 'patient');
  const raised = formatClinicDateTime(alert.createdAt);
  const seenAt = formatClinicDateTime(alert.acknowledgedAt);
  return (
    <Card style={styles.card}>
      <View style={styles.badges}>
        <StatusBadge label={alertTypeLabel(alert.alertType)} tone="neutral" icon={alertTypeIcon(alert.alertType)} />
        <StatusBadge label={priorityLabel(alert.priority)} tone={priorityTone(alert.priority)} />
      </View>
      <Text style={styles.title} accessibilityRole="header" {...ariaLevel(headingLevel)}>{alert.title}</Text>
      <Text style={styles.message}>{alert.message}</Text>
      <View style={styles.metaRow}>
        {raised ? <Text style={styles.meta}>Raised {raised}</Text> : null}
        {alert.acknowledged ? (
          <Text style={styles.meta}>
            Seen{seenBy ? ` by ${seenBy}` : ' by the care team'}{seenAt ? ` on ${seenAt}` : ''}
          </Text>
        ) : null}
      </View>
      {alert.detectionId !== null && alert.detectionId !== undefined ? (
        <ConfirmedResult
          detectionId={alert.detectionId}
          known={detection}
          listSettled={detectionsSettled}
          headingLevel={headingLevel === 2 ? 3 : 4}
        />
      ) : null}
    </Card>
  );
}

/**
 * Read-only health alerts for the patient side: the non-diagnostic alerts (missed doses, a follow-up that is
 * due) and the screening results a doctor has confirmed. Each confirmed result is shown once, inside the alert
 * that was raised for it. There are no source links, no suggested actions and nothing to acknowledge here.
 * The list carries the "automated screening, not a diagnosis" label itself, in every state.
 */
export function HealthAlertsList({ patientSystemId, noAccessMessage, headingLevel = 2 }: HealthAlertsListProps) {
  const alerts = usePatientAiAlerts(patientSystemId);
  // Confirmed results are only needed once an alert refers to one, which most patients never have.
  const needsDetections = alerts.items.some(alert => alert.detectionId !== null && alert.detectionId !== undefined);
  const detections = usePatientDetections(patientSystemId, { enabled: needsDetections, pageSize: DETECTION_PAGE_SIZE });

  const byId = useMemo(() => {
    const map = new Map<number, DiseaseDetection>();
    for (const detection of detections.items) map.set(detection.detectionId, detection);
    return map;
  }, [detections.items]);

  return (
    <PagedList
      state={alerts}
      label="Health alerts"
      keyOf={alert => alert.alertId}
      header={<ScreeningNotice text={SCREENING_DISCLAIMER} />}
      empty={{
        icon:  'shield-checkmark-outline',
        title: 'No health alerts',
        sub:   'Reminders about missed doses or a follow-up that is due, and screening results a doctor has confirmed, appear here.',
      }}
      noAccessMessage={noAccessMessage}
      renderItem={alert => (
        <HealthAlertCard
          alert={alert}
          detection={alert.detectionId !== null && alert.detectionId !== undefined ? byId.get(alert.detectionId) : undefined}
          detectionsSettled={!detections.loading}
          headingLevel={headingLevel}
        />
      )}
    />
  );
}

const styles = StyleSheet.create({
  card:    { gap: space.sm },
  badges:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.xs },
  title:   { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  message: { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },
  metaRow: { flexDirection: 'row', flexWrap: 'wrap', columnGap: space.md, rowGap: 2 },
  meta:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  result:      { marginTop: space.xs, paddingTop: space.md, borderTopWidth: 1, borderTopColor: colors.border },
  resultError: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
});
