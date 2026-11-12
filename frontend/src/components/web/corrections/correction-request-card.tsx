import type { ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { ariaLevel, Button, Card, colors, fontSize, radius, space, StatusBadge, statusTone, useUiTheme } from '@/components/web/ui';
import {
  correctionRequesterLabel, correctionResourceLabel, correctionStatusLabel, formatCorrectionInstant,
} from '@/lib/corrections/labels';
import type { CorrectionRequest } from '@/lib/corrections/types';

/** "Dr. First Last" for a doctor name the server sends without a title. */
export function withDoctorTitle(name: string | null | undefined): string {
  const n = (name ?? '').replace(/\s+/g, ' ').trim();
  if (!n) return '';
  return /^dr(\.|\s)/i.test(n) ? n : `Dr. ${n}`;
}

export interface CorrectionRequestCardProps {
  request: CorrectionRequest;
  /** Shows whose record it is (the doctor's queue lists several patients). */
  showPatient?: boolean;
  /** Marks the request a notification link points at. */
  highlighted?: boolean;
  /** Opens the record the request is about; no link is shown without it. */
  onOpenRecord?: (request: CorrectionRequest) => void;
  /** Buttons under the request (Accept, Reject). */
  actions?: ReactNode;
}

/**
 * One correction request: which kind of record, who asked and when, what they asked for, who reviews it and the
 * answer once it is resolved. The request carries no clinical content, so the record itself is one click away.
 */
export function CorrectionRequestCard({ request, showPatient = false, highlighted = false, onOpenRecord, actions }: CorrectionRequestCardProps) {
  const { accent, accentBg } = useUiTheme();
  const kind = correctionResourceLabel(request.resourceType);
  const asked = formatCorrectionInstant(request.createdAt);
  const resolved = formatCorrectionInstant(request.resolvedAt);
  const reviewer = withDoctorTitle(request.assignedDoctorName);

  return (
    <Card style={[styles.card, highlighted && { borderColor: accent, backgroundColor: accentBg }]}>
      <View style={styles.header}>
        <View style={styles.titles}>
          <Text style={styles.title} accessibilityRole="header" {...ariaLevel(3)}>
            {showPatient ? `${kind} of ${request.patientName}` : kind}
          </Text>
          <Text style={styles.meta}>
            {`Requested by ${request.requestedByName} (${correctionRequesterLabel(request.requestedByRole)})${asked ? ` on ${asked}` : ''}`}
          </Text>
        </View>
        <StatusBadge label={correctionStatusLabel(request.status)} tone={statusTone('correction', request.status)} />
      </View>

      <View style={styles.block}>
        <Text style={styles.blockLabel}>Requested correction</Text>
        <Text style={styles.blockText}>{request.requestedText}</Text>
      </View>

      {request.status === 'OPEN' ? (
        <Text style={styles.meta}>
          {reviewer ? `Waiting for a review by ${reviewer}.` : 'Waiting for a doctor to be assigned.'}
        </Text>
      ) : (
        <View style={styles.block}>
          <Text style={styles.blockLabel}>
            {`${correctionStatusLabel(request.status)}${request.resolverName ? ` by ${request.resolverName}` : ''}${resolved ? ` on ${resolved}` : ''}`}
          </Text>
          <Text style={styles.blockText}>{request.resolutionNote?.trim() || 'No note was left.'}</Text>
        </View>
      )}

      {onOpenRecord || actions ? (
        <View style={styles.actions}>
          {onOpenRecord ? (
            <Button
              label="Open record"
              accessibilityLabel={`Open record: ${kind.toLowerCase()}${showPatient ? ` of ${request.patientName}` : ''}`}
              icon="open-outline"
              variant="secondary"
              size="sm"
              onPress={() => onOpenRecord(request)}
            />
          ) : null}
          {actions}
        </View>
      ) : null}
    </Card>
  );
}

const styles = StyleSheet.create({
  card:    { gap: space.md },
  header:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.sm },
  titles:  { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, gap: 2 },
  title:   { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  meta:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  block:   { gap: space.xs, padding: space.md, borderRadius: radius.md, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.bg },
  blockLabel: { fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted },
  blockText:  { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
});
