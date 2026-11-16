import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, Button, Card, colors, fontSize, InfoRow, space, StatusBadge, STATUS_TONES,
} from '@/components/web/ui';
import type { ImagingResultDto } from '@/hooks/use-doctor-imaging-results';
import { useDoctorName } from '@/lib/doctors/names';
import { formatClinicDateTime } from '@/lib/time/clinic';
import { formatDateOnly } from '@/lib/ui/date-only';
import { ResultAttachmentsButton } from './attachments';
import {
  DetailBlock, DetailText, imagingTypeLabel, ResultAttachment, type ResultAttachmentViewSlot, ResultStatusBadge,
  ReviewStamp,
} from './shared';

// An imaging result as a list row and as the content of the detail panel.

export function imagingTitle(result: Pick<ImagingResultDto, 'imagingType' | 'bodyPart'>): string {
  const type = imagingTypeLabel(result.imagingType);
  const part = (result.bodyPart ?? '').trim();
  return part ? `${type}: ${part}` : type;
}

export function imagingPatientName(result: Pick<ImagingResultDto, 'patientName'>): string {
  return result.patientName?.trim() || 'Unknown patient';
}

/** The author's name: the API's doctorName, else looked up from the doctor's system id (CONTRACTS 12.4). */
function useRecordedBy(result: Pick<ImagingResultDto, 'doctorSystemId' | 'doctorName'>): string {
  return useDoctorName(result.doctorSystemId, result.doctorName);
}

function ImagingBadges({ result }: { result: ImagingResultDto }) {
  return (
    <View style={styles.badges}>
      <ResultStatusBadge status={result.status} />
      <StatusBadge label={imagingTypeLabel(result.imagingType)} tone="neutral" icon="scan-outline" />
    </View>
  );
}

function firstLine(text: string | null): string | null {
  const words = (text ?? '').replace(/\s+/g, ' ').trim();
  if (!words) return null;
  return words.length > 160 ? `${words.slice(0, 157).trimEnd()}…` : words;
}

export function ImagingResultCard({ result, onOpen, onReview }: {
  result:    ImagingResultDto;
  onOpen:    () => void;
  /** Shows "Mark reviewed" when given. */
  onReview?: () => void;
}) {
  const title = imagingTitle(result);
  const patient = imagingPatientName(result);
  const subject = `${title} for ${patient}`;
  const impression = firstLine(result.impression) ?? firstLine(result.findings);
  const author = useRecordedBy(result);
  return (
    <Card style={[styles.card, result.status === 'CRITICAL' && styles.cardCritical]}>
      <View style={styles.head}>
        <View style={styles.titles}>
          <Text style={styles.title} accessibilityRole="header" {...ariaLevel(2)}>{title}</Text>
          <Text style={styles.meta}>{`${patient} · ${formatDateOnly(result.imageDate)}`}</Text>
        </View>
        <ImagingBadges result={result} />
      </View>

      {impression ? <Text style={styles.summary}>{impression}</Text> : null}
      <Text style={styles.meta}>{`Recorded by ${author}`}</Text>
      <ReviewStamp reviewedAt={result.reviewedAt} reviewedBy={result.reviewedBy} reviewerName={result.reviewedByName} compact />

      <View style={styles.actions}>
        <Button label="View details" variant="secondary" size="sm" onPress={onOpen} accessibilityLabel={`View details of ${subject}`} />
        <ResultAttachmentsButton
          kind="IMAGING"
          resultId={result.imagingId}
          patientSystemId={result.patientSystemId}
          documentId={result.documentId}
        />
        {onReview ? (
          <Button
            label="Mark reviewed"
            size="sm"
            icon="checkmark-done-outline"
            onPress={onReview}
            accessibilityLabel={`Mark reviewed: ${subject}`}
          />
        ) : null}
      </View>
    </Card>
  );
}

export function ImagingResultDetail({ result, attachmentViewSlot }: {
  result: ImagingResultDto;
  attachmentViewSlot?: ResultAttachmentViewSlot;
}) {
  const findings = result.findings?.trim();
  const impression = result.impression?.trim();
  const report = result.radiologistReport?.trim();
  const author = useRecordedBy(result);
  return (
    <View style={styles.detail}>
      {result.status === 'CANCELLED' ? (
        <Banner tone="info" text="This result was cancelled. It is kept for the record and no longer appears in the lists." />
      ) : result.status === 'CRITICAL' ? (
        <Banner tone="danger" text="Critical result. It needs urgent attention." />
      ) : null}

      <ImagingBadges result={result} />

      <View>
        <InfoRow label="Patient" value={imagingPatientName(result)} />
        <InfoRow label="Body part" value={result.bodyPart} emptyText="Not recorded" />
        <InfoRow label="Image date" value={formatDateOnly(result.imageDate)} />
        <InfoRow label="Recorded by" value={author} />
        <InfoRow label="Recorded on" value={formatClinicDateTime(result.createdAt)} />
        <InfoRow
          label="Consultation"
          value={result.consultationId != null ? `Consultation #${result.consultationId}` : null}
          emptyText="Not linked to a consultation"
        />
      </View>

      {findings ? <DetailBlock title="Findings"><DetailText>{findings}</DetailText></DetailBlock> : null}
      {impression ? <DetailBlock title="Impression"><DetailText>{impression}</DetailText></DetailBlock> : null}
      {report ? <DetailBlock title="Radiologist report"><DetailText>{report}</DetailText></DetailBlock> : null}
      {!findings && !impression && !report ? (
        <Text style={styles.meta}>No findings, impression or report have been recorded yet.</Text>
      ) : null}

      <DetailBlock title="Review">
        <ReviewStamp reviewedAt={result.reviewedAt} reviewedBy={result.reviewedBy} reviewerName={result.reviewedByName} notes={result.reviewNotes} />
      </DetailBlock>

      <ResultAttachment
        kind="IMAGING"
        documentId={result.documentId}
        patientSystemId={result.patientSystemId}
        viewSlot={attachmentViewSlot}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  card:         { gap: space.sm, borderLeftWidth: 4, borderLeftColor: colors.border },
  cardCritical: { borderLeftColor: STATUS_TONES.critical.bg },
  head:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.sm },
  titles:  { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, gap: 2 },
  title:   { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  meta:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  badges:  { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs, maxWidth: '100%' },
  summary: { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'flex-end', gap: space.sm, marginTop: space.xs },
  detail:  { gap: space.lg, minWidth: 0 },
});
