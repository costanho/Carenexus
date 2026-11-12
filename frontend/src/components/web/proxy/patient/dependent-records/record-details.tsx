import type { ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, Button, colors, fontSize, InfoRow, radius, space, StatusBadge, statusLabel, statusTone,
} from '@/components/web/ui';
import type { CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import type {
  ConsultationDto, ImagingResultDto, LabResultDto, MedicalRecordDto,
} from '@/hooks/use-dependent-records';
import { useDoctorName } from '@/lib/doctors/names';
import {
  appointmentTypeLabel, formatDay, formatMoment, consultationWhen, imagingTypeLabel, labFlagTone, labTypeLabel,
  parseLabSubTests, recordTypeLabel,
} from './format';

// The detail views opened from a record card (or from a notification link): everything the caregiver may read
// about one lab result, imaging result, medical record or consultation. Read-only.

/**
 * The files attached to a lab result, imaging result or medical record. The control that lists and downloads
 * them is rendered by the screen that owns documents; it may render nothing when no file is attached.
 */
export interface RecordAttachment {
  /** The result's own document reference (`documentId` of a lab or imaging result), when it has one. */
  documentId:      number | null;
  /** resultId, imagingId or recordId of the record the files are linked to. */
  resourceId:      number;
  patientSystemId: string;
  kind:            'labs' | 'imaging' | 'records';
  /** What the files belong to, for the control's accessible name: "Full blood count". */
  label:           string;
  /** The caregiver's grant for the loved one the record belongs to, when it is among the loved ones in view. */
  dependent?:      CaregiverDependent;
}

export type AttachmentRenderer = (attachment: RecordAttachment) => ReactNode;

function clean(value: string | null | undefined): string {
  return (value ?? '').trim();
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle} accessibilityRole="header" {...ariaLevel(3)}>{title}</Text>
      {children}
    </View>
  );
}

/** A titled block of free text. Without `emptyText` the block is left out when there is no text. */
function TextSection({ title, text, emptyText }: { title: string; text: string | null | undefined; emptyText?: string }) {
  const body = clean(text);
  if (!body && !emptyText) return null;
  return (
    <Section title={title}>
      <Text style={[styles.paragraph, !body && styles.muted]}>{body || emptyText}</Text>
    </Section>
  );
}

function ReviewSection({ status, reviewedAt, reviewedByName, reviewNotes }: {
  status:         string;
  reviewedAt:     string | null;
  reviewedByName: string | null;
  reviewNotes:    string | null;
}) {
  if (!reviewedAt) {
    if (status === 'CANCELLED') return null;
    return (
      <Section title="Doctor's review">
        <Text style={[styles.paragraph, styles.muted]}>A doctor has not reviewed this result yet.</Text>
      </Section>
    );
  }
  return (
    <Section title="Doctor's review">
      <InfoRow label="Reviewed by" value={clean(reviewedByName) || 'A doctor on the care team'} />
      <InfoRow label="Reviewed on" value={formatMoment(reviewedAt)} />
      {clean(reviewNotes) ? <Text style={styles.paragraph}>{clean(reviewNotes)}</Text> : null}
    </Section>
  );
}

function ResultHeader({ status, domain, critical }: { status: string; domain: 'lab' | 'imaging'; critical: boolean }) {
  return (
    <>
      <View style={styles.badges}>
        <StatusBadge label={statusLabel(status)} tone={statusTone(domain, status)} />
      </View>
      {critical ? (
        <Banner tone="danger" icon="warning" text="The care team marked this result as critical." />
      ) : null}
      {status === 'CANCELLED' ? (
        <Banner tone="info" text="This result was cancelled by the care team and is kept for reference only." />
      ) : null}
    </>
  );
}

// ─── Lab result ───────────────────────────────────────────────────────────────

