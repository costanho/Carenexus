import type { ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  Button, Card, colors, fontSize, Icon, type IoniconsName, radius, space, StatusBadge, statusLabel, statusTone,
  STATUS_TONES, useUiTheme,
} from '@/components/web/ui';
import type {
  ConsultationDto, ImagingResultDto, LabResultDto, MedicalRecordDto,
} from '@/hooks/use-dependent-records';
import { useDoctorName } from '@/lib/doctors/names';
import { formatClinicDate } from '@/lib/time/clinic';
import {
  appointmentTypeLabel, consultationDay, formatDay, imagingTypeLabel, labHeadline, labTypeLabel, recordTypeLabel,
} from './format';

// One card per record in the caregiver's Documents & Results lists. A card shows what the record is, when it
// was made and by whom, and opens the detail view. The loved one's name is in the heading of the group the card
// sits in, and in the button's accessible name.

/** Sentence on a result the care team marked critical. Shown as text, never by colour alone. */
export const CRITICAL_NOTICE = 'Marked critical by the care team';

function RecordRow({ icon, title, meta, summary, badges, critical = false, openLabel, onOpen, actions }: {
  icon:      IoniconsName;
  title:     string;
  /** Short facts under the title (type, date, doctor); blanks are skipped. */
  meta:      (string | null | undefined)[];
  /** One or two lines of content. */
  summary?:  string | null;
  badges?:   ReactNode;
  critical?: boolean;
  /** Context for the "View details" button: "Full blood count for Thomas Dube". */
  openLabel: string;
  onOpen:    () => void;
  /** More controls beside "View details" (for example "Request a correction"). */
  actions?:  ReactNode;
}) {
  const { accent, accentBg } = useUiTheme();
  const tone = STATUS_TONES.danger;
  const facts = meta.map(m => (m ?? '').trim()).filter(Boolean);
  return (
    <Card padded={false} style={[styles.card, critical && { borderColor: tone.border }]}>
      {critical ? (
        <View style={[styles.critical, { backgroundColor: tone.bg }]}>
          <Icon name="warning" size={14} color={tone.fg} />
          <Text style={[styles.criticalText, { color: tone.fg }]}>{CRITICAL_NOTICE}</Text>
        </View>
      ) : null}
      <View style={styles.body}>
        <View style={[styles.iconWrap, { backgroundColor: critical ? tone.bg : accentBg }]}>
          <Icon name={icon} size={18} color={critical ? tone.fg : accent} />
        </View>
        <View style={styles.texts}>
          <Text style={styles.title}>{title}</Text>
          {facts.length > 0 ? <Text style={styles.meta}>{facts.join(' · ')}</Text> : null}
          {summary ? <Text style={styles.summary} numberOfLines={2}>{summary}</Text> : null}
          {badges ? <View style={styles.badges}>{badges}</View> : null}
        </View>
        <View style={styles.controls}>
          <Button
            label="View details"
            accessibilityLabel={`View details, ${openLabel}`}
            variant="secondary"
            size="sm"
            onPress={onOpen}
          />
          {actions}
        </View>
      </View>
    </Card>
  );
}

export interface RecordCardProps<T> {
  row:         T;
  /** The loved one the record belongs to. */
  patientName: string;
  onOpen:      () => void;
  /** More controls on the card, beside "View details". */
  actions?:    ReactNode;
}

export function LabResultCard({ row, patientName, onOpen, actions }: RecordCardProps<LabResultDto>) {
  const doctor = useDoctorName(row.doctorSystemId, row.doctorName);
  return (
    <RecordRow
      icon="flask-outline"
      title={row.testName}
      meta={[labTypeLabel(row.testType), formatDay(row.testDate), doctor]}
      summary={labHeadline(row)}
      critical={row.isCritical}
      badges={<StatusBadge label={statusLabel(row.status)} tone={statusTone('lab', row.status)} />}
      openLabel={`${row.testName} for ${patientName}`}
      onOpen={onOpen}
      actions={actions}
    />
  );
}

