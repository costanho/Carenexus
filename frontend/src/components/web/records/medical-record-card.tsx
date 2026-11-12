import { StyleSheet, Text, View } from 'react-native';
import {
  Button, colors, fontSize, type IoniconsName, radius, space, StatusBadge,
} from '@/components/web/ui';
import type { MedicalRecord, MedicalRecordType } from '@/hooks/use-medical-records';
import {
  doctorByline, ExpandToggle, formatRecordMoment, RecordBadges, RecordCard, type RecordCardActions,
  RecordCardFooter, RecordCardHeader, RecordTextBlock, useExpanded,
} from './card-parts';

export const MEDICAL_RECORD_TYPE_LABELS: Record<MedicalRecordType, { label: string; icon: IoniconsName }> = {
  CONSULT:      { label: 'Consultation', icon: 'medkit-outline' },
  LAB:          { label: 'Lab result',   icon: 'flask-outline' },
  IMAGING:      { label: 'Imaging',      icon: 'image-outline' },
  PRESCRIPTION: { label: 'Prescription', icon: 'document-outline' },
  REFERRAL:     { label: 'Referral',     icon: 'git-compare-outline' },
  DISCHARGE:    { label: 'Discharge',    icon: 'exit-outline' },
  NOTE:         { label: 'Note',         icon: 'create-outline' },
  OPERATION:    { label: 'Operation',    icon: 'fitness-outline' },
};

/** A description longer than this is cut to a preview until the card is opened. */
const LONG_DESCRIPTION = 140;

/** The ICD-10 codes of a record. A code outside the bundled catalogue is marked "unverified" in words. */
export function IcdCodeList({ codes }: { codes: NonNullable<MedicalRecord['icd_codes']> }) {
  return (
    <View style={styles.icdBlock}>
      <Text style={styles.icdLabel}>ICD-10 codes</Text>
      <View style={styles.icdRow} role="list">
        {codes.map((icd, index) => (
          <View key={`${icd.code}-${index}`} style={styles.icdChip} role="listitem">
            <Text style={styles.icdText}>
              <Text style={styles.icdCode}>{icd.code}</Text>
              {icd.description ? `  ${icd.description}` : ''}
              {icd.verified === false ? '  (unverified)' : ''}
            </Text>
          </View>
        ))}
      </View>
    </View>
  );
}

export interface MedicalRecordCardProps {
  item:                MedicalRecord;
  /** Extra controls for this record (attachment, correction request), drawn in the card footer. */
  actions?:            RecordCardActions<MedicalRecord>;
  /** Open the details at once (a record opened from a link). */
  defaultExpanded?:    boolean;
  /** Opens the consultation the record belongs to; the "View consultation" button shows only when this is set. */
  onOpenConsultation?: (consultationId: number) => void;
}

/** One medical record: type, author and date, the description, and observations, treatment plan and ICD codes on demand. */
export function MedicalRecordCard({ item, actions, defaultExpanded = false, onOpenConsultation }: MedicalRecordCardProps) {
  const [expanded, toggle] = useExpanded(defaultExpanded);
  const type = MEDICAL_RECORD_TYPE_LABELS[item.record_type] ?? MEDICAL_RECORD_TYPE_LABELS.NOTE;
  const title = item.title || type.label;
  const codes = item.icd_codes && item.icd_codes.length > 0 ? item.icd_codes : null;
  const hasDetail = !!(item.observations || item.treatment_plan || codes)
    || (!!item.description && item.description.length > LONG_DESCRIPTION);
  const consultationId = item.consultation_id;
  const extra = actions?.(item);

  return (
    <RecordCard>
      <RecordCardHeader
        icon={type.icon}
        title={title}
        byline={doctorByline(item.doctor_name, item.doctor_specialization)}
        aside={<Text style={styles.date}>{formatRecordMoment(item.created_at)}</Text>}
      />

      <RecordBadges>
        <StatusBadge label={type.label} tone="neutral" icon={type.icon} />
        {item.source === 'CONSULTATION' ? (
          <StatusBadge label="Created from consultation" tone="info" icon="link-outline" />
        ) : null}
        {item.edited ? <StatusBadge label="Edited" tone="neutral" icon="pencil-outline" /> : null}
      </RecordBadges>

      {item.description ? (
        <RecordTextBlock
          label="Description"
          icon="document-text-outline"
          text={item.description}
          lines={expanded ? undefined : 2}
          tinted
        />
      ) : null}

      {expanded ? (
        <View style={styles.sections}>
          {item.observations ? <RecordTextBlock label="Observations" icon="eye-outline" text={item.observations} /> : null}
          {item.treatment_plan ? <RecordTextBlock label="Treatment plan" icon="list-outline" text={item.treatment_plan} /> : null}
          {codes ? <IcdCodeList codes={codes} /> : null}
        </View>
      ) : null}

      <RecordCardFooter
        toggle={hasDetail ? (
          <ExpandToggle
            expanded={expanded}
            onToggle={toggle}
            showLabel="Show details"
            hideLabel="Hide details"
            subject={title}
          />
        ) : undefined}
      >
        {(consultationId !== null && onOpenConsultation) || extra ? (
          <>
            {consultationId !== null && onOpenConsultation ? (
              <Button
                label="View consultation"
                icon="medkit-outline"
                variant="ghost"
                size="sm"
                accessibilityLabel={`View consultation for ${title}`}
                onPress={() => onOpenConsultation(consultationId)}
              />
            ) : null}
            {extra}
          </>
        ) : null}
      </RecordCardFooter>
    </RecordCard>
  );
}

const styles = StyleSheet.create({
  date:     { fontSize: fontSize.sm, color: colors.textMuted },
  sections: { gap: space.md },

  icdBlock: { gap: space.xs },
  icdLabel: { fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted, textTransform: 'uppercase', letterSpacing: 0.5 },
  icdRow:   { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  icdChip:  { maxWidth: '100%', backgroundColor: colors.bgSubtle, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, paddingHorizontal: space.sm, paddingVertical: space.xs },
  icdText:  { fontSize: fontSize.sm, color: colors.text, lineHeight: 19 },
  icdCode:  { fontWeight: '700' },
});
