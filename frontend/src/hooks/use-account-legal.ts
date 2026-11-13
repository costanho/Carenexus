import { useAction, type ActionState } from '@/hooks/use-action';
import { useResource } from '@/hooks/use-resource';
import { acceptLegalDocument, getLegalDocument, getLegalStatus } from '@/lib/account/api';
import type { AcceptLegalRequest, LegalDocType, LegalDocument, LegalStatus } from '@/lib/account/types';
import { updateSessionFlags } from '@/lib/auth/auth.storage';

export interface LegalStatusState {
  /** What the account has accepted and what is still outstanding; null until loaded. */
  status:  LegalStatus | null;
  loading: boolean;
  error:   string | null;
  refetch: () => void;
  /**
   * Accepts the current version of one document. Send the version of the text that was shown; a version that
   * is no longer current fails with code `LEGAL_ACCEPTANCE_REQUIRED` (reload and ask again).
   */
  accept:  ActionState<[AcceptLegalRequest], LegalStatus>;
}

/** Legal acceptances of the signed-in account (GET /api/users/me/legal, POST /api/users/me/legal/accept). */
export function useLegalStatus(): LegalStatusState {
  const status = useResource(getLegalStatus, [], { errorMessage: 'Your legal acceptances could not be loaded.' });
  const { refetch, setData } = status;

  const accept = useAction(async (body: AcceptLegalRequest) => {
    const next = await acceptLegalDocument(body);
    setData(next);
    // Once nothing is outstanding, clear the stored session flag so the dashboard guard does not ask again.
    if (!next.actionRequired) await updateSessionFlags({ legalActionRequired: false });
    return next;
  }, { errorMessage: 'Your acceptance could not be recorded. Please try again.' });

  return { status: status.data, loading: status.loading, error: status.error, refetch, accept };
}

export interface LegalDocumentState {
  document: LegalDocument | null;
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
}

/** The current text of one legal document (GET /api/legal/documents/{docType}); nothing is loaded for null. */
export function useLegalDocument(docType: LegalDocType | null): LegalDocumentState {
  const doc = useResource(
    () => getLegalDocument(docType as LegalDocType),
    [docType],
    { enabled: docType !== null, errorMessage: 'The document could not be loaded.' },
  );
  return { document: doc.data, loading: doc.loading, error: doc.error, refetch: doc.refetch };
}
