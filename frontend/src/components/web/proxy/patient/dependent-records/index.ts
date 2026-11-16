// Caregiver views of a loved one's records: the tabs of Documents & Results, their cards and detail views, and
// the per-loved-one cards of the caregiver home.

export {
  AccessNote, accessNoteText, DependentFilter, FilterChips, isAccessStatus, scopeDependents,
  type ChipOption, type DependentScope,
} from './common';
export { DependentHighlights, type DependentHighlightsProps } from './dashboard-cards';
export { dependentName, KIND_TEXT, parseId, RECORD_KINDS, type RecordKind } from './format';
export { ConsultationCard, ImagingResultCard, imagingTitle, LabResultCard, MedicalRecordCard } from './record-cards';
export {
  ConsultationDetail, ImagingResultDetail, LabResultDetail, MedicalRecordDetail,
  type AttachmentRenderer, type RecordAttachment,
} from './record-details';
export { RecordsTab, type RecordsTabProps } from './records-tab';
export {
  ConsultationsTab, ImagingResultsTab, LabResultsTab, MedicalRecordsTab, type DependentRecordCardActions,
  type RecordCardActions, type RecordTabProps,
} from './tabs';
