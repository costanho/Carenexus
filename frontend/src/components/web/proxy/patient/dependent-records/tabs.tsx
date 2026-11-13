import { type ReactNode, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { SelectField, type SelectOption } from '@/components/web/ui';
import type { CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import {
  getDependentConsultation, getDependentImagingResult, getDependentLabResult, getDependentMedicalRecord,
  listDependentConsultations, listDependentImagingResults, listDependentLabResults, listDependentMedicalRecords,
  type ConsultationDto, type ImagingResultDto, type LabResultDto, type MedicalRecordDto, type MedicalRecordType,
  type ResultListFilter,
} from '@/hooks/use-dependent-records';
import { effectivePermission } from '@/lib/caregiver/permissions';
import { type ChipOption, FilterChips } from './common';
import { RECORD_TYPES, recordTypeLabel } from './format';
import { ConsultationCard, ImagingResultCard, LabResultCard, MedicalRecordCard } from './record-cards';
import {
  type AttachmentRenderer, ConsultationDetail, ImagingResultDetail, LabResultDetail, MedicalRecordDetail,
} from './record-details';
import { RecordsTab } from './records-tab';

/** Hands the attachment renderer the grant of the loved one the record belongs to. */
function withDependent(
  render: AttachmentRenderer | undefined, dependents: readonly CaregiverDependent[],
): AttachmentRenderer | undefined {
  if (!render) return undefined;
  return attachment => render({
    ...attachment,
    dependent: dependents.find(d => d.patientSystemId === attachment.patientSystemId),
  });
}

// The four built-in tabs of the caregiver's Documents & Results screen.

export interface RecordTabProps {
  /** The loved ones to list (already narrowed by the `patient` URL param). */
  dependents: readonly CaregiverDependent[];
  /** The record open in the side panel (the `id` URL param), or null. */
  openId:     number | null;
  onOpen:     (id: number | null) => void;
}

/**
 * More controls on one record card, beside "View details" (for example "Request a correction"). Receives the
 * record and the loved one it belongs to, so the control can be gated on that person's grant.
 */
export type RecordCardActions<T> = (row: T, dependent: CaregiverDependent) => ReactNode;

/** Card controls per kind of record, as the screen hands them to its tabs. */
export interface DependentRecordCardActions {
  labs?:          RecordCardActions<LabResultDto>;
  imaging?:       RecordCardActions<ImagingResultDto>;
  records?:       RecordCardActions<MedicalRecordDto>;
  consultations?: RecordCardActions<ConsultationDto>;
}

// ─── Lab and imaging results share one status filter ──────────────────────────

type ResultFilterKey = 'all' | 'critical' | 'REQUIRES_REVIEW' | 'COMPLETED';

const RESULT_FILTERS: readonly ChipOption<ResultFilterKey>[] = [
  { key: 'all',             label: 'All results' },
  { key: 'critical',        label: 'Critical' },
  { key: 'REQUIRES_REVIEW', label: 'Awaiting review' },
  { key: 'COMPLETED',       label: 'Completed' },
];

function resultQuery(filter: ResultFilterKey): ResultListFilter {
  if (filter === 'critical') return { critical: true };
  if (filter === 'all') return {};
  return { status: filter };
}

export function LabResultsTab({ dependents, openId, onOpen, renderAttachment, cardActions }: RecordTabProps & {
  renderAttachment?: AttachmentRenderer;
  cardActions?:      RecordCardActions<LabResultDto>;
}) {
  const [filter, setFilter] = useState<ResultFilterKey>('all');
  return (
    <RecordsTab<LabResultDto>
      kind="labs"
      flag="canViewLabResults"
      icon="flask-outline"
      dependents={dependents}
      filterDeps={[filter]}
      filtered={filter !== 'all'}
      fetchPageFor={(patient, page, size) => listDependentLabResults(patient, resultQuery(filter), page, size)}
      getOne={getDependentLabResult}
      idOf={row => row.resultId}
      patientOf={row => row.patientSystemId}
      renderCard={(row, patientName, open, dependent) => (
        <LabResultCard row={row} patientName={patientName} onOpen={open} actions={cardActions?.(row, dependent)} />
      )}
      renderDetail={(row, patientName) => (
        <LabResultDetail row={row} patientName={patientName} renderAttachment={withDependent(renderAttachment, dependents)} />
      )}
      filters={<FilterChips label="Filter lab results" options={RESULT_FILTERS} value={filter} onChange={setFilter} />}
      openId={openId}
      onOpen={onOpen}
    />
  );
}

export function ImagingResultsTab({ dependents, openId, onOpen, renderAttachment, cardActions }: RecordTabProps & {
  renderAttachment?: AttachmentRenderer;
  cardActions?:      RecordCardActions<ImagingResultDto>;
}) {
  const [filter, setFilter] = useState<ResultFilterKey>('all');
  return (
    <RecordsTab<ImagingResultDto>
      kind="imaging"
      flag="canViewImaging"
      icon="scan-outline"
      dependents={dependents}
      filterDeps={[filter]}
      filtered={filter !== 'all'}
      fetchPageFor={(patient, page, size) => listDependentImagingResults(patient, resultQuery(filter), page, size)}
      getOne={getDependentImagingResult}
      idOf={row => row.imagingId}
      patientOf={row => row.patientSystemId}
      renderCard={(row, patientName, open, dependent) => (
        <ImagingResultCard row={row} patientName={patientName} onOpen={open} actions={cardActions?.(row, dependent)} />
      )}
      renderDetail={(row, patientName) => (
        <ImagingResultDetail row={row} patientName={patientName} renderAttachment={withDependent(renderAttachment, dependents)} />
      )}
      filters={<FilterChips label="Filter imaging results" options={RESULT_FILTERS} value={filter} onChange={setFilter} />}
      openId={openId}
      onOpen={onOpen}
    />
  );
}

// ─── Medical records ──────────────────────────────────────────────────────────

const ALL_TYPES = 'ALL';

const RECORD_TYPE_OPTIONS: SelectOption[] = [
  { value: ALL_TYPES, label: 'All record types' },
  ...RECORD_TYPES.map(type => ({ value: type as string, label: recordTypeLabel(type) })),
];

export function MedicalRecordsTab({ dependents, openId, onOpen, renderAttachment, cardActions }: RecordTabProps & {
  renderAttachment?: AttachmentRenderer;
  cardActions?:      RecordCardActions<MedicalRecordDto>;
}) {
  const [type, setType] = useState<string>(ALL_TYPES);
  const selected = type === ALL_TYPES ? undefined : (type as MedicalRecordType);
  return (
    <RecordsTab<MedicalRecordDto>
      kind="records"
      flag="canViewRecords"
      icon="document-text-outline"
      dependents={dependents}
      filterDeps={[type]}
      filtered={selected !== undefined}
      fetchPageFor={(patient, page, size) => listDependentMedicalRecords(patient, { type: selected }, page, size)}
      getOne={getDependentMedicalRecord}
      idOf={row => row.recordId}
      patientOf={row => row.patientSystemId}
      renderCard={(row, patientName, open, dependent) => (
        <MedicalRecordCard row={row} patientName={patientName} onOpen={open} actions={cardActions?.(row, dependent)} />
      )}
      renderDetail={(row, patientName) => (
        <MedicalRecordDetail row={row} patientName={patientName} renderAttachment={withDependent(renderAttachment, dependents)} />
      )}
      filters={(
        <View style={styles.select}>
          <SelectField label="Record type" value={type} onChange={setType} options={RECORD_TYPE_OPTIONS} />
        </View>
      )}
      openId={openId}
      onOpen={onOpen}
    />
  );
}

// ─── Consultations ────────────────────────────────────────────────────────────

export function ConsultationsTab({ dependents, openId, onOpen, onOpenRecord, cardActions }: RecordTabProps & {
  /** Opens a consultation's medical record (Medical records tab). */
  onOpenRecord: (recordId: number) => void;
  cardActions?: RecordCardActions<ConsultationDto>;
}) {
  return (
    <RecordsTab<ConsultationDto>
      kind="consultations"
      flag="canViewConsultations"
      icon="medkit-outline"
      dependents={dependents}
      filterDeps={[]}
      fetchPageFor={listDependentConsultations}
      getOne={getDependentConsultation}
      idOf={row => row.consultationId}
      patientOf={row => row.patientSystemId}
      renderCard={(row, patientName, open, dependent) => (
        <ConsultationCard row={row} patientName={patientName} onOpen={open} actions={cardActions?.(row, dependent)} />
      )}
      renderDetail={(row, patientName) => {
        // The record link is offered only when records are shared for this loved one; the server still decides.
        const dependent = dependents.find(d => d.patientSystemId === row.patientSystemId);
        const recordsShared = effectivePermission(dependent, 'canViewRecords');
        return (
          <ConsultationDetail
            row={row}
            patientName={patientName}
            onOpenRecord={recordsShared ? onOpenRecord : undefined}
          />
        );
      }}
      openId={openId}
      onOpen={onOpen}
    />
  );
}

const styles = StyleSheet.create({
  select: { width: '100%', maxWidth: 320 },
});