/** "X-ray, chest" from the imaging type and body part. */
export function imagingTitle(row: Pick<ImagingResultDto, 'imagingType' | 'bodyPart'>): string {
  const part = (row.bodyPart ?? '').trim();
  return part ? `${imagingTypeLabel(row.imagingType)}, ${part}` : imagingTypeLabel(row.imagingType);
}

export function ImagingResultCard({ row, patientName, onOpen, actions }: RecordCardProps<ImagingResultDto>) {
  const doctor = useDoctorName(row.doctorSystemId, row.doctorName);
  const title = imagingTitle(row);
  return (
    <RecordRow
      icon="scan-outline"
      title={title}
      meta={[formatDay(row.imageDate), doctor]}
      summary={row.impression ?? row.findings}
      critical={row.isCritical}
      badges={<StatusBadge label={statusLabel(row.status)} tone={statusTone('imaging', row.status)} />}
      openLabel={`${title} for ${patientName}`}
      onOpen={onOpen}
      actions={actions}
    />
  );
}

export function MedicalRecordCard({ row, patientName, onOpen, actions }: RecordCardProps<MedicalRecordDto>) {
  const doctor = useDoctorName(row.doctorSystemId, row.doctorName);
  return (
    <RecordRow
      icon="document-text-outline"
      title={row.title}
      meta={[formatClinicDate(row.createdAt, 'Date not recorded'), doctor]}
      summary={row.description ?? row.observations ?? row.treatmentPlan}
      badges={(
        <>
          <StatusBadge label={recordTypeLabel(row.recordType)} tone="neutral" icon={null} />
          {row.source === 'CONSULTATION'
            ? <StatusBadge label="Created from consultation" tone="info" icon="medkit-outline" />
            : null}
        </>
      )}
      openLabel={`${row.title} for ${patientName}`}
      onOpen={onOpen}
      actions={actions}
    />
  );
}

export function ConsultationCard({ row, patientName, onOpen, actions }: RecordCardProps<ConsultationDto>) {
  const doctor = useDoctorName(row.doctorSystemId);
  const day = consultationDay(row);
  const reason = (row.chiefComplaint ?? '').trim();
  const diagnosis = (row.diagnosis ?? '').trim();
  return (
    <RecordRow
      icon="medkit-outline"
      title={reason || 'Consultation'}
      meta={[day, doctor, row.appointmentType ? appointmentTypeLabel(row.appointmentType) : null]}
      summary={diagnosis ? `Diagnosis: ${diagnosis}` : null}
      badges={row.followUpRequired ? (
        <StatusBadge
          label={row.followUpDate ? `Follow-up by ${formatDay(row.followUpDate)}` : 'Follow-up needed'}
          tone="warning"
          icon="calendar-outline"
        />
      ) : null}
      openLabel={`consultation on ${day} for ${patientName}`}
      onOpen={onOpen}
      actions={actions}
    />
  );
}

const styles = StyleSheet.create({
  card:         { overflow: 'hidden' },
  critical:     { flexDirection: 'row', alignItems: 'center', gap: space.xs, paddingHorizontal: space.lg, paddingVertical: space.xs },
  criticalText: { fontSize: fontSize.xs, fontWeight: '700' },

  // Wraps: on a phone the button moves under the text instead of squeezing it.
  body:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.md, padding: space.lg },
  iconWrap: { width: 40, height: 40, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  texts:   { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, gap: space.xs },
  title:   { fontSize: fontSize.md, fontWeight: '700', color: colors.text, lineHeight: 20 },
  meta:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  summary: { fontSize: fontSize.sm, color: colors.text, lineHeight: 19 },
  badges:  { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs, marginTop: 2 },
  // On a phone the controls wrap under the text; each keeps its own width.
  controls: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, flexShrink: 0, maxWidth: '100%' },
});
