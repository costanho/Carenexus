import { z } from 'zod';
import { DOCUMENT_DESCRIPTION_MAX, DOCUMENT_TITLE_MAX, isDocumentType } from './types';

// The text fields of the upload form, mirroring POST /api/documents (api/be-documents.md section 2.1):
// title not blank and at most 300 characters, description at most 2000, documentType one of the eight types.
// The file itself is checked separately (`fileProblem` in types.ts) because it is not a form value.

export const uploadDocumentSchema = z.object({
  title: z.string()
    .max(DOCUMENT_TITLE_MAX, `Use ${DOCUMENT_TITLE_MAX} characters or fewer`)
    .refine(value => value.trim().length > 0, 'Enter a title'),
  // Kept a plain string (no narrowing), so the form's input and output types are the same.
  documentType: z.string().refine((value): boolean => isDocumentType(value), 'Choose a document type'),
  description: z.string().max(DOCUMENT_DESCRIPTION_MAX, `Use ${DOCUMENT_DESCRIPTION_MAX} characters or fewer`),
});

export type UploadDocumentFormValues = z.infer<typeof uploadDocumentSchema>;