export function LabResultDetail({ row, patientName, renderAttachment }: {
  row:               LabResultDto;
  patientName:       string;
  renderAttachment?: AttachmentRenderer;
}) {
  const doctor = useDoctorName(row.doctorSystemId, row.doctorName);
  const subTests = parseLabSubTests(row.resultData);
  const headline = clean(row.resultValue);
  const attachment = renderAttachment
    ? renderAttachment({
      documentId: row.documentId ?? null, resourceId: row.resultId, patientSystemId: row.patientSystemId,
      kind: 'labs', label: row.testName,
    })
    : null;

  return (
    <View style={styles.detail}>
      <Text style={styles.heading}>{row.testName}</Text>
      <ResultHeader status={row.status} domain="lab" critical={row.isCritical} />

      <View>
        <InfoRow label="For" value={patientName} />
        <InfoRow label="Test type" value={labTypeLabel(row.testType)} />
        <InfoRow label="Test date" value={formatDay(row.testDate)} />
        <InfoRow label="Recorded by" value={doctor} />
      </View>

      <Section title="Result">
        {headline ? (
          <View>
            <InfoRow label="Value" value={[headline, clean(row.unit)].filter(Boolean).join(' ')} />
            <InfoRow label="Reference range" value={row.referenceRange} emptyText="Not recorded" />
          </View>
        ) : null}
        {subTests.length > 0 ? (
          <View style={styles.subTests} role="list" aria-label="Values in this result">
            {subTests.map(sub => (
              <View key={sub.key} style={styles.subTest} role="listitem">
                <View style={styles.subTestHead}>
                  <Text style={styles.subTestName}>{sub.name}</Text>
                  {sub.flag ? <StatusBadge label={statusLabel(sub.flag)} tone={labFlagTone(sub.flag)} /> : null}
                </View>
                <Text style={styles.subTestValue}>{sub.value || 'No value recorded'}</Text>
                {sub.reference ? <Text style={styles.subTestMeta}>Reference: {sub.reference}</Text> : null}
                {sub.interpretation ? <Text style={styles.subTestMeta}>{sub.interpretation}</Text> : null}
              </View>
            ))}
          </View>
        ) : null}
        {!headline && subTests.length === 0 ? (
          <Text style={[styles.paragraph, styles.muted]}>No result values have been recorded yet.</Text>
        ) : null}
      </Section>

      <ReviewSection
        status={row.status}
        reviewedAt={row.reviewedAt}
        reviewedByName={row.reviewedByName}
        reviewNotes={row.reviewNotes}
      />

      {/* The control renders nothing when no file is attached, so it carries no heading of its own here. */}
      {attachment ? <View style={styles.attachment}>{attachment}</View> : null}
    </View>
  );
}

// ─── Imaging result ───────────────────────────────────────────────────────────

export function ImagingResultDetail({ row, patientName, renderAttachment }: {
  row:               ImagingResultDto;
  patientName:       string;
  renderAttachment?: AttachmentRenderer;
}) {
  const doctor = useDoctorName(row.doctorSystemId, row.doctorName);
  const type = imagingTypeLabel(row.imagingType);
  const attachment = renderAttachment
    ? renderAttachment({
      documentId: row.documentId ?? null, resourceId: row.imagingId, patientSystemId: row.patientSystemId,
      kind: 'imaging', label: type,
    })
    : null;
  const nothingWritten = !clean(row.findings) && !clean(row.impression) && !clean(row.radiologistReport);

  return (
    <View style={styles.detail}>
      <Text style={styles.heading}>{clean(row.bodyPart) ? `${type}, ${clean(row.bodyPart)}` : type}</Text>
      <ResultHeader status={row.status} domain="imaging" critical={row.isCritical} />

      <View>
        <InfoRow label="For" value={patientName} />
        <InfoRow label="Imaging type" value={type} />
        <InfoRow label="Body part" value={row.bodyPart} emptyText="Not recorded" />
        <InfoRow label="Image date" value={formatDay(row.imageDate)} />
        <InfoRow label="Recorded by" value={doctor} />
      </View>

      <TextSection title="Impression" text={row.impression} />
      <TextSection title="Findings" text={row.findings} />
      <TextSection title="Radiologist's report" text={row.radiologistReport} />
      {nothingWritten ? (
        <Section title="Report">
          <Text style={[styles.paragraph, styles.muted]}>No report has been written for this image yet.</Text>
        </Section>
      ) : null}

      <ReviewSection
        status={row.status}
        reviewedAt={row.reviewedAt}
        reviewedByName={row.reviewedByName}
        reviewNotes={row.reviewNotes}
      />

      {attachment ? <View style={styles.attachment}>{attachment}</View> : null}
    </View>
  );
}

// ─── Medical record ───────────────────────────────────────────────────────────

