import { useResource, type ResourceState } from '@/hooks/use-resource';
import { getCurrentLegal, getLegalDocument } from '@/lib/admin/ops-api';
import type { LegalCurrent, LegalDocType, LegalDocument } from '@/lib/admin/ops-types';

/** The version in force of the Terms of Service and the Privacy Policy (without the text). */
export function useLegalCurrent(): ResourceState<LegalCurrent> {
  return useResource<LegalCurrent>(() => getCurrentLegal(), [], {
    errorMessage: 'The legal documents could not be loaded.',
  });
}

/** The text in force of one document. */
export function useLegalDocument(docType: LegalDocType): ResourceState<LegalDocument> {
  return useResource<LegalDocument>(() => getLegalDocument(docType), [docType], {
    errorMessage: 'The text of the document could not be loaded.',
  });
}
