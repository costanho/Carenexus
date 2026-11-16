import type { CaregiverFlag } from '@/lib/caregiver/permissions';

// Documents (api/be-documents.md). The typings of section 8.1 are copied as written; the labels and helpers
// below them are what the web components share.

export type DocumentType =
  | 'LAB_REPORT' | 'IMAGING' | 'PRESCRIPTION' | 'REFERRAL' | 'CONSENT' | 'DISCHARGE' | 'INSURANCE' | 'OTHER';
export type DocumentLinkType =
  | 'LAB_RESULT' | 'IMAGING_RESULT' | 'MEDICAL_RECORD' | 'CONSULTATION' | 'INSURANCE_CLAIM';
export type DocumentContentType = 'application/pdf' | 'image/jpeg' | 'image/png' | 'application/dicom';
export type UploaderRole = 'PATIENT' | 'DOCTOR' | 'CAREGIVER' | 'ADMIN';

export interface DocumentDto {
  documentId: number;
  patientSystemId: string;
  uploadedBySystemId: string;
  uploadedByName: string;
  uploadedByRole: UploaderRole;
  documentType: DocumentType;
  title: string;
  description: string | null;
  originalFilename: string;
  contentType: DocumentContentType;
  sizeBytes: number;
  linkType: DocumentLinkType | null;
  linkId: number | null;
  uploadedAt: string;          // clinic wall-clock, no offset
  canDelete: boolean;
}

export interface DocumentListQuery {
  type?: DocumentType; linkType?: DocumentLinkType; linkId?: number; page?: number; size?: number;
}

export const DOCUMENT_MAX_BYTES = 10 * 1024 * 1024;            // 10485760
export const DOCUMENT_ACCEPT =
  '.pdf,.jpg,.jpeg,.png,.dcm,.dicom,application/pdf,image/jpeg,image/png,application/dicom';
export const DOCUMENT_TITLE_MAX = 300;
export const DOCUMENT_DESCRIPTION_MAX = 2000;

/** documentType the server requires for a link type (400 otherwise). */
export const REQUIRED_TYPE_FOR_LINK: Partial<Record<DocumentLinkType, DocumentType>> = {
  LAB_RESULT: 'LAB_REPORT', IMAGING_RESULT: 'IMAGING',
};
/** documentType to preselect for a link type the server does not constrain. */
export const SUGGESTED_TYPE_FOR_LINK: Partial<Record<DocumentLinkType, DocumentType>> = {
  INSURANCE_CLAIM: 'INSURANCE',
};

// POST   /api/documents                            multipart -> DocumentDto (201, or 200 on replay)
// GET    /api/documents/patient/{patientSystemId}  DocumentListQuery -> PageResponse<DocumentDto>
// GET    /api/documents/{id}                       -> DocumentDto
// GET    /api/documents/{id}/download              -> Blob
// DELETE /api/documents/{id}                       -> 204

/** The parts of one upload (section 2.1). Optional parts that are absent are not sent. */
export interface UploadDocumentInput {
  patientSystemId: string;
  documentType:    DocumentType;
  title:           string;
  description?:    string | null;
  linkType?:       DocumentLinkType | null;
  /** Only together with `linkType`; omit while the linked item does not exist yet. */
  linkId?:         number | null;
  file:            Blob;
  /** File name sent with the `file` part. Defaults to the name of `file` when it is a `File`. */
  fileName?:       string;
}

// ─── Labels ───────────────────────────────────────────────────────────────────

/** Every document type, in the order the selectors list them. */
export const DOCUMENT_TYPES: readonly DocumentType[] = [
  'LAB_REPORT', 'IMAGING', 'PRESCRIPTION', 'REFERRAL', 'CONSENT', 'DISCHARGE', 'INSURANCE', 'OTHER',
];

export const DOCUMENT_TYPE_LABELS: Record<DocumentType, string> = {
  LAB_REPORT:   'Lab report',
  IMAGING:      'Imaging',
  PRESCRIPTION: 'Prescription',
  REFERRAL:     'Referral letter',
  CONSENT:      'Consent form',
  DISCHARGE:    'Discharge summary',
  INSURANCE:    'Insurance',
  OTHER:        'Other',
};

export const DOCUMENT_LINK_LABELS: Record<DocumentLinkType, string> = {
  LAB_RESULT:      'Lab result',
  IMAGING_RESULT:  'Imaging result',
  MEDICAL_RECORD:  'Medical record',
  CONSULTATION:    'Consultation',
  INSURANCE_CLAIM: 'Insurance claim',
};

export const CONTENT_TYPE_LABELS: Record<DocumentContentType, string> = {
  'application/pdf':   'PDF',
  'image/jpeg':        'JPEG image',
  'image/png':         'PNG image',
  'application/dicom': 'DICOM',
};

