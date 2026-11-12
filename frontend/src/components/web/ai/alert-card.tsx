import { StyleSheet, Text, View } from 'react-native';
import {
  Button, Card, colors, fontSize, Icon, radius, space, StatusBadge, useUiTheme,
} from '@/components/web/ui';
import {
  alertSourceLink, alertTypeIcon, alertTypeLabel, type AlertSourceLink, priorityLabel, priorityTone,
  reviewStatusLabel, reviewStatusTone,
} from '@/lib/ai/labels';
import type { AiAlert } from '@/lib/ai/types';
import { formatClinicDateTime } from '@/lib/time/clinic';
import { useClinicianName } from './clinician-name';

export interface AlertCardProps {
  alert: AiAlert;
  /** Show the patient's name (the inbox). The per-patient panel leaves it out. Default true. */
  showPatient?:  boolean;
  /** The row a notification link pointed at. */
  highlighted?:  boolean;
  /** True while this alert's acknowledgement is in flight. */
  acknowledging: boolean;
  onAcknowledge: (alert: AiAlert) => void;
  /** Opens the screening result behind the alert. The button is shown only for alerts that have one. */
  onOpenDetection?: (alert: AiAlert) => void;
  /**
   * Opens the record that triggered the alert. Pass it only when the dashboard has the target section
   * (`canOpenSource`), so the link is absent rather than dead.
   */
  onOpenSource?:  (link: AlertSourceLink, alert: AiAlert) => void;
  canOpenSource?: (link: AlertSourceLink) => boolean;
}

/**
 * One alert as a doctor or administrator sees it: priority, type, patient, message, the suggested action, a
 * link to the record that triggered it and, when the server allows it, Acknowledge.
 */
export function AlertCard({
  alert, showPatient = true, highlighted = false, acknowledging, onAcknowledge, onOpenDetection, onOpenSource, canOpenSource,
}: AlertCardProps) {
  const theme = useUiTheme();
  const acknowledgedBy = useClinicianName(alert.acknowledgedBySystemId, 'clinician');
  const link = alertSourceLink(alert);
  const showLink = !!link && !!onOpenSource && (canOpenSource ? canOpenSource(link) : true);
  const hasDetection = alert.detectionId !== null && alert.detectionId !== undefined;
  const detectionLabel = alert.detectionReviewStatus === 'PENDING_REVIEW' ? 'Review screening result' : 'View screening result';
  const raised = formatClinicDateTime(alert.createdAt);
  const acknowledgedAt = formatClinicDateTime(alert.acknowledgedAt);

  return (
    <Card style={[styles.card, highlighted && { borderColor: theme.accent, backgroundColor: theme.accentBg }]}>
      {highlighted ? (
        <View style={styles.linkedRow}>
          <Icon name="notifications-outline" size={14} color={theme.accent} />
          <Text style={[styles.linked, { color: theme.accent }]}>Opened from your notification</Text>
        </View>
      ) : null}

      <View style={styles.badges}>
        <StatusBadge label={priorityLabel(alert.priority)} tone={priorityTone(alert.priority)} />
        <StatusBadge label={alertTypeLabel(alert.alertType)} tone="neutral" icon={alertTypeIcon(alert.alertType)} />
        {alert.acknowledged
          ? <StatusBadge label="Acknowledged" tone="success" />
          : alert.actionRequired
            ? <StatusBadge label="Action required" tone="danger" icon="alert-circle-outline" />
            : <StatusBadge label="Unacknowledged" tone="warning" icon="ellipse-outline" />}
        {hasDetection && alert.detectionReviewStatus ? (
          <StatusBadge
            label={`Screening result: ${reviewStatusLabel(alert.detectionReviewStatus).toLowerCase()}`}
            tone={reviewStatusTone(alert.detectionReviewStatus)}
          />
        ) : null}
      </View>

      <Text style={styles.title}>{alert.title}</Text>
      {showPatient ? (
        <View style={styles.metaRow}>
          <Icon name="person-outline" size={14} color={colors.textMuted} />
          <Text style={styles.patient}>{alert.patientName?.trim() || 'Patient'}</Text>
        </View>
      ) : null}
      <Text style={styles.message}>{alert.message}</Text>

      {alert.suggestedAction ? (
        <View style={styles.suggestion}>
          <Text style={styles.suggestionLabel}>Suggested action</Text>
          <Text style={styles.suggestionText}>{alert.suggestedAction}</Text>
        </View>
      ) : null}

      <View style={styles.meta}>
        {raised ? <Text style={styles.metaText}>Raised {raised}</Text> : null}
        {alert.acknowledged ? (
          <Text style={styles.metaText}>
            Acknowledged{acknowledgedBy ? ` by ${acknowledgedBy}` : ''}{acknowledgedAt ? ` on ${acknowledgedAt}` : ''}
          </Text>
        ) : null}
      </View>

      {showLink || (hasDetection && onOpenDetection) || alert.canAcknowledge ? (
        <View style={styles.actions}>
          {showLink && link ? (
            <Button
              label={link.label}
              icon="open-outline"
              variant="ghost"
              size="sm"
              onPress={() => onOpenSource?.(link, alert)}
              accessibilityLabel={`${link.label} for ${alert.patientName}`}
            />
          ) : null}
          {hasDetection && onOpenDetection ? (
            <Button
              label={detectionLabel}
              icon="document-text-outline"
              variant="secondary"
              size="sm"
              onPress={() => onOpenDetection(alert)}
              accessibilityLabel={`${detectionLabel} for ${alert.patientName}`}
            />
          ) : null}
          {alert.canAcknowledge ? (
            <Button
              label="Acknowledge"
              icon="checkmark-outline"
              size="sm"
              loading={acknowledging}
              onPress={() => onAcknowledge(alert)}
              accessibilityLabel={`Acknowledge ${alert.title} for ${alert.patientName}`}
            />
          ) : null}
        </View>
      ) : null}
    </Card>
  );
}

const styles = StyleSheet.create({
  card:      { gap: space.sm },
  linkedRow: { flexDirection: 'row', alignItems: 'center', gap: space.xs },
  linked:    { fontSize: fontSize.xs, fontWeight: '700' },
  badges:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.xs },
  title:     { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  metaRow:   { flexDirection: 'row', alignItems: 'center', gap: space.xs, minWidth: 0 },
  patient:   { flexShrink: 1, fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  message:   { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },

  suggestion:      { gap: 2, padding: space.md, borderRadius: radius.md, backgroundColor: colors.bgSubtle },
  suggestionLabel: { fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted },
  suggestionText:  { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },

  meta:     { flexDirection: 'row', flexWrap: 'wrap', columnGap: space.md, rowGap: 2 },
  metaText: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  actions:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'flex-end', gap: space.sm },
});
