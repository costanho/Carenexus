// Reusable pieces of the patient record screens: the four cards and the screen frame around them.
// A card takes an `actions` render prop that receives its row, and a screen takes `cardActions` and `extraTabs`,
// so the dashboard adds attachment and correction controls, or a tab, without editing these files.

export {
  doctorByline, ExpandToggle, formatRecordDay, formatRecordMoment, humaniseKey, RecordBadges, RecordCard,
  RecordCardFooter, RecordCardHeader, RecordMeta, RecordTextBlock, useExpanded,
  type RecordCardActions, type RecordMetaItem,
} from './card-parts';
export { LabResultCard, LabResultData, LAB_TYPE_LABELS, RESULT_STATUS_LABELS, type LabResultCardProps } from './lab-result-card';
export { ImagingResultCard, IMAGING_TYPE_LABELS, type ImagingResultCardProps } from './imaging-result-card';
export { IcdCodeList, MedicalRecordCard, MEDICAL_RECORD_TYPE_LABELS, type MedicalRecordCardProps } from './medical-record-card';
export { ConsultationCard, type ConsultationCardProps } from './consultation-card';
export { FilterChips, ToggleChip, type FilterChipOption } from './filter-chips';
export { RecordStats, type RecordStat } from './record-stats';
export {
  enumParam, FocusedRecord, positiveIntParam, RecordsScreen, RefreshFailureBanner, resolveRecordTab,
  usePagedFeedback, useRecordScreenNav,
  type PagedFeedback, type RecordScreenProps, type RecordsScreenProps, type RecordTab, type ResolvedRecordTab,
} from './records-screen';