const UPLOADER_ROLE_LABELS: Record<UploaderRole, string> = {
  PATIENT: 'Patient', DOCTOR: 'Doctor', CAREGIVER: 'Caregiver', ADMIN: 'Administrator',
};

/** A document type in words; an unknown value from a newer server is shown as sent. */
export function documentTypeLabel(type: string | null | undefined): string {
  const key = (type ?? '').trim().toUpperCase() as DocumentType;
  return DOCUMENT_TYPE_LABELS[key] ?? (type?.trim() || 'Document');
}

export function documentLinkLabel(linkType: string | null | undefined): string | null {
  const key = (linkType ?? '').trim().toUpperCase() as DocumentLinkType;
  return DOCUMENT_LINK_LABELS[key] ?? (linkType?.trim() || null);
}

export function contentTypeLabel(contentType: string | null | undefined): string {
  const key = (contentType ?? '').trim().toLowerCase() as DocumentContentType;
  return CONTENT_TYPE_LABELS[key] ?? 'File';
}

export function uploaderRoleLabel(role: string | null | undefined): string | null {
  const key = (role ?? '').trim().toUpperCase() as UploaderRole;
  return UPLOADER_ROLE_LABELS[key] ?? null;
}

export function isDocumentType(value: string | null | undefined): value is DocumentType {
  return !!value && (DOCUMENT_TYPES as readonly string[]).includes(value);
}

/** "248 KB", "1.4 MB". An unusable value gives ''. */
export function formatFileSize(bytes: number | null | undefined): string {
  if (typeof bytes !== 'number' || !Number.isFinite(bytes) || bytes < 0) return '';
  if (bytes < 1024) return `${bytes} ${bytes === 1 ? 'byte' : 'bytes'}`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${Math.round(kb)} KB`;
  const mb = kb / 1024;
  return `${mb >= 10 ? Math.round(mb) : Math.round(mb * 10) / 10} MB`;
}

// ─── Caregiver rules (section 1.2, decision D1) ───────────────────────────────

/** The grant flag a caregiver needs to read a document of this type. */
export function caregiverReadFlag(type: DocumentType): CaregiverFlag {
  if (type === 'LAB_REPORT') return 'canViewLabResults';
  if (type === 'IMAGING') return 'canViewImaging';
  return 'canViewRecords';
}

/** The grant flag a caregiver needs to upload or delete a document of any type (MANAGE_DOCUMENTS). */
export const CAREGIVER_MANAGE_DOCUMENTS_FLAG: CaregiverFlag = 'canManageCarePlan';

// ─── Client-side file hints (section 8.2) ─────────────────────────────────────

export const FILE_TOO_LARGE_MESSAGE = 'This file is larger than 10 MB.';
export const FILE_EMPTY_MESSAGE = 'The file is empty';
export const FILE_TYPE_MESSAGE = 'This file type is not allowed. Upload a PDF, JPEG, PNG or DICOM file.';
/** Shown under the file picker. */
export const DOCUMENT_FILE_HINT = 'PDF, JPEG, PNG or DICOM, up to 10 MB.';

const ALLOWED_EXTENSIONS = ['.pdf', '.jpg', '.jpeg', '.png', '.dcm', '.dicom'];
const ALLOWED_MIME_TYPES: readonly string[] = ['application/pdf', 'image/jpeg', 'image/png', 'application/dicom'];

/**
 * A reason to refuse a chosen file before sending it, or null. These are hints only: the server decides from
 * the file's bytes, so a file that passes here can still come back with FILE_REJECTED.
 */
export function fileProblem(file: { name?: string; size: number; type?: string }): string | null {
  if (file.size <= 0) return FILE_EMPTY_MESSAGE;
  if (file.size > DOCUMENT_MAX_BYTES) return FILE_TOO_LARGE_MESSAGE;
  const name = (file.name ?? '').toLowerCase();
  const type = (file.type ?? '').toLowerCase();
  const byExtension = ALLOWED_EXTENSIONS.some(ext => name.endsWith(ext));
  const byType = ALLOWED_MIME_TYPES.includes(type);
  // Browsers report no type for some files (DICOM in particular); a file with neither a known extension nor a
  // known type is refused, anything else is left to the server.
  return byExtension || byType ? null : FILE_TYPE_MESSAGE;
}

/** A title suggestion from a file name: the name without its extension, with separators as spaces. */
export function titleFromFileName(name: string | null | undefined): string {
  const base = (name ?? '').replace(/^.*[\\/]/, '').replace(/\.[A-Za-z0-9]{1,6}$/, '');
  return base.replace(/[_]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, DOCUMENT_TITLE_MAX);
}
