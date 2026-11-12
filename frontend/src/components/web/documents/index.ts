// Documents UI shared by the patient, doctor and caregiver dashboards (api/be-documents.md section 8).

export { DocumentList, type DocumentListProps } from './document-list';
export { DocumentRow, type DocumentRowProps } from './document-row';
export { DocumentTypeFilter, type DocumentTypeFilterProps } from './document-type-filter';
export { UploadDocumentButton, type UploadDocumentButtonProps } from './upload-document-button';
export { AttachmentButton, type AttachmentButtonProps } from './attachment-button';
export {
  DependentDocumentsPanel, type DependentDocumentsPanelProps, type DocumentsDependent,
} from './dependent-documents-panel';
