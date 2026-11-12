import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, Button, Card, colors, fontSize, InfoRow, radius, space, StatusBadge, STATUS_TONES,
} from '@/components/web/ui';
import { abnormalSubTestCount, type DoctorLabResult, type LabSubTest } from '@/hooks/use-doctor-lab-results';
import { useDoctorName } from '@/lib/doctors/names';
import { formatClinicDateTime } from '@/lib/time/clinic';
import { formatDateOnly } from '@/lib/ui/date-only';
import { ResultAttachmentsButton } from './attachments';
import {
  DetailBlock, FlagPill, labelify, labTestTypeLabel, ResultAttachment, type ResultAttachmentViewSlot,
  ResultStatusBadge, ReviewStamp,
} from './shared';

// A lab result as a list row and as the content of the detail panel.

function headline(result: DoctorLabResult): string | null {
  if (!result.result_value) return null;
  const value = `${result.result_value}${result.unit ? ` ${result.unit}` : ''}`;
  return result.reference_range ? `${value} (reference ${result.reference_range})` : value;
}

/** Status, critical flag, test type and the number of abnormal sub-tests, each in words. */
function LabBadges({ result }: { result: DoctorLabResult }) {
  const abnormal = abnormalSubTestCount(result);
  return (
    <View style={styles.badges}>
      <ResultStatusBadge status={result.status} />
      {/* A result can be flagged critical while it is still pending or waiting for review. */}
      {!!result.is_critical && result.status !== 'CRITICAL' ? <StatusBadge label="Critical" tone="critical" /> : null}
      <StatusBadge label={labTestTypeLabel(result.test_type)} tone="neutral" icon="flask-outline" />
      {abnormal > 0 ? <StatusBadge label={`${abnormal} abnormal`} tone="warning" icon="warning-outline" /> : null}
    </View>
  );
}

export function LabResultCard({ result, onOpen, onReview }: {
  result:    DoctorLabResult;
  onOpen:    () => void;
  /** Shows "Mark reviewed" when given. */
  onReview?: () => void;
}) {
  const subject = `${result.test_name} for ${result.patient_name}`;
  const value = headline(result);
  const subTests = result.result_data ? Object.keys(result.result_data).length : 0;
  const author = useDoctorName(result.doctor_system_id, result.recorded_by_name);
  return (
    <Card style={[styles.card, !!result.is_critical && styles.cardCritical]}>
      <View style={styles.head}>
        <View style={styles.titles}>
          <Text style={styles.title} accessibilityRole="header" {...ariaLevel(2)}>{result.test_name}</Text>
          <Text style={styles.meta}>{`${result.patient_name} · ${formatDateOnly(result.test_date)}`}</Text>
        </View>
        <LabBadges result={result} />
      </View>

      {value ? <Text style={styles.value}>{`Result: ${value}`}</Text> : null}
      {subTests > 0 ? <Text style={styles.meta}>{`${subTests} ${subTests === 1 ? 'sub-test' : 'sub-tests'}`}</Text> : null}
      <Text style={styles.meta}>{`Recorded by ${author}`}</Text>
      <ReviewStamp reviewedAt={result.reviewed_at} reviewedBy={result.reviewed_by_system_id} reviewerName={result.reviewer_name} compact />

      <View style={styles.actions}>
        <Button label="View details" variant="secondary" size="sm" onPress={onOpen} accessibilityLabel={`View details of ${subject}`} />
        <ResultAttachmentsButton
          kind="LAB"
          resultId={result.result_id}
          patientSystemId={result.patient_system_id}
          documentId={result.document_id}
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

function SubTestRow({ name, sub }: { name: string; sub: LabSubTest }) {
  const value = `${sub.value}${sub.unit ? ` ${sub.unit}` : ''}`.trim();
  return (
    <View style={styles.subRow} role="listitem">
      <View style={styles.subMain}>
        <Text style={styles.subName}>{labelify(name)}</Text>
        <View style={styles.subValueWrap}>
          {value ? <Text style={styles.subValue}>{value}</Text> : null}
          <FlagPill flag={sub.flag} />
        </View>
      </View>
      {sub.reference ? <Text style={styles.subNote}>{`Reference: ${sub.reference}`}</Text> : null}
      {sub.interpretation ? <Text style={styles.subNote}>{sub.interpretation}</Text> : null}
    </View>
  );
}

export function LabResultDetail({ result, attachmentViewSlot }: {
  result: DoctorLabResult;
  attachmentViewSlot?: ResultAttachmentViewSlot;
}) {
  const subTests = Object.entries(result.result_data ?? {});
  const author = useDoctorName(result.doctor_system_id, result.recorded_by_name);
  return (
    <View style={styles.detail}>
      {result.status === 'CANCELLED' ? (
        <Banner tone="info" text="This result was cancelled. It is kept for the record and no longer appears in the lists." />
      ) : result.is_critical ? (
        <Banner tone="danger" text="Critical result. It needs urgent attention." />
      ) : null}

      <LabBadges result={result} />

      <View>
        <InfoRow label="Patient" value={result.patient_name} />
        <InfoRow label="Test date" value={formatDateOnly(result.test_date)} />
        <InfoRow label="Result" value={headline(result)} emptyText={subTests.length > 0 ? 'See the sub-tests below' : 'No value recorded'} />
        <InfoRow label="Recorded by" value={author} />
        <InfoRow label="Recorded on" value={formatClinicDateTime(result.created_at)} />
        <InfoRow
          label="Consultation"
          value={result.consultation_id != null ? `Consultation #${result.consultation_id}` : null}
          emptyText="Not linked to a consultation"
        />
      </View>

      {subTests.length > 0 ? (
        <DetailBlock title={`Sub-tests (${subTests.length})`}>
          <View style={styles.subList} role="list">
            {subTests.map(([name, sub]) => <SubTestRow key={name} name={name} sub={sub} />)}
          </View>
        </DetailBlock>
      ) : null}

      <DetailBlock title="Review">
        <ReviewStamp reviewedAt={result.reviewed_at} reviewedBy={result.reviewed_by_system_id} reviewerName={result.reviewer_name} notes={result.review_notes} />
      </DetailBlock>

      <ResultAttachment
        kind="LAB"
        documentId={result.document_id}
        patientSystemId={result.patient_system_id}
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
  value:   { fontSize: fontSize.md, fontWeight: '600', color: colors.text, lineHeight: 20 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'flex-end', gap: space.sm, marginTop: space.xs },

  detail:  { gap: space.lg, minWidth: 0 },
  subList: { borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, overflow: 'hidden' },
  subRow:  { gap: space.xs, paddingHorizontal: space.md, paddingVertical: space.sm, borderBottomWidth: 1, borderBottomColor: colors.border, backgroundColor: colors.bg },
  subMain: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  subName: { flexGrow: 1, flexShrink: 1, flexBasis: 140, minWidth: 0, fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  subValueWrap: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, maxWidth: '100%' },
  subValue: { fontSize: fontSize.md, color: colors.text },
  subNote:  { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
