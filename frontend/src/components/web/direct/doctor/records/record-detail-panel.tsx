import { useMemo } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Button, colors, EmptyView, ErrorView, fontSize, LoadingView, NoAccessView, SidePanel, space,
} from '@/components/web/ui';
import { type DoctorMedicalRecord, toDoctorMedicalRecord, useMedicalRecord } from '@/hooks/use-doctor-medical-records';
import { RecordBody, RecordMeta, RecordTypeBadge } from './record-card';

export interface RecordDetailPanelProps {
  /** The record to show (`id` URL param). */
  recordId:       number;
  /** The signed-in doctor: Edit and Delete are offered only on records this doctor wrote. */
  doctorSystemId: string | null;
  patientNames:   ReadonlyMap<string, string>;
  onClose:        () => void;
  onEdit:         (record: DoctorMedicalRecord) => void;
  onDelete:       (record: DoctorMedicalRecord) => void;
  deleting?:      boolean;
  onOpenConsultation?: (record: DoctorMedicalRecord) => void;
}

/**
 * One record opened from a link (a notification, the correction queue, a consultation). The record is read by
 * id, so it is shown even when the list's filters would hide it; a record of another author is read-only.
 */
export function RecordDetailPanel({
  recordId, doctorSystemId, patientNames, onClose, onEdit, onDelete, deleting = false, onOpenConsultation,
}: RecordDetailPanelProps) {
  const { record: dto, loading, error, errorStatus, refetch } = useMedicalRecord(recordId);
  const record = useMemo(() => (dto ? toDoctorMedicalRecord(dto, patientNames) : null), [dto, patientNames]);
  const own = !!record && !!doctorSystemId && record.doctor_system_id === doctorSystemId;

  return (
    <SidePanel
      open
      size="lg"
      title="Medical record"
      onClose={onClose}
      busy={deleting}
      footer={record && (own || (record.consultation_id && onOpenConsultation)) ? (
        <View style={styles.footer}>
          {record.consultation_id && onOpenConsultation ? (
            <Button label="Open consultation" icon="videocam-outline" variant="ghost" onPress={() => onOpenConsultation(record)} />
          ) : null}
          {own ? (
            <>
              <Button label="Edit" accessibilityLabel={`Edit ${record.title}`} icon="create-outline" variant="secondary" onPress={() => onEdit(record)} disabled={deleting} />
              <Button label="Delete" accessibilityLabel={`Delete ${record.title}`} icon="trash-outline" variant="danger" onPress={() => onDelete(record)} loading={deleting} />
            </>
          ) : null}
        </View>
      ) : undefined}
    >
      {loading ? <LoadingView label="Loading the record" />
        : record ? (
          <>
            <RecordTypeBadge type={record.record_type} />
            <Text style={styles.title} accessibilityRole="header" {...ariaLevel(3)}>{record.title}</Text>
            <RecordMeta record={record} />
            <RecordBody record={record} />
            {!record.description && !record.observations && !record.treatment_plan && record.icd_codes.length === 0 ? (
              <Text style={styles.empty}>This record has no notes or codes.</Text>
            ) : null}
            {!own ? <Text style={styles.empty}>Only the doctor who wrote this record can change it.</Text> : null}
          </>
        ) : errorStatus === 403 ? <NoAccessView message="You don't have access to this record." />
          : errorStatus === 404 ? (
            <EmptyView icon="document-outline" title="This record is no longer available" sub="It may have been deleted." />
          ) : <ErrorView message={error ?? 'The record could not be loaded.'} onRetry={refetch} />}
    </SidePanel>
  );
}

const styles = StyleSheet.create({
  title:  { fontSize: fontSize.xl, fontWeight: '700', color: colors.text, lineHeight: 26 },
  empty:  { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  footer: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'flex-end', alignItems: 'center', gap: space.sm },
});
