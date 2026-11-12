import { type ReactNode, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Button, Card, colors, fontSize, Icon, radius, space, StatusBadge,
} from '@/components/web/ui';
import { type DoctorMedicalRecord, isRecordEdited } from '@/hooks/use-doctor-medical-records';
import { formatClinicDateTime } from '@/lib/time/clinic';
import { DisclosureButton } from '../disclosure-button';
import { recordTypeMeta } from './record-meta';

/** The coloured type label of a record ("Lab result"). The words carry the meaning; the colour only helps scanning. */
export function RecordTypeBadge({ type }: { type: string }) {
  const meta = recordTypeMeta(type);
  return (
    <View style={[styles.typeBadge, { backgroundColor: meta.bg, borderColor: meta.border }]}>
      <Icon name={meta.icon} size={12} color={meta.fg} />
      <Text style={[styles.typeBadgeText, { color: meta.fg }]}>{meta.label}</Text>
    </View>
  );
}

function Section({ label, children }: { label: string; children: ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionLabel}>{label}</Text>
      {children}
    </View>
  );
}

/**
 * Everything a record holds besides its heading: description, observations, treatment plan and ICD-10 codes
 * (a code outside the catalogue is marked "Unverified"). `collapsed` shows only the start of the description.
 */
export function RecordBody({ record, collapsed = false }: { record: DoctorMedicalRecord; collapsed?: boolean }) {
  return (
    <>
      {record.description ? (
        <Section label="Description">
          <Text style={styles.text} numberOfLines={collapsed ? 3 : undefined}>{record.description}</Text>
        </Section>
      ) : null}
      {collapsed ? null : (
        <>
          {record.observations ? (
            <Section label="Observations"><Text style={styles.text}>{record.observations}</Text></Section>
          ) : null}
          {record.treatment_plan ? (
            <Section label="Treatment plan"><Text style={styles.text}>{record.treatment_plan}</Text></Section>
          ) : null}
          {record.icd_codes.length > 0 ? (
            <Section label="ICD-10 codes">
              <View style={styles.codes} role="list">
                {record.icd_codes.map((code, index) => (
                  <View key={`${code.code}-${index}`} role="listitem" style={styles.code}>
                    <Text style={styles.codeId}>{code.code}</Text>
                    {code.description ? <Text style={styles.codeText}>{code.description}</Text> : null}
                    {code.verified === false ? <StatusBadge label="Unverified" tone="warning" icon="help-circle-outline" /> : null}
                  </View>
                ))}
              </View>
            </Section>
          ) : null}
        </>
      )}
    </>
  );
}

/** The labels under a record's title: who it is about, who wrote it, when, and how it came to exist. */
export function RecordMeta({ record }: { record: DoctorMedicalRecord }) {
  return (
    <View style={styles.meta}>
      <Text style={styles.metaText}>{`Patient: ${record.patient_name}`}</Text>
      <Text style={styles.metaText}>{`Author: ${record.doctor_name}`}</Text>
      <Text style={styles.metaText}>{formatClinicDateTime(record.created_at, 'Date not recorded')}</Text>
      {record.source === 'CONSULTATION' ? (
        <StatusBadge label="Created from consultation" tone="info" icon="videocam-outline" />
      ) : null}
      {isRecordEdited(record) ? <StatusBadge label="Edited" tone="neutral" icon="pencil-outline" /> : null}
    </View>
  );
}

export interface RecordCardProps {
  record:    DoctorMedicalRecord;
  /** The signed-in doctor wrote the record: Edit and Delete are offered. */
  canEdit:   boolean;
  onEdit:    (record: DoctorMedicalRecord) => void;
  onDelete:  (record: DoctorMedicalRecord) => void;
  /** This record is being deleted. */
  deleting?: boolean;
  /** Opens the consultation the record is linked to; no link is shown without it. */
  onOpenConsultation?: (record: DoctorMedicalRecord) => void;
}

/** One record in the doctor's list, collapsed to its heading and the start of the description. */
export function RecordCard({ record, canEdit, onEdit, onDelete, deleting = false, onOpenConsultation }: RecordCardProps) {
  const [expanded, setExpanded] = useState(false);
  const meta = recordTypeMeta(record.record_type);
  const hasMore = !!(record.observations || record.treatment_plan || record.icd_codes.length > 0
    || (record.description && record.description.length > 180));

  return (
    <Card style={[styles.card, { borderLeftColor: meta.fg }]}>
      <View style={styles.header}>
        <RecordTypeBadge type={record.record_type} />
        <Text style={styles.title} accessibilityRole="header" {...ariaLevel(2)}>{record.title}</Text>
      </View>
      <RecordMeta record={record} />
      <RecordBody record={record} collapsed={!expanded} />

      <View style={styles.actions}>
        {hasMore ? (
          <DisclosureButton
            label={expanded ? 'Show less' : 'View full record'}
            accessibilityLabel={`${expanded ? 'Show less' : 'View full record'}: ${record.title}`}
            expanded={expanded}
            onPress={() => setExpanded(e => !e)}
          />
        ) : null}
        {record.consultation_id && onOpenConsultation ? (
          <Button
            label="Open consultation"
            accessibilityLabel={`Open consultation of ${record.title}`}
            icon="videocam-outline"
            variant="ghost"
            size="sm"
            onPress={() => onOpenConsultation(record)}
          />
        ) : null}
        {canEdit ? (
          <>
            <Button
              label="Edit"
              accessibilityLabel={`Edit ${record.title}`}
              icon="create-outline"
              variant="secondary"
              size="sm"
              onPress={() => onEdit(record)}
              disabled={deleting}
            />
            <Button
              label="Delete"
              accessibilityLabel={`Delete ${record.title}`}
              icon="trash-outline"
              variant="danger"
              size="sm"
              onPress={() => onDelete(record)}
              loading={deleting}
            />
          </>
        ) : null}
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  card:   { gap: space.md, borderLeftWidth: 4 },
  header: { gap: space.sm, minWidth: 0 },
  title:  { fontSize: fontSize.lg, fontWeight: '700', color: colors.text, lineHeight: 22 },

  typeBadge: {
    flexDirection: 'row', alignItems: 'center', gap: space.xs, alignSelf: 'flex-start', maxWidth: '100%',
    paddingHorizontal: space.sm, paddingVertical: 3, borderWidth: 1, borderRadius: radius.pill,
  },
  typeBadgeText: { fontSize: fontSize.xs, fontWeight: '700' },

  meta:     { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', columnGap: space.lg, rowGap: space.xs },
  metaText: { fontSize: fontSize.sm, color: colors.textMuted },

  section:      { gap: space.xs, minWidth: 0 },
  sectionLabel: { fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted },
  text:         { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },

  codes: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  code: {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, maxWidth: '100%',
    paddingHorizontal: space.sm, paddingVertical: space.xs,
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.bg,
  },
  codeId:   { fontSize: fontSize.sm, fontWeight: '700', color: colors.text },
  codeText: { flexShrink: 1, fontSize: fontSize.sm, color: colors.textMuted },

  actions: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
});