export function MedicalRecordDetail({ row, patientName, renderAttachment }: {
  row:               MedicalRecordDto;
  patientName:       string;
  renderAttachment?: AttachmentRenderer;
}) {
  const doctor = useDoctorName(row.doctorSystemId, row.doctorName);
  const attachment = renderAttachment
    ? renderAttachment({
      documentId: null, resourceId: row.recordId, patientSystemId: row.patientSystemId, kind: 'records', label: row.title,
    })
    : null;
  const codes = row.icdCodeList ?? [];
  const edited = row.updatedAt !== row.createdAt;
  const nothingWritten = !clean(row.description) && !clean(row.observations) && !clean(row.treatmentPlan);

  return (
    <View style={styles.detail}>
      <Text style={styles.heading}>{row.title}</Text>
      <View style={styles.badges}>
        <StatusBadge label={recordTypeLabel(row.recordType)} tone="neutral" icon={null} />
        {row.source === 'CONSULTATION'
          ? <StatusBadge label="Created from consultation" tone="info" icon="medkit-outline" />
          : null}
        {edited ? <StatusBadge label="Edited" tone="neutral" icon="create-outline" /> : null}
      </View>

      <View>
        <InfoRow label="For" value={patientName} />
        <InfoRow label="Filed on" value={formatMoment(row.createdAt)} />
        <InfoRow label="Filed by" value={doctor} />
      </View>

      <TextSection title="Description" text={row.description} />
      <TextSection title="Observations" text={row.observations} />
      <TextSection title="Treatment plan" text={row.treatmentPlan} />
      {nothingWritten ? (
        <Text style={[styles.paragraph, styles.muted]}>This record has no written notes.</Text>
      ) : null}

      {codes.length > 0 ? (
        <Section title="Diagnosis codes (ICD-10)">
          <View style={styles.codes} role="list">
            {codes.map(code => (
              <View key={code.code} style={styles.code} role="listitem">
                <Text style={styles.codeValue}>{code.code}</Text>
                <Text style={styles.codeText}>
                  {clean(code.description) || 'No description'}{code.verified ? '' : ' (unverified code)'}
                </Text>
              </View>
            ))}
          </View>
        </Section>
      ) : null}

      {attachment ? <View style={styles.attachment}>{attachment}</View> : null}
    </View>
  );
}

// ─── Consultation ─────────────────────────────────────────────────────────────

function followUpText(row: ConsultationDto): string {
  if (!row.followUpRequired) return 'No follow-up visit was requested.';
  return row.followUpDate
    ? `A follow-up visit is needed by ${formatDay(row.followUpDate)}.`
    : 'A follow-up visit is needed. No date was set.';
}

export function ConsultationDetail({ row, patientName, onOpenRecord }: {
  row:           ConsultationDto;
  patientName:   string;
  /** Opens the consultation's medical record. Omitted when records are not shared with the caregiver. */
  onOpenRecord?: (recordId: number) => void;
}) {
  const doctor = useDoctorName(row.doctorSystemId);
  const recordId = row.medicalRecordId;

  return (
    <View style={styles.detail}>
      <Text style={styles.heading}>{clean(row.chiefComplaint) || 'Consultation'}</Text>
      {row.status !== 'COMPLETED' ? (
        <View style={styles.badges}>
          <StatusBadge label={statusLabel(row.status)} tone={statusTone('consultation', row.status)} />
        </View>
      ) : null}

      <View>
        <InfoRow label="For" value={patientName} />
        <InfoRow label="Date" value={formatMoment(consultationWhen(row))} />
        <InfoRow label="Doctor" value={doctor} />
        {row.appointmentType ? <InfoRow label="Visit type" value={appointmentTypeLabel(row.appointmentType)} /> : null}
        {row.durationMinutes ? <InfoRow label="Duration" value={`${row.durationMinutes} minutes`} /> : null}
      </View>

      <TextSection title="Diagnosis" text={row.diagnosis} emptyText="No diagnosis was recorded." />
      <TextSection title="Treatment plan" text={row.treatmentPlan} emptyText="No treatment plan was recorded." />

      <Section title="Follow-up">
        <Text style={styles.paragraph}>{followUpText(row)}</Text>
        {clean(row.followUpInstructions) ? <Text style={styles.paragraph}>{clean(row.followUpInstructions)}</Text> : null}
      </Section>

      <TextSection title="History of the problem" text={row.historyOfPresentIllness} />
      <TextSection title="Examination" text={row.physicalExamination} />
      <TextSection title="Doctor's notes" text={row.clinicalNotes} />

      {recordId != null && onOpenRecord ? (
        <Button
          label="Open medical record"
          accessibilityLabel={`Open medical record of this consultation for ${patientName}`}
          variant="secondary"
          icon="document-text-outline"
          onPress={() => onOpenRecord(recordId)}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  detail:  { gap: space.lg, minWidth: 0 },
  heading: { fontSize: fontSize.lg, fontWeight: '700', color: colors.text, lineHeight: 22 },
  badges:  { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs },
  attachment: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },

  section:      { gap: space.sm, minWidth: 0 },
  sectionTitle: {
    fontSize: fontSize.md, fontWeight: '700', color: colors.text,
    paddingBottom: space.xs, borderBottomWidth: 1, borderBottomColor: colors.border,
  },
  paragraph: { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },
  muted:     { color: colors.textMuted },

  subTests:     { gap: space.sm },
  subTest:      { gap: 2, padding: space.md, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, minWidth: 0 },
  subTestHead:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  subTestName:  { flexShrink: 1, fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  subTestValue: { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  subTestMeta:  { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  codes:     { gap: space.xs },
  code:      { flexDirection: 'row', flexWrap: 'wrap', columnGap: space.sm, rowGap: 2 },
  codeValue: { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  codeText:  { flexShrink: 1, fontSize: fontSize.md, color: colors.text },
});
